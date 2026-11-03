# Agent collaboration on Android

Status: architecture draft COLLAB-1, 2026-11-02. Not implemented or released.

The shared [Mob vocabulary](https://github.com/xrdavies/mobd/blob/ddd731dfa64c0070345d423d9ff6add507d877cd/docs/agent-concepts.md) and
[mobd architecture](https://github.com/xrdavies/mobd/blob/ddd731dfa64c0070345d423d9ff6add507d877cd/docs/agent-collaboration.md)
define the overall design. Android provides the interaction and displays the
collaboration saved by mobd.

## User flow

1. In agent A's chat, select agent B by `@name` or short ID and tell A what to
   collaborate on. Each existing conversation is an agent; Codex, Claude Code
   and Pi are the framework choices used to run it.
2. A sends a focused request. Show who contacted whom, what was requested and
   whether it is waiting, being handled, answered or needs user attention.
3. B may ask A for clarification. mobd delivers the exchange and resumes A
   after B's result so A can continue the user's task.
4. Let the user open B's conversation, inspect the exchange and stop or cancel
   the relevant work. Preserve the draft and reading position when navigating.

Mention selection identifies a specific session. Duplicate names need explicit
selection, and renaming an agent preserves that identity. Selecting a mention
alone does not send work to another agent.

## Android responsibilities

Use the existing composer and chat views, with accessible mention suggestions
and request details. Keep a compact pending-collaboration view available even
in a very long conversation; load exchange details when the user opens them.
Preserve the selected conversation and draft through normal UI recreation.

mobd owns message delivery, execution and continuation. Collaboration continues
when Android backgrounds or closes the app. On reopening, the app refreshes
server state; its local cache supports reading and does not schedule agent work.
This design does not require a new always-running Android service.

## Assessment and scope

The architecture fits the existing app/server split and needs no separate
Android agent engine. Desktop, iOS and Android must present the same
collaboration behavior, with interaction adapted to each platform. First
delivery covers existing agents on one mobd.

Validate the A/B exchange, user control and reopening after disconnection first.
UI component details, API models and cache changes follow after the shared
implementation contract is defined.
