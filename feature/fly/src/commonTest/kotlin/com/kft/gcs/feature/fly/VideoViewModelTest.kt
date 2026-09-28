package com.kft.gcs.feature.fly

import com.kft.gcs.ui.video.ElementaryStreamSource
import com.kft.gcs.ui.video.PlayerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

class VideoViewModelTest {
    private class MemoryStore(var text: String? = null) : VideoStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val t12 = ElementaryStreamSource("t12", emptyFlow(), MutableStateFlow(null))

    @Test
    fun firstRunOffersThePresetsWithVideoOff() {
        val s = VideoViewModel(MemoryStore(), t12).state.value
        assertEquals(listOf("SIYI (RTSP 192.168.144.25:8554)", "Skydroid T12 (USB)"), s.profiles.map { it.name })
        assertNull(s.selected)
        assertNull(s.source)
        assertFalse(s.visible)
    }

    @Test
    fun theChosenSourceIsSavedAndComesBackAfterARestart() {
        val store = MemoryStore()
        VideoViewModel(store, t12).onSourceSelected("Skydroid T12 (USB)")
        val again = VideoViewModel(store, t12).state.value
        assertSame(t12, again.source)
        assertTrue(again.visible)
    }

    @Test
    fun theSiyiPresetPlaysItsRtspUrl() {
        val vm = VideoViewModel(MemoryStore(), t12)
        vm.onSourceSelected("SIYI (RTSP 192.168.144.25:8554)")
        assertEquals(PlayerSource("rtsp://192.168.144.25:8554/main.264"), vm.state.value.source)
    }

    @Test
    fun anOwnRtspUrlIsCheckedAddedAndDeletable() {
        val vm = VideoViewModel(MemoryStore(), t12)
        assertNotNull(vm.onRtspAdded("Bench", "http://10.0.0.5/live"))
        assertNotNull(vm.onRtspAdded("Skydroid T12 (USB)", "rtsp://10.0.0.5:8554/live"), "a preset's name")
        assertNull(vm.onRtspAdded(" Bench ", " rtsp://10.0.0.5:8554/live "))
        assertEquals(PlayerSource("rtsp://10.0.0.5:8554/live"), vm.state.value.source)
        assertTrue(vm.state.value.profiles.single { it.name == "Bench" }.custom)

        vm.onProfileDeleted("Bench")
        assertNull(vm.state.value.source)
        assertTrue(vm.state.value.profiles.none { it.name == "Bench" })
    }

    @Test
    fun hidingEndsTheSwapAndChoosingAgainShowsTheWindow() {
        val vm = VideoViewModel(MemoryStore(), t12)
        vm.onSourceSelected("Skydroid T12 (USB)")
        vm.onSwapClicked()
        assertTrue(vm.state.value.swapped)
        vm.onHideClicked()
        assertFalse(vm.state.value.visible)
        assertFalse(vm.state.value.swapped, "the map is never left small behind a hidden video")
        vm.onSourceSelected("Skydroid T12 (USB)")
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun aBrokenFileFallsBackToThePresets() {
        val s = VideoViewModel(MemoryStore("{ not json"), t12).state.value
        assertEquals(2, s.profiles.size)
        assertNull(s.selected)
    }
}
