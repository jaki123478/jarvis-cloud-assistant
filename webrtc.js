/* J.A.R.V.I.S. WebRTC audio layer.
 * Signaling is deliberately separated from the media path: the server forwards
 * SDP/ICE only, while audio travels directly between the two authorized peers.
 */
(function () {
  'use strict';
  const state = { socket: null, pc: null, localStream: null, room: null, initiator: false };
  const rtcConfig = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' }
    ],
    bundlePolicy: 'max-bundle'
  };

  function signalUrl(base, room) {
    const url = new URL(base || window.location.origin);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    url.pathname = `/ws/webrtc/${encodeURIComponent(room)}`;
    url.search = '';
    return url.toString();
  }

  async function start(options) {
    options = options || {};
    if (!window.RTCPeerConnection || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('WebRTC non supportato dal browser');
    }
    await stop();
    state.room = String(options.room || '').trim();
    if (!state.room) throw new Error('Room WebRTC mancante');
    state.initiator = !!options.initiator;
    state.localStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false
    });
    state.pc = new RTCPeerConnection(rtcConfig);
    state.localStream.getTracks().forEach(track => state.pc.addTrack(track, state.localStream));
    state.pc.ontrack = event => {
      const audio = document.getElementById('jarvis-webrtc-audio') || document.createElement('audio');
      audio.id = 'jarvis-webrtc-audio'; audio.autoplay = true; audio.playsInline = true;
      if (!audio.parentNode) document.body.appendChild(audio);
      audio.srcObject = event.streams[0];
      audio.play().catch(() => {});
    };
    state.pc.onicecandidate = event => {
      if (event.candidate && state.socket?.readyState === WebSocket.OPEN) {
        state.socket.send(JSON.stringify({ type: 'ice', candidate: event.candidate }));
      }
    };
    state.pc.onconnectionstatechange = () => options.onState?.(state.pc.connectionState);
    state.socket = new WebSocket(signalUrl(options.signalingUrl, state.room));
    state.socket.onmessage = async event => {
      const msg = JSON.parse(event.data);
      if (msg.type === 'ready' && state.initiator && msg.peers > 0) {
        const offer = await state.pc.createOffer({ offerToReceiveAudio: true });
        await state.pc.setLocalDescription(offer);
        state.socket.send(JSON.stringify({ type: 'offer', sdp: state.pc.localDescription }));
      } else if (msg.type === 'offer') {
        await state.pc.setRemoteDescription(msg.sdp);
        const answer = await state.pc.createAnswer();
        await state.pc.setLocalDescription(answer);
        state.socket.send(JSON.stringify({ type: 'answer', sdp: state.pc.localDescription }));
      } else if (msg.type === 'answer') {
        await state.pc.setRemoteDescription(msg.sdp);
      } else if (msg.type === 'ice' && msg.candidate) {
        await state.pc.addIceCandidate(msg.candidate);
      }
    };
    return state;
  }

  async function stop() {
    state.socket?.close(); state.socket = null;
    state.pc?.close(); state.pc = null;
    state.localStream?.getTracks().forEach(track => track.stop()); state.localStream = null;
    const audio = document.getElementById('jarvis-webrtc-audio');
    if (audio) { audio.pause(); audio.srcObject = null; audio.remove(); }
  }

  window.JarvisWebRTC = { start, stop, state };
})();
