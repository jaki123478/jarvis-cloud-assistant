"""
J.A.R.V.I.S. // nanoGPT Core Engine
Basato sull'architettura ufficiale nanoGPT di Andrej Karpathy (https://github.com/karpathy/nanoGPT)
Ottimizzazioni:
- Fused QKV Projection
- FlashAttention-2 nativo via torch.nn.functional.scaled_dot_product_attention
- Weight Tying (wte.weight == lm_head.weight)
- BPE Tokenizer GPT-2 (tiktoken)
- Supporto from_pretrained('gpt2') per caricare i pesi ufficiali OpenAI in puro PyTorch
"""

import math
import inspect
from dataclasses import dataclass
import torch
import torch.nn as nn
from torch.nn import functional as F
import tiktoken

# =============================================================================
# CONFIGURAZIONE MODELLO
# =============================================================================
@dataclass
class GPTConfig:
    block_size: int = 256        # Lunghezza massima sequenza / contesto
    vocab_size: int = 50304      # Vocabolario GPT-2 (50257 arrotondato a multiplo di 64 per efficienza GPU)
    n_layer: int = 6             # Numero di blocchi Transformer (12 per gpt2 standard)
    n_head: int = 6              # Numero teste di attenzione (12 per gpt2 standard)
    n_embd: int = 384            # Dimensione embedding (768 per gpt2 standard)
    dropout: float = 0.0         # Per pre-training su larga scala 0.0, per fine-tuning 0.1
    bias: bool = True            # True: come GPT-2 nei Linear e LayerNorm

