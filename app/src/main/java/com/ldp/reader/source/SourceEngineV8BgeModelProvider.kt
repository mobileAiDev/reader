package com.ldp.reader.source

import android.content.Context
import com.ldp.reader.App
import com.ldp.reader.sourceengine.content.v8.V8BgeSemanticModel
import com.ldp.reader.sourceengine.content.v8.V8SourceChapterValidator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object SourceEngineV8BgeModelProvider {
    private val lock = Any()
    private val releaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "v8-bge-idle-release").apply { isDaemon = true }
    }
    private var holder: Holder? = null
    private var activeUses = 0
    private var releaseFuture: ScheduledFuture<*>? = null

    fun <T> useValidator(block: (V8SourceChapterValidator) -> T): T {
        val selected = synchronized(lock) {
            releaseFuture?.cancel(false)
            releaseFuture = null
            val current = holder ?: createHolder().also { created ->
                holder = created
                AiBridgeTrace.event(
                    "source_v8_bge_model_created",
                    "global",
                    AiBridgeTrace.fields("idleReleaseMs" to IDLE_RELEASE_MS)
                )
            }
            activeUses += 1
            current
        }
        return try {
            block(selected.validator)
        } finally {
            synchronized(lock) {
                activeUses -= 1
                if (activeUses == 0) scheduleIdleRelease(selected)
            }
        }
    }

    private fun createHolder(): Holder {
        val model = createModel()
        return Holder(
            model = model,
            validator = V8SourceChapterValidator(model)
        )
    }

    private fun createModel(): V8BgeSemanticModel {
        val context = App.getContext()
        val modelDir = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
        ASSET_FILES.forEach { name ->
            copyAssetIfMissing(context, "$ASSET_DIR/$name", File(modelDir, name))
        }
        return V8BgeSemanticModel(
            modelFile = File(modelDir, MODEL_FILE),
            vocabFile = File(modelDir, VOCAB_FILE),
            maxTokens = 160,
            maxEmbeddingCacheEntries = 512,
            diskCacheDir = File(context.cacheDir, EMBEDDING_CACHE_DIR_NAME),
            cacheNamespace = CACHE_DIR_NAME
        )
    }

    private fun scheduleIdleRelease(expected: Holder) {
        releaseFuture?.cancel(false)
        releaseFuture = releaseExecutor.schedule(
            {
                val released = synchronized(lock) {
                    if (activeUses == 0 && holder === expected) {
                        holder = null
                        releaseFuture = null
                        expected
                    } else {
                        null
                    }
                }
                released?.let { idleHolder ->
                    val stats = idleHolder.model.cacheStats()
                    runCatching { idleHolder.model.close() }
                    AiBridgeTrace.event(
                        "source_v8_bge_model_released",
                        "global",
                        AiBridgeTrace.fields(
                            "reason" to "idle",
                            "memoryEntries" to stats.memoryEntries,
                            "memoryValueKiB" to stats.memoryValueBytes / 1024L
                        )
                    )
                }
            },
            IDLE_RELEASE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun copyAssetIfMissing(context: Context, assetPath: String, destination: File) {
        if (destination.isFile && destination.length() > 0L) return
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        context.assets.open(assetPath).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (destination.exists()) destination.delete()
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private const val ASSET_DIR = "bge-small-zh-v1.5-onnx"
    private const val CACHE_DIR_NAME = "source_engine_bge_small_zh_v1_5_v1"
    private const val EMBEDDING_CACHE_DIR_NAME = "source_engine_bge_embeddings_v1"
    private const val MODEL_FILE = "model_quantized.onnx"
    private const val MODEL_DATA_FILE = "model_quantized.onnx_data"
    private const val VOCAB_FILE = "vocab.txt"
    private const val CONFIG_FILE = "config.json"
    private const val IDLE_RELEASE_MS = 60_000L
    private val ASSET_FILES = listOf(MODEL_FILE, MODEL_DATA_FILE, VOCAB_FILE, CONFIG_FILE)

    private data class Holder(
        val model: V8BgeSemanticModel,
        val validator: V8SourceChapterValidator
    )
}
