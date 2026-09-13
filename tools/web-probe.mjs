// Runs one JavaScript expression inside a real page and prints what it returns,
// plus everything the page logged. The missing half of tools/overlay-audit.mjs:
// that one asks "is the package drawable", this one asks "does the page work".
//
//   node tools/web-probe.mjs --url http://127.0.0.1:8788/editor/ \
//     --js "document.querySelectorAll('.ed-layer').length"
//
// A .mjs file of its own rather than a --flag on the audit, because the two
// answer different questions and a probe expression is not a layout document.
//
// Exits non-zero when the page threw or logged an error, so it works as a smoke
// test in a script. A missing favicon is not an error and is ignored.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const at = argv.indexOf('--' + name);
  return at >= 0 && argv[at + 1] ? argv[at + 1] : fallback;
}

const LINK = arg('url', 'http://127.0.0.1:8788/editor/');
const JS = arg('js', 'document.title');
const WAIT = Number(arg('wait', 5000));
const WIDTH = Number(arg('width', 1920));
const HEIGHT = Number(arg('height', 1080));

function findBrowser() {
  const candidates = [
    process.env.STEVEHUD_BROWSER,
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ].filter(Boolean);
  for (const c of candidates) if (fs.existsSync(c)) return c;
  throw new Error('no Edge or Chrome found; set STEVEHUD_BROWSER');
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'stevehud-probe-'));
const port = 9700 + Math.floor(Math.random() * 200);
const child = spawn(findBrowser(), [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
  '--disable-background-networking', '--hide-scrollbars', '--mute-audio',
  '--remote-debugging-port=' + port, '--user-data-dir=' + profile, 'about:blank',
], { stdio: 'ignore' });

let exitCode = 0;
const problems = [];

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
      return;
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails || {};
      problems.push('exception: ' + (d.exception?.description || d.text || ''));
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      // The message alone does not name the resource, so the url field is what
      // decides whether a 404 is the page's problem or the browser asking for a
      // favicon nobody shipped.
      const where = msg.params.entry.url || '';
      if (!/favicon/.test(where) && !/favicon/.test(msg.params.entry.text)) {
        problems.push('log: ' + msg.params.entry.text + ' ' + where);
      }
    }
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
      problems.push('console: ' + (msg.params.args || []).map(a => a.value ?? a.description ?? '').join(' '));
    }
  };
  const send = (method, params = {}, sessionId) => {
    const id = ++seq;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    });
  };

  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Runtime.enable', {}, sessionId);
  await send('Log.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride',
    { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false }, sessionId);
  await send('Page.navigate', { url: LINK }, sessionId);
  await sleep(WAIT);

  const out = await send('Runtime.evaluate',
    { expression: JS, returnByValue: true, awaitPromise: true }, sessionId);
  if (out.exceptionDetails) {
    problems.push('probe threw: ' + (out.exceptionDetails.exception?.description
      || out.exceptionDetails.text || ''));
  } else {
    console.log(JSON.stringify(out.result?.value ?? null, null, 2));
  }

  ws.close();
} catch (e) {
  problems.push('probe failed: ' + e.message);
} finally {
  child.kill();
  await sleep(400);
  try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3 }); } catch { /* windows lock */ }
}

if (problems.length) {
  console.error('page problems:');
  for (const p of problems) console.error('  x ' + p);
  exitCode = 1;
}
process.exit(exitCode);
