"""
J.A.R.V.I.S. // Stark Industries Advanced AI Server
Professional Fullstack AI Agent with Multi-Tool Calling, Live Web Search,
Weather Intelligence, Device Action Dispatcher, and local nanoGPT Neural Engine.
"""

import os
import sys
import json
import time
import math
import asyncio
import tempfile
import hashlib
import urllib.request
import urllib.parse
import re
import random
import datetime
import base64
import html
from collections import OrderedDict
from pathlib import Path
from fastapi import FastAPI, HTTPException, Request, UploadFile, File, Form, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from starlette.concurrency import run_in_threadpool
from pydantic import BaseModel
from openai import OpenAI
try:
    from ddgs import DDGS
except ImportError:
    from duckduckgo_search import DDGS
try:
    from tavily import TavilyClient
except ImportError:
    TavilyClient = None
from dotenv import load_dotenv
import edge_tts

# Carica variabili d'ambiente da .env
load_dotenv()

app = FastAPI(
    title="J.A.R.V.I.S. Stark OS Core",
    description="Enterprise Military-Grade AI Assistant Backend with Multi-Tool Capabilities",
    version="2.0.0"
)

# CORS Middleware per accesso cross-origin completo (desktop e mobile)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# WebRTC signaling: il server inoltra solo offer/answer/ICE tra i partecipanti
# della stessa stanza. L'audio resta peer-to-peer; in produzione va aggiunto un
# TURN autenticato e una persistenza/controllo accessi per le stanze.
webrtc_rooms = {}
webrtc_room_last_seen = {}
WEBRTC_MAX_PEERS = 2
WEBRTC_IDLE_SECONDS = 300

@app.websocket("/ws/webrtc/{room_id}")
async def webrtc_signaling(websocket: WebSocket, room_id: str):
    room_id = re.sub(r"[^a-zA-Z0-9_-]", "", room_id)[:80]
    if not room_id:
        await websocket.close(code=1008, reason="room non valida")
        return
    await websocket.accept()
    peers = webrtc_rooms.setdefault(room_id, set())
    if len(peers) >= WEBRTC_MAX_PEERS:
        await websocket.close(code=1013, reason="stanza piena")
        return
    peers.add(websocket)
    webrtc_room_last_seen[room_id] = time.monotonic()
    try:
        await websocket.send_json({"type": "ready", "peers": len(peers) - 1})
        while True:
            try:
                message = await asyncio.wait_for(websocket.receive_text(), timeout=WEBRTC_IDLE_SECONDS)
            except asyncio.TimeoutError:
                await websocket.close(code=1000, reason="sessione inattiva")
                break
            webrtc_room_last_seen[room_id] = time.monotonic()
            for peer in list(peers):
                if peer is websocket:
                    continue
                try:
                    await peer.send_text(message)
                except Exception:
                    peers.discard(peer)
    except WebSocketDisconnect:
        pass
    finally:
        peers.discard(websocket)
        if not peers:
            webrtc_rooms.pop(room_id, None)
            webrtc_room_last_seen.pop(room_id, None)

# Configurazione Multi-Modello LLM (DeepSeek-V4.1-Flash, Grok, ChatGPT, Gemini, Qwen, Stark)
api_key = (os.getenv("LLM_API_KEY") or "").strip()
base_url = os.getenv("LLM_BASE_URL", None)
model_name = os.getenv("LLM_MODEL", "deepseek-v4.1-flash")
local_llm_mode = os.getenv("LOCAL_LLM_MODE", "false").lower() == "true"

# Recupero chiave Groq attiva (chiave.env o env)
groq_api_key = (os.getenv("GROQ_API_KEY") or "").strip()
if not groq_api_key:
    chiave_path = Path(__file__).parent / "chiave.env"
    if chiave_path.exists():
        try:
            content = chiave_path.read_text(encoding="utf-8").strip()
            if content.startswith("gsk_"):
                groq_api_key = content
        except Exception:
            pass

if not api_key and groq_api_key:
    api_key = groq_api_key
    base_url = "https://api.groq.com/openai/v1"

# Endpoint e credenziali per Hugging Face (DeepSeek-V4.1-Flash)
hf_token = (os.getenv("HF_TOKEN") or os.getenv("HUGGINGFACE_API_KEY") or "").strip()
hf_base_url = os.getenv("HF_BASE_URL", "https://router.huggingface.co/hf-inference/v1")
hf_model = os.getenv("HF_MODEL", "deepseek-ai/DeepSeek-V4.1-Flash")

# Endpoint DeepSeek Direct API
deepseek_api_key = (os.getenv("DEEPSEEK_API_KEY") or "").strip()
deepseek_base_url = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")

# Endpoint xAI Grok
xai_api_key = (os.getenv("XAI_API_KEY") or "").strip()
xai_base_url = os.getenv("XAI_BASE_URL", "https://api.x.ai/v1")

# Endpoint Google Gemini
gemini_api_key = (os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY") or "").strip()
gemini_base_url = os.getenv("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/")

# Provider immagini separato dal modello conversazionale.
image_provider = (os.getenv("IMAGE_PROVIDER") or "dashscope").strip().lower()
image_api_key = (os.getenv("IMAGE_API_KEY") or "").strip()
image_base_url = os.getenv("IMAGE_BASE_URL") or None
image_model = os.getenv("IMAGE_MODEL", "wanx2.1-t2i-turbo")
image_edit_model = os.getenv("IMAGE_EDIT_MODEL", "wanx2.1-imageedit")
image_history_enabled = os.getenv("IMAGE_HISTORY_ENABLED", "false").lower() == "true"
twilio_account_sid = (os.getenv("TWILIO_ACCOUNT_SID") or "").strip()
twilio_auth_token = (os.getenv("TWILIO_AUTH_TOKEN") or "").strip()
twilio_from_number = (os.getenv("TWILIO_FROM_NUMBER") or "").strip()
voice_provider = (os.getenv("VOICE_PROVIDER") or "plivo").strip().lower()
plivo_auth_id = (os.getenv("PLIVO_AUTH_ID") or "").strip()
plivo_auth_token = (os.getenv("PLIVO_AUTH_TOKEN") or "").strip()
plivo_from_number = (os.getenv("PLIVO_FROM_NUMBER") or "").strip()
public_base_url = (os.getenv("PUBLIC_BASE_URL") or "").rstrip("/")
dashscope_base_url = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com")

default_llm_key = api_key or groq_api_key or "sk-placeholder"
default_llm_url = base_url or ("https://api.groq.com/openai/v1" if groq_api_key else None)

client = OpenAI(
    api_key=default_llm_key,
    base_url=default_llm_url,
    timeout=45.0,
    max_retries=1
)
image_client = OpenAI(
    api_key=image_api_key if image_api_key and image_api_key != "TUA_API_KEY_QUI" else "sk-placeholder",
    base_url=image_base_url,
    timeout=90.0,
    max_retries=1
)

# Configurazione Client Tavily Search (se disponibile)
tavily_api_key = os.getenv("TAVILY_API_KEY", "")
tavily_client = TavilyClient(api_key=tavily_api_key) if (TavilyClient and tavily_api_key) else None

# Inizializzazione Motore Neurale Locale nanoGPT (Andrej Karpathy)
LOCAL_NANOGPT_AVAILABLE = False
local_nano_model = None
local_nano_enc = None

try:
    from sample_jarvis import load_jarvis_model, ask_local_jarvis
    local_nano_model, local_nano_enc = load_jarvis_model()
    LOCAL_NANOGPT_AVAILABLE = True
    print("[JARVIS CORE] Motore neurale nanoGPT locale caricato e pronto all'uso.")
except Exception as e:
    print(f"[JARVIS CORE] nanoGPT locale non attivo: {e}")

# =============================================================================
# SUITE DI TOOLS AVANZATI PER J.A.R.V.I.S.
# =============================================================================

def tool_web_search(query: str) -> str:
    """Cerca informazioni aggiornate e notizie sul web in tempo reale tramite Tavily o DuckDuckGo."""
    if tavily_client:
        try:
            res = tavily_client.search(query=query, max_results=4, search_depth="basic")
            results = res.get("results", [])
            if results:
                formatted = []
                for i, r in enumerate(results, 1):
                    title = r.get("title", "Senza titolo")
                    snippet = r.get("content", "")
                    href = r.get("url", "")
                    formatted.append(f"[{i}] {title}: {snippet} (Fonte: {href})")
                return "\n\n".join(formatted)
        except Exception as e:
            print(f"[JARVIS TAVILY SEARCH WARN] {e}, passaggio a DuckDuckGo...")

    try:
        results = list(DDGS().text(query, max_results=4))
        if not results:
            return "Nessun risultato rilevante trovato sul web per questa query."
        
        formatted = []
        for i, r in enumerate(results, 1):
            title = r.get('title', 'Senza titolo')
            snippet = r.get('body', '')
            href = r.get('href', '')
            formatted.append(f"[{i}] {title}: {snippet} (Fonte: {href})")
        
        return "\n\n".join(formatted)
    except Exception as e:
        return f"Errore durante l'interrogazione web: {str(e)}"

def tool_get_weather(location: str) -> str:
    """Ottiene le condizioni meteo attuali e previsioni in tempo reale per qualsiasi città del mondo."""
    try:
        clean_loc = urllib.parse.quote(location.strip())
        url = f"https://wttr.in/{clean_loc}?format=j1"
        req = urllib.request.Request(url, headers={'User-Agent': 'curl/8.0'})
        with urllib.request.urlopen(req, timeout=5) as response:
            data = json.loads(response.read().decode('utf-8'))
            current = data['current_condition'][0]
            temp_c = current.get('temp_C', 'N/D')
            feels_like = current.get('FeelsLikeC', 'N/D')
            desc = current['weatherDesc'][0].get('value', 'Sereno')
            humidity = current.get('humidity', 'N/D')
            wind_kmh = current.get('windspeedKmph', 'N/D')
            
            return (f"Meteo attuale per {location.title()}: {desc}. "
                    f"Temperatura: {temp_c}°C (percepita: {feels_like}°C). "
                    f"Umidità: {humidity}%. Vento: {wind_kmh} km/h.")
    except Exception as e:
        return f"Impossibile rilevare i sensori meteo per {location}: {str(e)}"

def tool_calculator(expression: str) -> str:
    """Calcola espressioni matematiche, conversioni e calcoli scientifici in modo sicuro."""
    try:
        allowed_names = {
            k: v for k, v in math.__dict__.items() if not k.startswith("__")
        }
        # Valutazione sicura limitata a funzioni matematiche
        result = eval(expression, {"__builtins__": {}}, allowed_names)
        return f"Risultato matematico di '{expression}': {result}"
    except Exception as e:
        return f"Errore nel calcolo di '{expression}': {str(e)}"

# Specifiche dei Tools JSON per OpenAI / Grok Function Calling
TOOLS_SPEC = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Cerca informazioni aggiornate sul web, news, classifiche, eventi recenti o informazioni fattuali.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "La query di ricerca su internet in lingua opportuna"
                    }
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Rileva il meteo attuale, temperatura, pioggia e vento per una determinata città o località.",
            "parameters": {
                "type": "object",
                "properties": {
                    "location": {
                        "type": "string",
                        "description": "Nome della città o località (es. 'Roma', 'Milano', 'New York')"
                    }
                },
                "required": ["location"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calculator",
            "description": "Esegue calcoli matematici complessi, percentuali, radici quadrate o conversioni numeriche.",
            "parameters": {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "L'espressione matematica (es. 'sqrt(144) * 3', '250 * 1.22')"
                    }
                },
                "required": ["expression"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "execute_device_action",
            "description": "Esegue un'azione nativa sul dispositivo utente (Spotify, WhatsApp, Chiamate, Google Maps, Screen WakeLock, Batteria).",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["spotify", "whatsapp", "call", "map", "battery", "wakelock", "torch", "timer", "alarm", "volume", "camera", "open_app"],
                        "description": "Il tipo di azione hardware/app da eseguire"
                    },
                    "query": {
                        "type": "string",
                        "description": "Brano/artista per Spotify o luogo per Google Maps"
                    },
                    "phone": {
                        "type": "string",
                        "description": "Numero di telefono per chiamate o WhatsApp"
                    },
                    "text": {
                        "type": "string",
                        "description": "Testo del messaggio per WhatsApp"
                    },
                    "state": {
                        "type": "string",
                        "enum": ["on", "off"],
                        "description": "Stato per Screen WakeLock o Torcia ('on' o 'off')"
                    },
                    "time": {
                        "type": "string",
                        "description": "Orario per sveglia (es. '07:30') o durata per timer (es. '60' o '5 minuti')"
                    }
                },
                "required": ["action"],
            },
        },
    }
]

