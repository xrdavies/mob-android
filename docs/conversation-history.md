# Conversation history: local copy and paging (Android)

Status: planned on `feat/conversation-history`

Mob Mobile for Android keeps a local copy of each conversation and loads older turns as you
scroll. The protocol is in
[mobd](https://github.com/xrdavies/mobd/blob/main/docs/conversation-history.md):
`GET /sessions/{id}/history` for pages and bounded changes, `seq` for turn order, `sessionHistory`
in `/health`. Without that feature the app keeps today's full-snapshot request.

## Local copy

SQLite through the platform's `SQLiteOpenHelper` (no new dependency), in `noBackupFilesDir`, so it
is private to the app and not backed up. Tables:

- `turns`: one row per turn (machine, conversation, task ID, `seq`, `rev`, size) holding the JSON
  of the turn with its messages, steps and questions;
- `conversations`: the session row, `syncRev`, oldest `seq` held, whether older turns exist, last
  opened time and stored size;
- `meta`: the schema version.

Rows are keyed by the machine's `serverId` from pairing (the local machine ID when absent). A
corrupt file or a different schema version is deleted and rebuilt from mobd.

`ChatStore` writes on `Dispatchers.IO` through one queue: when a turn finishes, and at most every
5 seconds while one runs, never for each streamed event. It replaces today's eight-conversation
memory cache.

## Opening a conversation

1. Show the saved turns at once, if any.
2. Sync: with a saved copy, `since=<syncRev>`; without one, the newest page. A `reset` answer drops
   the saved turns and loads the newest page. Polling and stream recovery use the same request.
3. Scrolling near the top of the `LazyColumn` loads the page before the oldest held turn.

Merging is by task ID, keeping the higher `rev` (the `Task` model gains `seq` and `rev`). Only
turns from the oldest held `seq` up are shown; a changed turn older than that is stored but not
added to the end, so it can never become `current`.

## Sync status

| State | Shown |
|---|---|
| Opening a saved chat, returning to the app, reconnecting | Spinner and **Syncing…** under the title |
| Up to date | Nothing |
| mobd unreachable | **Offline · showing saved copy** |
| Sync failed while online | **Couldn't sync · Retry** |
| Loading older turns | Progress indicator at the top |
| Older turns not saved, offline | **Older messages aren't available offline** |

The status appears only after about 300 ms, and routine polls show nothing unless they fail; a
running turn keeps its live status. Reading and sending continue while syncing. Offline, saved
conversations can be read; sending is disabled and images not cached show a placeholder.

## Timeline

Items keep their stable keys, so the `LazyColumn` holds the first visible item when older turns
are prepended; verify this and restore the index and offset explicitly if it does not. A page
load must not scroll to the bottom, clear unseen output or start a second request; an error leaves
the cursor unchanged with Retry.

## Storage limit

At most 500 MB. Above it, the app removes the saved turns of the least recently opened
conversation, then the next, until it is under about 450 MB. A single conversation over the limit
keeps its newest turns and loses its oldest. The open conversation and any with a running turn are
never trimmed (the open one is trimmed when left). Conversations stay in the chat list.

Settings shows the space used and **Clear local copy**.

## Removal

- `session.deleted`, a `404`, or a conversation missing from the list after being offline:
  delete it locally.
- Removing a machine, or mobd revoking this device: delete that machine's copy.

## Verification

Unit tests: decoding, first and older pages, duplicate task IDs, an old turn changed after paging
is not appended, `reset`, full-snapshot fallback, eviction order, trimming, a corrupt database
rebuilt. On the emulator against a local mobd: relaunch shows the saved copy before the network
answers; scrolling up loads older turns without moving the visible one.
