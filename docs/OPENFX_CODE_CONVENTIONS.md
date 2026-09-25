# OpenFX Code Conventions & Technical Reference

## Build System

### Version Management
All versions centralized in `buildSrc/src/main/kotlin/Dependencies.kt`.
- `Versions` object: SDK levels, plugin versions
- `Deps` object: full Maven coordinates with version interpolation
- Submodules reference as `Deps.hiltAndroid`, `Versions.compileSdk`, etc.

### buildSrc requires its own build.gradle.kts:
```kotlin
plugins {
    `kotlin-dsl`
}
repositories {
    mavenCentral()
}
```

### Module Structure
```
settings.gradle.kts → includes :app and :core
build.gradle.kts (root) → declares plugins with `apply false`
app/build.gradle.kts → applies plugins, depends on :core
core/build.gradle.kts → Android library, no Compose
```

---

## Architecture Patterns

### MVVM with Hilt
- ViewModels use `@HiltViewModel` + `@Inject constructor`
- Activities use `@AndroidEntryPoint`
- Services use `@AndroidEntryPoint`
- Application uses `@HiltAndroidApp`
- DI module in `core/common/CoreModule.kt`

### State Management
- `MutableStateFlow<UiState>` in ViewModel
- UI collects via `viewModel.uiState.collectAsState()`
- State updates via `_uiState.update { it.copy(...) }`
- Dialog visibility controlled via boolean flags in UiState

### File Operations
- `FileOperations` interface in core → `LocalFileOperations` implementation
- All operations are `suspend` functions running on `Dispatchers.IO`
- Copy/Move return `Flow<TransferProgress>` for progress tracking
- Results wrapped in `Result<T>` for error handling

### Navigation
- Bottom `NavigationBar` (portrait) / `NavigationRail` (landscape)
- Switching controlled by `AdaptiveLayoutInfo.useSideNavigation`
- Four tabs: Files (檔案), Devices (裝置), Transfer (傳檔), Settings (設定)

### Platform Detection (SmartTube approach)
```kotlin
// Detection order:
1. UiModeManager.currentModeType == TELEVISION → TV
2. PackageManager.hasSystemFeature(LEANBACK) → TV  
3. !hasSystemFeature(TOUCHSCREEN) → TV
4. Build.MANUFACTURER == "Amazon" + AFT* → Fire TV
5. ScreenLayout >= LARGE → TABLET
6. else → PHONE
```

### Adaptive Layout
```kotlin
val layoutInfo = rememberAdaptiveLayoutInfo()
// layoutInfo.useDualPane → show sidebar in landscape
// layoutInfo.useSideNavigation → use NavigationRail vs NavigationBar
// layoutInfo.gridMinColumns → adjust grid column count
// layoutInfo.sidebarWidth → sidebar width in dp
```

---

## Common Issues & Solutions

### Missing imports (frequently needed after edits)
```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
```

### System bar overlap
- themes.xml: `<item name="android:fitsSystemWindows">true</item>`
- Do NOT use `enableEdgeToEdge()` — causes status bar issues on some tablets
- Bottom bars need `.navigationBarsPadding()` modifier

### File sharing requires FileProvider
- Declared in AndroidManifest: `${applicationId}.fileprovider`
- Paths config in `res/xml/file_paths.xml`
- Use `FileProvider.getUriForFile()` for all file URIs shared externally

### Clipboard pattern for Copy/Move
```
User selects files → presses Copy/Move → files stored in ClipboardData
→ User navigates to target folder → presses Paste → operation executes
→ Clipboard cleared, view refreshed
```

### Copy to same directory
Needs auto-rename: `file.txt` → `file_copy.txt` → `file_copy2.txt`
Check if target exists, append `_copy` + counter before extension.

---

## String Resources

### Convention
- Default language: Traditional Chinese (values/strings.xml)
- All user-visible strings should use `stringResource(R.string.xxx)`
- Format: `%d` for numbers, `%s` for text, `\n` for line breaks
- New language: copy `values/strings.xml` to `values-XX/` and translate

### Current status
- strings.xml files have 132 strings each (zh-TW, en, zh-CN)
- Kotlin code still has MANY hardcoded Chinese strings not yet converted
- Converting is mechanical: replace `"確認"` with `stringResource(R.string.confirm)`

---

## P2P Protocol (future reference)

### Device Discovery
- Wi-Fi Direct via WifiP2pManager
- BroadcastReceiver for PEERS_CHANGED, CONNECTION_CHANGED
- Pairing via QR Code payload or 4-digit PIN

### File Transfer Protocol (TCP port 18921)
```
Header (variable length):
  - magic: "OPENFX" (6 bytes)
  - version: int (4 bytes)
  - filename length: int (4 bytes)
  - filename: UTF-8 bytes
  - file size: long (8 bytes)
Data:
  - 8KB chunks until file size reached
```

### Remote Control (TCP port 18920)
- JSON-serialized RemoteCommand → RemoteResponse
- Commands: ListDir, Rename, Delete, Move, Copy, CreateDir, PlayMedia, GetStorageInfo
- TV runs RemoteControlService as a persistent TCP server
