import test from 'node:test';
import assert from 'node:assert/strict';
import { WindowMediaPeer, NativeFrameCanvas, acceptEncryptedVideoOffer, waitForIce, waitForConnected } from '../../../desktopMain/resources/app-sharing/media.mjs';
import { createBridge, closeViewerResources, resolveBridgeRequest } from '../../../desktopMain/resources/app-sharing/bridge.mjs';

function harness() {
  const log = [], requests = [], peers = [];
  class Peer extends EventTarget {
    constructor(options) { super(); this.options = options; this.transceivers = []; this.iceGatheringState = 'complete'; this.connectionState = 'new'; peers.push(this); }
    addTransceiver(kind, { direction }) {
      this.tx = { mid: '0', direction, sender: { replaceTrack: async () => log.push('attach-track') }, receiver: { track: { kind } }, setCodecPreferences: codecs => { assert.deepEqual(codecs.map(c => c.mimeType), ['video/VP8']); } }; this.transceivers.push(this.tx); return this.tx;
    }
    getTransceivers() { return this.transceivers; }
    async createOffer() { return { type: 'offer', sdp: 'publisher-offer' }; }
    async createAnswer() { return { type: 'answer', sdp: 'subscriber-answer' }; }
    async setLocalDescription(description) { this.localDescription = description; log.push('local-description'); }
    async setRemoteDescription(description) {
      this.remoteDescription = description; this.connectionState = 'connected'; log.push('remote-description');
      // An incoming offer does not reuse an addTransceiver-created receiver.
      if (description.type === 'offer') this.addTransceiver('video', { direction: 'recvonly' });
    }
    close() { this.connectionState = 'closed'; log.push('close-peer'); }
  }
  const env = { crypto: { subtle: {} }, RTCPeerConnection: Peer, RTCRtpSender: { getCapabilities: () => ({ codecs: [{ mimeType: 'video/VP8' }, { mimeType: 'video/H264' }] }), prototype: { createEncodedStreams() {} } }, RTCRtpReceiver: { prototype: { createEncodedStreams() {} } } };
  const request = async body => {
    requests.push(body);
    if (body.action === 'mediaPublish') return { session_description: { type: 'answer', sdp: 'sfu-answer' } };
    if (body.action === 'mediaSubscribe') return { session_description: { type: 'offer', sdp: 'sfu-offer' }, mid: '0' };
    return { ice_servers: [] };
  };
  const config = { sessionId: 'app-session', generation: 'generation', peerId: 'admitted-peer', windowId: 'selected-window' };
  const encrypt = async (_, __, direction) => { log.push(`encrypt-${direction}`); return () => log.push('close-transform'); };
  const create = () => new WindowMediaPeer({ config, request, env, encrypt });
  return { log, requests, peers, env, request, config, encrypt, create };
}

test('one publisher captures once and encrypts before track attachment or SDP', async () => {
  const h = harness(), media = h.create(); let stopped = 0;
  await media.publish({ stop() { stopped++; } });
  assert.equal(h.peers.length, 1);
  assert.equal(h.requests.filter(r => r.action === 'mediaPublish').length, 1);
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('attach-track'));
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('local-description'));
  await assert.rejects(media.connect(), /already connected/);
  media.stop(); media.stop();
  assert.equal(stopped, 1); assert.equal(h.requests.filter(r => r.action === 'mediaClose').length, 1);
});

test('subscriber binds the actual offered receiver before answering and exposes only that track', async () => {
  const h = harness(), media = h.create(); let received;
  await media.subscribe(track => { received = track; h.log.push('expose-track'); });
  assert.equal(h.peers[0].options.encodedInsertableStreams, true);
  assert.equal(h.peers[0].getTransceivers().length, 1);
  assert.equal(received, h.peers[0].getTransceivers()[0].receiver.track);
  assert.ok(h.log.indexOf('remote-description') < h.log.indexOf('encrypt-decrypt'));
  assert.ok(h.log.indexOf('encrypt-decrypt') < h.log.indexOf('local-description'));
  assert.ok(h.log.indexOf('local-description') < h.log.indexOf('expose-track'));
  for (const request of h.requests) {
    assert.equal(request.peer_id, h.config.peerId); assert.equal(request.window_id, h.config.windowId);
    assert.equal(request.session_id, h.config.sessionId); assert.equal(request.generation, h.config.generation);
    assert.equal(request.sfu_session_id, undefined);
  }
  assert.equal(h.requests.at(-1).action, 'mediaRenegotiate'); media.stop();
});

