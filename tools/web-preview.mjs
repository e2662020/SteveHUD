// Preview server for the SteveHUD web package.
//
// Serves the overlay and editor pages from the source tree, with a canned match
// state, the real built-in layout packages, and a live Server-Sent Events
// stream, so the whole broadcast package can be designed and reviewed in a real
// browser WITHOUT launching Minecraft.
//
// It reads straight from the source tree:
//
//   mod/client-mc/src/main/resources/stevehud/web    the pages
//   protocol/src/main/resources/stevehud/layout      the layout presets
//
// The layout presets are the same JSON files the mod ships and loads, so a
// package designed here is the package the game will draw — not a copy of it.
// Editing a page or a preset and reloading the browser is the whole iteration
// loop. No build, no copy, no game restart.
//
// What it mimics, deliberately, is the mod's API surface:
//
//   GET  /api/state      the canned match state
//   GET  /api/layout     the current layout document
//   POST /api/layout     store a document and broadcast it as a "layout" event
//   POST /api/preview    merge a partial state over the real one
//   DELETE /api/preview  clear that override
//   GET  /events         SSE: default event = state, "layout" event = document
//
// Usage:
//   node tools/web-preview.mjs
//   node tools/web-preview.mjs --port 8080 --preset olympia
//
// Then open http://127.0.0.1:8788/overlay/ (and /editor/ for the editor).
//
// What this does NOT prove: that the mod serves the same files correctly, or
// that the state reaches the browser over the real channel. Those need the game.
// This is for the design loop, which is where the time actually goes.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const WEB_ROOT = path.join(REPO, 'mod', 'client-mc', 'src', 'main', 'resources', 'stevehud', 'web');
const LAYOUT_ROOT = path.join(REPO, 'protocol', 'src', 'main', 'resources', 'stevehud', 'layout');

const args = process.argv.slice(2);
function arg(name, fallback) {
  const at = args.indexOf('--' + name);
  return at >= 0 && args[at + 1] ? args[at + 1] : fallback;
}

const PORT = Number(arg('port', 8788));
const PRESET = arg('preset', 'arena');
const PRESETS = ['arena', 'olympia', 'clean'];

const PRESET_LABELS = {
  arena: "竞技场",
  olympia: "奥林匹亚",
  clean: "边线",
};

if (!fs.existsSync(WEB_ROOT)) {
  console.error(`web pages not found at ${WEB_ROOT}`);
  process.exit(1);
}
if (!fs.existsSync(path.join(LAYOUT_ROOT, PRESET + '.json'))) {
  console.error(`no preset "${PRESET}" in ${LAYOUT_ROOT}`);
  process.exit(1);
}

// ---- the layout document ---------------------------------------------------
// Read from the mod's own resources, so what is previewed is what ships.

function readPreset(name) {
  return JSON.parse(fs.readFileSync(path.join(LAYOUT_ROOT, name + '.json'), 'utf8'));
}

let layout = readPreset(PRESET);
let preset = PRESET;

// ---- the match state -------------------------------------------------------
// Shaped exactly as the mod publishes it, so what works here works in game.

/* The canned match carries the same shape the mod publishes, data boards
   included, so a package designed here is a package the game will draw. The
   names are deliberately long and mixed-script: a preview that only ever shows
   "T1" never exercises the text fitting, which is exactly where a package
   breaks on air. */
const LONG_HOME = '皇家马德里电子竞技俱乐部';
const LONG_AWAY = 'Natus Vincere Junior Academy';

function series(values) { return values; }

