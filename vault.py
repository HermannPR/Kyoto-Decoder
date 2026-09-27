#!/usr/bin/env python3
"""Boveda local de archivos sobre foto-codec.

Por fuera es un gestor de archivos normal (agregar, listar, buscar, extraer,
borrar); por dentro cada archivo se guarda como una o varias imagenes PNG usando
`fotocodec.py`. Todo es LOCAL: nada se sube a ningun servicio en la nube.

Estructura de una boveda (una carpeta cualquiera). Las imagenes se organizan en
subcarpetas para que sea comodo revisarlas o subirlas a mano: por fecha
(AAAA/MM, el valor por defecto) o por tipo/extension (--por tipo):

    boveda/
      index.json                     indice legible con los metadatos de cada entrada
      datos/2024/06/<sello>_<id>/*.png   organizacion por fecha (por defecto)
      datos/pdf/<sello>_<id>/*.png       organizacion por tipo (--por tipo)

Las bovedas creadas por versiones anteriores (estructura plana
`datos/<id>/*.png`) se siguen leyendo sin migrar nada: `extraer`/`borrar`
encuentran la carpeta de cada entrada aunque no tenga organizacion nueva o se
haya movido a mano.

El cifrado es OPCIONAL y usa solo la libreria estandar (PBKDF2-HMAC-SHA256 para
derivar la clave y un cifrado de flujo tipo CTR con HMAC-SHA256, ver `_cifrar`).

Uso:
  vault.py agregar  RUTA_BOVEDA ARCHIVO [--clave CLAVE] [--max-mb N] [--por fecha|tipo]
  vault.py listar   RUTA_BOVEDA
  vault.py buscar   RUTA_BOVEDA TEXTO
  vault.py extraer  RUTA_BOVEDA (ID|NOMBRE) [-o CARPETA] [--clave CLAVE]
  vault.py borrar   RUTA_BOVEDA (ID|NOMBRE)
  vault.py exportar RUTA_BOVEDA CARPETA_DESTINO [--por fecha|tipo] [--filtro TEXTO]
"""
import argparse, datetime, getpass, hashlib, hmac, json, os, re, shutil, struct, sys, tempfile, uuid

import fotocodec as fc

NOMBRE_INDICE = "index.json"
NOMBRE_DATOS = "datos"
ORGANIZACION_DEFECTO = "fecha"

# ---------- cifrado opcional (solo libreria estandar) ----------
#
# Formato del bloque cifrado (encrypt-then-MAC, todo concatenado):
#
#   MAGIA_ENC (9 bytes)  b"VAULTENC1"
#   salt      (16 bytes) aleatorio, uno por archivo
#   vueltas   (4 bytes)  uint32 big-endian, iteraciones de PBKDF2
#   tag       (32 bytes) HMAC-SHA256(mac_key, MAGIA_ENC+salt+vueltas+cifrado)
#   cifrado   (resto)    texto_claro XOR flujo
#
# La clave de 64 bytes se deriva con PBKDF2-HMAC-SHA256(clave, salt, vueltas);
# los primeros 32 bytes cifran (enc_key) y los ultimos 32 autentican (mac_key).
# El flujo es HMAC-SHA256(enc_key, contador_be8) por cada bloque de 32 bytes.
# Como el salt (y por tanto enc_key) es unico por archivo, el contador puede
# empezar en 0 sin reutilizar nunca el mismo flujo.

MAGIA_ENC = b"VAULTENC1"
VUELTAS = 200_000


def _flujo_xor(enc_key, datos):
    salida = bytearray()
    contador = 0
    while len(salida) < len(datos):
        bloque = hmac.new(enc_key, struct.pack(">Q", contador), hashlib.sha256).digest()
        salida += bloque
        contador += 1
    salida = salida[:len(datos)]
    return bytes(b ^ k for b, k in zip(datos, salida))


def _cifrar(datos, clave):
    salt = os.urandom(16)
    derivada = hashlib.pbkdf2_hmac("sha256", clave.encode("utf-8"), salt, VUELTAS, dklen=64)
    enc_key, mac_key = derivada[:32], derivada[32:]
    cifrado = _flujo_xor(enc_key, datos)
    cabecera = MAGIA_ENC + salt + struct.pack(">I", VUELTAS)
    tag = hmac.new(mac_key, cabecera + cifrado, hashlib.sha256).digest()
    return cabecera + tag + cifrado


