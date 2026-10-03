/** Quick probe: where does a PvP spawn land, and what block is at the feet? */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');
const Vec3 = require('vec3');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = parseInt(process.env.MC_PORT || '25565', 10);
const VERSION = process.env.BOT_VERSION || '1.20.4';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmt = (p) => `${p.x.toFixed(2)},${p.y.toFixed(2)},${p.z.toFixed(2)}`;

(async () => {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username: 'ProbePvp', version: VERSION, auth: 'offline',
  });
  installMovementGuard(bot);
  bot.on('kicked', (r) => console.log('KICKED ' + JSON.stringify(r).slice(0, 120)));
  await new Promise((res, rej) => {
    bot.once('spawn', res);
    setTimeout(() => rej(new Error('spawn timeout')), 60000);
  });
  console.log('spawned at ' + fmt(bot.entity.position));
  await sleep(1200);
  bot.chat('/arcade join pvp');
  // teleports happen immediately on session start
  for (let i = 0; i < 6; i++) {
    await sleep(700);
    const p = bot.entity.position;
    const feet = bot.blockAt(new Vec3(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)));
    const below = bot.blockAt(new Vec3(Math.floor(p.x), Math.floor(p.y) - 1, Math.floor(p.z)));
    const head = bot.blockAt(new Vec3(Math.floor(p.x), Math.floor(p.y) + 1, Math.floor(p.z)));
    console.log(`t${i}: pos=${fmt(p)} feetBlock=${feet && feet.name} below=${below && below.name} head=${head && head.name}`);
    // try to walk east for 400ms and see if we actually move
    bot.lookAt(new Vec3(p.x + 5, p.y + 1.4, p.z), true);
    bot.setControlState('forward', true);
    await sleep(400);
    bot.setControlState('forward', false);
    const p2 = bot.entity.position;
    console.log(`   after 400ms walk east: ${fmt(p2)} (dx=${(p2.x - p.x).toFixed(2)})`);
  }
  bot.quit();
  await sleep(1000);
  process.exit(0);
})().catch((e) => { console.log('PROBE FAILED: ' + e.message); process.exit(1); });
