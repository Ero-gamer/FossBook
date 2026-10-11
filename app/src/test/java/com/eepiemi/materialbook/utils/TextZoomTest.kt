package com.eepiemi.materialbook.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextZoomTest {

    @Test
    fun storedValues_areTheirPercentage() {
        TextZoom.ALL.forEach { assertEquals(it.toInt(), TextZoom.percent(it)) }
    }

    @Test
    fun unknownStoredValue_isTheDefault() {
        listOf("", "bogus", "0", "1000", "100.0").forEach { assertEquals(100, TextZoom.percent(it)) }
    }

    @Test
    fun defaultIsOneOfTheChoices() {
        assertTrue(TextZoom.DEFAULT in TextZoom.ALL)
        assertEquals(100, TextZoom.percent(TextZoom.DEFAULT))
    }
}
