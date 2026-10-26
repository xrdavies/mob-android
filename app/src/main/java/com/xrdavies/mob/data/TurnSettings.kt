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
data class SettingsInfo(val settings: MachineSettings, val agents: Map<String, AgentInfo>?) {
    data class AgentInfo(val label: String?, val efforts: List<String>?, val agentDefaultModel: String?, val agentDefaultEffort: String?)
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
