# USB redirection: Android scope

Status: draft USB-1, 2026-11-01. Not implemented or released.

The [mobd USB design](https://github.com/xrdavies/mobd/blob/main/docs/usb-redirection.md)
is the canonical contract. Mob Desktop exports a selected local USB device and
mobd imports it, using USB/IP over an authenticated, encrypted reverse tunnel
and open-source implementations only. The first receiver is Linux; Windows and
macOS receivers are later, independently verified milestones.

## Role in the first delivery

Android does not export phone/tablet USB devices, import virtual USB devices, or
relay Desktop's USB traffic. USB host/OTG availability alone does not establish
full transparent forwarding. Investigate mobile device access, permissions and
background restrictions separately if that feature is requested later.

Do not add a USB permission flow, native driver, Share USB button or foreground
service for this Desktop-only scope. In particular, do not reuse the notification
watcher as a device-forwarding service.

The app can continue chatting with an agent on a machine using hardware shared
from Desktop, subject to the remote OS driver and permissions. This does not
transfer USB-session ownership to Android or isolate hardware to a chat.
Switching chats/machines, closing the live view, backgrounding or disconnecting
this phone must not terminate Desktop's share. Only the originating Desktop
client operates it through the USB API; remote local administrator recovery is
specified in the shared contract.

## Compatibility and optional later status

The proposed `usbRedirection` health feature means server contract support, not
mobile USB support or readiness of every device. Ignore unknown features and
keep chat/files/live view usable with old and new mobd. No USB API integration
or new models are necessary for the first delivery.

A later status UI must use the canonical states `connecting`, `connected`,
`disconnecting`, `disconnected`, `failed` and the server's authorization policy.
Reading or managing another client's devices needs a separately specified
permission; it is not automatically authorized by pairing. Do not add mobile
management in this draft.

Selecting a document on a USB drive through Android's document provider remains
ordinary [file transfer](file-transfers.md), subject to provider permissions.
It does not expose the device to the remote OS, and USB I/O cannot inherit tus
resume semantics.

## Apple application and validation

[mobd's Apple runbook](https://github.com/xrdavies/mobd/blob/main/docs/apple-usb-entitlement.md)
records `com.apple.developer.usb.host-controller-interface` for the receiving
macOS importer, with official references, application entry point and materials.
It is unrelated to Android USB permission, and Developer ID signing alone does
not grant it. No application is submitted by this documentation change.

When Desktop support lands, verify old/new-server compatibility and that Android
view/background transitions do not interfere with an active Desktop share. If
status is added later, use the existing `data/` API models and appropriate UI
state tests; no manifest, native dependency or runtime change is needed now.
