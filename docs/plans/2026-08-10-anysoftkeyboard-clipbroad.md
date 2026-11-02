# AnySoftKeyboard ClipBroad Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the headless ClipBroad IME with a usable AnySoftKeyboard-derived IME that keeps normal typing available while providing automatic encrypted Mac↔Redmi clipboard synchronization.

**Architecture:** Fork AnySoftKeyboard into this repository, preserve its IME and keyboard UI, and add ClipBroad transport as a small Kotlin service owned by the same process. The IME remains the active keyboard so Android clipboard access is authorized; incoming remote clipboard writes are loop-suppressed and local clipboard changes are encrypted and sent over the existing Bonjour/NSD channel.

**Tech Stack:** Android/Kotlin, AnySoftKeyboard Apache-2.0 source, Android ClipboardManager/InputMethodService, NSD, Java AES-GCM, existing Swift macOS companion.

---

### Task 1: Import and inventory AnySoftKeyboard

**Files:**
- Create: `android/` from the upstream AnySoftKeyboard repository
- Preserve: upstream license and notice files

Steps:

1. Clone the upstream repository into a temporary directory and inspect its Gradle modules, package names, IME service, and license files.
2. Import only the required IME module and its required local modules; do not copy release artifacts or build outputs.
3. Confirm the upstream project builds before modifications.

### Task 2: Make the imported keyboard build as ClipBroad

**Files:**
- Modify: Android application/module Gradle files
- Modify: Android manifest and IME metadata
- Create: ClipBroad attribution/license notice

Steps:

1. Change application ID, app label, icon label, and settings activity branding to ClipBroad.
2. Preserve Apache-2.0 attribution and add ClipBroad copyright/notice text.
3. Add only required network permissions and keep unrelated upstream network features disabled.
4. Build the untouched-branded fork and fix only integration/build issues.

### Task 3: Integrate ClipBroad transport

**Files:**
- Create: `ClipBroadTransport.kt`
- Create: `ClipBroadPayload.kt`
- Modify: upstream IME service lifecycle
- Modify: setup/preferences UI

Steps:

1. Move the existing NSD/TCP/AES-GCM transport into reusable Kotlin classes.
2. Use `SecureRandom` for 12-byte GCM nonces and reject malformed frames.
3. Preserve origin IDs and suppress the next clipboard callback after applying a remote payload.
4. Add text and actual image-byte transfer through a content provider.
5. Load the pairing secret from shared preferences used by both setup and the IME.
6. Start/stop discovery and sockets with IME lifecycle and reconnect after disconnect.

### Task 4: Add keyboard-integrated setup/status

**Files:**
- Modify: keyboard toolbar/settings UI
- Modify: `SetupActivity`

Steps:

1. Add a ClipBroad status item to the keyboard toolbar/settings.
2. Show `Sync unavailable` when the transport is disconnected or the IME is destroyed.
3. Document default-keyboard, Autostart, and HyperOS battery setup.
4. Keep clipboard history disabled for ClipBroad’s own sync path.

### Task 5: Verify and package

**Files:**
- Create: `ClipBroad.apk`
- Modify: `README.md`

Steps:

1. Run the upstream/unit/build checks.
2. Build a debug APK and inspect manifest permissions/package metadata.
3. Install on the connected Redmi if available and test text, PNG/JPEG, lock/unlock, reconnect, and loop suppression.
4. Package the APK and matching Mac companion with setup instructions.

