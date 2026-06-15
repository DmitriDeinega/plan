package com.plan.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plan.data.api.Settings
import com.plan.data.repository.PlanRepository
import com.plan.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val settings: Settings? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.getSettings()) {
                is Result.Success -> _uiState.update {
                    it.copy(isLoading = false, settings = result.data)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}
