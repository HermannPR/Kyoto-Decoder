package com.hermannpr.kyoto.codec

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
private val IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
private val IEND = "IEND".toByteArray(Charsets.US_ASCII)

private fun OutputStream.writeInt(v: Int) {
    write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
}

private fun writeChunk(out: OutputStream, type: ByteArray, data: ByteArray, len: Int = data.size) {
    out.writeInt(len)
    out.write(type)
    out.write(data, 0, len)
    val crc = CRC32()
    crc.update(type)
    crc.update(data, 0, len)
    out.writeInt(crc.value.toInt())
}

/** Agrupa lo que escribe el Deflater en chunks IDAT de tamaño acotado. */
private class IdatOutputStream(private val out: OutputStream, size: Int) : OutputStream() {
    private val buf = ByteArray(size)
    private var n = 0

    override fun write(b: Int) {
        if (n == buf.size) emit()
        buf[n++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (n == buf.size) emit()
            val k = minOf(l, buf.size - n)
            System.arraycopy(b, o, buf, n, k)
            n += k; o += k; l -= k
        }
    }

    fun emit() {
        if (n > 0) writeChunk(out, IDAT, buf, n)
        n = 0
    }
}

/**
 * Escritor de PNG por *streaming*: la carga es `prefix` + `dataLength` bytes de
 * `data`, colocados como píxeles RGB de una imagen cuadrada y rellenados con ceros.
 * Todas las filas usan el filtro 0, igual que fotocodec.py. Nunca guarda la imagen
 * entera en memoria: solo una fila y un búfer de compresión.
 */
object PngWriter {
    fun writePayload(
        out: OutputStream,
        prefix: ByteArray,
        data: InputStream,
        dataLength: Long,
        level: Int = Fcod1.DEFAULT_COMPRESSION_LEVEL,
        cancel: CancelCheck = CancelCheck.NONE,
        onData: (Int) -> Unit = {},
    ) {
        val side = Fcod1.sideFor(prefix.size + dataLength)
        val rowBytes = side.toLong() * 3
        if (rowBytes > Int.MAX_VALUE - 1) throw Fcod1Exception("imagen demasiado ancha")
        out.write(PNG_SIGNATURE)
        val ihdr = ByteArray(13)
        for (i in 0..3) {
            ihdr[i] = (side ushr (24 - 8 * i)).toByte()
            ihdr[4 + i] = (side ushr (24 - 8 * i)).toByte()
        }
        ihdr[8] = 8 // profundidad
        ihdr[9] = 2 // RGB
        writeChunk(out, IHDR, ihdr)

        val idat = IdatOutputStream(out, 256 * 1024)
        val deflater = Deflater(level)
        try {
            val z = DeflaterOutputStream(idat, deflater, 64 * 1024)
            val row = ByteArray(1 + rowBytes.toInt()) // row[0] = 0: filtro None
            var prefixPos = 0
            var dataLeft = dataLength
            for (y in 0 until side) {
                if (y and 15 == 0) cancel.check()
                var pos = 1
                // 1) cabecera + nombre
                if (prefixPos < prefix.size) {
                    val k = minOf(prefix.size - prefixPos, row.size - pos)
                    System.arraycopy(prefix, prefixPos, row, pos, k)
                    prefixPos += k; pos += k
                }
                // 2) datos del archivo
                if (pos < row.size && dataLeft > 0) {
                    val want = minOf(dataLeft, (row.size - pos).toLong()).toInt()
                    if (readUpTo(data, row, pos, want) < want) {
                        throw Fcod1Exception("el archivo terminó antes de lo esperado (¿cambió mientras se codificaba?)")
                    }
                    pos += want; dataLeft -= want
                    onData(want)
                }
                // 3) relleno
                if (pos < row.size) row.fill(0, pos, row.size)
                z.write(row)
            }
            z.finish()
            idat.emit()
        } finally {
            deflater.end()
        }
        writeChunk(out, IEND, ByteArray(0))
        out.flush()
    }
}

