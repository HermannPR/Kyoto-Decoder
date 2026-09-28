package com.hermannpr.kyoto.codec

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

object TestSupport {
    fun tempDir(prefix: String = "kyoto"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    fun fixture(case: String): File {
        val url = TestSupport::class.java.getResource("/fixtures/$case/imagenes")
            ?: error("no existe el fixture $case; ejecuta: py -3 android/tools/gen_fixtures.py")
        return File(url.toURI()).parentFile
    }

    /** Reescribe un PNG con los mismos píxeles pero un byte cambiado (sigue siendo PNG válido). */
    fun tamperPixel(png: File, offset: Int) {
        val pixels = PngPixelInputStream(png.inputStream()).use { it.readBytes() }
        pixels[offset] = (pixels[offset].toInt() xor 0x01).toByte()
        png.outputStream().use { PngWriter.writePayload(it, pixels, ByteArray(0).inputStream(), 0) }
    }

    /** Renombra cada archivo con un nombre aleatorio (el decodificador no debe depender de nombres). */
    fun renameRandomly(files: List<File>, rnd: java.util.Random): List<File> = files.map { f ->
        val dest = File(f.parentFile, "x%08x.png".format(rnd.nextInt()))
        check(f.renameTo(dest))
        dest
    }
}

/** Ejecuta fotocodec.py. Se configura con -Dkyoto.python / KYOTO_PYTHON ("py -3" en Windows). */
object Python {
    val repoRoot: File = File(System.getProperty("kyoto.repoRoot") ?: "../..").absoluteFile
    val script: File = File(repoRoot, "fotocodec.py")

    private val command: List<String> by lazy {
        val custom = System.getProperty("kyoto.python") ?: System.getenv("KYOTO_PYTHON")
        when {
            custom != null -> custom.split(" ").filter { it.isNotBlank() }
            System.getProperty("os.name").lowercase().contains("win") -> listOf("py", "-3")
            else -> listOf("python3")
        }
    }

    val available: Boolean by lazy {
        script.isFile && runCatching { run(listOf("--version")).exitCode == 0 }.getOrDefault(false)
    }

    class Result(val exitCode: Int, val output: String)

    fun fotocodec(vararg args: String): Result = run(listOf(script.absolutePath) + args)

    private fun run(args: List<String>): Result {
        val pb = ProcessBuilder(command + args).redirectErrorStream(true)
        pb.environment()["PYTHONUTF8"] = "1"
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val p = pb.start()
        val buf = ByteArrayOutputStream()
        p.inputStream.copyTo(buf)
        check(p.waitFor(5, TimeUnit.MINUTES)) { "python no terminó" }
        return Result(p.exitValue(), buf.toString(Charsets.UTF_8))
    }
}
