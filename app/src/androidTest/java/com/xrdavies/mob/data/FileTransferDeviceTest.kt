package com.xrdavies.mob.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in Android networking check against the isolated mobd fixture, not a real paired machine. */
@RunWith(AndroidJUnit4::class)
class FileTransferDeviceTest {
    @Test fun uploadAndDownloadResumeOnAndroid() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("fileTestUrl")
        val token = args.getString("fileTestToken")
        assumeTrue("Set fileTestUrl and fileTestToken for the isolated fixture", base != null && token != null)
        val api = ApiClient(base!!, token)
        val id = UUID.randomUUID().toString()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "file-test-$id").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { writeBytes(ByteArray(9 * 1024 * 1024 + 17) { (it % 251).toByte() }) }
            var task = FileTransfer(id, "fixture", api.baseUrl.toString(), true, "android-device-$id.bin", source.length(), fileDigest(source))
            val first = FileTransferClient(api)
            try {
                first.upload(task, source, { task = task.copy(uploadUrl = it) }, { if (it >= FILE_CHUNK) first.cancel() })
                fail("Expected upload interruption")
            } catch (_: Exception) { assertNotNull(task.uploadUrl) }
            val receipt = FileTransferClient(api).upload(task, source, { task = task.copy(uploadUrl = it) }, {})
            assertEquals(task.sha256, receipt.sha256)
            val target = File(root, "download").apply { createNewFile() }
            var download = task.copy(upload = false, name = receipt.name)
            val second = FileTransferClient(api)
            try {
                second.download(download, target, { size, etag, sha -> download = download.copy(size = size, etag = etag, sha256 = sha) }, { if (it >= FILE_CHUNK) second.cancel() })
                fail("Expected download interruption")
            } catch (_: Exception) { assertTrue(target.length() in 1 until source.length()) }
            FileTransferClient(api).download(download, target, { _, _, _ -> }, {})
            assertEquals(fileDigest(source), fileDigest(target))
            val empty = File(root, "empty").apply { createNewFile() }
            assertEquals(0L, FileTransferClient(api).upload(task.copy(name = "empty-$id.txt", size = 0, sha256 = fileDigest(empty), uploadUrl = null), empty, {}, {}).size)
        } finally { root.deleteRecursively() }
    }
}
