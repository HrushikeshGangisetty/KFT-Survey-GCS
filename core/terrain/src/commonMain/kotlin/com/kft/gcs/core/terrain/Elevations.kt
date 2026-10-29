package com.kft.gcs.core.terrain

import com.kft.gcs.core.geo.LatLon
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ground height above mean sea level, in metres, for a list of points. An interface so the Plan screen's tests use a
 * made-up terrain, and so a DEM on disk (P1, for flying without internet) can replace the online source later.
 */
fun interface Elevations {
    /** One height per point, in the same order. Throws when the source can't answer (offline, server error). */
    suspend fun elevationsM(points: List<LatLon>): List<Double>
}

/**
 * Elevations from Open-Meteo's free elevation API (https://open-meteo.com/en/docs/elevation-api): no key, the
 * Copernicus GLO-90 DEM (90 m cells), heights above the EGM2008 geoid, which is what "AMSL" means for ArduPilot's
 * home altitude too. Up to [BATCH] points per request.
 *
 * Privacy: the planned points go to api.open-meteo.com, like the map tiles go to the tile server. Answers are kept in
 * memory (rounded to ~1 m), so dragging a waypoint asks only for the points that moved.
 */
class OpenMeteoElevations(private val client: HttpClient = HttpClient(CIO)) : Elevations {
    // ponytail: unbounded in-memory cache; a few thousand doubles per planning session. LRU if it ever matters.
    private val cache = mutableMapOf<Pair<Long, Long>, Double>()

    override suspend fun elevationsM(points: List<LatLon>): List<Double> {
        val missing = points.filter { key(it) !in cache }.distinctBy(::key)
        missing.chunked(BATCH).forEach { chunk ->
            val response = client.get(elevationUrl(chunk))
            check(response.status.isSuccess()) { "Elevation server answered ${response.status.value}" }
            chunk.zip(parseElevations(response.bodyAsText())).forEach { (p, h) -> cache[key(p)] = h }
        }
        return points.map { cache.getValue(key(it)) }
    }

    private fun key(p: LatLon) = (p.latitude * 1e5).roundToLong() to (p.longitude * 1e5).roundToLong()

    companion object {
        /** The API's own limit per request. */
        const val BATCH = 100
    }
}

/** The request for [points]: comma-separated latitudes and longitudes, 5 decimals (about 1 m, finer than the DEM). */
internal fun elevationUrl(points: List<LatLon>): String {
    fun List<Double>.csv() = joinToString(",") { ((it * 1e5).roundToLong() / 1e5).toString() }
    return "https://api.open-meteo.com/v1/elevation?latitude=${points.map { it.latitude }.csv()}&longitude=${points.map { it.longitude }.csv()}"
}

@Serializable
private data class ElevationReply(val elevation: List<Double>)

private val json = Json { ignoreUnknownKeys = true }

/** The `elevation` array of a reply: `{"elevation":[38.0,43.0]}`. */
internal fun parseElevations(body: String): List<Double> = json.decodeFromString<ElevationReply>(body).elevation
