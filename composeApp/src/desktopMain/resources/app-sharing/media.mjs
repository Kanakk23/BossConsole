import { createMediaCipher } from './crypto.mjs';

export function encryptionSupport(env = globalThis) {
  return !!(env.crypto?.subtle && env.RTCPeerConnection &&
    (env.RTCRtpScriptTransform || (env.RTCRtpSender?.prototype?.createEncodedStreams && env.RTCRtpReceiver?.prototype?.createEncodedStreams)));
}

export function vp8Only(transceiver, env = globalThis) {
  const codecs = env.RTCRtpSender.getCapabilities('video')?.codecs?.filter(c => c.mimeType.toLowerCase() === 'video/vp8');
  if (!codecs?.length || !transceiver.setCodecPreferences) throw new Error('Encrypted VP8 is unavailable');
  transceiver.setCodecPreferences(codecs);
}

export async function attachEncryption(endpoint, config, direction, fail, env = globalThis, observe = null) {
  // Import keys before touching the media sender so an unsupported algorithm is fatal.
  const cipher = await createMediaCipher(config, direction);
  if (env.RTCRtpScriptTransform) {
    const worker = new env.Worker(new URL('./encoded-worker.mjs', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
      if (data.type === 'fatal') fail(new Error(data.code));
      else if (data.type === 'diagnostic') observe?.(data);
    };
    worker.onerror = () => fail(new Error('Encrypted media worker failed'));
    endpoint.transform = new env.RTCRtpScriptTransform(worker, { config, direction, diagnostics: !!observe });
    return () => worker.terminate();
  }
  if (!endpoint.createEncodedStreams) throw new Error('Encoded transforms unavailable');
  const { readable, writable } = endpoint.createEncodedStreams();
  const abort = new AbortController();
  let received = 0, processed = 0, dropped = 0;
  readable.pipeThrough(new TransformStream({ async transform(frame, controller) {
    received++;
    try { frame.data = await cipher[direction](frame.data); controller.enqueue(frame); processed++; }
    catch (error) {
      dropped++;
      if (direction === 'encrypt') fail(error);
      observe?.({ type: 'diagnostic', direction, received, processed, dropped, error: String(error.message).slice(0, 120) });
      /* Reject ciphertext, never pass it through. */
    }
    if (received === 1 || received % 60 === 0) observe?.({ type: 'diagnostic', direction, received, processed, dropped });
  } })).pipeTo(writable, { signal: abort.signal }).catch(error => { if (!abort.signal.aborted) fail(error); });
  return () => abort.abort();
}

/**
 * Remote offers create their own receivers: addTransceiver-created receivers
 * are not reused. The peer MUST require encodedInsertableStreams, so incoming
 * frames cannot bypass decryption while the real offered receiver is bound.
 */
export async function acceptEncryptedVideoOffer(peer, description, mid, config, fail, env = globalThis, encrypt = attachEncryption) {
  if (description?.type !== 'offer' || typeof mid !== 'string' || !mid) throw new Error('Invalid SFU subscription offer');
  await peer.setRemoteDescription(description);
  const transceivers = peer.getTransceivers();
  if (transceivers.length !== 1) throw new Error('Unexpected SFU media sections');
  const transceiver = transceivers[0];
  if (transceiver.mid !== mid || transceiver.receiver.track.kind !== 'video') throw new Error('Unexpected SFU media section');
  vp8Only(transceiver, env);
  const cleanup = await encrypt(transceiver.receiver, config, 'decrypt', fail, env);
  return { track: transceiver.receiver.track, cleanup };
}

export function waitForIce(peer, signal, timeoutMs = 10000) {
  if (signal?.aborted) return Promise.reject(new Error('Sharing stopped'));
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); peer.removeEventListener('icegatheringstatechange', changed); signal?.removeEventListener('abort', stopped); };
    const changed = () => { if (peer.iceGatheringState === 'complete') { cleanup(); resolve(); } };
    const stopped = () => { cleanup(); reject(new Error('Sharing stopped')); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('ICE gathering timed out')); }, timeoutMs);
    peer.addEventListener('icegatheringstatechange', changed);
    signal?.addEventListener('abort', stopped, { once: true });
    changed();
  });
}

