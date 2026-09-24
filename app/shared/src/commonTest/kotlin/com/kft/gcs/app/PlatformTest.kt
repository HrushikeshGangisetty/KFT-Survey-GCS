package com.kft.gcs.app

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformNameIsNotBlank() {
        assertTrue(Platform.name.isNotBlank())
    }
}
