package com.xrdavies.mob.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.google.gson.Gson
import com.xrdavies.mob.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferStore(context: Context, private val scope: CoroutineScope, private val unauthorized: (String, String?) -> Unit) {
    private val context = context.applicationContext
    private val root = File(context.noBackupFilesDir, "file-transfers").apply { mkdirs() }
    private val index = AtomicFile(File(root, "tasks.json"))
    private val gson = Gson()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<FileTransfer>> = _tasks
    private val _listing = MutableStateFlow<FileListing?>(null)
    val listing: StateFlow<FileListing?> = _listing
    /** The open folder in mobd's shared folder; "" is the shared folder itself. */
    private val _path = MutableStateFlow("")
    val path: StateFlow<String> = _path
    private val parallel = 2
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing
    private var api: ApiClient? = null
    private var machine = ""
    var foreground = true
    private data class Work(val job: Job, val client: FileTransferClient)
    private val running = ConcurrentHashMap<String, Work>()

    private fun load(): List<FileTransfer> = try {
        if (!index.baseFile.exists()) emptyList() else {
            gson.fromJson(index.openRead().bufferedReader().use { it.readText() }, Array<FileTransfer>::class.java)
                .filter { runCatching { UUID.fromString(it.id) }.isSuccess }
                .map { if (it.state in listOf("running", "queued", "ready")) it.copy(state = "paused") else it }
        }
    } catch (e: Exception) { _error.value = "Could not restore transfers: ${e.readable()}"; emptyList() }

    @Synchronized private fun save(tasks: List<FileTransfer>) {
        val output = index.startWrite()
        try { output.write(gson.toJson(tasks).toByteArray()); index.finishWrite(output) }
        catch (e: Exception) { index.failWrite(output); throw e }
        _tasks.value = tasks
    }
    @Synchronized private fun change(id: String, persist: Boolean = true, edit: (FileTransfer) -> FileTransfer) {
        val tasks = _tasks.value.map { if (it.id == id) edit(it) else it }
        if (persist) save(tasks) else _tasks.value = tasks
    }
    @Synchronized private fun add(task: FileTransfer) { save(_tasks.value + task) }
    @Synchronized private fun remove(id: String) { save(_tasks.value.filterNot { it.id == id }) }
    private fun folder(id: String) = File(root, id)
    private fun payload(id: String) = File(folder(id), "payload")

    fun attach(api: ApiClient?, machine: String) { pauseAll(); this.api = api; this.machine = machine; _listing.value = null; _path.value = "" }
    fun report(error: Throwable) { _error.value = error.readable() }
    fun pause(id: String) {
        val work = running[id]
        if (work != null) { work.client.cancel(); work.job.cancel() }
        else runCatching { change(id) { if (it.state == "queued") it.copy(state = "paused") else it } }.onFailure(::report)
    }
    fun pauseAll() { _tasks.value.filter { it.state == "queued" }.forEach { pause(it.id) }; running.keys.toList().forEach(::pause) }

    fun open(path: String) { _path.value = path; _listing.value = null; refresh() }

    fun refresh() {
        val api = api ?: return
        val machine = machine
        scope.launch {
            try {
                val path = _path.value
                val listing = withContext(Dispatchers.IO) { FileTransferClient(api).list(path) }
                if (this@FileTransferStore.api === api && _path.value == path) { _listing.value = listing; _error.value = null }
            } catch (e: Exception) {
                if (this@FileTransferStore.api === api) report(e)
                if (e.isUnauthorized()) unauthorized(machine, api.token)
            }
        }
    }

    /** Uploads the picked documents into the open folder. */
    fun importFiles(uris: List<Uri>) = uris.forEach(::importFile)

    fun importFile(uri: Uri) {
        val api = api ?: return
        val machine = machine
        val dir = _path.value
        val id = UUID.randomUUID().toString()
        _preparing.value = true
        scope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "upload.bin"
                    if (!folder(id).mkdirs()) throw IOException("Could not create upload storage")
                    val file = payload(id)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var size = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                size += n
                                if (size > 20L * 1024 * 1024 * 1024) throw IOException("Maximum upload size is 20 GiB")
                                output.write(buffer, 0, n)
                            }
                        }
                    } ?: throw IOException("Cannot read the selected document")
                    FileTransfer(id, machine, api.baseUrl.toString(), true, name, file.length(), fileDigest(file), remotePath = joinRemote(dir, name)).also(::add)
                }
                if (this@FileTransferStore.api === api) start(task.id)
            } catch (e: Exception) {
                if (_tasks.value.none { it.id == id }) withContext(NonCancellable + Dispatchers.IO) { folder(id).deleteRecursively() }
                report(e)
            }
            finally { _preparing.value = false }
        }
    }

    fun download(file: RemoteFile) {
        val api = api ?: return
        val task = FileTransfer(UUID.randomUUID().toString(), machine, api.baseUrl.toString(), false, file.name, file.size, "", remotePath = joinRemote(_path.value, file.name))
        try {
            if (!folder(task.id).mkdirs() || !payload(task.id).createNewFile()) throw IOException("Could not create download storage")
            add(task); start(task.id)
        } catch (e: Exception) { report(e) }
    }

    /** Queues a transfer; at most two run at a time. */
    fun start(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!foreground || running.containsKey(id) || task.state == "done") return
        runCatching { change(id) { it.copy(state = "queued", message = null) } }.onFailure { report(it); return }
        pump()
    }

    @Synchronized private fun pump() {
        val api = api ?: return
        if (!foreground) return
        for (task in _tasks.value) {
            if (running.size >= parallel) return
            if (task.state == "queued" && !running.containsKey(task.id) && task.machine == machine && task.base == api.baseUrl.toString()) run(task, api)
        }
    }

    private fun run(task: FileTransfer, api: ApiClient) {
        val id = task.id
        val client = FileTransferClient(api)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    change(id) { it.copy(state = "running", message = null) }
                    if (task.upload) {
                        val receipt = client.upload(task, payload(id), { url -> change(id) { it.copy(uploadUrl = url) } }, { bytes -> change(id, false) { it.copy(bytes = bytes) } })
                        change(id) { it.copy(state = "done", remotePath = receipt.name, name = receipt.name.substringAfterLast('/'), bytes = receipt.size, message = null, finishedAt = System.currentTimeMillis()) }
                        if (!folder(id).deleteRecursively()) throw IOException("Uploaded; local cache could not be removed")
                    } else {
                        client.download(task, payload(id), { size, etag, sha -> change(id) { it.copy(size = size, etag = etag, sha256 = sha) } }, { bytes -> change(id, false) { it.copy(bytes = bytes) } })
                        ensureActive()
                        val saved = placeDownload(payload(id), task.name)
                        folder(id).deleteRecursively()
                        change(id) { it.copy(state = "done", localUri = saved, message = null, finishedAt = System.currentTimeMillis()) }
                    }
                }
                if (task.upload) refresh()
            } catch (e: Exception) {
                val paused = !isActive
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { change(id) { if (it.state == "done") it.copy(message = e.readable()) else it.copy(state = if (paused) "paused" else "failed", message = if (paused) null else e.readable()) } }.onFailure(::report)
                }
                if (!paused && e.isUnauthorized()) unauthorized(task.machine, api.token)
            } finally { running.remove(id); pump() }
        }
        running[id] = Work(job, client)
        job.start()
    }

    fun cancel(task: FileTransfer) {
        val api = api ?: return
        if (task.machine != machine || task.base != api.baseUrl.toString()) return
        scope.launch {
            try {
                val work = running[task.id]; work?.client?.cancel(); work?.job?.cancelAndJoin()
                withContext(Dispatchers.IO) {
                    val latest = _tasks.value.firstOrNull { it.id == task.id } ?: return@withContext
                    if (latest.upload && latest.state != "done" && latest.uploadUrl != null) FileTransferClient(api).removeUpload(latest.uploadUrl)
                    if (folder(task.id).exists() && !folder(task.id).deleteRecursively()) throw IOException("Could not remove transfer cache")
                    remove(task.id)
                }
            } catch (e: Exception) { report(e) }
        }
    }

    /** Removes a finished transfer from the list. */
    fun dismiss(id: String) {
        runCatching { if (_tasks.value.any { it.id == id && it.state == "done" }) remove(id) }.onFailure(::report)
    }

    fun rename(name: String, newName: String) = remote { it.rename(joinRemote(_path.value, name), newName) }
    fun delete(name: String) = remote { it.delete(joinRemote(_path.value, name)) }

    private fun remote(work: (FileTransferClient) -> Unit) {
        val api = api ?: return
        val machine = machine
        scope.launch {
            try { withContext(Dispatchers.IO) { work(FileTransferClient(api)) }; refresh() }
            catch (e: Exception) { report(e); if (e.isUnauthorized()) unauthorized(machine, api.token) }
        }
    }

    /** Saves a verified download in Downloads, never replacing a file (MediaStore adds " (1)").
     *  Before Android 10, the app's own Downloads folder, which needs no permission. */
    private fun placeDownload(source: File, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not save to Downloads")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } } ?: throw IOException("Could not save to Downloads")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            return uri.toString()
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("No storage for downloads")
        val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || '.' !in name) "" else ".$it" }
        for (n in 0 until 1000) {
            val target = File(dir, if (n == 0) name else "$stem ($n)$ext")
            if (target.createNewFile()) { source.copyTo(target, overwrite = true); return target.path }
        }
        throw IOException("Too many files with that name")
    }
}
