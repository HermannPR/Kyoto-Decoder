package com.hermannpr.kyoto.codec

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.SortedMap
import java.util.TreeMap

/** Una imagen reconocida como parte FCOD1. */
class ScannedPart<S>(val source: S, val header: PartHeader)

/** Una imagen descartada y el motivo (en español, para mostrar al usuario). */
class RejectedImage<S>(val source: S, val reason: String)

/**
 * Todas las partes encontradas de un archivo. Se agrupan por
 * (id, sha, total, tamaño, nombre) sin importar nombres ni orden de las imágenes.
 */
class FileGroup<S>(val header: PartHeader) {
    val parts: SortedMap<Int, ScannedPart<S>> = TreeMap()

    /** Imágenes repetidas (mismo índice de parte); se usa la primera. */
    val duplicates = ArrayList<ScannedPart<S>>()

    val name: String get() = header.name
    val total: Int get() = header.total
    val totalSize: Long get() = header.totalSize

    /** Números de parte faltantes, 1-based (como en los nombres de archivo). */
    val missingParts: List<Int>
        get() = (0 until total).filter { it !in parts }.map { it + 1 }

    val isComplete: Boolean get() = parts.size == total

    /** Suma de los `largo` de las partes (debe coincidir con el tamaño total). */
    val sumOfChunks: Long get() = parts.values.sumOf { it.header.chunkLength }

    /** Ej.: "faltan partes 3,7 de foto.zip" o "falta la parte 2 de foto.zip". */
    fun missingDescription(): String {
        val m = missingParts
        return if (m.size == 1) "falta la parte ${m[0]} de $name"
        else "faltan partes ${m.joinToString(",")} de $name"
    }
}

class ScanResult<S>(
    val groups: List<FileGroup<S>>,
    /** No son imágenes de Kyoto (fotos normales, otros formatos). */
    val ignored: List<RejectedImage<S>>,
    /** Parecen PNG pero están dañadas o su cabecera es inválida. */
    val damaged: List<RejectedImage<S>>,
)

/** Fallo al recuperar un archivo concreto. [partNumber] es 1-based si se conoce. */
class RecoveryException(message: String, val partNumber: Int? = null, val source: Any? = null, cause: Throwable? = null) :
    Fcod1Exception(message, cause)

object Fcod1Decoder {
    /** Abre una imagen y lee solo su cabecera. */
    fun readHeader(png: InputStream): PartHeader = png.use { PngPixelInputStream(it).use { px -> PartHeader.read(px) } }

    /**
     * Lee la cabecera de cada imagen (solo las primeras filas, es rápido) y agrupa
     * las partes por archivo. No aborta por imágenes malas: las clasifica.
     */
    fun <S> scan(
        sources: Iterable<S>,
        open: (S) -> InputStream,
        cancel: CancelCheck = CancelCheck.NONE,
        onScanned: (S) -> Unit = {},
    ): ScanResult<S> {
        val groups = LinkedHashMap<List<Any>, FileGroup<S>>()
        val ignored = ArrayList<RejectedImage<S>>()
        val damaged = ArrayList<RejectedImage<S>>()
        for (s in sources) {
            cancel.check()
            try {
                val h = readHeader(open(s))
                val key = listOf(h.sha256Hex, h.total, h.totalSize, h.name)
                val g = groups.getOrPut(key) { FileGroup(h) }
                val p = ScannedPart(s, h)
                if (h.partIndex in g.parts) g.duplicates += p else g.parts[h.partIndex] = p
            } catch (e: NotKyotoException) {
                ignored += RejectedImage(s, e.message ?: "no es de Kyoto")
            } catch (e: IOException) {
                damaged += RejectedImage(s, describe(e))
            } catch (e: RuntimeException) {
                damaged += RejectedImage(s, "imagen dañada: ${e.message ?: e.javaClass.simpleName}")
            }
            onScanned(s)
        }
        return ScanResult(groups.values.toList(), ignored, damaged)
    }

    private fun describe(e: IOException): String = when (e) {
        is Fcod1Exception -> e.message ?: "imagen dañada"
        is java.util.zip.ZipException -> "imagen dañada: datos comprimidos inválidos"
        is java.io.EOFException -> "imagen dañada: archivo truncado"
        else -> "no se pudo leer: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Reensambla [group] en [out] en orden de parte, verificando tamaño y SHA-256.
     * Si lanza, lo escrito en [out] no es válido y el llamador debe descartarlo.
     */
    fun <S> recover(
        group: FileGroup<S>,
        open: (S) -> InputStream,
        out: OutputStream,
        cancel: CancelCheck = CancelCheck.NONE,
        onBytes: (Int) -> Unit = {},
    ) {
        if (!group.isComplete) throw RecoveryException(group.missingDescription())
        if (group.sumOfChunks != group.totalSize) {
            throw RecoveryException("${group.name}: los tamaños de las partes no cuadran; alguna imagen se alteró")
        }
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        var written = 0L
        for ((index, part) in group.parts) {
            cancel.check()
            val number = index + 1
            val stream = try {
                val raw = open(part.source)
                try { PngPixelInputStream(raw) } catch (e: IOException) { raw.close(); throw e }
            } catch (e: IOException) {
                throw RecoveryException("${group.name}: la parte $number no se pudo leer (${describe(e)})", number, part.source, e)
            }
            stream.use { px ->
                var left: Long
                try {
                    val h = PartHeader.read(px)
                    if (h.partIndex != index || !h.sha256.contentEquals(group.header.sha256)) {
                        throw Fcod1Exception("la cabecera cambió desde el escaneo")
                    }
                    left = h.chunkLength
                } catch (e: IOException) {
                    throw RecoveryException("${group.name}: la parte $number está dañada (${describe(e)})", number, part.source, e)
                }
                while (left > 0) {
                    cancel.check()
                    val n = try {
                        px.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    } catch (e: IOException) {
                        throw RecoveryException("${group.name}: la parte $number está dañada (${describe(e)})", number, part.source, e)
                    }
                    if (n < 0) throw RecoveryException("${group.name}: la parte $number está incompleta", number, part.source)
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    written += n
                    left -= n
                    onBytes(n)
                }
                try {
                    px.verifyRest()
                } catch (e: IOException) {
                    throw RecoveryException("${group.name}: la parte $number está dañada (${describe(e)})", number, part.source, e)
                }
            }
        }
        out.flush()
        if (written != group.totalSize || !md.digest().contentEquals(group.header.sha256)) {
            throw RecoveryException("${group.name}: el hash no coincide; alguna imagen se alteró (¿recomprimida o editada?)")
        }
    }
}
