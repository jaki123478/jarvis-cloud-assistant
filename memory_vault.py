"""
J.A.R.V.I.S. Persistent Memory Vault
Per-session/user facts stored under data/memory/*.json.
Shared by web PWA and Android via the same HTTP contract.
"""
from __future__ import annotations

import json
import re
import time
import uuid
from pathlib import Path
from typing import Any

MEMORY_DIR = Path(__file__).resolve().parent / "data" / "memory"
MEMORY_DIR.mkdir(parents=True, exist_ok=True)

_SAFE_ID = re.compile(r"[^a-zA-Z0-9._:-]+")


def sanitize_session_id(session_id: str | None, client: str = "web") -> str:
    raw = (session_id or "").strip()[:120]
    if not raw:
        raw = f"{client or 'web'}:anonymous"
    safe = _SAFE_ID.sub("_", raw).strip("._")[:100]
    return safe or "anonymous"


def _path_for(session_id: str) -> Path:
    return MEMORY_DIR / f"{sanitize_session_id(session_id)}.json"


def load_vault(session_id: str) -> dict[str, Any]:
    key = sanitize_session_id(session_id)
    path = _path_for(key)
    if not path.is_file():
        return {"session_id": key, "updated_at": None, "facts": []}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("invalid vault")
        facts = data.get("facts") or []
        if not isinstance(facts, list):
            facts = []
        return {
            "session_id": key,
            "updated_at": data.get("updated_at"),
            "facts": [f for f in facts if isinstance(f, dict) and f.get("text")],
        }
    except Exception:
        return {"session_id": key, "updated_at": None, "facts": []}


def save_vault(session_id: str, facts: list[dict]) -> dict[str, Any]:
    key = sanitize_session_id(session_id)
    payload = {
        "session_id": key,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "facts": facts[:200],
    }
    path = _path_for(key)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)
    return payload


def list_facts(session_id: str) -> dict[str, Any]:
    vault = load_vault(session_id)
    return {
        "session_id": vault["session_id"],
        "updated_at": vault.get("updated_at"),
        "count": len(vault["facts"]),
        "facts": vault["facts"],
    }


def add_fact(session_id: str, text: str, source: str = "user") -> dict[str, Any]:
    fact_text = re.sub(r"\s+", " ", (text or "").strip())
    if len(fact_text) < 2:
        raise ValueError("Fatto troppo corto")
    if len(fact_text) > 500:
        fact_text = fact_text[:500].rstrip()
    vault = load_vault(session_id)
    # Deduplicate case-insensitive
    lower = fact_text.lower()
    for existing in vault["facts"]:
        if (existing.get("text") or "").lower() == lower:
            return {"status": "exists", "fact": existing, "vault": list_facts(session_id)}
    entry = {
        "id": uuid.uuid4().hex[:12],
        "text": fact_text,
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "source": source or "user",
    }
    vault["facts"].append(entry)
    save_vault(session_id, vault["facts"])
    return {"status": "saved", "fact": entry, "vault": list_facts(session_id)}


def forget_fact(session_id: str, query: str | None = None, fact_id: str | None = None) -> dict[str, Any]:
    vault = load_vault(session_id)
    before = len(vault["facts"])
    if fact_id:
        vault["facts"] = [f for f in vault["facts"] if f.get("id") != fact_id]
    elif query:
        q = query.strip().lower()
        if q in ("tutto", "tutta", "all", "*", "memoria"):
            vault["facts"] = []
        else:
            vault["facts"] = [
                f for f in vault["facts"]
                if q not in (f.get("text") or "").lower()
            ]
    else:
        raise ValueError("Specificare fact_id o query")
    removed = before - len(vault["facts"])
    save_vault(session_id, vault["facts"])
    return {
        "status": "forgotten" if removed else "noop",
        "removed": removed,
        "vault": list_facts(session_id),
    }


def clear_facts(session_id: str) -> dict[str, Any]:
    save_vault(session_id, [])
    return {"status": "cleared", "vault": list_facts(session_id)}