/**
 * Lee un PNG y entrega sus píxeles como bytes RGB (fila por fila), en streaming.
 * Acepta lo mismo que `leer_png` de fotocodec.py: 8 bits, RGB o RGBA (se descarta
 * el alfa), sin entrelazar, los 5 filtros y varios IDAT. Además verifica el CRC
 * de cada chunk que lee (Python no lo hace).
 */
class PngPixelInputStream(source: InputStream) : InputStream() {
    private val src = source
    private val input = DataInputStream(if (source is BufferedInputStream) source else BufferedInputStream(source, 64 * 1024))

    val width: Int
    val height: Int
    val channels: Int

    /** Bytes RGB totales que entrega este stream: ancho × alto × 3. */
    val pixelByteCount: Long

    private val rowLen: Int
    private var idatLeft = 0L
    private val crc = CRC32()
    private var idatEnded = false

    private val inflater = Inflater()
    private val zin: InflaterInputStream
    private val idatStream: InputStream
    private var prev: ByteArray
    private var cur: ByteArray
    private val rgb: ByteArray
    private var rgbPos = 0
    private var rgbLen = 0
    private var rowsDone = 0

    init {
        val sig = ByteArray(8)
        if (readUpTo(input, sig) < 8 || !sig.contentEquals(PNG_SIGNATURE)) throw NotKyotoException("no es PNG")
        var w = -1; var h = -1; var ch = 0
        while (true) {
            val (len, type) = readChunkHeader() ?: throw Fcod1Exception("PNG incompleto")
            if (type == "IDAT") {
                if (w < 0) throw Fcod1Exception("PNG sin IHDR")
                idatLeft = len
                break
            }
            if (type == "IEND") throw Fcod1Exception("PNG sin datos de imagen")
            if (type == "IHDR") {
                if (len != 13L) throw Fcod1Exception("IHDR inválido")
                val d = ByteArray(13)
                readChunkData(d)
                finishChunk(type)
                val bb = java.nio.ByteBuffer.wrap(d)
                w = bb.int; h = bb.int
                val depth = d[8].toInt() and 0xFF
                val color = d[9].toInt() and 0xFF
                val interlace = d[12].toInt() and 0xFF
                if (depth != 8 || (color != 2 && color != 6) || interlace != 0) {
                    throw NotKyotoException("PNG no soportado (se esperaba RGB/RGBA de 8 bits sin entrelazar)")
                }
                if (w <= 0 || h <= 0) throw Fcod1Exception("IHDR inválido")
                ch = if (color == 2) 3 else 4
            } else {
                skipChunkData(len)
                finishChunk(type)
            }
        }
        width = w; height = h; channels = ch
        val rl = w.toLong() * ch
        if (rl > 256L * 1024 * 1024) throw Fcod1Exception("imagen demasiado ancha")
        rowLen = rl.toInt()
        pixelByteCount = w.toLong() * h * 3
        idatStream = IdatStream()
        zin = InflaterInputStream(idatStream, inflater, 64 * 1024)
        prev = ByteArray(rowLen)
        cur = ByteArray(rowLen)
        rgb = ByteArray(w * 3)
    }

    /** Devuelve (largo, tipo) o null si el archivo terminó. El CRC arranca con el tipo. */
    private fun readChunkHeader(): Pair<Long, String>? {
        val hdr = ByteArray(8)
        val n = readUpTo(input, hdr)
        if (n == 0) return null
        if (n < 8) throw Fcod1Exception("PNG truncado")
        val len = java.nio.ByteBuffer.wrap(hdr, 0, 4).int.toLong() and 0xFFFFFFFFL
        if (len > Int.MAX_VALUE) throw Fcod1Exception("chunk PNG inválido")
        crc.reset()
        crc.update(hdr, 4, 4)
        return len to String(hdr, 4, 4, Charsets.ISO_8859_1)
    }

    private fun readChunkData(d: ByteArray) {
        try { readFully(input, d) } catch (e: EOFException) { throw Fcod1Exception("PNG truncado") }
        crc.update(d)
    }