export function waitForConnected(peer, signal, timeoutMs = 15000) {
  if (signal?.aborted) return Promise.reject(new Error('Sharing stopped'));
  if (peer.connectionState === 'connected') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); peer.removeEventListener('connectionstatechange', changed); signal?.removeEventListener('abort', stopped); };
    const changed = () => {
      if (peer.connectionState === 'connected') { cleanup(); resolve(); }
      else if (['failed', 'closed'].includes(peer.connectionState)) { cleanup(); reject(new Error('Media connection failed')); }
    };
    const stopped = () => { cleanup(); reject(new Error('Sharing stopped')); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('Media connection timed out')); }, timeoutMs);
    peer.addEventListener('connectionstatechange', changed);
    signal?.addEventListener('abort', stopped, { once: true });
    changed();
  });
}

/** One SFU peer, one publication for a selected window, irrespective of viewers. */
export class WindowMediaPeer {
  constructor({ config, request, onState = () => {}, env = globalThis, encrypt = attachEncryption, reconnectGraceMs = 10000 }) {
    this.config = config; this.request = request; this.onState = onState; this.env = env; this.encrypt = encrypt;
    this.abort = new AbortController(); this.cleanup = []; this.closed = false; this.started = false;
    this.reconnectGraceMs = reconnectGraceMs;
  }
  fields(action, extra = {}) {
    return { action, session_id: this.config.sessionId, generation: this.config.generation, peer_id: this.config.peerId, window_id: this.config.windowId, ...extra };
  }
  async call(action, extra) {
    if (this.closed) throw new Error('Sharing stopped');
    const result = await this.request(this.fields(action, extra));
    if (this.closed) throw new Error('Sharing stopped');
    return result;
  }
  async callWhenPublished(action, extra, { timeoutMs = 30000, retryMs = 500 } = {}) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try { return await this.call(action, extra); }
      catch (error) {
        if (error.code !== 'publication_pending' || Date.now() >= deadline || this.closed) throw error;
        await new Promise((resolve, reject) => {
          const stopped = () => { clearTimeout(timer); reject(new Error('Sharing stopped')); };
          const timer = setTimeout(() => { this.abort.signal.removeEventListener('abort', stopped); resolve(); }, Math.min(retryMs, Math.max(0, deadline - Date.now())));
          this.abort.signal.addEventListener('abort', stopped, { once: true });
          if (this.abort.signal.aborted) stopped();
        });
      }
    }
  }
  async connect() {
    if (this.started) throw new Error('Window already connected');
    this.started = true;
    if (!encryptionSupport(this.env)) throw new Error('This browser cannot receive encrypted app sharing');
    const created = await this.call('mediaCreate');
    this.peer = new this.env.RTCPeerConnection({ iceServers: created.ice_servers ?? [], encodedInsertableStreams: true });
    this.peer.onconnectionstatechange = () => {
      const state = this.peer.connectionState;
      this.onState(state);
      if (state === 'connected') { clearTimeout(this.disconnectTimer); this.disconnectTimer = null; }
      else if (state === 'disconnected' && !this.disconnectTimer) {
        this.disconnectTimer = setTimeout(() => {
          this.disconnectTimer = null;
          if (this.peer.connectionState !== 'connected') this.stop();
        }, this.reconnectGraceMs);
      }
      if (['failed', 'closed'].includes(state)) this.stop();
    };
    return this.peer;
  }
  async publish(track) {
    this.track = track;
    try {
      const peer = await this.connect();
      // Transform installation precedes both attaching the native capture and SDP.
      const transceiver = peer.addTransceiver('video', { direction: 'sendonly' });
      vp8Only(transceiver, this.env);
      this.cleanup.push(await this.encrypt(transceiver.sender, this.config, 'encrypt', () => this.stop(), this.env));
      if (this.closed) throw new Error('Sharing stopped');
      await transceiver.sender.replaceTrack(track);
      await peer.setLocalDescription(await peer.createOffer());
      await waitForIce(peer, this.abort.signal);
      const response = await this.call('mediaPublish', { mid: transceiver.mid, session_description: peer.localDescription.toJSON?.() ?? peer.localDescription });
      if (response.session_description?.type !== 'answer') throw new Error('Invalid SFU publish answer');
      await peer.setRemoteDescription(response.session_description);
      await this.waitConnected();
      this.onState('published');
    } catch (error) { this.stop(); throw error; }
  }
  async subscribe(onTrack) {
    try {
      const peer = await this.connect();
      const response = await this.callWhenPublished('mediaSubscribe');
      const received = await acceptEncryptedVideoOffer(peer, response.session_description, response.mid, this.config, () => this.stop(), this.env, this.encrypt);
      if (this.closed) { received.cleanup(); throw new Error('Sharing stopped'); }
      this.cleanup.push(received.cleanup);
      await peer.setLocalDescription(await peer.createAnswer());
      await waitForIce(peer, this.abort.signal);
      await this.call('mediaRenegotiate', { session_description: peer.localDescription.toJSON?.() ?? peer.localDescription });
      onTrack(received.track);
      await this.waitConnected();
      this.onState('subscribed');
      this.heartbeat = setTimeout(() => this.keepAlive(), 60000);
    } catch (error) { this.stop(); throw error; }
  }
  async answer(description) {
    if (description?.type !== 'offer') throw new Error('Invalid SFU data offer');
    await this.peer.setRemoteDescription(description);
    await this.peer.setLocalDescription(await this.peer.createAnswer());
    await waitForIce(this.peer, this.abort.signal);
    await this.call('mediaRenegotiate', { session_description: this.peer.localDescription.toJSON?.() ?? this.peer.localDescription });
  }
  waitConnected() { return waitForConnected(this.peer, this.abort.signal); }
  async keepAlive() {
    try {
      await this.call('peerHeartbeat');
      if (!this.closed) this.heartbeat = setTimeout(() => this.keepAlive(), 60000);
    } catch (_) { this.stop(); }
  }
  stop() {
    if (this.closed) return this.closeDone;
    this.closed = true;
    // Start cleanup before callbacks can re-enter stop(). The bridge bounds
    // this request to 15s; callers keep it open until the request settles.
    try { this.closeDone = Promise.resolve(this.started ? this.request(this.fields('mediaClose')) : undefined).catch(() => {}); }
    catch (_) { this.closeDone = Promise.resolve(); }
    clearTimeout(this.heartbeat);
    clearTimeout(this.disconnectTimer); this.disconnectTimer = null;
    this.abort.abort();
    this.track?.stop();
    for (const cleanup of this.cleanup.splice(0)) cleanup();
    this.peer?.close();
    this.onState('stopped');
    return this.closeDone;
  }
}