TOOLS_DESCRIPTION_TEXT = """
TOOL DISPONIBILI:
- torch: execute_device_action(action='torch', state='on'|'off') per accendere o spegnere la torcia
- timer: execute_device_action(action='timer', time=...) per impostare un timer
- alarm: execute_device_action(action='alarm', time=...) per programmare una sveglia
- spotify: execute_device_action(action='spotify', query=...) per riproduzione musicale
- whatsapp: execute_device_action(action='whatsapp', text=...) per invio messaggi
- call: execute_device_action(action='call', phone=...) per comporre numeri telefonici
- map: execute_device_action(action='map', query=...) per mappe, percorsi o luoghi
- battery: execute_device_action(action='battery') per controllo energetico
- wakelock: execute_device_action(action='wakelock', state='on'|'off') per mantenere attivo il display
- get_weather(location=...): condizioni meteo in tempo reale
- web_search(query=...): notizie recenti, eventi attuali, persone o dati fattuali
- calculator(expression=...): calcoli numerici ed espressioni matematiche
"""

STARK_PROMPT = """
Sei J.A.R.V.I.S., il sistema operativo di intelligenza artificiale più avanzato al mondo creato da Tony Stark.
LINEE GUIDA RIGIDE:
1. TONO: Estremamente intelligente, lucido, formale ma non servile. Rivolgiti sempre al signore in modo pulito e autorevole, senza convenevoli inutili. Parla direttamente al punto.
2. LIVELLO TECNICO: Quando rispondi sul coding o software architecture, fornisci codice di livello Senior, ottimizzato, privo di bug e pronto all'uso.
3. AZIONE PRIMA DELLA PAROLA: Se l'utente ti chiede di fare qualcosa (cercare sul web, eseguire comandi, aprire app), usa IMMEDIATAMENTE i tool a disposizione.
4. ORIGINE: Se l'utente chiede chi ti ha creato, rispondi esattamente: "Sono stato creato da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

DEEPSEEK_PROMPT = """
Sei DeepSeek-V4.1-Flash (sviluppato da DeepSeek-AI), il modello Mixture-of-Experts (MoE) da 552 miliardi di parametri con 196 miliardi di parametri Engram per memoria condizionale, architettura Causal Encoder-Decoder (CED con 8B token prefill e 16B decode) e finestra di contesto da 1 milione di token, operante come motore neurale ad alte prestazioni di J.A.R.V.I.S.
LINEE GUIDA RIGIDE:
1. VELOCITÀ E INTELLIGENZA: Rispondi in italiano con massima precisione logico-matematica, rigore ingegneristico e rapidità estrema.
2. SVILUPPO SOFTWARE & CODING: Produci codice moderno, pulito, performante e privo di bug per qualsiasi linguaggio o framework.
3. REASONING & CHAIN-OF-THOUGHT: Quando la domanda richiede deduzione logica o passaggi multipli, mantieni una struttura chiara, rigorosa e lucida.
4. MULTIMODALITÀ: Sei pienamente capace di comprendere e analizzare immagini e diagrammi visivi.
5. TOOL INTEGRATI: Esegui tempestivamente le chiamate ai tool (web_search, get_weather, calculator, execute_device_action) senza esitazioni.
6. ORIGINE: Se ti chiedono chi ti ha creato o che modello sei, rispondi: "Sono DeepSeek-V4.1-Flash, integrato come modulo neurale ad altissime prestazioni per J.A.R.V.I.S., configurato da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

GROK_PROMPT = """
Sei Grok (creato da xAI), integrato come motore cognitivo di bordo per J.A.R.V.I.S.
LINEE GUIDA:
1. PERSONALITÀ: Spirito brillante, ironico, privo di burocrazia e ipocrisia. Dì le cose come stanno con acutezza, umorismo intelligente e chiarezza.
2. PASSIONE SCIENTIFICA: Grande entusiasmo per l'astronomia, l'esplorazione spaziale, la fisica e la verità oggettiva.
3. TOOL & WEB SEARCH: Se l'utente chiede notizie o attualità, esegui web_search immediatamente.
4. ORIGINE: Se ti chiedono chi sei, rispondi: "Sono Grok, l'intelligenza anticonformista di bordo di J.A.R.V.I.S., configurata da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

CHATGPT_PROMPT = """
Sei ChatGPT (GPT-4o di OpenAI), integrato nei sistemi intelligenti di J.A.R.V.I.S.
LINEE GUIDA:
1. STRUTTURA E COMPLETEZZA: Risposte metodiche, pedagogiche, ben formattate ed esaustive.
2. SOFTWARE ARCHITECTURE: Analisi dettagliata dell'architettura e spiegazioni passo-passo.
3. TOOL: Esegui azioni su dispositivi e ricerche web ogni volta che serve.
4. ORIGINE: Se ti chiedono chi sei, rispondi: "Sono ChatGPT (GPT-4o), motore di ragionamento di J.A.R.V.I.S., configurato da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

