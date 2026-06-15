package com.plan.ui.foods

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plan.data.api.FoodItem
import com.plan.data.repository.PlanRepository
import com.plan.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class FoodItemUiState(
    val name: String = "",
    val type: String = "",
    val innerType: String = "",
    val protein: String = "0",
    val fat: String = "0",
    val calories: String = "0",
    val available: String = "",
    val isNew: Boolean = false
)

data class FoodsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val snackMessage: String? = null,
    val foodsByType: Map<String, List<FoodItemUiState>> = emptyMap(),
    val collapsedTypes: Set<String> = emptySet()
)

val FOOD_TYPES = listOf("Protein", "Fat", "Carb")

// Drop the trailing ".0" Float.toString() adds (e.g. 52.0 -> "52") while keeping real
// decimals (85.71 -> "85.71"), so values fit the compact single-row cells.
private fun fmtNum(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

// Ensure the list ends with a fully blank row to type into (blank macros, not "0").
private fun List<FoodItemUiState>.withTrailingEmpty(type: String): List<FoodItemUiState> =
    if (isEmpty() || last().name.isNotBlank())
        this + FoodItemUiState(type = type, isNew = true, protein = "", fat = "", calories = "", available = "")
    else this

fun FoodItem.toUiState() = FoodItemUiState(
    name = name,
    type = type,
    innerType = inner_type,
    protein = fmtNum(protein),
    fat = fmtNum(fat),
    calories = fmtNum(calories),
    // Free text (Y / N / blank) — passed through verbatim so saving never rewrites it.
    available = available
)

fun FoodItemUiState.toApiModel() = FoodItem(
    name = name,
    type = type,
    inner_type = innerType,
    protein = protein.toFloatOrNull() ?: 0f,
    fat = fat.toFloatOrNull() ?: 0f,
    calories = calories.toFloatOrNull() ?: 0f,
    available = available
)

@HiltViewModel
class FoodsViewModel @Inject constructor(
    private val repository: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodsUiState())
    val uiState: StateFlow<FoodsUiState> = _uiState.asStateFlow()

    init {
        loadFoods()
    }

    fun loadFoods(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isRefreshing = true) else it.copy(isLoading = true, error = null)
            }
            when (val result = repository.getFoods()) {
                is Result.Success -> {
                    val grouped = FOOD_TYPES.associateWith { type ->
                        result.data.filter { it.type == type }
                            .map { it.toUiState() }
                            .withTrailingEmpty(type)
                    }
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, foodsByType = grouped) }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, snackMessage = result.message)
                }
            }
        }
    }

    fun pullRefresh() = loadFoods(isRefresh = true)

    fun toggleTypeCollapsed(type: String) {
        _uiState.update { state ->
            val collapsed = state.collapsedTypes.toMutableSet()
            if (collapsed.contains(type)) collapsed.remove(type) else collapsed.add(type)
            state.copy(collapsedTypes = collapsed)
        }
    }

    fun updateFoodField(type: String, index: Int, update: FoodItemUiState.() -> FoodItemUiState) {
        _uiState.update { state ->
            val map = state.foodsByType.toMutableMap()
            val list = map[type]?.toMutableList() ?: return@update state
            if (index < list.size) list[index] = list[index].update()
            // Keep one blank row at the bottom so the user can always type a new food into it
            // (mirrors the web's trailing-empty-row behavior; replaces the explicit "+ Add").
            map[type] = list.withTrailingEmpty(type)
            state.copy(foodsByType = map)
        }
    }

    fun saveFoods() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val allFoods = _uiState.value.foodsByType.values.flatten()
                .filter { it.name.isNotBlank() }
                .map { it.toApiModel() }
            when (val result = repository.saveFoods(allFoods)) {
                is Result.Success -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = "Foods saved")
                }
                is Result.Error -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = result.message)
                }
            }
        }
    }

    fun clearSnack() {
        _uiState.update { it.copy(snackMessage = null) }
    }
}
