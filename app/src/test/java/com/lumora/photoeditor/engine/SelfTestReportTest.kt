package com.lumora.photoeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executed verification of the GPU self-test report contract
 * ([SelfTestResult.toReportText]). This report is what the user copies to the
 * clipboard from the self-test dialog and sends back (the mask-shader
 * verification loop), so its structure must be stable and machine-readable:
 * exact header line, a device line, one PASS/FAIL line per check with trimmed
 * details on the following indented line, and a summary count. Blank or
 * whitespace-only details must not emit an empty detail line.
 *
 * Runs on the plain-JVM harness with an android.os.Build stub, because the
 * only Android dependency of the report path is [android.os.Build]; the GL
 * checks themselves stay on-device.
 */
class SelfTestReportTest {

    private fun reportOf(checks: List<SelfTestCheck>, durationMs: Long = 1500L) =
        SelfTestResult(checks, durationMs).toReportText()

    @Test fun header_firstLineIsFixedReportTitle() {
        val text = reportOf(listOf(SelfTestCheck("A", true, "")))
        assertEquals("H2W Photo Lab — GPU self-test report", text.lines().first())
    }

    @Test fun deviceLine_carriesBuildFields() {
        val text = reportOf(listOf(SelfTestCheck("A", true, "")))
        val device = text.lines()[1]
        assertTrue(device.startsWith("Device: "))
        assertTrue(device.contains(android.os.Build.MANUFACTURER))
        assertTrue(device.contains(android.os.Build.MODEL))
        assertTrue(device.contains(android.os.Build.VERSION.RELEASE))
        assertTrue(device.contains(android.os.Build.VERSION.SDK_INT.toString()))
    }

    @Test fun ranLine_presentAfterDeviceLine() {
        val text = reportOf(listOf(SelfTestCheck("A", true, "")))
        val lines = text.lines()
        assertTrue(lines[2].startsWith("Ran: "))
    }

    @Test fun passFail_markersPerCheck() {
        val text = reportOf(
            listOf(
                SelfTestCheck("Mask shader uMaskTex[0..3]", true, ""),
                SelfTestCheck("Blur pipeline", false, "readback mismatch"),
            ),
            durationMs = 0L,
        )
        assertTrue(text.contains("PASS — Mask shader uMaskTex[0..3]"))
        assertTrue(text.contains("FAIL — Blur pipeline"))
    }

    @Test fun detail_trimmedAndIndentedOnOwnLine() {
        val text = reportOf(listOf(SelfTestCheck("A", false, "   gpu readback wrong   ")))
        assertTrue(text.lines().contains("    gpu readback wrong"))
    }

    @Test fun blankDetail_emitsNoDetailLine() {
        val text = reportOf(listOf(SelfTestCheck("A", true, "")))
        assertFalse(text.lines().any { it.startsWith("    ") })
    }

    @Test fun whitespaceOnlyDetail_emitsNoDetailLine() {
        val text = reportOf(listOf(SelfTestCheck("A", true, "   \t ")))
        assertFalse(text.lines().any { it.startsWith("    ") })
    }

    @Test fun summaryLine_countsAndDuration() {
        val text = reportOf(
            listOf(
                SelfTestCheck("A", true, ""),
                SelfTestCheck("B", false, "x"),
                SelfTestCheck("C", true, ""),
            ),
            durationMs = 1500L,
        )
        assertTrue(text.lines().contains("Result: 2/3 checks passed (1500 ms)"))
    }

    @Test fun summaryLine_zeroOfZeroForEmptyChecks() {
        val text = reportOf(emptyList(), durationMs = 42L)
        assertTrue(text.lines().contains("Result: 0/0 checks passed (42 ms)"))
    }

    @Test fun allPassed_trueOnlyWhenNonEmptyAndAllPass() {
        assertTrue(SelfTestResult(listOf(SelfTestCheck("A", true, "")), 0L).allPassed)
        assertFalse(SelfTestResult(emptyList(), 0L).allPassed)
        assertFalse(
            SelfTestResult(
                listOf(SelfTestCheck("A", true, ""), SelfTestCheck("B", false, "")),
                0L,
            ).allPassed,
        )
    }
}
