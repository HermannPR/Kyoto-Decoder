import os, random, tempfile, unittest

import vault as v


class PruebasBoveda(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.boveda = os.path.join(self.tmp, "boveda")

    def _archivo(self, nombre, datos):
        p = os.path.join(self.tmp, nombre)
        with open(p, "wb") as f:
            f.write(datos)
        return p

    def test_ida_y_vuelta(self):
        datos = random.randbytes(50_000)
        src = self._archivo("foto.jpg", datos)
        eid = v.agregar(self.boveda, src)
        salida = os.path.join(self.tmp, "out")
        destino = v.extraer(self.boveda, eid, salida)
        self.assertEqual(os.path.basename(destino), "foto.jpg")
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), datos)

    def test_indice_metadatos(self):
        src = self._archivo("doc.pdf", b"contenido de prueba")
        eid = v.agregar(self.boveda, src)
        indice = v.cargar_indice(self.boveda)
        e = indice["entradas"][eid]
        self.assertEqual(e["nombre"], "doc.pdf")
        self.assertEqual(e["tipo"], "pdf")
        self.assertEqual(e["tamano"], len(b"contenido de prueba"))
        self.assertFalse(e["cifrado"])
        self.assertIn("fecha", e)
        self.assertTrue(e["partes"])

    def test_extraer_por_nombre(self):
        src = self._archivo("notas.txt", b"hola")
        v.agregar(self.boveda, src)
        destino = v.extraer(self.boveda, "notas.txt", os.path.join(self.tmp, "out"))
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), b"hola")

    def test_buscar(self):
        v.agregar(self.boveda, self._archivo("gato.png", b"a"))
        v.agregar(self.boveda, self._archivo("perro.png", b"b"))
        v.agregar(self.boveda, self._archivo("informe.pdf", b"c"))
        self.assertEqual(len(v.buscar(self.boveda, "gato")), 1)
        self.assertEqual(len(v.buscar(self.boveda, "png")), 2)
        self.assertEqual(len(v.buscar(self.boveda, "pdf")), 1)
        self.assertEqual(len(v.buscar(self.boveda, "nada")), 0)

    def test_borrar(self):
        src = self._archivo("temporal.bin", b"x" * 100)
        eid = v.agregar(self.boveda, src)
        carpeta = os.path.join(self.boveda, v.NOMBRE_DATOS, eid)
        self.assertTrue(os.path.isdir(carpeta))
        v.borrar(self.boveda, eid)
        self.assertFalse(os.path.isdir(carpeta))
        self.assertNotIn(eid, v.cargar_indice(self.boveda)["entradas"])

    def test_listar_vacia(self):
        self.assertEqual(v.listar(self.boveda), [])

    def test_partido_en_varias_imagenes(self):
        datos = random.randbytes(300_000)
        src = self._archivo("grande.bin", datos)
        eid = v.agregar(self.boveda, src, max_mb=0.1)
        self.assertGreater(len(v.cargar_indice(self.boveda)["entradas"][eid]["partes"]), 1)
        destino = v.extraer(self.boveda, eid, os.path.join(self.tmp, "out"))
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), datos)

    # ---- cifrado ----

    def test_cifrar_descifrar(self):
        datos = random.randbytes(20_000)
        src = self._archivo("secreto.txt", datos)
        eid = v.agregar(self.boveda, src, clave="miClave123")
        self.assertTrue(v.cargar_indice(self.boveda)["entradas"][eid]["cifrado"])
        destino = v.extraer(self.boveda, eid, os.path.join(self.tmp, "out"), clave="miClave123")
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), datos)

    def test_clave_incorrecta_falla(self):
        src = self._archivo("secreto.txt", b"informacion privada")
        eid = v.agregar(self.boveda, src, clave="correcta")
        with self.assertRaises(ValueError):
            v.extraer(self.boveda, eid, os.path.join(self.tmp, "out"), clave="incorrecta")

    def test_cifrado_sin_clave_al_extraer_falla(self):
        src = self._archivo("secreto.txt", b"x")
        eid = v.agregar(self.boveda, src, clave="c")
        with self.assertRaises(ValueError):
            v.extraer(self.boveda, eid, os.path.join(self.tmp, "out"))

    def test_bloque_cifrado_no_contiene_texto_claro(self):
        texto = b"CADENA_SECRETA_UNICA_1234567890"
        blob = v._cifrar(texto, "clave")
        self.assertNotIn(texto, blob)
        self.assertEqual(v._descifrar(blob, "clave"), texto)


if __name__ == "__main__":
    unittest.main()