test('subscriber refuses unexpected mids or extra media sections before installing a decryptor', async () => {
  for (const extra of [false, true]) {
    const h = harness(), peer = new h.env.RTCPeerConnection({ encodedInsertableStreams: true });
    if (extra) peer.addTransceiver('video', { direction: 'recvonly' });
    await assert.rejects(acceptEncryptedVideoOffer(peer, { type: 'offer' }, extra ? '0' : 'other', h.config, () => {}, h.env, h.encrypt), /Unexpected SFU media section/);
    assert.equal(h.log.includes('encrypt-decrypt'), false);
  }
});

test('unsupported encryption rejects before creating an SFU session', async () => {
  const h = harness(); h.env.RTCRtpSender.prototype = {};
  const media = h.create(); await assert.rejects(media.publish({ stop() {} }), /encrypted/);
  assert.equal(h.requests.filter(r => r.action === 'mediaCreate').length, 0);
});

test('on-demand publication waits only for the explicit pending code and stops on owner cancellation', async () => {
  const h = harness(); let attempts = 0;
  const pending = () => Object.assign(new Error('pending'), { code: 'publication_pending' });
  const media = new WindowMediaPeer({ ...h, request: async () => { if (++attempts === 1) throw pending(); return { ready: true }; } });
  assert.deepEqual(await media.callWhenPublished('mediaSubscribe', {}, { retryMs: 1 }), { ready: true });
  assert.equal(attempts, 2);
  media.request = async () => { throw Object.assign(new Error('denied'), { code: 'forbidden' }); };
  await assert.rejects(media.callWhenPublished('mediaSubscribe'), /denied/);
  media.request = async () => { throw pending(); };
  await assert.rejects(media.callWhenPublished('mediaSubscribe', {}, { timeoutMs: 0 }), /pending/);
  const waiting = media.callWhenPublished('mediaSubscribe');
  await new Promise(resolve => setImmediate(resolve)); media.stop();
  await assert.rejects(waiting, /stopped/);
});

test('stop during SFU creation cannot resurrect a peer', async () => {
  const h = harness(); let resolve;
  const media = new WindowMediaPeer({ ...h, request: body => body.action === 'mediaCreate' ? new Promise(r => { resolve = r; }) : Promise.resolve({}) });
  const pending = media.publish({ stop() {} }); media.stop(); resolve({});
  await assert.rejects(pending, /stopped/); assert.equal(h.peers.length, 0);
});

test('SFU negotiation failure cleans up media and transform without fallback', async () => {
  const h = harness(); let stopped = 0;
  const media = new WindowMediaPeer({ ...h, request: async body => body.action === 'mediaPublish' ? { session_description: { type: 'offer' } } : {} });
  await assert.rejects(media.publish({ stop() { stopped++; } }), /publish answer/);
  assert.equal(stopped, 1); assert.ok(h.log.includes('close-transform')); assert.ok(h.log.includes('close-peer'));
});

test('ICE wait aborts immediately when the owner closes', async () => {
  const peer = new EventTarget(); peer.iceGatheringState = 'gathering';
  const abort = new AbortController(); const wait = waitForIce(peer, abort.signal); abort.abort();
  await assert.rejects(wait, /stopped/);
});

test('connected wait does not mistake negotiation for usable media', async () => {
  const peer = new EventTarget(); peer.connectionState = 'connecting';
  let connected = false;
  const promise = waitForConnected(peer).then(() => { connected = true; });
  await Promise.resolve(); assert.equal(connected, false);
  peer.connectionState = 'connected'; peer.dispatchEvent(new Event('connectionstatechange'));
  await promise; assert.equal(connected, true);
});

