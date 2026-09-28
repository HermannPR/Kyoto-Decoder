package com.hermannpr.kyoto.work

import android.content.Context
import android.os.SystemClock
import com.hermannpr.kyoto.codec.CancelCheck
import com.hermannpr.kyoto.codec.Fcod1
import com.hermannpr.kyoto.codec.Fcod1Decoder
import com.hermannpr.kyoto.codec.Fcod1Encoder
import com.hermannpr.kyoto.codec.PartOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.FilterOutputStream
import java.io.OutputStream

enum class JobKind { ENCODE, RECOVER }

data class Progress(
    val title: String,
    val detail: String = "",
    val done: Long = 0,
    /** 0 = indeterminado. */
    val total: Long = 0,
    /** true si done/total son bytes (si no, son imágenes). */
    val isBytes: Boolean = true,
) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

data class EncodeSummary(
    val files: Int,
    val parts: Int,
    val bytesIn: Long,
    val bytesOut: Long,
    val folder: String,
    val errors: List<String>,
    val cancelled: Boolean,
)

data class RecoveredFile(val name: String, val size: Long)

data class RecoverSummary(
    val recovered: List<RecoveredFile>,
    /** Archivos que no se pudieron recuperar (faltan partes, hash, parte dañada). */
    val failures: List<String>,
    /** Imágenes dañadas detectadas al leer cabeceras. */
    val damaged: List<String>,
    /** Imágenes que no son de Kyoto (se ignoran). */
    val ignored: List<String>,
    val imagesScanned: Int,
    val folder: String,
    val cancelled: Boolean,
)

sealed interface JobState {
    data object Idle : JobState
    data class Running(val kind: JobKind, val progress: Progress) : JobState
    data class EncodeDone(val summary: EncodeSummary) : JobState
    data class RecoverDone(val summary: RecoverSummary) : JobState
    data class Failed(val kind: JobKind, val message: String) : JobState
}

/**
 * Ejecuta un trabajo a la vez fuera del hilo principal, con alcance de aplicación
 * (sobrevive a rotaciones y a salir de la pantalla; [WorkService] mantiene vivo el proceso).
 */
class JobManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!isRunning) _state.value = JobState.Idle
    }

    fun encode(inputs: List<InputFile>, target: OutputTarget, maxPartBytes: Long) =
        start(JobKind.ENCODE) { report -> JobState.EncodeDone(runEncode(inputs, target, maxPartBytes, report)) }

    fun recover(input: RecoverInput, target: OutputTarget) =
        start(JobKind.RECOVER) { report -> JobState.RecoverDone(runRecover(input, target, report)) }

    private fun start(kind: JobKind, block: suspend (Reporter) -> JobState) {
        if (isRunning) return
        val reporter = Reporter { p -> if (job?.isActive == true) _state.value = JobState.Running(kind, p) }
        _state.value = JobState.Running(kind, Progress("Preparando…"))
        WorkService.start(context)
        job = scope.launch {
            val result = try {
                block(reporter)
            } catch (e: CancellationException) {
                JobState.Failed(kind, "Cancelado")
            } catch (e: Throwable) {
                JobState.Failed(kind, e.message ?: e.javaClass.simpleName)
            }
            _state.value = result
        }
    }

    /** Limita las actualizaciones de progreso a ~10 por segundo. */
    class Reporter(private val emit: (Progress) -> Unit) {
        private var last = 0L
        fun update(force: Boolean = false, p: () -> Progress) {
            val now = SystemClock.uptimeMillis()
            if (force || now - last >= 100) {
                last = now
                emit(p())
            }
        }
    }

    private suspend fun cancelCheck(): CancelCheck {
        val j = currentCoroutineContext().job
        return CancelCheck { j.ensureActive() }
    }

    private suspend fun runEncode(inputs: List<InputFile>, target: OutputTarget, maxPartBytes: Long, report: Reporter): EncodeSummary {
        val resolver = context.contentResolver
        val cancel = cancelCheck()
        val out = Storage.open(context, target)
        out.ensureNomedia() // primero: así la galería ignora las imágenes desde la primera
        var total = inputs.sumOf { it.size ?: 0L } * 2
        var done = 0L
        var files = 0
        var parts = 0
        var bytesIn = 0L
        var bytesOut = 0L
        val errors = ArrayList<String>()
        var cancelled = false
        val encoder = Fcod1Encoder(maxPartBytes)

        for ((i, f) in inputs.withIndex()) {
            val created = ArrayList<CreatedFile>()
            val prefix = if (inputs.size > 1) "Archivo ${i + 1} de ${inputs.size}: " else ""
            try {
                val digest = Storage.openInput(resolver, f.uri).use { input ->
                    encoder.digest(input, cancel) { n ->
                        done += n
                        report.update { Progress("Calculando SHA-256", prefix + f.name, done, total) }
                    }
                }
                val expected = f.size ?: 0L
                if (digest.size != expected) total += 2 * (digest.size - expected)
                var partLabel = ""
                val result = Storage.openInput(resolver, f.uri).use { input ->
                    encoder.encode(
                        f.name, digest, input,
                        output = PartOutput { name, _, _ ->
                            val c = out.create(name, "image/png")
                            created += c
                            CountingStream(c.stream) { bytesOut += it }
                        },
                        cancel = cancel,
                        onBytes = { n ->
                            done += n
                            report.update { Progress("Creando imágenes", prefix + f.name + partLabel, done, total) }
                        },
                        onPart = { k, n -> partLabel = if (n > 1) " · parte ${k + 1} de $n" else "" },
                    )
                }
                files++
                parts += result.partNames.size
                bytesIn += digest.size
            } catch (e: CancellationException) {
                created.forEach(out::delete)
                cancelled = true
                break
            } catch (e: Exception) {
                created.forEach(out::delete)
                errors += "${f.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return EncodeSummary(files, parts, bytesIn, bytesOut, out.label, errors, cancelled)
    }

    private suspend fun runRecover(input: RecoverInput, target: OutputTarget, report: Reporter): RecoverSummary {
        val resolver = context.contentResolver
        val cancel = cancelCheck()
        val images = when (input) {
            is RecoverInput.Images -> input.images
            is RecoverInput.Folder -> Storage.listPngs(resolver, input.treeUri) { n ->
                cancel.check()
                report.update { Progress("Buscando imágenes", "$n encontradas") }
            }
        }
        var scanned = 0
        val open = { s: ImageSource -> Storage.openInput(resolver, s.uri) }
        val recovered = ArrayList<RecoveredFile>()
        val failures = ArrayList<String>()
        var cancelled = false
        val out = Storage.open(context, target)

        val scan = try {
            Fcod1Decoder.scan(images, open, cancel) {
                scanned++
                report.update { Progress("Leyendo cabeceras", "$scanned de ${images.size} imágenes", scanned.toLong(), images.size.toLong(), isBytes = false) }
            }
        } catch (e: CancellationException) {
            return RecoverSummary(emptyList(), emptyList(), emptyList(), emptyList(), scanned, out.label, true)
        }

        val complete = scan.groups.filter { it.isComplete }
        val total = complete.sumOf { it.totalSize }
        var done = 0L
        for (g in scan.groups) {
            if (!g.isComplete) {
                failures += g.missingDescription()
                continue
            }
            val name = Fcod1.safeOutputName(g.name)
            var created: CreatedFile? = null
            try {
                val c = out.create(name, Storage.mimeFor(name))
                created = c
                c.stream.use { s ->
                    Fcod1Decoder.recover(g, open, s, cancel) { n ->
                        done += n
                        report.update { Progress("Recuperando", name, done, total) }
                    }
                }
                recovered += RecoveredFile(out.displayName(c), g.totalSize)
            } catch (e: CancellationException) {
                created?.let(out::delete)
                cancelled = true
                break
            } catch (e: Exception) {
                created?.let(out::delete)
                failures += e.message ?: "${g.name}: ${e.javaClass.simpleName}"
            }
        }
        return RecoverSummary(
            recovered, failures,
            damaged = scan.damaged.map { "${it.source.name}: ${it.reason}" },
            ignored = scan.ignored.map { it.source.name },
            imagesScanned = images.size,
            folder = out.label,
            cancelled = cancelled,
        )
    }
}

private class CountingStream(out: OutputStream, private val onBytes: (Int) -> Unit) : FilterOutputStream(out) {
    override fun write(b: Int) { out.write(b); onBytes(1) }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); onBytes(len) }
}
