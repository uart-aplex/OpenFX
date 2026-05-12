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

2. Translate all `<string>` values. **Do NOT change the `name` attribute.**

3. Keep `%d` and `%s` format placeholders in place — they get replaced at runtime:
   - `%d` = number (e.g. "3 selected")
   - `%s` = text (e.g. error messages, device names)

4. Keep `\n` for line breaks.

5. Test by changing your phone language in Settings → System → Language.

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
