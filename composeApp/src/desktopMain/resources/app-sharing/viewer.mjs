import { createBridge, closeViewerResources, resolveBridgeRequest, rejectBridgeRequest } from './bridge.mjs';
import { WindowMediaPeer } from './media.mjs';
import { AppControlChannel, videoPoint } from './control.mjs';
import { browserViewerConfig } from './browser-bootstrap.mjs';

const video = document.getElementById('screen');
const status = document.getElementById('status');
const controlButton = document.getElementById('control');
const windowPicker = document.getElementById('window');
const windowLabel = document.getElementById('window-label');
let current = null;
let navigation = 0;
function text(value) { status.textContent = value; }
function stopOwner() {
  const owner = current; current = null;
  if (!owner) return;
  const closing = closeViewerResources(owner); video.srcObject = null;
  controlButton.disabled = true; controlButton.textContent = 'Take control'; text('Disconnected');
  return closing;
}
function stop() { navigation++; return stopOwner(); }
function configureWindows(config) {
  windowPicker.replaceChildren();
  for (const window of config.windows ?? []) {
    const option = document.createElement('option'); option.value = window.id; option.textContent = window.title;
    windowPicker.append(option);
  }
  windowPicker.value = config.windowId;
  windowPicker.disabled = false;
  windowLabel.hidden = !config.canSwitchWindows || (config.windows?.length ?? 0) < 2;
}
async function start(value) {
  const attempt = ++navigation;
  await stopOwner();
  if (attempt !== navigation) return;
  const config = typeof value === 'string' ? JSON.parse(value) : value;
  const bridge = createBridge(config);
  const owner = { bridge, config }; current = owner; configureWindows(config); text('Connecting securely…');
  try {
    owner.media = new WindowMediaPeer({ config, request: body => bridge.request(body), onState: state => {
      bridge.state({ state });
      if (current !== owner) return;
      if (state === 'connected') {
        text('Viewing BossConsole');
        if (owner.control?.channel?.readyState === 'open') controlButton.disabled = config.role === 'view';
      }
      if (['failed', 'disconnected', 'stopped'].includes(state)) {
        text('Connection lost. Reopen this session to reconnect.');
        if (state === 'disconnected') owner.control?.releaseControl(); else owner.control?.stop();
        controlButton.disabled = true;
      }
    } });
    await owner.media.subscribe(track => { video.srcObject = new MediaStream([track]); video.play().catch(() => text('Select the video to start playback')); });
    if (current !== owner) return;
    owner.control = new AppControlChannel(owner.media, {
      onLease: lease => {
        if (current !== owner) return;
        owner.lease = lease;
        controlButton.textContent = lease ? 'Release control' : 'Take control';
        text(lease ? 'You control this BossConsole window' : 'Viewing BossConsole');
      },
      onGeometry: geometry => { if (current === owner) owner.geometry = geometry; },
      onError: message => { if (current === owner) text(message); },
    });
    try {
      await owner.control.start();
      if (current === owner) { controlButton.disabled = config.role === 'view'; text('Viewing BossConsole'); }
    } catch (_) { if (current === owner) text('Viewing BossConsole. Remote control is unavailable.'); }
  } catch (_) {
    if (current === owner) { stop(); text('Unable to connect. Reopen the session from BossConsole.'); }
  }
}
async function switchWindow() {
  const owner = current, selected = windowPicker.value;
  if (!owner?.config.canSwitchWindows || !owner.config.windows.some(window => window.id === selected) || selected === owner.config.windowId) return;
  const attempt = ++navigation; windowPicker.disabled = true; text('Switching shared window…');
  try {
    const config = await browserViewerConfig(globalThis.location, fetch, selected);
    if (attempt !== navigation) return;
    // Admission initially grants view only. start() awaits old lease/media
    // cleanup before subscribing; no old control lease follows the new window.
    await start(config);
  } catch (_) {
    if (current === owner && attempt === navigation) { windowPicker.value = owner.config.windowId; windowPicker.disabled = false; text('This shared window is unavailable.'); }
  }
}
async function takeControl() {
  const owner = current;
  if (!owner?.control) return;
  controlButton.disabled = true;
  try { await owner.control.takeControl(); video.focus(); }
  catch (_) { text('Control is unavailable or another viewer has control'); }
  finally { if (current === owner) controlButton.disabled = false; }
}
function releaseControl() { return current?.control?.releaseControl(); }
function point(event) {
  const geometry = current?.geometry;
  return geometry && videoPoint(event.clientX, event.clientY, video.getBoundingClientRect(), geometry.width, geometry.height);
}
for (const [eventName, action] of [['pointermove', 'move'], ['pointerdown', 'down'], ['pointerup', 'up']]) {
  video.addEventListener(eventName, event => {
    if (!current?.lease) return;
    const coordinates = point(event);
    // Releasing outside the image cannot leave a held host button behind.
    if (!coordinates) { if (action === 'up') releaseControl(); return; }
    if (event.button > 2) return;
    event.preventDefault();
    if (action === 'down') { video.focus(); video.setPointerCapture(event.pointerId); }
    current.control.send({ type: 'pointer', action, ...coordinates, button: event.button < 0 ? 0 : event.button });
  });
}
video.addEventListener('pointercancel', releaseControl);
video.addEventListener('wheel', event => {
  if (!current?.lease) return;
  const coordinates = point(event); if (!coordinates) return;
  event.preventDefault();
  const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 400 : 1;
  current.control.send({ type: 'wheel', ...coordinates, deltaX: Math.max(-1000, Math.min(1000, event.deltaX * unit)), deltaY: Math.max(-1000, Math.min(1000, event.deltaY * unit)) });
}, { passive: false });
for (const [eventName, action] of [['keydown', 'down'], ['keyup', 'up']]) video.addEventListener(eventName, event => {
  if (!current?.lease) return;
  event.preventDefault();
  if (event.isComposing) return;
  current.control.send({ type: 'key', action, code: event.code, key: event.key, alt: event.altKey, ctrl: event.ctrlKey, meta: event.metaKey, shift: event.shiftKey });
});
video.addEventListener('contextmenu', event => { if (current?.lease) event.preventDefault(); });
video.addEventListener('click', () => video.play().catch(() => {}));
globalThis.addEventListener('blur', releaseControl);
document.addEventListener('visibilitychange', () => { if (document.hidden) releaseControl(); });
globalThis.addEventListener('pagehide', stop);
controlButton.addEventListener('click', () => current?.lease ? releaseControl() : takeControl());
document.getElementById('disconnect').addEventListener('click', stop);
windowPicker.addEventListener('change', switchWindow);
globalThis.BossAppShareViewer = { start, stop, takeControl, releaseControl, resolve: resolveBridgeRequest, reject: rejectBridgeRequest };
globalThis.__bossAppShareBridge?.state?.(JSON.stringify({ state: 'ready' }));
if (globalThis.__bossAppShareConfig && globalThis.__bossAppShareConfig.autoStart !== false) start(globalThis.__bossAppShareConfig);
else if (!globalThis.__bossAppShareConfig) browserViewerConfig().then(start).catch(error => {
  text(error.message);
  if (error.loginUrl) {
    const link = document.createElement('a'); link.href = error.loginUrl; link.textContent = 'Sign in';
    status.append(document.createTextNode(' '), link);
  }
});
