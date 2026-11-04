package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.remote.PrefsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraOutputsTest {

    private val prefix = ExtraOutputs.keyPrefix(1)

    @Test
    fun `the stored list skips garbage, duplicates and the main controller`() {
        assertEquals(listOf(1, 3), ExtraOutputs.parseIds("1, x,3,1,0,-2,"))
    }

    @Test
    fun `an empty list gives no controllers`() {
        assertTrue(ExtraOutputs.parseIds(null).isEmpty())
    }

    @Test
    fun `the main controller keeps its keys as they are`() {
        assertEquals("", ExtraOutputs.keyPrefix(ExtraOutputs.MAIN))
    }

    @Test
    fun `settings of a controller reach the phone remote`() {
        assertTrue(PrefsCodec.isSynced(prefix + "pref_key_host"))
    }

    @Test
    fun `the prefix of one controller does not cover another`() {
        assertFalse((ExtraOutputs.keyPrefix(12) + "pref_key_host").startsWith(prefix))
    }

    @Test
    fun `the service reacts to controller settings and to the list`() {
        assertTrue(ExtraOutputs.isOutputKey(prefix + "pref_key_host"))
        assertTrue(ExtraOutputs.isOutputKey("pref_key_extra_outputs"))
    }

    @Test
    fun `settings of the main controller are not controller keys`() {
        assertFalse(ExtraOutputs.isOutputKey("pref_key_output_delay"))
    }

    @Test
    fun `a second Home Assistant connection becomes a controller`() {
        val changes = ExtraOutputs.migrateHa2(
            mapOf(
                "pref_key_ha2_enabled" to true,
                "pref_key_ha2_host" to "192.168.1.2",
                "pref_key_ha2_port" to "8124",
                "pref_key_ha2_token" to "secret",
                "pref_key_ha2_lamps" to "light.a\tleft\tLamp",
                "pref_key_ha2_dark_off" to false,
            )
        )
        assertEquals(
            mapOf(
                "pref_key_ha2_enabled" to null,
                "pref_key_ha2_host" to null,
                "pref_key_ha2_port" to null,
                "pref_key_ha2_token" to null,
                "pref_key_ha2_lamps" to null,
                "pref_key_ha2_dark_off" to null,
                prefix + "pref_key_output_enabled" to true,
                prefix + "pref_key_connection_type" to "homeassistant",
                prefix + "pref_key_host" to "192.168.1.2",
                prefix + "pref_key_port" to "8124",
                prefix + "pref_key_ha_token" to "secret",
                prefix + "pref_key_ha_lamps" to "light.a\tleft\tLamp",
                prefix + "pref_key_ha_dark_off" to false,
                "pref_key_extra_outputs" to "1",
            ),
            changes
        )
    }

    @Test
    fun `a connection without the enabled key stays off`() {
        val changes = ExtraOutputs.migrateHa2(mapOf("pref_key_ha2_host" to "ha.local"))
        assertEquals(false, changes[prefix + "pref_key_output_enabled"])
    }

    @Test
    fun `a connection on the default port gets it written down`() {
        val changes = ExtraOutputs.migrateHa2(mapOf("pref_key_ha2_host" to "ha.local"))
        assertEquals("8123", changes[prefix + "pref_key_port"])
    }

    @Test
    fun `a connection nobody filled in is only cleaned up`() {
        val changes = ExtraOutputs.migrateHa2(
            mapOf("pref_key_ha2_enabled" to true, "pref_key_ha2_host" to " ")
        )
        assertEquals(
            mapOf("pref_key_ha2_enabled" to null, "pref_key_ha2_host" to null),
            changes
        )
    }

    @Test
    fun `settings without the old connection stay untouched`() {
        assertTrue(ExtraOutputs.migrateHa2(mapOf("pref_key_host" to "wled.local")).isEmpty())
    }

    @Test
    fun `the migrated connection takes the next free number`() {
        val changes = ExtraOutputs.migrateHa2(
            mapOf("pref_key_extra_outputs" to "1,4", "pref_key_ha2_token" to "secret")
        )
        assertEquals("1,4,5", changes["pref_key_extra_outputs"])
    }
}
