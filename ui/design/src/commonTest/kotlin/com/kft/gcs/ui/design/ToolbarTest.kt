package com.kft.gcs.ui.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolbarTest {
    private val icon = ImageVector.Builder("t", 24.dp, 24.dp, 24f, 24f).build()
    private fun action(name: String) = ToolbarEntry.Action(icon, name) {}
    private val d = ToolbarEntry.Divider

    // a b | c d | e: 5 buttons × 48 + 2 dividers × 17 = 274 dp
    private val entries = listOf(action("a"), action("b"), d, action("c"), action("d"), d, action("e"))
    private fun names(list: List<ToolbarEntry>) = list.map { (it as? ToolbarEntry.Action)?.label ?: "|" }

    @Test
    fun everythingShowsWhenItFits() {
        val (shown, overflow) = splitForWidth(entries, 274.dp)
        assertEquals(entries, shown)
        assertEquals(emptyList(), overflow)
    }

    @Test
    fun theEndMovesIntoTheMenuAndRoomIsKeptForTheMoreButton() {
        // 273 dp: one short. With the 48 dp "more" button: a(96) b(144) |(161) c(209) d(257), then | would be 274.
        val (shown, overflow) = splitForWidth(entries, 273.dp)
        assertEquals(listOf("a", "b", "|", "c", "d"), names(shown))
        assertEquals(listOf("e"), names(overflow))
    }

    @Test
    fun aDividerNeverEndsTheRowOrGoesIntoTheMenu() {
        // 170 dp: more(48) a(96) b(144) |(161), c would be 209 → the row ends in a divider, which is dropped.
        val (shown, overflow) = splitForWidth(entries, 170.dp)
        assertEquals(listOf("a", "b"), names(shown))
        assertEquals(listOf("c", "d", "e"), names(overflow))
    }
}
