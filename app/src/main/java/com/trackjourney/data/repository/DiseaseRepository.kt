package com.trackjourney.data.repository

import com.trackjourney.data.local.DiseaseDao
import com.trackjourney.data.local.DiseaseGroupDao
import com.trackjourney.data.local.PersonDao
import com.trackjourney.data.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiseaseRepository @Inject constructor(
    private val personDao: PersonDao,
    private val diseaseGroupDao: DiseaseGroupDao,
    private val diseaseDao: DiseaseDao
) {
    // ── Persons ──────────────────────────────────────────

    fun getAllPersons(): Flow<List<Person>> = personDao.getAllPersons()

    suspend fun getSelfPerson(): Person? = personDao.getSelfPerson()

    fun observeSelfPerson(): Flow<Person?> = personDao.observeSelfPerson()

    suspend fun getPersonById(id: String): Person? = personDao.getPersonById(id)

    suspend fun insertPerson(person: Person) = personDao.insert(person)

    suspend fun updatePerson(person: Person) = personDao.update(person)

    suspend fun deletePerson(person: Person) = personDao.delete(person)

    suspend fun ensureSelfPersonExists(): Person {
        val existing = personDao.getSelfPerson()
        if (existing != null) return existing

        val self = Person(
            name = "Self",
            relationship = "Self",
            isSelf = true
        )
        personDao.insert(self)
        return self
    }

    // ── Disease Groups ───────────────────────────────────

    fun getAllGroups(): Flow<List<DiseaseGroup>> = diseaseGroupDao.getAllGroups()

    suspend fun getGroupById(id: String): DiseaseGroup? = diseaseGroupDao.getGroupById(id)

    suspend fun insertGroup(group: DiseaseGroup) = diseaseGroupDao.insert(group)

    suspend fun updateGroup(group: DiseaseGroup) = diseaseGroupDao.update(group)

    suspend fun deleteGroup(group: DiseaseGroup) = diseaseGroupDao.delete(group)

    suspend fun ensureDefaultGroupsExist() {
        if (diseaseGroupDao.getGroupCount() > 0) return

        val defaults = listOf(
            DiseaseGroup(name = "Respiratory", colorHex = 0xFF2196F3, iconName = "Air", sortOrder = 0),
            DiseaseGroup(name = "Digestive", colorHex = 0xFFFF9800, iconName = "Restaurant", sortOrder = 1),
            DiseaseGroup(name = "Cardiovascular", colorHex = 0xFFE53935, iconName = "FavoriteBorder", sortOrder = 2),
            DiseaseGroup(name = "Neurological", colorHex = 0xFF9C27B0, iconName = "Psychology", sortOrder = 3),
            DiseaseGroup(name = "Musculoskeletal", colorHex = 0xFF4CAF50, iconName = "FitnessCenter", sortOrder = 4),
            DiseaseGroup(name = "Skin", colorHex = 0xFFFF5722, iconName = "Face", sortOrder = 5),
            DiseaseGroup(name = "Other", colorHex = 0xFF607D8B, iconName = "MoreHoriz", sortOrder = 99)
        )
        defaults.forEach { diseaseGroupDao.insert(it) }
    }

    // ── Diseases ─────────────────────────────────────────

    fun getActiveDiseases(personId: String): Flow<List<Disease>> =
        diseaseDao.getActiveDiseases(personId)

    fun getAllActiveDiseases(): Flow<List<Disease>> =
        diseaseDao.getAllActiveDiseases()

    fun getDeletedDiseases(): Flow<List<Disease>> =
        diseaseDao.getDeletedDiseases()

    fun getDeletedDiseasesForPerson(personId: String): Flow<List<Disease>> =
        diseaseDao.getDeletedDiseasesForPerson(personId)

    fun getDiseasesWithGroup(personId: String): Flow<List<DiseaseWithGroup>> =
        diseaseDao.getDiseasesWithGroup(personId)

    fun getDiseasesByGroup(groupId: String): Flow<List<Disease>> =
        diseaseDao.getDiseasesByGroup(groupId)

    fun getUngroupedDiseases(personId: String): Flow<List<Disease>> =
        diseaseDao.getUngroupedDiseases(personId)

    suspend fun getDiseaseById(id: String): Disease? = diseaseDao.getDiseaseById(id)

    suspend fun insertDisease(disease: Disease) = diseaseDao.insert(disease)

    suspend fun updateDisease(disease: Disease) =
        diseaseDao.update(disease.copy(updatedAt = System.currentTimeMillis()))

    suspend fun softDeleteDisease(id: String) = diseaseDao.softDelete(id)

    suspend fun restoreDisease(id: String) = diseaseDao.restore(id)

    suspend fun permanentlyDeleteDisease(disease: Disease) = diseaseDao.hardDelete(disease)

    suspend fun purgeOldDeletedDiseases(olderThanDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000)
        diseaseDao.purgeOldDeleted(cutoff)
    }
}
