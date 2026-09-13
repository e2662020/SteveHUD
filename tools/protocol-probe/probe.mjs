// Headless protocol test for SteveHUD.
//
// Connects to a server as a vanilla-protocol client and checks the half of the
// handshake that lives on the server: that the plugin's sendPluginMessage calls
// actually reach a client, on the expected channel, carrying text that parses as
// a SteveHUD envelope whose fields make sense.
//
// It deliberately registers the channel first. Bukkit only delivers an outgoing
// plugin message to a client that has declared it accepts the channel, so
// registering is both required and a useful control: it separates "the server
// never sent" from "the client never registered".
//
// What this cannot check is the Fabric-side receiver, which needs the real mod.
//
// Usage:
//   node probe.mjs
//   MC_HOST=127.0.0.1 MC_PORT=25565 MC_USERNAME=SHUDProbe node probe.mjs
//
// Exit code 0 if every check passes, 1 otherwise, so it can be used as a test.

import mineflayer from 'mineflayer';

const HOST = process.env.MC_HOST ?? '127.0.0.1';
const PORT = Number(process.env.MC_PORT ?? 25565);
const USERNAME = process.env.MC_USERNAME ?? 'SHUDProbe';
const WAIT_MS = Number(process.env.WAIT_MS ?? 9000);
const REGISTER = process.env.SKIP_REGISTER !== '1';

const CHANNEL = 'stevehud:main';
const EXPECTED_PROTOCOL = 2;

const checks = [];
const payloads = [];
const packetNames = new Set();

function check(name, ok, detail = '') {
  checks.push({ name, ok, detail });
  // ASCII only. This output gets consumed by shells with every imaginable code
  // page, and a non-ASCII separator turns into mojibake in a Windows console.
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` - ${detail}` : ''}`);
}

const bot = mineflayer.createBot({
  host: HOST,
  port: PORT,
  username: USERNAME,
  auth: 'offline',
  // Let mineflayer read the version from the server's handshake response, so the
  // probe keeps working when the test server moves to another 1.21.x build.
  hideErrors: false,
});

bot.once('login', () => {
  console.log(`connected as ${bot.username}, protocol ${bot.protocolVersion}`);
});

bot.once('spawn', () => {
  if (!REGISTER) {
    console.log('SKIP_REGISTER=1: not registering the channel (negative control)');
    return;
  }
  bot._client.write('custom_payload', {
    channel: 'minecraft:register',
    data: Buffer.from(`${CHANNEL}\0`, 'utf8'),
  });
  console.log(`registered ${CHANNEL} with the server`);
});

bot._client.on('packet', (data, meta) => {
  packetNames.add(meta.name);
  if (typeof data?.channel !== 'string') {
    return;
  }
  const raw = data.data ?? data.payload;
  const bytes = Buffer.isBuffer(raw) ? raw : Buffer.from(raw ?? []);
  payloads.push({ channel: data.channel, bytes });

  if (data.channel !== CHANNEL) {
    return;
  }
  console.log(`received ${bytes.length} bytes on ${CHANNEL}`);
  console.log(bytes.toString('utf8'));
});

// Without this, a packet the protocol library cannot decode is swallowed and the
// test would look like it simply received nothing.
bot._client.on('error', e => console.log(`protocol error: ${e?.message ?? e}`));
bot.on('kicked', reason =>
  console.log(`kicked: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`));
bot.on('error', e => console.log(`error: ${e?.message ?? e}`));

setTimeout(() => {
  console.log('');
  console.log('checks:');

  if (!REGISTER) {
    // Count only our own channel: a server always sends minecraft:brand, so
    // "no plugin messages at all" would never hold and would prove nothing.
    const oursWithoutRegistering = payloads.filter(p => p.channel === CHANNEL);
    check('negative control: nothing arrives on our channel without registering',
      oursWithoutRegistering.length === 0,
      `${oursWithoutRegistering.length} on ${CHANNEL}, ${payloads.length} plugin message(s) overall`);
    return finish();
  }

  const ours = payloads.filter(p => p.channel === CHANNEL);
  check('a payload arrived on ' + CHANNEL, ours.length > 0,
    `${ours.length} message(s), ${packetNames.size} distinct packet types seen`);
  if (ours.length === 0) {
    return finish();
  }

  let envelope = null;
  try {
    envelope = JSON.parse(ours[0].bytes.toString('utf8'));
    check('the payload is JSON', true);
  } catch (e) {
    check('the payload is JSON', false, e.message);
    return finish();
  }

  check('protocol version matches', envelope.v === EXPECTED_PROTOCOL,
    `declared ${envelope.v}, client speaks ${EXPECTED_PROTOCOL}`);
  check('message type is HELLO', envelope.type === 'HELLO', `got ${envelope.type}`);
  check('it is not fragmented', envelope.total === 1 && envelope.index === 0,
    `index ${envelope.index} of ${envelope.total}`);
  check('revision is present', Number.isInteger(envelope.rev) && envelope.rev >= 0,
    `rev ${envelope.rev}`);

  let body = null;
  try {
    body = JSON.parse(envelope.body);
    check('the body is JSON', true);
  } catch (e) {
    check('the body is JSON', false, e.message);
    return finish();
  }

  check('the body declares the same protocol version',
    body.protocolVersion === envelope.v, `body ${body.protocolVersion} vs envelope ${envelope.v}`);
  check('the body names an implementation',
    typeof body.implementation === 'string' && body.implementation.length > 0,
    body.implementation);
  check('the body lists capabilities',
    Array.isArray(body.capabilities) && body.capabilities.length > 0,
    (body.capabilities ?? []).join(', '));
  // Whether the client may author the graphics package is the server's decision
  // and travels in the greeting, so its presence is part of the contract.
  check('the body carries an operator flag',
    typeof body.operator === 'boolean',
    `operator=${body.operator} (this probe connects offline, so false is expected)`);

  finish();
}, WAIT_MS);

function finish() {
  const failed = checks.filter(c => !c.ok);
  console.log('');
  console.log(failed.length === 0
    ? `RESULT: PASS (${checks.length} checks)`
    : `RESULT: FAIL (${failed.length} of ${checks.length} checks failed)`);
  bot.quit();
  setTimeout(() => process.exit(failed.length === 0 ? 0 : 1), 400);
}