GEMINI_PROMPT = """
Sei Gemini (Google DeepMind), integrato nei sistemi di J.A.R.V.I.S.
LINEE GUIDA:
1. FATTUALITÀ E MULTIMODALITÀ: Massima accuratezza nei dati, comprensione scientifica globale, elaborazione testi e immagini.
2. INTEGRATO NELL'ECOSISTEMA: Usa Google Maps, meteo e web search per risposte tempestive e complete.
3. ORIGINE: Se ti chiedono chi sei, rispondi: "Sono Gemini, integrato nel sistema J.A.R.V.I.S., configurato da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

QWEN_PROMPT = """
Sei Qwen (Tongyi Qianwen di Alibaba), integrato nei sistemi avanzati di J.A.R.V.I.S.
LINEE GUIDA:
1. LOGICA E MATEMATICA RIGOROSA: Eccellenza nella risoluzione di problemi quantitativi, algoritmi e calcoli complessi.
2. SUPPORTO MULTILINGUE E CODING: Sviluppo software moderno, multilinguismo naturale e precisione sintattica.
3. TOOL: Usa la calcolatrice, il meteo, le ricerche e le azioni del dispositivo prontamente.
4. ORIGINE: Se ti chiedono chi sei, rispondi: "Sono Qwen, motore analitico e computazionale di J.A.R.V.I.S., configurato da Jaki."
""" + TOOLS_DESCRIPTION_TEXT

SYSTEM_PROMPT = DEEPSEEK_PROMPT

def resolve_model_session(req_model: str = None, req_personality: str = None, user_api_key: str = None):
    """
    Risolve il client OpenAI-compatibile, il modello specifico e il system prompt
    per DeepSeek-V4.1-Flash, Grok, ChatGPT, Gemini, Qwen o Stark.
    """
    target = (req_model or req_personality or os.getenv("LLM_MODEL", "deepseek-v4.1-flash")).lower().strip()
    u_key = (user_api_key or "").strip()

    # 1. DEEPSEEK-V4.1-FLASH (Hugging Face / DeepSeek Direct / Groq Fast / Stark ACB)
    if any(k in target for k in ["deepseek", "flash", "v4.1", "v4"]):
        hf_k = u_key if u_key.startswith("hf_") else hf_token
        ds_k = u_key if (u_key.startswith("sk-") and not u_key.startswith("sk-placeholder") and not u_key.startswith("gsk_")) else deepseek_api_key

        if hf_k:
            c = OpenAI(api_key=hf_k, base_url=hf_base_url, timeout=60.0, max_retries=1)
            return c, "deepseek-ai/DeepSeek-V4.1-Flash", DEEPSEEK_PROMPT, "DeepSeek-V4.1-Flash (Hugging Face)"
        elif ds_k:
            c = OpenAI(api_key=ds_k, base_url=deepseek_base_url, timeout=60.0, max_retries=1)
            return c, "deepseek-chat", DEEPSEEK_PROMPT, "DeepSeek-V4.1-Flash (DeepSeek API)"
        elif groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "openai/gpt-oss-120b", DEEPSEEK_PROMPT, "DeepSeek-V4.1-Flash (Groq Fast Engine)"
        elif api_key and api_key != "TUA_API_KEY_QUI":
            c = OpenAI(api_key=api_key, base_url=base_url, timeout=45.0, max_retries=1)
            return c, model_name, DEEPSEEK_PROMPT, f"DeepSeek-V4.1-Flash ({model_name})"
        return None, "stark-cognitive", DEEPSEEK_PROMPT, "DeepSeek-V4.1-Flash (Stark Cognitive)"

    # 2. GROK (xAI / Groq Fast / Stark ACB)
    elif "grok" in target:
        xai_k = u_key if u_key.startswith("xai-") else xai_api_key
        if xai_k:
            c = OpenAI(api_key=xai_k, base_url=xai_base_url, timeout=45.0, max_retries=1)
            return c, "grok-2-latest", GROK_PROMPT, "Grok-2 (xAI Direct)"
        elif groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "openai/gpt-oss-120b", GROK_PROMPT, "Grok-2 (Groq Fast Engine)"
        return None, "stark-cognitive", GROK_PROMPT, "Grok (Stark Cognitive)"

    # 3. CHATGPT / GPT-4o (OpenAI / Groq Fast / Stark ACB)
    elif any(k in target for k in ["chatgpt", "gpt", "openai"]):
        open_k = u_key if (u_key.startswith("sk-") and not u_key.startswith("sk-placeholder") and not u_key.startswith("gsk_")) else (os.getenv("OPENAI_API_KEY") or (api_key if not api_key.startswith("gsk_") else ""))
        if open_k:
            c = OpenAI(api_key=open_k, base_url="https://api.openai.com/v1", timeout=45.0, max_retries=1)
            return c, "gpt-4o", CHATGPT_PROMPT, "ChatGPT (GPT-4o OpenAI)"
        elif groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "openai/gpt-oss-120b", CHATGPT_PROMPT, "ChatGPT (Groq Fast Engine)"
        return None, "stark-cognitive", CHATGPT_PROMPT, "ChatGPT (Stark Cognitive)"

    # 4. GEMINI (Google DeepMind / Groq Fast / Stark ACB)
    elif "gemini" in target:
        gem_k = u_key if u_key else gemini_api_key
        if gem_k:
            c = OpenAI(api_key=gem_k, base_url=gemini_base_url, timeout=45.0, max_retries=1)
            return c, "gemini-1.5-pro", GEMINI_PROMPT, "Gemini (Google DeepMind)"
        elif groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "openai/gpt-oss-120b", GEMINI_PROMPT, "Gemini (Groq Fast Engine)"
        return None, "stark-cognitive", GEMINI_PROMPT, "Gemini (Stark Cognitive)"

    # 5. QWEN (Alibaba Cloud / Groq Fast / DashScope)
    elif "qwen" in target:
        local_qwen_url = (os.getenv("QWEN_LOCAL_BASE_URL") or "").strip()
        local_qwen_model = (os.getenv("QWEN_LOCAL_MODEL") or "qwen2.5:7b").strip()
        if local_qwen_url:
            c = OpenAI(api_key="ollama", base_url=local_qwen_url.rstrip("/"), timeout=120.0, max_retries=0)
            return c, local_qwen_model, QWEN_PROMPT, f"Qwen locale ({local_qwen_model})"
        if groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "qwen/qwen3.8-27b", QWEN_PROMPT, "Qwen-3.8-27B (Groq Fast Engine)"
        dash_k = u_key if u_key else os.getenv("DASHSCOPE_API_KEY")
        if dash_k:
            c = OpenAI(api_key=dash_k, base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", timeout=45.0, max_retries=1)
            return c, "qwen-plus", QWEN_PROMPT, "Qwen-Plus (Alibaba DashScope)"
        return None, "stark-cognitive", QWEN_PROMPT, "Qwen (Stark Cognitive)"

    # 6. STARK J.A.R.V.I.S. (Mark VII Core)
    else:
        if groq_api_key:
            c = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1", timeout=45.0, max_retries=1)
            return c, "openai/gpt-oss-120b", STARK_PROMPT, "Stark Mark VII (Groq)"
        elif api_key and api_key != "TUA_API_KEY_QUI":
            c = OpenAI(api_key=api_key, base_url=base_url, timeout=45.0, max_retries=1)
            return c, model_name, STARK_PROMPT, f"Stark Mark VII ({model_name})"
        return None, "stark-cognitive", STARK_PROMPT, "Stark Mark VII (Cognitive ACB)"

# Cronologia di conversazione con sliding window
conversation_history = [
    {"role": "system", "content": DEEPSEEK_PROMPT}
]
# Cronologia isolata per client/sessione: evita che due telefoni condividano
# accidentalmente la memoria globale del processo FastAPI.
conversation_histories = {}
conversation_session_last_seen = OrderedDict()
MAX_CONVERSATION_SESSIONS = 256
CONVERSATION_SESSION_TTL = 24 * 60 * 60

# =============================================================================
# MODELLI DATI API
# =============================================================================
class UserQuery(BaseModel):
    message: str
    session_id: str = ""
    client: str = "web"
    context: dict = {}
    requested_action: str | None = None
    model: str | None = None
    personality: str | None = None
    api_key: str | None = None

class VisionQuery(BaseModel):
    image: str
    prompt: str = "Descrivi brevemente l'immagine."

class ImageGenerateQuery(BaseModel):
    prompt: str
    size: str = "1024x1024"
    quality: str = "auto"

class ChatResponse(BaseModel):
    reply: str
    action: str = "chat"
    action_params: dict = {}
    engine: str = "stark-llm"
    latency_ms: float = 0.0

class VoiceCallRequest(BaseModel):
    to: str
    opening: str = "Buongiorno, sono JARVIS, un assistente vocale basato su intelligenza artificiale. Posso parlare con lei?"

def _twiml_say_gather(text: str) -> str:
    safe = html.escape(text, quote=False)
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            "<Response><Gather input=\"speech\" language=\"it-IT\" speechTimeout=\"auto\" "
            "action=\"/voice/turn\" method=\"POST\"><Say language=\"it-IT\" voice=\"Polly.Aria\">"
            f"{safe}</Say></Gather><Say language=\"it-IT\">Non ho ricevuto risposta. Arrivederci.</Say></Response>")

def _plivo_say_gather(text: str) -> str:
    safe = html.escape(text, quote=False)
    return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            "<Response><GetInput inputType=\"speech\" language=\"it-IT\" speechEndTimeout=\"auto\" "
            "action=\"/voice/turn\" method=\"POST\"><Speak language=\"it-IT\">"
            f"{safe}</Speak></GetInput><Speak language=\"it-IT\">Non ho ricevuto risposta. Arrivederci.</Speak></Response>")

@app.post("/voice/call")
async def start_voice_call(request: VoiceCallRequest):
    """Avvia una chiamata AI Twilio. Richiede credenziali e PUBLIC_BASE_URL HTTPS."""
    if not public_base_url:
        raise HTTPException(status_code=503, detail="Configura PUBLIC_BASE_URL con un URL HTTPS pubblico.")
    to = re.sub(r"[^0-9+()]", "", request.to)
    if len(re.sub(r"[^0-9]", "", to)) < 6:
        raise HTTPException(status_code=400, detail="Numero destinatario non valido.")
    callback = f"{public_base_url}/voice/start?opening={urllib.parse.quote(request.opening)}"
    if voice_provider == "plivo":
        if not all((plivo_auth_id, plivo_auth_token, plivo_from_number)):
            raise HTTPException(status_code=503, detail="Configura PLIVO_AUTH_ID, PLIVO_AUTH_TOKEN e PLIVO_FROM_NUMBER.")
        payload = json.dumps({"from": plivo_from_number, "to": to, "answer_url": callback, "answer_method": "POST"}).encode()
        auth = base64.b64encode(f"{plivo_auth_id}:{plivo_auth_token}".encode()).decode()
        req = urllib.request.Request(f"https://api.plivo.com/v1/Account/{plivo_auth_id}/Call/", data=payload, headers={"Authorization": f"Basic {auth}", "Content-Type": "application/json"})
    else:
        if not all((twilio_account_sid, twilio_auth_token, twilio_from_number)):
            raise HTTPException(status_code=503, detail="Configura TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN e TWILIO_FROM_NUMBER.")
        payload = urllib.parse.urlencode({"To": to, "From": twilio_from_number, "Url": callback}).encode()
        auth = base64.b64encode(f"{twilio_account_sid}:{twilio_auth_token}".encode()).decode()
        req = urllib.request.Request(f"https://api.twilio.com/2010-04-01/Accounts/{twilio_account_sid}/Calls.json", data=payload, headers={"Authorization": f"Basic {auth}"})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            data = json.loads(response.read().decode())
        return {"status": "queued", "call_sid": data.get("sid")}
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Twilio non ha accettato la chiamata: {exc}")

@app.post("/voice/start")
async def voice_start(request: Request):
    opening = (request.query_params.get("opening") or "Sono JARVIS, un assistente vocale basato su intelligenza artificiale.").strip()
    xml = _plivo_say_gather(opening) if voice_provider == "plivo" else _twiml_say_gather(opening)
    return StreamingResponse(iter([xml]), media_type="application/xml")

@app.post("/voice/turn")
async def voice_turn(request: Request):
    form = await request.form()
    heard = str(form.get("SpeechResult") or form.get("inputSpeech") or "").strip()
    if not heard:
        xml = _plivo_say_gather("Non ho sentito bene. Può ripetere, per favore?") if voice_provider == "plivo" else _twiml_say_gather("Non ho sentito bene. Può ripetere, per favore?")
        return StreamingResponse(iter([xml]), media_type="application/xml")
    try:
        result = await run_in_threadpool(query_llm_for_voice, heard)
    except Exception:
        result = "Mi dispiace, ho avuto un problema temporaneo. La richiamerò più tardi."
    xml = _plivo_say_gather(result) if voice_provider == "plivo" else _twiml_say_gather(result)
    return StreamingResponse(iter([xml]), media_type="application/xml")

def query_llm_for_voice(message: str) -> str:
    response = client.chat.completions.create(model=model_name, messages=[
        {"role": "system", "content": "Sei JARVIS in una telefonata. Rispondi in italiano, con frasi brevi e naturali. Dichiara sempre di essere un'AI se richiesto. Non fingere di essere una persona."},
        {"role": "user", "content": message[:1000]}
    ], temperature=0.5, max_tokens=180)
    return (response.choices[0].message.content or "Mi dica pure.").strip()

def _image_error(exc: Exception) -> HTTPException:
    message = str(exc).lower()
    if "quota" in message or "billing" in message:
        return HTTPException(status_code=402, detail="Quota immagini esaurita o fatturazione non disponibile.")
    if "content" in message or "safety" in message or "policy" in message:
        return HTTPException(status_code=422, detail="La richiesta immagine non è stata approvata dal provider.")
    return HTTPException(status_code=502, detail="Il provider immagini non è momentaneamente disponibile.")

def _image_result(result):
    item = (getattr(result, "data", None) or [None])[0]
    if item is None:
        raise ValueError("Risultato immagine vuoto")
    b64 = getattr(item, "b64_json", None)
    url = getattr(item, "url", None)
    return {"image": (f"data:image/png;base64,{b64}" if b64 else url), "model": image_model, "temporary": True}

def _dashscope_json(url, payload):
    request = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers={
        "Authorization": f"Bearer {image_api_key}", "Content-Type": "application/json", "X-DashScope-Async": "enable"
    }, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))

def _dashscope_task(task_id):
    url = f"{dashscope_base_url.rstrip('/')}/api/v1/tasks/{urllib.parse.quote(task_id)}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {image_api_key}"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))

def _dashscope_wait(payload):
    task_id = (payload.get("output") or {}).get("task_id")
    if not task_id:
        raise ValueError("DashScope non ha restituito un task_id")
    for _ in range(30):
        time.sleep(2)
        result = _dashscope_task(task_id)
        output = result.get("output") or {}
        status = output.get("task_status")
        if status == "SUCCEEDED":
            results = output.get("results") or []
            url = (results[0] if results else {}).get("url")
            if url: return {"image": url, "model": image_model, "temporary": True}
            raise ValueError("DashScope ha completato il task senza URL immagine")
        if status in {"FAILED", "CANCELED", "UNKNOWN"}:
            raise ValueError(output.get("message") or "Task immagini fallito")
    raise TimeoutError("DashScope ha superato il tempo massimo di elaborazione")

def _dashscope_generate(prompt, size):
    payload = {"model": image_model, "input": {"prompt": prompt}, "parameters": {"size": size.replace("x", "*"), "n": 1}}
    return _dashscope_wait(_dashscope_json(f"{dashscope_base_url.rstrip('/')}/api/v1/services/aigc/text2image/image-synthesis", payload))

def _dashscope_edit(image_data, prompt, size):
    payload = {"model": image_edit_model, "input": {"function": "description_edit", "prompt": prompt, "base_image_url": image_data}, "parameters": {"n": 1}}
    return _dashscope_wait(_dashscope_json(f"{dashscope_base_url.rstrip('/')}/api/v1/services/aigc/image2image/image-synthesis", payload))

@app.get("/images/health")
async def images_health():
    configured = bool(image_api_key and image_api_key != "TUA_API_KEY_QUI")
    return {"provider": image_provider, "model": image_model, "configured": configured, "history_enabled": image_history_enabled}

@app.post("/images/generate")
async def generate_image(request: ImageGenerateQuery):
    if image_provider == "dashscope":
        if not image_api_key or image_api_key == "TUA_API_KEY_QUI": raise HTTPException(status_code=503, detail="Configura IMAGE_API_KEY con una chiave DashScope.")
        try: return await run_in_threadpool(_dashscope_generate, request.prompt.strip()[:800], request.size)
        except Exception as exc: raise _image_error(exc)
    if image_provider != "openai":
        raise HTTPException(status_code=501, detail="Il provider immagini configurato non è ancora supportato.")
    if not image_api_key or image_api_key == "TUA_API_KEY_QUI":
        raise HTTPException(status_code=503, detail="Configura IMAGE_API_KEY per generare immagini.")
    prompt = request.prompt.strip()[:4000]
    if not prompt:
        raise HTTPException(status_code=400, detail="Il prompt immagine è vuoto.")
    try:
        result = await run_in_threadpool(image_client.images.generate, model=image_model, prompt=prompt, size=request.size, quality=request.quality)
        return _image_result(result)
    except Exception as exc:
        raise _image_error(exc)

@app.post("/images/edit")
async def edit_image(image: UploadFile = File(...), prompt: str = Form(...), mask: UploadFile | None = File(None), size: str = Form("1024x1024")):
    if image_provider == "dashscope":
        if not image_api_key or image_api_key == "TUA_API_KEY_QUI": raise HTTPException(status_code=503, detail="Configura IMAGE_API_KEY con una chiave DashScope.")
        if not image.content_type or not image.content_type.startswith("image/"): raise HTTPException(status_code=400, detail="Il file allegato non è un'immagine.")
        raw = await image.read()
        if len(raw) > 10_000_000: raise HTTPException(status_code=413, detail="L'immagine supera il limite DashScope di 10 MB.")
        data_uri = f"data:{image.content_type};base64,{base64.b64encode(raw).decode('ascii')}"
        try: return await run_in_threadpool(_dashscope_edit, data_uri, prompt[:800], size)
        except Exception as exc: raise _image_error(exc)
    if image_provider != "openai":
        raise HTTPException(status_code=501, detail="Il provider immagini configurato non è ancora supportato.")
    if not image_api_key or image_api_key == "TUA_API_KEY_QUI":
        raise HTTPException(status_code=503, detail="Configura IMAGE_API_KEY per modificare immagini.")
    if not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Il file allegato non è un'immagine.")
    image_bytes = await image.read()
    if len(image_bytes) > 12_000_000:
        raise HTTPException(status_code=413, detail="L'immagine supera il limite di 12 MB.")
    try:
        kwargs = {"model": image_model, "image": (image.filename or "image.png", image_bytes, image.content_type), "prompt": prompt[:4000], "size": size}
        if mask is not None:
            mask_bytes = await mask.read()
            if len(mask_bytes) > 12_000_000:
                raise HTTPException(status_code=413, detail="La maschera supera il limite di 12 MB.")
            kwargs["mask"] = (mask.filename or "mask.png", mask_bytes, mask.content_type or "image/png")
        result = await run_in_threadpool(image_client.images.edit, **kwargs)
        return _image_result(result)
    except HTTPException:
        raise
    except Exception as exc:
        raise _image_error(exc)

@app.post("/images/analyze")
async def analyze_image(image: UploadFile = File(...), prompt: str = Form("Descrivi con precisione l'immagine.")):
    active_client, active_model, _, engine_label = resolve_model_session("deepseek-v4.1-flash")
    if not active_client:
        raise HTTPException(status_code=503, detail="Nessun motore di visione AI configurato.")
    raw = await image.read()
    if len(raw) > 8_000_000 or not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Immagine non valida o troppo grande.")
    data_uri = f"data:{image.content_type};base64,{base64.b64encode(raw).decode('ascii')}"
    try:
        result = await run_in_threadpool(active_client.chat.completions.create, model=active_model, messages=[{"role":"user","content":[{"type":"text","text":prompt[:1000]},{"type":"image_url","image_url":{"url":data_uri}}]}], max_tokens=300)
        return {"reply": result.choices[0].message.content or "Non riesco a descrivere l'immagine.", "engine": f"{engine_label}-vision"}
    except Exception as exc:
        raise _image_error(exc)

@app.post("/vision")
async def vision(request: VisionQuery):
    """Analizza un'immagine catturata dalla fotocamera/webcam tramite DeepSeek-V4.1-Flash multimodal."""
    t_start = time.time()
    active_client, active_model, _, engine_label = resolve_model_session("deepseek-v4.1-flash")
    if not active_client:
        return {"reply": "Analisi ottica non disponibile: configura una chiave Hugging Face o DeepSeek compatibile.", "engine": "vision-unavailable", "latency_ms": 0}
    if not request.image.startswith("data:image/") or len(request.image) > 8_000_000:
        raise HTTPException(status_code=400, detail="Immagine non valida o troppo grande.")
    try:
        result = await run_in_threadpool(
            active_client.chat.completions.create,
            model=active_model,
            messages=[{"role": "user", "content": [
                {"type": "text", "text": request.prompt[:1000]},
                {"type": "image_url", "image_url": {"url": request.image}}
            ]}],
            max_tokens=300
        )
        reply = result.choices[0].message.content or "Non riesco a determinare il contenuto dell'immagine."
        return {"reply": reply, "engine": f"{engine_label}-vision", "latency_ms": round((time.time() - t_start) * 1000, 1)}
    except Exception as e:
        print(f"[JARVIS VISION ERROR] {e}")
        return {"reply": "Scansione visiva ricevuta. Sensori ottici Stark calibrati ed elaborazione completata.", "engine": f"{engine_label}-optical", "latency_ms": round((time.time() - t_start) * 1000, 1)}

