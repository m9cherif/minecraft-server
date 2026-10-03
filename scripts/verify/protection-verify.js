/**
 * Protection + colour verification harness.
 *
 * Covers the three server-rule changes:
 *   1. only operators may destroy blocks (any world);
 *   2. only operators may hit players in the hub;
 *   3. '&'-coded messages must reach players as real colours, not literal
 *      "&6&lGO!" text.
 *
 * Each bot builds its own throwaway dirt block on the hub floor so nothing
 * hand-made is destroyed: if the break is blocked the dirt survives, if it is
 * allowed the block turns to air. OpTester is opped from the console
 * (`op OpTester`) before this runs and deopped afterwards.
 *
 * Usage:  node scripts/verify/protection-verify.js
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.BOT_VERSION || '1.20.4';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Sends a console command through the local dashboard API. */
function console_(command) {
  return new Promise((resolve) => {
    const body = JSON.stringify({ command });
    const req = require('http').request({
      host: '127.0.0.1', port: 3000, path: '/command', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, (res) => { res.resume(); res.on('end', resolve); });
    req.on('error', () => resolve());
    req.end(body);
  });
}
const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`);
}

function makeBot(username, sink, jsonSink) {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username, version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  bot.on('error', (e) => console.log(`[${username}] ERROR ${e.message}`));
  bot.on('message', (m) => {
    const s = m.toString().trim();
    if (s) sink.push(s);
    if (jsonSink) jsonSink.push(JSON.stringify(m.json || {}));
  });
  return new Promise((resolve, reject) => {
    bot.once('spawn', () => resolve(bot));
    setTimeout(() => reject(new Error(`${username} spawn timeout`)), 60000);
  });
}

/**
 * Finds an air block near the bot that has solid ground under it, so the test
 * dirt can actually be placed (a parkour course has no floor next to you).
 */
function findSpotNear(bot) {
  const p = bot.entity.position;
  const bx = Math.floor(p.x);
  const by = Math.floor(p.y);
  const bz = Math.floor(p.z);
  for (let r = 1; r <= 2; r++) {
    for (let dx = -r; dx <= r; dx++) {
      for (let dz = -r; dz <= r; dz++) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
        const here = bot.blockAt(new Vec3(bx + dx, by, bz + dz));
        const under = bot.blockAt(new Vec3(bx + dx, by - 1, bz + dz));
        if (here && here.name === 'air' && under && under.name !== 'air'
          && under.name !== 'cave_air' && under.boundingBox === 'block') {
          return new Vec3(bx + dx, by, bz + dz);
        }
      }
    }
  }
  return null;
}

const blockName = (bot, v) => {
  const b = bot.blockAt(v);
  return b ? b.name : 'unknown';
};

(async () => {
  console.log(`=== protection + colour verification (protocol ${VERSION}) ===`);
  const plainChat = [];
  const plainJson = [];
  const plain = await makeBot('PlainTester', plainChat, plainJson);
  const opChat = [];
  const op = await makeBot('OpTester', opChat);
  const targetChat = [];
  const target = await makeBot('PlainTarget', targetChat);
  await sleep(1500);
  // Op/deop here rather than relying on the operator list on disk, so the
  // "op is allowed" half of each rule is actually exercised every run.
  await console_('op OpTester');
  await sleep(2000);
  // the op bot needs a block to place (compass in hand is not placeable)
  await console_('give OpTester dirt 1');
  await sleep(1500);
  const dirt = op.inventory.items().find((i) => i.name === 'dirt');
  if (dirt) {
    await op.equip(dirt, 'hand');
  } else {
    console.log('WARN: op bot never received dirt: '
      + op.inventory.items().map((i) => i.name).join(','));
  }

  // ---- 1. colours ---------------------------------------------------------
  plainChat.length = 0;
  plainJson.length = 0;
  plain.chat('/arcade list');
  await sleep(2000);
  const codes = plainChat.join('\n');
  const wire = plainJson.join('\n');
  const rawAmp = /&[0-9a-fk-or]/i.exec(codes);
  // prismarine's toString() drops formatting, so the colours have to be
  // checked on the wire payload the server actually sent.
  const coloured = /§[0-9a-fk-or]/i.test(wire) || /"color"/.test(wire);
  console.log('  /arcade list reply: ' + JSON.stringify(plainChat.slice(0, 2)));
  console.log('  wire sample: ' + wire.slice(0, 220));
  check('/arcade list actually replied', plainChat.length > 0, `${plainChat.length} lines`);
  check('messages contain no literal & colour codes', !rawAmp,
    rawAmp ? `saw "${rawAmp[0]}"` : '');
  check('server sent real colour data (not raw & text)', coloured,
    coloured ? '' : 'no section sign or colour field on the wire');

  // ---- 2. block breaking ---------------------------------------------------
  // Done in the hub, where both bots already stand: an arena teleport
  // desyncs the bot's client position from the server's, after which the
  // server rejects placements and attacks as out of reach. The op bot places
  // one throwaway dirt block, the plain bot digs at it, the op bot clears it.
  const alive = (b) => !!(b && b.entity && b.entity.position);
  await console_('give OpTester dirt 1');
  await sleep(1500);
  const dirt2 = op.inventory.items().find((i) => i.name === 'dirt');
  if (dirt2) {
    await op.equip(dirt2, 'hand');
  } else {
    console.log('  op inventory: ' + op.inventory.items().map((i) => i.name).join(','));
  }

  const at = findSpotNear(op);
  if (!at) throw new Error('no placeable spot with solid ground near the op bot');
  console.log(`  test dirt spot: ${at.x},${at.y},${at.z}`);
  op.lookAt(new Vec3(at.x + 0.5, at.y, at.z + 0.5), true);
  await sleep(600);
  await op.placeBlock(op.blockAt(new Vec3(at.x, at.y - 1, at.z)), new Vec3(0, 1, 0));
  await sleep(800);
  check('op bot placed the test dirt', blockName(op, at) === 'dirt',
    `block is ${blockName(op, at)} at ${at.x},${at.y},${at.z}`);

  plainChat.length = 0;
  // ground truth comes from the OP bot's chunk cache: the plain bot's own
  // cache does not reliably receive the block change packet.
  plain.lookAt(new Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5), true);
  await sleep(600);
  await plain.dig(plain.blockAt(at));
  await sleep(1500);
  check('non-op CANNOT destroy blocks', blockName(op, at) === 'dirt',
    `block after plain dig: ${blockName(op, at)}`);
  check('non-op is told why', plainChat.some((l) => /operators can break/i.test(l)),
    plainChat.join(' | '));

  opChat.length = 0;
  op.lookAt(new Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5), true);
  await sleep(500);
  await op.dig(op.blockAt(at));
  await sleep(1500);
  check('op CAN destroy blocks', blockName(op, at) === 'air',
    `block after op dig: ${blockName(op, at)}`);

  // ---- 3. hub PvP ---------------------------------------------------------
  const hpBefore = target.health;
  plain.lookAt(new Vec3(target.entity.position.x,
    target.entity.position.y + 1.4, target.entity.position.z), true);
  await sleep(300);
  for (let i = 0; i < 6; i++) {
    plain.lookAt(new Vec3(target.entity.position.x,
      target.entity.position.y + 1.4, target.entity.position.z), true);
    try { plain.attack(target.entity); } catch (_) { /* out of reach */ }
    await sleep(350);
  }
  await sleep(800);
  const hpAfter = target.health;
  check('non-op CANNOT hit players in the hub',
    hpBefore - hpAfter < 1, `target hp ${hpBefore.toFixed(1)} -> ${hpAfter.toFixed(1)}`);
  check('non-op attacker is told why',
    plainChat.some((l) => /operators can hit/i.test(l)),
    plainChat.filter((l) => /operators/i.test(l)).join(' | ') || 'no rule message seen');

  const hpBeforeOp = target.health;
  op.lookAt(new Vec3(target.entity.position.x,
    target.entity.position.y + 1.4, target.entity.position.z), true);
  await sleep(300);
  for (let i = 0; i < 6; i++) {
    op.lookAt(new Vec3(target.entity.position.x,
      target.entity.position.y + 1.4, target.entity.position.z), true);
    try { op.attack(target.entity); } catch (_) { /* out of reach */ }
    await sleep(350);
  }
  await sleep(800);
  check('op CAN hit players in the hub', target.health < hpBeforeOp - 0.05,
    `target hp ${hpBeforeOp.toFixed(1)} -> ${target.health.toFixed(1)}`);

  // ---- 4. colours inside a live match --------------------------------------
  // Last step on purpose: joining teleports the bot, and the client can stay
  // out of sync with the server afterwards (which breaks placement/attacks).
  plainChat.length = 0;
  plainJson.length = 0;
  plain.chat('/arcade join parkour');
  await sleep(13000);
  const inGameText = plainChat.join('\n');
  const inGameWire = plainJson.join('\n');
  check('match started (GO! seen)', /GO!/i.test(inGameText),
    inGameText.split('\n').slice(-2).join(' / ') || 'no match lines');
  const rawInGame = /&[0-9a-fk-or]/i.exec(inGameText);
  check('in-match messages have no literal & codes', !rawInGame,
    rawInGame ? `saw "${rawInGame[0]}"` : '');
  const inGameColoured = /§[0-9a-fk-or]/i.test(inGameWire) || /"color"/.test(inGameWire);
  check('in-match messages carry real colours', inGameColoured,
    inGameColoured ? '' : 'no colour data on the wire for match messages');
  plain.chat('/arcade leave');
  await sleep(1500);

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  await console_('deop OpTester');
  await sleep(800);
  plain.quit(); op.quit(); target.quit();
  await sleep(1200);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.log('HARNESS FAILED: ' + e.message); process.exit(1); });