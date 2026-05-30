package com.ldp.reader.source

import android.content.Context
import com.ldp.reader.App
import com.ldp.reader.sourceengine.content.v8.V8BgeSemanticModel
import java.io.File

internal object SourceEngineV8BgeModelProvider {
    @Volatile
    private var model: V8BgeSemanticModel? = null

    fun get(): V8BgeSemanticModel {
        model?.let { return it }
        return synchronized(this) {
            model ?: createModel().also { created -> model = created }
        }
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
    private val ASSET_FILES = listOf(MODEL_FILE, MODEL_DATA_FILE, VOCAB_FILE, CONFIG_FILE)
}