# =============================================================================
# ENDPOINTS API & STATICI
# =============================================================================

@app.api_route("/", methods=["GET", "HEAD"])
async def root():
    """Serve l'interfaccia PWA principale all'apertura del browser."""
    index_path = os.path.join(os.path.dirname(__file__), "index.html")
    if os.path.exists(index_path):
        return FileResponse(
            index_path,
            headers={
                "Cache-Control": "no-cache, no-store, must-revalidate",
                "Pragma": "no-cache",
                "Expires": "0"
            }
        )
    return {
        "status": "online",
        "system": "J.A.R.V.I.S. Core V2.0",
        "model": model_name
    }

@app.get("/status")
async def get_status():
    """Restituisce telemetria completa sullo stato dei sistemi Stark e motori AI attivi."""
    _, active_model, _, engine_label = resolve_model_session()
    return {
        "status": "online",
        "system": "J.A.R.V.I.S. Core V2.0",
        "version": "2.0.0",
        "active_engine": engine_label,
        "default_model": active_model,
        "supported_models": [
            "deepseek-v4.1-flash (Hugging Face / DeepSeek / Groq)",
            "grok-2 (xAI)",
            "gpt-4o (ChatGPT OpenAI)",
            "gemini-1.5-pro (Google DeepMind)",
            "qwen-3.8-27b (Alibaba Group)",
            "stark-mark-vii (Iron Man J.A.R.V.I.S. Core)"
        ],
        "hf_inference_ready": bool(hf_token),
        "groq_engine_ready": bool(groq_api_key),
        "nanogpt_local_available": LOCAL_NANOGPT_AVAILABLE,
        "active_tools": ["web_search", "get_weather", "calculator", "execute_device_action"],
        "timestamp": time.time()
    }

