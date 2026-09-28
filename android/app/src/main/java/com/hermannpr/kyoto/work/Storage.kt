package com.hermannpr.kyoto.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.hermannpr.kyoto.codec.Fcod1Files
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Un archivo de entrada (del selector o compartido desde otra app). */
data class InputFile(val uri: Uri, val name: String, val size: Long?)

/** Una imagen a escanear para recuperar. */
data class ImageSource(val uri: Uri, val name: String)

/** Dónde escribir: una carpeta elegida con SAF o una carpeta propia de la app. */
sealed interface OutputTarget {
    val label: String

    data class Tree(val uri: Uri, override val label: String) : OutputTarget
    data class Local(val dir: File) : OutputTarget {
        override val label: String get() = dir.absolutePath
    }
}

/** Qué recuperar: una carpeta (con subcarpetas) o imágenes sueltas. */
sealed interface RecoverInput {
    data class Folder(val treeUri: Uri, val label: String) : RecoverInput
    data class Images(val images: List<ImageSource>) : RecoverInput
}

/** Un archivo creado en el destino; se puede borrar si el trabajo falla. */
class CreatedFile(val name: String, val uri: Uri?, val file: File?, val stream: OutputStream)

/** Carpeta de salida abierta (SAF o local) con operaciones mínimas. */
interface OutputDir {
    val label: String
    fun ensureNomedia()
    fun create(name: String, mime: String): CreatedFile
    fun delete(f: CreatedFile)
    fun displayName(f: CreatedFile): String = f.name
}

object Storage {
    fun open(context: Context, target: OutputTarget): OutputDir = when (target) {
        is OutputTarget.Tree -> TreeDir(context.contentResolver, target)
        is OutputTarget.Local -> LocalDir(target.dir)
    }

    fun defaultEncodeDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "codificado")

    fun defaultRecoverDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recuperado")

    fun describe(resolver: ContentResolver, uri: Uri): InputFile {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return InputFile(uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo", size)
    }

    fun treeLabel(resolver: ContentResolver, treeUri: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val name = runCatching {
            resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':')
    }

    fun openInput(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("no se pudo abrir $uri")

    /** Todos los PNG de la carpeta y sus subcarpetas (sin importar nombres). */
    fun listPngs(resolver: ContentResolver, treeUri: Uri, onFound: (Int) -> Unit = {}): List<ImageSource> {
        val out = ArrayList<ImageSource>()
        val pending = ArrayDeque<String>()
        pending += DocumentsContract.getTreeDocumentId(treeUri)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (mime == Document.MIME_TYPE_DIR) {
                        pending += id
                    } else if (mime == "image/png" || name.lowercase().endsWith(".png")) {
                        out += ImageSource(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name)
                        onFound(out.size)
                    }
                }
            }
        }
        return out
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

private const val BUFFER = 256 * 1024

private class TreeDir(private val resolver: ContentResolver, target: OutputTarget.Tree) : OutputDir {
    override val label = target.label
    private val treeUri = target.uri
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override fun ensureNomedia() {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val exists = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(0) == ".nomedia") { found = true; break }
            found
        } ?: false
        if (!exists) {
            DocumentsContract.createDocument(resolver, root, "application/octet-stream", ".nomedia")
                ?: throw IOException("no se pudo crear .nomedia en la carpeta de destino")
        }
    }

    override fun create(name: String, mime: String): CreatedFile {
        val uri = DocumentsContract.createDocument(resolver, root, mime, name)
            ?: throw IOException("no se pudo crear $name en la carpeta de destino")
        val s = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("no se pudo escribir $name")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
        return CreatedFile(name, uri, null, BufferedOutputStream(s, BUFFER))
    }

    override fun delete(f: CreatedFile) {
        runCatching { f.stream.close() }
        f.uri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }

    override fun displayName(f: CreatedFile): String = f.uri?.let { uri ->
        runCatching {
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    } ?: f.name
}

private class LocalDir(private val dir: File) : OutputDir {
    override val label: String = dir.absolutePath

    override fun ensureNomedia() {
        dir.mkdirs()
        File(dir, ".nomedia").createNewFile()
    }

    override fun create(name: String, mime: String): CreatedFile {
        dir.mkdirs()
        val f = Fcod1Files.uniqueFile(dir, name)
        return CreatedFile(f.name, null, f, BufferedOutputStream(f.outputStream(), BUFFER))
    }

    override fun delete(f: CreatedFile) {
        runCatching { f.stream.close() }
        f.file?.delete()
    }
}
