/**
 * Headless Sumo verification harness.
 *
 * Connects two real Minecraft protocol clients to the live Paper server and
 * plays an actual Sumo match: both bots run `/arcade join sumo`, then bot A
 * walks into melee range and punches bot B until the server's knockback
 * pushes B off the platform into the void.
 *
 * Exercises the real server loop end to end:
 *   join -> countdown -> GO -> damage/knockback -> void death -> round win
 *   -> next round -> match win -> everyone restored to the hub.
 *
 * Server log lines are mirrored to stdout with a [srv] prefix so harness
 * output and server/logs/latest.log can be compared directly.
 *
 * Usage:  node scripts/verify/sumo-verify.js
 * (server must be running; `npm i mineflayer` somewhere on NODE_PATH)
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');
const fs = require('fs');
const path = require('path');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
// 1.20.4 is the newest protocol mineflayer can drive that ViaVersion on this
// box actually translates without kicking us for "invalid player movement"
// (1.21.4+ and 26.1 are dropped by the translation layer).
const VERSION = process.env.BOT_VERSION || '1.20.4';
const LOG = process.env.SERVER_LOG
  || path.join(__dirname, '..', '..', 'server', 'logs', 'latest.log');

let srvOffset = fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8').split('\n').length : 0;

/** Mirror interesting server log lines as they appear. */
function pumpServerLog() {
  if (!fs.existsSync(LOG)) return;
  const lines = fs.readFileSync(LOG, 'utf8').split('\n');
  for (let i = srvOffset; i < lines.length; i++) {
    const l = lines[i];
    if (l && (l.includes('[Arcade]') || l.includes('joined the game')
      || l.includes('left the game'))) {
      console.log(`[srv] ${l.replace(/^\[[^\]]+\] \[[^\]]+\]:\s*/, '')}`);
    }
  }
  srvOffset = lines.length;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (p) => (p ? `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}` : 'n/a');
// mineflayer reports the dimension TYPE, and both the hub and the void world
// are NORMAL, so position is the only reliable way to tell them apart.
const SUMO_CX = 4000;
const onArena = (bot) => Math.abs(bot.entity.position.x - SUMO_CX) < 200;
const onHub = (bot) => !onArena(bot);

function makeBot(username) {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username, version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  bot.on('error', (e) => console.log(`[${username}] ERROR ${e.message}`));
  bot.on('kicked', (r) => console.log(`[${username}] KICKED ${JSON.stringify(r).slice(0, 120)}`));
  bot.on('message', (m) => {
    const s = m.toString().trim();
    if (s) console.log(`[chat][${username}] ${s}`);
  });
  bot.once('spawn', () => {
    console.log(`[${username}] spawned at ${fmt(bot.entity.position)}`);
  });
  return new Promise((resolve, reject) => {
    bot.once('spawn', () => resolve(bot));
    setTimeout(() => reject(new Error(`${username} spawn timeout`)), 60000);
  });
}

/** Walk at the target until within `range` blocks, never leaving the platform. */
async function closeIn(bot, targetPos, range, maxMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    if (!bot.entity || !bot.entity.position) return false;
    const pos = bot.entity.position;
    const d = Math.hypot(pos.x - targetPos.x, pos.z - targetPos.z);
    if (d <= range) {
      bot.setControlState('forward', false);
      return true;
    }
    // The sumo platform is 7x7 centred on x=4000 (half-width 3). If A drifts
    // to the rim while chasing, walk back towards the centre first instead of
    // freezing there - otherwise it can never land another hit.
    const edge = Math.max(Math.abs(pos.x - SUMO_CX), Math.abs(pos.z));
    if (edge > 2.4) {
      bot.lookAt(new Vec3(SUMO_CX, pos.y, 0), true);
      bot.setControlState('forward', true);
      await sleep(120);
      continue;
    }
    // let mineflayer do the lookAt math (it knows the yaw convention); the
    // bot then walks forward along its own facing
    bot.lookAt(targetPos.offset(0, 1.4, 0), true);
    bot.setControlState('forward', true);
    await sleep(120);
  }
  bot.setControlState('forward', false);
  return false;
}

(async () => {
  console.log(`=== Sumo end-to-end verification (bot protocol ${VERSION}) ===`);
  const a = await makeBot('BotAlpha');
  const b = await makeBot('BotBeta');
  await sleep(1500);

  console.log('\n--- both bots run /arcade join sumo ---');
  a.chat('/arcade join sumo');
  await sleep(400);
  b.chat('/arcade join sumo');

  await sleep(3000);
  pumpServerLog();
  console.log(`A at ${fmt(a.entity.position)} | B at ${fmt(b.entity.position)}`);

  console.log('\n--- waiting out the countdown ---');
  for (let i = 0; i < 13; i++) {
    await sleep(1000);
    pumpServerLog();
  }

  console.log('\n--- A presses the attack; B is knocked into the void ---');
  const deadline = Date.now() + 90000;
  let swings = 0;
  let sawArena = false;
  let warnedNoTarget = false;
  while (Date.now() < deadline) {
    if (sawArena && onHub(a) && onHub(b)) {
      console.log('  both bots are back on the hub -> match over');
      break;
    }
    if (onArena(a)) sawArena = true;
    // Re-resolve the target every tick: after a void death and respawn the
    // bot's cached self-entity pointer can go stale, and an attack aimed at
    // the dead entity id is silently ignored by the server. Only prefer the
    // tracked entity when it actually has a position, else use the cached one.
    const tracked = a.players[b.username];
    const target = (tracked && tracked.position) ? tracked : b.entity;
    if (!target || !target.position) {
      if (!warnedNoTarget) {
        warnedNoTarget = true;
        console.log(`  no usable target yet (tracked=${!!tracked} cached=${!!b.entity})`);
      }
      await sleep(300);
      continue;
    }
    // Only close in when the target is actually in the arena with us.
    if (!onArena(b)) {
      a.setControlState('forward', false);
      await sleep(400);
      continue;
    }
    if (!(await closeIn(a, target.position, 2.0, 2500))) { await sleep(150); continue; }
    a.lookAt(target.position.offset(0, 1.4, 0), true);
    await sleep(120);
    try { a.attack(target); swings++; } catch (_) { /* out of reach */ }
    if (swings % 15 === 1) {
      // Round resets teleport both bots mid-match; the client's position can
      // desync from the server's copy and then the server silently rejects
      // attacks as out of reach. A jump pulse forces a fresh movement packet.
      a.setControlState('jump', true);
      b.setControlState('jump', true);
      await sleep(150);
      a.setControlState('jump', false);
      b.setControlState('jump', false);
      await sleep(60);
    }
    if (swings % 3 === 0) {
      pumpServerLog();
      console.log(`  swing #${swings} A=${fmt(a.entity && a.entity.position)}`
        + ` [hp=${a.health !== undefined ? Math.round(a.health) : '?'} gm=${a.game.gameMode}]`
        + ` B=${fmt(b.entity && b.entity.position)}`
        + ` [hp=${b.health !== undefined ? Math.round(b.health) : '?'} gm=${b.game.gameMode}]`);
    }
    await sleep(250);
  }

  pumpServerLog();
  console.log(`\ntotal swings: ${swings}`);
  console.log(`A final: ${fmt(a.entity && a.entity.position)} dim=${a.game.dimension}`);
  console.log(`B final: ${fmt(b.entity && b.entity.position)} dim=${b.game.dimension}`);

  await sleep(2000);
  a.quit(); b.quit();
  await sleep(1500);
  process.exit(0);
})().catch((e) => { console.log('HARNESS FAILED: ' + e.message); process.exit(1); });
