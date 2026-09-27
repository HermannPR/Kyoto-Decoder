#!/usr/bin/env python3
"""foto-codec: guarda cualquier archivo como una o varias imágenes PNG y lo recupera idéntico.

Solo usa la librería estándar. El PNG es sin pérdida: si una imagen se
recomprime a JPEG o se redimensiona, los datos se pierden (el hash lo detecta).

Uso:
  fotocodec.py codificar ARCHIVO [-o CARPETA] [--max-mb N]
  fotocodec.py decodificar IMAGEN_O_CARPETA [-o CARPETA]
"""
import argparse, hashlib, math, os, struct, sys, zlib

MAGIA = b"FCOD1"
# Cabecera por imagen: magia, id del archivo (8 bytes del sha256), parte, total,
# tamaño del trozo, tamaño total, sha256 completo, largo del nombre, nombre.
CAB = struct.Struct(">5s8sIIQQ32sH")


# ---------- PNG mínimo (RGB 8 bits) ----------

def _chunk(tipo, datos):
    return struct.pack(">I", len(datos)) + tipo + datos + struct.pack(">I", zlib.crc32(tipo + datos) & 0xFFFFFFFF)


def escribir_png(ruta, datos):
    """Empaqueta bytes crudos en un PNG RGB cuadrado (relleno con ceros)."""
    pixeles = math.ceil(len(datos) / 3)
    lado = max(1, math.ceil(math.sqrt(pixeles)))
    fila = lado * 3
    datos = datos + b"\0" * (lado * fila - len(datos))
    crudo = b"".join(b"\0" + datos[i * fila:(i + 1) * fila] for i in range(lado))
    png = (b"\x89PNG\r\n\x1a\n"
           + _chunk(b"IHDR", struct.pack(">IIBBBBB", lado, lado, 8, 2, 0, 0, 0))
           + _chunk(b"IDAT", zlib.compress(crudo, 9))
           + _chunk(b"IEND", b""))
    with open(ruta, "wb") as f:
        f.write(png)


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else (b if pb <= pc else c)


def leer_png(ruta):
    """Devuelve los bytes de los píxeles. Acepta los 5 filtros por si otra
    herramienta re-guardó el PNG sin pérdida."""
    with open(ruta, "rb") as f:
        b = f.read()
    if b[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("no es PNG")
    pos, idat, ancho = 8, [], None
    while pos < len(b):
        largo, tipo = struct.unpack(">I4s", b[pos:pos + 8])
        datos = b[pos + 8:pos + 8 + largo]
        pos += 12 + largo
        if tipo == b"IHDR":
            ancho, alto, prof, color, _, _, entrelazado = struct.unpack(">IIBBBBB", datos)
            if prof != 8 or color not in (2, 6) or entrelazado:
                raise ValueError("PNG no soportado (se esperaba RGB/RGBA de 8 bits sin entrelazar)")
            canales = 3 if color == 2 else 4
        elif tipo == b"IDAT":
            idat.append(datos)
        elif tipo == b"IEND":
            break
    crudo = zlib.decompress(b"".join(idat))
    bpp, fila = canales, ancho * canales
    salida, previa = bytearray(), bytearray(fila)
    for y in range(alto):
        filtro = crudo[y * (fila + 1)]
        actual = bytearray(crudo[y * (fila + 1) + 1:(y + 1) * (fila + 1)])
        # Filtro 0 (el que escribe este codificador): la fila ya son los datos.
        for x in (range(fila) if filtro else ()):
            a = actual[x - bpp] if x >= bpp else 0
            c = previa[x - bpp] if x >= bpp else 0
            pred = (0, a, previa[x], (a + previa[x]) // 2, _paeth(a, previa[x], c))[filtro]
            actual[x] = (actual[x] + pred) & 0xFF
        if canales == 4:  # descarta alfa: el codificador siempre escribe RGB
            rgb = bytearray(actual)
            del rgb[3::4]
            salida += rgb
        else:
            salida += actual
        previa = actual
    return bytes(salida)


# ---------- codificar / decodificar ----------

def codificar(ruta, carpeta, max_mb):
    with open(ruta, "rb") as f:
        datos = f.read()
    sha = hashlib.sha256(datos).digest()
    nombre = os.path.basename(ruta).encode("utf-8")
    trozo = max(1, int(max_mb * 1024 * 1024))
    total = max(1, math.ceil(len(datos) / trozo))
    os.makedirs(carpeta, exist_ok=True)
    salidas = []
    for i in range(total):
        pedazo = datos[i * trozo:(i + 1) * trozo]
        cab = CAB.pack(MAGIA, sha[:8], i, total, len(pedazo), len(datos), sha, len(nombre)) + nombre
        destino = os.path.join(carpeta, f"{os.path.basename(ruta)}.{i + 1:04d}de{total:04d}.png")
        escribir_png(destino, cab + pedazo)
        salidas.append(destino)
    return salidas


def _leer_parte(ruta):
    b = leer_png(ruta)
    magia, fid, parte, total, largo, tam, sha, lnom = CAB.unpack_from(b)
    if magia != MAGIA:
        raise ValueError("la imagen no fue creada por foto-codec")
    ini = CAB.size + lnom
    return {"id": fid, "parte": parte, "total": total, "tam": tam, "sha": sha,
            "nombre": b[CAB.size:ini].decode("utf-8"), "datos": b[ini:ini + largo]}


def decodificar(entrada, carpeta):
    rutas = ([os.path.join(entrada, n) for n in sorted(os.listdir(entrada)) if n.lower().endswith(".png")]
             if os.path.isdir(entrada) else [entrada])
    grupos = {}
    for r in rutas:
        try:
            p = _leer_parte(r)
        except Exception as e:
            print(f"  omito {r}: {e}", file=sys.stderr)
            continue
        grupos.setdefault(p["id"], []).append(p)
    os.makedirs(carpeta, exist_ok=True)
    resultados = []
    for partes in grupos.values():
        primero = partes[0]
        tengo = {p["parte"]: p for p in partes}
        faltan = [i + 1 for i in range(primero["total"]) if i not in tengo]
        if faltan:
            raise SystemExit(f"{primero['nombre']}: faltan partes {faltan}")
        datos = b"".join(tengo[i]["datos"] for i in range(primero["total"]))
        if len(datos) != primero["tam"] or hashlib.sha256(datos).digest() != primero["sha"]:
            raise SystemExit(f"{primero['nombre']}: el hash no coincide; alguna imagen se alteró (¿recomprimida?)")
        # Solo el nombre base: una imagen maliciosa no puede escribir fuera de la carpeta.
        destino = os.path.join(carpeta, os.path.basename(primero["nombre"]) or "archivo")
        with open(destino, "wb") as f:
            f.write(datos)
        resultados.append(destino)
    return resultados


def main():
    ap = argparse.ArgumentParser(description="Guarda archivos como imágenes PNG y los recupera idénticos.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("codificar", help="archivo -> imágenes")
    c.add_argument("archivo")
    c.add_argument("-o", "--salida", default="imagenes")
    c.add_argument("--max-mb", type=float, default=20, help="tamaño máximo de datos por imagen (MB)")
    d = sub.add_parser("decodificar", help="imágenes -> archivo")
    d.add_argument("entrada", help="una imagen o una carpeta con las partes")
    d.add_argument("-o", "--salida", default="recuperado")
    a = ap.parse_args()
    if a.cmd == "codificar":
        for r in codificar(a.archivo, a.salida, a.max_mb):
            print(r)
    else:
        for r in decodificar(a.entrada, a.salida):
            print(f"OK (hash verificado): {r}")


if __name__ == "__main__":
    main()
