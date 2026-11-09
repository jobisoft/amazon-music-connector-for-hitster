package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.CrashReport
import de.jobisoft.hitsterconnector.util.JsonSafe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {

    @Test
    fun theReportHasTimeAppDeviceThreadAndTheStackTraceWithItsCauses() {
        val cause = IllegalStateException("the root cause")
        val text = CrashReport.format(
            "2026-11-09 20:00:00", "0.1.0-test-debug", "samsung SM-A326B, Android 13 (SDK 33)", "main",
            RuntimeException("outer", cause)
        )
        assertTrue(text.startsWith("Crash at 2026-11-09 20:00:00\nApp: 0.1.0-test-debug\nDevice: samsung SM-A326B"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("java.lang.RuntimeException: outer"))
        assertTrue("the cause is in", text.contains("Caused by: java.lang.IllegalStateException: the root cause"))
        assertTrue("with the place where it happened", text.contains("CrashReportTest"))
    }

    @Test
    fun aHugeStackTraceIsCutButKeepsItsBeginning() {
        fun deep(n: Int): Throwable = if (n == 0) RuntimeException("x".repeat(30_000)) else deep(n - 1)
        val text = CrashReport.format("t", "a", "d", "main", deep(3))
        assertTrue(text.length <= CrashReport.MAX_CHARS + 20)
        assertTrue(text.startsWith("Crash at t"))
        assertTrue(text.endsWith("... (cut)"))
    }

    @Test
    fun aNumberThatJsonRefusesBecomesText() {
        assertEquals("NaN", JsonSafe.number(Float.NaN))
        assertEquals("Infinity", JsonSafe.number(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", JsonSafe.number(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun anOrdinaryNumberStaysANumber() {
        assertEquals(1.5f, JsonSafe.number(1.5f))
        assertEquals(42L, JsonSafe.number(42L))
        assertEquals(7, JsonSafe.number(7))
    }
}
