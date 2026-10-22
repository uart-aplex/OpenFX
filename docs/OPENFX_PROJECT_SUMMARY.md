# OpenFX Project Summary
## Open-Source Android File Manager (ES File Explorer Replacement)

**Last Updated**: 2026-05-12 (Session 2)
**Developer**: Charlie
**Status**: Alpha — builds and runs on device, core file browsing works

---

## Project Overview

OpenFX is a native Kotlin/Jetpack Compose file manager for Android, designed as a clean, ad-free replacement for ES File Explorer. **Single APK** runs on phone, tablet, and Android TV with automatic UI switching (SmartTube approach).

### Key Design Decisions
- **Single APK architecture** (not separate phone/TV APKs) — `PlatformDetector` checks at runtime via UiModeManager, Leanback feature, touchscreen presence, and manufacturer fingerprints
- **Two modules only**: `app` (unified UI for phone+TV) and `core` (shared logic)
- **Language**: Default Traditional Chinese (zh-TW) for Charlie's family, with English and Simplified Chinese translations ready
- **License**: Apache 2.0, planned open-source on GitHub
- **Build system**: Kotlin DSL with `buildSrc/Dependencies.kt` for centralized version management

---

## Architecture

```
OpenFX/
├── buildSrc/
│   ├── build.gradle.kts           (plugins { `kotlin-dsl` } + repositories)
│   └── src/main/kotlin/
│       └── Dependencies.kt        (all version constants)
│
├── core/                          (Android Library module)
│   ├── file/
│   │   ├── FileItem.kt            (data model, FileType enum, StorageVolume)
│   │   ├── FileOperations.kt      (interface + SortBy enum)
│   │   └── LocalFileOperations.kt (Java File API implementation)
│   ├── sync/
│   │   ├── DeviceSync.kt          (DeviceInfo, PairingState, RemoteCommand/Response)
│   │   ├── FileTransfer.kt        (TransferTask, FileTransferEngine interface)
│   │   ├── WifiDirectDiscovery.kt  (Wi-Fi P2P implementation)
│   │   └── TcpFileTransferEngine.kt (TCP file transfer protocol)
│   ├── network/
│   │   └── Smb2Client.kt          (SMB2/3 via smbj library)
│   └── common/
│       └── CoreModule.kt          (Hilt DI bindings)
│
├── app/                           (Android Application module — unified APK)
│   ├── platform/
│   │   ├── PlatformDetector.kt    (runtime phone/tablet/TV detection)
│   │   └── AdaptiveLayout.kt     (WindowSizeClass, orientation-aware layout)
│   ├── ui/
│   │   ├── MainActivity.kt        (unified entry — switches UI tree by platform)
│   │   ├── NavHost.kt             (adaptive nav: bottom bar portrait / side rail landscape)
│   │   ├── theme/Theme.kt         (Material3 with OpenFX brand colors)
│   │   ├── browser/
│   │   │   ├── FileBrowserViewModel.kt  (navigation, selection, clipboard, share, open)
│   │   │   └── FileBrowserScreen.kt     (grid/list view, sidebar, search, sort, thumbnails)
│   │   ├── transfer/
│   │   │   ├── DeviceScreen.kt    (P2P device scanning + pairing UI)
│   │   │   └── TransferScreen.kt  (active transfers + history)
│   │   ├── settings/
│   │   │   └── SettingsScreen.kt  (all settings categories)
│   │   ├── media/
│   │   │   └── PlaybackService.kt (Media3 MediaSessionService)
│   │   └── tv/
│   │       ├── TvTheme.kt         (TV dark theme)
│   │       ├── home/TvHomeScreen.kt    (Leanback-style card layout)
│   │       └── remote/RemoteControlService.kt (TCP command server)
│   └── res/
│       ├── values/strings.xml      (zh-TW, 132 strings)
│       ├── values-en/strings.xml   (English, 132 strings)
│       ├── values-zh-rCN/strings.xml (Simplified Chinese, 132 strings)
│       ├── values/themes.xml       (fitsSystemWindows=true)
│       ├── xml/file_paths.xml      (FileProvider config)
│       ├── drawable/               (launcher icons, TV banner)
│       └── mipmap-anydpi-v26/      (adaptive icon)
```

---

## Current Versions (as of last successful build)

| Component | Version |
|-----------|---------|
| AGP | 8.13.2 |
| Kotlin | 2.2.0 |
| KSP | 2.2.0-2.0.2 |
| Gradle | 8.13 |
| Compose BOM | 2024.12.01 |
| Hilt | 2.57 |
| Target SDK | 35 |
| Min SDK | 24 |
| JDK | 17 (Embedded JBR) |

**Note**: `buildSrc/src/main/kotlin/Dependencies.kt` is the single source of versions (there is no version catalog).

---

## Feature Status

