package com.kft.gcs.feature.replay.di

import com.kft.gcs.feature.replay.ReplayViewModel
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Replay bindings. The file dialog ([com.kft.gcs.feature.replay.ReplayFiles]) comes from the app, which owns the platform ones. */
val replayModule = module {
    // Parsing and analysis are CPU work, not I/O waits, so Default rather than the IO dispatcher.
    viewModel { ReplayViewModel(get(), Dispatchers.Default) }
}
