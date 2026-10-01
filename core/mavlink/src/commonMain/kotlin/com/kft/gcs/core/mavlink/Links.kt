package com.kft.gcs.core.mavlink

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every open link (ADR-005): one [ConnectionManager] each, numbered 1, 2, 3… in the order they were opened. A number
 * is never reused while the app runs, so a [VehicleId] can't come to mean another link's vehicle.
 *
 * [connect] and [disconnect] are called from the UI thread only (the Links screen), so `nextId` needs no lock.
 */
class Links internal constructor(private val newLink: (id: Int) -> ConnectionManager) {
    /** Production constructor: each link gets the real transports and its own gateway. */
    constructor(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher, podStatus: StateFlow<PodStatus>, serialPorts: SerialPorts, t12: T12Link) :
        this({ id -> ConnectionManager(scope, ioDispatcher, podStatus, serialPorts, t12, id) })

    private val _open = MutableStateFlow<List<ConnectionManager>>(emptyList())

    /** The links that are open or trying to open, oldest first. */
    val open: StateFlow<List<ConnectionManager>> = _open.asStateFlow()

    private var nextId = 1

    /** Opens [config] as a new link. A config that is already open is left alone: the same port can't be opened twice. */
    fun connect(config: LinkConfig) {
        if (_open.value.any { it.config == config }) return
        val link = newLink(nextId++)
        link.connect(config)
        _open.update { it + link }
    }

    /** Closes the link numbered [id] and forgets it, and with it the vehicles that were on it. */
    fun disconnect(id: Int) {
        val link = _open.value.find { it.id == id } ?: return
        link.disconnect()
        _open.update { it - link }
    }
}
