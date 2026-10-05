package com.caigit1.rootbattery

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatteryMonitorUiState(
    val rootReady: Boolean = false,
    val selfChecks: List<SelfCheckItem> = emptyList(),
    val latest: BatterySnapshot? = null,
    val history: List<BatterySnapshot> = emptyList(),
    val error: MonitorError? = null,
    val pollingSeconds: Int = 5,
    val alertsEnabled: Boolean = true,
    val serviceEnabled: Boolean = false
)

class BatteryMonitorViewModel(
    application: Application,
    private val repository: BatteryMonitorRepository
) : AndroidViewModel(application) {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                if (modelClass.isAssignableFrom(BatteryMonitorViewModel::class.java)) {
                    val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    return BatteryMonitorViewModel(application, BatteryMonitorRepository()) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    private val _uiState = MutableStateFlow(BatteryMonitorUiState())
    val uiState: StateFlow<BatteryMonitorUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        runSelfCheck()
        startPolling()
    }

    fun runSelfCheck() {
        viewModelScope.launch {
            val checks = repository.selfCheck()
            _uiState.update {
                it.copy(
                    selfChecks = checks,
                    rootReady = checks.firstOrNull()?.ok == true,
                    error = null
                )
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            applyResult(repository.refreshOnce())
        }
    }

    fun setPollingSeconds(seconds: Int) {
        _uiState.update { it.copy(pollingSeconds = seconds.coerceIn(2, 60)) }
        startPolling()
    }

    fun setAlertsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(alertsEnabled = enabled) }
    }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        _uiState.update { it.copy(serviceEnabled = enabled) }
        val context = getApplication<Application>()
        val intent = Intent(context, BatteryMonitorService::class.java)
        if (enabled) {
            context.startForegroundService(intent)
        } else {
            context.stopService(intent)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repository.poll(_uiState.value.pollingSeconds * 1000L).collect { result ->
                applyResult(result)
            }
        }
    }

    private fun applyResult(result: MonitorResult) {
        when (result) {
            is MonitorResult.Success -> {
                _uiState.update { state ->
                    val merged = (state.history + result.snapshot).takeLast(60)
                    state.copy(latest = result.snapshot, history = merged, error = null)
                }
            }

            is MonitorResult.Error -> {
                _uiState.update { it.copy(error = result.error) }
            }
        }
    }
}
