package com.lumora.photoeditor.data.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-ever executed verification of the license/trial state machine
 * (Phase D#9 slice 1 — data/billing/LicenseState.kt).
 *
 * Pure Kotlin, zero Android imports, so it runs on the standalone kotlinc
 * harness (see hidden_files/license-harness/run.sh). SelfTest check 71
 * pins the broad strokes on-device; this pins the sharp edges the
 * state machine's own KDoc *proves*: the check-digit claim ("EVERY
 * one-character typo is caught … 0/2625 false accepts across 5 bodies")
 * is verified exhaustively here rather than trusted on prose.
 */
class LicenseStateTest {

    private val day = MILLIS_PER_DAY
    private val now = 1_800_000_000_000L

    private val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Builds a XXXX-XXXX-XXXX-XXXX key through the public checkChar. */
    private fun makeKey(body15: String): String =
        (body15 + checkChar(body15)).chunked(4).joinToString("-")

    // ---- trial day math ------------------------------------------------

    @Test
    fun freshInstallShowsFullTrial() {
        assertEquals(LicenseStatus.Trial(TRIAL_DAYS), evaluateLicense(0L, now, null))
        assertEquals(LicenseStatus.Trial(TRIAL_DAYS), evaluateLicense(-42L, now, null))
    }

    @Test
    fun clockSkewFutureStartShowsFullTrial() {
        assertEquals(
            LicenseStatus.Trial(TRIAL_DAYS),
            evaluateLicense(now + day, now, null)
        )
    }

    @Test
    fun trialCountsDownInWholeDays() {
        assertEquals(LicenseStatus.Trial(14), evaluateLicense(now, now, null))
        assertEquals(
            LicenseStatus.Trial(11),
            evaluateLicense(now - 3 * day, now, null)
        )
        // 13 days + 23:59 in: still "1 day left", never 0.
        assertEquals(
            LicenseStatus.Trial(1),
            evaluateLicense(now - 13 * day - 23 * 3_600_000L - 59 * 60_000L, now, null)
        )
    }

    @Test
    fun trialExpiresExactlyAtFourteenDays() {
        assertEquals(
            LicenseStatus.TrialExpired,
            evaluateLicense(now - TRIAL_DAYS * day, now, null)
        )
        assertEquals(
            LicenseStatus.TrialExpired,
            evaluateLicense(now - (TRIAL_DAYS + 5) * day, now, null)
        )
        assertEquals(
            LicenseStatus.TrialExpired,
            evaluateLicense(now - 365 * day, now, null)
        )
    }

    // ---- license key outranks the trial --------------------------------

    @Test
    fun blankKeyFallsBackToTrialMath() {
        val midTrial = evaluateLicense(now - 3 * day, now, "  ")
        assertTrue(midTrial is LicenseStatus.Trial)
        val expired = evaluateLicense(now - (TRIAL_DAYS + 5) * day, now, "   ")
        assertEquals(LicenseStatus.TrialExpired, expired)
    }

    @Test
    fun validKeyBeatsAnExpiredTrial() {
        val good = makeKey("H2WPRO000000001")
        val lic = evaluateLicense(now - (TRIAL_DAYS + 5) * day, now, good)
        assertTrue(lic is LicenseStatus.Licensed && lic.plan == "Pro")
    }

    @Test
    fun invalidKeyNeverLicenses() {
        val bad = makeKey("H2WPRO000000001").dropLast(1) + "X"
        assertTrue(validateLicenseKey(bad) is KeyValidation.Invalid)
        assertEquals(
            LicenseStatus.Trial(11),
            evaluateLicense(now - 3 * day, now, bad)
        )
    }

    // ---- key shape -----------------------------------------------------

    @Test
    fun keysBuiltThroughCheckCharValidate() {
        listOf(
            "H2WPRO000000001",
            "ABCDEFGHIJKLMNO",
            "ZZZZZZZZZZZZZZZ",
            "012345678901234",
            "0A1B2C3D4E5F6G7"
        ).forEach { body ->
            assertTrue(
                "key for body $body should validate",
                validateLicenseKey(makeKey(body)) is KeyValidation.Valid
            )
        }
    }

    @Test
    fun lowercaseAndPaddedKeysValidate() {
        val good = makeKey("H2WPRO000000001")
        assertTrue(validateLicenseKey(good.lowercase()) is KeyValidation.Valid)
        assertTrue(validateLicenseKey("  $good  ") is KeyValidation.Valid)
    }

    @Test
    fun malformedKeysAreRejected() {
        assertTrue(validateLicenseKey("") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("ABC-123") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("ABCD-EFGH-IJKL") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("ABCD-EFGH-IJKL-MNOP-QRST") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("ABC-DEFG-HIJK-LMNO") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("ABCDE-FGHI-JKLM-NOPQ") is KeyValidation.Invalid)
        // 16 valid chars but no dashes: still the wrong shape.
        assertTrue(validateLicenseKey("ABCD EFGH IJKL MNOP") is KeyValidation.Invalid)
        assertTrue(validateLicenseKey("H2W!-PRO0-0000-0000") is KeyValidation.Invalid)
    }

    @Test
    fun wrongCheckDigitIsRejected() {
        val good = makeKey("H2WPRO000000001")
        val last = good.last()
        val wrong = good.dropLast(1) + if (last == '0') "1" else "0"
        assertTrue(validateLicenseKey(wrong) is KeyValidation.Invalid)
    }

    @Test
    fun checkCharRejectsWrongLength() {
        try {
            checkChar("TOOSHORT")
            assertTrue("checkChar should require exactly 15 chars", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun checkCharIsDeterministicAndInAlphabet() {
        val a = checkChar("H2WPRO000000001")
        val b = checkChar("H2WPRO000000001")
        assertEquals(a, b)
        assertTrue(a in alphabet)
    }

    // ---- the proven typo guarantee, executed ----------------------------

    @Test
    fun everySingleCharSubstitutionIsCaught() {
        // KDoc claim: multiplier 31 coprime to 36, so delta*31^k mod 36
        // never vanishes for delta in 1..35 — 0/2625 false accepts.
        val bodies = listOf(
            "H2WPRO000000001",
            "ABCDEFGHIJKLMNO",
            "ZZZZZZZZZZZZZZZ",
            "012345678901234",
            "0A1B2C3D4E5F6G7"
        )
        var falseAccepts = 0
        var mutations = 0
        for (body in bodies) {
            val check = checkChar(body)
            for (pos in 0 until 15) {
                for (alt in alphabet) {
                    if (alt == body[pos]) continue
                    mutations++
                    val mutated = body.substring(0, pos) + alt + body.substring(pos + 1)
                    val key = (mutated + check).chunked(4).joinToString("-")
                    if (validateLicenseKey(key) is KeyValidation.Valid) {
                        falseAccepts++
                    }
                }
            }
        }
        assertEquals(2625, mutations)
        assertEquals(
            "single-char typos must never validate, got $falseAccepts false accepts",
            0,
            falseAccepts
        )
    }

    @Test
    fun adjacentTranspositionSlipsExactlyWhenDocumented() {
        // KDoc: a transposition of positions i,i+1 changes the hash by
        // (a-b)*30*31^(13-i) mod 36, which vanishes exactly when
        // 6 | (a-b) — i.e. the two chars' alphabet indices differ by a
        // multiple of 6. This pins that mechanism directly: a swapped
        // key validates if and only if the documented condition holds.
        val bodies = listOf(
            "H2WPRO000000001",
            "ABCDEFGHIJKLMNO",
            "0A1B2C3D4E5F6G7",
            "QRSTUVWXYZ01234",
            "9999AAAA0000ZZZ"
        )
        var checked = 0
        for (body in bodies) {
            val check = checkChar(body)
            for (i in 0 until 14) {
                val a = alphabet.indexOf(body[i])
                val b = alphabet.indexOf(body[i + 1])
                if (a == b) continue
                checked++
                val swapped = body.substring(0, i) + body[i + 1] + body[i] +
                    body.substring(i + 2)
                val key = (swapped + check).chunked(4).joinToString("-")
                val accepted = validateLicenseKey(key) is KeyValidation.Valid
                val documentedSlip = ((a - b) % 6 + 6) % 6 == 0
                assertEquals(
                    "swap at $i in $body (indices $a/$b): accepted=$accepted " +
                        "but 6|($a-$b)=$documentedSlip",
                    documentedSlip,
                    accepted
                )
            }
        }
        assertTrue("need a real sample, got $checked", checked >= 50)
    }

    // ---- gating hook + status text -------------------------------------

    @Test
    fun isProGatingHook() {
        assertTrue(isPro(LicenseStatus.Licensed("Pro")))
        assertTrue(!isPro(LicenseStatus.Trial(14)))
        assertTrue(!isPro(LicenseStatus.TrialExpired))
    }

    @Test
    fun statusTextCoversEveryState() {
        assertEquals("Pro trial · 1 day left", licenseStatusText(LicenseStatus.Trial(1)))
        assertEquals(
            "Pro trial · 14 days left",
            licenseStatusText(LicenseStatus.Trial(14))
        )
        assertTrue(
            licenseStatusText(LicenseStatus.Trial(5)).contains("5 days left")
        )
        assertEquals("Trial ended", licenseStatusText(LicenseStatus.TrialExpired))
        assertEquals(
            "Licensed · Pro",
            licenseStatusText(LicenseStatus.Licensed("Pro"))
        )
    }

    @Test
    fun proFeatureListNamesRealThings() {
        assertTrue(PRO_FEATURES.isNotEmpty())
        assertTrue(PRO_FEATURES.all { it.isNotBlank() })
    }
}
