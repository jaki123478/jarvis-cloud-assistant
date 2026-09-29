# J.A.R.V.I.S. Android client

Questa cartella è riservata al client Android nativo. Dopo il deploy Render, impostare `JARVIS_BACKEND_URL` durante la build con l'URL HTTPS assegnato da Render:

```powershell
gradle assembleRelease -PJARVIS_BACKEND_URL=https://tuo-backend.example.com
```

Il valore predefinito è il backend Render attualmente distribuito (`https://jarvis-cloud-assistant-4dsr.onrender.com`). L'URL viene inserito in `BuildConfig`, senza modificare il codice Java.

Il backend deve restare su Render: l'app Android non contiene chiavi OpenAI.


## Wake word (Hey Jarvis)

Always-on wake word uses **Option B**: continuous `SpeechRecognizer` inside `JarvisForegroundService` (no Picovoice key, no ONNX deps).

### Enable
1. Grant microphone (+ notifications on Android 13+).
2. Settings → **WAKE WORD HEY JARVIS** → ON → Save.
3. Main HUD → **CORE ON**. Notification should say `ASCOLTO WAKE: HEY JARVIS`.
4. Say **Hey Jarvis** / **Ehi Jarvis** / **Jarvis**. App opens a voice session (tap-to-talk still works).

### Battery / locks
- PARTIAL_WAKE_LOCK held only while wake listening; released on CORE OFF / stop.
- Screen wake lock remains a separate Settings toggle.
- Continuous SpeechRecognizer is heavier than a true on-device wake model (expect noticeable battery use). Prefer CORE OFF when idle for long periods.
- OEM battery savers may kill the FGS; exclude JARVIS from battery optimization if needed.

### Limitations
- Relies on Google / device speech recognition (often needs network).
- False triggers possible; Italian locale preferred.
- Only one SpeechRecognizer owner: session mic pauses wake listening, then resumes.
- Not a SoTA openWakeWord detector.

### Upgrade path (Option A — openWakeWord)
1. Vendor `onnxruntime-android` AAR + openWakeWord mel/embedding/Hey-Jarvis-class models under `app/src/main/assets/`.
2. Replace `JarvisForegroundService` SpeechRecognizer loop with AudioRecord → ONNX inference.
3. Keep the same `WakePhrase` / `ACTION_WAKE_*` contracts so MainActivity stays unchanged.
4. Option C (Porcupine) needs a free Picovoice access key — document in Settings if adopted; do not commit secrets.


## Anti-echo (TTS)

While JARVIS speaks (Edge `/tts/audio` or native TTS), wake listening and command restarts are paused. A ~1.1s cooldown after TTS end ignores transcripts that match `lastSpokenText` so the mic does not re-trigger on its own voice.

## Memory API hooks (Android)

`MemoryApiClient` best-effort:
- `GET /memory?session_id=` on launch (merge into local prefs if 200)
- `POST /memory` after chat turns / "ricordati che"
- `POST /reset` (+ optional `/memory/clear`) on "cancella memoria"

If the sibling agent has not shipped those routes yet, calls 404 and local `conversation_memory` remains authoritative. Tap-to-talk and `/chat` + `session_id` unchanged.
