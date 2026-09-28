package com.hermannpr.kyoto.codec

import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Tamaño y SHA-256 de un archivo (primera pasada de la codificación). */
class FileDigest(val size: Long, val sha256: ByteArray)

class EncodeResult(val name: String, val size: Long, val sha256: ByteArray, val partNames: List<String>)

/** Destino de cada parte: devuelve el stream donde escribir el PNG. */
fun interface PartOutput {
    fun open(partName: String, partIndex: Int, total: Int): OutputStream
}

/**
 * Codificador FCOD1 por streaming. Como el SHA-256 completo va en la cabecera de
 * cada parte, se hacen dos pasadas: [digest] (tamaño + hash) y [encode] (escritura
 * de las partes). En memoria solo hay una fila de la imagen y búferes pequeños.
 */
class Fcod1Encoder(
    val maxPartBytes: Long = Fcod1.DEFAULT_MAX_PART_BYTES,
    val compressionLevel: Int = Fcod1.DEFAULT_COMPRESSION_LEVEL,
) {
    init {
        require(maxPartBytes >= 1) { "maxPartBytes debe ser >= 1" }
    }

    fun digest(input: InputStream, cancel: CancelCheck = CancelCheck.NONE, onBytes: (Int) -> Unit = {}): FileDigest {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        var size = 0L
        while (true) {
            cancel.check()
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            size += n
            onBytes(n)
        }
        return FileDigest(size, md.digest())
    }

    /**
     * Escribe las partes leyendo [input] de principio a fin. [digest] debe ser el de
     * [digest] sobre el mismo contenido; si el archivo cambió entre pasadas se lanza
     * [Fcod1Exception] (las partes ya escritas quedan inválidas y el llamador debe borrarlas).
     */
    fun encode(
        name: String,
        digest: FileDigest,
        input: InputStream,
        output: PartOutput,
        cancel: CancelCheck = CancelCheck.NONE,
        onBytes: (Int) -> Unit = {},
        onPart: (partIndex: Int, total: Int) -> Unit = { _, _ -> },
    ): EncodeResult {
        if (name.toByteArray(Charsets.UTF_8).size > Fcod1.MAX_NAME_BYTES) throw Fcod1Exception("nombre demasiado largo")
        val total = Fcod1.partCount(digest.size, maxPartBytes)
        val md = MessageDigest.getInstance("SHA-256")
        val din = DigestInputStream(input, md)
        val names = ArrayList<String>(total)
        for (i in 0 until total) {
            cancel.check()
            val offset = i.toLong() * maxPartBytes
            val chunk = minOf(maxPartBytes, digest.size - offset)
            val header = PartHeader.forPart(digest.sha256, i, total, chunk, digest.size, name).encode()
            val partName = Fcod1.partFileName(name, i, total)
            onPart(i, total)
            output.open(partName, i, total).use { out ->
                PngWriter.writePayload(out, header, din, chunk, compressionLevel, cancel, onBytes)
            }
            names += partName
        }
        if (din.read() >= 0 || !md.digest().contentEquals(digest.sha256)) {
            throw Fcod1Exception("el archivo cambió mientras se codificaba; vuelve a intentarlo")
        }
        return EncodeResult(name, digest.size, digest.sha256, names)
    }

    /** Las dos pasadas juntas; [open] se llama dos veces y debe devolver el mismo contenido. */
    fun encode(
        name: String,
        open: () -> InputStream,
        output: PartOutput,
        cancel: CancelCheck = CancelCheck.NONE,
        onBytes: (Int) -> Unit = {},
    ): EncodeResult {
        val d = open().use { digest(it, cancel, onBytes) }
        return open().use { encode(name, d, it, output, cancel, onBytes) }
    }
}