const state = {
  rev: 1,
  scene: 'full',
  event: { name: '春季联赛 总决赛', stage: 'BO5 · 第 3 局 · 决胜图' },
  sides: [
    {
      id: 'home', name: LONG_HOME, short: 'RMA', color: '#2FD8FF', score: 2,
      record: '14 - 3', series: series([0, 1200, 2400, 3100, 2900, 4200, 5600, 6800, 8100, 9400]),
      competitors: [
        { name: 'ClearLove', number: '07' },
        { name: 'Uzi', number: '11' },
        { name: 'Ming', number: '22' },
        { name: 'Xiaohu', number: '03' },
        { name: 'Bin', number: '19' },
      ],
    },
    {
      id: 'away', name: LONG_AWAY, short: 'NAV', color: '#FF4D7A', score: 1,
      record: '11 - 6', series: series([0, 900, 1800, 3600, 4100, 3900, 4700, 5200, 6100, 7000]),
      competitors: [
        { name: 's1mple', number: '01' },
        { name: 'b1t', number: '08' },
        { name: 'jL', number: '13' },
        { name: 'iM', number: '21' },
        { name: 'Aleksib', number: '04' },
      ],
    },
  ],
  clock: { label: '比赛计时', value: '24:18', running: true },
  ticker: [
    '欢迎收看春季联赛总决赛 · 图形由服务端统一控制，所有客户端显示一致',
    '第 3 局进行中 · 主队手握赛点',
  ],
  announcement: { title: '', subtitle: '', visible: false },
  lowerThird: { visible: false, side: '' },

  /* Data boards. One shape for every panel in the package: a keyed table of
     labelled metrics. A board with no rows draws nothing at all, so a package
     can ship with every panel configured and only the fed ones appear. */
  boards: [
    {
      key: 'compare', title: '数据对比', unit: '',
      rows: [
        { key: 'kills', label: '击杀', side: 'home', value: 42, display: '42' },
        { key: 'kills', label: '击杀', side: 'away', value: 37, display: '37' },
        { key: 'gold', label: '经济', side: 'home', value: 68400, display: '68.4K' },
        { key: 'gold', label: '经济', side: 'away', value: 61200, display: '61.2K' },
        { key: 'towers', label: '防御塔', side: 'home', value: 8, display: '8' },
        { key: 'towers', label: '防御塔', side: 'away', value: 5, display: '5' },
        { key: 'dragons', label: '元素龙', side: 'home', value: 3, display: '3' },
        { key: 'dragons', label: '元素龙', side: 'away', value: 2, display: '2' },
      ],
    },
    {
      key: 'kills', title: '击杀榜', unit: 'K',
      rows: [
        { key: 'p1', label: 'Uzi', sub: 'RMA · 射手', value: 14, display: '14' },
        { key: 'p2', label: 's1mple', sub: 'NAV · 狙击', value: 12, display: '12' },
        { key: 'p3', label: 'ClearLove', sub: 'RMA · 打野', value: 9, display: '9' },
        { key: 'p4', label: 'b1t', sub: 'NAV · 步枪', value: 7, display: '7' },
        { key: 'p5', label: 'Xiaohu', sub: 'RMA · 中路', value: 5, display: '5' },
      ],
    },
    {
      key: 'totals', title: '关键数据', unit: '',
      rows: [
        { key: 't1', label: '总击杀', value: 79, display: '79', delta: 5 },
        { key: 't2', label: '平均经济', value: 64800, display: '64.8K', delta: -1200 },
        { key: 't3', label: '首杀率', value: 62, display: '62', unit: '%' },
        { key: 't4', label: '大龙控制', value: 3, display: '3', unit: '条' },
      ],
    },
    {
      key: 'series', title: '赛程', unit: '',
      rows: [
        { key: 'g1', index: 'G1', label: '裂谷之心', value: 1, display: '1 – 0', state: 'done' },
        { key: 'g2', index: 'G2', label: '黑色港湾', value: 0, display: '0 – 1', state: 'done' },
        { key: 'g3', index: 'G3', label: '苍穹之顶', value: 1, display: '进行中', state: 'live' },
        { key: 'g4', index: 'G4', label: '待定', value: 0, display: '—', state: 'todo' },
        { key: 'g5', index: 'G5', label: '待定', value: 0, display: '—', state: 'todo' },
      ],
    },
    {
      key: 'timeline', title: '关键时刻', unit: '',
      rows: [
        { key: 'e1', time: '03:12', label: '首杀 · Uzi', side: 'home', value: 0 },
        { key: 'e2', time: '07:48', label: '第一条元素龙 · NAV', side: 'away', value: 0 },
        { key: 'e3', time: '12:05', label: '一血塔 · RMA', side: 'home', value: 0 },
        { key: 'e4', time: '18:33', label: '团战 4 换 1 · RMA', side: 'home', value: 0 },
        { key: 'e5', time: '23:59', label: '大龙被 NAV 抢下', side: 'away', value: 0 },
      ],
    },
  ],

  preview: false,
  layoutName: layout.name,
  layoutPreset: preset,
  // Which scope is on air. The mod reports this from the server's package; here it
  // is always local, and a test can flip it through POST /api/preview.
  layoutScope: "local",
  layoutAuthor: "",
  link: { connected: true, millisSinceLastMessage: 0 },
};

