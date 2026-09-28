package com.hermannpr.kyoto.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * (b) Kotlin -> Python: lo que codifica la app debe decodificarlo `fotocodec.py`
 * idéntico. Ejecuta `py -3 fotocodec.py decodificar` (o KYOTO_PYTHON / -Dkyoto.python).
 * Si Python no está disponible, estas pruebas se marcan como omitidas.
 */
class KotlinToPythonTest {
    private val rnd = Random(42)

    @Before fun needsPython() = assumeTrue("Python 3 no disponible", Python.available)

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    /** Codifica con Kotlin, renombra y devuelve la carpeta de imágenes. */
    private fun encode(name: String, data: ByteArray, maxPart: Long): Pair<File, List<File>> {
        val work = TestSupport.tempDir("k2p")
        val src = File(work, name).apply { writeBytes(data) }
        val parts = Fcod1Files.encode(src, File(work, "img"), maxPart).shuffled(rnd)
        return File(work, "img") to TestSupport.renameRandomly(parts, rnd)
    }

    private fun pythonDecodes(name: String, data: ByteArray, maxPart: Long, expectedParts: Int) {
        val (img, parts) = encode(name, data, maxPart)
        assertEquals(expectedParts, parts.size)
        val out = File(img.parentFile, "py-out")
        val r = Python.fotocodec("decodificar", img.path, "-o", out.path)
        assertEquals(r.output, 0, r.exitCode)
        assertArrayEquals(data, File(out, name).readBytes())
    }

    @Test fun multiparteRenombrada() = pythonDecodes("datos.bin", bytes(50_000), 10_485, 5)

    @Test fun vacio() = pythonDecodes("vacio.bin", ByteArray(0), Fcod1.DEFAULT_MAX_PART_BYTES, 1)

    @Test fun nombreUnicode() =
        pythonDecodes("canción ñ 日本 😀.txt", "Árbol 東京 😀\n".repeat(900).toByteArray(), 8_000, 3)

    @Test fun variosMegas() = pythonDecodes("grande.bin", bytes(3 * 1024 * 1024 + 17), 1024 * 1024, 4)

    @Test fun tamanosPequenos() {
        for (n in listOf(1, 2, 3, 4, 1000)) pythonDecodes("t$n.bin", bytes(n), Fcod1.DEFAULT_MAX_PART_BYTES, 1)
    }

    @Test fun parteFaltanteLaDetectaPython() {
        val (img, _) = encode("f.bin", bytes(50_000), 10_485)
        // Borra las partes 2 y 4 buscándolas por su cabecera (los nombres son aleatorios).
        img.listFiles()!!.filter { Fcod1Decoder.readHeader(it.inputStream()).partIndex in setOf(1, 3) }.forEach { it.delete() }
        val r = Python.fotocodec("decodificar", img.path, "-o", File(img.parentFile, "o").path)
        assertNotEquals(0, r.exitCode)
        assertTrue(r.output, r.output.contains("faltan partes [2, 4]"))
        // Y Kotlin da el mensaje equivalente.
        assertEquals(listOf("faltan partes 2,4 de f.bin"), Fcod1Files.decode(listOf(img), TestSupport.tempDir()).failures)
    }

    @Test fun parteAlteradaLaDetectaPython() {
        val (img, parts) = encode("a.bin", bytes(30_000), 10_485)
        val second = parts.first { Fcod1Decoder.readHeader(it.inputStream()).partIndex == 1 }
        TestSupport.tamperPixel(second, Fcod1.HEADER_SIZE + "a.bin".length + 1234)
        val r = Python.fotocodec("decodificar", img.path, "-o", File(img.parentFile, "o").path)
        assertNotEquals(0, r.exitCode)
        assertTrue(r.output, r.output.contains("el hash no coincide"))
        val report = Fcod1Files.decode(listOf(img), TestSupport.tempDir())
        assertTrue(report.recovered.isEmpty())
        assertTrue(report.failures.single().startsWith("a.bin: el hash no coincide"))
    }
}
