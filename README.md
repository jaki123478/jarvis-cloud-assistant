# ⚡ J.A.R.V.I.S. // Stark Industries Holographic Voice Assistant

> **Enterprise Military-Grade PWA & AI Core** ispirato all'HUD di Iron Man, ottimizzato per **Android Mobile (PWA Standalone Fullscreen)** e **Desktop**, potenziato da un backend Python FastAPI con **Multi-Tool Calling**, **Ricerca Web DuckDuckGo in tempo reale**, **Meteo Live**, **Screen Wake Lock API**, **Battery Status API**, e un motore neurale locale **nanoGPT** (PyTorch, architettura Andrej Karpathy) 100% offline.

---

## 🚀 Architettura del Sistema

```
                          ┌─────────────────────────────────────┐
                          │   J.A.R.V.I.S. PWA Client (HUD)     │
                          │   Android Standalone / Desktop      │
                          └──────────────────┬──────────────────┘
                                             │ Web Speech API / TTS
                                             │ Web Audio API (FFT Spectrum)
                                             │ Screen Wake Lock & Battery API
                                             ▼
                          ┌─────────────────────────────────────┐
                          │     FastAPI Core Server (8000)      │
                          └──────────┬───────────────┬──────────┘
                                     │               │
                 ┌───────────────────┴───┐       ┌───┴───────────────────┐
                 │    Online Tools       │       │    Local Intelligence │
                 │ ───────────────────── │       │ ───────────────────── │
                 │ • DuckDuckGo Search   │       │ • nanoGPT Transformer │
                 │ • wttr.in Weather     │       │   (Andrej Karpathy)   │
                 │ • Safe Math Engine    │       │ • Device Dispatcher   │
                 │ • OpenAI / xAI Grok   │       │   (Spotify/WhatsApp)  │
                 └───────────────────────┘       └───────────────────────┘
```

---

## ✨ Funzionalità Avanzate

### 1. 📱 PWA & Esperienza Mobile Android
* **Standalone Fullscreen**: `manifest.json` configurato senza barre di navigazione del browser per un'esperienza a schermo intero nativa.
* **Screen Wake Lock API**: mantiene il display dello smartphone costantemente acceso durante le sessioni operative.
* **Battery Status API**: telemetria reale in tempo reale sulla carica e stato di alimentazione del dispositivo.
* **Parallasse Olografico 3D**: sfrutta il giroscopio dello smartphone (`DeviceOrientation`) e il movimento del mouse su desktop per inclinare il Reattore Arc in 3D.
* **Service Worker**: architettura Stale-While-Revalidate per funzionamento istantaneo offline.

### 2. 🎙️ Riconoscimento Vocale & Audio Spettrale
* **Web Speech API Streaming**: ascolto continuo con riavvio automatico tramite watchdog resiliente contro i timeout di Chrome Mobile.
* **Rilevamento Wake-Word**: pronuncia *"Jarvis"*, *"Ehi Jarvis"*, *"Hey Jarvis"*, *"Ok Jarvis"*, o tocca il Reattore Arc centrale.
* **Spettrogramma FFT Web Audio API**: 16 bande di frequenza analizzate dal vivo sul microfono per far pulsare il reattore al ritmo della voce.
* **Sintesi Vocale con Anti-Freeze**: suddivisione intelligente in periodi frasali (chunking) per prevenire il blocco del TTS nativo su Android. Supporto per ElevenLabs API opzionale.

### 3. 🧠 Multi-Tool & Intelligenza Ibrida (Cloud + Offline)
* **DuckDuckGo Web Search**: ricerca istantanea sul web con citazioni cliccabili delle fonti.
* **Meteo Live Globale**: dati meteorologici in tempo reale da qualsiasi città del mondo.
* **Calcolatrice Quantistica Sicura**: esecuzione istantanea di espressioni matematiche complesse.
* **Deep Linking Dispositivo**:
  - **Spotify**: apertura diretta e riproduzione brani (`spotify:search:...`).
  - **WhatsApp**: composizione diretta chat e messaggi (`wa.me/...`).
  - **Chiamate**: compositore telefonico nativo (`tel:...`).
  - **Google Maps**: navigazione satellitare verso qualsiasi destinazione.
* **Motore Neurale nanoGPT (Andrej Karpathy)**: rete neurale Transformer GPT-2 addestrata su dialoghi Stark, in esecuzione locale tramite PyTorch in modalità 100% offline.

---

## 🛠️ Come Avviare il Sistema

### Passo 1: Installazione Dipendenze
Apri un terminale nella cartella del progetto:
```powershell
pip install fastapi uvicorn openai ddgs python-dotenv torch tiktoken transformers
```

### Passo 2: Avvio del Server Core
```powershell
python server.py
```
Il server si avvia su tutte le interfacce di rete (`http://0.0.0.0:8000`).

### Passo 3: Accesso su Desktop & Mobile
* **Da PC (Desktop)**: apri il browser su [http://localhost:8000](http://localhost:8000).
* **Da Smartphone Android (stessa rete Wi-Fi)**:
  1. Trova l'indirizzo IP locale del tuo PC (es. `192.168.1.X` o `192.168.178.158`).
  2. Apri Chrome su Android all'indirizzo: `http://192.168.178.158:8000`.
  3. Tocca il menu di Chrome (tre puntini) e seleziona **"Aggiungi a schermata Home"** o **"Installa applicazione"**.
  4. L'app si aprirà come vera applicazione a schermo intero!

---

## 💬 Esempi di Comandi Vocali

| Direttiva | Azione Eseguita |
| :--- | :--- |
| *"Jarvis, che tempo fa a Roma?"* | Rileva meteo, temperatura e vento in tempo reale |
| *"Jarvis, cerca sul web le ultime notizie su SpaceX"* | Esegue ricerca live DuckDuckGo con fonti e link |
| *"Jarvis, calcola radice quadrata di 144 per 12"* | Esegue il calcolo nei processori quantistici |
| *"Jarvis, metti i Queen su Spotify"* | Avvia l'app nativa Spotify sul brano/artista |
| *"Jarvis, manda un messaggio WhatsApp a Marco"* | Prepara il protocollo di trasmissione WhatsApp |
| *"Jarvis, portami al Colosseo su Google Maps"* | Carica le coordinate su Google Maps |
| *"Jarvis, quanta batteria abbiamo?"* | Legge i sensori energetici dello smartphone |
| *"Jarvis, mantieni lo schermo acceso"* | Attiva Screen Wake Lock del display |
| *"Jarvis, chi sei?"* | Dialogo conversazionale nativo (Stark Butler persona) |

---

## ⚙️ Configurazione & Chiavi API (Opzionale)

J.A.R.V.I.S. funziona **immediatamente senza alcuna chiave API** grazie agli intent euristici, a DuckDuckGo, a wttr.in e al modello PyTorch nanoGPT locale.

Se desideri abilitare anche i modelli cloud più avanzati (OpenAI GPT-4o o xAI Grok):
1. Crea un file `.env` copiando `.env.example`:
```env
LLM_API_KEY=sk-...
LLM_MODEL=gpt-4o
```
2. Oppure configurali direttamente dall'icona delle impostazioni ⚙️ nell'HUD della Web App.

---
*Progettato e sviluppato per Stark Industries // OS-7 Architecture.*