/** Native snapshots are latest-only; a slow decode never creates an unbounded queue. */
export class NativeFrameCanvas {
  constructor(canvas, onGeometry = () => {}, decode = createImageBitmap) {
    this.canvas = canvas; this.onGeometry = onGeometry; this.decode = decode; this.closed = false;
    this.canvas.width = 2; this.canvas.height = 2;
    this.stream = canvas.captureStream(0); this.track = this.stream.getVideoTracks()[0];
    if (!this.track?.requestFrame) throw new Error('Native capture streaming unsupported');
    this.track.contentHint = 'detail';
  }
  frame(frame) {
    if (this.closed) return;
    if (!Number.isSafeInteger(frame.width) || !Number.isSafeInteger(frame.height) || frame.width < 1 || frame.height < 1 || frame.width > 8192 || frame.height > 8192 || frame.width * frame.height > 16777216 || !Number.isSafeInteger(frame.geometryRevision) || frame.geometryRevision < 0 || typeof frame.png !== 'string' || frame.png.length > 24 * 1024 * 1024) throw new Error('Invalid native snapshot');
    this.pending = frame;
    if (!this.decoding) this.decoding = this.drain().finally(() => { this.decoding = null; });
    return this.decoding;
  }
  async drain() {
    while (!this.closed && this.pending) {
      const frame = this.pending; this.pending = null;
      const bytes = Uint8Array.from(atob(frame.png), c => c.charCodeAt(0));
      const image = await this.decode(new Blob([bytes], { type: 'image/png' }));
      try {
        if (this.closed) return;
        if (image.width !== frame.width || image.height !== frame.height) throw new Error('Native geometry mismatch');
        this.canvas.width = frame.width; this.canvas.height = frame.height;
        this.canvas.getContext('2d', { alpha: false }).drawImage(image, 0, 0);
        this.onGeometry({ width: frame.width, height: frame.height, geometryRevision: frame.geometryRevision });
        this.track.requestFrame();
      } finally { image.close(); }
    }
  }
  stop() { this.closed = true; this.pending = null; this.stream.getTracks().forEach(track => track.stop()); this.canvas.width = 2; this.canvas.height = 2; }
}
