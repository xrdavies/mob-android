package com.xrdavies.mob.data

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class FileTransfersTest {
    @Test fun locationsAndRangesStayBoundToThePairedFile() {
        val base = "https://office.example/".toHttpUrl()
        val path = "/file-uploads/" + "a".repeat(32)
        assertEquals("https://office.example$path", uploadLocation(base, path).toString())
        for (value in listOf("https://other.example$path", "/settings", "$path?token=bad", "http://office.example$path")) {
            assertThrows(java.io.IOException::class.java) { uploadLocation(base, value) }
        }
        checkDownloadRange(206, "bytes 4-7/8", "v1", "v1", 4, 7, 8)
        assertThrows(java.io.IOException::class.java) { checkDownloadRange(200, "bytes 4-7/8", "v1", "v1", 4, 7, 8) }
        assertThrows(java.io.IOException::class.java) { checkDownloadRange(206, "bytes 4-7/8", "v2", "v1", 4, 7, 8) }
    }

    @Test fun realMobdUploadAndDownloadResume() {
        val fixture = System.getenv("MOB_FILE_TEST_FIXTURE")
        assumeTrue("Set MOB_FILE_TEST_FIXTURE for the isolated mobd integration fixture", fixture != null)
        val config = Gson().fromJson(File(fixture!!).readText(), Map::class.java)
        val api = ApiClient(config["url"] as String, config["token"] as String)
        val dir = Files.createTempDirectory("mob-files-android-").toFile()
        try {
            val source = File(dir, "source")
            source.writeBytes(ByteArray(9 * 1024 * 1024 + 17) { (it % 251).toByte() })
            val id = UUID.randomUUID().toString()
            var task = FileTransfer(id, "fixture", api.baseUrl.toString(), true, "android 报告 $id.bin", source.length(), fileDigest(source))
            val first = FileTransferClient(api)
            assertThrows(Exception::class.java) {
                first.upload(task, source, { task = task.copy(uploadUrl = it) }, { if (it >= FILE_CHUNK) first.cancel() })
            }
            assertNotNull(task.uploadUrl)
            // Restore the same serialized shape as the app uses, with a fresh SDK client.
            task = Gson().fromJson(Gson().toJson(task), FileTransfer::class.java)
            assertFalse(Gson().toJson(task).contains(api.token!!))
            val receipt = FileTransferClient(api).upload(task, source, { task = task.copy(uploadUrl = it) }, {})
            assertEquals(task.sha256, receipt.sha256)
            assertEquals(receipt, FileTransferClient(api).upload(task, source, {}, {}))
            assertTrue(FileTransferClient(api).list().files.any { it.name == receipt.name })
            var download = task.copy(id = UUID.randomUUID().toString(), upload = false, name = receipt.name, uploadUrl = null, sha256 = "")
            val target = File(dir, "target").apply { createNewFile() }
            val interrupted = FileTransferClient(api)
            assertThrows(Exception::class.java) {
                interrupted.download(download, target, { size, etag, sha -> download = download.copy(size = size, etag = etag, sha256 = sha) }, { if (it >= FILE_CHUNK) interrupted.cancel() })
            }
            assertTrue(target.length() in 1 until source.length())
            FileTransferClient(api).download(download, target, { _, _, _ -> }, {})
            assertEquals(fileDigest(source), fileDigest(target))
            val duplicate = FileTransferClient(api).upload(task.copy(uploadUrl = null), source, {}, {})
            assertNotEquals(receipt.name, duplicate.name)
            val empty = File(dir, "empty").apply { createNewFile() }
            val zero = task.copy(name = "empty-$id.txt", size = 0, sha256 = fileDigest(empty), uploadUrl = null)
            assertEquals(0L, FileTransferClient(api).upload(zero, empty, {}, {}).size)
            // Folders (mobd with fileFolders): upload into a folder, list it, download from it, rename, delete.
            val top = FileTransferClient(api).list()
            if (top.folders != null) {
                val folder = "android 文件夹 $id"
                val inner = task.copy(id = UUID.randomUUID().toString(), name = "inner.bin", remotePath = "$folder/inner.bin", uploadUrl = null)
                assertEquals("$folder/inner.bin", FileTransferClient(api).upload(inner, source, {}, {}).name)
                assertTrue(FileTransferClient(api).list().folders!!.any { it.name == folder })
                val listed = FileTransferClient(api).list(folder)
                assertEquals(listOf("inner.bin"), listed.files.map { it.name })
                var nested = inner.copy(id = UUID.randomUUID().toString(), upload = false, sha256 = "")
                val got = File(dir, "nested").apply { createNewFile() }
                FileTransferClient(api).download(nested, got, { size, etag, sha -> nested = nested.copy(size = size, etag = etag, sha256 = sha) }, {})
                assertEquals(fileDigest(source), fileDigest(got))
                FileTransferClient(api).rename("$folder/inner.bin", "renamed.bin")
                assertEquals(listOf("renamed.bin"), FileTransferClient(api).list(folder).files.map { it.name })
                FileTransferClient(api).delete(folder)
                assertFalse(FileTransferClient(api).list().folders!!.any { it.name == folder })
            }
        } finally { dir.deleteRecursively() }
    }
}
