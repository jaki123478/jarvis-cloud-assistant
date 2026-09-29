# J.A.R.V.I.S. Android client

Questa cartella è riservata al client Android nativo. Dopo il deploy Render, impostare `JARVIS_BACKEND_URL` durante la build con l'URL HTTPS assegnato da Render:

```powershell
gradle assembleRelease -PJARVIS_BACKEND_URL=https://tuo-backend.example.com
```

Il valore predefinito è il backend Render attualmente distribuito (`https://jarvis-cloud-assistant-4dsr.onrender.com`). L'URL viene inserito in `BuildConfig`, senza modificare il codice Java.

Il backend deve restare su Render: l'app Android non contiene chiavi OpenAI.
