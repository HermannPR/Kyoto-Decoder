import os, random, shutil, tempfile, unittest

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
        entrada = v.cargar_indice(self.boveda)["entradas"][eid]
        carpeta = v._resolver_carpeta_datos(self.boveda, eid, entrada)
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

    # ---- organizacion por carpetas ----

    def test_organizacion_por_fecha_por_defecto(self):
        src = self._archivo("foto.jpg", b"x" * 500)
        eid = v.agregar(self.boveda, src)
        entrada = v.cargar_indice(self.boveda)["entradas"][eid]
        self.assertEqual(entrada["organizacion"], "fecha")
        hoy = __import__("datetime").datetime.now()
        prefijo = f"{v.NOMBRE_DATOS}/{hoy.strftime('%Y')}/{hoy.strftime('%m')}/"
        self.assertTrue(entrada["ruta_datos"].startswith(prefijo), entrada["ruta_datos"])
        carpeta = os.path.join(self.boveda, *entrada["ruta_datos"].split("/"))
        self.assertTrue(os.path.isdir(carpeta))
        self.assertTrue(any(n.lower().endswith(".png") for n in os.listdir(carpeta)))

    def test_organizacion_por_tipo(self):
        src = self._archivo("informe.pdf", b"y" * 500)
        eid = v.agregar(self.boveda, src, organizar_por="tipo")
        entrada = v.cargar_indice(self.boveda)["entradas"][eid]
        self.assertEqual(entrada["organizacion"], "tipo")
        self.assertTrue(entrada["ruta_datos"].startswith(f"{v.NOMBRE_DATOS}/pdf/"), entrada["ruta_datos"])
        carpeta = os.path.join(self.boveda, *entrada["ruta_datos"].split("/"))
        self.assertTrue(os.path.isdir(carpeta))

    def test_nombre_de_carpeta_ordenable_y_unico(self):
        eid = v.agregar(self.boveda, self._archivo("Mi Archivo Raro!!.txt", b"z"))
        entrada = v.cargar_indice(self.boveda)["entradas"][eid]
        nombre_carpeta = entrada["ruta_datos"].rstrip("/").split("/")[-1]
        # empieza con AAAAMMDD-HHMMSS (ordenable) y termina con el id completo (unico)
        self.assertRegex(nombre_carpeta, r"^\d{8}-\d{6}_")
        self.assertTrue(nombre_carpeta.endswith(eid))

    def test_compatibilidad_boveda_antigua_estructura_plana(self):
        # Simula una boveda de una version anterior: sin "ruta_datos" en el
        # indice y los PNG directamente en datos/<id>/.
        src = self._archivo("viejo.bin", b"contenido antiguo")
        eid_temporal = v.agregar(self.boveda, src)
        indice = v.cargar_indice(self.boveda)
        entrada = indice["entradas"].pop(eid_temporal)
        carpeta_nueva = v._resolver_carpeta_datos(self.boveda, eid_temporal, entrada)
        carpeta_plana = os.path.join(self.boveda, v.NOMBRE_DATOS, eid_temporal)
        shutil.move(carpeta_nueva, carpeta_plana)
        entrada.pop("ruta_datos", None)
        entrada.pop("organizacion", None)
        indice["entradas"][eid_temporal] = entrada
        v.guardar_indice(self.boveda, indice)

        destino = v.extraer(self.boveda, eid_temporal, os.path.join(self.tmp, "out"))
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), b"contenido antiguo")
        v.borrar(self.boveda, eid_temporal)
        self.assertFalse(os.path.isdir(carpeta_plana))

    def test_recuperacion_si_se_reorganiza_a_mano(self):
        # Si el usuario mueve la carpeta de datos a mano y "ruta_datos" del
        # indice queda desactualizada, extraer la debe encontrar igual
        # buscando de forma recursiva por el id de la entrada.
        src = self._archivo("movido.bin", b"contenido movido")
        eid = v.agregar(self.boveda, src)
        indice = v.cargar_indice(self.boveda)
        entrada = indice["entradas"][eid]
        origen = v._resolver_carpeta_datos(self.boveda, eid, entrada)
        nuevo_padre = os.path.join(self.boveda, v.NOMBRE_DATOS, "reordenado", "a-mano")
        os.makedirs(nuevo_padre, exist_ok=True)
        nuevo_destino = os.path.join(nuevo_padre, os.path.basename(origen))
        shutil.move(origen, nuevo_destino)
        entrada["ruta_datos"] = "datos/ruta/que-ya-no-existe"
        v.guardar_indice(self.boveda, indice)

        destino = v.extraer(self.boveda, eid, os.path.join(self.tmp, "out"))
        with open(destino, "rb") as f:
            self.assertEqual(f.read(), b"contenido movido")

    def test_buscar_recursivo_entre_organizaciones_distintas(self):
        v.agregar(self.boveda, self._archivo("gato.png", b"a"), organizar_por="fecha")
        v.agregar(self.boveda, self._archivo("perro.png", b"b"), organizar_por="tipo")
        v.agregar(self.boveda, self._archivo("informe.pdf", b"c"), organizar_por="tipo")
        self.assertEqual(len(v.buscar(self.boveda, "png")), 2)
        self.assertEqual(len(v.buscar(self.boveda, "pdf")), 1)
        # y todas se pueden extraer sin importar en que subcarpeta quedaron
        for nombre in ("gato.png", "perro.png", "informe.pdf"):
            destino = v.extraer(self.boveda, nombre, os.path.join(self.tmp, "out", nombre))
            self.assertTrue(os.path.isfile(destino))

    # ---- exportar ----

    def test_exportar_copia_pngs_organizados(self):
        eid1 = v.agregar(self.boveda, self._archivo("uno.txt", b"1" * 200))
        eid2 = v.agregar(self.boveda, self._archivo("dos.csv", b"2" * 200), organizar_por="tipo")
        destino = os.path.join(self.tmp, "listo-para-subir")

        copiados = v.exportar(self.boveda, destino, organizar_por="fecha")
        self.assertEqual(len(copiados), 2)
        for ruta in copiados:
            self.assertTrue(os.path.isfile(ruta))
            self.assertTrue(ruta.startswith(destino))

        hoy = __import__("datetime").datetime.now()
        carpeta_fecha = os.path.join(destino, hoy.strftime("%Y"), hoy.strftime("%m"))
        self.assertTrue(os.path.isdir(carpeta_fecha))

        # el "datos/" interno de la boveda no debe aparecer en la carpeta exportada
        self.assertFalse(os.path.isdir(os.path.join(destino, v.NOMBRE_DATOS)))

        # las copias son PNG validos: decodifican al mismo contenido original
        for eid, contenido in ((eid1, b"1" * 200), (eid2, b"2" * 200)):
            entrada = v.cargar_indice(self.boveda)["entradas"][eid]
            momento = v._fecha_de_entrada(entrada)
            ruta_rel = v._ruta_relativa_datos(eid, entrada["nombre"], entrada["tipo"], momento, "fecha")
            carpeta_export = os.path.join(destino, *ruta_rel.split("/")[1:])
            self.assertTrue(os.path.isdir(carpeta_export))
            out = os.path.join(self.tmp, f"chk-{eid}")
            import fotocodec as fc
            recuperados = fc.decodificar(carpeta_export, out)
            with open(recuperados[0], "rb") as f:
                self.assertEqual(f.read(), contenido)

    def test_exportar_con_filtro(self):
        v.agregar(self.boveda, self._archivo("reporte.pdf", b"p"))
        v.agregar(self.boveda, self._archivo("imagen.png", b"i"))
        destino = os.path.join(self.tmp, "solo-pdf")
        copiados = v.exportar(self.boveda, destino, texto="pdf")
        self.assertEqual(len(copiados), 1)


if __name__ == "__main__":
    unittest.main()