# =============================================================================
# 1. CAUSAL SELF-ATTENTION (FLASHATTENTION-2 FUSED)
# =============================================================================
class CausalSelfAttention(nn.Module):
    def __init__(self, config: GPTConfig):
        super().__init__()
        assert config.n_embd % config.n_head == 0
        # Calcolo simultaneo Q, K, V in una sola moltiplicazione di matrice per tutte le teste
        self.c_attn = nn.Linear(config.n_embd, 3 * config.n_embd, bias=config.bias)
        # Proiezione di output
        self.c_proj = nn.Linear(config.n_embd, config.n_embd, bias=config.bias)
        # Regularization
        self.attn_dropout = nn.Dropout(config.dropout)
        self.resid_dropout = nn.Dropout(config.dropout)
        self.n_head = config.n_head
        self.n_embd = config.n_embd
        self.dropout = config.dropout

        # Verifica supporto nativo FlashAttention
        self.flash = hasattr(torch.nn.functional, 'scaled_dot_product_attention')
        if not self.flash:
            print("[AVVISO] FlashAttention non disponibile, uso maschera causale classica.")
            self.register_buffer("bias", torch.tril(torch.ones(config.block_size, config.block_size))
                                        .view(1, 1, config.block_size, config.block_size))

    def forward(self, x):
        B, T, C = x.size() # Batch size, Sequence length, Embedding dim

        # Calcola query, key, values per tutte le teste in parallelo
        q, k, v = self.c_attn(x).split(self.n_embd, dim=2)
        k = k.view(B, T, self.n_head, C // self.n_head).transpose(1, 2) # (B, nh, T, hs)
        q = q.view(B, T, self.n_head, C // self.n_head).transpose(1, 2) # (B, nh, T, hs)
        v = v.view(B, T, self.n_head, C // self.n_head).transpose(1, 2) # (B, nh, T, hs)

        # Causal Self-Attention con FlashAttention-2 nativo
        if self.flash:
            y = torch.nn.functional.scaled_dot_product_attention(
                q, k, v, 
                attn_mask=None, 
                dropout_p=self.dropout if self.training else 0, 
                is_causal=True
            )
        else:
            # Fallback manuale classico
            att = (q @ k.transpose(-2, -1)) * (1.0 / math.sqrt(k.size(-1)))
            att = att.masked_fill(self.bias[:,:,:T,:T] == 0, float('-inf'))
            att = F.softmax(att, dim=-1)
            att = self.attn_dropout(att)
            y = att @ v

        # Riassemblaggio delle teste e proiezione finale
        y = y.transpose(1, 2).contiguous().view(B, T, C)
        y = self.resid_dropout(self.c_proj(y))
        return y

# =============================================================================
# 2. MULTI-LAYER PERCEPTRON (MLP / FEED-FORWARD)
# =============================================================================
class MLP(nn.Module):
    def __init__(self, config: GPTConfig):
        super().__init__()
        self.c_fc = nn.Linear(config.n_embd, 4 * config.n_embd, bias=config.bias)
        self.gelu = nn.GELU(approximate='tanh')
        self.c_proj = nn.Linear(4 * config.n_embd, config.n_embd, bias=config.bias)
        self.dropout = nn.Dropout(config.dropout)

    def forward(self, x):
        x = self.c_fc(x)
        x = self.gelu(x)
        x = self.c_proj(x)
        x = self.dropout(x)
        return x

# =============================================================================
# 3. BLOCCO TRANSFORMER (Pre-LayerNorm con Skip Connection)
# =============================================================================
class Block(nn.Module):
    def __init__(self, config: GPTConfig):
        super().__init__()
        self.ln_1 = nn.LayerNorm(config.n_embd, elementwise_affine=config.bias)
        self.attn = CausalSelfAttention(config)
        self.ln_2 = nn.LayerNorm(config.n_embd, elementwise_affine=config.bias)
        self.mlp = MLP(config)

    def forward(self, x):
        x = x + self.attn(self.ln_1(x))
        x = x + self.mlp(self.ln_2(x))
        return x

# =============================================================================
# 4. MODELLO COMPLETO GPT (nanoGPT)
# =============================================================================
class GPT(nn.Module):
    def __init__(self, config: GPTConfig):
        super().__init__()
        assert config.vocab_size is not None
        assert config.block_size is not None
        self.config = config

        self.transformer = nn.ModuleDict(dict(
            wte = nn.Embedding(config.vocab_size, config.n_embd),
            wpe = nn.Embedding(config.block_size, config.n_embd),
            drop = nn.Dropout(config.dropout),
            h = nn.ModuleList([Block(config) for _ in range(config.n_layer)]),
            ln_f = nn.LayerNorm(config.n_embd, elementwise_affine=config.bias),
        ))
        self.lm_head = nn.Linear(config.n_embd, config.vocab_size, bias=False)

        # WEIGHT TYING: wte.weight e lm_head.weight condividono gli stessi identici pesi
        # Riferimento: "Using the Output Embedding to Improve Language Models" (Press & Wolf 2016)
        self.transformer.wte.weight = self.lm_head.weight

        # Inizializzazione pesi
        self.apply(self._init_weights)
        # Scalatura speciale residua per i layer di proiezione c_proj (come da paper GPT-2)
        for pn, p in self.named_parameters():
            if pn.endswith('c_proj.weight'):
                torch.nn.init.normal_(p, mean=0.0, std=0.02 / math.sqrt(2 * config.n_layer))

    def _init_weights(self, module):
        if isinstance(module, nn.Linear):
            torch.nn.init.normal_(module.weight, mean=0.0, std=0.02)
            if module.bias is not None:
                torch.nn.init.zeros_(module.bias)
        elif isinstance(module, nn.Embedding):
            torch.nn.init.normal_(module.weight, mean=0.0, std=0.02)

    def forward(self, idx, targets=None):
        device = idx.device
        b, t = idx.size()
        assert t <= self.config.block_size, f"Sequenza di lunghezza {t} eccede block_size {self.config.block_size}"

        pos = torch.arange(0, t, dtype=torch.long, device=device) # shape (t)

        # Token + Positional embeddings
        tok_emb = self.transformer.wte(idx) # (b, t, n_embd)
        pos_emb = self.transformer.wpe(pos) # (t, n_embd)
        x = self.transformer.drop(tok_emb + pos_emb)

        for block in self.transformer.h:
            x = block(x)
        x = self.transformer.ln_f(x)

        if targets is not None:
            # Calcolo Loss per l'addestramento
            logits = self.lm_head(x)
            loss = F.cross_entropy(logits.view(-1, logits.size(-1)), targets.view(-1), ignore_index=-1)
        else:
            # Inferenza: calcola i logits solo per l'ultimo token
            logits = self.lm_head(x[:, [-1], :])
            loss = None

        return logits, loss

    @torch.no_grad()
    def generate(self, idx, max_new_tokens, temperature=0.8, top_k=50):
        """Generazione autoregressiva token-by-token con temperatura e Top-K sampling."""
        for _ in range(max_new_tokens):
            idx_cond = idx if idx.size(1) <= self.config.block_size else idx[:, -self.config.block_size:]
            logits, _ = self(idx_cond)
            logits = logits[:, -1, :] / max(temperature, 1e-5)

            if top_k is not None:
                v, _ = torch.topk(logits, min(top_k, logits.size(-1)))
                logits[logits < v[:, [-1]]] = -float('Inf')

            probs = F.softmax(logits, dim=-1)
            idx_next = torch.multinomial(probs, num_samples=1)
            idx = torch.cat((idx, idx_next), dim=1)

        return idx

    def configure_optimizers(self, weight_decay, learning_rate, betas, device_type):
        """Separa i parametri tra quelli soggetti a weight decay e quelli esclusi (bias, LayerNorm)."""
        decay = set()
        no_decay = set()
        whitelist_weight_modules = (torch.nn.Linear, )
        blacklist_weight_modules = (torch.nn.LayerNorm, torch.nn.Embedding)
        for mn, m in self.named_modules():
            for pn, p in m.named_parameters():
                fpn = '%s.%s' % (mn, pn) if mn else pn
                if pn.endswith('bias'):
                    no_decay.add(fpn)
                elif pn.endswith('weight') and isinstance(m, whitelist_weight_modules):
                    decay.add(fpn)
                elif pn.endswith('weight') and isinstance(m, blacklist_weight_modules):
                    no_decay.add(fpn)

        # Weight tying correction
        decay.remove('lm_head.weight')

        param_dict = {pn: p for pn, p in self.named_parameters()}
        optim_groups = [
            {"params": [param_dict[pn] for pn in sorted(list(decay))], "weight_decay": weight_decay},
            {"params": [param_dict[pn] for pn in sorted(list(no_decay))], "weight_decay": 0.0},
        ]
        
        optimizer = torch.optim.AdamW(optim_groups, lr=learning_rate, betas=betas)
        return optimizer

    @classmethod
    def from_pretrained(cls, model_type="gpt2"):
        """Carica i pesi ufficiali GPT-2 di OpenAI direttamente in nanoGPT."""
        assert model_type in {'gpt2', 'gpt2-medium', 'gpt2-large', 'gpt2-xl'}
        from transformers import GPT2LMHeadModel
        print(f"[nanoGPT] Download/Caricamento pesi pre-addestrati OpenAI '{model_type}'...")

        config_args = {
            'gpt2':         dict(n_layer=12, n_head=12, n_embd=768),  # 124M params
            'gpt2-medium':  dict(n_layer=24, n_head=16, n_embd=1024), # 350M params
            'gpt2-large':   dict(n_layer=36, n_head=20, n_embd=1280), # 774M params
            'gpt2-xl':      dict(n_layer=48, n_head=25, n_embd=1600), # 1558M params
        }[model_type]
        config_args['vocab_size'] = 50257
        config_args['block_size'] = 1024
        config_args['bias'] = True

        config = GPTConfig(**config_args)
        model = cls(config)
        sd = model.state_dict()
        sd_keys = [k for k in sd.keys() if not k.endswith('.attn.bias')]

        # Carica il modello HuggingFace per estrarre i pesi
        model_hf = GPT2LMHeadModel.from_pretrained(model_type)
        sd_hf = model_hf.state_dict()
        sd_keys_hf = [k for k in sd_hf.keys() if not k.endswith('.attn.masked_bias') and not k.endswith('.attn.bias')]

        # Trasposizione pesi lineari Conv1D di HuggingFace per matchare nn.Linear PyTorch
        transposed = ['attn.c_attn.weight', 'attn.c_proj.weight', 'mlp.c_fc.weight', 'mlp.c_proj.weight']
        for k in sd_keys_hf:
            if any(k.endswith(w) for w in transposed):
                with torch.no_grad():
                    sd[k].copy_(sd_hf[k].t())
            else:
                with torch.no_grad():
                    sd[k].copy_(sd_hf[k])

        print(f"[nanoGPT] Modello '{model_type}' caricato con successo ({sum(p.numel() for p in model.parameters()):,} parametri).")
        return model

# =============================================================================
# TEST DI INFERENZA & DEMO
# =============================================================================
if __name__ == '__main__':
    print("=" * 65)
    print(" J.A.R.V.I.S. // nanoGPT Architecture Core")
    print(f" PyTorch version: {torch.__version__} | Device: {'CUDA' if torch.cuda.is_available() else 'CPU'}")
    print("=" * 65)

    # Configurazione compatta Stark Lite
    config = GPTConfig(
        block_size=128,
        vocab_size=50304,
        n_layer=4,
        n_head=4,
        n_embd=256,
        dropout=0.1
    )

    model = GPT(config)
    print(f"Numero Parametri Totali: {sum(p.numel() for p in model.parameters()):,}")
    print(f"FlashAttention Attivo: {model.transformer.h[0].attn.flash}")

    # Prova BPE Tokenizer GPT-2
    enc = tiktoken.get_encoding("gpt2")
    prompt = "J.A.R.V.I.S.: Tutti i sistemi sono pronti, signore."
    tokens = enc.encode(prompt)
    input_tensor = torch.tensor([tokens], dtype=torch.long)

    import sys
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')

    print(f"\nPrompt: '{prompt}' ({len(tokens)} token BPE)")
    generated = model.generate(input_tensor, max_new_tokens=20, temperature=0.8)
    output_text = enc.decode(generated[0].tolist(), errors='replace')
    print("Generazione output:\n", output_text)
    print("\n[OK] nanoGPT Core pronto per training e inferenza!")