let previewOverride = null;

function publishState() {
  const payload = { ...state, layoutName: layout.name, layoutPreset: preset };
  if (previewOverride) deepMergeInto(payload, previewOverride);
  payload.preview = previewOverride !== null;
  const frame = `data: ${JSON.stringify(payload)}\n\n`;
  for (const res of subscribers) res.write(frame);
}

function publishLayout() {
  const frame = `event: layout\ndata: ${JSON.stringify(layout)}\n\n`;
  for (const res of subscribers) res.write(frame);
}

const subscribers = new Set();

/* One rule for partial updates, shared with the mod's JsonMerge: objects merge
   a level deeper, everything else replaces. */
function deepMergeInto(base, patch) {
  for (const [key, value] of Object.entries(patch)) {
    const existing = base[key];
    if (value && typeof value === 'object' && !Array.isArray(value) &&
        existing && typeof existing === 'object' && !Array.isArray(existing)) {
      deepMergeInto(existing, value);
    } else {
      base[key] = value;
    }
  }
  return base;
}

function patch(changes) {
  Object.assign(state, changes);
  state.rev += 1;
  publishState();
}

function lowerThird(side) {
  patch({ lowerThird: { visible: Boolean(side), side: side ?? '' } });
}

function announce(title, subtitle) {
  patch({ announcement: { title, subtitle, visible: Boolean(title) } });
}

function score(sideId, delta) {
  const side = state.sides.find(s => s.id === sideId);
  if (side) {
    side.score = Math.max(0, side.score + delta);
    state.rev += 1;
    publishState();
  }
}

// ---- the demonstration loop ------------------------------------------------
// One step per second, cycling, so every element appears without anyone waiting.

let tick = 0;
let clockSeconds = 0;
const CYCLE = 44;

const script = [
  [0, () => { state.sides.forEach(s => { s.score = 0; }); }],
  [1, () => patch({ scene: 'compact' })],
  [2, () => lowerThird('home')],
  [6, () => lowerThird('away')],
  [10, () => score('home', 1)],
  [13, () => announce('ROUND 3', '决胜局')],
  [17, () => { announce('', ''); score('away', 1); }],
  [21, () => patch({ ticker: ['主队拿下一分', '图形随比赛数据实时更新'] })],
  [25, () => {
    lowerThird('');
    clockSeconds = 0;
    state.clock.running = true;
    state.clock.value = '00:00';
  }],
  [34, () => announce('MATCH POINT', '赛点')],
  [38, () => { announce('', ''); state.clock.running = false; }],
  [40, () => patch({ ticker: ['演示循环重新开始', '编辑页面后刷新浏览器即可看到效果'] })],
];

setInterval(() => {
  // The match clock advances independently of the script, and a match clock that
  // does not tick is not much of a demonstration.
  if (state.clock.running) {
    clockSeconds += 1;
    state.clock.value =
      `${String(Math.floor(clockSeconds / 60)).padStart(2, '0')}:${String(clockSeconds % 60).padStart(2, '0')}`;
  }
  for (const [at, action] of script) {
    if (at === tick) action();
  }
  // Published once per second either way: during a match the state really does
  // change about this often, so the page sees a realistic stream.
  state.rev += 1;
  publishState();
  tick = (tick + 1) % CYCLE;
}, 1000);

// ---- serving ---------------------------------------------------------------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