def _descifrar(blob, clave):
    if blob[:len(MAGIA_ENC)] != MAGIA_ENC:
        raise ValueError("el bloque no esta cifrado por la boveda")
    salt = blob[9:25]
    vueltas = struct.unpack(">I", blob[25:29])[0]
    tag = blob[29:61]
    cifrado = blob[61:]
    derivada = hashlib.pbkdf2_hmac("sha256", clave.encode("utf-8"), salt, vueltas, dklen=64)
    enc_key, mac_key = derivada[:32], derivada[32:]
    cabecera = blob[:29]
    esperado = hmac.new(mac_key, cabecera + cifrado, hashlib.sha256).digest()
    if not hmac.compare_digest(tag, esperado):
        raise ValueError("clave incorrecta o datos alterados")
    return _flujo_xor(enc_key, cifrado)


def esta_cifrado(blob):
    return blob[:len(MAGIA_ENC)] == MAGIA_ENC


# ---------- indice ----------

def _ruta_indice(boveda):
    return os.path.join(boveda, NOMBRE_INDICE)


def cargar_indice(boveda):
    ruta = _ruta_indice(boveda)
    if not os.path.exists(ruta):
        return {"version": 1, "entradas": {}}
    with open(ruta, "r", encoding="utf-8") as f:
        return json.load(f)


def guardar_indice(boveda, indice):
    os.makedirs(boveda, exist_ok=True)
    tmp = _ruta_indice(boveda) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(indice, f, ensure_ascii=False, indent=2, sort_keys=True)
    os.replace(tmp, _ruta_indice(boveda))


# ---------- organizacion por carpetas ----------
#
# Cada entrada guarda su propia carpeta de datos en "ruta_datos" (relativa a la
# boveda, con "/" como separador para que el index.json sea igual en cualquier
# sistema operativo). Las entradas viejas no tienen ese campo: se siguen
# encontrando por la ruta plana original o, si tampoco existe, buscandolas de
# forma recursiva dentro de datos/.

def _slug(nombre):
    base, _ = os.path.splitext(nombre)
    s = re.sub(r"[^A-Za-z0-9_-]+", "-", base).strip("-").lower()
    return s or "archivo"


def _subcarpeta(organizar_por, tipo, momento):
    if organizar_por == "tipo":
        return [tipo or "sin_tipo"]
    return [momento.strftime("%Y"), momento.strftime("%m")]  # "fecha" (por defecto)


def _nombre_carpeta_entrada(entrada_id, nombre, momento):
    # Sello cronologico al frente para que las carpetas queden ordenables por
    # fecha aunque la organizacion elegida sea "tipo".
    sello = momento.strftime("%Y%m%d-%H%M%S")
    return f"{sello}_{_slug(nombre)}_{entrada_id}"


def _ruta_relativa_datos(entrada_id, nombre, tipo, momento, organizar_por):
    partes = [NOMBRE_DATOS] + _subcarpeta(organizar_por, tipo, momento) + \
        [_nombre_carpeta_entrada(entrada_id, nombre, momento)]
    return "/".join(partes)


def _ruta_fs(boveda, ruta_relativa):
    return os.path.join(boveda, *ruta_relativa.split("/"))


def _fecha_de_entrada(entrada):
    try:
        return datetime.datetime.fromisoformat(entrada["fecha"])
    except (KeyError, ValueError):
        return datetime.datetime.now()


def _resolver_carpeta_datos(boveda, eid, entrada):
    """Encuentra la carpeta con los PNG de una entrada, sea cual sea su
    organizacion (nueva por carpetas, plana de bovedas viejas, o movida a mano)."""
    ruta_rel = entrada.get("ruta_datos")
    if ruta_rel:
        candidato = _ruta_fs(boveda, ruta_rel)
        if os.path.isdir(candidato):
            return candidato

    plano = os.path.join(boveda, NOMBRE_DATOS, eid)  # boveda antigua (sin subcarpetas)
    if os.path.isdir(plano):
        return plano

    base = os.path.join(boveda, NOMBRE_DATOS)  # busqueda recursiva de respaldo
    if os.path.isdir(base):
        for raiz, carpetas, _ in os.walk(base):
            for c in carpetas:
                if c == eid or c.endswith("_" + eid):
                    return os.path.join(raiz, c)

    raise FileNotFoundError(f"no encuentro los datos de la entrada {eid}")


def _resolver(indice, id_o_nombre):
    """Devuelve (id, entrada) buscando por id exacto o por nombre exacto."""
    entradas = indice["entradas"]
    if id_o_nombre in entradas:
        return id_o_nombre, entradas[id_o_nombre]
    coincidencias = [(i, e) for i, e in entradas.items() if e["nombre"] == id_o_nombre]
    if not coincidencias:
        raise KeyError(f"no encuentro '{id_o_nombre}' en la boveda")
    if len(coincidencias) > 1:
        ids = ", ".join(i for i, _ in coincidencias)
        raise KeyError(f"'{id_o_nombre}' es ambiguo; usa el id (uno de: {ids})")
    return coincidencias[0]


