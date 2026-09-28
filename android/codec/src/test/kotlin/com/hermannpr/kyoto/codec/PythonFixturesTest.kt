package com.hermannpr.kyoto.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * (a) Python -> Kotlin: imágenes generadas con `fotocodec.py codificar`
 * (android/tools/gen_fixtures.py) deben decodificarse byte a byte idénticas.
 */
class PythonFixturesTest {
    private fun decode(case: String): Pair<File, DecodeReport> {
        val dir = TestSupport.fixture(case)
        val out = TestSupport.tempDir("rec-$case")
        return dir to Fcod1Files.decode(listOf(File(dir, "imagenes")), out)
    }

    private fun assertMatchesOriginals(dir: File, report: DecodeReport) {
        val originals = File(dir, "originales").listFiles()!!.sortedBy { it.name }
        val recovered = report.recovered.sortedBy { it.name }
        assertEquals(originals.map { it.name }, recovered.map { it.name })
        for ((o, r) in originals.zip(recovered)) {
            assertArrayEquals("contenido de ${o.name}", o.readBytes(), r.readBytes())
        }
    }

    private fun assertClean(case: String) {
        val (dir, report) = decode(case)
        assertEquals(emptyList<String>(), report.failures)
        assertTrue(report.damaged.isEmpty())
        assertTrue(report.ignored.isEmpty())
        assertMatchesOriginals(dir, report)
    }

    @Test fun basico() = assertClean("basico")

    @Test fun multiparteRenombradaYDesordenada() = assertClean("multiparte")

    @Test fun archivoVacio() = assertClean("vacio")

    @Test fun nombreUnicode() = assertClean("unicode")

    @Test fun pngReguardadoConFiltrosRgbYRgba() = assertClean("filtros")

    @Test fun mezclaConFotosNormales() {
        val (dir, report) = decode("mezcla")
        assertEquals(emptyList<String>(), report.failures)
        assertMatchesOriginals(dir, report)
        assertEquals(setOf("foto-normal.png", "no-es-png.png"), report.ignored.map { it.source.name }.toSet())
        assertTrue(report.damaged.isEmpty())
    }

    @Test fun partesFaltantes() {
        val (_, report) = decode("faltante")
        assertTrue(report.recovered.isEmpty())
        assertEquals(listOf("faltan partes 2,4 de faltante.bin"), report.failures)
    }

    @Test fun parteAlteradaSeDetectaPorHash() {
        val (_, report) = decode("alterada")
        assertTrue(report.recovered.isEmpty())
        assertEquals(1, report.failures.size)
        assertTrue(report.failures[0], report.failures[0].startsWith("alterada.bin: el hash no coincide"))
    }

    @Test fun parteDanadaSeSenala() {
        val (_, report) = decode("danada")
        assertTrue(report.recovered.isEmpty())
        assertEquals(1, report.failures.size)
        val msg = report.failures[0]
        println("danada -> $msg; dañadas al escanear: ${report.damaged.map { it.reason }}")
        // Según dónde cayó el daño se detecta al escanear (y falta la parte) o al recuperar.
        assertTrue(msg, msg == "falta la parte 3 de danada.bin" || msg.startsWith("danada.bin: la parte 3 está dañada"))
    }
}
