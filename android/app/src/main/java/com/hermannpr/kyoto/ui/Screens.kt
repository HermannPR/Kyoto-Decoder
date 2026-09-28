package com.hermannpr.kyoto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermannpr.kyoto.MainViewModel
import com.hermannpr.kyoto.work.EncodeSummary
import com.hermannpr.kyoto.work.JobKind
import com.hermannpr.kyoto.work.JobState
import com.hermannpr.kyoto.work.OutputTarget
import com.hermannpr.kyoto.work.RecoverInput
import com.hermannpr.kyoto.work.RecoverSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KyotoRoot(vm: MainViewModel) {
    val state by vm.jobs.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Kyoto", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Archivos ⇄ imágenes PNG sin pérdida",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == 0, onClick = { vm.tab = 0 },
                    icon = { Icon(KIcons.Image, null) }, label = { Text("Codificar") },
                )
                NavigationBarItem(
                    selected = vm.tab == 1, onClick = { vm.tab = 1 },
                    icon = { Icon(KIcons.Restore, null) }, label = { Text("Recuperar") },
                )
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val running = state as? JobState.Running
            if (running != null) {
                ProgressCard(running, onCancel = vm.jobs::cancel)
            }
            if (vm.tab == 0) EncodeScreen(vm, state) else RecoverScreen(vm, state)
        }
    }
}

/** Pide permiso de notificaciones (Android 13+) una vez, y luego arranca el trabajo. */
@Composable
private fun rememberStarter(start: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncodeScreen(vm: MainViewModel, state: JobState) {
    val busy = state is JobState.Running
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setEncodeTree) }
    val start = rememberStarter { vm.startEncode() }

    Section("Archivos", KIcons.File) {
        if (vm.encodeFiles.isEmpty()) {
            Hint("Elige uno o varios archivos, o compártelos desde otra app con «Codificar con Kyoto».")
        } else {
            val total = vm.encodeFiles.sumOf { it.size ?: 0L }
            Text(
                "${plural(vm.encodeFiles.size, "archivo", "archivos")} · ${formatBytes(total)}",
                style = MaterialTheme.typography.labelLarge,
            )
            vm.encodeFiles.take(50).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.size?.let(::formatBytes) ?: "tamaño desconocido",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.removeFile(f) }, enabled = !busy) { Icon(Icons.Filled.Close, "Quitar") }
                }
            }
            if (vm.encodeFiles.size > 50) Hint("y ${vm.encodeFiles.size - 50} más…")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Elegir archivos") }
            if (vm.encodeFiles.isNotEmpty()) TextButton(onClick = vm::clearFiles, enabled = !busy) { Text("Vaciar") }
        }
    }

    Section("Guardar imágenes en", KIcons.Folder) {
        TargetText(vm.encodeTarget)
        Hint("Se crea un archivo .nomedia para que la galería y Google Fotos no muestren estas imágenes.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !busy) { Text("Elegir carpeta") }
            if (vm.encodeTarget is OutputTarget.Tree) {
                TextButton(onClick = { vm.setEncodeTree(null) }, enabled = !busy) { Text("Usar la de la app") }
            }
        }
    }

    Section("Tamaño máximo por imagen", null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainViewModel.PART_SIZES_MB.forEach { mb ->
                FilterChip(
                    selected = vm.maxPartMb == mb, onClick = { vm.setMaxPart(mb) }, enabled = !busy,
                    label = { Text(if (mb == MainViewModel.DEFAULT_MAX_MB) "$mb MB (predet.)" else "$mb MB") },
                )
            }
        }
        Hint("Los archivos más grandes se parten en varias imágenes. Para recuperarlos hacen falta todas.")
    }

    Button(
        onClick = start, enabled = !busy && vm.encodeFiles.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(if (vm.encodeFiles.size > 1) "Codificar ${vm.encodeFiles.size} archivos" else "Codificar")
    }

    when (state) {
        is JobState.EncodeDone -> EncodeResult(state.summary, onDismiss = vm.jobs::dismiss)
        is JobState.Failed -> if (state.kind == JobKind.ENCODE) FailedCard(state.message, vm.jobs::dismiss)
        else -> {}
    }
}

