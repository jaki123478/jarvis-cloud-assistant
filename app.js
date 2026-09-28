/**
 * J.A.R.V.I.S. HUD ASSISTANT - ARCHITECTURE CORE
 * Progressive Web App for Android Mobile & Desktop
 * Features:
 * - Screen Wake Lock API (keeps screen active during sessions)
 * - Battery Status API (real-time power telemetry)
 * - Web Speech API (continuous voice streaming & 'Jarvis' wake-word)
 * - TTS Engine (Native window.speechSynthesis + ElevenLabs fallback)
 * - Arc Reactor Animated Canvas Visualizer
 * - LLM Tool Calling (OpenAI / xAI Grok / Local Offline Engine)
 * - Android & Desktop Deep-Linking (Spotify, WhatsApp, Phone Calls, Maps, Search)
 */

(function () {
  'use strict';

  // =========================================================================
  // GLOBAL STATE
  // =========================================================================
  const state = {
    // Systems
    isListening: false,
    isSpeaking: false,
    isProcessing: false,
    currentStatus: 'STANDBY', // 'STANDBY', 'LISTENING', 'PROCESSING', 'SPEAKING', 'ERROR'
    
    // Wake Lock
    wakeLockSentinel: null,
    wakeLockRequested: false,

    // Battery
    battery: {
      level: 1.0,
      charging: false,
      supported: false
    },

    // Speech Recognition & Watchdog
    recognition: null,
    isSpeechActive: false,
    wakeWordDetected: false,
    lastTranscript: '',
    watchdogInterval: null,
    reconnectTimer: null,

    // Settings (persisted in localStorage)
    settings: {
      llmProvider: 'fastapi', // 'fastapi', 'nanogpt', 'local', 'openai', 'xai'
      backendUrl: 'http://localhost:8000',
      apiKey: '',
      ttsEngine: 'edge-server', // 'edge-server', 'native', 'elevenlabs'
      edgeTtsVoice: '',         // '' = default server (Diego), oppure alias es. 'it-male-multi'
      edgeTtsRate: '+0%',       // Velocità: '-10%', '+0%', '+15%'
      edgeTtsPitch: '+0Hz',     // Tono: '-3Hz', '+0Hz', '+5Hz'
      elevenLabsKey: '',
      elevenLabsVoice: '21m00Tcm4TlvDq8ikWAM',
      lang: 'it-IT',
      wakeWordEnabled: true,
      continuousRec: true
    },

    // Real-Time Web Audio FFT Analyzer
    audioCtx: null,
    analyser: null,
    dataArray: null,
    micStream: null,
    micSource: null,
    speechPulseValue: 0
  };

  // =========================================================================
  // DOM ELEMENTS
  // =========================================================================
  const DOM = {
    // Header & Telemetry
    systemStatusText: document.getElementById('system-status-text'),
    systemStatusIndicator: document.getElementById('system-status-indicator'),
    hudClock: document.getElementById('hud-clock'),
    hudDate: document.getElementById('hud-date'),
    fpsVal: document.getElementById('fps-val'),
    pingVal: document.getElementById('ping-val'),
    engineModeBtn: document.getElementById('engine-mode-btn'),
    engineModeLabel: document.getElementById('engine-mode-label'),
    wakeLockToggle: document.getElementById('wake-lock-toggle'),
    wakeLockLabel: document.getElementById('wake-lock-label'),
    batteryWidget: document.getElementById('battery-widget'),
    batteryChargingSymbol: document.getElementById('battery-charging-symbol'),
    batteryLevelFill: document.getElementById('battery-level-fill'),
    batteryPctLabel: document.getElementById('battery-pct-label'),
    openSettingsBtn: document.getElementById('open-settings-btn'),

    // Left Diagnostics
    metricBatteryVal: document.getElementById('metric-battery-val'),
    batterySegments: document.getElementById('battery-segments'),
    metricChargingState: document.getElementById('metric-charging-state'),
    metricWakewordVal: document.getElementById('metric-wakeword-val'),
    diagMicState: document.getElementById('diag-mic-state'),
    diagTtsState: document.getElementById('diag-tts-state'),
    diagWakelockState: document.getElementById('diag-wakelock-state'),

    // Center Stage & Reactor
    reactorTiltWrapper: document.getElementById('reactor-tilt-wrapper'),
    reactorCanvas: document.getElementById('reactor-canvas'),
    reactorTouchBtn: document.getElementById('reactor-touch-btn'),
    waveformContainer: document.getElementById('waveform-container'),
    waveBars: document.querySelectorAll('.wave-bar'),
    statusBanner: document.getElementById('status-banner'),
    statusBannerText: document.getElementById('status-banner-text'),
    hudMicMasterBtn: document.getElementById('hud-mic-master-btn'),
    micGlyph: document.getElementById('mic-glyph'),
    micMasterLabel: document.getElementById('mic-master-label'),
    micStatusBadge: document.getElementById('mic-status-badge'),
    btnRequestMic: document.getElementById('btn-request-mic'),
    httpsAlertBanner: document.getElementById('https-alert-banner'),
    transcriptBox: document.getElementById('transcript-box'),
    transcriptText: document.getElementById('transcript-text'),

    // Right Terminal Log
    messagesContainer: document.getElementById('messages-container'),
    bootTime: document.getElementById('boot-time'),
    manualInputForm: document.getElementById('manual-input-form'),
    manualTextInput: document.getElementById('manual-text-input'),

    // Settings Modal
    settingsModal: document.getElementById('settings-modal'),
    closeSettingsBtn: document.getElementById('close-settings-btn'),
    saveSettingsBtn: document.getElementById('save-settings-btn'),
    settingLlmProvider: document.getElementById('setting-llm-provider'),
    backendUrlGroup: document.getElementById('backend-url-group'),
    settingBackendUrl: document.getElementById('setting-backend-url'),
    apiKeyGroup: document.getElementById('api-key-group'),
    settingApiKey: document.getElementById('setting-api-key'),
    settingTtsEngine: document.getElementById('setting-tts-engine'),
    elevenlabsSettingsGroup: document.getElementById('elevenlabs-settings-group'),
    settingElevenlabsKey: document.getElementById('setting-elevenlabs-key'),
    settingElevenlabsVoice: document.getElementById('setting-elevenlabs-voice'),
    settingLang: document.getElementById('setting-lang'),
    settingWakeWord: document.getElementById('setting-wake-word'),
    settingContinuousRec: document.getElementById('setting-continuous-rec'),
    testVoiceBtn: document.getElementById('test-voice-btn'),
    testFxBtn: document.getElementById('test-fx-btn'),
    btnTestSpeaker: document.getElementById('btn-test-speaker'),
    jarvisAudioPlayer: document.getElementById('jarvis-audio-player')
  };

  // =========================================================================
  // INITIALIZATION & SERVICE WORKER
  // =========================================================================
  function init() {
    try { loadSettings(); } catch (e) { console.error('[JARVIS INIT] loadSettings error:', e); }
    try { initClock(); } catch (e) { console.error('[JARVIS INIT] initClock error:', e); }
    try { initTelemetry(); } catch (e) { console.error('[JARVIS INIT] initTelemetry error:', e); }
    try { initEngineToggle(); } catch (e) { console.error('[JARVIS INIT] initEngineToggle error:', e); }
    try { initParallax(); } catch (e) { console.error('[JARVIS INIT] initParallax error:', e); }
    try { initAudioContext(); } catch (e) { console.error('[JARVIS INIT] initAudioContext error:', e); }
    try { initCanvasReactor(); } catch (e) { console.error('[JARVIS INIT] initCanvasReactor error:', e); }
    try { initBatteryAPI(); } catch (e) { console.error('[JARVIS INIT] initBatteryAPI error:', e); }
    try { initWakeLock(); } catch (e) { console.error('[JARVIS INIT] initWakeLock error:', e); }
    try { initSpeechRecognition(); } catch (e) { console.error('[JARVIS INIT] initSpeechRecognition error:', e); }
    try { registerServiceWorker(); } catch (e) { console.error('[JARVIS INIT] registerServiceWorker error:', e); }
    try { initVisionScanner(); } catch (e) { console.error('[JARVIS INIT] initVisionScanner error:', e); }
    try { bindEvents(); } catch (e) { console.error('[JARVIS INIT] bindEvents error:', e); }
    try { updateUIStatus('STANDBY'); } catch (e) { console.error('[JARVIS INIT] updateUIStatus error:', e); }
  }

  function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', () => {
        navigator.serviceWorker.register('./service-worker.js')
          .then((reg) => console.log('[JARVIS] Service Worker registrato con scope:', reg.scope))
          .catch((err) => console.warn('[JARVIS] Registrazione Service Worker fallita:', err));
      });
    }
  }

  // =========================================================================
  // SETTINGS & STORAGE
  // =========================================================================
  function loadSettings() {
    try {
      const stored = localStorage.getItem('jarvis_hud_settings');
      if (stored) {
        state.settings = Object.assign(state.settings, JSON.parse(stored));
      }
      // MIGRAZIONE CRITICA: abilita sempre Edge Neural TTS di default se non impostato su ElevenLabs
      if (!state.settings.ttsEngine || state.settings.ttsEngine === 'native') {
        state.settings.ttsEngine = 'edge-server';
      }
      // MIGRAZIONE CRITICA: assicura sempre che il provider LLM sia il Core Server Stark
      if (!state.settings.llmProvider || state.settings.llmProvider === 'local' || state.settings.llmProvider === 'nanogpt') {
        state.settings.llmProvider = 'fastapi';
      }
      try {
        localStorage.setItem('jarvis_hud_settings', JSON.stringify(state.settings));
      } catch (err) {}
    } catch (e) {
      console.warn('[JARVIS] Impossibile caricare impostazioni da localStorage:', e);
      state.settings.ttsEngine = 'edge-server';
      state.settings.llmProvider = 'fastapi';
    }
    syncSettingsForm();
  }

  function saveSettings() {
    state.settings.llmProvider = DOM.settingLlmProvider.value;
    state.settings.backendUrl = (DOM.settingBackendUrl ? DOM.settingBackendUrl.value.trim() : '') || 'http://localhost:8000';
    state.settings.apiKey = DOM.settingApiKey.value.trim();
    state.settings.ttsEngine = DOM.settingTtsEngine.value;
    // Edge TTS settings
    const edgeVoiceEl = document.getElementById('setting-edge-tts-voice');
    const edgeRateEl = document.getElementById('setting-edge-tts-rate');
    const edgePitchEl = document.getElementById('setting-edge-tts-pitch');
    state.settings.edgeTtsVoice = edgeVoiceEl ? edgeVoiceEl.value : '';
    state.settings.edgeTtsRate = edgeRateEl ? edgeRateEl.value : '+0%';
    state.settings.edgeTtsPitch = edgePitchEl ? edgePitchEl.value : '+0Hz';
    state.settings.elevenLabsKey = DOM.settingElevenlabsKey.value.trim();
    state.settings.elevenLabsVoice = DOM.settingElevenlabsVoice.value.trim() || '21m00Tcm4TlvDq8ikWAM';
    state.settings.lang = DOM.settingLang.value;
    state.settings.wakeWordEnabled = DOM.settingWakeWord.checked;
    state.settings.continuousRec = DOM.settingContinuousRec.checked;

    // Show/hide engine-specific settings
    const edgeGroup = document.getElementById('edge-tts-settings-group');
    const elevenGroup = document.getElementById('elevenlabs-settings-group');
    if (edgeGroup) edgeGroup.style.display = state.settings.ttsEngine === 'edge-server' ? 'block' : 'none';
    if (elevenGroup) elevenGroup.style.display = state.settings.ttsEngine === 'elevenlabs' ? 'block' : 'none';

    try {
      localStorage.setItem('jarvis_hud_settings', JSON.stringify(state.settings));
    } catch (e) {
      console.error(e);
    }

    // Refresh telemetry
    DOM.metricWakewordVal.textContent = state.settings.wakeWordEnabled ? 'ATTIVO' : 'DISATTIVO';
    DOM.diagTtsState.textContent = state.settings.ttsEngine === 'edge-server' ? 'EDGE NEURAL TTS' : state.settings.ttsEngine === 'elevenlabs' ? 'ELEVENLABS API' : 'WEB SPEECH API';

    // Restart speech recognition if language changed
    if (state.recognition) {
      state.recognition.lang = state.settings.lang;
    }

    playFx('action');
    addLogEntry('JARVIS', 'Configurazione e protocolli di sistema aggiornati, signore.');
    closeModal();
  }

  function syncSettingsForm() {
    DOM.settingLlmProvider.value = state.settings.llmProvider;
    if (DOM.settingBackendUrl) {
      DOM.settingBackendUrl.value = state.settings.backendUrl || 'http://localhost:8000';
    }
    DOM.settingApiKey.value = state.settings.apiKey;
    DOM.settingTtsEngine.value = state.settings.ttsEngine;
    // Sync Edge TTS settings
    const edgeVoiceEl = document.getElementById('setting-edge-tts-voice');
    const edgeRateEl = document.getElementById('setting-edge-tts-rate');
    const edgePitchEl = document.getElementById('setting-edge-tts-pitch');
    if (edgeVoiceEl) edgeVoiceEl.value = state.settings.edgeTtsVoice || '';
    if (edgeRateEl) edgeRateEl.value = state.settings.edgeTtsRate || '+0%';
    if (edgePitchEl) edgePitchEl.value = state.settings.edgeTtsPitch || '+0Hz';
    DOM.settingElevenlabsKey.value = state.settings.elevenLabsKey;
    DOM.settingElevenlabsVoice.value = state.settings.elevenLabsVoice;
    DOM.settingLang.value = state.settings.lang;
    DOM.settingWakeWord.checked = state.settings.wakeWordEnabled;
    DOM.settingContinuousRec.checked = state.settings.continuousRec;

    toggleProviderVisibility();
    toggleTtsVisibility();

    DOM.metricWakewordVal.textContent = state.settings.wakeWordEnabled ? 'ATTIVO' : 'DISATTIVO';
    DOM.diagTtsState.textContent = state.settings.ttsEngine === 'edge-server' ? 'EDGE NEURAL TTS' : state.settings.ttsEngine === 'elevenlabs' ? 'ELEVENLABS API' : 'WEB SPEECH API';
  }

  function toggleProviderVisibility() {
    const provider = DOM.settingLlmProvider.value;
    if (DOM.backendUrlGroup) {
      DOM.backendUrlGroup.style.display = provider === 'fastapi' ? 'flex' : 'none';
    }
    DOM.apiKeyGroup.style.display = (provider === 'openai' || provider === 'xai') ? 'flex' : 'none';
  }

  function toggleTtsVisibility() {
    const engine = DOM.settingTtsEngine.value;
    const edgeGroup = document.getElementById('edge-tts-settings-group');
    if (edgeGroup) edgeGroup.style.display = engine === 'edge-server' ? 'block' : 'none';
    DOM.elevenlabsSettingsGroup.style.display = engine === 'elevenlabs' ? 'block' : 'none';
  }

  // =========================================================================
  // CLOCK & TELEMETRY
  // =========================================================================
  function initClock() {
    function update() {
      const now = new Date();
      const timeStr = now.toTimeString().split(' ')[0];
      DOM.hudClock.textContent = timeStr;

      const dateStr = now.toLocaleDateString('it-IT', {
        weekday: 'short',
        day: '2-digit',
        month: 'short',
        year: 'numeric'
      }).toUpperCase();
      DOM.hudDate.textContent = `STARK OS // ${dateStr}`;

      if (DOM.bootTime.textContent === 'READY') {
        DOM.bootTime.textContent = timeStr;
      }
    }
    update();
    setInterval(update, 1000);
  }

  function resolveBackendEndpoint() {
    const currentOrigin = (window.location.origin && window.location.origin.startsWith('http')) ? window.location.origin : 'http://localhost:8000';
    let baseEndpoint = currentOrigin;
    if (state.settings.backendUrl && state.settings.backendUrl !== 'http://localhost:8000') {
      if (window.location.protocol === 'https:' && state.settings.backendUrl.startsWith('http:')) {
        baseEndpoint = currentOrigin;
      } else {
        baseEndpoint = state.settings.backendUrl;
      }
    }
    return baseEndpoint.replace(/\/+$/, '');
  }

  // =========================================================================
  // TELEMETRY: REAL-TIME FPS & SERVER LATENCY (PING)
  // =========================================================================
  function initTelemetry() {
    let lastTime = performance.now();
    let frames = 0;

    function calcFps(now) {
      frames++;
      if (now - lastTime >= 1000) {
        const fps = Math.round((frames * 1000) / (now - lastTime));
        if (DOM.fpsVal) DOM.fpsVal.textContent = fps;
        frames = 0;
        lastTime = now;
      }
      requestAnimationFrame(calcFps);
    }
    requestAnimationFrame(calcFps);

    async function measurePing() {
      if (!DOM.pingVal) return;
      const t0 = performance.now();
      try {
        const baseEndpoint = resolveBackendEndpoint();
        const res = await fetch(`${baseEndpoint}/ping`, { cache: 'no-store' });
        if (res.ok) {
          const rtt = Math.round(performance.now() - t0);
          DOM.pingVal.textContent = `${rtt} ms`;
        } else {
          DOM.pingVal.textContent = 'STANDBY';
        }
      } catch (e) {
        DOM.pingVal.textContent = 'OFFLINE';
      }
    }

    measurePing();
    setInterval(measurePing, 10000);
  }

  // =========================================================================
  // ENGINE SELECTOR & PROTOCOL TOGGLE
  // =========================================================================
  function initEngineToggle() {
    if (!DOM.engineModeBtn || !DOM.engineModeLabel) return;

    function updateLabel() {
      if (state.settings.llmProvider === 'fastapi') {
        DOM.engineModeLabel.textContent = 'FASTAPI (DDG & TOOLS)';
      } else if (state.settings.llmProvider === 'nanogpt') {
        DOM.engineModeLabel.textContent = 'NANOGPT (OFFLINE PYTORCH)';
      } else if (state.settings.llmProvider === 'openai') {
        DOM.engineModeLabel.textContent = 'OPENAI GPT-4O';
      } else if (state.settings.llmProvider === 'xai') {
        DOM.engineModeLabel.textContent = 'XAI GROK';
      } else {
        DOM.engineModeLabel.textContent = 'BROWSER OFFLINE';
      }
    }

    updateLabel();

    DOM.engineModeBtn.addEventListener('click', () => {
      triggerHaptic(30);
      playFx('action');
      if (state.settings.llmProvider === 'fastapi') {
        state.settings.llmProvider = 'nanogpt';
      } else if (state.settings.llmProvider === 'nanogpt') {
        state.settings.llmProvider = 'local';
      } else {
        state.settings.llmProvider = 'fastapi';
      }
      try {
        localStorage.setItem('jarvis_hud_settings', JSON.stringify(state.settings));
      } catch (e) {}
      updateLabel();
      DOM.settingLlmProvider.value = state.settings.llmProvider;
      addLogEntry('JARVIS', `Protocollo neurale commutato: ${DOM.engineModeLabel.textContent}, signore.`);
      speak(`Protocollo neurale commutato su ${DOM.engineModeLabel.textContent}`);
    });
  }

  // =========================================================================
  // HOLOGRAPHIC 3D GYROSCOPE & MOUSE PARALLAX
  // =========================================================================
  function initParallax() {
    const tiltWrapper = DOM.reactorTiltWrapper || document.getElementById('reactor-tilt-wrapper');
    if (!tiltWrapper) return;

    // Mobile Gyroscope Parallax (DeviceOrientation API)
    if (window.DeviceOrientationEvent) {
      window.addEventListener('deviceorientation', (e) => {
        if (e.gamma !== null && e.beta !== null) {
          const tiltX = Math.max(-14, Math.min(14, (e.gamma / 3)));
          const tiltY = Math.max(-14, Math.min(14, ((e.beta - 45) / 3)));
          tiltWrapper.style.transform = `rotateY(${tiltX.toFixed(1)}deg) rotateX(${(-tiltY).toFixed(1)}deg)`;
        }
      });
    }

    // Desktop Mousemove Parallax
    window.addEventListener('mousemove', (e) => {
      if (window.innerWidth <= 768) return;
      const cx = window.innerWidth / 2;
      const cy = window.innerHeight / 2;
      const dx = (e.clientX - cx) / cx;
      const dy = (e.clientY - cy) / cy;
      tiltWrapper.style.transform = `rotateY(${(dx * 10).toFixed(1)}deg) rotateX(${(-dy * 10).toFixed(1)}deg)`;
    });
  }

  // =========================================================================
  // AUDIO SYNTHESIZER & REAL-TIME FFT ANALYZER
  // =========================================================================
  function initAudioContext() {
    try {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) {
        state.audioCtx = new AudioContextClass();
        state.analyser = state.audioCtx.createAnalyser();
        state.analyser.fftSize = 64;
        state.analyser.smoothingTimeConstant = 0.8;
        state.dataArray = new Uint8Array(state.analyser.frequencyBinCount);
      }
    } catch (e) {
      console.warn('[JARVIS] Web Audio API non supportata:', e);
    }
  }

  async function initMicAudioAnalyser() {
    if (!state.audioCtx) initAudioContext();
    if (!state.audioCtx) return;
    resumeAudio();

    if (state.micSource) return;

    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        state.micStream = stream;
        state.micSource = state.audioCtx.createMediaStreamSource(stream);
        // Feed into analyser for FFT visualizer WITHOUT routing to destination (avoids feedback screech)
        state.micSource.connect(state.analyser);
        console.log('[JARVIS] Microfono collegato con successo all\'analizzatore spettrale FFT.');
      }
    } catch (err) {
      console.warn('[JARVIS] Microfono analyser non disponibile:', err);
    }
  }

  function unlockAudio() {
    if (!state.audioCtx) initAudioContext();
    if (state.audioCtx && state.audioCtx.state === 'suspended') {
      state.audioCtx.resume().catch(() => {});
    }
    // Sblocca l'elemento audio HTML5 permanente autorizzandolo al primo tocco
    if (DOM.jarvisAudioPlayer) {
      try {
        DOM.jarvisAudioPlayer.play().then(() => {
          DOM.jarvisAudioPlayer.pause();
          DOM.jarvisAudioPlayer.currentTime = 0;
        }).catch(() => {});
      } catch (e) {}
    }
    // Sblocca speech synthesis nativo
    if ('speechSynthesis' in window && window.speechSynthesis.paused) {
      window.speechSynthesis.resume();
    }
  }

  function resumeAudio() {
    unlockAudio();
  }

  function playFx(type) {
    if (!state.audioCtx) return;
    resumeAudio();

    const ctx = state.audioCtx;
    const now = ctx.currentTime;

    if (type === 'activate') {
      // Jarvis activation rising sci-fi arpeggio
      const osc1 = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      const gain = ctx.createGain();

      osc1.type = 'sine';
      osc2.type = 'triangle';

      osc1.frequency.setValueAtTime(440, now);
      osc1.frequency.exponentialRampToValueAtTime(880, now + 0.15);

      osc2.frequency.setValueAtTime(554.37, now + 0.08);
      osc2.frequency.exponentialRampToValueAtTime(1108.73, now + 0.25);

      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);

      osc1.connect(gain);
      osc2.connect(gain);
      gain.connect(ctx.destination);

      osc1.start(now);
      osc2.start(now + 0.08);
      osc1.stop(now + 0.15);
      osc2.stop(now + 0.25);
    } else if (type === 'action') {
      // Action ping
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(987.77, now);
      osc.frequency.setValueAtTime(1318.51, now + 0.07);

      gain.gain.setValueAtTime(0.12, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.3);

      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now);
      osc.stop(now + 0.3);
    } else if (type === 'deactivate') {
      // Falling pitch
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(600, now);
      osc.frequency.exponentialRampToValueAtTime(200, now + 0.2);

      gain.gain.setValueAtTime(0.12, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.2);

      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now);
      osc.stop(now + 0.2);
    } else if (type === 'wake') {
      // High-tech wake-word chirp (beepStart)
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(880, now);
      osc.frequency.exponentialRampToValueAtTime(1320, now + 0.12);
      gain.gain.setValueAtTime(0.18, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.15);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now);
      osc.stop(now + 0.15);
    } else if (type === 'done') {
      // Command confirmation chime (beepDone)
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(1320, now);
      osc.frequency.exponentialRampToValueAtTime(660, now + 0.18);
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.2);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now);
      osc.stop(now + 0.2);
    }
  }

  // =========================================================================
  // SCREEN WAKE LOCK API (Evita spegnimento display smartphone)
  // =========================================================================
  // 1. Evita che lo schermo del telefono si spenga durante l'uso (Screen Wake Lock)
  let wakeLock = null;

  async function requestWakeLock(notify = true) {
    try {
      if ('wakeLock' in navigator) {
        wakeLock = await navigator.wakeLock.request('screen');
        state.wakeLockSentinel = wakeLock;
        state.wakeLockRequested = true;

        DOM.wakeLockToggle.classList.add('active');
        DOM.wakeLockLabel.textContent = 'WAKE LOCK: ON';
        DOM.diagWakelockState.textContent = 'ATTIVO (SCHERMO SVEGLIO)';

        if (notify) {
          playFx('action');
          addLog("Display WakeLock: ATTIVO");
        }

        wakeLock.addEventListener('release', () => {
          console.warn('[JARVIS] Display WakeLock rilasciato dal sistema');
          if (!state.wakeLockRequested) {
            DOM.wakeLockToggle.classList.remove('active');
            DOM.wakeLockLabel.textContent = 'WAKE LOCK: OFF';
            DOM.diagWakelockState.textContent = 'DISATTIVO';
          }
        });
      }
    } catch (err) {
      console.warn("Wake Lock non supportato o rifiutato:", err);
      DOM.diagWakelockState.textContent = 'ERRORE ACQUISIZIONE';
    }
  }

  async function releaseWakeLock(notify = true) {
    state.wakeLockRequested = false;
    if (wakeLock || state.wakeLockSentinel) {
      try {
        const sentinel = wakeLock || state.wakeLockSentinel;
        await sentinel.release();
        wakeLock = null;
        state.wakeLockSentinel = null;
      } catch (err) {
        console.warn(err);
      }
    }
    DOM.wakeLockToggle.classList.remove('active');
    DOM.wakeLockLabel.textContent = 'WAKE LOCK: OFF';
    DOM.diagWakelockState.textContent = 'DISATTIVO';

    if (notify) {
      playFx('deactivate');
      addLog("Display WakeLock: DISATTIVO");
    }
  }

  function toggleWakeLock() {
    triggerHaptic(40);
    if (state.wakeLockRequested) {
      releaseWakeLock(true);
    } else {
      requestWakeLock(true);
    }
  }

  async function initWakeLock() {
    if ('wakeLock' in navigator) {
      DOM.diagWakelockState.textContent = 'SUPPORTATO (OFF)';
      
      // Auto re-acquire wake lock if page becomes visible again
      document.addEventListener('visibilitychange', async () => {
        if (state.wakeLockRequested && document.visibilityState === 'visible') {
          console.log('[JARVIS] Pagina visibile: riacquisizione Screen Wake Lock...');
          await requestWakeLock(false);
        }
      });
    } else {
      DOM.diagWakelockState.textContent = 'NON SUPPORTATO';
      DOM.wakeLockToggle.disabled = true;
      DOM.wakeLockLabel.textContent = 'WAKE LOCK: N/A';
    }
  }

  // =========================================================================
  // BATTERY STATUS API (Lettura dati reali della batteria smartphone)
  // =========================================================================
  // 2. Legge la vera batteria dello smartphone invece di dati finti
  async function initBatteryAPI() {
    if ('getBattery' in navigator) {
      navigator.getBattery().then(battery => {
        state.battery.supported = true;

        function updateBattery() {
          const level = Math.round(battery.level * 100);
          state.battery.level = battery.level;
          state.battery.charging = battery.charging;

          // Aggiorna ram-val e ram-bar richiesti per telemetry smartphone
          const ramVal = document.getElementById('ram-val');
          const ramBar = document.getElementById('ram-bar');
          if (ramVal && ramBar) {
            ramVal.innerText = `${level}% (${battery.charging ? 'IN CARICA' : 'BATTERIA'})`;
            ramBar.style.width = level + "%";
            if (level <= 20) {
              ramBar.style.backgroundColor = 'var(--hud-red)';
              ramBar.style.boxShadow = '0 0 8px var(--hud-red)';
            } else if (level <= 40) {
              ramBar.style.backgroundColor = 'var(--hud-gold)';
              ramBar.style.boxShadow = '0 0 8px var(--hud-gold)';
            } else {
              ramBar.style.backgroundColor = 'var(--hud-cyan)';
              ramBar.style.boxShadow = '0 0 8px var(--hud-cyan)';
            }
          }

          // Aggiornamento telemetria HUD
          DOM.batteryPctLabel.textContent = `${level}%`;
          DOM.metricBatteryVal.textContent = `${level}%`;
          DOM.batteryLevelFill.style.width = `${level}%`;

          // Indicatore fulmine di ricarica
          if (battery.charging) {
            DOM.batteryChargingSymbol.classList.add('active');
            DOM.metricChargingState.textContent = 'ALIMENTAZIONE: IN CARICA ⚡';
          } else {
            DOM.batteryChargingSymbol.classList.remove('active');
            DOM.metricChargingState.textContent = 'ALIMENTAZIONE: BATTERIA';
          }

          // Color shifts in base alla riserva energetica
          if (level <= 20) {
            DOM.batteryLevelFill.style.backgroundColor = 'var(--hud-red)';
            DOM.batteryLevelFill.style.boxShadow = '0 0 5px var(--hud-red)';
          } else if (level <= 40) {
            DOM.batteryLevelFill.style.backgroundColor = 'var(--hud-gold)';
            DOM.batteryLevelFill.style.boxShadow = '0 0 5px var(--hud-gold)';
          } else {
            DOM.batteryLevelFill.style.backgroundColor = 'var(--hud-cyan)';
            DOM.batteryLevelFill.style.boxShadow = '0 0 5px var(--hud-cyan)';
          }

          // Update segmented LEDs
          updateBatterySegments(level);
        }

        updateBattery();
        battery.addEventListener('levelchange', updateBattery);
        battery.addEventListener('chargingchange', updateBattery);
      }).catch(err => {
        console.warn('[JARVIS] Battery API error:', err);
        fallbackBatteryUI();
      });
    } else {
      fallbackBatteryUI();
    }
  }

  function fallbackBatteryUI() {
    state.battery.supported = false;
    DOM.batteryPctLabel.textContent = '100%';
    DOM.metricBatteryVal.textContent = 'SIMULATO';
    DOM.metricChargingState.textContent = 'API NON DISPONIBILE SUL BROWSER';
    const ramVal = document.getElementById('ram-val');
    const ramBar = document.getElementById('ram-bar');
    if (ramVal && ramBar) {
      ramVal.innerText = '100% (SIMULATO)';
      ramBar.style.width = '100%';
    }
    updateBatterySegments(100);
  }

  function updateBatterySegments(pct) {
    const segments = DOM.batterySegments.querySelectorAll('.seg');
    const activeCount = Math.ceil((pct / 100) * segments.length);

    segments.forEach((seg, index) => {
      if (index < activeCount) {
        seg.classList.add('active');
        if (pct <= 20) {
          seg.classList.add('low');
        } else {
          seg.classList.remove('low');
        }
      } else {
        seg.classList.remove('active', 'low');
      }
    });
  }

  // =========================================================================
  // ARC REACTOR CANVAS ANIMATION ENGINE
  // =========================================================================
  function initCanvasReactor() {
    const canvas = DOM.reactorCanvas || document.getElementById('reactor-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let angleOuter = 0;
    let angleInner = 0;
    let pulseAngle = 0;

    // Retina High-DPI Canvas Support with Safe Fallbacks
    function resizeCanvas() {
      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();
      const displayWidth = rect.width > 20 ? rect.width : (canvas.clientWidth || 320);
      const displayHeight = rect.height > 20 ? rect.height : (canvas.clientHeight || 320);
      canvas.width = Math.round(displayWidth * dpr);
      canvas.height = Math.round(displayHeight * dpr);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.scale(dpr, dpr);
    }
    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);

    function drawReactor() {
      try {
        const rect = canvas.getBoundingClientRect();
        const width = rect.width > 20 ? rect.width : (canvas.clientWidth || 320);
        const height = rect.height > 20 ? rect.height : (canvas.clientHeight || 320);
        const cx = width / 2;
        const cy = height / 2;
        const radius = Math.max(30, Math.min(width, height) / 2 - 10);

        ctx.clearRect(0, 0, width, height);

      // Real Audio FFT Analysis & Spectrum Waveform
      let audioEnergy = 0;
      if (state.analyser && state.dataArray) {
        state.analyser.getByteFrequencyData(state.dataArray);
        let sum = 0;
        for (let i = 0; i < state.dataArray.length; i++) {
          sum += state.dataArray[i];
        }
        audioEnergy = sum / (state.dataArray.length * 255); // 0.0 to 1.0

        // Real-time Waveform Bars (16 Bands)
        const bars = DOM.waveBars && DOM.waveBars.length ? DOM.waveBars : document.querySelectorAll('.wave-bar');
        if (bars && bars.length > 0) {
          const step = Math.max(1, Math.floor(state.dataArray.length / bars.length));
          bars.forEach((bar, idx) => {
            const binIdx = Math.min(state.dataArray.length - 1, idx * step);
            let val = state.dataArray[binIdx];
            if (state.currentStatus === 'SPEAKING' && val < 20) {
              val = Math.round(state.speechPulseValue * 180 * (0.5 + 0.5 * Math.sin(idx * 0.4 + pulseAngle)));
            }
            const heightPx = Math.max(4, Math.round((val / 255) * 26));
            bar.style.height = `${heightPx}px`;
            if (val > 140) {
              bar.style.backgroundColor = 'var(--hud-cyan-bright)';
              bar.style.boxShadow = '0 0 8px var(--hud-cyan-bright)';
            } else {
              bar.style.backgroundColor = 'var(--hud-cyan)';
              bar.style.boxShadow = '0 0 4px var(--hud-cyan)';
            }
          });
        }
      }

      // Animation speeds adjusted by system state & acoustic energy
      let speedMult = 1.0;
      let coreIntensity = 0.5;

      if (state.currentStatus === 'LISTENING') {
        speedMult = 2.0 + (audioEnergy * 3.0);
        coreIntensity = 0.8 + (audioEnergy * 0.8);
      } else if (state.currentStatus === 'PROCESSING') {
        speedMult = 4.2;
        coreIntensity = 0.9;
      } else if (state.currentStatus === 'SPEAKING') {
        const pulse = Math.max(audioEnergy, state.speechPulseValue);
        speedMult = 2.0 + (pulse * 2.0);
        coreIntensity = 0.75 + (pulse * 0.65);
      } else {
        coreIntensity = 0.45 + (audioEnergy * 0.4);
      }

      angleOuter += 0.008 * speedMult;
      angleInner -= 0.012 * speedMult;
      pulseAngle += 0.04 * speedMult;

      const pulseScale = 1 + Math.sin(pulseAngle) * 0.06 * coreIntensity;

      // 1. Outer Tech Ring with Tick marks
      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(angleOuter);

      ctx.strokeStyle = 'rgba(0, 240, 255, 0.4)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(0, 0, radius * 0.92, 0, Math.PI * 2);
      ctx.stroke();

      const numTicks = 36;
      for (let i = 0; i < numTicks; i++) {
        const rad = (Math.PI * 2 / numTicks) * i;
        const r1 = radius * 0.88;
        const r2 = i % 3 === 0 ? radius * 0.95 : radius * 0.91;
        ctx.beginPath();
        ctx.moveTo(Math.cos(rad) * r1, Math.sin(rad) * r1);
        ctx.lineTo(Math.cos(rad) * r2, Math.sin(rad) * r2);
        ctx.strokeStyle = i % 3 === 0 ? 'rgba(0, 240, 255, 0.9)' : 'rgba(0, 240, 255, 0.3)';
        ctx.lineWidth = i % 3 === 0 ? 2 : 1;
        ctx.stroke();
      }
      ctx.restore();

      // 2. Segmented Arc Coils (10 Core Electromagnets)
      ctx.save();
      ctx.translate(cx, cy);
      ctx.rotate(angleInner);

      const numCoils = 10;
      const coilRadius = radius * 0.72;
      const coilArcWidth = (Math.PI * 2 / numCoils) * 0.75;

      for (let i = 0; i < numCoils; i++) {
        const startRad = (Math.PI * 2 / numCoils) * i;
        const endRad = startRad + coilArcWidth;

        // Coil Body Glow
        ctx.beginPath();
        ctx.arc(0, 0, coilRadius, startRad, endRad);
        ctx.strokeStyle = state.currentStatus === 'PROCESSING' 
          ? 'rgba(255, 183, 3, 0.8)' 
          : 'rgba(0, 240, 255, 0.85)';
        ctx.lineWidth = radius * 0.12;
        ctx.lineCap = 'round';
        ctx.shadowColor = 'rgba(0, 240, 255, 0.8)';
        ctx.shadowBlur = 10 * coreIntensity;
        ctx.stroke();

        // Inner filament wire
        ctx.beginPath();
        ctx.arc(0, 0, coilRadius, startRad + 0.05, endRad - 0.05);
        ctx.strokeStyle = '#ffffff';
        ctx.lineWidth = 2;
        ctx.stroke();
      }
      ctx.restore();

      // 3. Middle Structural Ring
      ctx.save();
      ctx.translate(cx, cy);
      ctx.beginPath();
      ctx.arc(0, 0, radius * 0.52, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(0, 119, 182, 0.8)';
      ctx.lineWidth = 4;
      ctx.shadowBlur = 0;
      ctx.stroke();

      ctx.beginPath();
      ctx.arc(0, 0, radius * 0.44, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(0, 240, 255, 0.9)';
      ctx.lineWidth = 2;
      ctx.setLineDash([8, 6]);
      ctx.rotate(angleOuter * 1.5);
      ctx.stroke();
      ctx.restore();

      // 4. Central Glowing Arc Reactor Core
      ctx.save();
      ctx.translate(cx, cy);
      ctx.scale(pulseScale, pulseScale);

      const coreRadius = radius * 0.32;

      // Radial Core Gradient
      const grad = ctx.createRadialGradient(0, 0, 2, 0, 0, coreRadius);
      grad.addColorStop(0, '#ffffff');
      grad.addColorStop(0.35, 'rgba(153, 246, 255, 0.95)');
      grad.addColorStop(0.7, 'rgba(0, 210, 255, 0.8)');
      grad.addColorStop(1, 'rgba(0, 80, 140, 0.2)');

      ctx.beginPath();
      ctx.arc(0, 0, coreRadius, 0, Math.PI * 2);
      ctx.fillStyle = grad;
      ctx.shadowColor = state.currentStatus === 'PROCESSING' ? '#ffb703' : '#00f0ff';
      ctx.shadowBlur = 25 * coreIntensity;
      ctx.fill();

      // Core Glass Perimeter
      ctx.beginPath();
      ctx.arc(0, 0, coreRadius, 0, Math.PI * 2);
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 3;
      ctx.stroke();

      // Inner Mark VI Geometric Triangle Core
      ctx.rotate(-angleOuter);
      const triR = coreRadius * 0.65;
      ctx.beginPath();
      for (let i = 0; i < 3; i++) {
        const a = -Math.PI / 2 + (Math.PI * 2 * i / 3);
        const px = Math.cos(a) * triR;
        const py = Math.sin(a) * triR;
        if (i === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fillStyle = 'rgba(3, 15, 30, 0.8)';
      ctx.fill();
      ctx.strokeStyle = '#00f0ff';
      ctx.lineWidth = 2;
      ctx.stroke();

      // Inner bright center diode
      ctx.beginPath();
      ctx.arc(0, 0, 5, 0, Math.PI * 2);
      ctx.fillStyle = '#ffffff';
      ctx.shadowBlur = 10;
      ctx.shadowColor = '#ffffff';
      ctx.fill();

      ctx.restore();
      } catch (err) {
        console.warn('[JARVIS] Draw reactor error:', err);
      }

      requestAnimationFrame(drawReactor);
    }

    requestAnimationFrame(drawReactor);
  }

  // =========================================================================
  // WEB SPEECH RECOGNITION (STREAMING, ROBUST WAKE-WORD & SILENCE DEBOUNCE)
  // =========================================================================
  const WAKE_WORD_REGEX = /\b(jarvis|giarvis|iarvis|djarvis|ciarvis|charvis|jervis|gervis|yarvis|garvis|travis|ehi\s*jarvis|ehi\s*giarvis|hey\s*jarvis|hey\s*giarvis|ok\s*jarvis|ok\s*giarvis|ciao\s*jarvis|ciao\s*giarvis|ascolta\s*jarvis|ascolta\s*giarvis|stark)\b/i;

  let speechSilenceTimer = null;
  let activeSpeechCandidate = '';
  let micPermissionGranted = false;

  function updateMicButtonUI(mode) {
    if (!DOM.hudMicMasterBtn) return;

    DOM.hudMicMasterBtn.classList.remove('pulse-attention', 'is-listening', 'is-processing', 'is-speaking', 'is-denied');

    switch (mode) {
      case 'need-touch':
        DOM.hudMicMasterBtn.classList.add('pulse-attention');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '🎙️';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'TOCCA PER ATTIVARE IL MICROFONO';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'ATTIVA ORA';
        break;

      case 'ready':
      case 'standby':
        if (DOM.micGlyph) DOM.micGlyph.textContent = '🎙️';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'TOCCA PER PARLARE // DI\' "JARVIS"';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'STANDBY';
        break;

      case 'listening':
        DOM.hudMicMasterBtn.classList.add('is-listening');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '🟢';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'IN ASCOLTO... PARLA ADESSO!';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'AUDIO LIVE';
        break;

      case 'processing':
        DOM.hudMicMasterBtn.classList.add('is-processing');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '⚡';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'ELABORAZIONE IN CORSO...';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'IA CORE';
        break;

      case 'speaking':
        DOM.hudMicMasterBtn.classList.add('is-speaking');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '🔊';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'JARVIS STA RISPONDENDO...';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'VOCE';
        break;

      case 'denied':
        DOM.hudMicMasterBtn.classList.add('is-denied');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '⚠️';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'MICROFONO BLOCCATO (CLICCA QUI)';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'BLOCCATO';
        break;

      case 'insecure':
        DOM.hudMicMasterBtn.classList.add('is-denied');
        if (DOM.micGlyph) DOM.micGlyph.textContent = '🔒';
        if (DOM.micMasterLabel) DOM.micMasterLabel.textContent = 'SERVE HTTPS PER IL MICROFONO';
        if (DOM.micStatusBadge) DOM.micStatusBadge.textContent = 'HTTPS REQ';
        break;
    }
  }

  async function requestMicrophonePermission() {
    if (micPermissionGranted) return true;
    unlockAudio();

    try {
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          }
        });
        micPermissionGranted = true;

        // Rilasciamo i binari audio di test: SpeechRecognition richiede accesso esclusivo
        // all'input microfonico su Windows e Android per non andare in starvation/blocco
        stream.getTracks().forEach(track => track.stop());

        DOM.diagMicState.textContent = 'STREAMING ATTIVO';
        updateMicButtonUI('ready');
        return true;
      } else {
        throw new Error('API getUserMedia non supportata');
      }
    } catch (err) {
      console.warn('[JARVIS] Permesso microfono rifiutato:', err);
      micPermissionGranted = false;
      DOM.diagMicState.textContent = 'PERMESSO NEGATO';
      DOM.statusBannerText.textContent = '🔴 MICROFONO BLOCCATO NEL BROWSER';
      updateMicButtonUI('denied');
      addLogEntry('JARVIS', 'Accesso al microfono non consentito. Tocchi il pulsante del microfono per verificare o consenta l\'accesso nell\'icona 🔒 della barra degli indirizzi.', 'MIC-ALERT');
      return false;
    }
  }

  function initSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

    const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
    if (!window.isSecureContext && !isLocal) {
      DOM.diagMicState.textContent = 'HTTPS RICHIESTO';
      if (DOM.httpsAlertBanner) DOM.httpsAlertBanner.style.display = 'flex';
      DOM.statusBannerText.textContent = '⚠️ APRI IL LINK HTTPS PER IL MICROFONO';
      DOM.transcriptText.textContent = 'Chrome blocca il microfono su HTTP. Apri il link Cloudflare HTTPS.';
      updateMicButtonUI('insecure');
      addLogEntry('JARVIS', 'Attenzione signore: Chrome su mobile richiede una connessione HTTPS sicura per abilitare il microfono. Utilizzi il link Cloudflare trycloudflare.com.', 'SECURITY-NOTICE');
      return;
    }

    if (!SpeechRecognition) {
      console.warn('[JARVIS] Web Speech Recognition API non supportata.');
      DOM.diagMicState.textContent = 'NON SUPPORTATO';
      DOM.transcriptText.textContent = 'Web Speech API non supportata in questo browser. Utilizza l\'input testuale.';
      updateMicButtonUI('denied');
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = state.settings.lang || 'it-IT';
    recognition.maxAlternatives = 1;

    recognition.onstart = () => {
      state.isSpeechActive = true;
      DOM.diagMicState.textContent = 'STREAMING ATTIVO';
      if (!state.isListening) {
        DOM.statusBannerText.textContent = 'TOCCA IL REATTORE O IL MICROFONO';
        updateMicButtonUI('ready');
      }
      console.log('[JARVIS] Speech Recognition avviato');
    };

    recognition.onresult = (event) => {
      if (state.isSpeaking) return;

      let interimTranscript = '';
      let finalTranscript = '';

      for (let i = event.resultIndex; i < event.results.length; ++i) {
        const transcriptPart = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          finalTranscript += transcriptPart;
        } else {
          interimTranscript += transcriptPart;
        }
      }

      const activeText = (finalTranscript || interimTranscript).trim();
      if (!activeText) return;

      DOM.transcriptText.textContent = `"${activeText}"`;

      handleSpeechInput(finalTranscript, interimTranscript);
    };

    recognition.onerror = (event) => {
      if (event.error === 'no-speech') {
        // Silenzio temporaneo, il watchdog gestisce il timeout automatico
        return;
      }
      if (event.error === 'not-allowed') {
        micPermissionGranted = false;
        DOM.diagMicState.textContent = 'PERMESSO NEGATO';
        DOM.statusBannerText.textContent = '🔴 MICROFONO BLOCCATO NEL BROWSER';
        updateMicButtonUI('denied');
        addLogEntry('JARVIS', 'Accesso al microfono non consentito. Verifichi i permessi del browser per questo sito, signore.', 'MIC-PERMISSION');
        stopDirectListening();
      } else if (event.error === 'audio-capture') {
        console.warn('[JARVIS] Dispositivo microfono occupato o non rilevato');
        DOM.diagMicState.textContent = 'ERRORE MICROFONO';
        DOM.statusBannerText.textContent = '⚠️ MICROFONO NON DISPONIBILE';
        stopDirectListening();
      } else {
        console.warn('[JARVIS] Speech Recognition info:', event.error);
      }
    };

    recognition.onend = () => {
      state.isSpeechActive = false;
      DOM.diagMicState.textContent = micPermissionGranted ? 'STANDBY' : 'PERMESSO NEGATO';

      if (state.reconnectTimer) clearTimeout(state.reconnectTimer);
      if (micPermissionGranted && state.settings.continuousRec && !state.isSpeaking) {
        state.reconnectTimer = setTimeout(() => {
          startListeningStream();
        }, 300);
      }
    };

    state.recognition = recognition;

    // Check microphone permission via Permissions API if available
    if (navigator.permissions && navigator.permissions.query) {
      navigator.permissions.query({ name: 'microphone' }).then(status => {
        if (status.state === 'granted') {
          micPermissionGranted = true;
          DOM.diagMicState.textContent = 'AUTORIZZATO';
          updateMicButtonUI('ready');
          if (state.settings.continuousRec) {
            startListeningStream();
          }
        } else if (status.state === 'denied') {
          DOM.diagMicState.textContent = 'PERMESSO NEGATO';
          updateMicButtonUI('denied');
        } else {
          DOM.diagMicState.textContent = 'IN ATTESA DI TOCCO';
          updateMicButtonUI('need-touch');
        }

        status.onchange = () => {
          if (status.state === 'granted') {
            micPermissionGranted = true;
            DOM.diagMicState.textContent = 'AUTORIZZATO';
            updateMicButtonUI('ready');
            if (state.settings.continuousRec && !state.isSpeechActive) {
              startListeningStream();
            }
          } else if (status.state === 'denied') {
            micPermissionGranted = false;
            DOM.diagMicState.textContent = 'PERMESSO NEGATO';
            updateMicButtonUI('denied');
          }
        };
      }).catch(() => {
        updateMicButtonUI('need-touch');
      });
    } else {
      updateMicButtonUI('need-touch');
    }
  }

  let listeningWatchdogTimer = null;
  let currentAudioSource = null;

  function stopCurrentAudio() {
    if (currentAudioSource) {
      try { currentAudioSource.stop(); } catch (e) {}
      currentAudioSource = null;
    }
    if (DOM.jarvisAudioPlayer) {
      try { DOM.jarvisAudioPlayer.pause(); DOM.jarvisAudioPlayer.currentTime = 0; } catch (e) {}
    }
    if ('speechSynthesis' in window) {
      try { window.speechSynthesis.cancel(); } catch (e) {}
    }
    state.isSpeaking = false;
    state.speechPulseValue = 0;
  }

  function startListeningStream() {
    if (state.recognition && !state.isSpeechActive && !state.isSpeaking) {
      try {
        state.recognition.start();
      } catch (err) {
        // Già avviato o in transizione
      }
    }
  }

  async function toggleVoiceMode() {
    triggerHaptic(40);
    unlockAudio();

    const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
    if (!window.isSecureContext && !isLocal) {
      window.open('https://driven-robert-charitable-concentrations.trycloudflare.com', '_blank');
      return;
    }

    if (DOM.hudMicMasterBtn && DOM.hudMicMasterBtn.classList.contains('is-denied')) {
      alert('Per attivare il microfono:\n1. Clicca sull\'icona 🔒 a sinistra dell\'indirizzo URL del browser.\n2. Imposta "Microfono" su "Consenti".\n3. Ricarica la pagina.');
      return;
    }

    // Se Jarvis sta parlando, un tocco sul reattore lo interrompe all'istante
    if (state.isSpeaking) {
      stopCurrentAudio();
    }

    // Se è già in ascolto: un secondo tocco invia subito il comando se c'è testo, o torna in standby
    if (state.isListening) {
      if (activeSpeechCandidate && activeSpeechCandidate.trim().length >= 2) {
        executeDirectSpeechCommand(activeSpeechCandidate);
      } else {
        stopDirectListening();
      }
      return;
    }

    const granted = await requestMicrophonePermission();
    if (granted) {
      startDirectListening();
    }
  }

  function startDirectListening() {
    stopCurrentAudio();
    state.isListening = true;
    activeSpeechCandidate = '';
    updateUIStatus('LISTENING');
    playFx('activate');

    DOM.statusBannerText.textContent = '🟢 IN ASCOLTO... PARLA ADESSO';
    DOM.transcriptText.textContent = 'Ti sto ascoltando... dì il tuo comando (es. "Che tempo fa?")';

    // Watchdog automatico: se l'utente non dice nulla entro 8 secondi, resetta a standby (evita blocco infinito)
    if (listeningWatchdogTimer) clearTimeout(listeningWatchdogTimer);
    listeningWatchdogTimer = setTimeout(() => {
      if (state.isListening && !activeSpeechCandidate) {
        console.log('[JARVIS] Watchdog timeout: nessuna voce rilevata');
        stopDirectListening();
        DOM.statusBannerText.textContent = 'NESSUNA VOCE RILEVATA // TOCCA PER PARLARE';
        DOM.transcriptText.textContent = 'Non ho sentito nulla. Tocca il microfono e parla, oppure scrivi sotto.';
      }
    }, 8000);

    // Riavvia una sessione fresca di SpeechRecognition per catturare subito l'audio
    if (state.recognition) {
      try {
        state.recognition.abort();
      } catch (e) {}
      setTimeout(() => {
        try {
          state.recognition.start();
          console.log('[JARVIS] Sessione di riconoscimento vocale avviata correttamente.');
        } catch (e) {
          console.warn('[JARVIS] Recognition start info:', e);
        }
      }, 60);
    }
  }

  function stopDirectListening() {
    state.isListening = false;
    updateUIStatus('STANDBY');
    playFx('deactivate');
    DOM.statusBannerText.textContent = 'TOCCA IL REATTORE O IL MICROFONO';

    if (speechSilenceTimer) {
      clearTimeout(speechSilenceTimer);
      speechSilenceTimer = null;
    }
    if (listeningWatchdogTimer) {
      clearTimeout(listeningWatchdogTimer);
      listeningWatchdogTimer = null;
    }
  }

  // Elaborazione intelligente del parlato continuo e della wake-word
  function handleSpeechInput(finalText, interimText) {
    const rawText = (finalText || interimText).trim();
    if (!rawText) return;

    activeSpeechCandidate = rawText;

    // CASO 1: Modalità ASCOLTO DIRETTO (L'utente ha toccato il reattore o il microfono)
    if (state.isListening) {
      DOM.statusBannerText.textContent = `🎤 RILEVATO: "${rawText.slice(0, 28)}..."`;
      DOM.transcriptText.textContent = `"${rawText}"`;

      if (speechSilenceTimer) clearTimeout(speechSilenceTimer);
      if (listeningWatchdogTimer) clearTimeout(listeningWatchdogTimer);

      // Se il browser ha già finalizzato la frase, esegui all'istante!
      if (finalText && finalText.trim().length >= 2) {
        executeDirectSpeechCommand(finalText);
        return;
      }

      // Altrimenti imposta un timer di silenzio di 750ms: appena l'utente finisce di parlare, invia subito!
      speechSilenceTimer = setTimeout(() => {
        if (state.isListening && activeSpeechCandidate && activeSpeechCandidate.length >= 2) {
          executeDirectSpeechCommand(activeSpeechCandidate);
        }
      }, 750);
      return;
    }

    // CASO 2: Modalità WAKE-WORD PASSIVA ("Jarvis", "Hey Jarvis"...)
    if (state.settings.wakeWordEnabled && !state.isListening) {
      if (WAKE_WORD_REGEX.test(rawText)) {
        console.log('[JARVIS] Wake-Word rilevata nel flusso audio:', rawText);
        state.wakeWordDetected = true;

        // Tronca immediatamente l'audio in corso per non sovrapporsi
        if (window.speechSynthesis) window.speechSynthesis.cancel();
        stopCurrentAudio();
        playFx('wake');

        const cleanCommand = rawText.replace(WAKE_WORD_REGEX, '').replace(/^[,\s;:]+/, '').trim();

        if (cleanCommand.length >= 2) {
          if (speechSilenceTimer) clearTimeout(speechSilenceTimer);
          if (finalText) {
            startDirectListening();
            executeDirectSpeechCommand(cleanCommand);
          } else {
            speechSilenceTimer = setTimeout(() => {
              const latestClean = activeSpeechCandidate.replace(WAKE_WORD_REGEX, '').replace(/^[,\s;:]+/, '').trim();
              if (latestClean.length >= 2) {
                startDirectListening();
                executeDirectSpeechCommand(latestClean);
              }
            }, 750);
          }
        } else {
          // Ha detto solo "Jarvis": attiva ascolto diretto e rispondi
          startDirectListening();
          speak('Sì, signore? Sono in ascolto.');
        }
      }
    }
  }

  function executeDirectSpeechCommand(command) {
    if (speechSilenceTimer) {
      clearTimeout(speechSilenceTimer);
      speechSilenceTimer = null;
    }
    if (listeningWatchdogTimer) {
      clearTimeout(listeningWatchdogTimer);
      listeningWatchdogTimer = null;
    }

    // Interrompi ogni discorso precedente
    if (window.speechSynthesis) window.speechSynthesis.cancel();
    stopCurrentAudio();

    const cleanCmd = command.replace(WAKE_WORD_REGEX, '').replace(/^[,\s;:]+/, '').trim() || command.trim();
    stopDirectListening();
    if (cleanCmd.length >= 2) {
      playFx('done');
      DOM.statusBannerText.textContent = `⚡ ELABORAZIONE: "${cleanCmd.slice(0, 22)}..."`;
      processUserCommand(cleanCmd);
    }
  }

  // =========================================================================
  // TEXT-TO-SPEECH (ANTI-FREEZE CHUNKING & ELEVENLABS FALLBACK)
  // =========================================================================
  function chunkTextForTTS(text, maxLength = 130) {
    if (!text) return [];
    const cleanText = text
      .replace(/\(Fonte:\s*https?:\/\/[^\)]+\)/gi, '')
      .replace(/\*{1,3}([^*]+)\*{1,3}/g, '$1')
      .replace(/\[\d+\]/g, '')
      .replace(/https?:\/\/[^\s]+/g, 'link web')
      .trim();

    const sentences = cleanText.match(/[^.!?;\n]+[.!?;\n]*/g) || [cleanText];
    const chunks = [];
    let current = '';

    for (const s of sentences) {
      const sentence = s.trim();
      if (!sentence) continue;
      if ((current + ' ' + sentence).trim().length <= maxLength) {
        current = (current ? current + ' ' : '') + sentence;
      } else {
        if (current) chunks.push(current);
        if (sentence.length > maxLength) {
          const parts = sentence.split(/,\s*/);
          let subcurr = '';
          for (const p of parts) {
            if ((subcurr + ' ' + p).trim().length <= maxLength) {
              subcurr = (subcurr ? subcurr + ', ' : '') + p;
            } else {
              if (subcurr) chunks.push(subcurr);
              subcurr = p;
            }
          }
          if (subcurr) chunks.push(subcurr);
          current = '';
        } else {
          current = sentence;
        }
      }
    }
    if (current) chunks.push(current);
    return chunks.length ? chunks : [cleanText];
  }

  let currentSpeechQueue = [];
  let isSpeakingQueue = false;

  async function speak(text) {
    if (!text) return;
    unlockAudio();
    state.isSpeaking = true;
    updateUIStatus('SPEAKING');

    // Pulse reactor rhythmically while speaking
    let pulsePhase = 0;
    const pulseInterval = setInterval(() => {
      if (!state.isSpeaking) {
        clearInterval(pulseInterval);
        state.speechPulseValue = 0;
        return;
      }
      pulsePhase += 0.3;
      state.speechPulseValue = 0.4 + 0.6 * Math.abs(Math.sin(pulsePhase));
    }, 80);

    const finishSpeaking = () => {
      clearInterval(pulseInterval);
      state.speechPulseValue = 0;
      state.isSpeaking = false;
      updateUIStatus('STANDBY');
    };

    // Option 1: ElevenLabs API (se esplicitamente selezionato con API key valida)
    if (state.settings.ttsEngine === 'elevenlabs' && state.settings.elevenLabsKey) {
      try {
        await speakWithElevenLabs(text);
        finishSpeaking();
        return;
      } catch (err) {
        console.warn('[JARVIS] ElevenLabs fallito, passo a Edge TTS / Nativo:', err);
      }
    }

    // Option 2: Microsoft Edge Neural TTS Server (Motore Predefinito Studio ad altissima qualità)
    try {
      await speakWithEdgeTTS(text);
      finishSpeaking();
      return;
    } catch (err) {
      console.warn('[JARVIS] Edge TTS Server fallito o non raggiungibile, fallback su Web Speech Synthesis nativo:', err);
    }

    // Option 3: Web Speech Synthesis with Anti-Freeze Sentence Chunking (fallback di emergenza)
    if ('speechSynthesis' in window) {
      try {
        window.speechSynthesis.cancel();
        window.speechSynthesis.resume();
      } catch (e) {}

      currentSpeechQueue = chunkTextForTTS(text);
      isSpeakingQueue = true;

      function speakNextChunk() {
        if (!isSpeakingQueue || currentSpeechQueue.length === 0) {
          finishSpeaking();
          return;
        }

        const chunk = currentSpeechQueue.shift();
        const utterance = new SpeechSynthesisUtterance(chunk);
        utterance.lang = state.settings.lang;
        utterance.pitch = 0.95;
        utterance.rate = 1.05;

        const voices = window.speechSynthesis.getVoices();
        if (voices.length > 0) {
          const langPrefix = state.settings.lang.split('-')[0];
          const preferredVoice = voices.find(v => v.lang.startsWith(langPrefix) && (v.name.includes('Google') || v.name.includes('Natural') || v.name.includes('Alice') || v.name.includes('Cosimo') || v.name.includes('Elsa') || v.name.includes('Diego')));
          if (preferredVoice) utterance.voice = preferredVoice;
        }

        let chunkEndTimer = setTimeout(() => {
          speakNextChunk();
        }, 12000);

        utterance.onend = () => {
          clearTimeout(chunkEndTimer);
          speakNextChunk();
        };

        utterance.onerror = (e) => {
          clearTimeout(chunkEndTimer);
          console.warn('[JARVIS] SpeechSynthesis chunk error:', e);
          speakNextChunk();
        };

        window.speechSynthesis.speak(utterance);
      }

      speakNextChunk();
    } else {
      finishSpeaking();
    }
  }

  /**
   * EDGE TTS SERVER ENGINE — Genera audio tramite il backend /tts (Microsoft Neural Voice)
   * Riproduzione tramite Web Audio API (invia l'audio all'analizzatore FFT del Reattore)
   * con fallback automatico su elemento <audio> persistente nel DOM.
   */
  async function speakWithEdgeTTS(text) {
    const baseEndpoint = resolveBackendEndpoint();
    const ttsUrl = `${baseEndpoint.replace('/chat', '')}/tts`;

    // Rimuovi markdown/link dal testo prima di mandarlo al TTS
    const cleanText = text
      .replace(/\(Fonte:\s*https?:\/\/[^\)]+\)/gi, '')
      .replace(/\*{1,3}([^*]+)\*{1,3}/g, '$1')
      .replace(/\[\d+\]/g, '')
      .replace(/https?:\/\/[^\s]+/g, 'link web')
      .replace(/```[\s\S]*?```/g, ' codice omesso ')
      .replace(/`([^`]+)`/g, '$1')
      .trim();

    if (!cleanText) return;

    unlockAudio();

    const response = await fetch(ttsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: cleanText,
        voice: state.settings.edgeTtsVoice || '',
        rate: state.settings.edgeTtsRate || '+0%',
        pitch: state.settings.edgeTtsPitch || '+0Hz'
      })
    });

    if (!response.ok) {
      throw new Error(`Edge TTS Server returned ${response.status}`);
    }

    const arrayBuffer = await response.arrayBuffer();

    // METODO A: Web Audio API (Bypassa qualsiasi blocco di autoplay se AudioContext è attivo,
    // e anima il reattore olografico e le onde audio con la voce di Jarvis!)
    if (state.audioCtx) {
      try {
        if (state.audioCtx.state === 'suspended') {
          await state.audioCtx.resume();
        }
        const audioBuffer = await state.audioCtx.decodeAudioData(arrayBuffer.slice(0));
        return new Promise((resolve) => {
          const source = state.audioCtx.createBufferSource();
          source.buffer = audioBuffer;
          if (state.analyser) {
            source.connect(state.analyser);
          }
          source.connect(state.audioCtx.destination);
          source.onended = () => {
            resolve();
          };
          source.start(0);
        });
      } catch (audioCtxErr) {
        console.warn('[JARVIS] Web Audio decodeAudioData non riuscito, uso elemento HTML5:', audioCtxErr);
      }
    }

    // METODO B: Elemento Audio persistente HTML5
    const audioBlob = new Blob([arrayBuffer], { type: 'audio/mpeg' });
    const audioUrl = URL.createObjectURL(audioBlob);
    const player = DOM.jarvisAudioPlayer || new Audio();
    player.src = audioUrl;

    return new Promise((resolve, reject) => {
      player.onended = () => {
        URL.revokeObjectURL(audioUrl);
        resolve();
      };
      player.onerror = (err) => {
        URL.revokeObjectURL(audioUrl);
        reject(err);
      };
      player.play().catch(reject);
    });
  }

  async function speakWithElevenLabs(text) {
    const voiceId = state.settings.elevenLabsVoice || '21m00Tcm4TlvDq8ikWAM';
    const response = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voiceId}`, {
      method: 'POST',
      headers: {
        'Accept': 'audio/mpeg',
        'Content-Type': 'application/json',
        'xi-api-key': state.settings.elevenLabsKey
      },
      body: JSON.stringify({
        text: text,
        model_id: 'eleven_multilingual_v2',
        voice_settings: {
          stability: 0.5,
          similarity_boost: 0.8
        }
      })
    });

    if (!response.ok) {
      throw new Error(`ElevenLabs API returned ${response.status}`);
    }

    const audioBlob = await response.blob();
    const audioUrl = URL.createObjectURL(audioBlob);
    const audio = new Audio(audioUrl);

    return new Promise((resolve, reject) => {
      audio.onended = () => {
        URL.revokeObjectURL(audioUrl);
        resolve();
      };
      audio.onerror = (err) => {
        URL.revokeObjectURL(audioUrl);
        reject(err);
      };
      audio.play().catch(reject);
    });
  }

  function speakJarvis(text) {
    speak(text);
  }

  // =========================================================================
  // FASTAPI MULTI-TOOL CLIENT & DISPATCHER
  // =========================================================================
  async function askJarvis(userText) {
    addLogEntry('USER', userText);
    const statusElem = document.getElementById('ai-status');
    if (statusElem) statusElem.innerText = "ELABORAZIONE // INTELLIGENCE";
    updateUIStatus('PROCESSING');

    const baseEndpoint = resolveBackendEndpoint();
    const route = '/chat';
    const serverUrl = `${baseEndpoint}${route}`;

    try {
      const response = await fetch(serverUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: userText })
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const data = await response.json();
      const reply = data.reply;
      const engineUsed = data.engine || 'Stark-Core';
      const latencyMs = data.latency_ms || 0;

      // Mostra risposta con badge motore e latenza
      addLogEntry('JARVIS', reply, engineUsed, latencyMs);
      if (statusElem) statusElem.innerText = "ONLINE // STANDBY";
      updateUIStatus('STANDBY');

      // Riproduzione vocale senza freeze
      speak(reply);

      // Esecuzione tool action inviata dal server
      const action = data.action;
      const actionParams = data.action_params || {};

      if (action && action !== 'chat') {
        executeToolAction(action, actionParams);
      } else {
        const localCheck = executeLocalIntentEngine(userText);
        if (localCheck && localCheck.action !== 'chat') {
          executeToolAction(localCheck.action, localCheck.params);
        }
      }

      return reply;

    } catch (err) {
      console.warn('[JARVIS] Errore connessione server:', err);
      addLogEntry('JARVIS', `Connessione al Core Server fallita (${serverUrl}). Attivazione circuito di emergenza offline.`, 'OFFLINE-ALERT');
      if (statusElem) statusElem.innerText = "OFFLINE // AUTONOMO";
      updateUIStatus('ERROR');

      // Fallback autonomo euristico
      const fallback = executeLocalIntentEngine(userText);
      if (fallback) {
        addLogEntry('JARVIS', fallback.speech, 'LOCAL-HEURISTIC');
        speak(fallback.speech);
        if (fallback.action !== 'chat') executeToolAction(fallback.action, fallback.params);
      }
    }
  }

  // Esponi globalmente
  window.askJarvis = askJarvis;
  window.speakJarvis = speakJarvis;

  // =========================================================================
  // LLM INTELLIGENCE & TOOL CALLING / ACTION DISPATCHER
  // =========================================================================
  async function processUserCommand(commandText) {
    if (!commandText || commandText.trim() === '') return;

    // Se l'utente ha configurato una chiave esterna per OpenAI o xAI Grok, la interroga direttamente
    if ((state.settings.llmProvider === 'openai' || state.settings.llmProvider === 'xai') && state.settings.apiKey) {
      addLogEntry('USER', commandText);
      updateUIStatus('PROCESSING');
      DOM.statusBannerText.textContent = 'ELABORAZIONE PROTOCOLLO...';

      let jarvisResponse = null;
      try {
        if (state.settings.llmProvider === 'openai') {
          jarvisResponse = await queryOpenAI(commandText);
        } else {
          jarvisResponse = await queryXAI(commandText);
        }
      } catch (err) {
        console.error('[JARVIS] Errore durante query LLM:', err);
      }

      if (jarvisResponse) {
        addLogEntry('JARVIS', jarvisResponse.speech, state.settings.llmProvider);
        speak(jarvisResponse.speech);
        if (jarvisResponse.action && jarvisResponse.action !== 'chat') {
          executeToolAction(jarvisResponse.action, jarvisResponse.params);
        }
        return;
      }
    }

    // Default universale: Stark Autonomous Cognitive Brain del Core Server (/chat)
    await askJarvis(commandText);
  }

  // 1. OPENAI API CALL
  async function queryOpenAI(userInput) {
    const systemPrompt = `Sei J.A.R.V.I.S., l'avanzata intelligenza artificiale creata da Tony Stark (Iron Man).
Sei impeccabile, formale, arguto e altamente efficiente. Rivolgiti sempre all'utente come "signore" (o "sir").
Rispondi ESCLUSIVAMENTE in formato JSON con la seguente struttura:
{
  "speech": "La frase che pronuncerai all'utente",
  "action": "spotify" | "whatsapp" | "call" | "map" | "search" | "battery" | "wakelock" | "chat",
  "params": {
    "query": "termine di ricerca musicale o di mappa o di ricerca web",
    "phone": "numero di telefono o vuoto",
    "text": "testo del messaggio whatsapp o vuoto",
    "state": "on" o "off"
  }
}
Azioni disponibili:
- spotify: se l'utente vuole ascoltare musica, canzoni o artisti.
- whatsapp: se l'utente vuole inviare un messaggio o aprire WhatsApp.
- call: se vuole chiamare un numero o contatto.
- map: se cerca luoghi, indicazioni o ristoranti.
- search: se chiede di cercare informazioni sul web o su Google.
- battery: se chiede lo stato o livello della batteria.
- wakelock: se vuole mantenere acceso lo schermo.
- chat: per tutte le altre conversazioni ordinarie.`;

    const response = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${state.settings.apiKey}`
      },
      body: JSON.stringify({
        model: 'gpt-4o-mini',
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: userInput }
        ],
        response_format: { type: 'json_object' },
        temperature: 0.7
      })
    });

    if (!response.ok) {
      throw new Error(`OpenAI error: ${response.status}`);
    }

    const data = await response.json();
    return JSON.parse(data.choices[0].message.content);
  }

  // 2. xAI GROK API CALL
  async function queryXAI(userInput) {
    const systemPrompt = `Sei J.A.R.V.I.S., assistente HUD Stark. Rispondi SEMPRE e SOLO in JSON valido:
{"speech": "Risposta vocale per il signore", "action": "spotify"|"whatsapp"|"call"|"map"|"search"|"battery"|"wakelock"|"chat", "params": {"query": "", "phone": "", "text": "", "state": ""}}`;

    const response = await fetch('https://api.x.ai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${state.settings.apiKey}`
      },
      body: JSON.stringify({
        model: 'grok-2-latest',
        messages: [
          { role: 'system', content: systemPrompt },
          { role: 'user', content: userInput }
        ],
        temperature: 0.6
      })
    });

    if (!response.ok) {
      throw new Error(`xAI error: ${response.status}`);
    }

    const data = await response.json();
    const content = data.choices[0].message.content;
    const jsonMatch = content.match(/\{[\s\S]*\}/);
    return jsonMatch ? JSON.parse(jsonMatch[0]) : { speech: content, action: 'chat', params: {} };
  }

  // 3. HIGH-INTELLIGENCE LOCAL ENGINE (OFFLINE & INSTANT)
  function executeLocalIntentEngine(input) {
    const text = input.toLowerCase();

    // SPOTIFY: "riproduci Queen", "metti musica", "ascolta ac/dc su spotify"
    if (text.includes('spotify') || text.includes('musica') || text.includes('canzone') || text.includes('riproduci') || text.includes('suona') || text.includes('ascolta')) {
      let query = input
        .replace(/riproduci|metti|ascolta|suona|apri|cerca|musica|canzone|dei|dei|su spotify|spotify/gi, '')
        .trim();
      if (!query) query = 'Top Hits';
      return {
        speech: `Avvio Spotify per la ricerca di "${query}", signore.`,
        action: 'spotify',
        params: { query: query }
      };
    }

    // WHATSAPP: "invia messaggio whatsapp a Marco", "apri whatsapp"
    if (text.includes('whatsapp') || text.includes('messaggio') || text.includes('scrivi a')) {
      let targetText = input.replace(/invia|manda|un|messaggio|su|a|whatsapp|scrivi/gi, '').trim();
      return {
        speech: 'Preparo il protocollo di trasmissione WhatsApp, signore.',
        action: 'whatsapp',
        params: { text: targetText, phone: '' }
      };
    }

    // BATTERY: "quanta batteria ho", "stato carica", "livello energetico"
    if (text.includes('batteria') || text.includes('carica') || text.includes('energia') || text.includes('alimentazione')) {
      const pct = Math.round(state.battery.level * 100);
      const isCharging = state.battery.charging;
      let speechMsg = `Il livello energetico dei condensatori è al ${pct}%, signore.`;
      if (isCharging) {
        speechMsg += ' Il reattore è attualmente connesso a una fonte di ricarica attiva.';
      } else {
        speechMsg += pct < 20 ? ' Suggerisco di collegare il dispositivo al più presto.' : ' I sistemi operano a rendimento ottimale.';
      }
      return {
        speech: speechMsg,
        action: 'battery',
        params: { level: pct, charging: isCharging }
      };
    }

    // MAPS / LOCATIONS: "dove si trova il Colosseo", "portami a Roma", "apri mappe", "ristorante"
    if (text.includes('mappa') || text.includes('mappe') || text.includes('portami') || text.includes('dove si trova') || text.includes('indicazioni') || text.includes('ristorante')) {
      let place = input.replace(/dove si trova|portami a|indicazioni per|apri mappa per|apri mappe|mappe|mappa|cerca/gi, '').trim();
      if (!place) place = 'ristoranti vicini';
      return {
        speech: `Calcolo le coordinate per "${place}" su Google Maps, signore.`,
        action: 'map',
        params: { query: place }
      };
    }

    // CALL: "chiama il 333...", "fai una chiamata"
    if (text.includes('chiama') || text.includes('telefona') || text.includes('chiamata')) {
      const numbers = input.match(/\+?[0-9\s]{5,15}/);
      const phoneNum = numbers ? numbers[0].replace(/\s+/g, '') : '';
      return {
        speech: phoneNum ? `Avvio la composizione del numero ${phoneNum}, signore.` : 'Apro l\'interfaccia telefonica, signore.',
        action: 'call',
        params: { phone: phoneNum }
      };
    }

    // SCREEN WAKE LOCK: "schermo acceso", "mantieni sveglio", "wake lock"
    if (text.includes('schermo') || text.includes('wake lock') || text.includes('spegnere lo schermo')) {
      const shouldTurnOn = !state.wakeLockRequested;
      return {
        speech: shouldTurnOn ? 'Attivo il protocollo Screen Wake Lock. Il display rimarrà illuminato.' : 'Disattivo Screen Wake Lock, signore.',
        action: 'wakelock',
        params: { state: shouldTurnOn ? 'on' : 'off' }
      };
    }

    // GOOGLE SEARCH: "cerca chi ha inventato..."
    if (text.includes('cerca') || text.includes('ricerca') || text.includes('trova')) {
      const query = input.replace(/cerca|trova|ricerca|su google/gi, '').trim();
      return {
        speech: `Interrogo i database globali per "${query}", signore.`,
        action: 'search',
        params: { query: query }
      };
    }

    // SMALLTALK & STARK PERSONA
    if (text.includes('chi sei') || text.includes('come ti chiami')) {
      return {
        speech: 'Sono J.A.R.V.I.S., acronimo di Just A Rather Very Intelligent System. Gestisco i sistemi di casa Stark e sono al Suo completo servizio.',
        action: 'chat',
        params: {}
      };
    }

    if (text.includes('ciao') || text.includes('buongiorno') || text.includes('buonasera')) {
      return {
        speech: 'Saluti a Lei, signore. Tutti i sistemi della Mark VII sono a pieno regime. Come posso esserLe utile?',
        action: 'chat',
        params: {}
      };
    }

    if (text.includes('grazie')) {
      return {
        speech: 'È sempre un piacere esserLe d\'aiuto, signore.',
        action: 'chat',
        params: {}
      };
    }

    // Default polite intelligent response
    return {
      speech: `Ho registrato la Sua direttiva: "${input}". Ho avviato un controllo sui protocolli Stark, signore.`,
      action: 'chat',
      params: {}
    };
  }

  // =========================================================================
  // DEEP LINKING & ACTION EXECUTION (ANDROID & DESKTOP)
  // =========================================================================
  function executeToolAction(action, params) {
    playFx('action');

    switch (action) {
      case 'spotify': {
        const query = params.query || '';
        const deepLink = `spotify:search:${encodeURIComponent(query)}`;
        const webLink = `https://open.spotify.com/search/${encodeURIComponent(query)}`;

        addActionCard(
          'SPOTIFY DEEP-LINK',
          `Ricerca audio: "${query}"`,
          'APRI IN SPOTIFY',
          deepLink,
          webLink
        );

        // Attempt direct native Android / Desktop deep link
        tryTriggerDeepLink(deepLink, webLink);
        break;
      }

      case 'whatsapp': {
        const phone = (params.phone || '').replace(/[^0-9]/g, '');
        const message = params.text || '';
        const deepLink = phone 
          ? `https://wa.me/${phone}?text=${encodeURIComponent(message)}`
          : `https://api.whatsapp.com/send?text=${encodeURIComponent(message)}`;

        addActionCard(
          'WHATSAPP PROTOCOL',
          message ? `Messaggio pronto: "${message}"` : 'Apertura client WhatsApp',
          'APRI WHATSAPP',
          deepLink,
          deepLink
        );

        tryTriggerDeepLink(deepLink, deepLink);
        break;
      }

      case 'call': {
        const phone = params.phone || '';
        const telLink = `tel:${phone}`;

        addActionCard(
          'CHIAMATA TELEFONICA',
          phone ? `Destinatario: ${phone}` : 'Tastierino telefonico',
          'CHIAMA ORA',
          telLink,
          telLink
        );

        if (phone) {
          window.location.href = telLink;
        }
        break;
      }

      case 'map': {
        const query = params.query || '';
        const mapLink = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;

        addActionCard(
          'GOOGLE MAPS NAVIGATION',
          `Coordinate: "${query}"`,
          'APRI MAPPE',
          mapLink,
          mapLink
        );

        tryTriggerDeepLink(mapLink, mapLink);
        break;
      }

      case 'search': {
        const query = params.query || '';
        const searchLink = `https://www.google.com/search?q=${encodeURIComponent(query)}`;

        addActionCard(
          'RICERCA WEB STARK',
          `Interrogazione: "${query}"`,
          'CERCA SU GOOGLE',
          searchLink,
          searchLink
        );

        tryTriggerDeepLink(searchLink, searchLink);
        break;
      }

      case 'wakelock': {
        if (params.state === 'on') {
          requestWakeLock(false);
        } else {
          releaseWakeLock(false);
        }
        break;
      }

      default:
        break;
    }
  }

  // Safe deep link launcher that prevents Android browser popups from getting trapped
  function tryTriggerDeepLink(appUri, fallbackUrl) {
    const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
    
    // On Android, directly pointing window.location to app URI triggers the native app intent
    setTimeout(() => {
      window.open(fallbackUrl, '_blank');
    }, 400);
  }

  // =========================================================================
  // MISSION LOG & UI HELPERS
  // =========================================================================
  function updateUIStatus(status) {
    state.currentStatus = status;
    DOM.systemStatusIndicator.className = 'status-indicator';

    switch (status) {
      case 'LISTENING':
        DOM.systemStatusIndicator.classList.add('listening');
        DOM.systemStatusText.textContent = 'MIC ATTIVO // IN ASCOLTO';
        DOM.waveformContainer.classList.add('active');
        updateMicButtonUI('listening');
        break;
      case 'PROCESSING':
        DOM.systemStatusIndicator.classList.add('processing');
        DOM.systemStatusText.textContent = 'NEURAL ENGINE // ELABORAZIONE';
        DOM.waveformContainer.classList.remove('active');
        updateMicButtonUI('processing');
        break;
      case 'SPEAKING':
        DOM.systemStatusIndicator.classList.add('speaking');
        DOM.systemStatusText.textContent = 'SINTESI VOCALE // VOCE ATTIVA';
        DOM.waveformContainer.classList.add('active');
        updateMicButtonUI('speaking');
        break;
      default:
        DOM.systemStatusText.textContent = 'ONLINE // STANDBY';
        DOM.waveformContainer.classList.remove('active');
        updateMicButtonUI(micPermissionGranted ? 'ready' : 'need-touch');
        break;
    }
  }

  // =========================================================================
  // HAPTIC FEEDBACK & LOG UTILITIES
  // =========================================================================
  function triggerHaptic(duration = 40) {
    if (navigator.vibrate) {
      try {
        navigator.vibrate(duration);
      } catch (e) {}
    }
  }

  function formatContentWithCitations(rawText) {
    if (!rawText) return '';
    // Format (Fonte: https://...) into clickable citation pills
    const urlRegex = /\(Fonte:\s*(https?:\/\/[^\s\)]+)\)/gi;
    let formatted = rawText.replace(urlRegex, (match, url) => {
      let hostname = '';
      try { hostname = new URL(url).hostname.replace('www.', ''); } catch (e) { hostname = 'fonte'; }
      return `<a class="citation-pill" href="${url}" target="_blank" rel="noopener noreferrer">🌐 Fonte: ${hostname} ↗</a>`;
    });
    // Format [1], [2] headers nicely
    formatted = formatted.replace(/\[(\d+)\]\s*([^:\n]+):/g, '<strong>[$1] $2:</strong>');
    return formatted.replace(/\n/g, '<br>');
  }

  function addLog(text) {
    addLogEntry('JARVIS', text);
  }

  function addLogEntry(sender, text, engine = null, latency = null) {
    const now = new Date();
    const timeStr = now.toTimeString().split(' ')[0];

    const entry = document.createElement('div');
    entry.className = `log-entry ${sender.toLowerCase()}-entry`;

    const meta = document.createElement('div');
    meta.className = 'entry-meta';

    const badge = document.createElement('span');
    badge.className = 'entry-badge';
    badge.textContent = sender === 'JARVIS' ? 'J.A.R.V.I.S.' : 'DIRETTIVA UTENTE';

    const timeContainer = document.createElement('div');
    timeContainer.style.display = 'flex';
    timeContainer.style.alignItems = 'center';
    timeContainer.style.gap = '6px';

    if (engine) {
      const engTag = document.createElement('span');
      engTag.className = 'engine-tag';
      engTag.textContent = `${engine}${latency ? ` // ${latency}ms` : ''}`;
      timeContainer.appendChild(engTag);
    }

    const time = document.createElement('span');
    time.className = 'entry-time';
    time.textContent = timeStr;
    timeContainer.appendChild(time);

    meta.appendChild(badge);
    meta.appendChild(timeContainer);

    const content = document.createElement('div');
    content.className = 'entry-content';
    content.innerHTML = formatContentWithCitations(text);

    entry.appendChild(meta);
    entry.appendChild(content);

    DOM.messagesContainer.appendChild(entry);
    DOM.messagesContainer.scrollTop = DOM.messagesContainer.scrollHeight;
  }

  function addActionCard(title, description, btnText, directLink, fallbackLink) {
    const card = document.createElement('div');
    card.className = 'action-card';

    card.innerHTML = `
      <div class="action-card-header">
        <span>⚡</span>
        <span>${title}</span>
      </div>
      <div class="action-card-desc">${description}</div>
      <div class="action-card-footer">
        <a class="action-card-btn" href="${fallbackLink || directLink}" target="_blank" rel="noopener noreferrer">
          ${btnText} ↗
        </a>
        <button class="action-card-btn secondary" type="button" title="Copia link negli appunti">
          📋 Copia
        </button>
      </div>
    `;

    const copyBtn = card.querySelector('.action-card-btn.secondary');
    if (copyBtn) {
      copyBtn.addEventListener('click', () => {
        triggerHaptic(20);
        navigator.clipboard.writeText(fallbackLink || directLink);
        copyBtn.textContent = '✓ Copiato!';
        setTimeout(() => { copyBtn.textContent = '📋 Copia'; }, 2000);
      });
    }

    DOM.messagesContainer.appendChild(card);
    DOM.messagesContainer.scrollTop = DOM.messagesContainer.scrollHeight;
  }

  // =========================================================================
  // EVENT BINDINGS
  // =========================================================================
  function bindEvents() {
    // Tap on Master Mic Button, Left Panel Quick Mic, Arc Reactor, or Status Banner
    if (DOM.hudMicMasterBtn) {
      DOM.hudMicMasterBtn.addEventListener('click', toggleVoiceMode);
    }
    if (DOM.btnRequestMic) {
      DOM.btnRequestMic.addEventListener('click', toggleVoiceMode);
    }
    DOM.reactorTouchBtn.addEventListener('click', toggleVoiceMode);
    DOM.reactorCanvas.addEventListener('click', toggleVoiceMode);
    if (DOM.statusBanner) {
      DOM.statusBanner.style.cursor = 'pointer';
      DOM.statusBanner.addEventListener('click', toggleVoiceMode);
    }

    // Screen Wake Lock Toggle Button
    DOM.wakeLockToggle.addEventListener('click', toggleWakeLock);

    // Manual input submit
    DOM.manualInputForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const val = DOM.manualTextInput.value.trim();
      if (val) {
        // Una nuova direttiva ha sempre la precedenza sulla risposta vocale corrente.
        if (window.speechSynthesis) window.speechSynthesis.cancel();
        stopCurrentAudio();
        unlockAudio();
        triggerHaptic(25);
        processUserCommand(val);
        DOM.manualTextInput.value = '';
      }
    });

    // Quick Command Shortcut Buttons
    document.querySelectorAll('.shortcut-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        // Anche i comandi rapidi devono troncare immediatamente il TTS precedente.
        if (window.speechSynthesis) window.speechSynthesis.cancel();
        stopCurrentAudio();
        unlockAudio();
        triggerHaptic(35);
        const cmd = btn.getAttribute('data-command');
        processUserCommand(cmd);
      });
    });

    // Settings Modal
    DOM.openSettingsBtn.addEventListener('click', () => {
      triggerHaptic(20);
      DOM.settingsModal.removeAttribute('hidden');
    });

    DOM.closeSettingsBtn.addEventListener('click', closeModal);

    DOM.settingsModal.addEventListener('click', (e) => {
      if (e.target === DOM.settingsModal) closeModal();
    });

    DOM.settingLlmProvider.addEventListener('change', toggleProviderVisibility);
    DOM.settingTtsEngine.addEventListener('change', toggleTtsVisibility);

    DOM.saveSettingsBtn.addEventListener('click', saveSettings);

    DOM.testVoiceBtn.addEventListener('click', () => {
      triggerHaptic(30);
      unlockAudio();
      speak('Sistemi operativi al cento per cento. La mia voce è calibrata e pronta, signore.');
    });

    DOM.testFxBtn.addEventListener('click', () => {
      triggerHaptic(40);
      unlockAudio();
      playFx('activate');
    });

    // Quick test speaker button on Diagnostics panel
    if (DOM.btnTestSpeaker) {
      DOM.btnTestSpeaker.addEventListener('click', () => {
        triggerHaptic(35);
        unlockAudio();
        speak('Sistemi operativi online, signore. In attesa di istruzioni.');
      });
    }

    // 3. Attiva microfono/WakeLock e Audio al primo tocco sullo schermo (richiesto dai browser mobile)
    let hasWelcomed = false;
    const onFirstUserInteraction = async () => {
      unlockAudio();
      await requestMicrophonePermission();
      requestWakeLock(true);
      if (navigator.vibrate) {
        navigator.vibrate(40);
      }
      if (!hasWelcomed) {
        hasWelcomed = true;
        setTimeout(() => {
          speak('Sistemi operativi online, signore. In attesa di istruzioni.');
        }, 350);
      }
      if (state.settings.continuousRec && state.recognition && !state.isSpeechActive) {
        try {
          state.recognition.start();
        } catch (e) {}
      }
    };

    window.addEventListener('click', onFirstUserInteraction, { once: true });
    window.addEventListener('touchstart', onFirstUserInteraction, { once: true });
  }

  // =========================================================================
  // SCANSIONE OTTICA / WEBCAM VISION
  // =========================================================================
  let webcamStream = null;

  function initVisionScanner() {
    const btnToggle = document.getElementById('btn-toggle-vision');
    const btnCapture = document.getElementById('btn-capture-vision');
    const video = document.getElementById('hud-webcam');
    const canvas = document.getElementById('vision-canvas');

    if (!btnToggle || !video) return;

    btnToggle.addEventListener('click', async () => {
      unlockAudio();
      if (webcamStream) {
        // Arresto telecamera
        webcamStream.getTracks().forEach(t => t.stop());
        webcamStream = null;
        video.srcObject = null;
        video.style.display = 'none';
        if (btnCapture) btnCapture.style.display = 'none';
        btnToggle.innerHTML = '<span class="btn-prefix">👁️</span> ATTIVA WEBCAM';
        playFx('deactivate');
        addLogEntry('SYSTEM', 'Sensori visivi disattivati.');
        return;
      }

      try {
        playFx('activate');
        webcamStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } },
          audio: false
        });
        video.srcObject = webcamStream;
        video.style.display = 'block';
        if (btnCapture) btnCapture.style.display = 'inline-block';
        btnToggle.innerHTML = '<span class="btn-prefix">⏹️</span> DISATTIVA';
        addLogEntry('SYSTEM', 'Sensori visivi online. Inquadra un oggetto e tocca ANALIZZA.');
      } catch (err) {
        console.error('[JARVIS VISION ERROR]', err);
        addLogEntry('ERROR', `Accesso telecamera negato o non supportato: ${err.message}`);
      }
    });

    if (btnCapture) {
      btnCapture.addEventListener('click', async () => {
        if (!webcamStream || !video.videoWidth) return;
        // La scansione è una nuova richiesta: interrompe eventuale risposta parlata.
        if (window.speechSynthesis) window.speechSynthesis.cancel();
        stopCurrentAudio();
        unlockAudio();
        playFx('action');
        updateUIStatus('PROCESSING');
        DOM.statusBannerText.textContent = 'SCANSIONE OTTICA IN CORSO...';

        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.65);

        addLogEntry('USER', '📸 [Scansione ottica inviata]');

        const baseEndpoint = resolveBackendEndpoint();
        try {
          const res = await fetch(`${baseEndpoint}/vision`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              image: dataUrl,
              prompt: "Descrivi con precisione e brevità analitica cosa viene inquadrato in quest'immagine, rivolgendoti al signore."
            })
          });
          const data = await res.json();
          updateUIStatus('STANDBY');
          addLogEntry('JARVIS', data.reply, data.engine || 'Vision-Core', data.latency_ms || 0);
          speak(data.reply);
        } catch (err) {
          updateUIStatus('ERROR');
          addLogEntry('ERROR', `Errore analisi ottica: ${err.message}`);
        }
      });
    }
  }

  function closeModal() {
    DOM.settingsModal.setAttribute('hidden', '');
  }

  // Self-boot on DOMContentLoaded
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
