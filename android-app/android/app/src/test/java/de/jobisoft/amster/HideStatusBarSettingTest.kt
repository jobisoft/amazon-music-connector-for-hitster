package de.jobisoft.amster

import de.jobisoft.amster.util.HideStatusBarSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideStatusBarSettingTest {
    @Test
    fun theSettingIsOnByDefault() {
        assertTrue(HideStatusBarSetting.DEFAULT)
    }

    @Test
    fun theStatusBarIsHiddenOnlyWhileASongIsOnTheScreenAndTheSettingIsOn() {
        assertTrue(HideStatusBarSetting.shouldHide(enabled = true, songOnScreen = true))
        assertFalse(HideStatusBarSetting.shouldHide(enabled = true, songOnScreen = false))
        assertFalse(HideStatusBarSetting.shouldHide(enabled = false, songOnScreen = true))
        assertFalse(HideStatusBarSetting.shouldHide(enabled = false, songOnScreen = false))
    }
}