    private fun skipChunkData(len: Long) {
        val buf = ByteArray(8192)
        var left = len
        while (left > 0) {
            val k = minOf(left, buf.size.toLong()).toInt()
            if (readUpTo(input, buf, 0, k) < k) throw Fcod1Exception("PNG truncado")
            crc.update(buf, 0, k)
            left -= k
        }
    }

    private fun finishChunk(type: String) {
        val c = ByteArray(4)
        if (readUpTo(input, c) < 4) throw Fcod1Exception("PNG truncado")
        val stored = java.nio.ByteBuffer.wrap(c).int.toLong() and 0xFFFFFFFFL
        if (stored != crc.value) throw Fcod1Exception("imagen dañada: CRC incorrecto en el chunk $type")
    }

    /** Concatena el contenido de los IDAT consecutivos, verificando el CRC de cada uno. */
    private inner class IdatStream : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (idatLeft == 0L) {
                if (idatEnded) return -1
                finishChunk("IDAT")
                val next = readChunkHeader()
                if (next == null || next.second != "IDAT") {
                    idatEnded = true
                    return -1
                }
                idatLeft = next.first
            }
            val k = input.read(b, off, minOf(len.toLong(), idatLeft).toInt())
            if (k < 0) throw Fcod1Exception("PNG truncado")
            crc.update(b, off, k)
            idatLeft -= k
            return k
        }
    }

    private fun nextRow(): Boolean {
        if (rowsDone >= height) return false
        val f = zin.read()
        if (f < 0) throw Fcod1Exception("imagen dañada: faltan datos de píxeles")
        if (readUpTo(zin, cur) < rowLen) throw Fcod1Exception("imagen dañada: faltan datos de píxeles")
        unfilter(f, cur, prev, channels)
        val t = prev; prev = cur; cur = t
        if (channels == 3) {
            System.arraycopy(prev, 0, rgb, 0, rowLen)
        } else {
            var j = 0
            var i = 0
            while (i < rowLen) {
                rgb[j] = prev[i]; rgb[j + 1] = prev[i + 1]; rgb[j + 2] = prev[i + 2]
                j += 3; i += 4
            }
        }
        rgbPos = 0
        rgbLen = width * 3
        rowsDone++
        return true
    }

    override fun read(): Int {
        if (rgbPos >= rgbLen && !nextRow()) return -1
        return rgb[rgbPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (rgbPos >= rgbLen && !nextRow()) return -1
        val k = minOf(len, rgbLen - rgbPos)
        System.arraycopy(rgb, rgbPos, b, off, k)
        rgbPos += k
        return k
    }

    /**
     * Lee el resto de la imagen (relleno incluido) y comprueba el CRC de todos los
     * IDAT. Sirve para detectar una imagen dañada aunque el daño esté después de los datos útiles.
     */
    fun verifyRest() {
        val buf = ByteArray(64 * 1024)
        while (read(buf, 0, buf.size) >= 0) { /* filas restantes */ }
        while (idatStream.read(buf, 0, buf.size) >= 0) { /* resto de IDAT: verifica CRC */ }
    }

    override fun close() {
        inflater.end()
        src.close()
    }

    companion object {
        internal fun unfilter(f: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
            val n = cur.size
            when (f) {
                0 -> {}
                1 -> for (x in bpp until n) cur[x] = (cur[x] + cur[x - bpp]).toByte()
                2 -> for (x in 0 until n) cur[x] = (cur[x] + prev[x]).toByte()
                3 -> for (x in 0 until n) {
                    val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                    val up = prev[x].toInt() and 0xFF
                    cur[x] = (cur[x] + ((a + up) ushr 1)).toByte()
                }
                4 -> for (x in 0 until n) {
                    val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                    val up = prev[x].toInt() and 0xFF
                    val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                    cur[x] = (cur[x] + paeth(a, up, c)).toByte()
                }
                else -> throw Fcod1Exception("imagen dañada: filtro PNG desconocido ($f)")
            }
        }

        internal fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}
