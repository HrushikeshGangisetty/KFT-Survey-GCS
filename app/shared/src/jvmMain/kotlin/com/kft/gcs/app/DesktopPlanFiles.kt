package com.kft.gcs.app

import com.kft.gcs.feature.plan.OpenedFile
import com.kft.gcs.feature.plan.PlanFiles
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Plan files on desktop, through AWT's `FileDialog`, which is the operating system's own dialog (the Windows one on
 * Windows). It's modal and must run on the UI thread; the ViewModel calls from `viewModelScope`, which is that thread.
 * The dialog runs its own event loop, so the window keeps painting while it's open. Reading and writing go to [io].
 */
class DesktopPlanFiles(private val io: CoroutineDispatcher) : PlanFiles {

    override suspend fun save(suggestedName: String, text: String): String? {
        val file = choose(FileDialog.SAVE, suggestedName) ?: return null
        withContext(io) { file.writeText(text) }
        return file.name
    }

    override suspend fun open(): OpenedFile? {
        val file = choose(FileDialog.LOAD, null) ?: return null
        return OpenedFile(file.name, withContext(io) { file.readText() })
    }

    private fun choose(mode: Int, name: String?): File? {
        val dialog = FileDialog(null as Frame?, if (mode == FileDialog.SAVE) "Save" else "Open a plan (.kftplan, .plan, .waypoints)", mode)
        name?.let { dialog.file = it }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }
}
