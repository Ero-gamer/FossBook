package com.eepiemi.materialbook.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledScriptsTest {

    private fun contentFor(resourceId: Int) = "content-$resourceId;"

    @Test
    fun concatenatesEnabledScripts_inCallerOrder() {
        val result = bundledScripts(
            listOf(Script(true, 2), Script(true, 1), Script(true, 3)),
            ::contentFor
        )

        assertEquals("content-2;content-1;content-3;", result)
    }

    @Test
    fun skipsDisabledScripts() {
        val result = bundledScripts(
            listOf(Script(true, 1), Script(false, 2)),
            ::contentFor
        )

        assertEquals("content-1;", result)
    }
}