### ✅ Completed & Working
- File browsing (navigate folders, go up, go back)
- Grid and List view toggle
- Long-press multi-select
- New folder creation
- Delete files/folders with confirmation dialog
- Rename (single file)
- **Copy/Move** — clipboard mode (select → copy → navigate to target → paste)
- **Share** — Android ShareSheet integration via FileProvider
- **Open files** — launches system default app via FileProvider
- **Search** — expandable search bar, recursive file search
- **Sort** — dropdown menu with Name/Size/Date/Type + ascending/descending toggle
- **Show/hide hidden files** — toggle via toolbar icon
- **Image/Video thumbnails** — Coil AsyncImage in grid and list views
- **Properties dialog** — shows folder info (path, item count, permissions, dates)
- **Error display** — error bar with dismiss button
- **Adaptive navigation** — bottom bar (portrait) / side rail (landscape)
- **Sidebar** — storage volumes + shortcuts (landscape/tablet only)
- **Breadcrumb navigation** — clickable path segments
- **Back button handling** — multi-select → clear, root → exit app
- **Multi-language** — zh-TW (default), English, zh-CN with translation guide
- **Unified APK** — same APK works on phone, tablet, and Android TV
- **Platform detection** — runtime TV/Phone/Tablet switching
- **TV home screen** — Leanback-style card layout (basic)
- **TV remote control service** — TCP server for phone→TV commands

### 🔲 Not Yet Implemented
- **Copy to same directory** — needs auto-rename (file_copy.txt) — code was provided but not yet applied
- **Media player** — ExoPlayer integration for image viewer, video player, audio player
- **Room database** — for media index, bookmarks, transfer history, paired device persistence
- **FTP/SFTP client** — interface not created (JSch dependency included)
- **Archive browsing** — open zip/rar/7z contents (commons-compress/junrar included)
- **DataStore preferences** — settings persistence not wired up
- **Notification channels** — for transfer progress notifications
- **File sharing intent** — handling ACTION_SEND from other apps
- **QR code generation** — currently placeholder in pairing UI (needs ZXing)
- **TV file browser** — D-pad navigable file browsing UI for TV
- **MediaStore scanning** — browse all images/videos across all directories (not just DCIM)
- **Bookmark system** — save favorite folders
- **SMB/NAS browsing UI** — Smb2Client exists but no UI to connect/browse
- **Hardcoded strings** — Kotlin code still has Chinese strings not yet replaced with `stringResource(R.string.xxx)`

### ❌ Decided Not To Do
- Separate TV APK (merged into single APK)
- Cloud storage integration (keep it local/LAN focused)
- Ad system or tracking (clean open-source)
- Google Play Store distribution initially (sideload first)

---

## Known Bugs

### UI Issues
1. **Status bar overlap** — Fixed with `fitsSystemWindows=true` in themes.xml and removing `enableEdgeToEdge()`. Verify on different devices.
2. **Bottom bar overlap with system Dock** — `navigationBarsPadding()` added to ActionBar and QuickActionBar. Error bar also needs it (fix was provided but may not be applied yet).
3. **Error message hidden behind system nav** — needs `navigationBarsPadding()` on error Surface (fix provided in last session).
4. **Copy to same directory fails** — target file already exists, needs auto-rename logic (fix code provided but not applied).

### Build/Compile Notes
- `horizontalScroll` import frequently missing after code changes — add `import androidx.compose.foundation.horizontalScroll`
- `LocalContext` import needed for BackHandler exit — `import androidx.compose.ui.platform.LocalContext`
- KSP version must exactly match Kotlin version (2.2.0 → 2.2.0-2.0.2)
- `buildSrc/build.gradle.kts` must include `repositories { mavenCentral() }`
- AGP auto-upgrade suggestions from Android Studio should be cautious (9.x is too new)

---

## Test Device
- **Lenovo Y700 (TB322FC)** — Android tablet
- ZUI launcher (has bottom Dock that overlaps with app UI)
- Landscape-primary orientation
- Status bar shows condensed mode (`•••`) in some apps

---

## Dependencies (in buildSrc/Dependencies.kt)

### Core
- Kotlin Coroutines + Flow
- Kotlin Serialization JSON
- Hilt (DI)
- KSP (annotation processing)
- Room (database — not yet used)
- DataStore (preferences — not yet used)

### UI
- Jetpack Compose + Material3 (phone/tablet)
- Compose for TV + Leanback (TV)
- Coil 3 (image loading + thumbnails)
- Media3 ExoPlayer + MediaSession

### Network/Files
- smbj (SMB2/3 client)
- commons-net (FTP)
- JSch (SFTP/SSH)
- Apache Commons Compress + JUnRAR (archives)

---

## Project Location on Charlie's Machine
```
E:\APP\O\OpenFX\
```

## Key Files to Check After Resuming
1. `buildSrc/src/main/kotlin/Dependencies.kt` — verify all version numbers match what Android Studio AI may have changed
2. `app/build.gradle.kts` — check if Hilt version was updated
3. `app/src/main/res/values/themes.xml` — should have `fitsSystemWindows=true`
4. `app/src/main/kotlin/com/openfx/mobile/ui/MainActivity.kt` — should NOT have `enableEdgeToEdge()`

---

## Next Priorities (suggested order)
1. Apply copy-to-same-directory fix (auto-rename)
2. Apply error bar navigationBarsPadding fix
3. MediaStore image/video scanning (browse all pictures across all folders)
4. Replace hardcoded Chinese strings with `stringResource()` calls
5. Built-in image viewer (full-screen, pinch zoom, swipe between images)
6. Built-in video player (ExoPlayer)
7. Settings persistence (DataStore)
8. Bookmark system
9. SMB/NAS browsing UI
10. P2P end-to-end testing
