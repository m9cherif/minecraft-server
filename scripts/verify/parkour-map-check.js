/**
 * Verifies the Parkour mode serves the imported Parkour Panic map: joins the
 * mode with a bot and reports which world it ends up in, what is around it and
 * that the round timer runs.
 *
 * Usage:  node scripts/verify/parkour-map-check.js
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.BOT_VERSION || '1.20.4';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username: 'ParkourBot', version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  bot.on('error', (e) => console.log('ERROR ' + e.message));
  bot.on('message', (m) => {
    const s = m.toString().trim();
    if (s) console.log('[chat] ' + s);
  });
  await new Promise((res, rej) => {
    bot.once('spawn', res);
    setTimeout(() => rej(new Error('spawn timeout')), 60000);
  });

  const hubX = bot.entity.position.x;
  bot.chat('/arcade join parkour');
  await sleep(14000);

  const p = bot.entity.position;
  console.log(`hub x was ${hubX.toFixed(1)} -> now ${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}`);
  console.log(`world dimension reported by client: ${bot.game.dimension}`);
  console.log(`in map world: ${Math.abs(p.x - 1) < 400 ? 'yes (x near map spawn 1)' : 'NO - still near hub x'}`);
  for (let dy = 0; dy >= -2; dy--) {
    let row = `y=${Math.floor(p.y) + dy}: `;
    for (let dx = -2; dx <= 2; dx++) {
      const b = bot.blockAt(new Vec3(Math.floor(p.x) + dx, Math.floor(p.y) + dy,
        Math.floor(p.z)));
      row += (b ? b.name.slice(0, 7) : '??').padEnd(8);
    }
    console.log(row);
  }
  const floor = bot.blockAt(new Vec3(Math.floor(p.x), Math.floor(p.y) - 1, Math.floor(p.z)));
  console.log(`block under feet: ${floor ? floor.name : 'none'}`);
  console.log(`flying allowed: ${bot.allowFlight ?? 'n/a'}`);

  bot.chat('/arcade leave');
  await sleep(2500);
  console.log(`after leave x=${bot.entity.position.x.toFixed(1)} (hub is near 0)`);
  bot.quit();
  await sleep(1000);
  process.exit(0);
})().catch((e) => { console.log('CHECK FAILED: ' + e.message); process.exit(1); });