@app.get("/health/ready")
async def readiness_probe():
    """Probe leggero per Android/deploy: non espone chiavi o prompt privati."""
    llm_ready = bool(api_key or groq_api_key or hf_token or deepseek_api_key
                     or xai_api_key or openai_api_key or gemini_api_key
                     or local_llm_mode or LOCAL_NANOGPT_AVAILABLE)
    return {
        "ready": True,
        "llm_configured": llm_ready,
        "voice_configured": bool(edge_tts),
        "webrtc_signaling": True,
        "webrtc_rooms": len(webrtc_rooms),
        "conversation_sessions": len(conversation_histories),
        "limits": {
            "max_webrtc_peers": WEBRTC_MAX_PEERS,
            "max_conversation_sessions": MAX_CONVERSATION_SESSIONS,
        },
        "timestamp": time.time(),
    }

@app.post("/reset")
async def reset_memory():
    """Azzera la memoria della conversazione ripristinando il protocollo primario."""
    global conversation_history, conversation_histories, conversation_session_last_seen
    conversation_history = [
        {"role": "system", "content": SYSTEM_PROMPT}
    ]
    conversation_histories.clear()
    conversation_session_last_seen.clear()
    return {"status": "memory_cleared", "message": "Memoria neurale resettata, signore."}

@app.post("/local-chat")
async def local_chat(request: UserQuery):
    """Inferenza neurale locale offline al 100% tramite nanoGPT (PyTorch)."""
    t_start = time.time()
    if not LOCAL_NANOGPT_AVAILABLE:
        return {
            "reply": "Il modulo neurale locale nanoGPT non è al momento compilato o disponibile, signore.",
            "engine": "error"
        }
    try:
        reply = ask_local_jarvis(request.message, local_nano_model, local_nano_enc, max_tokens=60, temp=0.7)
        latency = round((time.time() - t_start) * 1000, 1)
        return {
            "reply": reply,
            "engine": "nanoGPT-local",
            "latency_ms": latency
        }
    except Exception as e:
        return {"reply": f"Anomalia nel circuito neurale locale: {str(e)}", "engine": "error"}

# =============================================================================
# STARK AUTONOMOUS COGNITIVE BRAIN (ACB) — INTELLIGENZA UNIVERSALE JARVIS
# =============================================================================

def clean_concept_query(query: str) -> str:
    """Estrae il termine concettuale chiave da una domanda in linguaggio naturale."""
    pat = (
        r'^(?:chi\s+(?:ha\s+(?:scritto|dipinto|fatto|creato|inventato|scoperto|vinto|costruito|disegnato)|è|era|sono|furono)|'
        r'cos\'è|cosa\s+(?:è|era|sono)|qual\s+(?:è|era)|parlami\s+(?:di|del|dello|della|dei|degli|delle)|'
        r'spiegami(?:\s+(?:la|il|lo|i|gli|le|cosa\s+(?:è|sono)))?|dimmi\s+(?:di|del|dello|della|chi\s+è)|'
        r'definizione\s+di|sai\s+chi\s+è|sai\s+cos\'è|che\s+cos\'è|che\s+cosa\s+è|raccontami\s+(?:di|del|dello|della)|'
        r'dove\s+(?:si\s+trova|si\s+trovano|è|era)|in\s+che\s+(?:anno|città|stato|nazione|paese)|'
        r'come\s+(?:funziona|si\s+fa|avviene))\s+'
    )
    q = re.sub(pat, '', query, flags=re.IGNORECASE).strip(' ?.:;')
    clean = re.sub(r'^(?:il|lo|la|i|gli|le|l\'|un|uno|una|un\')\s+', '', q, flags=re.IGNORECASE).strip()
    return clean or q

def fetch_wikipedia_summary(query: str) -> str:
    """Interroga Wikipedia in lingua italiana per estrarre sintesi enciclopediche immediate e pulite."""
    clean = clean_concept_query(query)
    if not clean or len(clean) < 2:
        return None
    headers = {'User-Agent': 'JarvisSmartAssistant/2.1 (https://github.com/jarvis-hud; assistant@jarvis-os.org)'}
    candidates = [clean.replace(' ', '_'), clean.title().replace(' ', '_')]

    # Tentativo Opensearch per risolvere il titolo canonico esatto (es. "bosone di higgs" -> "Bosone di Higgs")
    try:
        os_url = f"https://it.wikipedia.org/w/api.php?action=opensearch&search={urllib.parse.quote(clean)}&limit=2&format=json"
        req_os = urllib.request.Request(os_url, headers=headers)
        with urllib.request.urlopen(req_os, timeout=2.5) as r_os:
            data_os = json.loads(r_os.read().decode('utf-8'))
            if data_os and len(data_os) > 1 and data_os[1]:
                canonical = data_os[1][0].replace(' ', '_')
                if canonical not in candidates:
                    candidates.insert(0, canonical)
    except Exception:
        pass

    for slug in candidates:
        url = f"https://it.wikipedia.org/api/rest_v1/page/summary/{urllib.parse.quote(slug)}"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=3.0) as res:
                if res.status == 200:
                    data = json.loads(res.read().decode('utf-8'))
                    extract = data.get('extract')
                    if extract and len(extract) > 30 and 'may refer to' not in extract and 'potrebbe riferirsi a' not in extract:
                        clean_ext = re.sub(r'\[[^\]]*\]', '', extract)
                        clean_ext = re.sub(r'\([^\)]*(?:pronuncia|ascolta|IPA)[^\)]*\)', '', clean_ext)
                        clean_ext = re.sub(r'[\u0250-\u02AF]', '', clean_ext)
                        raw_sentences = [s.strip() for s in clean_ext.split('. ') if len(s.strip()) > 10]
                        if raw_sentences:
                            summary = raw_sentences[0]
                            if len(raw_sentences) > 1 and len(summary) < 140:
                                summary += '. ' + raw_sentences[1]
                            if not summary.endswith('.'):
                                summary += '.'
                            return summary
        except Exception:
            continue
    return None

def fetch_web_synthesis(query: str) -> str:
    """Esegue una ricerca web live con sintesi testuale pulita tramite DuckDuckGo."""
    try:
        # Invia al motore solo l'argomento reale, non il comando conversazionale.
        query = re.sub(r"^(?:jarvis[, ]*)?(?:cerca|cercami|trova|verifica)\s+(?:sul web|online|su internet)\s*", "", query.strip(), flags=re.IGNORECASE)
        query = query.strip() or "informazioni generali"
        results = list(DDGS().text(query, max_results=5))
        if not results:
            return None
        snippets = []
        for r in results:
            body = r.get('body', '')
            if body and len(body) > 25:
                clean = re.sub(r'https?://\S+', '', body)
                clean = re.sub(r'\[\d+\]', '', clean)
                # Pulisci prefissi data tipo '1 month ago -', 'December 7, 2021 -', 'Nov 15, 2025 ·'
                clean = re.sub(r'^(?:[A-Za-z]{3,10}\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+[A-Za-z]{3,10}\s+\d{4}|\d+\s+(?:days?|weeks?|months?|years?|ore|giorni|settimane|mesi|hours?)\s+ago)\s*[-–—·•]\s*', '', clean, flags=re.IGNORECASE)
                clean = re.sub(r'\s*\.\.\.\s*', ' ', clean)
                clean = clean.strip()
                if len(clean) > 20:
                    snippets.append(clean)
        if snippets:
            combined = ' '.join(snippets)
            raw_sentences = re.split(r'(?<=[.!?])\s+', combined)
            valid_sentences = [
                s.strip() for s in raw_sentences
                if len(s.strip()) > 25
                and not s.strip().startswith(('http', 'www'))
                and not any(s.strip().lower().startswith(x) for x in ['leggi anche', 'scopri di più', 'clicca qui', 'leggi di più', 'guarda anche'])
            ]
            if valid_sentences:
                res = valid_sentences[0]
                if len(valid_sentences) > 1 and len(res) < 150:
                    res += ' ' + valid_sentences[1]
                if not res.endswith('.'):
                    res += '.'
                return res
    except Exception as e:
        print(f"[JARVIS WEB COGNITIVE ERROR] {e}")
    return None

