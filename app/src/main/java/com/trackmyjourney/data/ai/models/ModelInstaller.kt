package com.trackmyjourney.data.ai.models

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelInstaller @Inject constructor(
    private val context: Context,
    private val localModelManager: LocalModelManager,
    private val compatibilityValidator: ModelCompatibilityValidator
) {
    companion object {
        /** File extensions recognized as AI model files during device scan. */
        val MODEL_FILE_EXTENSIONS = setOf("task", "litertlm", "gguf", "bin", "tflite", "onnx")
    }

    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    private val modelsDir: File
        get() = File(context.filesDir, "local_models")

    private fun getModelDir(modelId: String): File =
        File(modelsDir, modelId).also { it.mkdirs() }

    private fun getModelFile(modelId: String, format: String): File =
        File(getModelDir(modelId), "model.$format")

    suspend fun downloadModel(model: LocalAiModel, url: String): Result<LocalAiModel> =
        withContext(Dispatchers.IO) {
            try {
                if (url.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Download URL is blank"))
                }

                // Validate compatibility before download
                val report = compatibilityValidator.validate(model)
                if (!report.isCompatible) {
                    return@withContext Result.failure(
                        IllegalStateException("Model incompatible: ${report.issues.joinToString()}")
                    )
                }

                // Save initial state
                val downloadingModel = model.copy(installState = ModelInstallState.DOWNLOADING)
                localModelManager.saveModel(downloadingModel)
                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.DOWNLOADING,
                    progressPercent = 0
                )

                val destFile = getModelFile(model.modelId, model.fileFormat)
                destFile.parentFile?.mkdirs()

                // Download with progress
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "TrackMyJourney/1.0")
                connection.instanceFollowRedirects = true

                // Handle redirects manually for cross-protocol
                var currentConnection = connection
                var redirectCount = 0
                while (redirectCount < 5) {
                    currentConnection.connect()
                    val responseCode = currentConnection.responseCode
                    if (responseCode in 301..308) {
                        val redirectUrl = currentConnection.getHeaderField("Location")
                        currentConnection.disconnect()
                        currentConnection = URL(redirectUrl).openConnection() as HttpURLConnection
                        currentConnection.connectTimeout = 30_000
                        currentConnection.readTimeout = 300_000
                        currentConnection.setRequestProperty("User-Agent", "TrackMyJourney/1.0")
                        currentConnection.instanceFollowRedirects = true
                        redirectCount++
                    } else {
                        break
                    }
                }

                val totalBytes = currentConnection.contentLengthLong
                var downloadedBytes = 0L

                currentConnection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else 0
                            _installProgress.value = InstallProgress(
                                modelId = model.modelId,
                                state = ModelInstallState.DOWNLOADING,
                                progressPercent = progress
                            )
                        }
                    }
                }
                currentConnection.disconnect()

                // Installing phase — compute checksum
                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.INSTALLING,
                    progressPercent = 100
                )

                val checksum = computeSha256(destFile)

                val installedModel = model.copy(
                    installState = ModelInstallState.INSTALLED,
                    localPath = destFile.absolutePath,
                    checksum = checksum,
                    installedAt = System.currentTimeMillis()
                )
                localModelManager.saveModel(installedModel)

                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.INSTALLED,
                    progressPercent = 100
                )

                Result.success(installedModel)
            } catch (e: Exception) {
                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.FAILED,
                    progressPercent = 0,
                    errorMessage = e.message
                )
                localModelManager.updateInstallState(model.modelId, ModelInstallState.FAILED)
                Result.failure(e)
            }
        }

    suspend fun importFromUri(model: LocalAiModel, uri: Uri): Result<LocalAiModel> =
        withContext(Dispatchers.IO) {
            try {
                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.INSTALLING,
                    progressPercent = 0
                )

                val destFile = getModelFile(model.modelId, model.fileFormat)
                destFile.parentFile?.mkdirs()

                val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IllegalStateException("Cannot open URI"))

                var copiedBytes = 0L
                inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            copiedBytes += bytesRead
                            val progress = if (model.sizeMb > 0) {
                                ((copiedBytes / (1024 * 1024)) * 100 / model.sizeMb).toInt().coerceIn(0, 100)
                            } else 0
                            _installProgress.value = InstallProgress(
                                modelId = model.modelId,
                                state = ModelInstallState.INSTALLING,
                                progressPercent = progress
                            )
                        }
                    }
                }

                val checksum = computeSha256(destFile)

                val installedModel = model.copy(
                    installState = ModelInstallState.INSTALLED,
                    localPath = destFile.absolutePath,
                    checksum = checksum,
                    sizeMb = destFile.length() / (1024 * 1024),
                    installedAt = System.currentTimeMillis()
                )
                localModelManager.saveModel(installedModel)

                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.INSTALLED,
                    progressPercent = 100
                )

                Result.success(installedModel)
            } catch (e: Exception) {
                _installProgress.value = InstallProgress(
                    modelId = model.modelId,
                    state = ModelInstallState.FAILED,
                    errorMessage = e.message
                )
                Result.failure(e)
            }
        }

    suspend fun registerFromPath(model: LocalAiModel, path: String): Result<LocalAiModel> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    return@withContext Result.failure(IllegalStateException("File not found: $path"))
                }

                val report = compatibilityValidator.validate(model)
                if (!report.isCompatible) {
                    return@withContext Result.failure(
                        IllegalStateException("Model incompatible: ${report.issues.joinToString()}")
                    )
                }

                val checksum = computeSha256(file)

                val registeredModel = model.copy(
                    installState = ModelInstallState.INSTALLED,
                    localPath = path,
                    checksum = checksum,
                    sizeMb = file.length() / (1024 * 1024),
                    installedAt = System.currentTimeMillis()
                )
                localModelManager.saveModel(registeredModel)

                Result.success(registeredModel)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun scanForModels(): Int = withContext(Dispatchers.IO) {
        val foundFiles = mutableSetOf<String>() // track absolute paths to avoid duplicates
        var foundCount = 0

        // 1. Scan internal app local_models directory
        foundCount += scanInternalModels()

        // 2. Scan all accessible on-device storage locations
        val scanRoots = collectScanRoots()
        for (root in scanRoots) {
            foundCount += scanDirectoryRecursive(root, foundFiles, maxDepth = 5)
        }

        foundCount
    }

    /**
     * Collects storage roots accessible without special permissions:
     * - App-specific external dirs on each volume (internal + SD cards)
     */
    private fun collectScanRoots(): List<File> {
        val roots = mutableSetOf<File>()

        // App-specific external dirs — on each volume the device has (internal + SD cards).
        // No special permission required; the system grants access automatically.
        context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { appDir ->
            roots.add(appDir)
        }

        return roots.filter { it.exists() && it.canRead() }.toList()
    }

    private suspend fun scanInternalModels(): Int {
        var count = 0
        val dir = modelsDir
        if (!dir.exists()) return 0

        dir.listFiles()?.forEach { subDir ->
            if (!subDir.isDirectory) return@forEach
            val modelId = subDir.name
            val existing = localModelManager.getModel(modelId)
            if (existing != null) return@forEach

            val modelFile = subDir.listFiles()?.firstOrNull { file ->
                file.name.startsWith("model.") ||
                        file.extension in MODEL_FILE_EXTENSIONS
            } ?: return@forEach

            val catalogModel = ModelCatalog.findById(modelId)
            if (catalogModel != null) {
                val registeredModel = catalogModel.copy(
                    installState = ModelInstallState.INSTALLED,
                    localPath = modelFile.absolutePath,
                    sizeMb = modelFile.length() / (1024 * 1024),
                    installedAt = modelFile.lastModified()
                )
                localModelManager.saveModel(registeredModel)
                count++
            }
        }
        return count
    }

    /**
     * Recursively walks a directory tree up to [maxDepth] looking for model files.
     * Skips Android system directories, hidden dirs, and already-seen paths.
     */
    private suspend fun scanDirectoryRecursive(
        dir: File,
        seenPaths: MutableSet<String>,
        maxDepth: Int,
        currentDepth: Int = 0
    ): Int {
        if (currentDepth > maxDepth) return 0
        if (!dir.exists() || !dir.canRead()) return 0

        // Skip directories that are unlikely to contain user models and are expensive to walk
        val skipDirs = setOf("Android", ".thumbnails", ".trash", "cache", "Cache", "code_cache")
        if (dir.name in skipDirs && currentDepth > 0) return 0

        var count = 0
        val entries = dir.listFiles() ?: return 0

        for (entry in entries) {
            if (entry.isDirectory) {
                count += scanDirectoryRecursive(entry, seenPaths, maxDepth, currentDepth + 1)
            } else if (entry.isFile && isModelFile(entry) && entry.absolutePath !in seenPaths) {
                seenPaths.add(entry.absolutePath)
                count += registerScannedFile(entry)
            }
        }
        return count
    }

    private fun isModelFile(file: File): Boolean {
        if (file.length() < 10 * 1024 * 1024) return false // skip files < 10 MB
        return file.extension.lowercase() in MODEL_FILE_EXTENSIONS
    }

    private suspend fun registerScannedFile(file: File): Int {
        val stableId = generateStableId(file.nameWithoutExtension)
        val existing = localModelManager.getModel(stableId)
        if (existing != null) return 0

        val matchedCatalogModel = matchAgainstCatalog(file.nameWithoutExtension)

        val ext = file.extension.lowercase()
        val format = when (ext) {
            "tflite" -> "tflite"
            "task", "litertlm" -> "task"
            else -> "bin"
        }
        val runtimeType = when (ext) {
            "tflite" -> "litert"
            else -> "mediapipe_llm"
        }

        val model = matchedCatalogModel?.copy(
            modelId = stableId,
            installState = ModelInstallState.INSTALLED,
            localPath = file.absolutePath,
            sizeMb = file.length() / (1024 * 1024),
            installedAt = file.lastModified()
        ) ?: LocalAiModel(
            modelId = stableId,
            displayName = formatDisplayName(file.nameWithoutExtension),
            runtimeType = runtimeType,
            fileFormat = format,
            quantization = inferQuantization(file.nameWithoutExtension),
            requiredRamMb = 2048,
            recommendedRamMb = 4096,
            sizeMb = file.length() / (1024 * 1024),
            downloadUrl = null,
            localPath = file.absolutePath,
            installState = ModelInstallState.INSTALLED,
            checksum = null,
            version = "1.0",
            supportsStructuredJson = false,
            supportsStreaming = true,
            supportsTextGeneration = true,
            installedAt = file.lastModified()
        )

        localModelManager.saveModel(model)
        return 1
    }

    private fun generateStableId(filename: String): String =
        "scanned-${filename.lowercase().replace(Regex("[^a-z0-9]"), "-").trim('-')}"

    private fun matchAgainstCatalog(filename: String): LocalAiModel? {
        val filenameLower = filename.lowercase()
        for (catalogModel in ModelCatalog.availableModels) {
            val keywords = catalogModel.modelId.split("-", "_", ".")
            val matchCount = keywords.count { keyword ->
                keyword.length >= 2 && filenameLower.contains(keyword.lowercase())
            }
            if (matchCount >= 2) return catalogModel
        }
        return null
    }

    private fun inferQuantization(filename: String): String? {
        val lower = filename.lowercase()
        val patterns = listOf("q4_k_m", "q4_k_s", "q4_0", "q4_1", "q5_k_m", "q5_0", "q8_0", "q6_k", "q3_k_m", "q2_k")
        return patterns.firstOrNull { lower.contains(it) }?.uppercase()
    }

    private fun formatDisplayName(filename: String): String =
        filename.replace(Regex("[._-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }

    fun clearProgress() {
        _installProgress.value = null
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
