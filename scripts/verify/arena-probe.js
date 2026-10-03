/**
 * Read-only arena probe: parks a creative bot over an arcade arena and dumps
 * the blocks around it. Used to check that a stamped arena is really there
 * (spawn platforms, floors) without guessing coordinates.
 *
 * Usage:  node scripts/verify/arena-probe.js [x] [y] [z]
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.BOT_VERSION || '1.20.4';
const X = parseInt(process.argv[2] || '4000', 10);
const Y = parseInt(process.argv[3] || '102', 10);
const Z = parseInt(process.argv[4] || '0', 10);

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

(async () => {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username: 'ArenaProbe', version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  await new Promise((res, rej) => {
    bot.once('spawn', res);
    setTimeout(() => rej(new Error('spawn timeout')), 60000);
  });
  // creative so the probe never takes fall damage while hovering
  await console_('gamemode creative ArenaProbe');
  await sleep(800);
  await console_(`tp ArenaProbe ${X} ${Y} ${Z}`);
  await sleep(2500);
  console.log(`probe at ${bot.entity.position.x.toFixed(1)},`
    + `${bot.entity.position.y.toFixed(1)},${bot.entity.position.z.toFixed(1)}`
    + ` (dimension ${bot.game.dimension})`);
  for (let dy = 1; dy >= -2; dy--) {
    let row = `y=${Y + dy}: `;
    for (let dx = -3; dx <= 3; dx++) {
      const b = bot.blockAt(new Vec3(X + dx, Y + dy, Z));
      row += (b ? b.name.slice(0, 6) : '??').padEnd(7);
    }
    console.log(row);
  }
  bot.quit();
  await sleep(800);
  process.exit(0);
})().catch((e) => { console.log('PROBE FAILED: ' + e.message); process.exit(1); });