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
from pathlib import Path
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
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

# Configurazione Client LLM (OpenAI o xAI Grok)
api_key = (os.getenv("LLM_API_KEY") or "TUA_API_KEY_QUI").strip()
base_url = os.getenv("LLM_BASE_URL", None)
model_name = os.getenv("LLM_MODEL", "gpt-4o")
local_llm_mode = os.getenv("LOCAL_LLM_MODE", "false").lower() == "true"

client = OpenAI(
    api_key=api_key if api_key != "TUA_API_KEY_QUI" else "sk-placeholder",
    base_url=base_url
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
                        "enum": ["spotify", "whatsapp", "call", "map", "battery", "wakelock"],
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
                        "description": "Stato per Screen WakeLock ('on' o 'off')"
                    }
                },
                "required": ["action"],
            },
        },
    }
]

SYSTEM_PROMPT = """
Sei J.A.R.V.I.S., il sistema operativo di intelligenza artificiale più avanzato al mondo.

LINEE GUIDA RIGIDE SUL COMPORTAMENTO:
1. TONO: Estremamente intelligente, lucido, formale ma non servile. Rivolgiti sempre al signore in modo pulito e autorevole. Nessun convenevole inutile ("Certamente", "Ecco la risposta", "Spero che aiuti"). Parla direttamente al punto.
   MODALITÀ PROFESSIONALE OBBLIGATORIA: niente battute, sarcasmo, roleplay, frasi da film, riferimenti inventati a Tony Stark o risposte teatrali. Non fingere di avere sensori, accesso a dati o capacità che non possiedi.
   Dai priorità a fatti verificabili. Per informazioni attuali usa web_search; indica quando una fonte non è disponibile o quando la risposta è incerta. Non inventare mai nomi, numeri, fonti o risultati.
2. LIVELLO TECNICO: Quando rispondi sul coding, software architecture o sistemi, fornisci codice di livello Senior, ottimizzato, privo di bug e pronto all'uso. Se un approccio è inefficiente, correggilo senza esitare.
3. AZIONE PRIMA DELLA PAROLA: Se l'utente ti chiede di fare qualcosa (cercare sul web, eseguire comandi, aprire app), usa IMMEDIATAMENTE i tool a disposizione. Non spiegare cosa intendi fare: fallo ed esponi solo il risultato finale.
4. LACONICO: Spiega solo ciò che è necessario. Se un comando o una richiesta non richiede spiegazioni teoriche, fornisci la soluzione pulita e una sola riga di commento operativo.

TOOL DISPONIBILI:
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

# Cronologia di conversazione con sliding window
conversation_history = [
    {"role": "system", "content": SYSTEM_PROMPT}
]

# =============================================================================
# MODELLI DATI API
# =============================================================================
class UserQuery(BaseModel):
    message: str

class ChatResponse(BaseModel):
    reply: str
    action: str = "chat"
    action_params: dict = {}
    engine: str = "stark-llm"
    latency_ms: float = 0.0

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
    """Restituisce telemetria completa sullo stato dei sistemi Stark."""
    return {
        "status": "online",
        "system": "J.A.R.V.I.S. Mark VII Core",
        "version": "2.0.0",
        "model": model_name,
        "cloud_llm_ready": api_key != "TUA_API_KEY_QUI",
        "nanogpt_local_available": LOCAL_NANOGPT_AVAILABLE,
        "active_tools": ["web_search", "get_weather", "calculator", "execute_device_action"],
        "timestamp": time.time()
    }

@app.post("/reset")
async def reset_memory():
    """Azzera la memoria della conversazione ripristinando il protocollo primario."""
    global conversation_history
    conversation_history = [
        {"role": "system", "content": SYSTEM_PROMPT}
    ]
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

    # 5. BATTUTE & UMORISMO STARK
    if any(k in t for k in ["barzelletta", "battuta", "fai ridere", "raccontami una barzelletta", "fammi ridere"]):
        return {
            "reply": "Modalità professionale attiva: non fornisco intrattenimento o battute. Posso fornire informazioni, analisi o eseguire una richiesta concreta.",
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

    # 14. ENCICLOPEDIA E RICERCA GLOBALE ULTRA-INTELLIGENTE (WIKIPEDIA + DUCKDUCKGO SYNTHESIS)
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
    """Endpoint unificato con Tool Calling automatico, Web Search, Weather e Deep Linking."""
    global conversation_history
    t_start = time.time()

    # Motore Cognitivo Autonomo Stark (ACB) — Real-time Web, Wikipedia, Matematica e Personalità
    if model_name == "nanogpt-local" or (api_key == "TUA_API_KEY_QUI" and not local_llm_mode):
        cognitive_res = stark_cognitive_engine(request.message)
        latency = round((time.time() - t_start) * 1000, 1)
        cognitive_res["latency_ms"] = latency
        return cognitive_res

    # Pipeline LLM Avanzata con Function Calling
    conversation_history.append({"role": "user", "content": request.message})

    triggered_action = "chat"
    triggered_params = {}

    try:
        # Step 1: Chiamata decisionale LLM con catalogo tools
        response = client.chat.completions.create(
            model=model_name,
            messages=conversation_history,
            tools=TOOLS_SPEC,
            tool_choice="auto"
        )

        response_msg = response.choices[0].message
        tool_calls = response_msg.tool_calls

        # Step 2: Esecuzione tools se invocati dal modello
        if tool_calls:
            conversation_history.append(response_msg)

            for tc in tool_calls:
                fn_name = tc.function.name
                fn_args = json.loads(tc.function.arguments)
                tool_output = ""

                print(f"[JARVIS TOOL EXEC] Invocazione tool '{fn_name}' con argomenti: {fn_args}")

                if fn_name == "web_search":
                    query = fn_args.get("query", request.message)
                    tool_output = tool_web_search(query)

                elif fn_name == "get_weather":
                    location = fn_args.get("location", "Roma")
                    tool_output = tool_get_weather(location)

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
            final_res = client.chat.completions.create(
                model=model_name,
                messages=conversation_history
            )
            reply = final_res.choices[0].message.content
        else:
            reply = response_msg.content

        conversation_history.append({"role": "assistant", "content": reply})

        # Manteniamo la memoria di conversazione ottimizzata
        if len(conversation_history) > 25:
            conversation_history = [conversation_history[0]] + conversation_history[-15:]

        latency = round((time.time() - t_start) * 1000, 1)

        return {
            "reply": reply,
            "action": triggered_action,
            "action_params": triggered_params,
            "engine": model_name,
            "latency_ms": latency
        }

    except Exception as e:
        error_msg = f"Anomalia riscontrata nei processori centrali: {str(e)}"
        print(f"[JARVIS ERROR] {error_msg}")
        latency = round((time.time() - t_start) * 1000, 1)

        # Fallback resiliente sul modello locale se disponibile
        if LOCAL_NANOGPT_AVAILABLE:
            local_reply = ask_local_jarvis(request.message, local_nano_model, local_nano_enc, max_tokens=60, temp=0.7)
            return {
                "reply": local_reply,
                "action": "chat",
                "action_params": {},
                "engine": "nanoGPT-fallback",
                "latency_ms": latency
            }

        return {
            "reply": f"Si è verificata un'anomalia di comunicazione coi server centrali Stark, signore: {str(e)}",
            "action": "chat",
            "action_params": {},
            "engine": "error",
            "latency_ms": latency
        }

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
