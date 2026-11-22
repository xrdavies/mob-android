package com.xrdavies.mob.data

// settings.json (GET/PUT /settings) and which model and effort the message box shows and sends
// (mobd docs/mobd-v3.md, "Settings"). "" means the default from settings.json; an empty default
// sends none, so the agent's own applies.

/** One agent's settings in settings.json. */
data class AgentSettings(
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val defaultEffort: String = "",
    val permissions: String = "bypass",
)

/** settings.json: the runtime settings of agents and conversations, shared by every device. */
data class MachineSettings(val rev: Long, val defaultAgent: String, val agents: Map<String, AgentSettings>)

/** GET /settings: the file, and per agent its label, efforts and own defaults. */
data class SettingsInfo(val settings: MachineSettings, val agents: Map<String, AgentInfo>?, val installed: List<Installed>? = null) {
    /** agentModels: the models the agent itself reports (ACP agents), offered to add. */
    data class AgentInfo(
        val label: String?, val efforts: List<String>?, val agentDefaultModel: String?, val agentDefaultEffort: String?,
        val agentModels: List<String>? = null,
        /** false: the agent cannot ask before acting (it decides on its own); null (older mobd) means it can. */
        val approvals: Boolean? = null,
    )

    /** Whether a supported agent is set up on the machine, enabled or not. */
    data class Installed(
        val name: String, val label: String?, val state: String, val enabled: Boolean?, val detail: String?, val install: String?,
        /** "native" (mobd's own adapter) or "acp" (Agent Client Protocol). */
        val protocol: String? = null,
        /** Its config.json settings (feature agentOptions), drawn by AgentOptionRows. */
        val options: List<AgentOption>? = null,
    ) {
        /** ready, disabled (installed, not enabled), missing, error. */
        val stateLabel get() = when (state) {
            "disabled" -> "Installed, not enabled"
            "missing" -> "Not installed"
            "error" -> "Not working"
            else -> state
        }
    }
}

/**
 * One of an agent's config.json settings (agents.<name>.<key>), drawn by its type: bool, choice, path,
 * text or number. value is a String, Boolean or Double as Gson reads it.
 */
data class AgentOption(
    val key: String,
    val label: String,
    val help: String? = null,
    val type: String,
    val choices: List<Choice>? = null,
    val value: Any? = null,
    /** Applies when mobd restarts. */
    val restart: Boolean? = null,
    /** Set by a flag or environment variable on the computer: read-only. */
    val overridden: Boolean? = null,
) {
    data class Choice(val value: String, val label: String)

    companion object {
        /** An option value as text; whole numbers without ".0". */
        fun text(v: Any?): String = when (v) {
            null -> ""
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            is String, is Boolean -> v.toString()
            else -> ""
        }
    }
}

/** An error body's machine-readable reason. */
data class ErrorCodeBody(val error: String?, val code: String?)

data class TurnChoice(val model: String = "", val effort: String = "")

object TurnSettings {
    /** What the menus show: an open chat its conversation's values, a new chat this device's draft. */
    fun shown(session: Session?, draftModel: String?, draftEffort: String?): TurnChoice =
        if (session != null) TurnChoice(session.model.orEmpty(), session.effort.orEmpty())
        else TurnChoice(draftModel.orEmpty(), draftEffort.orEmpty())

    /** The models offered: the agent's list, plus a value in use that is no longer listed. */
    fun menuModels(models: List<String>, current: String): List<String> =
        if (current.isNotEmpty() && current !in models) models + current else models

    /** Applies a change to one agent's settings. */
    fun with(s: MachineSettings, agent: String, change: (AgentSettings) -> AgentSettings): MachineSettings =
        s.copy(agents = s.agents + (agent to change(s.agents[agent] ?: AgentSettings())))

    private val modelName = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")

    /** Adds a typed model ID once; null when it is not a model name. */
    fun addModel(models: List<String>, raw: String): List<String>? {
        val m = raw.trim()
        if (!modelName.matches(m)) return null
        return if (m in models) models else models + m
    }

    /** Agents a save would let run without asking that ask today (they need a confirmation). */
    fun newlyUnguarded(before: MachineSettings, after: MachineSettings): List<String> =
        before.agents.filter { (name, a) -> a.permissions == "approve" && after.agents[name]?.permissions == "bypass" }.keys.sorted()
}
