package com.hermannpr.kyoto.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/** Pruebas del codec Kotlin por sí solo (formato, ida y vuelta, robustez). */
class Fcod1Test {
    private val rnd = Random(7)
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun ejemploDelDocumento() {
        // docs/formato-fcod1.md, sección 7: "hola" en hi.txt.
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("hola".toByteArray())
        val header = PartHeader.forPart(sha, 0, 1, 4, 4, "hi.txt").encode()
        val expected = hex(
            "46434F4431 B221D9DBB083A7F3 00000000 00000001 0000000000000004 0000000000000004 " +
                "B221D9DBB083A7F33428D7C2A3C3198AE925614D70210E28716CCAA7CD4DDB79 0006 68692E747874"
        )
        assertArrayEquals(expected, header)
        assertEquals(6, Fcod1.sideFor(header.size + 4L))
        val png = ByteArrayOutputStream()
        PngWriter.writePayload(png, header, "hola".toByteArray().inputStream(), 4)
        val px = PngPixelInputStream(png.toByteArray().inputStream())
        assertEquals(6, px.width)
        val pixels = px.readBytes()
        assertEquals(108, pixels.size)
        assertArrayEquals(header + "hola".toByteArray() + ByteArray(27), pixels)
    }

    @Test fun calculos() {
        assertEquals(20_971_520L, Fcod1.DEFAULT_MAX_PART_BYTES)
        assertEquals(104_857L, Fcod1.maxPartBytesFromMb(0.1))
        assertEquals(1, Fcod1.partCount(0, 10))
        assertEquals(5, Fcod1.partCount(50_000, 10_485))
        assertEquals(1, Fcod1.sideFor(0))
        assertEquals(1, Fcod1.sideFor(3))
        assertEquals(2, Fcod1.sideFor(4))
        for (n in listOf(1L, 2, 15, 16, 17, 99_999_999, 4_000_000_000_000)) {
            val r = Fcod1.ceilSqrt(n)
            assertTrue(r * r >= n && (r - 1) * (r - 1) < n)
        }
        assertEquals("foto.zip.0003de0012.png", Fcod1.partFileName("foto.zip", 2, 12))
    }

    @Test fun nombresSeguros() {
        assertEquals("escapado.txt", Fcod1.safeOutputName("../../escapado.txt"))
        assertEquals("x.txt", Fcod1.safeOutputName("C:\\Windows\\x.txt"))
        assertEquals("archivo", Fcod1.safeOutputName(".."))
        assertEquals("archivo", Fcod1.safeOutputName(""))
    }

    @Test fun idaYVueltaVariosTamanos() {
        for (n in listOf(0, 1, 2, 3, 4, 70, 71, 72, 1000, 65_537)) {
            val work = TestSupport.tempDir()
            val src = File(work, "a.bin").apply { writeBytes(bytes(n)) }
            Fcod1Files.encode(src, File(work, "img"))
            val report = Fcod1Files.decode(listOf(File(work, "img")), File(work, "out"))
            assertArrayEquals("n=$n", src.readBytes(), report.recovered.single().readBytes())
        }
    }

    @Test fun contenidoIgualConNombresDistintosSeRecuperaDosVeces() {
        val work = TestSupport.tempDir()
        val data = bytes(5000)
        for (n in listOf("uno.bin", "dos.bin")) {
            Fcod1Files.encode(File(work, n).apply { writeBytes(data) }, File(work, "img"), 2000)
        }
        val report = Fcod1Files.decode(listOf(File(work, "img")), File(work, "out"))
        assertEquals(listOf("dos.bin", "uno.bin"), report.recovered.map { it.name }.sorted())
    }

    @Test fun partesDuplicadasNoMolestan() {
        val work = TestSupport.tempDir()
        val src = File(work, "d.bin").apply { writeBytes(bytes(9000)) }
        val parts = Fcod1Files.encode(src, File(work, "img"), 4000)
        parts[1].copyTo(File(work, "img/copia.png"))
        val report = Fcod1Files.decode(listOf(File(work, "img")), File(work, "out"))
        assertArrayEquals(src.readBytes(), report.recovered.single().readBytes())
    }

    @Test fun archivoQueCambiaDuranteLaCodificacion() {
        val enc = Fcod1Encoder(1000)
        var content = bytes(3000)
        val d = enc.digest(content.inputStream())
        content = bytes(3000)
        try {
            enc.encode("x", d, content.inputStream(), { _, _, _ -> ByteArrayOutputStream() })
            throw AssertionError("debió fallar")
        } catch (e: Fcod1Exception) {
            assertTrue(e.message!!.contains("cambió"))
        }
    }

    @Test fun cancelacion() {
        val enc = Fcod1Encoder(1000)
        var calls = 0
        try {
            enc.encode("x", { bytes(100_000).inputStream() }, { _, _, _ -> ByteArrayOutputStream() },
                cancel = { if (++calls > 3) throw java.util.concurrent.CancellationException() })
            throw AssertionError("debió cancelarse")
        } catch (e: java.util.concurrent.CancellationException) {
            // ok
        }
    }

    /** Streaming: 64 MB en partes de 20 MB sin cargar el archivo completo en memoria. */
    @Test fun archivoGrandePorStreaming() {
        val work = TestSupport.tempDir()
        val size = 64L * 1024 * 1024 + 5
        val src = File(work, "grande.bin")
        val block = bytes(1024 * 1024)
        src.outputStream().buffered().use { o ->
            var left = size
            while (left > 0) { val k = minOf(left, block.size.toLong()).toInt(); o.write(block, 0, k); left -= k }
        }
        val parts = Fcod1Files.encode(src, File(work, "img"))
        assertEquals(4, parts.size)
        val report = Fcod1Files.decode(listOf(File(work, "img")), File(work, "out"))
        val out = report.recovered.single()
        assertEquals(size, out.length())
        val sha = { f: File -> java.security.MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            md.digest()
        } }
        assertArrayEquals(sha(src), sha(out))
        src.delete(); out.delete(); parts.forEach { it.delete() }
    }
}
