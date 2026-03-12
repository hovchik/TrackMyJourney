package com.trackjourney.data.ai.models

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_ai_models")
data class LocalAiModelEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "runtime_type")
    val runtimeType: String,

    @ColumnInfo(name = "file_format")
    val fileFormat: String,

    @ColumnInfo(name = "quantization")
    val quantization: String?,

    @ColumnInfo(name = "required_ram_mb")
    val requiredRamMb: Int,

    @ColumnInfo(name = "recommended_ram_mb")
    val recommendedRamMb: Int,

    @ColumnInfo(name = "size_mb")
    val sizeMb: Long,

    @ColumnInfo(name = "download_url")
    val downloadUrl: String?,

    @ColumnInfo(name = "local_path")
    val localPath: String?,

    @ColumnInfo(name = "install_state")
    val installState: String,

    @ColumnInfo(name = "checksum")
    val checksum: String?,

    @ColumnInfo(name = "version")
    val version: String,

    @ColumnInfo(name = "supports_structured_json")
    val supportsStructuredJson: Boolean,

    @ColumnInfo(name = "supports_streaming")
    val supportsStreaming: Boolean,

    @ColumnInfo(name = "supports_text_generation")
    val supportsTextGeneration: Boolean,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,

    @ColumnInfo(name = "installed_at")
    val installedAt: Long? = null
)

@Dao
interface LocalAiModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: LocalAiModelEntity)

    @Update
    suspend fun update(model: LocalAiModelEntity)

    @Query("SELECT * FROM local_ai_models WHERE model_id = :modelId")
    suspend fun getById(modelId: String): LocalAiModelEntity?

    @Query("SELECT * FROM local_ai_models ORDER BY display_name ASC")
    fun observeAll(): Flow<List<LocalAiModelEntity>>

    @Query("SELECT * FROM local_ai_models ORDER BY display_name ASC")
    suspend fun getAll(): List<LocalAiModelEntity>

    @Query("SELECT * FROM local_ai_models WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): LocalAiModelEntity?

    @Query("SELECT * FROM local_ai_models WHERE is_active = 1 LIMIT 1")
    fun observeActive(): Flow<LocalAiModelEntity?>

    @Query("SELECT * FROM local_ai_models WHERE install_state = :state")
    suspend fun getByState(state: String): List<LocalAiModelEntity>

    @Query("UPDATE local_ai_models SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE local_ai_models SET is_active = 1 WHERE model_id = :modelId")
    suspend fun activate(modelId: String)

    @Query("DELETE FROM local_ai_models WHERE model_id = :modelId")
    suspend fun delete(modelId: String)

    @Query("DELETE FROM local_ai_models")
    suspend fun deleteAll()
}
