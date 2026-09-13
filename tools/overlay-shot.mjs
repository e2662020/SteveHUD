// Renders a SteveHUD package over a stand-in for gameplay and writes a PNG.
//
// The overlay page is transparent on purpose - in OBS only the graphic elements
// paint and the game shows through. Which means a plain screenshot of it proves
// almost nothing: a panel that is illegible over a bright sky reads perfectly
// fine on a flat black page. So this injects a backdrop with the tonal range a
// real scene has - bright sky, mid-tone terrain, dark ground, a warm highlight
// and a cool one - and shoots the package on top of it.
//
//   node tools/overlay-shot.mjs --preset arena --scene compact --out .shots/arena.png
//   node tools/overlay-shot.mjs --url http://127.0.0.1:8788/editor/ --plain --out .shots/editor.png
//
// Requires the preview server (tools/web-preview.mjs).

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const at = argv.indexOf('--' + name);
  return at >= 0 && argv[at + 1] && !argv[at + 1].startsWith('--') ? argv[at + 1] : fallback;
}
function flag(name) { return argv.includes('--' + name); }

const BASE = arg('base', 'http://127.0.0.1:8788');
const PRESET = arg('preset', 'arena');
const SCENE = arg('scene', 'compact');
const LINK = arg('url', null);
const OUT = arg('out', '.shots/shot.png');
const WIDTH = Number(arg('width', 1920));
const HEIGHT = Number(arg('height', 1080));
const WAIT = Number(arg('wait', 6500));
const WITH_BACKDROP = !flag('plain');

function findBrowser() {
  const found = [
    process.env.STEVEHUD_BROWSER,
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ].filter(Boolean).find(p => fs.existsSync(p));
  if (!found) throw new Error('no Edge or Chrome found; set STEVEHUD_BROWSER');
  return found;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// The stand-in, built element by element rather than as an HTML string: no
// nested quoting to get wrong, and every value is a plain CSS property.
const BACKDROP = [
  '(function () {',
  '  var d = document.createElement("div");',
  '  d.id = "__backdrop";',
  '  d.style.cssText = "position:fixed;inset:0;z-index:0;pointer-events:none";',
  '  var sky = document.createElement("div");',
  '  sky.style.cssText = "position:absolute;inset:0";',
  '  sky.style.background = "radial-gradient(760px 470px at 76% 20%, rgba(255,246,214,.98), rgba(255,214,140,.35) 45%, transparent 66%), linear-gradient(180deg,#6aa8e0 0%,#9dc9ea 34%,#c9d68f 40%,#7d9b5c 48%,#4d6b3d 66%,#25301f 100%)";',
  '  var bands = document.createElement("div");',
  '  bands.style.cssText = "position:absolute;inset:0";',
  '  bands.style.background = "linear-gradient(90deg,transparent 44%,rgba(196,168,116,.9) 44% 56%,transparent 56%), linear-gradient(0deg,transparent 58%,rgba(112,84,56,.75) 58% 72%,transparent 72%), repeating-linear-gradient(90deg,rgba(0,0,0,.16) 0 3px,transparent 3px 52px)";',
  '  var warm = document.createElement("div");',
  '  warm.style.cssText = "position:absolute;left:16%;top:26%;width:340px;height:230px;filter:blur(20px);opacity:.85";',
  '  warm.style.background = "radial-gradient(circle at 50% 42%,#ffe066,#e4572e 68%,transparent 71%)";',
  '  var cool = document.createElement("div");',
  '  cool.style.cssText = "position:absolute;right:6%;bottom:18%;width:260px;height:180px;filter:blur(26px);opacity:.7";',
  '  cool.style.background = "radial-gradient(circle at 50% 50%,#6fd3ff,#1f6feb 70%,transparent 74%)";',
  '  d.appendChild(sky); d.appendChild(bands); d.appendChild(warm); d.appendChild(cool);',
  '  document.body.insertBefore(d, document.body.firstChild);',
  '  return true;',
  '})()',
].join("\n");

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'stevehud-shot-'));
const port = 9800 + Math.floor(Math.random() * 190);
const child = spawn(findBrowser(), [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
  '--disable-background-networking', '--hide-scrollbars', '--force-device-scale-factor=1',
  '--mute-audio', '--remote-debugging-port=' + port, '--user-data-dir=' + profile, 'about:blank',
], { stdio: 'ignore' });

let exitCode = 0;
try {
  let wsUrl = null;
  for (let i = 0; i < 80 && !wsUrl; i++) {
    try {
      const res = await fetch('http://127.0.0.1:' + port + '/json/version');
      if (res.ok) wsUrl = (await res.json()).webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    if (!wsUrl) await sleep(250);
  }
  if (!wsUrl) throw new Error('devtools endpoint never came up');

  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let seq = 0;
  const pending = new Map();
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    }
  };
  const send = (method, params = {}, sessionId) => {
    const id = ++seq;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    });
  };

  // Switch package and scene through the same API the editor uses, so what is
  // photographed is what an operator would actually put on air.
  if (PRESET) await fetch(BASE + '/api/layout?preset=' + encodeURIComponent(PRESET)).catch(() => {});
  if (SCENE) {
    await fetch(BASE + '/api/preview', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scene: SCENE }),
    }).catch(() => {});
  }

  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride',
    { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false }, sessionId);
  await send('Page.navigate', { url: LINK || (BASE + '/overlay/') }, sessionId);
  await sleep(WAIT);

  if (WITH_BACKDROP) {
    await send('Runtime.evaluate', { expression: BACKDROP, returnByValue: true }, sessionId);
    await sleep(1000);
  }

  // Collapse the entrance animations before capturing.
  //
  // Every element arrives with a keyframe that starts at opacity 0, and a still
  // image has no business racing one: catching it mid-flight produces a picture
  // of an empty package that is entirely convincing and entirely wrong. Setting
  // the duration to 0 with fill-mode:both jumps each element straight to the
  // state it settles in, which is the state worth photographing.
  await send('Runtime.evaluate', {
    expression: 'Array.prototype.forEach.call(' +
      'document.querySelectorAll(".el-body"), function (n) {' +
      '  n.className = n.className.split(" ").filter(function (c) {' +
      '    return c.indexOf("enter-") !== 0;' +
      '  }).join(" ");' +
      '}); "frozen"',
    returnByValue: true,
  }, sessionId);
  await sleep(700);

  // Warm the compositor with a throwaway frame, then force a reflow and settle
  // before the real capture.
  //
  // Without this the first capture can come back with layers that have not
  // painted yet: panels at partial opacity, a backdrop glow showing through a
  // panel that is supposed to be 95% opaque. That is not a rendering bug in the
  // package, it is a screenshot of an incomplete frame - and it is convincing
  // enough to send someone hunting for a defect that does not exist.
  await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }, sessionId);
  await send('Runtime.evaluate',
    { expression: 'void document.body.offsetHeight', returnByValue: true }, sessionId);
  await sleep(600);

  let shotData = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    shotData = await send('Page.captureScreenshot',
      { format: 'png', captureBeyondViewport: false }, sessionId);
    fs.mkdirSync(path.dirname(path.resolve(OUT)), { recursive: true });
    fs.writeFileSync(OUT, Buffer.from(shotData.data, 'base64'));
    await sleep(220);
  }
  console.log('wrote ' + OUT + ' (' + fs.statSync(OUT).size + ' bytes)');
  ws.close();
} catch (e) {
  console.error('shot failed: ' + e.message);
  exitCode = 1;
} finally {
  child.kill();
  await sleep(400);
  try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3 }); } catch { /* windows lock */ }
}
process.exit(exitCode);
