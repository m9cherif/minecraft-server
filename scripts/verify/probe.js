/**
 * Probe: find mineflayer client options that survive the hub's join teleport.
 *
 * The hub's OnJoinSpawn plugin teleports every player 1 tick after join, and
 * mineflayer's own physics engine answers with a movement packet the server
 * rejects ("Invalid move player packet received" -> disconnect).
 */
const mineflayer = require('mineflayer');
const installMovementGuard = require('./fix-movement');

const VARIANTS = [
  { name: 'guarded', opts: {}, guard: true },
  { name: 'guarded-nophys', opts: { physicsEnabled: false }, guard: true },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function trial(variant) {
  return new Promise((resolve) => {
    let alive = true;
    const bot = mineflayer.createBot({
      host: '127.0.0.1', port: 25565,
      username: 'Probe' + variant.name.replace(/\W/g, ''),
      version: '26.1', auth: 'offline',
      ...variant.opts,
    });
    // Install BEFORE login: the hub teleport arrives during the login burst,
    // and the very first physics packet follows it within ~30ms.
    if (variant.guard) installMovementGuard(bot);
    bot.on('error', () => { alive = false; });
    bot.on('kicked', (r) => { alive = false; resolve({ ...variant, ok: false, why: 'kicked' }); });
    bot.once('spawn', async () => {
      await sleep(9000);
      const pos = bot.entity && bot.entity.position;
      resolve({
        ...variant,
        ok: alive,
        why: alive ? `survived 9s at ${pos ? `${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)}` : 'n/a'} dim=${bot.game.dimension}` : 'disconnected',
      });
      try { bot.quit(); } catch (_) {}
    });
    setTimeout(() => resolve({ ...variant, ok: alive, why: 'timeout' }), 30000);
  });
}

(async () => {
  for (const v of VARIANTS) {
    const r = await trial(v);
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}: ${r.why}`);
    await sleep(3000);
  }
  process.exit(0);
})();
