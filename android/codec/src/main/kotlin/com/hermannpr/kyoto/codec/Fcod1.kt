package com.hermannpr.kyoto.codec

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.sqrt

/**
 * Constantes y cálculos del formato FCOD1 (ver docs/formato-fcod1.md).
 * Todo es compatible con `fotocodec.py`.
 */
object Fcod1 {
    /** "FCOD1" en ASCII. */
    val MAGIC: ByteArray = "FCOD1".toByteArray(Charsets.US_ASCII)

    /** Tamaño fijo de `struct.Struct(">5s8sIIQQ32sH")`. */
    const val HEADER_SIZE = 71

    /** Igual que `--max-mb 20` de fotocodec.py. */
    const val DEFAULT_MAX_PART_BYTES: Long = 20L * 1024 * 1024

    const val MAX_NAME_BYTES = 0xFFFF

    /** Nivel zlib que usa la app. No afecta la compatibilidad (Python usa 9). */
    const val DEFAULT_COMPRESSION_LEVEL = 6

    /** `max(1, int(max_mb * 1024 * 1024))`. */
    fun maxPartBytesFromMb(mb: Double): Long = maxOf(1L, (mb * 1024 * 1024).toLong())

    /** `max(1, ceil(tam / trozo))`. */
    fun partCount(size: Long, maxPartBytes: Long): Int {
        require(size >= 0 && maxPartBytes >= 1)
        val n = maxOf(1L, (size + maxPartBytes - 1) / maxPartBytes)
        if (n > Int.MAX_VALUE) throw Fcod1Exception("demasiadas partes ($n); usa un tamaño de parte mayor")
        return n.toInt()
    }

    /** Lado del PNG cuadrado para una carga de [payloadLength] bytes. */
    fun sideFor(payloadLength: Long): Int {
        val pixels = (payloadLength + 2) / 3
        val side = maxOf(1L, ceilSqrt(pixels))
        if (side > 1_000_000L) throw Fcod1Exception("parte demasiado grande para una imagen")
        return side.toInt()
    }

    /** Raíz cuadrada entera redondeada hacia arriba (exacta). */
    fun ceilSqrt(n: Long): Long {
        if (n <= 0) return 0
        var r = sqrt(n.toDouble()).toLong()
        while (r * r > n) r--
        while (r * r < n) r++
        return r
    }

    /** Nombre que usa fotocodec.py: `foto.zip.0003de0012.png` (informativo). */
    fun partFileName(name: String, partIndex: Int, total: Int): String =
        String.format(Locale.ROOT, "%s.%04dde%04d.png", name, partIndex + 1, total)

    /**
     * Solo el nombre base (como `os.path.basename`), para que una imagen maliciosa
     * no pueda escribir fuera de la carpeta de salida.
     */
    fun safeOutputName(name: String): String {
        val base = name.substring(maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
        val clean = base.filter { it >= ' ' && it != '\u007f' }.trim()
        return if (clean.isEmpty() || clean == "." || clean == "..") "archivo" else clean
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Error de formato o de datos (imagen dañada, cabecera inválida, etc.). */
open class Fcod1Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

/** La imagen no es de Kyoto (no es PNG, PNG no soportado o sin magia FCOD1): se ignora. */
class NotKyotoException(message: String) : Fcod1Exception(message)

/** Cabecera de una parte. [partIndex] es 0-based como en el formato. */
class PartHeader(
    val fileId: ByteArray,
    val partIndex: Int,
    val total: Int,
    val chunkLength: Long,
    val totalSize: Long,
    val sha256: ByteArray,
    val name: String,
) {
    val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8)
    val encodedSize: Int get() = Fcod1.HEADER_SIZE + nameBytes.size
    val fileIdHex: String get() = Fcod1.hex(fileId)
    val sha256Hex: String get() = Fcod1.hex(sha256)

    fun encode(): ByteArray {
        if (nameBytes.size > Fcod1.MAX_NAME_BYTES) throw Fcod1Exception("nombre demasiado largo")
        val b = ByteBuffer.allocate(encodedSize) // big-endian por defecto
        b.put(Fcod1.MAGIC)
        b.put(fileId, 0, 8)
        b.putInt(partIndex)
        b.putInt(total)
        b.putLong(chunkLength)
        b.putLong(totalSize)
        b.put(sha256, 0, 32)
        b.putShort(nameBytes.size.toShort())
        b.put(nameBytes)
        return b.array()
    }

    companion object {
        fun forPart(sha256: ByteArray, partIndex: Int, total: Int, chunkLength: Long, totalSize: Long, name: String) =
            PartHeader(sha256.copyOf(8), partIndex, total, chunkLength, totalSize, sha256.copyOf(), name)

        /**
         * Lee la cabecera desde el inicio de los bytes de píxeles. Deja [pixels]
         * posicionado en el primer byte de datos del archivo.
         */
        fun read(pixels: InputStream): PartHeader {
            val magic = ByteArray(5)
            if (readUpTo(pixels, magic) < 5 || !magic.contentEquals(Fcod1.MAGIC)) {
                throw NotKyotoException("no es una imagen de Kyoto (sin firma FCOD1)")
            }
            val rest = ByteArray(Fcod1.HEADER_SIZE - 5)
            if (readUpTo(pixels, rest) < rest.size) throw Fcod1Exception("cabecera incompleta")
            val b = ByteBuffer.wrap(rest)
            val id = ByteArray(8).also { b.get(it) }
            val part = b.int.toLong() and 0xFFFFFFFFL
            val total = b.int.toLong() and 0xFFFFFFFFL
            val chunk = b.long
            val size = b.long
            val sha = ByteArray(32).also { b.get(it) }
            val nameLen = b.short.toInt() and 0xFFFF
            if (total < 1 || total > Int.MAX_VALUE) throw Fcod1Exception("cabecera inválida: total de partes $total")
            if (part >= total) throw Fcod1Exception("cabecera inválida: parte ${part + 1} de $total")
            if (size < 0 || chunk < 0 || chunk > size) throw Fcod1Exception("cabecera inválida: tamaños")
            if (!id.contentEquals(sha.copyOf(8))) throw Fcod1Exception("cabecera inválida: id no coincide con el SHA-256")
            val nameBytes = ByteArray(nameLen)
            if (readUpTo(pixels, nameBytes) < nameLen) throw Fcod1Exception("cabecera incompleta (nombre)")
            val name = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes)).toString()
            } catch (e: CharacterCodingException) {
                throw Fcod1Exception("cabecera inválida: el nombre no es UTF-8")
            }
            return PartHeader(id, part.toInt(), total.toInt(), chunk, size, sha, name)
        }
    }
}

/** Lee hasta llenar [buf] o llegar al final; devuelve cuántos bytes leyó. */
internal fun readUpTo(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
    var n = 0
    while (n < len) {
        val r = input.read(buf, off + n, len - n)
        if (r < 0) break
        n += r
    }
    return n
}

internal fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
    if (readUpTo(input, buf, off, len) < len) throw EOFException("fin de datos inesperado")
}

/** Permite cancelar operaciones largas: debe lanzar una excepción si se canceló. */
fun interface CancelCheck {
    fun check()

    companion object {
        val NONE = CancelCheck { }
    }
}
