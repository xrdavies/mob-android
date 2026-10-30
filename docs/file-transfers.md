# File transfers

Implementation branch: `feat/file-transfers`.

The [shared mobd contract](https://github.com/xrdavies/mobd/blob/feat/file-transfers/docs/file-transfers.md) defines the remote directory,
tus 1.0 uploads, authenticated Range downloads, completion/checksums and acceptance cases.

Files navigation uses for the active machine. Android's document picker imports ordinary files;
private disk staging avoids depending on a document provider after a restart. tus-java-client
handles uploads. Persist task/upload URLs without credentials. Use authenticated HTTP ranges
for downloads and verify SHA-256 before exporting with CreateDocument.

Transfers pause when backgrounded and can continue when the app returns. A transfer foreground
service, folder sync, remote deletion and agent prompt changes are outside this release.

## Verification

`./gradlew :app:testDebugUnitTest` covers URL/range validation. Set `MOB_FILE_TEST_FIXTURE` to the isolated fixture JSON for the JVM SDK integration test. `FileTransferDeviceTest` runs the same protocol on Android with instrumentation arguments `fileTestUrl` and `fileTestToken`; use `10.0.2.2` in place of `127.0.0.1` for the emulator.

See the [shared verification record](https://github.com/xrdavies/mobd/blob/feat/file-transfers/docs/file-transfer-verification.md) for coverage and limits.