test('transient disconnect can recover within grace but an abandoned peer closes after the deadline', async () => {
  const h = harness(), media = new WindowMediaPeer({ ...h, reconnectGraceMs: 5 });
  await media.subscribe(() => {}); const peer = h.peers[0];
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  peer.connectionState = 'connected'; peer.onconnectionstatechange();
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(media.closed, false); assert.equal(h.requests.some(value => value.action === 'mediaClose'), false);
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(media.closed, true); assert.equal(h.requests.filter(value => value.action === 'mediaClose').length, 1);
});

test('native frame canvas is latest-only and releases decoded frames on close', async () => {
  let requests = 0, closes = 0, stopped = 0, resolve;
  const draws = [], geometry = [];
  const track = { requestFrame() { requests++; }, stop() { stopped++; } };
  const canvas = { captureStream: () => ({ getVideoTracks: () => [track], getTracks: () => [track] }), getContext: () => ({ drawImage: img => draws.push(img.width) }) };
  const capture = new NativeFrameCanvas(canvas, value => geometry.push(value), () => new Promise(r => { resolve = r; }));
  assert.equal(track.contentHint, 'detail');
  const f = width => ({ png: 'AA==', width, height: 10, geometryRevision: width });
  const drain = capture.frame(f(10)); capture.frame(f(20)); capture.frame(f(30));
  resolve({ width: 10, height: 10, close() { closes++; } }); await new Promise(r => setImmediate(r));
  resolve({ width: 30, height: 10, close() { closes++; } }); await drain;
  assert.deepEqual(draws, [10, 30]); assert.equal(requests, 2); assert.equal(closes, 2); assert.equal(geometry.at(-1).geometryRevision, 30);
  capture.stop(); assert.equal(stopped, 1); assert.equal(canvas.width, 2);
});

test('bridge rejects untrusted RPC origin and cancels all pending native requests', async () => {
  assert.throws(() => createBridge({ rpcUrl: 'https://attacker.example/rpc', rpcToken: 'secret' }, null), /Untrusted/);
  const sent = [];
  const bridge = createBridge({}, { request: (id, json) => sent.push([id, json]) });
  const first = bridge.request({ action: 'mediaCreate' }); bridge.resolve(sent[0][0], '{"peer_id":"ok"}');
  assert.deepEqual(await first, { peer_id: 'ok' });
  const second = bridge.request({ action: 'mediaSubscribe' }); bridge.close(); await assert.rejects(second, /stopped/);
});

test('viewer teardown keeps bridge requests alive until both lease release and media cleanup settle', async () => {
  const h = harness(), held = new Map(); let closed = false, controlStopped = false;
  const bridge = createBridge({}, { request(id, json) {
    const body = JSON.parse(json);
    if (['mediaClose', 'controlRelease'].includes(body.action)) held.set(body.action, id);
    else h.request(body).then(result => bridge.resolve(id, result));
  } });
  const media = new WindowMediaPeer({ ...h, request: body => bridge.request(body) });
  await media.publish({ stop() {} });
  const closing = closeViewerResources({
    media,
    control: { releaseControl: () => bridge.request({ action: 'controlRelease' }), stop: () => { controlStopped = true; } },
    bridge: { close() { closed = true; bridge.close(); } },
  });
  assert.equal(controlStopped, true); assert.equal(media.closed, true);
  const mediaClose = media.stop(); assert.equal(media.stop(), mediaClose);
  assert.equal(held.size, 2); assert.equal(closed, false);
  // A new UI owner may already have its own native bridge by this point.
  const replacementIds = [];
  const replacement = createBridge({}, { request: id => replacementIds.push(id) });
  const replacementRequest = replacement.request({ action: 'mediaCreate' });
  assert.equal([...held.values()].includes(replacementIds[0]), false);
  resolveBridgeRequest(held.get('controlRelease'), {});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(closed, false, 'media cleanup must not be aborted after lease release');
  resolveBridgeRequest(held.get('mediaClose'), {});
  await closing; await mediaClose;
  assert.equal(closed, true);
  await assert.rejects(bridge.request({ action: 'mediaCreate' }), /stopped/);
  resolveBridgeRequest(replacementIds[0], { peer_id: 'replacement' });
  assert.deepEqual(await replacementRequest, { peer_id: 'replacement' }); replacement.close();
});