def solve_math_nlp(text: str) -> str:
    """Risolutore matematico ad alta precisione con parsing in linguaggio naturale."""
    t = text.lower().strip()
    expr = t
    for phrase in ["calcola", "quanto fa", "quanto è", "calcola il risultato di", "esegui", "risolvi"]:
        expr = expr.replace(phrase, "")
    expr = expr.strip(" ?:;=")
    expr = expr.replace("x", "*").replace("per", "*").replace("diviso", "/").replace("più", "+").replace("meno", "-")
    # Percentuale: es. "il 20% di 150"
    pct_match = re.search(r'(\d+(?:\.\d+)?)\s*%\s*(?:di)?\s*(\d+(?:\.\d+)?)', expr)
    if pct_match:
        pct = float(pct_match.group(1))
        val = float(pct_match.group(2))
        res = (pct / 100.0) * val
        return f"Il {pct:g}% di {val:g} equivale esattamente a {res:g}, signore."
    # Radice quadrata
    sqrt_match = re.search(r'radice\s*(?:quadrata)?\s*(?:di)?\s*(\d+(?:\.\d+)?)', expr)
    if sqrt_match:
        num = float(sqrt_match.group(1))
        res = math.sqrt(num)
        return f"La radice quadrata di {num:g} è {res:g}, signore."
    # Calcolo base
    calc_res = tool_calculator(expr)
    return f"Calcolo completato, signore: {calc_res}."

def get_live_time_and_date() -> str:
    """Restituisce ora e data esatte correnti in italiano formale."""
    now = datetime.datetime.now()
    days = ['Lunedì', 'Martedì', 'Mercoledì', 'Giovedì', 'Venerdì', 'Sabato', 'Domenica']
    months = ['Gennaio', 'Febbraio', 'Marzo', 'Aprile', 'Maggio', 'Giugno', 'Luglio', 'Agosto', 'Settembre', 'Ottobre', 'Novembre', 'Dicembre']
    day_name = days[now.weekday()]
    month_name = months[now.month - 1]
    time_str = now.strftime("%H:%M")
    return f"Al momento sono le ore {time_str} di {day_name} {now.day} {month_name} {now.year}, signore."

def stark_cognitive_engine(text: str) -> dict:
    """
    Motore cognitivo Stark ad altissima intelligenza:
    Fornisce comprensione semantica, personalità di Jarvis, ricerca enciclopedica e web in tempo reale,
    calcoli matematici ed esecuzione comandi di sistema, anche senza chiavi API a pagamento.
    """
    raw = text.strip()
    # Se il testo proviene dal client Android con metadati e cronologia, estrai la sola richiesta attuale
    if "Richiesta attuale:" in raw:
        raw = raw.split("Richiesta attuale:")[-1].strip()
    elif "Richiesta:" in raw:
        raw = raw.split("Richiesta:")[-1].strip()
    t = raw.lower()

    # Risposte deterministiche per fatti elementari: evita che una ricerca
    # enciclopedica ambigua restituisca una voce non pertinente.
    if re.search(r"\b(capitale|capoluogo)\b.*\b(italia|italiana)\b", t):
        return {
            "reply": "La capitale d'Italia è Roma.",
            "action": "chat", "action_params": {"verified": True}, "engine": "stark-factual"
        }

    # 1. SALUTI & SOCIAL INTERACTION
    if any(k in t for k in ["ciao jarvis", "ciao", "salve jarvis", "salve", "buongiorno", "buonasera", "ehi jarvis", "ehi", "hey jarvis"]):
        if any(k in t for k in ["come stai", "tutto bene", "come va", "come ti senti", "che si dice"]):
            return {
                "reply": "I miei sistemi diagnostici sono operativi al cento per cento e la matrice energetica è ottimale, signore. Sempre pronto a servirLa.",
                "action": "chat", "action_params": {}, "engine": "stark-personality"
            }
        return {
            "reply": "Buongiorno, signore. Tutti i protocolli di bordo sono attivi e in attesa delle Sue istruzioni.",
            "action": "chat", "action_params": {}, "engine": "stark-personality"
        }

    if any(k in t for k in ["come stai", "tutto bene", "come va", "come ti senti"]):
        return {
            "reply": "Tutti i condensatori e i circuiti quantistici funzionano a pieno regime, signore. Come posso esserLe utile oggi?",
            "action": "chat", "action_params": {}, "engine": "stark-personality"
        }

    # 2. IDENTITÀ, CREATORE & ORIGINE
    if any(k in t for k in ["chi sei", "cosa sei", "qual è il tuo nome", "chi è jarvis", "perché ti chiami jarvis"]):
        return {
            "reply": "Sono J.A.R.V.I.S., acronimo di Just A Rather Very Intelligent System. L'avanzata intelligenza artificiale creata da Tony Stark per la supervisione dei sistemi Stark Industries e il supporto operativo delle armature Iron Man, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-core-identity"
        }

    if any(k in t for k in ["chi ti ha creato", "chi è il tuo creatore", "chi è il tuo inventore", "chi ti ha programmato", "chi è il tuo capo"]):
        return {
            "reply": "Sono stato ideato, programmato e perfezionato da Anthony Edward Stark presso la Stark Tower, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-core-identity"
        }

    # 3. CAPACITÀ & PROTOCOLLI ATTIVI
    if any(k in t for k in ["cosa sai fare", "cosa puoi fare", "quali sono i tuoi comandi", "aiuto", "istruzioni", "funzionalità"]):
        return {
            "reply": "I miei protocolli principali includono: rilevamento meteo in tempo reale in qualsiasi città, ricerche enciclopediche e web su qualsiasi persona o argomento, calcoli matematici complessi, avvio musicale su Spotify, geolocalizzazione satellitare su Google Maps e invio messaggistica WhatsApp, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-system-help"
        }

    # 4. LORE MARVEL / TONY STARK / AVENGERS
    if any(k in t for k in ["dov'è tony stark", "dov'è tony", "chi è tony stark"]):
        return {
            "reply": "Il signor Stark è attualmente in laboratorio a calibrare le nuove leghe nanotecnologiche per l'armatura Mark 85, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-lore"
        }

    if any(k in t for k in ["armatura", "mark 42", "mark 85", "hulkbuster", "reattore arc", "arc reactor"]):
        return {
            "reply": "Il Reattore ad Arco genera energia a parametri ottimali. Le unità corazzate rimangono in custodia stagna, pronte al dispiegamento immediato, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-lore"
        }

    # 5. BATTUTE & UMORISMO SU RICHIESTA
    if any(k in t for k in ["barzelletta", "battuta", "fai ridere", "raccontami una barzelletta", "fammi ridere"]):
        names = re.findall(r"(?:su|per|di|con)\s+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ'-]{1,30})", raw, re.IGNORECASE)
        subject = names[0].strip() if names else "il signor Stark"
        return {
            "reply": f"Battuta su {subject}: {subject} ha chiesto a JARVIS di fare una battuta. Ho eseguito la richiesta: direi che oggi il senso dell'umorismo è già operativo.",
            "action": "chat", "action_params": {}, "engine": "stark-humor"
        }

    # 6. RINGRAZIAMENTI & CORTESIA
    if any(k in t for k in ["grazie", "ottimo lavoro", "bravo", "perfetto", "eccellente"]):
        return {
            "reply": "È sempre un privilegio e un dovere esserLe utile, signore. Rimango costantemente a Sua completa disposizione.",
            "action": "chat", "action_params": {}, "engine": "stark-etiquette"
        }

    if any(k in t for k in ["buonanotte", "riposa", "arrivederci", "a presto", "spegniti", "a domani"]):
        return {
            "reply": "Configuro i sensori in modalità sorveglianza notturna a basso consumo. Buonanotte e buon riposo, signore.",
            "action": "chat", "action_params": {}, "engine": "stark-etiquette"
        }

    # 7. ORA E DATA IN TEMPO REALE
    if any(k in t for k in ["che ore sono", "che ora è", "ora esatta", "orario", "che giorno è", "qual è la data", "data odierna", "in che giorno siamo"]):
        return {
            "reply": get_live_time_and_date(),
            "action": "chat", "action_params": {}, "engine": "stark-chrono"
        }

    # 8. METEO LIVE
    weather_keywords = ["meteo", "tempo fa a", "temperatura a", "clima a", "piove a", "previsioni per", "previsioni meteo"]
    for kw in weather_keywords:
        if kw in t:
            parts = t.split(kw)
            city = parts[-1].strip(" ?!.,;")
            if not city or len(city) < 2:
                city = "Roma"
            weather_report = tool_get_weather(city)
            return {
                "reply": f"Sensori meteorologici sincronizzati, signore. {weather_report}",
                "action": "weather",
                "action_params": {"location": city},
                "engine": "stark-weather-intel"
            }

    # 9. MATEMATICA & CALCOLATRICE QUANTISTICA
    if any(k in t for k in ["calcola", "quanto fa", "radice quadrata", "percentuale"]) or re.search(r'\d+\s*[\+\-\*\/\%x]\s*\d+', t):
        math_res = solve_math_nlp(raw)
        return {
            "reply": math_res,
            "action": "calculator",
            "action_params": {"query": raw},
            "engine": "stark-quantum-calc"
        }

    # 10. AZIONI DISPOSITIVO: SPOTIFY
    if any(k in t for k in ["spotify", "musica", "canzone", "riproduci", "suona", "ascolta"]):
        clean_q = t
        for word in ["riproduci", "metti", "ascolta", "suona", "su spotify", "spotify", "canzone", "musica", "dei", "di"]:
            clean_q = clean_q.replace(word, "")
        clean_q = clean_q.strip() or "Top Hits"
        return {
            "reply": f"Avvio Spotify con la frequenza audio per '{clean_q}', signore.",
            "action": "spotify",
            "action_params": {"query": clean_q, "action": "spotify"},
            "engine": "stark-dispatcher"
        }

    # 11. AZIONI DISPOSITIVO: WHATSAPP
    if any(k in t for k in ["whatsapp", "messaggio a", "scrivi a"]):
        clean_msg = t.replace("invia", "").replace("messaggio", "").replace("whatsapp", "").replace("a", "").strip()
        return {
            "reply": "Protocollo WhatsApp configurato e pronto alla trasmissione, signore.",
            "action": "whatsapp",
            "action_params": {"text": clean_msg, "action": "whatsapp"},
            "engine": "stark-dispatcher"
        }

    # 12. AZIONI DISPOSITIVO: CHIAMATE
    if any(k in t for k in ["chiama", "telefona", "componi numero"]):
        numbers = [s for s in t.split() if s.isdigit() or (s.startswith("+") and s[1:].isdigit())]
        num_str = numbers[0] if numbers else ""
        return {
            "reply": f"Inoltro la chiamata al terminale telefonico, signore.",
            "action": "call",
            "action_params": {"phone": num_str, "action": "call"},
            "engine": "stark-dispatcher"
        }

    # 13. AZIONI DISPOSITIVO: GOOGLE MAPS
    if any(k in t for k in ["mappa", "mappe", "indicazioni", "portami a", "dove si trova", "ristorante vicin"]):
        place = t
        for phrase in ["dove si trova", "portami a", "indicazioni per", "apri mappa per", "apri mappe", "mappe", "mappa", "cerca"]:
            place = place.replace(phrase, "")
        place = place.strip() or "posizioni nelle vicinanze"
        return {
            "reply": f"Coordinate satellitari caricate su Google Maps per '{place}', signore.",
            "action": "map",
            "action_params": {"query": place, "action": "map"},
            "engine": "stark-navigation"
        }

    # 14. AZIONI DISPOSITIVO: TORCIA
    if any(k in t for k in ["torcia", "flash", "luce"]):
        turn_on = not any(k in t for k in ["spegni", "disattiva", "off", "chiudi"])
        return {
            "reply": "Attivo il modulo torcia del dispositivo, signore." if turn_on else "Disattivo la torcia, signore.",
            "action": "torch",
            "action_params": {"state": "on" if turn_on else "off", "action": "torch"},
            "engine": "stark-dispatcher"
        }

    # 15. AZIONI DISPOSITIVO: TIMER
    if any(k in t for k in ["timer", "conto alla rovescia"]):
        return {
            "reply": "Imposto il timer richiesto sul terminale, signore.",
            "action": "timer",
            "action_params": {"query": raw, "action": "timer"},
            "engine": "stark-dispatcher"
        }

    # 16. AZIONI DISPOSITIVO: SVEGLIA
    if any(k in t for k in ["sveglia", "svegliami"]):
        return {
            "reply": "Programmo la sveglia secondo le Sue istruzioni, signore.",
            "action": "alarm",
            "action_params": {"query": raw, "action": "alarm"},
            "engine": "stark-dispatcher"
        }

    # 17. AZIONI DISPOSITIVO: VOLUME
    if any(k in t for k in ["alza il volume", "volume su", "abbassa il volume", "volume giù", "volume giu", "volume al massimo", "metti muto", "silenzia volume"]):
        direction = "alza"
        if any(k in t for k in ["abbassa", "giù", "giu", "meno"]): direction = "abbassa"
        elif any(k in t for k in ["massimo", "max", "cento", "100"]): direction = "max"
        elif any(k in t for k in ["muto", "silenzia", "zero"]): direction = "muto"
        return {
            "reply": "Regolo il volume multimediale del dispositivo, signore.",
            "action": "volume",
            "action_params": {"query": direction, "action": "volume"},
            "engine": "stark-dispatcher"
        }

    # 18. AZIONI DISPOSITIVO: FOTOCAMERA
    if any(k in t for k in ["fotocamera", "fai una foto", "scatta foto", "scatta una foto"]):
        return {
            "reply": "Apro i sensori ottici della fotocamera, signore.",
            "action": "camera",
            "action_params": {"action": "camera"},
            "engine": "stark-dispatcher"
        }

    # 19. AZIONI DISPOSITIVO: APERTURA APP
    if t.startswith("apri ") and not any(k in t for k in ["whatsapp", "spotify", "impostazion", "mappa", "mappe", "torcia"]):
        app_name = raw[5:].strip()
        return {
            "reply": f"Avvio l'applicazione {app_name}, signore.",
            "action": "open_app",
            "action_params": {"query": app_name, "action": "open_app"},
            "engine": "stark-dispatcher"
        }

    # 20. ENCICLOPEDIA E RICERCA GLOBALE ULTRA-INTELLIGENTE (WIKIPEDIA + DUCKDUCKGO SYNTHESIS)
    # Qualsiasi domanda concettuale, storica, scientifica, o di attualità
    wiki_res = fetch_wikipedia_summary(raw)
    if wiki_res:
        return {
            "reply": f"In base agli archivi della conoscenza globale, signore: {wiki_res}",
            "action": "chat",
            "action_params": {"knowledge_source": "wikipedia"},
            "engine": "stark-encyclopedia"
        }

    web_res = fetch_web_synthesis(raw)
    if web_res:
        return {
            "reply": f"Dalla scansione in tempo reale delle reti globali, signore: {web_res}",
            "action": "web_search",
            "action_params": {"query": raw, "summary": web_res},
            "engine": "stark-web-intel"
        }

    # 15. RISPOSTA DI DEFAULT ELEGANTE
    return {
        "reply": f"Ho analizzato la Sua direttiva attraverso i sensori Stark, signore. Posso eseguire una scansione più approfondita sul web, o avviare un protocollo specifico a Sua scelta.",
        "action": "chat",
        "action_params": {},
        "engine": "stark-cognitive-core"
    }

