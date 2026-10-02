# Installed App Transfer

Build: 0.2.2 (versionCode 4)
Validated: 2026-10-02

## Workflow

1. Enable receiving on the destination device.
2. On the sending phone, open Devices and select the destination.
3. Choose Send installed apps, search by label or package name, select apps and send.
4. The receiving device prompts for installation. Confirm in the Android system installer.

The TV device list also provides a Send installed apps action for each destination.
App lists show icons, version, package name and total installed APK size. System apps
are hidden initially and can be included with the filter. Searches retain selections.
Export preparation can be cancelled; already queued uploads use the existing transfer
progress and cancellation controls. Export snapshots are removed after upload ends.

## Package Format And Installation

- A standalone app is exported as an unchanged, signed `.apk`.
- A split app is exported as a flat `.apks` ZIP containing `base.apk` and all installed
  splits. APK contents and signatures are preserved. This is an installed APK set,
  not a bundletool archive containing every possible device configuration.
- OpenFX reads the base APK for the installation preview, validates ZIP entries and
  writes all APKs to one `PackageInstaller` session. Installation requires explicit
  Android confirmation and the unknown-app installation permission.
- `.apks` files can also be installed later from the file browser.
- Received apps are queued for prompts, so concurrent receipts do not replace an
  unanswered prompt. Mobile prompts remain available across navigation tabs.

Use OpenFX 0.2.2 or later on both devices for split-app transfer and installation.
Standalone APKs remain compatible with earlier OpenFX receivers.

## Limits

The transfer includes installation packages only. It does not include private app
data, accounts, login sessions, expansion/OBB files or separately downloaded assets.
The destination must support the app's Android version and CPU architecture.
Installed splits contain the source device's configurations; exporting does not
generate missing CPU or language variants. Paid/protected apps can still require
their original account, license or store. System apps can depend on their original
device firmware. Existing installations must have a compatible signing certificate.

## Verification

- Debug build and Android-test APK build succeeded using the existing local toolchain.
- Final build and four archive unit tests passed offline. Tests cover byte-preserving
  export, malformed archive paths/missing base, entry limits and cancellation cleanup.
- On an Android 15 AI BOX, instrumentation exported a standalone APK and a real split
  app, transferred both over the real HTTP endpoint and verified their SHA-256 contents.
  Every installed split was present. Session staging was verified and then abandoned.
- Picker search and selection passed accessibility-driven tests in landscape and
  simulated phone portrait layouts; screenshots were inspected for layout problems.
- The actual sending ViewModel transferred two selected apps from the AI BOX to an
  Android 14 TV over Wi-Fi. Export snapshots were cleaned after transfer.
- A minimal signed test app with a configuration split was installed on the sender,
  exported by the feature and successfully installed on the TV through the system
  confirmation screen. Both base and split were confirmed with `pm path`.
- A real arm64 app sent to the 32-bit TV was correctly rejected by Android. The app
  translates `NO_MATCHING_ABIS` into a readable processor-compatibility message.
- Test fixtures and received test files were removed; source-device display sizing
  was restored. Existing user apps and downloads were preserved.

These checks used an AI BOX and a TV, not two physical phones. Physical phone-to-phone
discovery and vendor-specific installer behavior still require device testing.

## Repeatable Tests

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --offline
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.openfx.mobile.test/com.openfx.mobile.apps.AppTransferInstrumentation
adb shell am instrument -w -e mode ui com.openfx.mobile.test/com.openfx.mobile.apps.AppTransferInstrumentation
```

The peer test (`-e mode send -e targetIp <receiver-ip>`) requires an OpenFX receiver
and an installed non-system split app without native libraries. For the minimal
fixture, link each manifest under `app/src/androidTest/fixtures` with local SDK
`aapt2 link -I <android.jar>`, sign both with the same local debug key using
`apksigner`, and install them on the sender with `adb install-multiple`.
The manifests contain no code, permissions or native libraries. Remove the fixture
after testing with `adb uninstall com.openfx.testfixture` on both devices.

Android references: [package visibility](https://developer.android.com/training/package-visibility/declaring),
[installation sessions](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session),
[required user action](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
