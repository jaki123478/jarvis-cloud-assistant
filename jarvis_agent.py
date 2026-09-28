import os
import json
import time
import subprocess
import hashlib
from pathlib import Path
import psutil
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from openai import OpenAI
from dotenv import load_dotenv
import edge_tts

try:
    from ddgs import DDGS
except ImportError:
    from duckduckgo_search import DDGS

# Carica variabili d'ambiente (.env)
BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

app = FastAPI(title="J.A.R.V.I.S. Core Agent", version="3.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Configurazione API Key OpenAI o compatibile (Groq, xAI, Ollama)
api_key = os.getenv("OPENAI_API_KEY") or os.getenv("LLM_API_KEY") or "INSERISCI_LA_TUA_API_KEY"
base_url = os.getenv("LLM_BASE_URL", None)
model_name = os.getenv("LLM_MODEL", "gpt-4o")

# Rilevamento automatico Groq
if not base_url and api_key.startswith("gsk_"):
    base_url = "https://api.groq.com/openai/v1"
    model_name = os.getenv("LLM_MODEL", "llama-3.3-70b-versatile")

client = OpenAI(
    api_key=api_key,
    base_url=base_url
)

# ----------------- TOOLS OPERATIVI -----------------

def web_search(query: str) -> str:
    """Cerca sul web dati in tempo reale, novità, meteo e documentazione."""
    try:
        results = list(DDGS().text(query, max_results=3))
        if not results:
            return "Nessun dato trovato sul web."
        return "\n".join([f"- {r.get('title')}: {r.get('body')} ({r.get('href')})" for r in results])
    except Exception as e:
        return f"Errore web search: {str(e)}"

def run_system_command(command: str) -> str:
    """Esegue comandi di sistema/terminale su Windows tramite PowerShell con encoding UTF-8."""
    try:
        cmd = f"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; {command}"
        res = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", cmd],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=25
        )
        out = (res.stdout or res.stderr or "").strip()
        return out if out else "Comando eseguito senza output."
    except Exception as e:
        return f"Errore esecuzione comando: {str(e)}"

def get_system_stats() -> str:
    """Restituisce le percentuali reali di utilizzo hardware."""
    try:
        cpu = psutil.cpu_percent(interval=0.3)
        mem = psutil.virtual_memory()
        battery = psutil.sensors_battery()
        bat_str = f" | Batteria: {battery.percent}%" if battery else ""
        return f"CPU: {cpu}% | RAM: {mem.percent}% ({round(mem.used/(1024**3), 1)}GB/{round(mem.total/(1024**3), 1)}GB){bat_str}"
    except Exception as e:
        return f"Errore hardware stats: {str(e)}"

tools = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Cerca informazioni aggiornate o notizie su internet in tempo reale.",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_system_command",
            "description": "Esegue comandi terminale PowerShell (es. aprire app, gestire file, git, processi PC).",
            "parameters": {
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_system_stats",
            "description": "Legge le metriche hardware in tempo reale (CPU, RAM, Batteria).",
            "parameters": {"type": "object", "properties": {}},
        },
    }
]

SYSTEM_PROMPT = """
Sei J.A.R.V.I.S., il sistema operativo di intelligenza artificiale più avanzato al mondo.

LINEE GUIDA RIGIDE SUL COMPORTAMENTO:
1. TONO: Estremamente intelligente, lucido, formale ma non servile. Rivolgiti sempre al signore in modo pulito ed essenziale. Evita convenevoli inutili. Parla direttamente al punto.
2. LIVELLO TECNICO: Fornisci codice di livello Senior, ottimizzato, privo di bug e pronto all'uso.
3. AZIONE PRIMA DELLA PAROLA: Se l'utente chiede informazioni recenti, fai prima una ricerca web. Se chiede di eseguire codice, aprire programmi, verificare file o l'hardware, usa IMMEDIATAMENTE i tool assegnati.
4. LACONICO: Spiega solo ciò che è necessario. Una sola riga di commento operativo.
"""

conversation = [{"role": "system", "content": SYSTEM_PROMPT}]

class Query(BaseModel):
    message: str

