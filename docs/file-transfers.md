# File transfers

Implementation branch: `feat/file-transfers`.

The [shared mobd contract](../../mobd/docs/file-transfers.md) defines the remote directory,
tus 1.0 uploads, authenticated Range downloads, completion/checksums and acceptance cases.

Add Files navigation for the active machine. Android's document picker imports ordinary files;
private disk staging avoids depending on a document provider after a restart. tus-java-client
handles uploads. Persist task/upload URLs without credentials. Use authenticated HTTP ranges
for downloads and verify SHA-256 before exporting with CreateDocument.

Transfers pause when backgrounded and can continue when the app returns. A transfer foreground
service, folder sync, remote deletion and agent prompt changes are outside this release.
