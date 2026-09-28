#!/usr/bin/env python3
"""Genera los fixtures de compatibilidad Python -> Kotlin.

Codifica archivos de prueba con la herramienta real (`fotocodec.py codificar`,
llamada como proceso) y luego renombra, desordena, quita o altera partes para
cubrir los casos difíciles. Salida:

    android/codec/src/test/resources/fixtures/<caso>/imagenes/*.png
    android/codec/src/test/resources/fixtures/<caso>/originales/<archivo original>

Uso (desde la raíz del repo):  py -3 android/tools/gen_fixtures.py
Las pruebas de Kotlin (Fcod1PythonFixturesTest) leen estos archivos.
"""
import os, random, shutil, struct, subprocess, sys, tempfile, zlib

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, RAIZ)
import fotocodec as fc  # noqa: E402  (solo para alterar imágenes ya creadas)

DESTINO = os.path.join(RAIZ, "android", "codec", "src", "test", "resources", "fixtures")
rng = random.Random(20260927)
ENV = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")


def codificar(nombre, datos, max_mb=None):
    """Llama a `fotocodec.py codificar` y devuelve las rutas de las partes, en orden."""
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, nombre)
    with open(src, "wb") as f:
        f.write(datos)
    out = os.path.join(tmp, "img")
    cmd = [sys.executable, os.path.join(RAIZ, "fotocodec.py"), "codificar", src, "-o", out]
    if max_mb is not None:
        cmd += ["--max-mb", str(max_mb)]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", env=ENV, check=True)
    return [l.strip() for l in r.stdout.splitlines() if l.strip()]


def caso(nombre):
    d = os.path.join(DESTINO, nombre)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, "imagenes"))
    os.makedirs(os.path.join(d, "originales"))
    return d


def original(d, nombre, datos):
    with open(os.path.join(d, "originales", nombre), "wb") as f:
        f.write(datos)


def copiar_renombrando(partes, d, prefijo="img"):
    """Copia las partes con nombres aleatorios y sin relación con el orden."""
    for p in partes:
        nuevo = f"{prefijo}_{rng.randrange(16**8):08x}.png"
        shutil.copy(p, os.path.join(d, "imagenes", nuevo))


