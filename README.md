# GlassChat 2.0

A monochrome Liquid Glass Android client for OpenAI-compatible chat completion APIs, built with Kotlin and Jetpack Compose.

## Implemented

- **Provider settings:** configurable API Base URL, API key, model ID, system prompt, and temperature. Non-secret settings persist with Preferences DataStore.
- **Encrypted API key:** AES-GCM encryption using a key generated and held by Android Keystore. The plaintext key is only held in memory while the app is running.
- **Streaming chat:** OpenAI-compatible `/chat/completions` SSE streaming, partial response persistence, stop/cancel generation, and readable HTTP errors.
- **Persistent conversations:** Room database stores chats and messages across restarts.
- **Chat management:** create, rename, delete, select, and search conversations.
- **Message actions:** copy messages, edit and resend user messages (later turns are discarded to branch from the edited message), and retry assistant responses.
- **Markdown and code:** headings, lists, bold/italic/inline code, fenced code blocks, and one-tap code copying.
- **Export:** save a conversation as Markdown or plain text using Android's document picker.
- **App-side web search:** DuckDuckGo HTML results are added to the request as a separate context message. This is best-effort; HTML markup and availability can change.
- **Attachments:** text files, common source-code formats, and PDFs with a selectable text layer. Extracted text is included in the prompt (maximum 180,000 characters per file).
- **SVG preview:** automatically detects SVG in assistant output and renders it in a WebView with JavaScript, file access, and content access disabled.
- **UI:** responsive dark monochrome glass panels, rounded surfaces, subtle gradients, animated empty state, and streaming indicator.
- **CI:** GitHub Actions builds a debug APK and uploads it as an artifact on push, pull request, or manual dispatch.

## Build an APK

1. Upload the repository contents to GitHub, keeping the directory structure.
2. Open **Actions → Android APK** and run the workflow (or push a commit to `main`).
3. Download the `GlassChat-debug` artifact from the completed workflow. It contains `app-debug.apk`.

Local build requirements: JDK 17, Android SDK 35, and Gradle 8.9 or newer compatible with AGP 8.7.3. Run:

```bash
gradle :app:assembleDebug
```

## API configuration

Open Settings in the app and enter:

- Base URL, for example `https://api.openai.com/v1` (without `/chat/completions`)
- API key
- Model ID supported by your provider
- Optional system prompt and temperature

The provider must support OpenAI-compatible Chat Completions and streaming SSE. Some providers return nonstandard content chunks or require additional headers; those may need an adapter.

## Important limitations and security notes

- PDF extraction reads embedded text only. Scanned PDFs need OCR, which is not bundled. Image attachments and multimodal image input are not implemented in this release.
- Web search uses DuckDuckGo's HTML endpoint rather than an official search API. Results may be blocked, incomplete, or change format. Check cited URLs and facts.
- Markdown rendering intentionally covers common chat formatting, not the full CommonMark/GFM specification. Tables and complex nested structures may appear as plain text.
- SVG is untrusted model output. JavaScript and local file/content access are disabled, but this is not a complete SVG sanitizer; do not treat arbitrary generated SVG as trusted content.
- The app needs network access. If you configure an `http://` provider endpoint, traffic is not encrypted in transit; prefer HTTPS except for trusted local development endpoints.
- The API key is encrypted at rest with Android Keystore. Removing app data or uninstalling the app removes the local vault; keep your key in a password manager.
- No API key is included in the repository or build.

## Project structure

- `app/src/main/java/app/connector/MainActivity.kt` — Compose UI, chat orchestration, SSE, search, file handling, Markdown, SVG.
- `app/src/main/java/app/connector/Storage.kt` — Room persistence, Preferences DataStore, Android Keystore vault.
- `.github/workflows/android.yml` — reproducible CI APK build.
