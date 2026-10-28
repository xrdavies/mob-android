package com.xrdavies.mob.data

import com.google.gson.Gson
import io.tus.java.client.TusClient
import io.tus.java.client.TusUpload
import io.tus.java.client.TusURLStore
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

const val FILE_CHUNK = 4 * 1024 * 1024
data class RemoteFile(val name: String, val size: Long, val modified: String)
data class FileListing(val directory: String, val maxUploadSize: Long, val files: List<RemoteFile>)
data class FileReceipt(val name: String, val size: Long, val sha256: String)
data class FileTransfer(
    val id: String, val machine: String, val base: String, val upload: Boolean,
    val name: String, val size: Long, val sha256: String, val bytes: Long = 0,
    val state: String = "paused", val uploadUrl: String? = null, val etag: String? = null,
    val message: String? = null,
)

fun uploadLocation(base: HttpUrl, value: String, creation: Boolean = false): HttpUrl {
    val root = base.newBuilder().addPathSegment("file-uploads").addPathSegment("").build()
    val url = root.resolve(value) ?: throw IOException("Invalid upload location")
    val id = url.encodedPath.removePrefix(root.encodedPath)
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
        !url.encodedPath.startsWith(root.encodedPath) || !(creation && id.isEmpty() || id.matches(Regex("[a-f0-9]{32}")))) {
        throw IOException("Invalid upload location returned by mobd")
    }
    return url
}

fun checkDownloadRange(code: Int, range: String?, etag: String?, expected: String, start: Long, end: Long, size: Long) {
    if (code == 412 || etag != expected) throw IOException("Remote file changed. Cancel and download the new version.")
    if (code != 206 || range != "bytes $start-$end/$size") throw IOException("Invalid download range returned by mobd")
}

