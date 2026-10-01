package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hand-worked on one field, in metres east and north of its south-west corner.
 *
 * The field is 290 m × 104 m (not round multiples of the spacings: a line of exactly 100 m with a photo every 10 m
 * is 10 or 11 photos on a rounding error). The camera at 50 m sees 75 m across and 50 m along (sensor 13.2 × 8.8 mm, focal length
 * 8.8 mm: 50 × 13.2 / 8.8 and 50 × 8.8 / 8.8). 60 % side overlap: lines 30 m apart. 80 % front overlap: a photo every
 * 10 m. Grid angle 0, starting south-west:
 * ```
 *   10 lines at x = 10, 40, 70, …, 280        pass 0: x = 10, flown north, photos at y = 0, 10, …, 100  (11)
 *                                             pass 1: x = 40, flown south, photos at y = 104, 94, …, 4  (11)
 *                                             pass 2: x = 70, flown north, …                 110 photos in all
 * ```
 * The run-in and run-out are 5 m: half a trigger distance, the least the camera switch-off needs.
 */
class ResumeTest {
    private val projection = LocalProjection(LatLon(-35.363261, 149.165230))
    private fun at(east: Double, north: Double) = projection.toLatLon(LocalPoint(east, north))
    private fun metres(p: LatLon) = projection.toLocal(p).let { round(it.x * 10) / 10 + 0.0 to round(it.y * 10) / 10 + 0.0 }

    private val camera = Camera(13.2, 8.8, 5472, 3648, 8.8, 0.0, 8.0, "test")
    private val field = listOf(at(0.0, 0.0), at(290.0, 0.0), at(290.0, 104.0), at(0.0, 104.0))
    private val params = SurveyParams(field, camera, SurveyHeight.Altitude(50.0), sideOverlap = 0.6, frontOverlap = 0.8, speedMs = 5.0, turnaround = Turnaround.Copter())
    private val plan = planSurvey(params)

    /** Photo k (0-based) of the plan above: 11 per pass, alternating north and south. */
    private fun planned(k: Int): LatLon {
        val (pass, i) = k / 11 to k % 11
        return at(10.0 + 30 * pass, if (pass % 2 == 0) 10.0 * i else 104.0 - 10 * i)
    }

    @Test
    fun thePlanIsTheOneInTheHeader() {
        assertEquals(30.0, plan.lineSpacingM, 1e-6)
        assertEquals(10.0, plan.triggerDistanceM, 1e-6)
        assertEquals(10, plan.grid.lineCount)
        assertEquals(110, plan.stats.photoCount)
        assertEquals(listOf(10.0 to 0.0, 40.0 to 104.0, 70.0 to 0.0), plan.grid.passes.take(3).map { metres(it.photoStart) })
    }

    /** 25 photos: two full passes (22) and three on pass 2, the last at (70, 20): pass 2, 20 m along. */
    @Test
    fun theLastPhotoIsFoundOnItsPass() {
        val position = plan.locateLastPhoto((0 until 25).map(::planned))!!
        assertEquals(0 to 2, position.leg to position.pass)
        assertEquals(20.0, position.alongM, 0.01)
        assertNull(plan.locateLastPhoto(emptyList()))
    }

    /** A real photo is a little late and a little off the line: (70.8, 20.6) is still pass 2, 20.6 m along. */
    @Test
    fun aPhotoSlightlyOffTheLineStillCountsForIt() {
        val position = plan.locateLastPhoto((0 until 24).map(::planned) + at(70.8, 20.6))!!
        assertEquals(2, position.pass)
        assertEquals(20.6, position.alongM, 0.01)
    }

    /**
     * Seven photos were missed, so only 18 arrived, the last still at (70, 20). The count alone would say "photo 18:
     * pass 1"; the photo's place says pass 2, and that wins.
     */
    @Test
    fun missedPhotosDoNotMoveTheResumePoint() {
        val position = plan.locateLastPhoto((0 until 17).map(::planned) + planned(24))!!
        assertEquals(2, position.pass)
        assertEquals(20.0, position.alongM, 0.01)
    }

    /** A photo 200 m from every line (taken in a wide turn): the count decides. 25 photos = 22 + 3: pass 2, 20 m along. */
    @Test
    fun aPhotoOffEveryLineFallsBackToTheCount() {
        val position = plan.locateLastPhoto((0 until 24).map(::planned) + at(150.0, 300.0))!!
        assertEquals(2, position.pass)
        assertEquals(20.0, position.alongM, 0.01)
    }