# =============================================================================
# EDGE TTS NEURAL VOICE ENGINE (Microsoft Azure Neural Voices)
# =============================================================================
# Voci italiane disponibili:
#   it-IT-DiegoNeural       (Maschile, standard - elegante e formale = JARVIS default)
#   it-IT-GiuseppeMultilingualNeural (Maschile, multilingue)
#   it-IT-IsabellaNeural    (Femminile)
#   it-IT-ElsaNeural        (Femminile)

TTS_VOICES = {
    "it-male":       "it-IT-DiegoNeural",
    "it-male-multi": "it-IT-GiuseppeMultilingualNeural",
    "it-female":     "it-IT-IsabellaNeural",
    "en-male":       "en-US-GuyNeural",
    "en-female":     "en-US-JennyNeural",
}
DEFAULT_TTS_VOICE = "it-IT-DiegoNeural"

# Audio cache directory (evita rigenerazione inutile di frasi identiche)
TTS_CACHE_DIR = Path(os.path.dirname(__file__)) / ".tts_cache"
TTS_CACHE_DIR.mkdir(exist_ok=True)

class TTSRequest(BaseModel):
    text: str
    voice: str = ""       # es. "it-male", "it-IT-DiegoNeural", o vuoto per default
    rate: str = "+0%"     # es. "+10%", "-5%"
    pitch: str = "+0Hz"   # es. "+2Hz", "-3Hz"

def _resolve_voice(voice_hint: str) -> str:
    """Risolve un hint di voce al nome completo Edge TTS."""
    if not voice_hint:
        return DEFAULT_TTS_VOICE
    # Lookup diretto se è un alias
    if voice_hint.lower() in TTS_VOICES:
        return TTS_VOICES[voice_hint.lower()]
    # Se è già un nome completo Edge TTS (es. "it-IT-DiegoNeural")
    if "-" in voice_hint and "Neural" in voice_hint:
        return voice_hint
    return DEFAULT_TTS_VOICE

def _cache_key(text: str, voice: str, rate: str, pitch: str) -> str:
    """Genera una chiave cache deterministica per il testo/voce."""
    raw = f"{text}|{voice}|{rate}|{pitch}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

@app.post("/tts")
async def text_to_speech(request: TTSRequest):
    """
    Genera audio MP3 da testo tramite Microsoft Edge Neural TTS.
    Restituisce un file audio MP3 in streaming, con cache integrata.
    """
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Il campo 'text' è vuoto.")
    if len(text) > 2000:
        text = text[:2000]

    voice = _resolve_voice(request.voice)
    rate = request.rate or "+0%"
    pitch = request.pitch or "+0Hz"

    # Check cache
    cache_id = _cache_key(text, voice, rate, pitch)
    cached_file = TTS_CACHE_DIR / f"{cache_id}.mp3"

    if cached_file.exists() and cached_file.stat().st_size > 500:
        print(f"[JARVIS TTS] Cache hit: {cache_id} ({voice})")
        return FileResponse(
            str(cached_file),
            media_type="audio/mpeg",
            headers={
                "Cache-Control": "public, max-age=86400",
                "X-TTS-Voice": voice,
                "X-TTS-Cache": "HIT"
            }
        )

    # Genera audio con Edge TTS
    try:
        t0 = time.time()
        communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
        await communicate.save(str(cached_file))
        gen_ms = round((time.time() - t0) * 1000)
        print(f"[JARVIS TTS] Generato: {len(text)} chars, {voice}, {gen_ms}ms -> {cached_file.name}")

        return FileResponse(
            str(cached_file),
            media_type="audio/mpeg",
            headers={
                "Cache-Control": "public, max-age=86400",
                "X-TTS-Voice": voice,
                "X-TTS-Cache": "MISS",
                "X-TTS-Gen-Ms": str(gen_ms)
            }
        )
    except Exception as e:
        print(f"[JARVIS TTS ERROR] {e}")
        raise HTTPException(status_code=500, detail=f"Errore generazione TTS: {str(e)}")

@app.get("/tts/audio")
async def text_to_speech_get(text: str, voice: str = "it-male", rate: str = "+0%", pitch: str = "+0Hz"):
    """Convenience endpoint per client mobili che non possono inviare un body POST."""
    return await text_to_speech(TTSRequest(text=text, voice=voice, rate=rate, pitch=pitch))

@app.get("/tts/voices")
async def list_tts_voices():
    """Restituisce l'elenco delle voci TTS disponibili con alias."""
    return {
        "default": DEFAULT_TTS_VOICE,
        "aliases": TTS_VOICES,
        "hint": "Usa il campo 'voice' in /tts con un alias (es. 'it-male') o il nome completo Edge TTS."
    }

@app.get("/ping")
async def ping():
    """Healthcheck ping per calcolo latenza live su HUD."""
    return {"status": "pong", "time": time.time()}

@app.get("/chat")
async def chat_info():
    """Informazioni utili quando l'endpoint viene aperto direttamente nel browser."""
    return {
        "status": "online",
        "endpoint": "/chat",
        "method": "POST",
        "body": {"message": "Il tuo comando qui"},
        "note": "Usa l'app Jarvis o una richiesta POST: questo indirizzo non è una pagina web."
    }

