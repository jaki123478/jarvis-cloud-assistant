# Deploy rapido

1. Pubblica questa cartella su un repository GitHub.
2. In Render scegli **New > Blueprint**, collega il repository e applica `render.yaml`.
3. Inserisci `LLM_API_KEY` nelle variabili segrete del servizio.
4. Copia l'URL `https://...onrender.com/` assegnato da Render in `MainActivity.java`, sostituendo `REPLACE_WITH_RENDER_URL`.
5. Ricompila con `gradle assembleDebug` e installa `app-debug.apk` sul telefono.

Non inserire mai la chiave OpenAI nell'app Android: deve rimanere nelle variabili segrete Render.