def facts_as_prompt_block(session_id: str, max_facts: int = 40) -> str:
    facts = load_vault(session_id)["facts"][-max_facts:]
    if not facts:
        return ""
    lines = ["Fatti persistenti sull'utente (Memory Vault — usali se rilevanti):"]
    for f in facts:
        lines.append(f"- {f.get('text', '').strip()}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Natural-language chat intents (IT + light EN)
# ---------------------------------------------------------------------------
_RE_REMEMBER = re.compile(
    r"^\s*(?:jarvis[,!\s]+)?(?:per\s+favore[,!\s]+)?(?:"
    r"ricordati\s+che|ricorda\s+che|ricorda|memorizza|tieni\s+a\s+mente|"
    r"remember\s+that|remember"
    r")\s*[:\-]?\s*(.+)$",
    re.IGNORECASE | re.DOTALL,
)
_RE_FORGET_ALL = re.compile(
    r"^\s*(?:jarvis[,!\s]+)?(?:"
    r"dimentica\s+tutto|cancella\s+(?:tutta\s+)?(?:la\s+)?memoria|"
    r"reset\s+memoria|forget\s+everything|clear\s+memory"
    r")\s*[.!]?\s*$",
    re.IGNORECASE,
)
_RE_FORGET = re.compile(
    r"^\s*(?:jarvis[,!\s]+)?(?:"
    r"dimentica|scorda|forget"
    r")\s*[:\-]?\s*(.+)$",
    re.IGNORECASE | re.DOTALL,
)
_RE_WHAT_KNOW = re.compile(
    r"^\s*(?:jarvis[,!\s]+)?(?:"
    r"cosa\s+sai\s+di\s+me|cosa\s+ti\s+ricordi(?:\s+di\s+me)?|"
    r"cosa\s+ricordi(?:\s+di\s+me)?|dimmi\s+(?:cosa\s+)?(?:sai|ricordi)|"
    r"elenca\s+(?:i\s+)?fatti|what\s+do\s+you\s+(?:know|remember)"
    r")\s*[?!.]?\s*$",
    re.IGNORECASE,
)


def try_memory_intent(session_id: str, message: str) -> dict[str, Any] | None:
    """If message is a memory command, execute it and return a chat-shaped dict."""
    text = (message or "").strip()
    if not text:
        return None

    if _RE_WHAT_KNOW.match(text):
        vault = list_facts(session_id)
        if vault["count"] == 0:
            reply = "Non ho ancora fatti salvati su di Lei nel Memory Vault, signore."
        else:
            bullets = "; ".join(f.get("text", "") for f in vault["facts"])
            reply = f"Nel Memory Vault ricordo {vault['count']} fatt{'o' if vault['count'] == 1 else 'i'}: {bullets}."
        return {
            "reply": reply,
            "action": "memory_list",
            "action_params": {"count": vault["count"], "facts": vault["facts"]},
            "engine": "memory-vault",
            "latency_ms": 0.0,
            "memory": vault,
        }

    if _RE_FORGET_ALL.match(text):
        result = clear_facts(session_id)
        return {
            "reply": "Memory Vault azzerato, signore. Non ricordo più i fatti precedenti.",
            "action": "memory_clear",
            "action_params": {"removed": "all"},
            "engine": "memory-vault",
            "latency_ms": 0.0,
            "memory": result["vault"],
        }

    m_forget = _RE_FORGET.match(text)
    if m_forget:
        query = m_forget.group(1).strip().rstrip(".!")
        # Avoid treating "dimentica tutto" twice — already handled
        result = forget_fact(session_id, query=query)
        if result["removed"]:
            reply = f"Dimenticato ({result['removed']}), signore."
        else:
            reply = f"Non ho trovato nulla da dimenticare per «{query}», signore."
        return {
            "reply": reply,
            "action": "memory_forget",
            "action_params": {"query": query, "removed": result["removed"]},
            "engine": "memory-vault",
            "latency_ms": 0.0,
            "memory": result["vault"],
        }

    m_remember = _RE_REMEMBER.match(text)
    if m_remember:
        fact = m_remember.group(1).strip().rstrip(".!")
        # Strip leading "che " if leftover
        fact = re.sub(r"^(?:che\s+)+", "", fact, flags=re.IGNORECASE).strip()
        if len(fact) < 2:
            return {
                "reply": "Cosa dovrei ricordare esattamente, signore?",
                "action": "memory_prompt",
                "action_params": {},
                "engine": "memory-vault",
                "latency_ms": 0.0,
            }
        result = add_fact(session_id, fact, source="chat")
        if result["status"] == "exists":
            reply = "Lo sapevo già, signore. Resta archiviato nel Memory Vault."
        else:
            reply = f"Memorizzato, signore: «{fact}»."
        return {
            "reply": reply,
            "action": "memory_save",
            "action_params": {"fact": result["fact"]},
            "engine": "memory-vault",
            "latency_ms": 0.0,
            "memory": result["vault"],
        }

    return None


def split_sentences_for_stream(text: str) -> list[str]:
    """Split assistant reply into speakable sentence chunks for SSE / progressive TTS."""
    clean = (text or "").strip()
    if not clean:
        return []
    parts = re.findall(r"[^.!?;\n]+[.!?;\n]*", clean)
    chunks: list[str] = []
    for p in parts:
        s = p.strip()
        if not s:
            continue
        prev = chunks[-1] if chunks else ""
        prev_complete = bool(re.search(r"[.!?]$", prev))
        # Attach only tiny fragments that are NOT new sentences after a completed one
        if chunks and not prev_complete and len(s) < 12:
            chunks[-1] = f"{prev} {s}".strip()
        else:
            chunks.append(s)
    final: list[str] = []
    for c in chunks:
        if len(c) <= 220:
            final.append(c)
            continue
        bits = re.split(r"(?<=[,;])\s+", c)
        buf = ""
        for b in bits:
            if not buf:
                buf = b
            elif len(buf) + len(b) + 1 <= 180:
                buf = f"{buf} {b}"
            else:
                final.append(buf)
                buf = b
        if buf:
            final.append(buf)
    return final or [clean]
