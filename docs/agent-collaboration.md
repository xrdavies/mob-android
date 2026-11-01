# Agent collaboration on Android

Status: draft COLLAB-1, 2026-11-02. Not implemented or released.

The pinned [Mob vocabulary](https://github.com/xrdavies/mobd/blob/7db3f83c218bcf3ac7c9a3b75bbae5c3929f6c28/docs/agent-concepts.md) and
[mobd collaboration contract](https://github.com/xrdavies/mobd/blob/7db3f83c218bcf3ac7c9a3b75bbae5c3929f6c28/docs/agent-collaboration.md)
own identity, message/request semantics, scheduling and recovery. This companion
defines Android behavior against that revision. Update the companion plans
together when shared behavior changes.

## References in the composer

Each conversation is one agent. Codex, Claude Code and Pi are **Agent frameworks**
in the new-chat picker; the `@` picker selects a session from the proposed
agent directory, not the framework readiness list in `/health.agents`.

Extend [Composer](../app/src/main/java/com/xrdavies/mob/ui/chat/Composer.kt)
with a bounded searchable suggestion surface and selected reference chips.
Show the agent name, unambiguous short ID, framework and busy/archive state.
Support name and ID queries, TalkBack, large fonts, hardware keyboard and IME
composition. Selecting an item must not also submit the draft.

Persist structured draft references as `{serverId, sessionId, displayName}`,
scoped to machine and conversation. Use the pairing server identity, never an
array index or current turn ID. A rename preserves the target; ambiguity needs
selection. Pasted code, quoted `@` text and email addresses have no dispatch
effect. A selected reference identifies a peer; the current agent sends a
focused message only when the user's instruction calls for collaboration.

Keep draft state outside a transient suggestion popup and preserve it through
recomposition and configuration changes. Save the small text/reference state
with the existing screen/view-model lifecycle as appropriate; do not claim the
current attachment or draft implementation already survives process death.
Any restored draft remains unsent.

Include `agentRefs` in user turn creation and supported user steering. Preserve
text, chips and attachments on rejection or missing reference support. Clear
only the accepted submitted draft version. After a lost submission response,
reconcile current history before retry; no automatic resend on reconnect.
Cancel stale search results when the query, session or machine changes.

## Timeline, details and actions

Extend [ChatScreen](../app/src/main/java/com/xrdavies/mob/ui/chat/ChatScreen.kt)
and existing timeline cards with attributed incoming/outgoing messages and a
request link. Distinguish peer content from user instructions using server
provenance; the framework's input role alone is insufficient.

Show a compact pending summary independently of loaded history turns. It opens
bounded request details in a sheet or detail view, retaining the source draft
and scroll position. A linked agent opens its existing conversation. A deleted
or archived agent remains an unavailable/archived reference; never redirect to
another with the same name.

| Server request state | Label |
| --- | --- |
| `open` | Queued / Processing / Waiting for clarification / Waiting for collaborator, derived from assignment/question state |
| `answered` | Answered |
| `needs_attention` | Needs attention |
| `failed` | Failed |
| `cancelled` | Cancelled |
| `expired` | Expired |

A proposed final result is **Result recorded; finishing** until the handling
turn exits normally. A receipt is not an answer; an answered request does not
certify completion of the user's overall task. Notices/progress do not
independently wake a model.

Provide distinct **Stop current turn**, **Cancel collaboration** and **Pause
automatic continuation** controls with the canonical scope. Resumption is
explicit and uses an expected revision. Never cancel the recipient's unrelated
user work. Show partial/late results without restarting cancelled/expired work.

## Networking and persistence

Gate controls on `agentCollaboration` in `/health.features`. Old-server chat,
history and live view continue unchanged. Offline requests are a labelled saved
copy; preserve drafts, but sending/cancel/resume require a live connection.
Cached capability or inbox data must not schedule work when the app launches.

Extend [Models](../app/src/main/java/com/xrdavies/mob/data/Models.kt),
[ApiClient](../app/src/main/java/com/xrdavies/mob/data/ApiClient.kt) and
[ChatStore](../app/src/main/java/com/xrdavies/mob/viewmodel/ChatStore.kt)
for directory queries, bounded snapshots/changes, request detail and actions.
Use the same routes, limits, state names and fixtures as the other clients.

Treat SSE as invalidation. On reconnect, event gaps or `onStart`, fetch bounded
authoritative collaboration state. Its `collaborationRev` is independent of
history `syncRev`. Complete snapshot pagination before establishing a baseline;
failed reads keep the old one. Apply reset/tombstones explicitly and discard
stale responses from a previous selected machine.

Extend [HistoryStore](../app/src/main/java/com/xrdavies/mob/data/History.kt) and
[SqliteHistory](../app/src/main/java/com/xrdavies/mob/data/SqliteHistory.kt)
on the existing serialized IO path. Preserve source metadata in turn payloads,
and cache request summaries/detail pages independently by server ID, stable
record ID and revision. Keep SQL compatible with Android 8. A schema rebuild
resyncs from mobd and cannot replay submissions.

An old turn update replaces its saved version without becoming current or
moving to the end of the `LazyColumn`. Use stable request/message keys and
preserve the visible anchor when expanding detail or loading older messages.
Pending summaries survive old-turn eviction; detail bodies are bounded and
refetchable. Follow [history cleanup](conversation-history.md) for device-local
clearing, server removal and revocation; none cancels server work.

## Background behavior and acceptance

The daemon owns progress while the app is stopped, killed or offline. Existing
`MachineWatcher` and optional foreground watching may surface notifications
while connected; notification permission affects notification display, not
collaboration execution. Do not require a new always-running Android service
or promise reliable background sockets. Reopening recovers from server state.
Opening an agent link does not acquire desktop Control mode.

Implement models/API and cache first, then the composer, pending summary and
request cards. Use JVM fake-server/state tests plus focused Compose/instrumented
checks for:

- Ambiguous names/IDs, rename/delete, wrong server identity, stale searches and
  paired-address switching.
- TalkBack, font size, keyboard/IME, rotation and saved draft/reference state;
  failed or uncertain sends and the supported user-steer path.
- One-slot request/clarification/result flow and a busy peer; no fake running
  state while waiting, and no premature completion from a proposed result.
- Duplicate SSE/HTTP updates, reconnect, process death, notifications disabled,
  offline reads and old-server fallback.
- Long chat with an old pending request, bounded detail pagination, cache eviction,
  schema rebuild/reset/tombstones and stable list position.
- Stop versus cancel versus continuation pause, late replies and resumption
  conflicts. Framework execution itself is covered by mobd's integration suite.

These are future acceptance checks; no runtime behavior or validated support
claim is added by this draft.