    /**
     * Resume after the photo at (70, 20): start one trigger distance back, at (70, 10), with the 5 m run-in before it
     * at (70, 5). That pass has 94 m left: photos at y = 10, 20, …, 100 = 10. Seven passes follow untouched: 77.
     * 87 photos = the 85 not yet taken + the 2 taken again for the overlap (y = 10 and y = 20).
     */
    @Test
    fun theRemainderStartsOnePhotoBackAndKeepsEveryLaterLine() {
        val rest = plan.remainderFrom(PlanPosition(0, 2, 20.0))
        val first = rest.grid.passes.first()
        assertEquals(70.0 to 10.0, metres(first.photoStart))
        assertEquals(70.0 to 5.0, metres(first.entry))
        assertEquals(70.0 to 104.0, metres(first.photoEnd))
        assertEquals(94.0, first.photoLengthM, 0.01)
        assertEquals(8, rest.grid.passes.size)
        assertEquals(plan.grid.passes.drop(3), rest.grid.passes.drop(1), "later passes are the plan's own")
        assertEquals(87, rest.stats.photoCount)
        assertEquals(8, rest.stats.lineCount)
        assertEquals(plan.grid.connectorsM.drop(2), rest.grid.connectorsM)
        // Speed is the plan's 5 m/s: time = distance / 5.
        assertEquals(rest.stats.distanceM / 5.0, rest.stats.flightTimeS, 0.01)
    }

    /** The very first photo of a pass: there is nothing a trigger distance back on this line, so it starts at the line's start. */
    @Test
    fun resumingAtTheStartOfAPassFliesTheWholePass() {
        val rest = plan.remainderFrom(PlanPosition(0, 2, 0.0))
        assertEquals(plan.grid.passes.drop(2), rest.grid.passes)
        assertEquals(88, rest.stats.photoCount) // 8 passes × 11
    }

    /** Covered so far: passes 0 and 1 whole, and pass 2 from (70, 0) to the last photo at (70, 20). */
    @Test
    fun coveredLinesEndAtTheLastPhoto() {
        val covered = plan.coveredLines(PlanPosition(0, 2, 20.0))
        assertEquals(
            listOf(listOf(10.0 to 0.0, 10.0 to 104.0), listOf(40.0 to 104.0, 40.0 to 0.0), listOf(70.0 to 0.0, 70.0 to 20.0)),
            covered.map { it.map(::metres) },
        )
    }

    /**
     * A crosshatch: after the 110 photos of the first set, the second set's lines run east–west. Its photo at the
     * point where it crosses the first set's line x = 40 lies on both. The count (photo 113, far past the first
     * set's 110) picks the second set.
     */
    @Test
    fun inACrosshatchTheCountTellsCrossingLinesApart() {
        val cross = planSurvey(params.copy(crosshatch = Crosshatch()))
        val second = cross.crosshatch!!.grid.passes.first()
        // Three photos along the second set's first line; the last is put exactly on x = 40.
        val line = second.photoPath()
        val y = projection.toLocal(line.first()).y
        val photos = (0 until 110).map(::planned) + listOf(line.first(), at(40.0, y))
        val position = cross.locateLastPhoto(photos)!!
        assertEquals(1 to 0, position.leg to position.pass)
        val rest = cross.remainderFrom(position)
        assertNull(rest.crosshatch, "only the second set is left, as the plan's one set of lines")
        assertEquals(cross.crosshatch.grid.passes.size, rest.grid.passes.size)
    }

    /**
     * For planning the rest again with another camera. Two lines done from the west (x = 10, 40): the rest begins
     * midway between x = 40 and x = 70, at x = 55. From the east (lines x = 280, 250 done): it ends at x = 235.
     */
    @Test
    fun theRemainingAreaStartsHalfASpacingBeforeTheFirstUnflownLine() {
        fun extent(p: List<LatLon>) = p.map(::metres).let { m -> m.minOf { it.first } to m.maxOf { it.first } }
        assertEquals(55.0 to 290.0, extent(remainingArea(field, 0.0, 30.0, EntryCorner.BOTTOM_LEFT, fromLine = 2)!!))
        assertEquals(0.0 to 235.0, extent(remainingArea(field, 0.0, 30.0, EntryCorner.TOP_RIGHT, fromLine = 2)!!))
        assertEquals(field, remainingArea(field, 0.0, 30.0, EntryCorner.BOTTOM_LEFT, fromLine = 0))
        assertNull(remainingArea(field, 0.0, 30.0, EntryCorner.BOTTOM_LEFT, fromLine = 10), "all 10 lines done")
    }
}