fun fileDigest(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

/** One transfer; all calls run on Dispatchers.IO. cancel closes the live HTTP request. */
class FileTransferClient(private val api: ApiClient) {
    private val http = api.okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .readTimeout(10, TimeUnit.MINUTES).writeTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var canceled = false
    @Volatile private var call: Call? = null
    @Volatile private var connection: HttpURLConnection? = null
    private val gson = Gson()

    fun cancel() { canceled = true; call?.cancel(); connection?.disconnect() }
    private fun checkCanceled() { if (canceled || Thread.currentThread().isInterrupted) throw CancellationException("Paused") }
    private fun execute(request: Request): Response {
        checkCanceled()
        val call = http.newCall(request); this.call = call
        if (canceled) { call.cancel(); checkCanceled() }
        return call.execute()
    }
    private fun check(response: Response) {
        if (response.code == 401) throw UnauthorizedException()
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            val message = runCatching { gson.fromJson(body, Map::class.java)["error"] as? String }.getOrNull()
            throw HttpStatusException(response.code, message ?: "HTTP ${response.code}")
        }
    }

    fun list(): FileListing = execute(Request.Builder().url(api.url("files")).build()).use {
        check(it); gson.fromJson(it.body!!.charStream(), FileListing::class.java)
    }

    private fun complete(url: HttpUrl): FileReceipt? = execute(Request.Builder()
        .url(url.newBuilder().addPathSegment("complete").build()).post(ByteArray(0).toRequestBody()).build()).use {
        if (it.code == 409) return null
        check(it); gson.fromJson(it.body!!.charStream(), FileReceipt::class.java)
    }

    fun upload(task: FileTransfer, file: File, onUrl: (String) -> Unit, progress: (Long) -> Unit): FileReceipt {
        try { return uploadTus(task, file, onUrl, progress) }
        catch (e: Exception) {
            if (runCatching { connection?.responseCode }.getOrNull() == 401) throw UnauthorizedException()
            throw e
        }
    }

    private fun uploadTus(task: FileTransfer, file: File, onUrl: (String) -> Unit, progress: (Long) -> Unit): FileReceipt {
        var destination = task.uploadUrl?.let { uploadLocation(api.baseUrl, it) }
        destination?.let { complete(it)?.let { receipt -> return verified(task, receipt) } }
        if (file.length() != task.size || fileDigest(file) != task.sha256) throw IOException("Local upload copy changed. Cancel and select the file again.")
        checkCanceled()
        val tus = object : TusClient() {
            override fun prepareConnection(conn: HttpURLConnection) {
                uploadLocation(api.baseUrl, conn.url.toString(), creation = true)
                checkCanceled()
                super.prepareConnection(conn)
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                api.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                connection = conn
                if (canceled) { conn.disconnect(); checkCanceled() }
            }
        }
        tus.uploadCreationURL = URL(api.url("file-uploads/"))
        tus.enableResuming(object : TusURLStore {
            override fun set(fingerprint: String, url: URL) {
                destination = uploadLocation(api.baseUrl, url.toString())
                onUrl(destination.toString())
            }
            override fun get(fingerprint: String): URL? = destination?.toUrl()
            override fun remove(fingerprint: String) {}
        })
        val upload = TusUpload(file)
        upload.metadata = mapOf("filename" to task.name, "sha256" to task.sha256)
        val uploader = try {
            if (destination != null) tus.beginOrResumeUploadFromURL(upload, destination.toUrl()) else tus.createUpload(upload)
        } catch (e: Exception) { upload.inputStream.close(); throw e }
        try {
            destination = uploadLocation(api.baseUrl, uploader.uploadURL.toString())
            onUrl(destination.toString())
            uploader.chunkSize = 256 * 1024
            uploader.requestPayloadSize = FILE_CHUNK
            while (uploader.offset < task.size) {
                checkCanceled()
                if (uploader.uploadChunk() < 0) break
                progress(uploader.offset)
            }
            uploader.finish()
        } finally {
            runCatching { uploader.finish() }
            upload.inputStream.close()
        }
        checkCanceled()
        return verified(task, complete(destination) ?: throw IOException("Upload is incomplete; retry"))
    }

    private fun verified(task: FileTransfer, receipt: FileReceipt): FileReceipt {
        if (receipt.sha256 != task.sha256 || receipt.size != task.size) throw IOException("Upload verification failed")
        return receipt
    }

    fun download(task: FileTransfer, file: File, identity: (Long, String, String) -> Unit, progress: (Long) -> Unit) {
        val url = api.baseUrl.newBuilder().addPathSegment("files").addPathSegment(task.name).build()
        val (size, etag, sha) = execute(Request.Builder().url(url).head().build()).use { response ->
            check(response)
            val size = response.header("Content-Length")?.toLongOrNull() ?: throw IOException("Missing file size")
            val etag = response.header("ETag") ?: throw IOException("Missing file version")
            val sha = response.header("X-Content-SHA256").orEmpty()
            if (size < 0 || !sha.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid file information")
            Triple(size, etag, sha)
        }
        var offset = file.length()
        if (task.etag != null && task.etag != etag || offset > size || offset > 0 && task.etag == null) throw IOException("Remote file changed. Cancel and download the new version.")
        identity(size, etag, sha)
        file.parentFile?.mkdirs()
        if (!file.exists() && !file.createNewFile()) throw IOException("Could not create download file")
        // The caller creates the partial file; append and resume from its actual length.
        while (offset < size) {
            checkCanceled()
            val end = minOf(offset + FILE_CHUNK, size) - 1
            execute(Request.Builder().url(url).header("Range", "bytes=$offset-$end").header("If-Match", etag).build()).use { response ->
                check(response)
                checkDownloadRange(response.code, response.header("Content-Range"), response.header("ETag"), etag, offset, end, size)
                val body = response.body ?: throw IOException("Missing download data")
                if (body.contentLength() != end - offset + 1) throw IOException("Invalid download size")
                java.io.FileOutputStream(file, true).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (offset <= end) {
                            checkCanceled()
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), end - offset + 1).toInt())
                            if (n < 0) throw IOException("Download interrupted; retry to continue")
                            output.write(buffer, 0, n)
                            offset += n
                            progress(offset)
                        }
                    }
                    output.fd.sync()
                }
            }
        }
        checkCanceled()
        if (fileDigest(file) != sha) throw IOException("Download checksum mismatch. Cancel and download again.")
    }

    fun removeUpload(url: String) {
        val target = uploadLocation(api.baseUrl, url)
        execute(Request.Builder().url(target).delete().header("Tus-Resumable", "1.0.0").build()).use {
            if (it.code != 404 && it.code != 410) check(it)
        }
    }
}
