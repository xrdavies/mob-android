# Conversation history pagination and incremental sync

Status: draft

The Android client should adopt the mobd history-page contract while keeping
revision sync as the source of truth for live changes. See the shared protocol
draft in [mobd](https://github.com/xrdavies/mobd/blob/main/docs/conversation-history.md).

## API and state

Add a Retrofit API method for:

```text
GET /sessions/{id}/history?limit=50
GET /sessions/{id}/history?before=<cursor>&limit=50
```

`ChatStore` keeps `syncRev`, `historyBefore`, `historyHasMore`, and an
in-flight page guard for the selected session. The first open loads the newest
page and establishes `syncRev` from `snapshotRev`. Existing polling and SSE
recovery continue to call `getSession(id, syncRev)`; history-page responses do
not move that revision forward after the initial baseline.

Tasks remain the merge unit. Replace a returned task and its related messages,
activities and questions by ID. Keep revision updates for tasks outside the
loaded range in the local task map so a later page cannot overwrite newer data.
If `/health` lacks `sessionHistoryPaging`, use the current full-session path
for compatibility with older mobd versions.

## Compose timeline behavior

`Timeline` should request the next older page when the lazy list reaches its
top. Prepend the page while preserving the first visible item and its pixel
offset. Guard against duplicate requests, display a top loading indicator, and
leave the cursor unchanged on failure so retry is safe.

The existing bottom-follow behavior for live output remains independent. A
history load must not move the list to the bottom, clear unseen output, or
interfere with task event streaming. When a page is loaded after a revision
update, merge by task ID and rebuild the timeline from the canonical detail
state.

## Cache and lifecycle

The existing eight-session in-memory cache can retain loaded pages, cursors and
the revision baseline. Keep it bounded and disposable; process restarts start
from the newest page. Foreground recovery reconciles with `getSession(...,
syncRev)` before resuming normal polling.

## Verification

Add unit tests for Retrofit decoding, first-page and older-page merges, cursor
deduplication, an old-task update after paging, and full-snapshot fallback.
Add a Compose list test that prepends rows without changing the first visible
item, plus a reconnect test that repairs a missed update through `since`.
