#!/usr/bin/env python3
"""Boveda local de archivos sobre foto-codec.

Por fuera es un gestor de archivos normal (agregar, listar, buscar, extraer,
borrar); por dentro cada archivo se guarda como una o varias imagenes PNG usando
`fotocodec.py`. Todo es LOCAL: nada se sube a ningun servicio en la nube.

Estructura de una boveda (una carpeta cualquiera):

    boveda/
      index.json            indice legible con los metadatos de cada entrada
      datos/<id>/*.png      las imagenes PNG de cada archivo

El cifrado es OPCIONAL y usa solo la libreria estandar (PBKDF2-HMAC-SHA256 para
derivar la clave y un cifrado de flujo tipo CTR con HMAC-SHA256, ver `_cifrar`).

Uso:
  vault.py agregar RUTA_BOVEDA ARCHIVO [--clave CLAVE] [--max-mb N]
  vault.py listar   RUTA_BOVEDA
  vault.py buscar   RUTA_BOVEDA TEXTO
  vault.py extraer  RUTA_BOVEDA (ID|NOMBRE) [-o CARPETA] [--clave CLAVE]
  vault.py borrar   RUTA_BOVEDA (ID|NOMBRE)
"""
import argparse, datetime, getpass, hashlib, hmac, json, os, shutil, struct, sys, tempfile, uuid

import fotocodec as fc

NOMBRE_INDICE = "index.json"
NOMBRE_DATOS = "datos"

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

def agregar(boveda, archivo, clave=None, max_mb=20):
    with open(archivo, "rb") as f:
        original = f.read()
    nombre = os.path.basename(archivo)
    _, ext = os.path.splitext(nombre)
    sha = hashlib.sha256(original).hexdigest()

    entrada_id = uuid.uuid4().hex
    carpeta_datos = os.path.join(boveda, NOMBRE_DATOS, entrada_id)

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
        "tipo": ext.lstrip(".").lower(),
        "tamano": len(original),
        "fecha": datetime.datetime.now().isoformat(timespec="seconds"),
        "sha256": sha,
        "cifrado": bool(clave),
        "partes": partes,
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
    carpeta_datos = os.path.join(boveda, NOMBRE_DATOS, eid)

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
    carpeta_datos = os.path.join(boveda, NOMBRE_DATOS, eid)
    if os.path.isdir(carpeta_datos):
        shutil.rmtree(carpeta_datos)
    del indice["entradas"][eid]
    guardar_indice(boveda, indice)
    return entrada["nombre"]


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

    args = ap.parse_args(argv)

    if args.cmd == "agregar":
        eid = agregar(args.boveda, args.archivo, _pedir_clave(args), args.max_mb)
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


if __name__ == "__main__":
    main()
