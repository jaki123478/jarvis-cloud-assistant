"""
J.A.R.V.I.S. // nanoGPT Interactive Inference
Carica il modello addestrato 'jarvis_model.pt' e genera risposte in tempo reale.
Utilizzo:
  python sample_jarvis.py
  python sample_jarvis.py --prompt "User: Jarvis, stato della batteria?"
"""

import os
import sys
import argparse
import torch
import tiktoken
from mini_gpt import GPT, GPTConfig

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

MODEL_PATH = 'jarvis_model.pt'
device = 'cuda' if torch.cuda.is_available() else 'cpu'

def load_jarvis_model():
    """Carica il checkpoint salvato o inizializza nanoGPT."""
    enc = tiktoken.get_encoding("gpt2")
    if os.path.exists(MODEL_PATH):
        print(f"[JARVIS CORE] Caricamento pesi addestrati da '{MODEL_PATH}'...")
        checkpoint = torch.load(MODEL_PATH, map_location=device, weights_only=False)
        config = checkpoint['config']
        model = GPT(config)
        model.load_state_dict(checkpoint['model_state_dict'])
    else:
        print("[JARVIS CORE] Nessun checkpoint trovato. Inizializzo configurazione standard...")
        config = GPTConfig(block_size=128, vocab_size=50304, n_layer=4, n_head=4, n_embd=256)
        model = GPT(config)

    model.to(device)
    model.eval()
    return model, enc

def ask_local_jarvis(prompt_text, model=None, enc=None, max_tokens=60, temp=0.7, top_k=40):
    if model is None or enc is None:
        model, enc = load_jarvis_model()

    if not prompt_text.startswith("User:"):
        prompt_text = f"User: {prompt_text}\nJ.A.R.V.I.S.:"

    tokens = enc.encode(prompt_text)
    x = torch.tensor([tokens], dtype=torch.long, device=device)

    with torch.no_grad():
        y = model.generate(x, max_new_tokens=max_tokens, temperature=temp, top_k=top_k)

    full_output = enc.decode(y[0].tolist(), errors='replace')
    # Estrai la parte di risposta dopo il prompt
    if prompt_text in full_output:
        reply = full_output.split(prompt_text)[-1].strip()
    else:
        reply = full_output.replace(prompt_text, '').strip()

    # Prendi fino al primo cambio turno o a capo
    if "User:" in reply:
        reply = reply.split("User:")[0].strip()

    return reply if reply else "Tutti i sistemi della Mark VII sono a pieno regime, signore."

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="J.A.R.V.I.S. nanoGPT Inference")
    parser.add_argument("--prompt", type=str, default="", help="Domanda o comando per Jarvis")
    parser.add_argument("--tokens", type=int, default=50, help="Numero massimo di token da generare")
    parser.add_argument("--temp", type=float, default=0.7, help="Temperatura di sampling")
    args = parser.parse_args()

    model, enc = load_jarvis_model()

    if args.prompt:
        print(f"\n[USER] {args.prompt}")
        reply = ask_local_jarvis(args.prompt, model, enc, max_tokens=args.tokens, temp=args.temp)
        print(f"[JARVIS] {reply}\n")
    else:
        print("\n" + "=" * 60)
        print(" J.A.R.V.I.S. Terminale Neurale Locale (nanoGPT)")
        print(" Digita il tuo comando (o 'esci' per terminare):")
        print("=" * 60)
        while True:
            try:
                user_msg = input("\nTu > ").strip()
                if not user_msg or user_msg.lower() in {'esci', 'quit', 'exit'}:
                    break
                reply = ask_local_jarvis(user_msg, model, enc, max_tokens=args.tokens, temp=args.temp)
                print(f"J.A.R.V.I.S. > {reply}")
            except (KeyboardInterrupt, EOFError):
                break
        print("\nSessione terminata, signore.")
