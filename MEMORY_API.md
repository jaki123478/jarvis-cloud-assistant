# J.A.R.V.I.S. Memory Vault & Chat Streaming — Android-compatible API

Shared by **web PWA** and **Android**. Base URL = FastAPI server (e.g. `http://192.168.x.x:8000`).

All memory is keyed by `session_id` (string, ≤120). Use a stable per-install id (Android already stores `assistant_session_id`).

---

## Memory endpoints

### `GET /memory?session_id={id}&client=android|web`

List facts.

```json
{
  "session_id": "android-…",
  "updated_at": "2026-09-29T15:00:00+0200",
  "count": 2,
  "facts": [
    { "id": "a1b2c3d4e5f6", "text": "Mi chiamo Jacopo", "created_at": "…", "source": "chat" }
  ]
}
```

### `POST /memory`

Body:

```json
{ "session_id": "…", "client": "android", "fact": "Preferisco il caffè", "source": "android" }
```

(`text` accepted as alias of `fact`.)

Response: `{ "status": "saved"|"exists", "fact": {…}, "vault": {…} }`

### `POST /memory/forget`

```json
{ "session_id": "…", "client": "android", "fact_id": "a1b2c3d4e5f6" }
```

or substring:

```json
{ "session_id": "…", "query": "caffè" }
```

Response: `{ "status": "forgotten"|"noop", "removed": N, "vault": {…} }`

### `DELETE /memory/{fact_id}?session_id={id}&client=android`

REST delete of one fact.

### `POST /memory/clear`

```json
{ "session_id": "…", "client": "android" }
```

Clears all facts for that session.

---

## Chat intents (same `/chat` body)

Works for web and Android without calling `/memory` explicitly:

| Utente dice | Effetto |
|-------------|---------|
| `ricorda che …` / `ricordati che …` / `memorizza …` | Salva fatto |
| `dimentica …` | Rimuove fatti che contengono la query |
| `dimentica tutto` / `cancella memoria` | Azzera vault |
| `cosa sai di me` / `cosa ti ricordi` | Elenca fatti |

`POST /chat` reply for these uses `"engine": "memory-vault"` and may include `"memory": { count, facts, … }` plus `"action": "memory_save"|"memory_forget"|"memory_clear"|"memory_list"`.

Facts are also **injected into the LLM system prompt** for normal chat turns.

---

## Chat (unchanged for Android)

### `POST /chat`

Existing body still valid:

```json
{
  "message": "…",
  "client": "android",
  "session_id": "…",
  "model": "deepseek-v4.1-flash",
  "personality": "stark",
  "context": { "device": "android" }
}
```

Response (unchanged shape):

```json
{
  "reply": "…",
  "action": "chat",
  "action_params": {},
  "engine": "…",
  "latency_ms": 123.4
}
```

Optional: `"stream": true` → same handler returns **SSE** (see below). Prefer dedicated endpoint on clients that support streaming.

### `POST /chat/stream`

Same JSON body as `/chat`. Always returns `text/event-stream`:

```
event: meta
data: {"action":"chat","engine":"…","latency_ms":…,"sentence_count":3}

event: sentence
data: {"index":0,"text":"Prima frase.","final":false}

event: sentence
data: {"index":1,"text":"Seconda frase.","final":true}

event: done
data: {"reply":"Prima frase. Seconda frase.","action":"chat","action_params":{},"engine":"…","latency_ms":…}
```

Android can keep using non-streaming `/chat` for now; wire SSE later for progressive TTS.

---

## Persistence

Files: `data/memory/{sanitized_session_id}.json` (gitignored). Not secrets — still avoid committing personal facts.

---

## Web PWA notes (this pass)

- Anti-echo: STT aborted/ignored while TTS speaks + ~550ms cooldown (HUD: **ANTI-ECHO**).
- Continuous listen + wake-phrase regex filter (no openWakeWord ONNX).
- Progressive sentence TTS via `/chat/stream` when available; else full reply + sentence-split Edge TTS.
