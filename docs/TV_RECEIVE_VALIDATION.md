# TV Receive UI Validation

Date: 2026-09-25
Build: 0.2.1 (versionCode 3), debug
Device: Xiaomi MiTV_MOEU0, 1920x1080 output, density 320

## Changes

- Phone-to-TV entry is prominent on the home screen. Opening the app does not start receiving.
- Receiver status uses text plus amber (off) or green (on), with a separate start/stop action.
- The transfer screen scrolls to keep lower actions accessible on smaller TV layouts.
- APK prompts are hosted above TV routes, so navigating home or to files does not remove the listener.
- Received APKs show an explicit installation question with a compact TV dialog and D-pad focus colors.
- Version increased to distinguish this update from previously installed 0.2.0 builds.

## Verification

- Online debug build succeeded after adding missing shared Gradle 8.13 / SDK 35 components.
- Offline debug build and subsequent incremental offline rebuild succeeded.
- Existing installed APK, local debug key and new APK have matching signing certificates; `adb install -r` succeeded without clearing app data.
- Home entry and receiver start/stop controls were inspected on device screenshots.
- A real APK uploaded over LAN using the app's HTTP transfer protocol displayed sender name and 57% progress during transfer.
- Completion automatically displayed the APK installation question on the receiver page.
- D-pad right/confirm opened the Android package installer; installation was cancelled because the test APK was this same app.
- A second upload while on the TV home screen also displayed the installation question.
- Returning to the receiver page after dismissing did not repeat the same prompt.
- The received-file action opened `/storage/emulated/0/Download/OpenFX` directly.
- Received APK SHA-256 matched the original build.
- Three generated test APKs were removed from the TV. Existing user downloads were preserved.
- TV was returned to the home screen with receiving stopped.

The sender in this session was a PC using the same upload endpoint, not a physical phone.
Phone discovery and the phone file-picker workflow were not retested in this session.
This was a build and focused device validation, not a full automated regression suite.

## Deliverable And Environment

APK: `dist/OpenFX-File-Manager-debug.apk`

Shared Windows setup: `E:\APP\ANDROID_BUILD_ENVIRONMENT.md`.
Use `E:\work\tools\bin\android-build-env.ps1` and the checked-in wrapper.
Generated module build directories and Gradle caches are not required for source backups.
