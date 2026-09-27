import os, random, struct, tempfile, unittest, zlib
import fotocodec as fc


class Pruebas(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def _archivo(self, nombre, datos):
        p = os.path.join(self.tmp, nombre)
        with open(p, "wb") as f:
            f.write(datos)
        return p

    def _ida_y_vuelta(self, datos, max_mb=20, nombre="a.bin"):
        src = self._archivo(nombre, datos)
        imgs = fc.codificar(src, os.path.join(self.tmp, "img"), max_mb)
        out = fc.decodificar(os.path.join(self.tmp, "img"), os.path.join(self.tmp, "out"))
        with open(out[0], "rb") as f:
            self.assertEqual(f.read(), datos)
        return imgs

    def test_varios_tamanos(self):
        for n in (0, 1, 2, 3, 4, 1000, 65537):
            with self.subTest(n=n):
                self.tmp = tempfile.mkdtemp()
                self._ida_y_vuelta(random.randbytes(n))

    def test_nombre_con_acentos(self):
        self._ida_y_vuelta(b"hola", nombre="canción ñ.txt")

    def test_partido_en_varias_imagenes(self):
        imgs = self._ida_y_vuelta(random.randbytes(300_000), max_mb=0.1)
        self.assertEqual(len(imgs), 3)

    def test_imagen_alterada_se_detecta(self):
        src = self._archivo("a.bin", random.randbytes(5000))
        img = fc.codificar(src, os.path.join(self.tmp, "img"), 20)[0]
        pix = bytearray(fc.leer_png(img))
        pix[-1] ^= 0xFF          # un bit de datos cambiado
        pix[fc.CAB.size + 5 + 100] ^= 1
        fc.escribir_png(img, bytes(pix))
        with self.assertRaises(SystemExit):
            fc.decodificar(img, os.path.join(self.tmp, "out"))

    def test_parte_faltante(self):
        src = self._archivo("a.bin", random.randbytes(300_000))
        imgs = fc.codificar(src, os.path.join(self.tmp, "img"), 0.1)
        os.remove(imgs[1])
        with self.assertRaises(SystemExit):
            fc.decodificar(os.path.join(self.tmp, "img"), os.path.join(self.tmp, "out"))

    def test_png_reguardado_con_filtros(self):
        # Simula otra herramienta que re-guarda el PNG sin pérdida usando filtros Sub/Up/Avg/Paeth.
        src = self._archivo("a.bin", random.randbytes(20_000))
        img = fc.codificar(src, os.path.join(self.tmp, "img"), 20)[0]
        pix = fc.leer_png(img)
        lado = int(round((len(pix) / 3) ** 0.5))
        fila, filas, previa = lado * 3, [], bytes(lado * 3)
        for y in range(lado):
            f = y % 5
            actual = pix[y * fila:(y + 1) * fila]
            enc = bytearray()
            for x in range(fila):
                a = actual[x - 3] if x >= 3 else 0
                c = previa[x - 3] if x >= 3 else 0
                pred = (0, a, previa[x], (a + previa[x]) // 2, fc._paeth(a, previa[x], c))[f]
                enc.append((actual[x] - pred) & 0xFF)
            filas.append(bytes([f]) + bytes(enc))
            previa = actual
        png = (b"\x89PNG\r\n\x1a\n" + fc._chunk(b"IHDR", struct.pack(">IIBBBBB", lado, lado, 8, 2, 0, 0, 0))
               + fc._chunk(b"IDAT", zlib.compress(b"".join(filas))) + fc._chunk(b"IEND", b""))
        with open(img, "wb") as fh:
            fh.write(png)
        out = fc.decodificar(img, os.path.join(self.tmp, "out"))
        with open(out[0], "rb") as fh, open(src, "rb") as orig:
            self.assertEqual(fh.read(), orig.read())

    def test_nombre_malicioso_no_escapa(self):
        src = self._archivo("x.bin", b"dato")
        img = fc.codificar(src, os.path.join(self.tmp, "img"), 20)[0]
        pix = bytearray(fc.leer_png(img))
        cab = list(fc.CAB.unpack_from(pix))
        malo = b"../../escapado.txt"
        cab[-1] = len(malo)
        nuevo = fc.CAB.pack(*cab) + malo + b"dato"
        fc.escribir_png(img, nuevo)
        out = fc.decodificar(img, os.path.join(self.tmp, "out"))
        self.assertTrue(out[0].startswith(os.path.join(self.tmp, "out")))


if __name__ == "__main__":
    unittest.main()
