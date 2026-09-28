package com.hermannpr.kyoto

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermannpr.kyoto.work.ImageSource
import com.hermannpr.kyoto.work.InputFile
import com.hermannpr.kyoto.work.OutputTarget
import com.hermannpr.kyoto.work.RecoverInput
import com.hermannpr.kyoto.work.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de la pantalla (selecciones y preferencias). El trabajo lo hace JobManager. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("kyoto", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    val jobs = (app as KyotoApp).jobs

    var tab by mutableIntStateOf(0)
    var encodeFiles by mutableStateOf(listOf<InputFile>())
        private set
    var encodeTarget by mutableStateOf(loadTarget(KEY_ENCODE_TREE) { Storage.defaultEncodeDir(app) })
        private set
    var maxPartMb by mutableIntStateOf(prefs.getInt(KEY_MAX_MB, DEFAULT_MAX_MB))
        private set
    var recoverInput by mutableStateOf<RecoverInput?>(null)
        private set
    var recoverTarget by mutableStateOf(loadTarget(KEY_RECOVER_TREE) { Storage.defaultRecoverDir(app) })
        private set

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.map { Storage.describe(resolver, it) } }
            encodeFiles = (encodeFiles + added).distinctBy { it.uri }
            tab = 0
        }
    }

    fun removeFile(f: InputFile) { encodeFiles = encodeFiles - f }
    fun clearFiles() { encodeFiles = emptyList() }

    /** Archivos compartidos desde otra app (ACTION_SEND / ACTION_SEND_MULTIPLE). */
    fun handleShare(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE ->
                androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
            else -> return
        }
        if (uris.isEmpty()) intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(uris::add) }
        addFiles(uris)
    }

    fun setMaxPart(mb: Int) {
        maxPartMb = mb
        prefs.edit().putInt(KEY_MAX_MB, mb).apply()
    }

    fun setEncodeTree(uri: Uri?) {
        encodeTarget = saveTarget(KEY_ENCODE_TREE, uri) { Storage.defaultEncodeDir(getApplication()) }
    }

    fun setRecoverTree(uri: Uri?) {
        recoverTarget = saveTarget(KEY_RECOVER_TREE, uri) { Storage.defaultRecoverDir(getApplication()) }
    }

    fun setRecoverFolder(uri: Uri) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        recoverInput = RecoverInput.Folder(uri, Storage.treeLabel(resolver, uri))
    }

    fun setRecoverImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imgs = withContext(Dispatchers.IO) { uris.map { ImageSource(it, Storage.describe(resolver, it).name) } }
            recoverInput = RecoverInput.Images(imgs)
        }
    }

    fun startEncode() {
        if (encodeFiles.isEmpty()) return
        jobs.encode(encodeFiles, encodeTarget, maxPartMb.toLong() * 1024 * 1024)
    }

    fun startRecover() {
        val input = recoverInput ?: return
        jobs.recover(input, recoverTarget)
    }

    private fun loadTarget(key: String, fallback: () -> java.io.File): OutputTarget {
        val saved = prefs.getString(key, null)?.let(Uri::parse)
        if (saved != null && resolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }) {
            return OutputTarget.Tree(saved, runCatching { Storage.treeLabel(resolver, saved) }.getOrDefault("Carpeta elegida"))
        }
        return OutputTarget.Local(fallback())
    }

    private fun saveTarget(key: String, uri: Uri?, fallback: () -> java.io.File): OutputTarget {
        if (uri == null) {
            prefs.edit().remove(key).apply()
            return OutputTarget.Local(fallback())
        }
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.edit().putString(key, uri.toString()).apply()
        return OutputTarget.Tree(uri, Storage.treeLabel(resolver, uri))
    }

    companion object {
        const val DEFAULT_MAX_MB = 20 // igual que fotocodec.py --max-mb
        val PART_SIZES_MB = listOf(5, 10, 20, 50, 100)
        private const val KEY_ENCODE_TREE = "encode_tree"
        private const val KEY_RECOVER_TREE = "recover_tree"
        private const val KEY_MAX_MB = "max_mb"
    }
}
