package com.kft.gcs.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.kft.gcs.feature.plan.OpenedFile
import com.kft.gcs.feature.plan.PlanFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Plan and parameter files on Android, through the system document picker (Storage Access Framework): the operator picks any
 * folder, Drive included, and the app needs no storage permission. The picker is an activity result, which only a
 * composable can launch, so [RegisterPlanFiles] connects the launchers while the UI is up.
 */
class AndroidPlanFiles(private val context: Context, private val io: CoroutineDispatcher) : PlanFiles {
    internal var create: ((String) -> Unit)? = null
    internal var open: (() -> Unit)? = null
    private var pending: CompletableDeferred<Uri?>? = null

    internal fun onPicked(uri: Uri?) {
        pending?.complete(uri)
    }

    override suspend fun save(suggestedName: String, text: String): String? {
        val launch = create ?: return null
        val uri = pick { launch(suggestedName) } ?: return null
        withContext(io) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) } }
        return nameOf(uri)
    }

    override suspend fun open(): OpenedFile? {
        val launch = open ?: return null
        val uri = pick(launch) ?: return null
        val text = withContext(io) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
        return OpenedFile(nameOf(uri), text)
    }

    private suspend fun pick(launch: () -> Unit): Uri? {
        val result = CompletableDeferred<Uri?>()
        pending = result
        launch()
        return result.await()
    }

    /** The name the picker shows ("mission.plan"), not the URI's opaque document id. */
    private fun nameOf(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file"
}

/** Registers the "create" and "open" document launchers for [files] while this composable is on screen. */
@Composable
internal fun RegisterPlanFiles(files: AndroidPlanFiles) {
    // octet-stream: the picker then keeps the extension we suggest (.kftplan, .plan, .waypoints) instead of adding .json.
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), files::onPicked)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), files::onPicked)
    DisposableEffect(files) {
        files.create = { create.launch(it) }
        files.open = { open.launch(arrayOf("*/*")) }
        onDispose {
            files.create = null
            files.open = null
        }
    }
}
