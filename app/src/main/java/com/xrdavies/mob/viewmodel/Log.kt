package com.xrdavies.mob.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** In-memory debug log (Settings → Log), like the desktop app's. Thread-safe. */
object Log {
    enum class Level { Info, Error }

    data class Entry(val at: LocalTime, val level: Level, val message: String) {
        override fun toString() = "${at.format(TIME)} ${if (level == Level.Error) "ERROR " else ""}$message"
    }

    private const val MAX = 500
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun add(message: String, level: Level = Level.Info) {
        _entries.value = (_entries.value + Entry(LocalTime.now(), level, message)).takeLast(MAX)
    }

    fun error(message: String) = add(message, Level.Error)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun text(): String = _entries.value.joinToString("\n")
}
