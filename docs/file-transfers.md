# File transfers

The [shared mobd contract](https://github.com/xrdavies/mobd/blob/feat/file-transfers/docs/file-transfers.md) defines the remote directory,
tus 1.0 uploads, authenticated Range downloads, completion/checksums and acceptance cases.

The Files tab lists the office computer's shared folder (`~/mob-files`): folders first, opened by
tapping, never above the shared folder. Tapping a file downloads it and, once its SHA-256 is
verified, saves it straight to Downloads through MediaStore (Android 10+; it never replaces a file,
MediaStore adds " (1)"); before Android 10, to the app's own Downloads folder. The finished row
offers Open. **+** uploads one or more documents into the open folder; private disk staging avoids
depending on a document provider after a restart, and tus-java-client handles uploads. Long-press
or ⋮: Download, Rename and Delete (a folder with everything in it, confirmed), with mobd's
`fileFolders`. Whole folders are not transferred on the phone. At most two transfers run at once,
shown at the top. Persist task/upload URLs without credentials.

Transfers pause when backgrounded and can continue when the app returns. A transfer foreground
service, folder sync, remote deletion and agent prompt changes are outside this release.

## Verification

`./gradlew :app:testDebugUnitTest` covers URL/range validation. Set `MOB_FILE_TEST_FIXTURE` to the isolated fixture JSON for the JVM SDK integration test. `FileTransferDeviceTest` runs the same protocol on Android with instrumentation arguments `fileTestUrl` and `fileTestToken`; use `10.0.2.2` in place of `127.0.0.1` for the emulator.

See the [shared verification record](https://github.com/xrdavies/mobd/blob/feat/file-transfers/docs/file-transfer-verification.md) for coverage and limits.
