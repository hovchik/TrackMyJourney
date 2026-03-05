package com.trackjourney.data.local

import androidx.room.*
import com.trackjourney.data.model.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────
//  PERSON DAO
// ─────────────────────────────────────────────────────────

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: Person)

    @Update
    suspend fun update(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Query("SELECT * FROM persons ORDER BY is_self DESC, name ASC")
    fun getAllPersons(): Flow<List<Person>>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getPersonById(id: String): Person?

    @Query("SELECT * FROM persons WHERE is_self = 1 LIMIT 1")
    suspend fun getSelfPerson(): Person?

    @Query("SELECT * FROM persons WHERE is_self = 1 LIMIT 1")
    fun observeSelfPerson(): Flow<Person?>

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun getPersonCount(): Int
}

// ─────────────────────────────────────────────────────────
//  DISEASE GROUP DAO
// ─────────────────────────────────────────────────────────

@Dao
interface DiseaseGroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: DiseaseGroup)

    @Update
    suspend fun update(group: DiseaseGroup)

    @Delete
    suspend fun delete(group: DiseaseGroup)

    @Query("SELECT * FROM disease_groups ORDER BY sort_order ASC, name ASC")
    fun getAllGroups(): Flow<List<DiseaseGroup>>

    @Query("SELECT * FROM disease_groups WHERE id = :id")
    suspend fun getGroupById(id: String): DiseaseGroup?

    @Query("SELECT COUNT(*) FROM disease_groups")
    suspend fun getGroupCount(): Int
}

// ─────────────────────────────────────────────────────────
//  DISEASE DAO
// ─────────────────────────────────────────────────────────

@Dao
interface DiseaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(disease: Disease)

    @Update
    suspend fun update(disease: Disease)

    @Delete
    suspend fun hardDelete(disease: Disease)

    // Active diseases for a person
    @Query("SELECT * FROM diseases WHERE person_id = :personId AND is_deleted = 0 ORDER BY created_at DESC")
    fun getActiveDiseases(personId: String): Flow<List<Disease>>

    // All active diseases
    @Query("SELECT * FROM diseases WHERE is_deleted = 0 ORDER BY created_at DESC")
    fun getAllActiveDiseases(): Flow<List<Disease>>

    // Deleted (soft-deleted) diseases for recovery
    @Query("SELECT * FROM diseases WHERE is_deleted = 1 ORDER BY deleted_at DESC")
    fun getDeletedDiseases(): Flow<List<Disease>>

    // Deleted diseases for a specific person
    @Query("SELECT * FROM diseases WHERE person_id = :personId AND is_deleted = 1 ORDER BY deleted_at DESC")
    fun getDeletedDiseasesForPerson(personId: String): Flow<List<Disease>>

    @Query("SELECT * FROM diseases WHERE id = :id")
    suspend fun getDiseaseById(id: String): Disease?

    // Soft delete
    @Query("UPDATE diseases SET is_deleted = 1, deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long = System.currentTimeMillis())

    // Restore from soft delete
    @Query("UPDATE diseases SET is_deleted = 0, deleted_at = NULL WHERE id = :id")
    suspend fun restore(id: String)

    // Permanently delete old soft-deleted items (e.g., older than 30 days)
    @Query("DELETE FROM diseases WHERE is_deleted = 1 AND deleted_at < :olderThan")
    suspend fun purgeOldDeleted(olderThan: Long)

    // Diseases with group info
    @Transaction
    @Query("SELECT * FROM diseases WHERE person_id = :personId AND is_deleted = 0 ORDER BY created_at DESC")
    fun getDiseasesWithGroup(personId: String): Flow<List<DiseaseWithGroup>>

    // Count active diseases
    @Query("SELECT COUNT(*) FROM diseases WHERE is_deleted = 0")
    suspend fun getActiveDiseaseCount(): Int

    // Count deleted diseases
    @Query("SELECT COUNT(*) FROM diseases WHERE is_deleted = 1")
    suspend fun getDeletedDiseaseCount(): Int

    // Get diseases by group
    @Query("SELECT * FROM diseases WHERE group_id = :groupId AND is_deleted = 0 ORDER BY created_at DESC")
    fun getDiseasesByGroup(groupId: String): Flow<List<Disease>>

    // Get ungrouped diseases
    @Query("SELECT * FROM diseases WHERE group_id IS NULL AND person_id = :personId AND is_deleted = 0 ORDER BY created_at DESC")
    fun getUngroupedDiseases(personId: String): Flow<List<Disease>>
}
