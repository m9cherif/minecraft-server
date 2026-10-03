/**
 * Headless PvP verification harness (cross-check for the shared death hook).
 *
 * Same setup as the Sumo harness: two real protocol bots join `/arcade join
 * pvp`, then bot A chases and kills bot B repeatedly. PvP is scored by KILLS,
 * so this exercises the same GameMode death hook that Sumo uses, and shows
 * the score and killstreak announcements actually increment.
 *
 * PvP needs 15 kills to win, which is far too slow for a test, so we verify
 * the scoring path itself (kill counter + killstreak) and then stop the round
 * with the admin command.
 *
 * Usage:  node scripts/verify/pvp-verify.js
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');
const fs = require('fs');
const path = require('path');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.BOT_VERSION || '1.20.4';
const LOG = process.env.SERVER_LOG
  || path.join(__dirname, '..', '..', 'server', 'logs', 'latest.log');
// PvP's originX in GameMode.java; its corner spawns sit at +/-24 from this.
const PVP_CX = 1000;

let srvOffset = fs.existsSync(LOG) ? fs.readFileSync(LOG, 'utf8').split('\n').length : 0;
// Set as soon as the server logs a declared PvP winner, so the run can stop early.
let winnerSeen = false;

function pumpServerLog() {
  if (!fs.existsSync(LOG)) return;
  const lines = fs.readFileSync(LOG, 'utf8').split('\n');
  for (let i = srvOffset; i < lines.length; i++) {
    const l = lines[i];
    if (l && (l.includes('[Arcade]') || l.includes('joined the game')
      || l.includes('left the game') || l.includes('slain by')
      || l.includes('died'))) {
      console.log(`[srv] ${l.replace(/^\[[^\]]+\] \[[^\]]+\]:\s*/, '')}`);
      if (l.includes('[pvp]') && /wins/i.test(l)) winnerSeen = true;
    }
  }
  srvOffset = lines.length;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (p) => (p ? `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}` : 'n/a');
const onArena = (bot) => Math.abs(bot.entity.position.x - PVP_CX) < 100;
const onHub = (bot) => !onArena(bot);

function makeBot(username) {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username, version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  bot.on('error', (e) => console.log(`[${username}] ERROR ${e.message}`));
  bot.on('kicked', (r) => console.log(`[${username}] KICKED ${JSON.stringify(r).slice(0, 100)}`));
  bot.on('message', (m) => {
    const s = m.toString().trim();
    if (s) console.log(`[chat][${username}] ${s}`);
  });
  return new Promise((resolve, reject) => {
    bot.once('spawn', () => {
      console.log(`[${username}] spawned at ${fmt(bot.entity.position)}`);
      resolve(bot);
    });
    setTimeout(() => reject(new Error(`${username} spawn timeout`)), 60000);
  });
}

async function closeIn(bot, targetPos, range, maxMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    if (!bot.entity || !bot.entity.position) return false;
    const pos = bot.entity.position;
    if (Math.hypot(pos.x - targetPos.x, pos.z - targetPos.z) <= range) {
      bot.setControlState('forward', false);
      bot.setControlState('jump', false);
      return true;
    }
    bot.lookAt(new Vec3(targetPos.x, targetPos.y + 1.4, targetPos.z), true);
    bot.setControlState('forward', true);
    bot.setControlState('sprint', true);
    // hold jump: the corner spawn platforms are 1 block up and mineflayer
    // never steps up on its own — without this the chaser wedges against
    // the ledge just out of striking distance.
    bot.setControlState('jump', true);
    await sleep(120);
  }
  bot.setControlState('forward', false);
  bot.setControlState('jump', false);
  return false;
}

(async () => {
  console.log(`=== PvP scoring verification (bot protocol ${VERSION}) ===`);
  const a = await makeBot('FighterA');
  const b = await makeBot('FighterB');
  await sleep(1500);

  a.chat('/arcade join pvp');
  await sleep(400);
  b.chat('/arcade join pvp');
  await sleep(3000);
  pumpServerLog();
  console.log(`A at ${fmt(a.entity.position)} | B at ${fmt(b.entity.position)}`);

  console.log('\n--- waiting out the countdown ---');
  for (let i = 0; i < 11; i++) {
    await sleep(1000);
    pumpServerLog();
  }

  console.log('\n--- A hunts B; each kill should raise A\'s score ---');
  const deadline = Date.now() + (parseInt(process.env.KILL_MS, 10) || 135000);
  let swings = 0;
  while (Date.now() < deadline) {
    if (winnerSeen) { console.log('  WINNER DECLARED — stopping early'); break; }
    if (onHub(a) && onHub(b)) { console.log('  match already finished'); break; }
    const target = b.entity;
    if (!target || !target.position || !onArena(b)) { await sleep(300); continue; }
    if (!(await closeIn(a, target.position, 2.0, 2500))) { await sleep(150); continue; }
    a.lookAt(new Vec3(target.position.x, target.position.y + 1.4, target.position.z), true);
    await sleep(120);
    try { a.attack(target); swings++; } catch (_) { /* out of reach */ }
    if (swings % 10 === 0) {
      pumpServerLog();
      console.log(`  swing #${swings} B hp=${b.health !== undefined ? Math.round(b.health) : '?'}`);
    }
    await sleep(250);
  }

  pumpServerLog();
  console.log(`\ntotal swings: ${swings}`);
  if (process.env.STOP === '1') {
    console.log('--- stopping the round as admin ---');
    a.chat('/arcade stop');
    await sleep(4000);
    pumpServerLog();
  } else {
    console.log('--- leaving the round running (kill-score path drives the winner) ---');
  }
  console.log(`A final: ${fmt(a.entity && a.entity.position)} | B final: ${fmt(b.entity && b.entity.position)}`);

  await sleep(1500);
  a.quit(); b.quit();
  await sleep(1200);
  process.exit(0);
})().catch((e) => { console.log('HARNESS FAILED: ' + e.message); process.exit(1); });
