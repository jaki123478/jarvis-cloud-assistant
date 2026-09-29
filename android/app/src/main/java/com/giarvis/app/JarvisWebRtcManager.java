package com.giarvis.app;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Native, audio-only WebRTC transport for calls between JARVIS clients. */
public final class JarvisWebRtcManager {
    private static final String TAG = "JarvisWebRTC";
    private final Context context;
    private final AudioManager audioManager;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private WebSocket signaling;
    private OkHttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean(true);

    public JarvisWebRtcManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void start(String signalingUrl, String roomId, boolean initiator) {
        if (signalingUrl == null || roomId == null || signalingUrl.trim().isEmpty() || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("WebRTC signaling URL e room sono obbligatori");
        }
        stop();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("jarvis-audio", audioSource);
        List<PeerConnection.IceServer> servers = Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        peerConnection = factory.createPeerConnection(config, observer);
        if (peerConnection == null) throw new IllegalStateException("PeerConnection non disponibile");
        peerConnection.addTrack(audioTrack);
        setAudioMode(true);
        closed.set(false);

        String ws = signalingUrl.replaceFirst("^http", "ws").replaceAll("/$", "") + "/ws/webrtc/" + roomId;
        httpClient = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();
        signaling = httpClient.newWebSocket(new Request.Builder().url(ws).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                try { handleSignal(new JSONObject(text), initiator); }
                catch (Exception e) { Log.w(TAG, "Segnale WebRTC non valido", e); }
            }
            @Override public void onFailure(WebSocket socket, Throwable t, Response response) { Log.w(TAG, "Signaling interrotto", t); }
        });
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override public void onIceCandidate(IceCandidate candidate) {
            try {
                send(new JSONObject().put("type", "ice").put("candidate", new JSONObject()
                        .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp)));
            } catch (Exception e) { Log.w(TAG, "ICE non inviato", e); }
        }
        @Override public void onAddStream(org.webrtc.MediaStream stream) {}
        @Override public void onTrack(org.webrtc.RtpTransceiver transceiver) {}
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onDataChannel(org.webrtc.DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {}
        @Override public void onSelectedCandidatePairChanged(org.webrtc.CandidatePairChangeEvent event) {}
        @Override public void onRemoveStream(org.webrtc.MediaStream stream) {}
    };

    private void handleSignal(JSONObject msg, boolean initiator) throws Exception {
        String type = msg.optString("type");
        if ("ready".equals(type) && initiator && msg.optInt("peers") > 0) {
            peerConnection.createOffer(new SdpObserver("offer"), new MediaConstraints());
        } else if ("offer".equals(type)) {
            JSONObject sdp = msg.getJSONObject("sdp");
            peerConnection.setRemoteDescription(new SdpObserver("answer"), new SessionDescription(SessionDescription.Type.OFFER, sdp.getString("sdp")));
        } else if ("answer".equals(type)) {
            JSONObject sdp = msg.getJSONObject("sdp");
            peerConnection.setRemoteDescription(new SdpObserver(null), new SessionDescription(SessionDescription.Type.ANSWER, sdp.getString("sdp")));
        } else if ("ice".equals(type)) {
            JSONObject c = msg.getJSONObject("candidate");
            peerConnection.addIceCandidate(new IceCandidate(c.optString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate")));
        }
    }

    private final class SdpObserver implements org.webrtc.SdpObserver {
        private final String next;
        SdpObserver(String next) { this.next = next; }
        @Override public void onCreateSuccess(SessionDescription sdp) {
            peerConnection.setLocalDescription(new SdpObserver(null), sdp);
            if (next != null) {
                try { send(new JSONObject().put("type", next).put("sdp", new JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description))); }
                catch (Exception e) { Log.w(TAG, "SDP non inviato", e); }
            }
        }
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { Log.w(TAG, "SDP create: " + error); }
        @Override public void onSetFailure(String error) { Log.w(TAG, "SDP set: " + error); }
    }

    private void send(JSONObject message) { if (signaling != null) signaling.send(message.toString()); }

    private void setAudioMode(boolean active) {
        if (audioManager == null) return;
        audioManager.setMode(active ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(active);
    }

    public synchronized void stop() {
        closed.set(true);
        if (signaling != null) signaling.close(1000, "stop");
        signaling = null;
        if (peerConnection != null) peerConnection.close();
        peerConnection = null;
        if (audioTrack != null) audioTrack.dispose(); audioTrack = null;
        if (audioSource != null) audioSource.dispose(); audioSource = null;
        if (factory != null) factory.dispose(); factory = null;
        if (httpClient != null) httpClient.dispatcher().executorService().shutdown(); httpClient = null;
        setAudioMode(false);
    }
}