# ---------- operaciones ----------

def agregar(boveda, archivo, clave=None, max_mb=20, organizar_por=ORGANIZACION_DEFECTO):
    with open(archivo, "rb") as f:
        original = f.read()
    nombre = os.path.basename(archivo)
    _, ext = os.path.splitext(nombre)
    tipo = ext.lstrip(".").lower()
    sha = hashlib.sha256(original).hexdigest()

    entrada_id = uuid.uuid4().hex
    momento = datetime.datetime.now()
    ruta_rel = _ruta_relativa_datos(entrada_id, nombre, tipo, momento, organizar_por)
    carpeta_datos = _ruta_fs(boveda, ruta_rel)

    carga = _cifrar(original, clave) if clave else original

    # Escribimos la carga (cifrada o no) en un temporal con el nombre original
    # para que fotocodec conserve un nombre razonable en la cabecera del PNG.
    with tempfile.TemporaryDirectory() as tmp:
        ruta_tmp = os.path.join(tmp, nombre)
        with open(ruta_tmp, "wb") as f:
            f.write(carga)
        rutas = fc.codificar(ruta_tmp, carpeta_datos, max_mb)

    partes = [os.path.basename(r) for r in rutas]
    indice = cargar_indice(boveda)
    indice["entradas"][entrada_id] = {
        "nombre": nombre,
        "tipo": tipo,
        "tamano": len(original),
        "fecha": momento.isoformat(timespec="seconds"),
        "sha256": sha,
        "cifrado": bool(clave),
        "partes": partes,
        "ruta_datos": ruta_rel,
        "organizacion": organizar_por,
    }
    guardar_indice(boveda, indice)
    return entrada_id


def listar(boveda):
    indice = cargar_indice(boveda)
    filas = []
    for eid, e in sorted(indice["entradas"].items(), key=lambda kv: kv[1]["fecha"]):
        filas.append({
            "id": eid, "nombre": e["nombre"], "tipo": e["tipo"],
            "tamano": e["tamano"], "fecha": e["fecha"], "cifrado": e["cifrado"],
        })
    return filas


def buscar(boveda, texto):
    texto = texto.lower()
    return [f for f in listar(boveda)
            if texto in f["nombre"].lower() or texto in (f["tipo"] or "")]


def extraer(boveda, id_o_nombre, carpeta, clave=None):
    indice = cargar_indice(boveda)
    eid, entrada = _resolver(indice, id_o_nombre)
    carpeta_datos = _resolver_carpeta_datos(boveda, eid, entrada)

    with tempfile.TemporaryDirectory() as tmp:
        # fotocodec ya verifica el SHA-256 de la carga guardada.
        recuperados = fc.decodificar(carpeta_datos, tmp)
        with open(recuperados[0], "rb") as f:
            carga = f.read()

    if entrada["cifrado"]:
        if not clave:
            raise ValueError(f"'{entrada['nombre']}' esta cifrado; falta la clave")
        original = _descifrar(carga, clave)
    else:
        original = carga

    if hashlib.sha256(original).hexdigest() != entrada["sha256"]:
        raise ValueError(f"{entrada['nombre']}: el hash no coincide (dato corrupto)")

    os.makedirs(carpeta, exist_ok=True)
    destino = os.path.join(carpeta, os.path.basename(entrada["nombre"]) or "archivo")
    with open(destino, "wb") as f:
        f.write(original)
    return destino


def borrar(boveda, id_o_nombre):
    indice = cargar_indice(boveda)
    eid, entrada = _resolver(indice, id_o_nombre)
    try:
        carpeta_datos = _resolver_carpeta_datos(boveda, eid, entrada)
    except FileNotFoundError:
        carpeta_datos = None
    if carpeta_datos and os.path.isdir(carpeta_datos):
        shutil.rmtree(carpeta_datos)
    del indice["entradas"][eid]
    guardar_indice(boveda, indice)
    return entrada["nombre"]