@Composable
private fun RecoverScreen(vm: MainViewModel, state: JobState) {
    val busy = state is JobState.Running
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setRecoverFolder) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.setRecoverImages(it) }
    val pickOut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setRecoverTree) }
    val start = rememberStarter { vm.startRecover() }

    Section("Imágenes", KIcons.Image) {
        when (val input = vm.recoverInput) {
            null -> Hint("Elige la carpeta con las imágenes (se revisan también sus subcarpetas) o selecciónalas. El nombre y el orden de las imágenes no importan.")
            is RecoverInput.Folder -> Text("Carpeta: ${input.label} (con subcarpetas)", style = MaterialTheme.typography.labelLarge)
            is RecoverInput.Images -> Text(plural(input.images.size, "imagen seleccionada", "imágenes seleccionadas"), style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !busy) { Text("Elegir carpeta") }
            OutlinedButton(onClick = { pickImages.launch(arrayOf("image/png", "image/*", "application/octet-stream")) }, enabled = !busy) {
                Text("Elegir imágenes")
            }
        }
    }

    Section("Guardar archivos en", KIcons.Folder) {
        TargetText(vm.recoverTarget)
        if (vm.recoverTarget is OutputTarget.Local) {
            Hint("Elige una carpeta para poder abrir los archivos recuperados desde otras apps.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickOut.launch(null) }, enabled = !busy) { Text("Elegir carpeta") }
            if (vm.recoverTarget is OutputTarget.Tree) {
                TextButton(onClick = { vm.setRecoverTree(null) }, enabled = !busy) { Text("Usar la de la app") }
            }
        }
    }

    Button(
        onClick = start, enabled = !busy && vm.recoverInput != null,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Recuperar") }

    when (state) {
        is JobState.RecoverDone -> RecoverResult(state.summary, onDismiss = vm.jobs::dismiss)
        is JobState.Failed -> if (state.kind == JobKind.RECOVER) FailedCard(state.message, vm.jobs::dismiss)
        else -> {}
    }
}

// ---------- piezas ----------

@Composable
private fun Section(title: String, icon: ImageVector?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun TargetText(t: OutputTarget) = when (t) {
    is OutputTarget.Tree -> Text("Carpeta: ${t.label}", style = MaterialTheme.typography.labelLarge)
    is OutputTarget.Local -> Column {
        Text("Carpeta de la app (predeterminada)", style = MaterialTheme.typography.labelLarge)
        Hint(t.label)
    }
}

@Composable
private fun ProgressCard(r: JobState.Running, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (r.kind == JobKind.ENCODE) "Codificando…" else "Recuperando…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(r.progress.title, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (r.progress.detail.isNotBlank()) {
                Text(r.progress.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val f = r.progress.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (f != null) {
                val pct = "${(100 * f).toInt()} %"
                Text(
                    if (r.progress.isBytes) "${formatBytes(r.progress.done)} de ${formatBytes(r.progress.total)} · $pct" else pct,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
        }
    }
}

@Composable
private fun ResultCard(ok: Boolean, title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cerrar") }
            }
            content()
        }
    }
}

@Composable
private fun Problem(text: String) =
    Text("• $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

@Composable
private fun EncodeResult(s: EncodeSummary, onDismiss: () -> Unit) {
    val ok = s.errors.isEmpty() && !s.cancelled
    val title = when {
        s.cancelled -> "Cancelado"
        ok -> "Listo"
        else -> "Terminado con errores"
    }
    ResultCard(ok, title, onDismiss) {
        Text("${plural(s.files, "archivo", "archivos")} → ${plural(s.parts, "imagen", "imágenes")}")
        Text("${formatBytes(s.bytesIn)} de datos · ${formatBytes(s.bytesOut)} en PNG", style = MaterialTheme.typography.bodySmall)
        Hint("En: ${s.folder}")
        if (s.cancelled) Hint("Se borraron las imágenes incompletas del archivo en curso.")
        s.errors.forEach { Problem(it) }
    }
}

@Composable
private fun RecoverResult(s: RecoverSummary, onDismiss: () -> Unit) {
    val ok = s.failures.isEmpty() && s.damaged.isEmpty() && !s.cancelled && s.recovered.isNotEmpty()
    val title = when {
        s.cancelled -> "Cancelado"
        s.recovered.isEmpty() && s.failures.isEmpty() && s.damaged.isEmpty() -> "No se encontraron imágenes de Kyoto"
        ok -> "Recuperado y verificado"
        s.recovered.isEmpty() -> "No se pudo recuperar"
        else -> "Recuperado en parte"
    }
    ResultCard(ok, title, onDismiss) {
        Hint("${plural(s.imagesScanned, "imagen revisada", "imágenes revisadas")} · guardado en ${s.folder}")
        s.recovered.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${f.name} · ${formatBytes(f.size)}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s.recovered.isNotEmpty()) Hint("SHA-256 verificado: idénticos a los originales.")
        s.failures.forEach { Problem(it) }
        if (s.damaged.isNotEmpty()) {
            Text("Imágenes dañadas o alteradas:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            s.damaged.take(30).forEach { Problem(it) }
            if (s.damaged.size > 30) Problem("y ${s.damaged.size - 30} más")
        }
        if (s.ignored.isNotEmpty()) {
            Hint("${plural(s.ignored.size, "imagen ignorada", "imágenes ignoradas")} (no son de Kyoto): " +
                s.ignored.take(5).joinToString(", ") + if (s.ignored.size > 5) "…" else "")
        }
    }
}

@Composable
private fun FailedCard(message: String, onDismiss: () -> Unit) =
    ResultCard(false, if (message == "Cancelado") "Cancelado" else "Error", onDismiss) {
        if (message != "Cancelado") Problem(message)
    }
