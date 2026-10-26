package com.xrdavies.mob.data

// Machine settings (GET/PUT /settings) and which model and effort the message box shows and
// sends (mobd docs/mobd-v3.md, "Machine and conversation settings"). "" means the default: the
// machine's for a new chat, the conversation's otherwise.

/** What the apps set for one agent; null or empty = not set here. */
data class AgentSettings(
    val models: List<String>? = null,
    val defaultModel: String? = null,
    val defaultEffort: String? = null,
    val permissions: String? = null,
) {
    val isEmpty get() = models.isNullOrEmpty() && defaultModel.isNullOrEmpty() && defaultEffort.isNullOrEmpty() && permissions.isNullOrEmpty()
}

data class MachineSettings(val rev: Long, val defaultAgent: String? = null, val agents: Map<String, AgentSettings>? = null)

/** What an agent uses now, and where each value comes from (app, config, agent, builtin). */
data class EffectiveAgent(
    val models: List<String>?,
    val modelsFrom: String?,
    val defaultModel: String?,
    val defaultModelFrom: String?,
    val defaultEffort: String?,
    val defaultEffortFrom: String?,
    val permissions: String?,
    val permissionsFrom: String?,
    val efforts: List<String>?,
    /** What applies without the app settings: what clearing a value returns to. */
    val fallback: EffectiveAgent? = null,
)

data class SettingsInfo(
    val settings: MachineSettings,
    val defaultAgent: String?,
    val defaultAgentFrom: String?,
    val fallbackDefaultAgent: String?,
    val agents: Map<String, EffectiveAgent>?,
)

/** An error body's machine-readable reason. */
data class ErrorCodeBody(val error: String?, val code: String?)

data class TurnChoice(val model: String = "", val effort: String = "")

object TurnSettings {
    /** With mobd settings an open chat shows its conversation's values; a new chat its draft. */
    fun shown(settings: Boolean, session: Session?, draftModel: String?, draftEffort: String?): TurnChoice =
        if (settings && session != null) TurnChoice(session.model.orEmpty(), session.effort.orEmpty())
        else TurnChoice(draftModel.orEmpty(), draftEffort.orEmpty())

    /** The models offered: the agent's list, plus a value in use that is no longer listed. */
    fun menuModels(models: List<String>, current: String): List<String> =
        if (current.isNotEmpty() && current !in models) models + current else models

    /** Only a new chat's draft is sent; an older mobd gets the choice on every turn. */
    fun sent(settings: Boolean, newChat: Boolean, shown: TurnChoice, efforts: List<String>): Pair<String?, String?> =
        if (settings && !newChat) null to null
        else shown.model.ifEmpty { null } to shown.effort.takeIf { it in efforts }

    /** Applies a change to one agent; empty values mean "not set here". */
    fun with(s: MachineSettings, agent: String, change: (AgentSettings) -> AgentSettings): MachineSettings {
        val a = change(s.agents?.get(agent) ?: AgentSettings()).let {
            it.copy(
                models = it.models?.takeIf { m -> m.isNotEmpty() },
                defaultModel = it.defaultModel?.ifEmpty { null },
                defaultEffort = it.defaultEffort?.ifEmpty { null },
                permissions = it.permissions?.ifEmpty { null },
            )
        }
        val agents = s.agents.orEmpty().toMutableMap()
        if (a.isEmpty) agents.remove(agent) else agents[agent] = a
        return s.copy(agents = agents)
    }

    private val modelName = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")

    /** Adds a typed model ID once; null when it is not a model name. */
    fun addModel(models: List<String>, raw: String): List<String>? {
        val m = raw.trim()
        if (!modelName.matches(m)) return null
        return if (m in models) models else models + m
    }

    /** Agents a save would let run without asking that ask today. */
    fun newlyUnguarded(info: SettingsInfo, next: MachineSettings): List<String> =
        info.agents.orEmpty().filter { (name, now) ->
            val after = next.agents?.get(name)?.permissions ?: (now.fallback?.permissions ?: now.permissions)
            now.permissions == "approve" && after == "bypass"
        }.keys.sorted()

    fun fromLabel(from: String?): String = when (from) {
        "app" -> "set in Mob"
        "config" -> "from mobd config"
        "agent" -> "from the agent's own config"
        "builtin", null -> "agent's built-in default"
        else -> from
    }
}
