package com.ai.notes

import org.junit.Assert.assertNotNull
import org.junit.Test

class AppTest {
    @Test
    fun appClassInstantiates() {
        val app = App()
        assertNotNull(app)
    }
}
