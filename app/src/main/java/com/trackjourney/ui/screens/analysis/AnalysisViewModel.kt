package com.trackjourney.ui.screens.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.LocalAiEngine
import com.trackjourney.data.model.TrackSession
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.data.repository.TrackingStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalysisUiState(
    val stats: TrackingStats = TrackingStats(0, 0.0, 0.0),
    val suggestions: List<LocalAiEngine.TripSuggestion> = emptyList(),
    val recentTracks: List<TrackSession> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val stats = repository.getStats()
            val suggestions = repository.suggestBestTrips()

            _uiState.update {
                it.copy(
                    stats = stats,
                    suggestions = suggestions,
                    isLoading = false
                )
            }
        }

        viewModelScope.launch {
            repository.getAllTracks().collect { tracks ->
                _uiState.update { it.copy(recentTracks = tracks.take(5)) }
            }
        }
    }
}