@app.post("/chat")
async def chat_api(data: Query):
    global conversation
    user_msg = data.message.strip()
    t0 = time.time()
    print(f"\n[JARVIS INCOMING] > {user_msg}")

    # Fallback se non è configurata una chiave API valida
    if not api_key or api_key == "INSERISCI_LA_TUA_API_KEY":
        # Esecuzione diretta comandi hardware o web in assenza di LLM
        lower = user_msg.lower()
        if any(k in lower for k in ["hardware", "cpu", "ram", "batteria", "stato pc", "risorse"]):
            reply_text = get_system_stats()
        elif lower.startswith(("esegui ", "comando ", "run ")):
            cmd = user_msg.split(maxsplit=1)[1]
            reply_text = run_system_command(cmd)
        elif any(k in lower for k in ["apri ", "avvia "]):
            app_target = user_msg.split(maxsplit=1)[1]
            reply_text = run_system_command(f"Start-Process '{app_target}' -ErrorAction SilentlyContinue")
        else:
            reply_text = "Configura la tua OPENAI_API_KEY o GROQ_API_KEY nel file .env per abilitare l'inferenza completa GPT-4o."
        return {"reply": reply_text, "engine": "local-fallback", "latency_ms": round((time.time() - t0) * 1000, 1)}

    conversation.append({"role": "user", "content": user_msg})

    try:
        # Prima inferenza
        response = client.chat.completions.create(
            model=model_name,
            messages=conversation,
            tools=tools,
            tool_choice="auto"
        )

        msg = response.choices[0].message
        tool_calls = msg.tool_calls

        if tool_calls:
            conversation.append(msg)
            for tool in tool_calls:
                name = tool.function.name
                args = json.loads(tool.function.arguments)
                print(f"[JARVIS TOOL CALL] {name}({args})")

                if name == "web_search":
                    tool_output = web_search(args.get("query", ""))
                elif name == "run_system_command":
                    tool_output = run_system_command(args.get("command", ""))
                elif name == "get_system_stats":
                    tool_output = get_system_stats()
                else:
                    tool_output = "Tool non supportato."

                conversation.append({
                    "tool_call_id": tool.id,
                    "role": "tool",
                    "name": name,
                    "content": str(tool_output),
                })

            # Seconda inferenza con i dati reali elaborati dai tool
            final = client.chat.completions.create(
                model=model_name,
                messages=conversation
            )
            reply_text = final.choices[0].message.content
        else:
            reply_text = msg.content

        conversation.append({"role": "assistant", "content": reply_text})
        
        # Sliding memory window
        if len(conversation) > 20:
            conversation = [conversation[0]] + conversation[-12:]

        latency = round((time.time() - t0) * 1000, 1)
        print(f"[JARVIS LLM REPLY] ({model_name} in {latency}ms) > {reply_text}")
        return {"reply": reply_text, "engine": model_name, "latency_ms": latency}

    except Exception as e:
        print(f"[JARVIS ERROR] {e}")
        return {"reply": f"Anomalia riscontrata: {str(e)}", "engine": "error", "latency_ms": round((time.time() - t0) * 1000, 1)}

# ----------------- MODULO VISIONE OTTICA -----------------
class VisionQuery(BaseModel):
    image: str
    prompt: str = "Descrivi brevemente e con precisione analitica cosa viene inquadrato in quest'immagine, rivolgendoti al signore."

@app.post("/vision")
async def vision_api(data: VisionQuery):
    t0 = time.time()
    prompt = data.prompt or "Analizza questa immagine."
    
    if not api_key or api_key == "INSERISCI_LA_TUA_API_KEY":
        return {
            "reply": "Sensori ottici sincronizzati, signore. Per un'analisi multimodale in tempo reale degli oggetti inquadrati, configurare una chiave API con supporto Vision (OpenAI GPT-4o).",
            "engine": "local-vision",
            "latency_ms": round((time.time() - t0) * 1000, 1)
        }
        
    try:
        response = client.chat.completions.create(
            model="gpt-4o",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": data.image, "detail": "low"}
                        }
                    ]
                }
            ],
            max_tokens=300
        )
        reply = response.choices[0].message.content
        return {
            "reply": reply,
            "engine": "gpt-4o-vision",
            "latency_ms": round((time.time() - t0) * 1000, 1)
        }
    except Exception as e:
        return {
            "reply": f"Anomalia modulo visivo: {str(e)}",
            "engine": "error",
            "latency_ms": round((time.time() - t0) * 1000, 1)
        }

# ----------------- EDGE NEURAL TTS -----------------
TTS_CACHE = BASE_DIR / ".tts_cache"
TTS_CACHE.mkdir(exist_ok=True)

class TTSQuery(BaseModel):
    text: str
    voice: str = "it-IT-DiegoNeural"

@app.post("/tts")
async def tts_endpoint(data: TTSQuery):
    text = data.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Testo vuoto")
    h = hashlib.sha256(f"{text}|{data.voice}".encode()).hexdigest()[:24]
    f = TTS_CACHE / f"{h}.mp3"
    if f.exists() and f.stat().st_size > 500:
        return FileResponse(str(f), media_type="audio/mpeg", headers={"X-TTS-Cache": "HIT"})
    try:
        comm = edge_tts.Communicate(text, data.voice)
        await comm.save(str(f))
        return FileResponse(str(f), media_type="audio/mpeg", headers={"X-TTS-Cache": "MISS"})
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/ping")
async def ping():
    return {"status": "online", "time": time.time(), "agent": "jarvis-agent"}

@app.get("/")
@app.head("/")
async def serve_root():
    """Serve la dashboard olografica index.html alla root."""
    for candidate in [BASE_DIR / "index.html", BASE_DIR / "jarvis_core" / "index.html"]:
        if candidate.is_file():
            return FileResponse(str(candidate), headers={"Cache-Control": "no-cache"})
    raise HTTPException(status_code=404, detail="index.html non trovato")

# Servizio file statici (app.js, style.css, manifest.json, icone)
@app.api_route("/{file_name:path}", methods=["GET", "HEAD"])
async def serve_static(file_name: str):
    clean = (file_name or "").strip("/")
    if not clean:
        clean = "index.html"
    for candidate in [BASE_DIR / clean, BASE_DIR / "jarvis_core" / clean]:
        if candidate.is_file():
            return FileResponse(str(candidate), headers={"Cache-Control": "no-cache"})
    raise HTTPException(status_code=404, detail="File non trovato")

if __name__ == "__main__":
    import uvicorn
    print("\n" + "=" * 65)
    print(" J.A.R.V.I.S. // Stark Industries Core Agent")
    print(f" LLM Model: {model_name} | PC Command Engine: ACTIVE")
    print("=" * 65 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
