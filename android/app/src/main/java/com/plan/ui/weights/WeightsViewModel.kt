package com.plan.ui.weights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plan.data.api.Day
import com.plan.data.repository.PlanRepository
import com.plan.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class WeightDataPoint(val date: String, val weight: Float, val weekLabel: String = "")

data class WeightsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val dailyWeights: List<WeightDataPoint> = emptyList(),
    val weeklyAverages: List<WeightDataPoint> = emptyList(),
    val weeklyReductions: List<WeightDataPoint> = emptyList()
)

@HiltViewModel
class WeightsViewModel @Inject constructor(
    private val repository: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeightsUiState())
    val uiState: StateFlow<WeightsUiState> = _uiState.asStateFlow()

    init {
        loadWeights()
    }

    fun loadWeights() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.getWeights()) {
                is Result.Success -> {
                    val days = result.data.filter { it.weight > 0f }
                    val daily = days.map { WeightDataPoint(it.date, it.weight) }
                    val weekly = computeWeeklyAverages(days)
                    val reductions = computeWeeklyReductions(weekly)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            dailyWeights = daily,
                            weeklyAverages = weekly,
                            weeklyReductions = reductions
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    private fun computeWeeklyAverages(days: List<Day>): List<WeightDataPoint> {
        val sdf = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
        val cal = Calendar.getInstance()

        val byWeek = mutableMapOf<String, MutableList<Float>>()
        for (day in days) {
            try {
                cal.time = sdf.parse(day.date)!!
                val year = cal.get(Calendar.YEAR)
                val week = cal.get(Calendar.WEEK_OF_YEAR)
                val key = "$year-W${week.toString().padStart(2, '0')}"
                byWeek.getOrPut(key) { mutableListOf() }.add(day.weight)
            } catch (e: Exception) {}
        }

        return byWeek.entries.sortedBy { it.key }.map { (week, weights) ->
            WeightDataPoint(
                date = week,
                weight = weights.average().toFloat(),
                weekLabel = week
            )
        }
    }

    private fun computeWeeklyReductions(weeklyAverages: List<WeightDataPoint>): List<WeightDataPoint> {
        if (weeklyAverages.size < 2) return emptyList()
        return weeklyAverages.zipWithNext { a, b ->
            WeightDataPoint(
                date = b.date,
                weight = a.weight - b.weight,
                weekLabel = b.weekLabel
            )
        }
    }
}
