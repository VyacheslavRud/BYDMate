package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerPolicyTest {
    @Test fun `selects the one exact HTTPS release asset`() {
        val expected = UpdateChecker.ReleaseAsset(
            name = "BYDMate-v3.7.0.apk",
            downloadUrl = "https://github.com/owner/repo/releases/download/v3.7.0/BYDMate-v3.7.0.apk",
        )

        val selected = UpdateChecker.selectExpectedApkAsset(
            version = "3.7.0",
            assets = listOf(
                UpdateChecker.ReleaseAsset("source.zip", "https://example.com/source.zip"),
                expected,
            ),
        )

        assertEquals(expected, selected)
    }

    @Test fun `rejects generic wrong duplicate and insecure APK assets`() {
        assertNull(
            UpdateChecker.selectExpectedApkAsset(
                "3.7.0",
                listOf(UpdateChecker.ReleaseAsset("app-release.apk", "https://example.com/app.apk")),
            )
        )
        assertNull(
            UpdateChecker.selectExpectedApkAsset(
                "3.7.0",
                listOf(
                    UpdateChecker.ReleaseAsset("BYDMate-v3.7.0.apk", "https://one.example/app.apk"),
                    UpdateChecker.ReleaseAsset("BYDMate-v3.7.0.apk", "https://two.example/app.apk"),
                ),
            )
        )
        assertNull(
            UpdateChecker.selectExpectedApkAsset(
                "3.7.0",
                listOf(UpdateChecker.ReleaseAsset("BYDMate-v3.7.0.apk", "http://example.com/app.apk")),
            )
        )
    }

    @Test fun `trusted update requires exact package and non-empty signer set`() {
        val installed = UpdateChecker.ApkIdentity("com.bydmate.app", setOf("signer-a", "signer-b"))

        assertTrue(
            UpdateChecker.isTrustedUpdateIdentity(
                installed,
                UpdateChecker.ApkIdentity("com.bydmate.app", setOf("signer-b", "signer-a")),
            )
        )
        assertFalse(
            UpdateChecker.isTrustedUpdateIdentity(
                installed,
                UpdateChecker.ApkIdentity("com.attacker.app", installed.signerDigests),
            )
        )
        assertFalse(
            UpdateChecker.isTrustedUpdateIdentity(
                installed,
                UpdateChecker.ApkIdentity("com.bydmate.app", setOf("signer-a")),
            )
        )
        assertFalse(
            UpdateChecker.isTrustedUpdateIdentity(
                UpdateChecker.ApkIdentity("com.bydmate.app", emptySet()),
                UpdateChecker.ApkIdentity("com.bydmate.app", emptySet()),
            )
        )
    }
}
