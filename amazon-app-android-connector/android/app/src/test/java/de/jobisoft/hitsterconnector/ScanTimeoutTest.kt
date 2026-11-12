package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.ScanTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTimeoutTest {
    @Test
    fun theCameraTimeoutIsFifteenSeconds() {
        assertEquals(15_000L, ScanTimeout.MS)
        assertEquals(15, ScanTimeout.SECONDS)
    }

    @Test
    fun theSecondsCountDownAndRoundUp() {
        assertEquals(15, ScanTimeout.remainingSeconds(1_000, 1_000))
        assertEquals(15, ScanTimeout.remainingSeconds(1_000, 1_100))
        assertEquals(14, ScanTimeout.remainingSeconds(1_000, 2_100))
        assertEquals(1, ScanTimeout.remainingSeconds(1_000, 15_999))
        assertEquals(0, ScanTimeout.remainingSeconds(1_000, 16_000))
    }

    @Test
    fun itExpiresAfterFifteenSecondsAndNotBefore() {
        assertFalse(ScanTimeout.expired(1_000, 15_999))
        assertTrue(ScanTimeout.expired(1_000, 16_000))
        assertTrue(ScanTimeout.expired(1_000, 99_000))
    }

    @Test
    fun aClockBeforeTheStartIsTheFullTime() {
        assertEquals(15_000L, ScanTimeout.remainingMs(5_000, 5_000))
    }
}
