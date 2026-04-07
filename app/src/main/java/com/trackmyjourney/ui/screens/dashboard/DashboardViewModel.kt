package com.trackmyjourney.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmyjourney.data.repository.PeriodStats
import com.trackmyjourney.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import java.util.*
import javax.inject.Inject

enum class StatsPeriod(val label: String) {
    DAY("1D"),
    WEEK("1W"),
    MONTH("1M"),
    YEAR("1Y")
}

data class DashboardUiState(
    val selectedPeriod: StatsPeriod = StatsPeriod.DAY,
    val stats: PeriodStats = PeriodStats(),
    val isLoading: Boolean = true
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        selectPeriod(StatsPeriod.DAY)
    }

    fun selectPeriod(period: StatsPeriod) {
        _uiState.update { it.copy(selectedPeriod = period, isLoading = true) }
        observeStats(period)
    }

    private fun observeStats(period: StatsPeriod) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            // Re-compute stats when tracks change; debounce to avoid recomputing
            // on every point insert during an active recording session.
            repository.getAllTracks().debounce(500.milliseconds).collect {
                try {
                    val since = calculateSince(period)
                    val stats = repository.getStatsSince(since)
                    _uiState.update {
                        it.copy(stats = stats, isLoading = false)
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(stats = PeriodStats(), isLoading = false)
                    }
                }
            }
        }
    }

    private fun calculateSince(period: StatsPeriod): Long {
        val cal = Calendar.getInstance()
        when (period) {
            StatsPeriod.DAY -> cal.add(Calendar.DAY_OF_YEAR, -1)
            StatsPeriod.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, -1)
            StatsPeriod.MONTH -> cal.add(Calendar.MONTH, -1)
            StatsPeriod.YEAR -> cal.add(Calendar.YEAR, -1)
        }
        return cal.timeInMillis
    }
}
