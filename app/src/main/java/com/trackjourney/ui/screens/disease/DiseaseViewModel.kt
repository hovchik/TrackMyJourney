package com.trackjourney.ui.screens.disease

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.model.*
import com.trackjourney.data.repository.DiseaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiseaseUiState(
    val persons: List<Person> = emptyList(),
    val selectedPerson: Person? = null,
    val groups: List<DiseaseGroup> = emptyList(),
    val diseasesWithGroup: List<DiseaseWithGroup> = emptyList(),
    val deletedDiseases: List<Disease> = emptyList(),
    val showingTrash: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class DiseaseViewModel @Inject constructor(
    private val repository: DiseaseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiseaseUiState())
    val uiState: StateFlow<DiseaseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSelfPersonExists()
            repository.ensureDefaultGroupsExist()
            loadPersons()
        }
    }

    private fun loadPersons() {
        viewModelScope.launch {
            repository.getAllPersons().collect { persons ->
                val currentSelected = _uiState.value.selectedPerson
                val selected = when {
                    currentSelected != null && persons.any { it.id == currentSelected.id } -> currentSelected
                    else -> persons.find { it.isSelf } ?: persons.firstOrNull()
                }
                _uiState.update { it.copy(persons = persons, selectedPerson = selected) }
                selected?.let { loadDiseasesForPerson(it.id) }
            }
        }

        viewModelScope.launch {
            repository.getAllGroups().collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
    }

    private fun loadDiseasesForPerson(personId: String) {
        viewModelScope.launch {
            repository.getDiseasesWithGroup(personId).collect { diseases ->
                _uiState.update { it.copy(diseasesWithGroup = diseases, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repository.getDeletedDiseasesForPerson(personId).collect { deleted ->
                _uiState.update { it.copy(deletedDiseases = deleted) }
            }
        }
    }

    fun selectPerson(person: Person) {
        _uiState.update { it.copy(selectedPerson = person, isLoading = true) }
        loadDiseasesForPerson(person.id)
    }

    fun toggleTrashView() {
        _uiState.update { it.copy(showingTrash = !it.showingTrash) }
    }

    fun addDisease(
        name: String,
        description: String,
        groupId: String?,
        severity: DiseaseSeverity,
        status: DiseaseStatus,
        notes: String
    ) {
        val personId = _uiState.value.selectedPerson?.id ?: return
        viewModelScope.launch {
            val disease = Disease(
                name = name,
                description = description,
                personId = personId,
                groupId = groupId,
                severity = severity,
                status = status,
                onsetDate = System.currentTimeMillis(),
                notes = notes
            )
            repository.insertDisease(disease)
        }
    }

    fun updateDisease(disease: Disease) {
        viewModelScope.launch {
            repository.updateDisease(disease)
        }
    }

    fun softDeleteDisease(diseaseId: String) {
        viewModelScope.launch {
            repository.softDeleteDisease(diseaseId)
        }
    }

    fun restoreDisease(diseaseId: String) {
        viewModelScope.launch {
            repository.restoreDisease(diseaseId)
        }
    }

    fun permanentlyDeleteDisease(disease: Disease) {
        viewModelScope.launch {
            repository.permanentlyDeleteDisease(disease)
        }
    }

    fun addPerson(name: String, relationship: String) {
        viewModelScope.launch {
            val person = Person(name = name, relationship = relationship)
            repository.insertPerson(person)
        }
    }

    fun deletePerson(person: Person) {
        if (person.isSelf) return // Cannot delete Self
        viewModelScope.launch {
            repository.deletePerson(person)
        }
    }

    fun addGroup(name: String, colorHex: Long) {
        viewModelScope.launch {
            val count = _uiState.value.groups.size
            val group = DiseaseGroup(name = name, colorHex = colorHex, sortOrder = count)
            repository.insertGroup(group)
        }
    }

    fun deleteGroup(group: DiseaseGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
        }
    }

    fun updateDiseaseStatus(diseaseId: String, newStatus: DiseaseStatus) {
        viewModelScope.launch {
            val disease = repository.getDiseaseById(diseaseId) ?: return@launch
            val updated = disease.copy(
                status = newStatus,
                recoveryDate = if (newStatus == DiseaseStatus.RECOVERED) System.currentTimeMillis() else disease.recoveryDate
            )
            repository.updateDisease(updated)
        }
    }
}
