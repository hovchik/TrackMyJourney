package com.trackjourney.data.ai.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelManager @Inject constructor(
    private val dao: LocalAiModelDao,
    private val aiPreferences: AiPreferences
) {
    // Scope tied to the singleton's lifetime — used only to maintain the cache.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe cache so isConfigured() / isAvailable() never need runBlocking.
    @Volatile private var cachedActiveModel: LocalAiModel? = null

    init {
        managerScope.launch {
            // Pre-load from DB, then stay in sync with any future changes.
            cachedActiveModel = dao.getActive()?.toDomain()
            observeActiveModel().collect { model -> cachedActiveModel = model }
        }
    }

    fun observeInstalledModels(): Flow<List<LocalAiModel>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    fun observeActiveModel(): Flow<LocalAiModel?> =
        dao.observeActive().map { it?.toDomain() }

    suspend fun getInstalledModels(): List<LocalAiModel> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toDomain() }
    }

    suspend fun getActiveModel(): LocalAiModel? = withContext(Dispatchers.IO) {
        dao.getActive()?.toDomain()
    }

    fun getActiveModelSync(): LocalAiModel? = cachedActiveModel

    suspend fun getModel(modelId: String): LocalAiModel? = withContext(Dispatchers.IO) {
        dao.getById(modelId)?.toDomain()
    }

    suspend fun saveModel(model: LocalAiModel) = withContext(Dispatchers.IO) {
        dao.insert(model.toEntity())
    }

    suspend fun setActiveModel(modelId: String) = withContext(Dispatchers.IO) {
        dao.deactivateAll()
        dao.activate(modelId)
        aiPreferences.setActiveModelId(modelId)
    }

    suspend fun deactivateAll() = withContext(Dispatchers.IO) {
        dao.deactivateAll()
        aiPreferences.setActiveModelId(null)
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = dao.getById(modelId)
        model?.localPath?.let { path ->
            val modelDir = File(path).parentFile
            modelDir?.deleteRecursively()
        }
        dao.delete(modelId)
        if (aiPreferences.getActiveModelId() == modelId) {
            aiPreferences.setActiveModelId(null)
        }
    }

    suspend fun deleteAllModels() = withContext(Dispatchers.IO) {
        val allModels = dao.getAll()
        allModels.forEach { entity ->
            entity.localPath?.let { path ->
                File(path).parentFile?.deleteRecursively()
            }
        }
        dao.deleteAll()
        aiPreferences.setActiveModelId(null)
    }

    suspend fun updateInstallState(
        modelId: String,
        state: ModelInstallState,
        localPath: String? = null
    ) = withContext(Dispatchers.IO) {
        val entity = dao.getById(modelId) ?: return@withContext
        dao.update(
            entity.copy(
                installState = state.name,
                localPath = localPath ?: entity.localPath,
                installedAt = if (state == ModelInstallState.INSTALLED) System.currentTimeMillis() else entity.installedAt
            )
        )
    }

    suspend fun getTotalStorageUsedMb(): Long = withContext(Dispatchers.IO) {
        dao.getByState(ModelInstallState.INSTALLED.name).sumOf { it.sizeMb }
    }

    // ── Domain ↔ Entity mappers ────────────────────────────

    private fun LocalAiModelEntity.toDomain(): LocalAiModel = LocalAiModel(
        modelId = modelId,
        displayName = displayName,
        runtimeType = runtimeType,
        fileFormat = fileFormat,
        quantization = quantization,
        requiredRamMb = requiredRamMb,
        recommendedRamMb = recommendedRamMb,
        sizeMb = sizeMb,
        downloadUrl = downloadUrl,
        localPath = localPath,
        installState = try {
            ModelInstallState.valueOf(installState)
        } catch (e: IllegalArgumentException) {
            ModelInstallState.NOT_INSTALLED
        },
        checksum = checksum,
        version = version,
        supportsStructuredJson = supportsStructuredJson,
        supportsStreaming = supportsStreaming,
        supportsTextGeneration = supportsTextGeneration,
        isActive = isActive,
        installedAt = installedAt
    )

    private fun LocalAiModel.toEntity(): LocalAiModelEntity = LocalAiModelEntity(
        modelId = modelId,
        displayName = displayName,
        runtimeType = runtimeType,
        fileFormat = fileFormat,
        quantization = quantization,
        requiredRamMb = requiredRamMb,
        recommendedRamMb = recommendedRamMb,
        sizeMb = sizeMb,
        downloadUrl = downloadUrl,
        localPath = localPath,
        installState = installState.name,
        checksum = checksum,
        version = version,
        supportsStructuredJson = supportsStructuredJson,
        supportsStreaming = supportsStreaming,
        supportsTextGeneration = supportsTextGeneration,
        isActive = isActive,
        installedAt = installedAt
    )
}
