# J.A.R.V.I.S. — architettura professionale

## Flusso Android

1. L'utente parla o pronuncia `Jarvis` / `Hey Jarvis`.
2. `SpeechRecognizer` acquisisce il comando; il watchdog riavvia la sessione dopo timeout o errore.
3. La wake phrase viene rimossa prima di inviare il comando al Core.
4. Android invia `/chat` con un `session_id` persistente e memoria locale recente.
5. Il backend sceglie il modello configurato, esegue gli strumenti e restituisce risposta e azione.
6. In caso di rete lenta o quota esaurita, l'app usa il fallback locale.

## WebRTC

- `JarvisWebRtcManager` gestisce audio-only WebRTC nell'app Android.
- `webrtc.js` gestisce il client browser.
- `/ws/webrtc/{room_id}` inoltra solo SDP/ICE; l'audio non passa dal server.
- Ogni stanza accetta massimo due partecipanti e scade dopo inattività.
- Per l'uso su reti diverse serve un TURN autenticato; STUN da solo non è sufficiente in ogni rete.

## Stabilità e osservabilità

- `/health/ready` espone solo stato operativo, contatori e limiti, mai chiavi.
- Le sessioni server sono isolate per dispositivo, limitate a 256 e rimosse dopo 24 ore.
- Android controlla il Core all'avvio e mostra lo stato `ONLINE`, `FALLBACK` o `OFFLINE`.
- Debug e release condividono la firma locale per consentire aggiornamenti senza conflitti.

## Progetti open-source di riferimento

- [Jarvis Android / Hermes](https://github.com/Bwarhness/jarvis-assistant): client Android con sessioni persistenti.
- [Jarvis Voice](https://github.com/strognoff/jarvis-voice): VAD, continuità vocale e TTS locale.
- [Wyoming](https://github.com/OHF-Voice/wyoming): protocollo separato per STT, TTS e wake word.
- [openWakeWord](https://www.home-assistant.io/voice_control/about_wake_word/): wake word locale addestrabile.
- [Butler](https://github.com/syumai/butler): separazione tra wake word on-device e sessione WebRTC.

## Limiti espliciti

WebRTC collega utenti JARVIS/browser. Non sostituisce un operatore telefonico e non può trasformare una chiamata cellulare Vodafone in una chiamata AI senza un servizio VoIP autorizzato. Le credenziali TURN e i provider vocali devono restare lato server.
