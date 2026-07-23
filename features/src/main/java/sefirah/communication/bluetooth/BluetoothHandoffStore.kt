package sefirah.communication.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sefirah.domain.model.BluetoothHandoffConfiguration
import sefirah.domain.model.BluetoothHandoffState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothHandoffStore @Inject constructor() {
    private var latestConfigurationRevision = 0L

    private val _configuration = MutableStateFlow(BluetoothHandoffConfiguration())
    val configuration: StateFlow<BluetoothHandoffConfiguration> = _configuration.asStateFlow()

    private val _state = MutableStateFlow<BluetoothHandoffState?>(null)
    val state: StateFlow<BluetoothHandoffState?> = _state.asStateFlow()

    fun updateConfiguration(configuration: BluetoothHandoffConfiguration) {
        if (configuration.revision > 0 && configuration.revision < latestConfigurationRevision) return
        if (configuration.revision > 0) latestConfigurationRevision = configuration.revision
        _configuration.value = configuration
    }

    fun updateState(state: BluetoothHandoffState) {
        _state.value = state
    }
}
