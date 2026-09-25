package com.kft.gcs.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.mavlink.LinkState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot things the screen does once, rather than draws continuously (CLAUDE.md §3). */
sealed interface ConnectionsEffect {
    data class ShowMessage(val text: String) : ConnectionsEffect
}

/**
 * The worked MVVM example: state flows down as one [ConnectionsUiState], events come up as `onXxx` calls.
 *
 * The ViewModel owns only the form text. Profiles and the link belong to the repository, because they outlive
 * this screen: the link must stay up when the user switches to the Fly view.
 */
class ConnectionsViewModel(private val repository: ConnectionsRepository) : ViewModel() {

    private val form = MutableStateFlow(ProfileForm())

    // Listed once at start and on Refresh, not polled: a USB device appearing is something the user just did.
    private val serialPorts = MutableStateFlow(repository.serialPorts())

    val state: StateFlow<ConnectionsUiState> =
        combine(repository.profiles, repository.linkState, form, serialPorts, repository.login, ::buildUiState)
            // WhileSubscribed(5 s): keep going through a rotation or a quick tab switch, stop if the screen is gone.
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                buildUiState(emptyList(), LinkState.Disconnected, ProfileForm(), serialPorts.value, login = null),
            )

    private val _effects = Channel<ConnectionsEffect>(Channel.BUFFERED)
    val effects: Flow<ConnectionsEffect> = _effects.receiveAsFlow()

    init {
        // Tell the user once when a vehicle appears or goes quiet, instead of making them watch the status card.
        viewModelScope.launch {
            repository.linkState
                .map { (it as? LinkState.Connected)?.vehicle }
                .map { it?.describe() }
                .distinctUntilChanged { old, new -> (old == null) == (new == null) }
                .drop(1) // the starting value isn't a change
                .collect { vehicle ->
                    _effects.send(ConnectionsEffect.ShowMessage(if (vehicle != null) "Vehicle found: $vehicle" else "Vehicle heartbeat lost"))
                }
        }
    }

    fun onConnectClicked(profileId: String) = repository.connect(profileId)

    fun onDisconnectClicked() = repository.disconnect()

    fun onDeleteClicked(profileId: String) = repository.deleteProfile(profileId)

    fun onFormKindChanged(kind: LinkKind) = form.update { it.copy(kind = kind, port = kind.defaultPort.toString(), error = null) }

    fun onFormNameChanged(name: String) = form.update { it.copy(name = name, error = null) }

    fun onFormHostChanged(host: String) = form.update { it.copy(host = host, error = null) }

    fun onFormPortChanged(port: String) = form.update { it.copy(port = port, error = null) }

    fun onFormSerialPortChanged(name: String) = form.update { it.copy(serialPort = name, error = null) }

    fun onFormBaudChanged(baud: Int) = form.update { it.copy(baud = baud, error = null) }

    fun onRefreshSerialPortsClicked() {
        serialPorts.value = repository.serialPorts()
    }

    fun onSaveProfileClicked() {
        val current = form.value
        current.toConfigOrError()
            .onSuccess { config ->
                repository.addProfile(current.name.trim().ifEmpty { config.summary }, config)
                form.value = ProfileForm()
            }
            .onFailure { error -> form.update { it.copy(error = error.message) } }
    }
}
