# Voice Agent

A voice assistant for Android with a **cloud brain and offline reflexes**.

Hold the mic and speak (or type). The phone transcribes speech locally with
**Whistle**, sends the text to the AI provider you chose, and speaks the answer
with the phone's own text-to-speech voice. When the provider is missing or
offline, **Needle 3** still handles simple commands on the device.

## What it can do

With a provider configured:

- Answer questions and hold a conversation.
- Find a contact and open the dialer (the call still needs one tap).
- Turn the flashlight on or off.
- Open an installed app by name.
- Set a timer or an alarm.

Without internet, the offline reflexes still handle calls, the flashlight,
apps, timers and alarms. They do not answer general questions.

Screen reading and tapping, the camera and vision are planned for later
versions. The app does not claim those abilities yet.

## Providers

The app speaks the OpenAI-compatible Chat Completions API, so one adapter
covers most services:

| Preset | Base URL |
| --- | --- |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| OpenAI | `https://api.openai.com/v1` |
| Ollama (LAN) | `http://192.168.1.100:11434/v1` |
| LM Studio (LAN) | `http://192.168.1.100:1234/v1` |
| llama.cpp (this phone) | `http://127.0.0.1:8080/v1` |
| Custom | any OpenAI-compatible endpoint |

You can also use **DeepSeek**, **LiteLLM**, **vLLM** or any other server that
implements `/chat/completions` and, optionally, `/models`.

### Setting up Gemini

1. Open [Google AI Studio](https://aistudio.google.com/apikey) and create an
   API key.
2. In the app open **Settings → AI providers → Google Gemini → Edit**.
3. Paste the key and tap **Test connection**.
4. Tap **Save**. The first provider you save becomes the default.

The default model is `gemini-3.5-flash-lite`. The free tier is rate-limited;
if you see a 429 error, wait a moment or add a second provider as a fallback.

### Fully local models

On the phone, in Termux:

```sh
pkg install llama-cpp
llama-server -m /path/to/model.gguf --host 127.0.0.1 --port 8080
```

Then add a **llama.cpp (this phone)** provider with model `local-model` (or the
name printed by the server). Plain `http://` is accepted only for localhost
and private LAN addresses; anything on the internet must use `https://`.

Small local models are fine for chat but weak at multi-step tool use. The
cloud provider is the better brain for actions.

### Keys and privacy

- API keys are entered on the phone and encrypted with the Android Keystore
  (AES/GCM). They are never committed to this repository and never sent
  anywhere except your chosen provider.
- Backups are disabled for the app, so keys and chat history are not copied
  to Google's servers.
- Chat history is stored locally and can be cleared in Settings.
- **Share diagnostics** produces a text report with API keys and tokens
  redacted. It leaves the phone only when you share it yourself.
- On Gemini's free tier, Google may use prompts to improve its products. Use a
  paid key or a local model if that matters to you.

## Android 16 notes

- The APK is debug-signed and installed by sideloading. Android may ask you to
  allow installs from your browser or file manager.
- **Advanced Protection must be off.** It blocks sideloading and restricts
  accessibility to verified apps.
- Later versions will use an accessibility service for screen reading and
  tapping. Android 16 requires **Allow restricted settings** for that and
  blocks accessibility changes during phone calls.
- The app uses edge-to-edge layout and supports 16 KB memory pages.

## Permissions

- **Microphone** — records while the mic button is held (16 kHz mono).
- **Contacts** — looks up the name you said. Nothing is uploaded.
- **Camera** — Android requires camera access to switch the flashlight.

Each permission is requested with a short explanation. If one is permanently
denied, the app offers a link to its settings page.

## Building

You do not need a computer. GitHub Actions builds the APK:

1. Create or open a repository on GitHub.
2. Push this folder to `main`.
3. Open the **Actions** tab and wait for **Build APK** to finish.
4. Open the run, scroll to **Artifacts**, and download `app-debug`.
5. On the phone: unzip it, tap `app-debug.apk`, and allow installs from that
   source when Android asks.

```sh
git init
git add .
git commit -m "Voice agent 1.1"
git branch -M main
git remote add origin https://github.com/YOUR_NAME/YOUR_REPO.git
git push -u origin main
```

Every push rebuilds the APK, which is kept as an artifact for 90 days.
Published releases keep a permanent copy.

## First launch

The app downloads two small models into private app storage while showing a
progress bar, then verifies each file's size and SHA-256:

| File | Size | Source |
| --- | --- | --- |
| `needle3.cact` | 35,335,380 B | `huggingface.co/Cactus-Compute/needle3` |
| `whistle.cact` | 16,919,407 B | `huggingface.co/Cactus-Compute/whistle` |

About 50 MB in total, downloaded once. The files are pinned to exact revisions
in `models/ModelDownloader.kt`. Model files are not committed to this
repository and are excluded from Android backup.

The native engine (the official Cactus 4 `libcactus.a` for Android arm64) is
committed under `app/src/main/cpp/cactus/`, so builds are reproducible.

## Technical notes

- Kotlin + Jetpack Compose, single activity, edge-to-edge.
- `arm64-v8a` only, `minSdk 24`, `targetSdk 36`, AGP 8.13, Compose BOM 2026.06.
- Provider layer: `llm/OpenAiCompatibleClient.kt` uses `HttpURLConnection` and
  `org.json`. No networking library and no serialization plugin.
- Gemini's compatibility endpoint needs a Bearer key and returns models as
  `models/<id>`; the client normalizes that. Opaque
  `extra_content.google.thought_signature` values on tool calls are stored and
  echoed back, which Gemini requires across tool turns.
- The visible transcript is canonical; the app does not rely on server-side
  conversation state.
- Tools: `agent/ToolRegistry.kt` defines the schema shared by Needle 3 and the
  cloud model; `agent/ToolExecutor.kt` runs them.
- Keys: `data/SecureStore.kt` (Android Keystore AES/GCM). Settings and chat:
  `data/AppStore.kt` (SharedPreferences, keys excluded).
- Offline model loading and JNI: `engine/CactusEngine.kt`,
  `app/src/main/cpp/cactus_jni.cpp`.

See `.github/workflows/build.yml` for the exact build steps.
