package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    private fun release(v: String, code: Long, minSdk: Int = 26, url: String = "https://dl.mymob.dev/android/v$v/mob-android_$v.apk") =
        """{"version":"$v","version_code":$code,"min_sdk":$minSdk,"download":{"url":"$url","sha256":"x"},"pub_date":"2026-11-23T00:00:00Z"}"""

    @Test
    fun picksTheNewestInstallableRelease() {
        val json = "[${release("0.7.0", 7000, minSdk = 40)},${release("0.6.2", 6002)},${release("0.6.1", 6001)}]"
        assertEquals("0.6.2", AppUpdate.newer(json, installed = 6001, sdk = 37)?.version)
        assertEquals("0.7.0", AppUpdate.newer(json, installed = 6001, sdk = 40)?.version)
    }

    @Test
    fun nothingWhenCurrentOrTheManifestIsOdd() {
        assertNull(AppUpdate.newer("[${release("0.6.1", 6001)}]", installed = 6001, sdk = 37))
        assertNull(AppUpdate.newer("[${release("0.6.2", 6002, url = "https://evil.example/mob.apk")}]", installed = 6001, sdk = 37))
        assertNull(AppUpdate.newer("not json", installed = 6001, sdk = 37))
        assertNull(AppUpdate.newer("[]", installed = 6001, sdk = 37))
    }
}
