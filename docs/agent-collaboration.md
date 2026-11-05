# Agent collaboration on Android

Status: architecture COLLAB-1, 2026-11-02. Implemented on main; see the status section below.

The shared [Mob vocabulary](https://github.com/xrdavies/mobd/blob/ddd731dfa64c0070345d423d9ff6add507d877cd/docs/agent-concepts.md) and
[mobd architecture](https://github.com/xrdavies/mobd/blob/ddd731dfa64c0070345d423d9ff6add507d877cd/docs/agent-collaboration.md)
define the overall design. Android provides the interaction and displays the
collaboration saved by mobd.

## User flow

1. In agent A's chat, select agent B by its handle (`@api`) or by title in the
   `@` picker, and tell A what to
   collaborate on. Each existing conversation is an agent; Codex, Claude Code
   and Pi are the framework choices used to run it.
2. A sends a focused request. Show who contacted whom, what was requested and
   whether it is waiting, being handled, answered or needs user attention.
3. B may ask A for clarification. mobd delivers the exchange and resumes A
   after B's result so A can continue the user's task.
4. Let the user open B's conversation, inspect the exchange and stop or cancel
   the relevant work. Preserve the draft and reading position when navigating.

The handle names the agent; the title describes its work. Show the handle as
a badge next to the title, and lead with it in collaboration views. Archived
agents appear greyed out in the picker and cannot be selected; their row offers
Unarchive or moving the handle to this conversation (mobd's
[handles](https://github.com/xrdavies/mobd/blob/main/docs/agent-collaboration-design.md#handles)).
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

## Status: Android (feat/agent-collab-core)

Matches Desktop's collaboration design. Checked on an emulator (API 37) against the office mobd
running `feat/agent-collab-core`, 2026-11-05.

- **Handles:** the badge shows next to the title; the chat menu's "Agent handle" opens a dialog with a
  fixed `@`, input kept to allowed characters, the rule broken as you type, a suggestion chip, and
  Remove apart from Save. A handle another conversation holds is moved only after the dialog says
  whose it is ("Move @heron here").
- **@ picker:** typing `@` lists "Agents" (with a handle) first, then "Conversations", without the open
  one. Picking a conversation without a handle offers "Name it @off-road" or "Mention as @b9b040".
  Archived agents are greyed out, with Unarchive and "Give @handle here". Typed mentions are resolved
  on send and stop it when unknown or archived. Resolved mentions are highlighted while typing.
- **Who says what:** only your messages are on the right; mentions in them are tinted links that open
  the agent. Another agent's message is on the left with its own colour, name (opens its
  conversation), framework and what it did ("asks", "replied"), without mobd's instructions to the
  recipient. Collaboration steps read "Asked @heron: …", "Replied: …".
- **Pending requests:** a strip above the timeline; tapping one shows its text, thread and result, with
  Cancel. Ended requests can be dismissed.

Not verified on the emulator: naming an agent from the picker (Enter writes a handle), a
clarification, and a new exchange started from the phone.