def exportar(boveda, destino, organizar_por=ORGANIZACION_DEFECTO, texto=None):
    """Copia los PNG de la boveda (ya organizados) a `destino`, listos para que
    el dueno los suba a mano a donde el decida. No sube ni conecta con nada."""
    indice = cargar_indice(boveda)
    entradas = indice["entradas"]
    ids = sorted(entradas.keys(), key=lambda i: entradas[i]["fecha"])
    if texto:
        t = texto.lower()
        ids = [i for i in ids
               if t in entradas[i]["nombre"].lower() or t in (entradas[i]["tipo"] or "")]

    copiados = []
    for eid in ids:
        entrada = entradas[eid]
        origen = _resolver_carpeta_datos(boveda, eid, entrada)
        momento = _fecha_de_entrada(entrada)
        ruta_rel = _ruta_relativa_datos(eid, entrada["nombre"], entrada["tipo"], momento, organizar_por)
        partes_rel = ruta_rel.split("/")[1:]  # quita el prefijo "datos" para el export
        carpeta_destino = os.path.join(destino, *partes_rel) if partes_rel else os.path.join(destino, eid)
        os.makedirs(carpeta_destino, exist_ok=True)
        for nombre_png in sorted(os.listdir(origen)):
            if nombre_png.lower().endswith(".png"):
                destino_png = os.path.join(carpeta_destino, nombre_png)
                shutil.copy2(os.path.join(origen, nombre_png), destino_png)
                copiados.append(destino_png)
    return copiados


# ---------- CLI ----------

def _pedir_clave(args):
    if args.clave is not None:
        return args.clave or None
    if getattr(args, "pedir_clave", False):
        return getpass.getpass("Clave: ")
    return None


def _fmt_tam(n):
    tam = float(n)
    for u in ("B", "KB", "MB", "GB"):
        if tam < 1024 or u == "GB":
            return f"{int(tam)}{u}" if u == "B" else f"{tam:.1f}{u}"
        tam /= 1024


def main(argv=None):
    ap = argparse.ArgumentParser(description="Boveda local de archivos guardados como imagenes PNG.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("agregar", help="agrega un archivo a la boveda")
    a.add_argument("boveda")
    a.add_argument("archivo")
    a.add_argument("--clave", nargs="?", const="", default=None, help="cifra con esta clave")
    a.add_argument("--pedir-clave", action="store_true", help="pide la clave sin mostrarla")
    a.add_argument("--max-mb", type=float, default=20)
    a.add_argument("--por", choices=["fecha", "tipo"], default=ORGANIZACION_DEFECTO,
                   help="como organizar las subcarpetas de datos (por defecto: fecha, AAAA/MM)")

    l = sub.add_parser("listar", help="lista el contenido de la boveda")
    l.add_argument("boveda")

    b = sub.add_parser("buscar", help="busca por nombre o tipo")
    b.add_argument("boveda")
    b.add_argument("texto")

    e = sub.add_parser("extraer", help="recupera un archivo (verifica SHA-256)")
    e.add_argument("boveda")
    e.add_argument("id_o_nombre")
    e.add_argument("-o", "--salida", default="recuperado")
    e.add_argument("--clave", nargs="?", const="", default=None)
    e.add_argument("--pedir-clave", action="store_true")

    r = sub.add_parser("borrar", help="borra un archivo de la boveda")
    r.add_argument("boveda")
    r.add_argument("id_o_nombre")

    x = sub.add_parser("exportar", help="copia los PNG organizados a una carpeta lista para subir a mano")
    x.add_argument("boveda")
    x.add_argument("destino")
    x.add_argument("--por", choices=["fecha", "tipo"], default=ORGANIZACION_DEFECTO,
                   help="como organizar la carpeta exportada (por defecto: fecha, AAAA/MM)")
    x.add_argument("--filtro", default=None, help="solo exporta coincidencias de nombre/tipo (como buscar)")

    args = ap.parse_args(argv)

    if args.cmd == "agregar":
        eid = agregar(args.boveda, args.archivo, _pedir_clave(args), args.max_mb, args.por)
        print(f"agregado: {eid}")
    elif args.cmd == "listar":
        filas = listar(args.boveda)
        if not filas:
            print("(boveda vacia)")
        for f in filas:
            cif = " [cifrado]" if f["cifrado"] else ""
            print(f"{f['id']}  {f['nombre']}  ({f['tipo'] or 'sin tipo'}, {_fmt_tam(f['tamano'])}, {f['fecha']}){cif}")
    elif args.cmd == "buscar":
        filas = buscar(args.boveda, args.texto)
        if not filas:
            print("(sin coincidencias)")
        for f in filas:
            print(f"{f['id']}  {f['nombre']}  ({f['tipo'] or 'sin tipo'}, {_fmt_tam(f['tamano'])})")
    elif args.cmd == "extraer":
        destino = extraer(args.boveda, args.id_o_nombre, args.salida, _pedir_clave(args))
        print(f"OK (hash verificado): {destino}")
    elif args.cmd == "borrar":
        nombre = borrar(args.boveda, args.id_o_nombre)
        print(f"borrado: {nombre}")
    elif args.cmd == "exportar":
        copiados = exportar(args.boveda, args.destino, args.por, args.filtro)
        print(f"exportados {len(copiados)} archivo(s) PNG a {args.destino}")


if __name__ == "__main__":
    main()
