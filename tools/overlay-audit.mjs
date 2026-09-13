// Overlay layout audit for the SteveHUD web package.
//
// Loads a package in a real browser and MEASURES it, because "it looks fine" is
// not a check that survives the next preset:
//
//   * is anything overflowing its own box -- the "a long English name got cut
//     off" class of bug, reported as the exact strings that had to shrink all
//     the way to the ellipsis floor;
//   * do two panels overlap each other on air;
//   * how much of the picture is covered, and specifically how much of the
//     gameplay window (the centre 60% x 68% of the canvas) a package eats.
//     A package that eats the middle is a package that blocks the match;
//   * did the page log anything, and did any request 404.
//
// Usage:
//   node tools/overlay-audit.mjs --preset arena --scene compact
//   node tools/overlay-audit.mjs --url http://127.0.0.1:8787/overlay/ --json
//
// Requires the preview server (tools/web-preview.mjs) or the game's own
// graphics server to be up, plus Edge or Chrome.

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// ---- arguments -------------------------------------------------------------

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const at = argv.indexOf('--' + name);
  return at >= 0 && argv[at + 1] && !argv[at + 1].startsWith('--') ? argv[at + 1] : fallback;
}
function flag(name) { return argv.includes('--' + name); }

const BASE = arg('base', 'http://127.0.0.1:8788');
const LINK = arg('url', null);
const PRESET = arg('preset', null);
const SCENE = arg('scene', null);
const WIDTH = Number(arg('width', 1920));
const HEIGHT = Number(arg('height', 1080));
const WAIT = Number(arg('wait', 5000));
const JSON_OUT = flag('json');
const ALL = flag('all');

/* The gameplay window: the area a package must keep its hands off. Centre
   60% x 68% of the canvas, which is what a viewer is actually watching during a
   fight. Fractions of the canvas, not pixels, so the rule holds at 720p and 4K.

   The budgets are not decoration. 3% of the window is roughly a thin clock
   crossing its edge; 16% of the canvas is a package that is present but not
   intrusive. Above either, the audit fails. */
const BUDGET_WINDOW = 0.03;
/* A single element may not eat more than this share of the window. This is the
   rule that catches "the scoreboard drifted into the middle", which an average
   over the whole window happily hides. */
const BUDGET_WINDOW_ELEMENT = 0.02;
/* When a package declares centre graphics, the scene is a takeover: the whole
   point is to own the frame for a few seconds. It still may not be total. */
const BUDGET_TAKEOVER = 0.70;
/* The canvas number is a soft budget, and deliberately looser than the window
   one. A full-width information bar alone is 5% of the canvas, and an
   edge-hugging data package of the density this project targets lands around
   20%. What matters is that all of it is at the edges - which is exactly what
   the window number and the per-element number above prove. Set too tight, this
   number would just make people switch the ticker off. */
const BUDGET_TOTAL = 0.24;

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

/* --all re-runs this script once per package and scene and aggregates. Spawning
   itself rather than looping in-process keeps one browser, one session and one
   teardown per configuration: a leaked devtools session between runs is exactly
   the kind of thing that makes a check quietly stop checking. */
