package com.hermannpr.kyoto.codec

import java.io.File

/** Resultado de recuperar una carpeta de imágenes con [Fcod1Files.decode]. */
class DecodeReport(
    val recovered: List<File>,
    /** Mensajes por archivo no recuperado ("faltan partes 3,7 de foto.zip", "el hash no coincide"...). */
    val failures: List<String>,
    val ignored: List<RejectedImage<File>>,
    val damaged: List<RejectedImage<File>>,
)

/** Atajos sobre `java.io.File` (pruebas y uso de escritorio). La app usa SAF. */
object Fcod1Files {
    fun encode(file: File, outDir: File, maxPartBytes: Long = Fcod1.DEFAULT_MAX_PART_BYTES): List<File> {
        outDir.mkdirs()
        val result = Fcod1Encoder(maxPartBytes).encode(
            file.name,
            open = { file.inputStream() },
            output = { name, _, _ -> File(outDir, name).outputStream().buffered(256 * 1024) },
        )
        return result.partNames.map { File(outDir, it) }
    }

    /** Todos los `.png` bajo [dir] (recursivo), en cualquier orden. */
    fun listPngs(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.lowercase().endsWith(".png") }.toList()

    fun decode(inputs: List<File>, outDir: File): DecodeReport {
        val files = inputs.flatMap { if (it.isDirectory) listPngs(it) else listOf(it) }
        val scan = Fcod1Decoder.scan(files, open = { it.inputStream() })
        outDir.mkdirs()
        val recovered = ArrayList<File>()
        val failures = ArrayList<String>()
        for (g in scan.groups) {
            if (!g.isComplete) {
                failures += g.missingDescription()
                continue
            }
            val dest = uniqueFile(outDir, Fcod1.safeOutputName(g.name))
            try {
                dest.outputStream().buffered(256 * 1024).use { out ->
                    Fcod1Decoder.recover(g, open = { it.inputStream() }, out = out)
                }
                recovered += dest
            } catch (e: Fcod1Exception) {
                dest.delete()
                failures += e.message ?: "error"
            }
        }
        return DecodeReport(recovered, failures, scan.ignored, scan.damaged)
    }

    /** `nombre.ext`, `nombre (1).ext`, `nombre (2).ext`... el primero que no exista. */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var i = 1
        while (f.exists()) {
            f = File(dir, name.substring(0, dot) + " ($i)" + name.substring(dot))
            i++
        }
        return f
    }
}
