package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayProfilesTest {

    @Test
    fun `profiles survive a round trip`() {
        val profiles = mapOf("com.google.android.youtube.tv" to 120, "com.netflix.ninja" to 80)
        assertEquals(profiles, DelayProfiles.parse(DelayProfiles.serialize(profiles)))
    }

    @Test
    fun `malformed entries are skipped`() {
        assertEquals(mapOf("a" to 1, "c" to 3), DelayProfiles.parse("a=1;broken;b=x;=5;c=3"))
    }

    @Test
    fun `a delay is clamped to one second`() {
        assertEquals(1000, DelayProfiles.parse("a=5000")["a"])
    }

    @Test
    fun `an empty setting has no profiles`() {
        assertEquals(emptyMap<String, Int>(), DelayProfiles.parse(""))
    }
}