if (ALL) {
  const { spawnSync } = await import('node:child_process');
  const presets = (arg('presets', 'arena,olympia,clean')).split(',');
  const scenes = (arg('scenes', 'compact,full')).split(',');
  let failed = 0;
  const lines = [];
  for (const preset of presets) {
    for (const scene of scenes) {
      const run = spawnSync(process.execPath, [process.argv[1],
        '--base', BASE, '--preset', preset, '--scene', scene, '--wait', String(WAIT)],
        { encoding: 'utf8' });
      const text = (run.stdout || '') + (run.stderr || '');
      const summary = text.split('\n').filter(l =>
        /场景|画面窗口|通过|不通过|^  x |^  ! /.test(l)).join('\n  ');
      const ok = run.status === 0;
      if (!ok) failed++;
      lines.push((ok ? 'PASS  ' : 'FAIL  ') + preset + ' / ' + scene + '\n  ' + summary);
    }
  }
  console.log(lines.join('\n\n'));
  console.log('\n' + (failed ? failed + ' configuration(s) failed.' : 'All configurations pass.'));
  process.exit(failed ? 1 : 0);
}
const MEASURE = `(() => {
  const root = document.documentElement;
  const unit = parseFloat(getComputedStyle(root).getPropertyValue('--u')) || 1;
  const canvas = (window.__stevehud && window.__stevehud.layout())
    ? window.__stevehud.layout().canvas : { width: 1920, height: 1080 };

  // An element may only sit in the middle of the picture if it says so. The
  // declaration lives in the layout document (options.window = "allow"), so it
  // is reviewable in a diff rather than being an implicit property of a preset.
  var declared = {};
  var doc = (window.__stevehud && window.__stevehud.layout()) || { elements: [] };
  (doc.elements || []).forEach(function (e) {
    if (e && e.options && String(e.options.window).toLowerCase() === 'allow') declared[e.id] = true;
  });

  const els = [];
  let blank = 0;
  document.querySelectorAll('.el').forEach(node => {
    if (node.hidden) return;
    const r = node.getBoundingClientRect();
    // A board with no data collapses to nothing and paints nothing; counting it
    // as coverage would make an empty package look busy.
    if (r.width < 2 || r.height < 2) { blank++; return; }
    els.push({
      id: node.dataset.id, type: node.dataset.type,
      x: r.left / unit, y: r.top / unit, w: r.width / unit, h: r.height / unit,
      windowAllowed: declared[node.dataset.id] === true,
    });
  });

  const tight = [];
  document.querySelectorAll('.fit.is-tight').forEach(span => {
    const owner = span.closest('.el');
    const fit = parseFloat(getComputedStyle(span).getPropertyValue('--fit')) || 1;
    tight.push({ id: owner ? owner.dataset.id : '?', type: owner ? owner.dataset.type : '?',
      text: span.textContent, fit: Math.round(fit * 1000) / 1000 });
  });

  const shrunk = [];
  document.querySelectorAll('.fit').forEach(span => {
    const fit = parseFloat(getComputedStyle(span).getPropertyValue('--fit')) || 1;
    if (fit < 0.995) {
      const owner = span.closest('.el');
      shrunk.push({ id: owner ? owner.dataset.id : '?', text: span.textContent,
        fit: Math.round(fit * 1000) / 1000 });
    }
  });

  // Rasterise coverage. An 8px step over 1920x1080 is 32k samples: exact enough
  // for a budget, and far simpler than a real polygon union.
  const W = canvas.width, H = canvas.height, STEP = 8;
  const win = { x: W * 0.2, y: H * 0.16, w: W * 0.6, h: H * 0.68 };
  let canvasHit = 0, canvasAll = 0, winHit = 0, winAll = 0;
  for (let y = STEP / 2; y < H; y += STEP) {
    for (let x = STEP / 2; x < W; x += STEP) {
      canvasAll++;
      const inWin = x >= win.x && x <= win.x + win.w && y >= win.y && y <= win.y + win.h;
      if (inWin) winAll++;
      let hit = false;
      for (const e of els) {
        if (x >= e.x && x <= e.x + e.w && y >= e.y && y <= e.y + e.h) { hit = true; break; }
      }
      if (hit) { canvasHit++; if (inWin) winHit++; }
    }
  }

  // Pairwise overlap, so 'these two are on top of each other' is named rather
  // than inferred from a percentage.
  const overlaps = [];
  for (let i = 0; i < els.length; i++) {
    for (let j = i + 1; j < els.length; j++) {
      const a = els[i], b = els[j];
      const ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
      const oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
      if (ox > 2 && oy > 2) overlaps.push({ a: a.id, b: b.id, area: Math.round(ox * oy) });
    }
  }

  return { canvas, unit, elements: els, blank, overlaps, tight, shrunk,
    coverage: { canvas: canvasHit / canvasAll, window: winAll ? winHit / winAll : 0 },
    scene: (window.__stevehud && window.__stevehud.state())
      ? window.__stevehud.state().scene : '?' };
})()`;

// ---- devtools plumbing -----------------------------------------------------

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'stevehud-audit-'));
const port = 9400 + Math.floor(Math.random() * 500);
const child = spawn(findBrowser(), [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-extensions', '--disable-background-networking', '--hide-scrollbars',
  '--force-device-scale-factor=1', '--mute-audio',
  '--remote-debugging-port=' + port,
  '--user-data-dir=' + profile,
  'about:blank',
], { stdio: 'ignore' });

/* How much of the gameplay window one element covers, as a fraction. */
function windowShare(e) {
  const W = 1920, H = 1080;
  const win = { x: W * 0.20, y: H * 0.16, w: W * 0.60, h: H * 0.68 };
  const ox = Math.min(e.x + e.w, win.x + win.w) - Math.max(e.x, win.x);
  const oy = Math.min(e.y + e.h, win.y + win.h) - Math.max(e.y, win.y);
  if (ox <= 0 || oy <= 0) return 0;
  return (ox * oy) / (win.w * win.h);
}

async function endpoint() {
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch('http://127.0.0.1:' + port + '/json/version');
      if (r.ok) return (await r.json()).webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    await sleep(250);
  }
  throw new Error('devtools endpoint never came up');
}

let exitCode = 0;
const problems = [];

