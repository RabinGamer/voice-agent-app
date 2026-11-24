# Voice Agent

A one-screen, fully offline Android voice agent.

Hold the round **Hold to talk** button, speak, and release. The app transcribes
your speech with **Whistle**, asks **Needle 3** which tool to run, and then
either opens the dialer for a contact or switches the flashlight.

There are exactly two tools:

- `call_contact(contact_name)` — fuzzy-matches a contact and opens the dialer
  (`Intent.ACTION_DIAL`, so the call still needs a final tap). If several
  contacts match, the app asks which one; if none match, it says so on screen.
- `toggle_flashlight(state = "on" | "off")` — switches the torch through
  `CameraManager.setTorchMode`.

Anything else gets: *"Sorry, I can only call contacts or control the flashlight."*

Everything runs on the phone. After the first launch there is no network use
at all: the Cactus engine has no networking code.

## Building

You do not need a computer. GitHub Actions builds the APK:

1. Create an empty repository on GitHub.
2. From this folder, commit and push (see below).
3. Open the **Actions** tab, wait for **Build APK** to finish (green tick).
4. Open the run, scroll to **Artifacts**, download `app-debug`.
5. On the phone: unzip it, tap `app-debug.apk`, and allow installing from this
   source when Android asks.

```sh
git init
git add .
git commit -m "Voice agent: offline STT + tool calls"
git branch -M main
git remote add origin https://github.com/YOUR_NAME/YOUR_REPO.git
git push -u origin main
```

Every push rebuilds it. The APK is kept for 90 days.

## First launch

The app downloads its two models into private app storage while showing a
progress bar, then checks each file's size and SHA-256:

| File | Size | Source |
| --- | --- | --- |
| `needle3.cact` | 35,335,380 B | `huggingface.co/Cactus-Compute/needle3` |
| `whistle.cact` | 16,919,407 B | `huggingface.co/Cactus-Compute/whistle` |

About 50 MB in total, downloaded once. The files are pinned to exact revisions
in `models/ModelDownloader.kt`, so a download can be verified. Models are not
committed to this repository and are excluded from Android backup.

The engine itself (the official Cactus 4 `libcactus.a` for Android arm64) is
committed under `app/src/main/cpp/cactus/`, so the build is reproducible
without any extra downloads.

## Permissions

- **Microphone** — records while the button is held (16 kHz mono).
- **Contacts** — looks up the name you said.
- **Camera** — Android requires camera access to control the flashlight.

Each one is requested with a short explanation; if one is permanently denied
the app offers a link to its settings page.

## Technical notes

- Kotlin + Jetpack Compose, single activity.
- `arm64-v8a` only, `minSdk 24`, `targetSdk 36`.
- The official Cactus 4 engine has no Kotlin SDK, so a small JNI bridge
  (`app/src/main/cpp/cactus_jni.cpp`) exposes `cactus_load`, `cactus_init`,
  `cactus_transcribe`, `cactus_complete`, `cactus_reset` and friends. It is
  built with the NDK and links `libcactus.a` statically.
- All native calls are serialized: the engine is one process-global instance.
- Audio is recorded as PCM16 and converted to the float32 samples Whistle
  expects ([-1, 1], 16 kHz mono).

See `.github/workflows/build.yml` for the exact build steps.
