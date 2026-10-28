package com.xrdavies.mob.viewmodel

import android.content.Context
import android.net.Uri
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
                .map { if (it.state == "running") it.copy(state = "paused") else it }
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

    fun attach(api: ApiClient?, machine: String) { pauseAll(); this.api = api; this.machine = machine; _listing.value = null }
    fun report(error: Throwable) { _error.value = error.readable() }
    fun pause(id: String) { running[id]?.let { it.client.cancel(); it.job.cancel() } }
    fun pauseAll() { running.keys.toList().forEach(::pause) }

    fun refresh() {
        val api = api ?: return
        val machine = machine
        scope.launch {
            try {
                val listing = withContext(Dispatchers.IO) { FileTransferClient(api).list() }
                if (this@FileTransferStore.api === api) { _listing.value = listing; _error.value = null }
            } catch (e: Exception) {
                if (this@FileTransferStore.api === api) report(e)
                if (e.isUnauthorized()) unauthorized(machine, api.token)
            }
        }
    }

    fun importFile(uri: Uri) {
        val api = api ?: return
        val machine = machine
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
                    FileTransfer(id, machine, api.baseUrl.toString(), true, name, file.length(), fileDigest(file)).also(::add)
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
        val task = FileTransfer(UUID.randomUUID().toString(), machine, api.baseUrl.toString(), false, file.name, file.size, "")
        try {
            if (!folder(task.id).mkdirs() || !payload(task.id).createNewFile()) throw IOException("Could not create download storage")
            add(task); start(task.id)
        } catch (e: Exception) { report(e) }
    }

    fun start(id: String) {
        val api = api ?: return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!foreground || running.containsKey(id) || task.machine != machine || task.base != api.baseUrl.toString() || task.state in listOf("ready", "done")) return
        val client = FileTransferClient(api)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    change(id) { it.copy(state = "running", message = null) }
                    if (task.upload) {
                        val receipt = client.upload(task, payload(id), { url -> change(id) { it.copy(uploadUrl = url) } }, { bytes -> change(id, false) { it.copy(bytes = bytes) } })
                        change(id) { it.copy(state = "done", name = receipt.name, bytes = receipt.size, message = null) }
                        if (!folder(id).deleteRecursively()) throw IOException("Uploaded; local cache could not be removed")
                    } else {
                        client.download(task, payload(id), { size, etag, sha -> change(id) { it.copy(size = size, etag = etag, sha256 = sha) } }, { bytes -> change(id, false) { it.copy(bytes = bytes) } })
                        ensureActive()
                        change(id) { it.copy(state = "ready", message = null) }
                    }
                }
                if (task.upload) refresh()
            } catch (e: Exception) {
                val paused = !isActive
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { change(id) { if (it.state == "done") it.copy(message = e.readable()) else it.copy(state = if (paused) "paused" else "failed", message = if (paused) null else e.readable()) } }.onFailure(::report)
                }
                if (!paused && e.isUnauthorized()) unauthorized(task.machine, api.token)
            } finally { running.remove(id) }
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

    fun export(task: FileTransfer, uri: Uri) {
        if (task.state != "ready") return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output -> payload(task.id).inputStream().use { it.copyTo(output, 256 * 1024) } }
                        ?: throw IOException("Cannot save to the selected location")
                    change(task.id) { it.copy(state = "done") }
                    if (!folder(task.id).deleteRecursively()) throw IOException("Saved; local cache could not be removed")
                }
            } catch (e: Exception) { report(e) }
        }
    }
}
