package com.kft.gcs.feature.plan

/**
 * The platform's "Save as…" and "Open…" dialogs, so the ViewModel can read and write plan files without knowing about
 * AWT (desktop) or Android's document picker. Both return null when the operator cancels.
 */
interface PlanFiles {
    /** Asks where to save, suggesting [suggestedName], and writes [text] there. Returns the chosen file's name. */
    suspend fun save(suggestedName: String, text: String): String?

    /** Asks for a file and returns its name and text. */
    suspend fun open(): OpenedFile?
}

data class OpenedFile(val name: String, val text: String)