function sendFile(res, file) {
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(`not found: ${file}\n`);
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    res.end(data);
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => resolve(body));
  });
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': MIME['.json'], 'Cache-Control': 'no-store' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const pathname = decodeURIComponent(url.pathname);

  if (pathname === '/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-store',
      Connection: 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    });
    subscribers.add(res);
    // Prime both documents immediately, exactly as the mod does, so a page
    // opened mid-loop is not blank.
    res.write(`data: ${JSON.stringify(state)}\n\n`);
    res.write(`event: layout\ndata: ${JSON.stringify(layout)}\n\n`);
    req.on('close', () => subscribers.delete(res));
    return;
  }

  if (pathname === '/api/state') {
    const payload = { ...state, layoutName: layout.name, layoutPreset: preset };
    if (previewOverride) deepMergeInto(payload, previewOverride);
    payload.preview = previewOverride !== null;
    json(res, 200, JSON.stringify(payload));
    return;
  }

  if (pathname === '/api/presets') {
    // Same shape the mod serves, labels included, so the editor looks identical
    // here and in game.
    // Each package's palette, read WITHOUT applying it. GET /api/layout?preset=
    // switches the live package, so using it to paint a swatch would walk the
    // broadcast through every preset in the list.
    const themes = {};
    for (const name of PRESETS) themes[name] = readPreset(name).theme;
    json(res, 200, JSON.stringify({ presets: PRESETS, labels: PRESET_LABELS, themes, current: preset }));
    return;
  }

  if (pathname === '/api/layout') {
    const wanted = url.searchParams.get('preset');
    if (req.method === 'GET' && wanted) {
      if (!PRESETS.includes(wanted)) {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`no such preset: ${wanted}\n`);
        return;
      }
      preset = wanted;
      layout = readPreset(wanted);
      publishLayout();
      publishState();
      json(res, 200, JSON.stringify(layout));
      return;
    }
    if (req.method === 'GET') {
      json(res, 200, JSON.stringify(layout));
      return;
    }
    if (req.method !== 'POST') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8', Allow: 'GET, POST' });
      res.end('GET or POST a layout document here\n');
      return;
    }
    const body = await readBody(req);
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('that body could not be read as a layout document\n');
      return;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('a layout document is a JSON object\n');
      return;
    }
    layout = parsed;
    // Kept on disk so a package designed here can be copied into the mod's
    // config directory, and so a reviewer can diff it.
    const out = path.join(REPO, `web-layout-${preset}.json`);
    fs.writeFile(out, JSON.stringify(layout, null, 2), () => {
      console.log(`[preview] stored a layout (${body.length} bytes) -> ${out}`);
    });
    publishLayout();
    json(res, 200, JSON.stringify(layout));
    return;
  }

  if (pathname === '/api/preview') {
    if (req.method === 'DELETE') {
      previewOverride = null;
      publishState();
      json(res, 200, JSON.stringify({ ok: true, preview: false }));
      return;
    }
    if (req.method !== 'POST') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8', Allow: 'POST, DELETE' });
      res.end('POST a partial state, or DELETE to clear it\n');
      return;
    }
    const body = await readBody(req);
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('that body could not be applied as state\n');
      return;
    }
    previewOverride = deepMergeInto(previewOverride ?? {}, parsed);
    publishState();
    json(res, 200, JSON.stringify({ ok: true, preview: true }));
    return;
  }

  if (pathname === '/' || pathname === '') {
    res.writeHead(302, { Location: '/overlay/' });
    res.end();
    return;
  }

  // Directory requests fall back to index.html, so /editor/ works.
  let file = path.join(WEB_ROOT, pathname);
  if (!file.startsWith(WEB_ROOT)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('forbidden\n');
    return;
  }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) {
    file = path.join(file, 'index.html');
  }
  sendFile(res, file);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('');
  console.log('  SteveHUD web preview');
  console.log(`  pages    ${WEB_ROOT}`);
  console.log(`  layouts  ${LAYOUT_ROOT}`);
  console.log('');
  console.log(`  overlay  http://127.0.0.1:${PORT}/overlay/`);
  console.log(`  editor   http://127.0.0.1:${PORT}/editor/`);
  console.log(`  preset   ${preset}  (--preset ${PRESETS.join('|')})`);
  console.log('');
  console.log('  A canned match state streams, cycling through every element on a');
  console.log('  timer. Drag an element in the editor and the overlay beside it moves');
  console.log('  with it. Ctrl+C to stop.');
  console.log('');
});
