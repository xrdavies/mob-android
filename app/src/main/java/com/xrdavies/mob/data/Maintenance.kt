package com.xrdavies.mob.data

// mobd's remote maintenance (mobd docs/remote-maintenance.md, feature "maintenance"): config.json's
// editable keys, and restart and upgrade as operations. Applying needs an idle computer (D6).

/** GET /config. Only the keys the phone changes are decoded; mobd sends more. */
data class ConfigView(
    val rev: String,
    /** config.json's values: the agents' sections, shown while a restart is pending. */
    val values: Values? = null,
    val effective: Effective?,
    val editable: List<String>?,
    val overridden: List<String>?,
    val pendingRestart: Boolean?,
    val canRestart: Boolean?,
    val configError: String?,
    val lastRejected: Rejected?,
) {
    data class Effective(val runners: List<String>?)
    data class Values(val agents: Map<String, Map<String, Any?>>?)
    data class Rejected(val at: String?, val reason: String)

    /** The key can be changed from here: editable, and not set by a flag or the environment. */
    fun canEdit(key: String) = editable.orEmpty().contains(key) && !overridden.orEmpty().contains(key)
}

/** A restart or upgrade, read back with GET /maintenance/{id} after mobd comes back. */
data class MaintenanceOp(
    val id: String,
    val action: String,
    val state: String,
    val step: String? = null,
    val error: String? = null,
    val code: String? = null,
    val version: String? = null,
) {
    val running get() = state == "running"
    val failed get() = state == "failed" || state == "rolled-back"
}

/** GET /maintenance/latest, as `mobd upgrade --check --json`. */
data class ReleaseCheck(val current: String, val latest: String, val newer: Boolean, val notes: String?, val canUpgrade: Boolean, val reason: String?)

data class MaintenanceRequest(val action: String, val version: String? = null)
data class MaintenanceStarted(val operationId: String)
data class ConfigChange(val rev: String, val changes: Map<String, Any?>)

object Maintenance {
    private val steps = mapOf("preparing" to "Preparing", "checking" to "Checking", "swapping" to "Installing", "restarting" to "Restarting", "rolling back" to "Going back")

    /** What an operation is doing or how it ended, in the words the apps use. */
    fun text(op: MaintenanceOp): String = when (op.state) {
        "running" -> "${steps[op.step] ?: "Working"}…"
        "done" -> if (op.action == "upgrade") "Updated${op.version?.let { " to $it" } ?: ""}" else "Restarted"
        "rolled-back" -> "Went back to the previous ${if (op.action == "upgrade") "version" else "settings"}${op.error?.let { ": $it" } ?: ""}"
        else -> op.error ?: "Failed"
    }

    /** Running and queued tasks: restart and update wait until there are none. */
    fun busy(q: Queue?) = q?.running.orEmpty().size + q?.queued.orEmpty().size

    fun tasksText(n: Int) = "$n task${if (n == 1) "" else "s"} running"

    /** The enabled agents with one turned on or off; null when it would leave none. */
    fun runners(current: List<String>, name: String, on: Boolean): List<String>? {
        val next = if (on) (if (name in current) current else current + name) else current - name
        return next.ifEmpty { null }
    }
}