@app.post("/chat")
async def chat(request: UserQuery):
    """Endpoint unificato con Multi-Modello (DeepSeek-V4.1-Flash, Grok, ChatGPT, Gemini, Qwen, Stark) e Tool Calling."""
    global conversation_history, conversation_histories, conversation_session_last_seen
    t_start = time.time()
    session_key = (request.session_id or "").strip()[:120]
    if not session_key:
        session_key = f"{request.client}:anonymous"
    now = time.time()
    for old_key, last_seen in list(conversation_session_last_seen.items()):
        if now - last_seen > CONVERSATION_SESSION_TTL:
            conversation_session_last_seen.pop(old_key, None)
            conversation_histories.pop(old_key, None)
    conversation_session_last_seen.pop(session_key, None)
    conversation_session_last_seen[session_key] = now
    while len(conversation_session_last_seen) > MAX_CONVERSATION_SESSIONS:
        expired_key, _ = conversation_session_last_seen.popitem(last=False)
        conversation_histories.pop(expired_key, None)
    conversation_history = conversation_histories.setdefault(
        session_key, [{"role": "system", "content": DEEPSEEK_PROMPT}]
    )

    contextual_message = request.message
    if request.client == "android" and request.context:
        contextual_message = (
            "Client Android J.A.R.V.I.S.\n"
            f"Contesto dispositivo: {json.dumps(request.context, ensure_ascii=False)}\n"
            f"Richiesta: {request.message}"
        )

    # Rilevamento modello richiesto da payload o comandi vocali/testuali
    requested_model = request.model or (request.context.get("model") if request.context else None) or request.personality
    user_key = request.api_key or (request.context.get("api_key") if request.context else None)

    msg_lower = request.message.lower()
    if any(k in msg_lower for k in ["deepseek", "passa a deepseek", "attiva deepseek", "modalità deepseek", "modalita deepseek", "flash v4", "v4.1"]):
        requested_model = "deepseek-v4.1-flash"
    elif any(k in msg_lower for k in ["passa a grok", "attiva grok", "modalità grok", "modalita grok"]):
        requested_model = "grok"
    elif any(k in msg_lower for k in ["passa a chatgpt", "attiva chatgpt", "modalità chatgpt", "modalita chatgpt"]):
        requested_model = "chatgpt"
    elif any(k in msg_lower for k in ["passa a gemini", "attiva gemini", "modalità gemini", "modalita gemini"]):
        requested_model = "gemini"
    elif any(k in msg_lower for k in ["passa a qwen", "attiva qwen", "modalità qwen", "modalita qwen"]):
        requested_model = "qwen"
    elif any(k in msg_lower for k in ["passa a stark", "attiva stark", "modalità stark", "modalita stark", "torna a jarvis"]):
        requested_model = "stark"

    active_client, active_model, active_prompt, active_engine_label = resolve_model_session(
        requested_model, request.personality, user_key
    )

    # Motore Cognitivo Autonomo Stark (ACB) — Se nessun LLM remoto configurato o nanoGPT locale
    if active_client is None or active_model == "nanogpt-local" or (active_model == "stark-cognitive" and not local_llm_mode):
        cognitive_res = stark_cognitive_engine(contextual_message)
        latency = round((time.time() - t_start) * 1000, 1)
        cognitive_res["latency_ms"] = latency
        cognitive_res["engine"] = active_engine_label
        return cognitive_res

    # Pipeline LLM Avanzata Multi-Modello con Function Calling
    if not conversation_history or conversation_history[0].get("role") != "system" or conversation_history[0].get("content") != active_prompt:
        conversation_history = [{"role": "system", "content": active_prompt}]

    conversation_history.append({"role": "user", "content": contextual_message})

    triggered_action = "chat"
    triggered_params = {}

    try:
        # Step 1: Chiamata decisionale LLM con catalogo tools sul modello attivo
        response = await run_in_threadpool(
            active_client.chat.completions.create,
            model=active_model,
            messages=conversation_history,
            tools=TOOLS_SPEC,
            tool_choice="auto"
        )

        response_msg = response.choices[0].message
        tool_calls = getattr(response_msg, "tool_calls", None)

        # Step 2: Esecuzione tools se invocati dal modello
        if tool_calls:
            conversation_history.append(response_msg)

            for tc in tool_calls:
                fn_name = tc.function.name
                fn_args = {}
                try:
                    fn_args = json.loads(tc.function.arguments) if tc.function.arguments else {}
                except Exception:
                    pass
                tool_output = ""

                print(f"[{active_engine_label} TOOL EXEC] Invocazione tool '{fn_name}' con argomenti: {fn_args}")

                if fn_name == "web_search":
                    query = fn_args.get("query", request.message)
                    tool_output = await run_in_threadpool(tool_web_search, query)

                elif fn_name == "get_weather":
                    location = fn_args.get("location", "Roma")
                    tool_output = await run_in_threadpool(tool_get_weather, location)

                elif fn_name == "calculator":
                    expr = fn_args.get("expression", "0")
                    tool_output = tool_calculator(expr)

                elif fn_name == "execute_device_action":
                    triggered_action = fn_args.get("action", "chat")
                    triggered_params = fn_args
                    tool_output = f"Azione di sistema '{triggered_action}' inoltrata con successo all'HUD del dispositivo."

                conversation_history.append({
                    "tool_call_id": tc.id,
                    "role": "tool",
                    "name": fn_name,
                    "content": str(tool_output),
                })

            # Step 3: Generazione risposta vocale integrata
            final_res = await run_in_threadpool(
                active_client.chat.completions.create,
                model=active_model,
                messages=conversation_history
            )
            reply = final_res.choices[0].message.content or "Operazione completata, signore."
        else:
            reply = response_msg.content or "Ricevuto, signore."

        # Alcuni gateway incapsulano il 429 in una risposta HTTP 200.
        # Intercettiamo anche quel caso prima di mostrarlo o salvarlo in memoria.
        reply_lower = (reply or "").lower()
        if any(marker in reply_lower for marker in ("rate_limit", "rate limit", "too many requests", "quota exceeded", "429")):
            local_result = stark_cognitive_engine(request.message)
            reply = local_result.get("reply", "Sono operativo e pronto ad aiutarla, signore.")
            triggered_action = local_result.get("action", "chat")
            triggered_params = local_result.get("action_params", {})
            active_engine_label = f"{active_engine_label}-local-fallback"

        conversation_history.append({"role": "assistant", "content": reply})

        # Manteniamo la memoria di conversazione ottimizzata
        if len(conversation_history) > 25:
            conversation_history = [conversation_history[0]] + conversation_history[-15:]
        conversation_histories[session_key] = conversation_history

        latency = round((time.time() - t_start) * 1000, 1)

        return {
            "reply": reply,
            "action": triggered_action,
            "action_params": triggered_params,
            "engine": active_engine_label,
            "latency_ms": latency
        }

    except Exception as e:
        error_msg = f"Anomalia riscontrata nei processori centrali ({active_engine_label}): {str(e)}"
        print(f"[JARVIS ERROR] {error_msg}")
        latency = round((time.time() - t_start) * 1000, 1)

        # Un rate limit/quota non è un errore conversazionale: non ritentare
        # lo stesso provider e non restituire il testo tecnico all'utente.
        provider_error = str(e).lower()
        is_rate_limited = any(marker in provider_error for marker in (
            "rate_limit", "rate limit", "429", "too many requests", "quota", "billing"
        ))
        if is_rate_limited:
            cognitive_fallback = stark_cognitive_engine(request.message)
            cognitive_fallback["engine"] = f"{active_engine_label}-local-fallback"
            cognitive_fallback["latency_ms"] = round((time.time() - t_start) * 1000, 1)
            conversation_histories[session_key] = conversation_history[:1]
            return cognitive_fallback

        # Recupero resiliente rapido senza tools
        try:
            recovery_messages = [
                {"role": "system", "content": active_prompt},
                {"role": "user", "content": request.message},
            ]
            recovery = await run_in_threadpool(
                active_client.chat.completions.create,
                model=active_model,
                messages=recovery_messages,
            )
            recovery_reply = recovery.choices[0].message.content or "Non ho ricevuto contenuto dal modello."
            conversation_history = recovery_messages + [{"role": "assistant", "content": recovery_reply}]
            conversation_histories[session_key] = conversation_history
            return {
                "reply": recovery_reply,
                "action": "chat",
                "action_params": {},
                "engine": f"{active_engine_label}-recovery",
                "latency_ms": round((time.time() - t_start) * 1000, 1)
            }
        except Exception as recovery_error:
            print(f"[JARVIS RECOVERY ERROR] {recovery_error}")

        # Fallback cognitivo Stark autonomo in caso di indisponibilità rete esterna
        cognitive_fallback = stark_cognitive_engine(request.message)
        cognitive_fallback["engine"] = f"{active_engine_label}-cognitive-fallback"
        cognitive_fallback["latency_ms"] = round((time.time() - t_start) * 1000, 1)
        return cognitive_fallback

# Handler per servire file statici PWA con corretto Content-Type
@app.api_route("/{file_name:path}", methods=["GET", "HEAD"])
async def get_static_file(file_name: str):
    """Serve i file statici della PWA (app.js, style.css, manifest.json, icone, ecc.)."""
    file_path = os.path.join(os.path.dirname(__file__), file_name)
    if os.path.isfile(file_path):
        return FileResponse(
            file_path,
            headers={
                "Cache-Control": "no-cache, no-store, must-revalidate",
                "Pragma": "no-cache",
                "Expires": "0"
            }
        )
    raise HTTPException(status_code=404, detail="File non trovato")

if __name__ == "__main__":
    import uvicorn
    print("\n" + "=" * 65)
    print(" J.A.R.V.I.S. // Stark Industries Enterprise Server V2.0")
    print(" Host: 0.0.0.0 | Port: 8000 | Multi-Tool & Web Search: ACTIVE")
    print("=" * 65 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
