package com.kft.gcs.app

import com.kft.gcs.feature.params.MetadataSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Parameter descriptions from the web, kept one file per vehicle and firmware version in [dir] (e.g.
 * `Copter-4.5.7.pdef`), so the field, without internet, still has them after one download at the office.
 *
 * Plain `HttpURLConnection`, which Android and the desktop JVM both have: one GET of a public file doesn't justify an
 * HTTP client library. Timeouts are generous because the files are 2–3 MB and a tablet may be on a slow hotspot.
 */
class HttpMetadataSource(private val dir: File, private val io: CoroutineDispatcher) : MetadataSource {

    override suspend fun cached(key: String): String? = withContext(io) { file(key).takeIf { it.exists() }?.readText() }

    /** Through [FileTextStore]: written to a temporary file and renamed, so a crash never leaves half a file. */
    override suspend fun save(key: String, text: String) = withContext(io) { FileTextStore(file(key)).write(text) }

    override suspend fun download(url: String): String? = withContext(io) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    // Keys come from the firmware version text; anything outside a safe file-name alphabet becomes "_".
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".pdef")
}
