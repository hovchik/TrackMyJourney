package com.trackmyjourney.ui.screens.analysis

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmyjourney.data.ai.LocalAiEngine
import com.trackmyjourney.data.model.AiAnalysis
import com.trackmyjourney.data.model.TrackSession
import com.trackmyjourney.data.repository.ActivityBreakdown
import com.trackmyjourney.data.repository.TrackRepository
import com.trackmyjourney.data.repository.TrackingStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalysisUiState(
    val stats: TrackingStats = TrackingStats(0, 0.0, 0.0),
    val suggestions: List<LocalAiEngine.TripSuggestion> = emptyList(),
    val recentTracks: List<TrackSession> = emptyList(),
    val activityBreakdown: List<ActivityBreakdown> = emptyList(),
    val recentAnalyses: List<Pair<TrackSession, AiAnalysis>> = emptyList(),
    val isLoading: Boolean = false,
    val isAnalyzing: Boolean = false
)

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    companion object {
        private const val TAG = "AnalysisViewModel"
    }

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    fun loadData() {
        observeData()
    }

    fun refreshWithAiAnalysis() {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            try {
                val count = repository.reAnalyzeRecentTracks(5)
                Log.i(TAG, "AI re-analyzed $count tracks")
            } catch (e: Exception) {
                Log.e(TAG, "AI re-analysis failed: ${e.message}")
            } finally {
                _uiState.update { it.copy(isAnalyzing = false) }
                observeData()
            }
        }
    }

    private fun observeData() {
        viewModelScope.launch {
            repository.getAllTracks().collect { tracks ->
                _uiState.update { it.copy(isLoading = true, recentTracks = tracks.take(5)) }

                val stats = repository.getStats()
                val suggestions = repository.suggestBestTrips()
                val breakdown = repository.getActivityBreakdown()
                val recentAnalyses = repository.getRecentAnalyses(5)

                _uiState.update {
                    it.copy(
                        stats = stats,
                        suggestions = suggestions,
                        activityBreakdown = breakdown,
                        recentAnalyses = recentAnalyses,
                        isLoading = false
                    )
                }
            }
        }
    }
}
