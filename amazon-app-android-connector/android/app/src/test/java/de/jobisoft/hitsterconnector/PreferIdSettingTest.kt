package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.PreferIdSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferIdSettingTest {
    @Test
    fun defaultIsOn() = assertTrue(PreferIdSetting.DEFAULT)

    @Test
    fun idIsUsedOnlyWithSettingOnAndAnId() {
        assertTrue(PreferIdSetting.useId(true, "B001"))
        assertFalse(PreferIdSetting.useId(true, null))
        assertFalse(PreferIdSetting.useId(false, "B001"))
        assertFalse(PreferIdSetting.useId(false, null))
    }

    @Test
    fun offLeavesNoIdForThePlayPath() {
        val id: String? = "B001"
        assertEquals(null, id.takeIf { PreferIdSetting.useId(false, it) })
        assertEquals("B001", id.takeIf { PreferIdSetting.useId(true, it) })
    }
}
