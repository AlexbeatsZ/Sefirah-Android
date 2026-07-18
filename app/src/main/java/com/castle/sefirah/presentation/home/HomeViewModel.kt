package com.castle.sefirah.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sefirah.domain.model.ActionInfo
import sefirah.domain.model.AudioDeviceInfo
import sefirah.domain.model.BatteryState
import sefirah.domain.model.MediaAction
import sefirah.domain.model.MediaActionType
import sefirah.domain.model.PlaybackInfo
import sefirah.domain.model.SocketMessage
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.actions.ActionFeature
import sefirah.media.RemotePlaybackFeature
import sefirah.status.RemoteDeviceStatusFeature
import sefirah.communication.bluetooth.BluetoothHandoffStore
import sefirah.domain.model.BluetoothHandoffRequest
import sefirah.domain.model.BluetoothDisconnectRequest
import sefirah.domain.model.BluetoothHandoffRefreshRequest
import sefirah.domain.model.BluetoothHeadsetVisibilityRequest
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val remotePlaybackFeature: RemotePlaybackFeature,
    private val deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
    actionFeature: ActionFeature,
    remoteDeviceStatusFeature: RemoteDeviceStatusFeature,
    bluetoothHandoffStore: BluetoothHandoffStore,
) : ViewModel() {

    val headsetConfiguration = bluetoothHandoffStore.configuration
    val headsetHandoffState = bluetoothHandoffStore.state
    val selectedEndpointId = deviceManager.selectedDeviceId

    val batteryByDevice: StateFlow<Map<String, BatteryState>> =
        remoteDeviceStatusFeature.batteryByDevice

    val activeSessions: StateFlow<List<PlaybackInfo>> = deviceManager.selectedDeviceId
        .flatMapLatest { deviceId ->
            remotePlaybackFeature.activeSessionsByDevice.map { it[deviceId] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val audioDevices: StateFlow<List<AudioDeviceInfo>> = deviceManager.selectedDeviceId
        .flatMapLatest { deviceId ->
            remotePlaybackFeature.audioDevicesByDevice.map { it[deviceId] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actions: StateFlow<List<ActionInfo>> = deviceManager.selectedDeviceId
        .flatMapLatest { deviceId ->
            actionFeature.actionsByDevice.map { it[deviceId] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onPlayPause(session: PlaybackInfo) = sendMessageToSelectedDevice(
        MediaAction(if (session.isPlaying) MediaActionType.Pause else MediaActionType.Play, session.source)
    )

    fun onNext(session: PlaybackInfo) = sendMessageToSelectedDevice(
        MediaAction(MediaActionType.Next, session.source)
    )

    fun onPrevious(session: PlaybackInfo) = sendMessageToSelectedDevice(
        MediaAction(MediaActionType.Previous, session.source)
    )

    fun onSeek(session: PlaybackInfo, position: Double) = sendMessageToSelectedDevice(
        MediaAction(MediaActionType.Seek, session.source, position)
    )

    fun onVolumeChange(device: AudioDeviceInfo, volume: Float) {
        sendMessageToSelectedDevice(MediaAction(MediaActionType.VolumeUpdate, device.deviceId, volume.toDouble()))
    }

    fun toggleMute(device: AudioDeviceInfo) {
        sendMessageToSelectedDevice(MediaAction(MediaActionType.ToggleMute, device.deviceId))
    }

    fun setDefaultDevice(device: AudioDeviceInfo) {
        sendMessageToSelectedDevice(MediaAction(MediaActionType.DefaultDevice, device.deviceId))
    }

    fun sendMessageToSelectedDevice(message: SocketMessage) {
        deviceManager.selectedDeviceId.value?.let { deviceId ->
            networkManager.sendMessage(deviceId, message)
        }
    }

    fun switchHeadset(headsetId: String, targetEndpointId: String) {
        sendToBluetoothCoordinator(
            BluetoothHandoffRequest(
                operationId = UUID.randomUUID().toString(),
                headsetId = headsetId,
                targetEndpointId = targetEndpointId,
            ),
        )
    }

    fun disconnectHeadset(headsetId: String, endpointId: String) {
        sendToBluetoothCoordinator(
            BluetoothDisconnectRequest(
                operationId = UUID.randomUUID().toString(),
                headsetId = headsetId,
                endpointId = endpointId,
            ),
        )
    }

    fun refreshBluetoothDevices() {
        sendToBluetoothCoordinator(BluetoothHandoffRefreshRequest)
    }

    fun setHeadsetVisibility(headsetId: String, isVisible: Boolean) {
        sendToBluetoothCoordinator(BluetoothHeadsetVisibilityRequest(headsetId, isVisible))
    }

    private fun sendToBluetoothCoordinator(message: SocketMessage) {
        val coordinator = deviceManager.pairedDevices.value.firstOrNull {
            it.connectionState.isConnected &&
                it.supportsCapability(sefirah.domain.model.ProtocolCapabilities.BLUETOOTH_HANDOFF_V1)
        } ?: return
        networkManager.sendMessage(coordinator.deviceId, message)
    }
}
