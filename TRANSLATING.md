# OpenFX Translation Guide / 翻譯指南

## How to add a new language

1. Copy `app/src/main/res/values/strings.xml` to a new folder:
   ```
   app/src/main/res/values-XX/strings.xml
   ```
   Where `XX` is the [ISO 639-1 language code](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes).

   For region-specific variants, use `values-XX-rYY`:
   - `values-pt-rBR/` = Brazilian Portuguese
   - `values-zh-rCN/` = Simplified Chinese
   - `values-zh-rHK/` = Hong Kong Chinese

   Also copy the small core module file (storage volume names):
   ```
   core/src/main/res/values/strings.xml → core/src/main/res/values-XX/strings.xml
   ```

2. Translate all `<string>` values and every `<item>` inside `<plurals>`. **Do NOT change the `name` attribute.**
   For `<plurals>`, add the quantities your language needs (`one`, `few`, `many`, `other`…);
   languages without plural forms (Chinese, Japanese, Korean…) only need `other`.

3. Keep format placeholders in place — they get replaced at runtime:
   - `%1$d`, `%d` = number (e.g. "3 selected")
   - `%1$s`, `%s` = text (e.g. error messages, device names)
   - The `1$` / `2$` part is the argument order; you may reorder them to fit your grammar.

4. Keep `\n` for line breaks.

5. Test by switching only OpenFX's language — no need to change the whole device:
   - Android 13+: Settings → Apps → OpenFX → Language
   - Or with adb (works on Android TV too):
     ```
     adb shell cmd locale set-app-locales com.openfx.mobile --locales ja
     adb shell cmd locale set-app-locales com.openfx.mobile --locales ''   # back to system language
     ```
   New `values-XX` folders are picked up automatically (`generateLocaleConfig` in `app/build.gradle.kts`).

## Current languages

| Language | Folder | Status |
|----------|--------|--------|
| 繁體中文 (Traditional Chinese) | `values/` (default) | ✅ Complete |
| English | `values-en/` | ✅ Complete |
| 简体中文 (Simplified Chinese) | `values-zh-rCN/` | ✅ Complete |
| 日本語 (Japanese) | `values-ja/` | 🔲 Needs translation |
| 한국어 (Korean) | `values-ko/` | 🔲 Needs translation |
| Español | `values-es/` | 🔲 Needs translation |
| Français | `values-fr/` | 🔲 Needs translation |
| Deutsch | `values-de/` | 🔲 Needs translation |
| Português | `values-pt/` | 🔲 Needs translation |
| ภาษาไทย | `values-th/` | 🔲 Needs translation |
| Tiếng Việt | `values-vi/` | 🔲 Needs translation |
| Bahasa Indonesia | `values-in/` | 🔲 Needs translation |

## Tips

- Keep translations concise — UI space is limited, especially on phone screens
- Test right-to-left (RTL) layouts if translating to Arabic or Hebrew
- App name "OpenFX" should NOT be translated
- Technical terms like "Wi-Fi Direct", "AES-256", "PIN", "QR Code", "APK" are usually kept as-is
