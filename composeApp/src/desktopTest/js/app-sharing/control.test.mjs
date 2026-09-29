import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { createControlCipher, toBase64 } from '../../../desktopMain/resources/app-sharing/crypto.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

globalThis.crypto ??= webcrypto;
const key = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
const config = {
  sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), windowId: 'main-window', keyEpoch: crypto.randomUUID(), peerId: crypto.randomUUID(),
  mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
  hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', key.privateKey))),
  hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', key.publicKey))),
};
const lease = { lease_id: crypto.randomUUID(), peer_id: config.peerId, control_secret: toBase64(crypto.getRandomValues(new Uint8Array(32))), expires_at: new Date(Date.now() + 30000).toISOString() };
function harness(overrides = {}) {
  const requests = [], sent = [];
  const channel = { id: 4, readyState: 'open', bufferedAmount: 0, send: value => sent.push(JSON.parse(value)), close() { this.readyState = 'closed'; } };
  const media = {
    config,
    call: async (action, body) => { requests.push({ action, ...body }); return overrides[action]?.(body) ?? (action === 'controlPoll' ? { lease } : action === 'controlAcquire' || action === 'controlRenew' ? lease : { channel_id: 4, session_description: { type: 'offer', sdp: 'data-offer' } }); },
    answer: async description => assert.equal(description.type, 'offer'),
    waitConnected: async () => {},
    peer: { createDataChannel: () => channel },
  };
  media.callWhenPublished = media.call;
  return { media, channel, requests, sent };
}
const until = async predicate => { for (let i = 0; i < 50 && !predicate(); i++) await new Promise(r => setTimeout(r, 2)); assert.ok(predicate()); };

test('viewer negotiates view-only channel before lease-gated reply and retains cipher across renewal', async () => {
  const h = harness(); const control = new AppControlChannel(h.media);
  await control.start();
  assert.deepEqual(h.requests.map(r => r.action), ['dataEstablish', 'dataSubscribe']);
  assert.equal(h.requests[1].can_reply, false);
  assert.equal(control.send({ type: 'key' }), false);
  await control.takeControl();
  assert.equal(h.requests.at(-1).can_reply, true); assert.equal(h.requests.at(-1).lease_id, lease.lease_id);
  const cipher = control.cipher;
  await control.renewLease(control.lease); assert.equal(control.cipher, cipher);
  await control.releaseControl(); assert.equal(control.lease, null);
  assert.equal(h.requests.at(-1).action, 'controlRelease');
  control.stop();
});

test('host authenticates input and rejects replay, stale geometry and expired leases', async () => {
  const h = harness(), received = [];
  const host = new AppControlChannel(h.media, { host: true, onInput: input => received.push(input) });
  await host.start(); await until(() => !!host.lease);
  host.setGeometry({ width: 1200, height: 800, geometryRevision: 2 });
  const sender = await createControlCipher(config, { leaseId: lease.lease_id, peerId: config.peerId, controlSecret: lease.control_secret });
  const good = await sender.encrypt({ type: 'pointer', action: 'down', x: 0.2, y: 0.3, button: 0 }, 2);
  h.channel.onmessage({ data: JSON.stringify(good) }); await until(() => received.length === 1);
  h.channel.onmessage({ data: JSON.stringify(good) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  const stale = await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 1);
  h.channel.onmessage({ data: JSON.stringify(stale) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  host.lease.expiresAt = Date.now() - 1;
  const expired = await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 2);
  h.channel.onmessage({ data: JSON.stringify(expired) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  host.stop();
});

test('geometry comes only from host signatures, with replay and other window rejection', async () => {
  const publisher = harness(), subscriber = harness(), seen = [];
  const host = new AppControlChannel(publisher.media, { host: true });
  const viewer = new AppControlChannel(subscriber.media, { onGeometry: value => seen.push(value) });
  await host.start(); await viewer.start();
  host.setGeometry({ width: 1000, height: 700, geometryRevision: 3 });
  await host.announceGeometry();
  const geometry = publisher.sent.at(-1);
  subscriber.channel.onmessage({ data: JSON.stringify(geometry) }); await until(() => seen.length === 1);
  subscriber.channel.onmessage({ data: JSON.stringify(geometry) }); await until(() => viewer.queue.length === 0); assert.equal(seen.length, 1);
  const forged = structuredClone(geometry); forged.body.sequence++; forged.body.windowId = 'unrelated';
  subscriber.channel.onmessage({ data: JSON.stringify(forged) }); await until(() => viewer.queue.length === 0); assert.equal(seen.length, 1);
  host.stop(); viewer.stop();
});

test('pointer moves coalesce while discrete input is encrypted in order; backpressure relinquishes lease', async () => {
  const h = harness(); const viewer = new AppControlChannel(h.media);
  await viewer.start(); await viewer.takeControl(); viewer.geometry = { geometryRevision: 7 };
  for (let i = 0; i < 20; i++) viewer.send({ type: 'pointer', action: 'move', x: i / 20, y: 0.5 });
  viewer.send({ type: 'key', action: 'down', code: 'KeyA' }); await viewer.chain;
  assert.equal(h.sent.length, 2);
  const receiver = await createControlCipher(config, { leaseId: lease.lease_id, peerId: config.peerId, controlSecret: lease.control_secret });
  assert.equal((await receiver.decrypt(h.sent[0], 7)).event.x, 0.95);
  assert.equal((await receiver.decrypt(h.sent[1], 7)).event.code, 'KeyA');
  h.channel.bufferedAmount = 100000;
  assert.equal(viewer.send({ type: 'key', action: 'up', code: 'KeyA' }), false);
  assert.equal(viewer.lease, null); viewer.stop();
});

test('permission polling failure immediately clears host control authority', async () => {
  let fail = false;
  const h = harness({ controlPoll: () => { if (fail) throw new Error('revoked'); return { lease }; } });
  const host = new AppControlChannel(h.media, { host: true });
  await host.start(); await until(() => !!host.lease);
  clearTimeout(host.timer); fail = true; await host.pollLease();
  assert.equal(host.lease, null); assert.equal(host.cipher, null); host.stop();
});

test('reacquiring the same lease retains send counter and host replay history', async () => {
  const h = harness(); const viewer = new AppControlChannel(h.media);
  await viewer.start(); await viewer.takeControl(); viewer.geometry = { geometryRevision: 1 };
  viewer.send({ type: 'key', action: 'down', code: 'KeyA' }); await viewer.chain;
  await viewer.releaseControl(); await viewer.takeControl();
  viewer.send({ type: 'key', action: 'up', code: 'KeyA' }); await viewer.chain;
  assert.deepEqual(h.sent.map(command => command.sequence), [1, 2]); viewer.stop();
  const hostHarness = harness(), host = new AppControlChannel(hostHarness.media, { host: true });
  await host.start(); await until(() => !!host.cipher);
  const firstCipher = host.cipher;
  await host.cipher.decrypt(h.sent[0], 1);
  clearTimeout(host.timer); host.clearLease(); await host.pollLease();
  assert.equal(host.cipher, firstCipher);
  await assert.rejects(host.cipher.decrypt(h.sent[0], 1), /Replayed/);
  host.stop();
});