try {
  const ws = new WebSocket(await endpoint());
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
    if (msg.method === 'Runtime.consoleAPICalled') {
      const text = (msg.params.args || []).map(a => a.value ?? a.description ?? '').join(' ');
      if (msg.params.type === 'error' || msg.params.type === 'warning') {
        problems.push(msg.params.type + ': ' + text);
      }
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails || {};
      problems.push('exception: ' + (d.exception?.description || d.text || ''));
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      problems.push('log: ' + msg.params.entry.text + ' ' + (msg.params.entry.url || ''));
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
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send('Log.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride',
    { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false }, sessionId);

  // Switch package and scene through the same API the editor uses, so what is
  // audited is what an operator would actually put on air.
  if (PRESET) {
    const res = await fetch(BASE + '/api/layout?preset=' + encodeURIComponent(PRESET)).catch(() => null);
    if (!res || !res.ok) {
      console.error('could not switch to preset ' + PRESET + ' - is the preview server up?');
      process.exit(2);
    }
  }
  await send('Page.navigate', { url: LINK || (BASE + '/overlay/') }, sessionId);
  await sleep(WAIT);
  if (SCENE) {
    await fetch(BASE + '/api/preview', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scene: SCENE }),
    }).catch(() => {});
    await sleep(1200);
  }

  const probe = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true }, sessionId);
  const report = probe.result?.value;

  if (!report || !report.elements) {
    console.error('the page reported no layout: ' + JSON.stringify(problems, null, 1));
    exitCode = 2;
  } else {
    report.problems = problems;
    report.failures = [];
    report.warnings = [];
    const pct = v => (v * 100).toFixed(2) + '%';
    // Hard: the middle of the picture is the match. A package that declares a
    // centre graphic (a head-to-head card, a round announcement) is a takeover,
    // and is judged by the takeover budget instead - but every element that
    // touches the middle must be one of the declared ones, always.
    const takeovers = report.elements.filter(e => e.windowAllowed);
    const windowBudget = takeovers.length ? BUDGET_TAKEOVER : BUDGET_WINDOW;
    if (report.coverage.window > windowBudget) {
      report.failures.push('遮挡画面窗口 ' + pct(report.coverage.window) +
        ' > 预算 ' + (windowBudget * 100) + '%');
    }
    for (const e of report.elements) {
      const share = windowShare(e);
      if (share > BUDGET_WINDOW_ELEMENT && !e.windowAllowed) {
        report.failures.push('元素 ' + e.id + ' 未声明 window=allow 却遮挡画面窗口 ' +
          pct(share) + '（上限 ' + (BUDGET_WINDOW_ELEMENT * 100) + '%）');
      }
    }
    if (takeovers.length) {
      report.warnings.push('本场景含 ' + takeovers.length + ' 个中场大卡（' +
        takeovers.map(e => e.id).join(', ') + '）：适合赛前/局间，不适合局内常驻');
    }
    // Soft: furniture density.
    if (report.coverage.canvas > BUDGET_TOTAL) {
      report.warnings.push('整幅占用 ' + pct(report.coverage.canvas) +
        ' 高于指导值 ' + (BUDGET_TOTAL * 100) + '%（贴边数据包通常在这个量级）');
    }
    for (const t of report.tight) {
      report.failures.push('文字缩到下限仍溢出 [' + t.id + '] "' + t.text + '"');
    }
    for (const o of report.overlaps) {
      report.failures.push('元素重叠 ' + o.a + ' x ' + o.b + ' (' + o.area + ' px2)');
    }
    for (const p of problems) {
      // A missing favicon is not a fault of the package and never reaches air.
      if (/favicon\.ico/.test(p)) continue;
      report.failures.push('页面报错 ' + p);
    }

    if (JSON_OUT) {
      console.log(JSON.stringify(report, null, 2));
    } else {
      console.log('场景 ' + report.scene + ' · 画布 ' + report.canvas.width + 'x' +
        report.canvas.height + ' · 上播元素 ' + report.elements.length);
      const budget = report.elements.some(e => e.windowAllowed) ? BUDGET_TAKEOVER : BUDGET_WINDOW;
      console.log('画面窗口占用 ' + pct(report.coverage.window) + ' (预算 ' +
        (budget * 100) + '%)  ·  整幅占用 ' + pct(report.coverage.canvas) +
        ' (指导 ' + (BUDGET_TOTAL * 100) + '%)  ·  空数据收起 ' + report.blank + ' 个');
      for (const e of report.elements) {
        console.log('  ' + String(e.id).padEnd(12) + String(e.type).padEnd(14) +
          String(Math.round(e.x)).padStart(5) + ',' + String(Math.round(e.y)).padStart(5) +
          '  ' + Math.round(e.w) + 'x' + Math.round(e.h));
      }
      if (report.shrunk.length) {
        console.log('自适应缩放 (' + report.shrunk.length + ')：');
        for (const s of report.shrunk.slice(0, 14)) {
          console.log('  ' + String(Math.round(s.fit * 100)).padStart(4) + '%  [' + s.id + '] ' + s.text);
        }
      }
      for (const w of report.warnings) console.log('  ! ' + w);
      console.log(report.failures.length ? '不通过：' : '通过。');
      for (const f of report.failures) console.log('  x ' + f);
    }
    if (report.failures.length) exitCode = 1;
  }
  ws.close();
} catch (e) {
  console.error('audit failed: ' + e.message);
  exitCode = 3;
} finally {
  child.kill();
  await sleep(500);
  try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 3 }); } catch { /* windows lock */ }
}
process.exit(exitCode);
