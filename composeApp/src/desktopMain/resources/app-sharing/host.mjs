import { createBridge, resolveBridgeRequest, rejectBridgeRequest } from './bridge.mjs';
import { WindowMediaPeer, NativeFrameCanvas } from './media.mjs';
import { AppControlChannel } from './control.mjs';

let current = null;
const usedEpochs = new Set();
function parse(value) { return typeof value === 'string' ? JSON.parse(value) : value; }
function state(value) { globalThis.__bossAppShareBridge?.state?.(JSON.stringify(value)); }

async function start(value) {
  stop();
  const config = parse(value);
  const epoch = JSON.stringify([config.sessionId, config.generation, config.windowId, config.keyEpoch]);
  // A fresh epoch/root must be issued by the host before reconnecting a publisher.
  if (usedEpochs.has(epoch)) throw new Error('Restart sharing with a fresh media key epoch');
  usedEpochs.add(epoch);
  const bridge = createBridge(config);
  const owner = { bridge }; current = owner;
  try {
    owner.media = new WindowMediaPeer({ config, request: body => bridge.request(body), onState: status => {
      bridge.state({ state: status });
      if (status === 'stopped' && current === owner) stop();
    } });
    owner.canvas = new NativeFrameCanvas(document.getElementById('capture'), geometry => { owner.geometry = geometry; owner.control?.setGeometry(geometry); });
    // Only native, exactly selected window frames feed this canvas. There is no
    // display picker or title-matching capture fallback here.
    await owner.media.publish(owner.canvas.track);
    if (current !== owner) return;
    owner.control = new AppControlChannel(owner.media, {
      host: true,
      onInput: input => { if (current === owner) bridge.input(input); },
      onLease: lease => { if (current === owner) bridge.state({ state: 'lease', lease }); },
      onError: () => {
        // Losing authoritative lease/session health cannot leave an old media
        // key publishing indefinitely to a revoked or expired subscription.
        if (current === owner) { bridge.state({ state: 'failed', message: 'Application sharing authority could not be verified' }); stop(); }
      },
    });
    if (owner.geometry) owner.control.setGeometry(owner.geometry);
    try { await owner.control.start(); }
    catch (_) { if (current === owner) bridge.state({ state: 'control-unavailable' }); }
    if (current === owner) bridge.state({ state: 'sharing' });
  } catch (error) {
    if (current === owner) { bridge.state({ state: 'failed', message: 'Encrypted application sharing could not start' }); stop(); }
    throw error;
  }
}
function frame(value) {
  const owner = current;
  if (!owner?.canvas) return false;
  try {
    const promise = owner.canvas.frame(parse(value));
    promise?.catch(() => { if (current === owner) { owner.bridge.state({ state: 'failed', message: 'Native window capture failed' }); stop(); } });
    return true;
  } catch (_) { owner.bridge.state({ state: 'failed', message: 'Native window capture failed' }); stop(); return false; }
}
function stop() {
  const owner = current; current = null;
  if (!owner) return;
  owner.control?.stop(); owner.canvas?.stop();
  const closing = Promise.resolve(owner.media?.stop()).finally(() => owner.bridge.close());
  state({ state: 'stopped' });
  return closing;
}
globalThis.BossAppShareHost = { start, frame, stop, resolve: resolveBridgeRequest, reject: rejectBridgeRequest };
globalThis.addEventListener('pagehide', stop);
state({ state: 'ready' });
if (globalThis.__bossAppShareConfig && globalThis.__bossAppShareConfig.autoStart !== false) start(globalThis.__bossAppShareConfig).catch(() => {});
