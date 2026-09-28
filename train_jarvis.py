"""
J.A.R.V.I.S. // nanoGPT Training Pipeline
Script di addestramento e fine-tuning basato su nanoGPT (Andrej Karpathy).
Addestra il modello nanoGPT sul corpus Stark (dialoghi, protocolli e comandi vocali).
Salva i pesi ottimali nel file 'jarvis_model.pt'.
"""

import os
import sys
import time
import math
import torch
import tiktoken
from mini_gpt import GPT, GPTConfig

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# =============================================================================
# IPERPARAMETRI DI TRAINING
# =============================================================================
DATA_PATH = os.path.join(os.path.dirname(__file__), 'data', 'jarvis_dataset.txt')
OUTPUT_MODEL = 'jarvis_model.pt'

batch_size = 4
block_size = 64        # Finestra di contesto (token)
max_iters = 150        # Numero iterazioni di addestramento dimostrativo
eval_interval = 25     # Valutazione della loss ogni N step
eval_iters = 10
learning_rate = 5e-4
min_lr = 5e-5
warmup_iters = 15
lr_decay_iters = 150
device = 'cuda' if torch.cuda.is_available() else 'cpu'

# =============================================================================
# CARICAMENTO & TOKENIZZAZIONE DATASET (BPE GPT-2)
# =============================================================================
print("=" * 60)
print(" J.A.R.V.I.S. // Training Pipeline (nanoGPT)")
print(f" Device di calcolo: {device.upper()}")
print("=" * 60)

with open(DATA_PATH, 'r', encoding='utf-8') as f:
    text = f.read()

# Duplica il dataset per avere abbastanza token di training se il file è corto
if len(text) < 10000:
    text = text * 10

enc = tiktoken.get_encoding("gpt2")
data_tokens = enc.encode(text)
print(f"Dataset caricato: {len(data_tokens):,} token BPE.")

# Split 90% Train / 10% Validation
n = int(0.9 * len(data_tokens))
train_data = torch.tensor(data_tokens[:n], dtype=torch.long)
val_data = torch.tensor(data_tokens[n:], dtype=torch.long)

def get_batch(split):
    data = train_data if split == 'train' else val_data
    ix = torch.randint(len(data) - block_size, (batch_size,))
    x = torch.stack([data[i:i+block_size] for i in ix])
    y = torch.stack([data[i+1:i+block_size+1] for i in ix])
    return x.to(device), y.to(device)

# =============================================================================
# INIZIALIZZAZIONE MODELLO
# =============================================================================
config = GPTConfig(
    block_size=block_size,
    vocab_size=50304,
    n_layer=4,
    n_head=4,
    n_embd=256,
    dropout=0.1
)

model = GPT(config).to(device)
print(f"Modello inizializzato: {sum(p.numel() for p in model.parameters()):,} parametri.")

optimizer = model.configure_optimizers(
    weight_decay=1e-1,
    learning_rate=learning_rate,
    betas=(0.9, 0.95),
    device_type=device
)

# Cosine Learning Rate Schedule con Warmup (Standard nanoGPT)
def get_lr(it):
    if it < warmup_iters:
        return learning_rate * (it + 1) / (warmup_iters + 1)
    if it > lr_decay_iters:
        return min_lr
    decay_ratio = (it - warmup_iters) / (lr_decay_iters - warmup_iters)
    coeff = 0.5 * (1.0 + math.cos(math.pi * decay_ratio))
    return min_lr + coeff * (learning_rate - min_lr)

@torch.no_grad()
def estimate_loss():
    out = {}
    model.eval()
    for split in ['train', 'val']:
        losses = torch.zeros(eval_iters)
        for k in range(eval_iters):
            X, Y = get_batch(split)
            _, loss = model(X, Y)
            losses[k] = loss.item()
        out[split] = losses.mean()
    model.train()
    return out

# =============================================================================
# CICLO DI TRAINING
# =============================================================================
print("\n[START] Inizio ciclo di addestramento nanoGPT...")
start_time = time.time()

for iter_num in range(max_iters):
    # Aggiorna learning rate
    lr = get_lr(iter_num)
    for param_group in optimizer.param_groups:
        param_group['lr'] = lr

    # Valutazione periodica
    if iter_num % eval_interval == 0 or iter_num == max_iters - 1:
        losses = estimate_loss()
        print(f" Iter {iter_num:03d}/{max_iters} | Train Loss: {losses['train']:.4f} | Val Loss: {losses['val']:.4f} | LR: {lr:.2e}")

    # Forward & Backward Pass
    xb, yb = get_batch('train')
    logits, loss = model(xb, yb)
    optimizer.zero_grad(set_to_none=True)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0) # Gradient clipping
    optimizer.step()

elapsed = time.time() - start_time
print(f"\n[OK] Addestramento completato in {elapsed:.2f} secondi!")

# Salvataggio checkpoint
checkpoint = {
    'model_state_dict': model.state_dict(),
    'config': config,
    'iter_num': max_iters,
}
torch.save(checkpoint, OUTPUT_MODEL)
print(f"[CHECKPOINT] Pesi salvati con successo in: '{OUTPUT_MODEL}'")

# =============================================================================
# TEST DI GENERAZIONE FINALE
# =============================================================================
print("\n[TEST GENERATIVO] Generazione prompt 'User: Jarvis, come sono i sistemi?'...")
model.eval()
prompt = "User: Jarvis, come sono i sistemi?"
tokens = enc.encode(prompt)
x = torch.tensor([tokens], dtype=torch.long, device=device)

with torch.no_grad():
    y = model.generate(x, max_new_tokens=40, temperature=0.7, top_k=20)

output_str = enc.decode(y[0].tolist(), errors='replace')
print("-" * 50)
print(output_str)
print("-" * 50)