def png_con_filtros(pixeles, rgba=False, trozo_idat=None):
    """Re-guarda sin pérdida usando los 5 filtros (como otra herramienta)."""
    lado = int(round((len(pixeles) / 3) ** 0.5))
    canales = 4 if rgba else 3
    fila, filas, previa = lado * canales, [], bytes(lado * canales)
    for y in range(lado):
        f = y % 5
        rgb = pixeles[y * lado * 3:(y + 1) * lado * 3]
        if rgba:
            actual = b"".join(rgb[i:i + 3] + bytes([rng.randrange(256)]) for i in range(0, len(rgb), 3))
        else:
            actual = rgb
        enc = bytearray()
        for x in range(fila):
            a = actual[x - canales] if x >= canales else 0
            c = previa[x - canales] if x >= canales else 0
            pred = (0, a, previa[x], (a + previa[x]) // 2, fc._paeth(a, previa[x], c))[f]
            enc.append((actual[x] - pred) & 0xFF)
        filas.append(bytes([f]) + bytes(enc))
        previa = actual
    z = zlib.compress(b"".join(filas), 6)
    trozo_idat = trozo_idat or len(z)
    idats = b"".join(fc._chunk(b"IDAT", z[i:i + trozo_idat]) for i in range(0, len(z), trozo_idat))
    return (b"\x89PNG\r\n\x1a\n"
            + fc._chunk(b"IHDR", struct.pack(">IIBBBBB", lado, lado, 8, 6 if rgba else 2, 0, 0, 0))
            + fc._chunk(b"tEXt", b"Comment\0re-guardado")
            + idats + fc._chunk(b"IEND", b""))


def main():
    os.makedirs(DESTINO, exist_ok=True)

    # 1. Básico: un archivo pequeño, una parte, renombrada.
    d = caso("basico")
    datos = "Hola desde Kyoto\n".encode()
    original(d, "hola.txt", datos)
    copiar_renombrando(codificar("hola.txt", datos), d)

    # 2. Multiparte: 5 partes (--max-mb 0.01 = 10 485 bytes), renombradas y desordenadas.
    d = caso("multiparte")
    datos = rng.randbytes(50_000)
    original(d, "datos.bin", datos)
    partes = codificar("datos.bin", datos, 0.01)
    assert len(partes) == 5, partes
    rng.shuffle(partes)
    copiar_renombrando(partes, d)

    # 3. Archivo vacío.
    d = caso("vacio")
    original(d, "vacio.bin", b"")
    copiar_renombrando(codificar("vacio.bin", b""), d)

    # 4. Nombre unicode, 2 partes.
    d = caso("unicode")
    nombre = "canción ñ 日本 😀.txt"
    datos = ("Árbol, ñandú, 東京, emoji 😀\n" * 800).encode("utf-8")
    original(d, nombre, datos)
    copiar_renombrando(codificar(nombre, datos, 0.02), d)

    # 5. Faltan las partes 2 y 4 de 5.
    d = caso("faltante")
    datos = rng.randbytes(50_000)
    partes = codificar("faltante.bin", datos, 0.01)
    copiar_renombrando([p for i, p in enumerate(partes) if i not in (1, 3)], d)

    # 6. Alterada: la parte 2 de 3 sigue siendo un PNG válido pero cambió un byte de datos.
    d = caso("alterada")
    datos = rng.randbytes(30_000)
    partes = codificar("alterada.bin", datos, 0.01)
    pix = bytearray(fc.leer_png(partes[1]))
    pix[fc.CAB.size + len(b"alterada.bin") + 500] ^= 0x01
    fc.escribir_png(partes[1], bytes(pix))
    copiar_renombrando(partes, d)

    # 7. Dañada: la parte 3 de 3 tiene un byte cambiado en el archivo PNG (CRC roto).
    d = caso("danada")
    datos = rng.randbytes(30_000)
    partes = codificar("danada.bin", datos, 0.01)
    with open(partes[2], "r+b") as f:
        f.seek(200)
        b = f.read(1)
        f.seek(200)
        f.write(bytes([b[0] ^ 0xFF]))
    copiar_renombrando(partes, d)

    # 8. Re-guardadas sin pérdida con filtros Sub/Up/Average/Paeth, RGB y RGBA, varios IDAT.
    d = caso("filtros")
    datos = rng.randbytes(20_000)
    original(d, "filtros-rgb.bin", datos)
    p = codificar("filtros-rgb.bin", datos)[0]
    with open(os.path.join(d, "imagenes", "rgb.png"), "wb") as f:
        f.write(png_con_filtros(fc.leer_png(p), trozo_idat=1000))
    datos = rng.randbytes(20_000)
    original(d, "filtros-rgba.bin", datos)
    p = codificar("filtros-rgba.bin", datos)[0]
    with open(os.path.join(d, "imagenes", "rgba.png"), "wb") as f:
        f.write(png_con_filtros(fc.leer_png(p), rgba=True, trozo_idat=777))

    # 9. Mezcla: varios archivos completos juntos + una foto normal + un "png" que no lo es.
    d = caso("mezcla")
    todos = []
    for nombre, datos, mb in (("uno.txt", b"uno\n" * 1000, None),
                              ("dos.bin", rng.randbytes(25_000), 0.01),
                              ("tres ñ.dat", rng.randbytes(1234), None)):
        original(d, nombre, datos)
        todos += codificar(nombre, datos, mb)
    rng.shuffle(todos)
    copiar_renombrando(todos, d, "foto")
    normal = bytes(rng.randrange(256) for _ in range(4 * 4 * 3))
    fc.escribir_png(os.path.join(d, "imagenes", "foto-normal.png"), normal)
    with open(os.path.join(d, "imagenes", "no-es-png.png"), "wb") as f:
        f.write(b"esto no es una imagen")

    print("fixtures en", DESTINO)


if __name__ == "__main__":
    main()
