/** Diagnostic: log every movement packet the bot sends and receives. */
const mineflayer = require('mineflayer');

const bot = mineflayer.createBot({
  host: '127.0.0.1', port: 25565, username: 'TraceBot',
  version: '26.1', auth: 'offline',
});

const t0 = Date.now();
const ms = () => String(Date.now() - t0).padStart(5);

bot._client.on('packet', (data, meta) => {
  if (data.name === 'position' || data.name === 'position_look') {
    console.log(`${ms()}ms  <- RECV ${data.name}  teleportFlag=${data.teleport}`);
  }
});

const orig = bot._client.write.bind(bot._client);
bot._client.write = function (name, data) {
  if (name === 'position' || name === 'position_look') {
    console.log(`${ms()}ms  -> SEND ${name}  x=${data.x?.toFixed?.(2)} y=${data.y?.toFixed?.(2)} z=${data.z?.toFixed?.(2)} onGround=${data.onGround}`);
  }
  return orig(name, data);
};

bot.on('kicked', (r) => console.log(`${ms()}ms  KICKED: ${JSON.stringify(r).slice(0, 200)}`));
bot.on('end', () => console.log(`${ms()}ms  END`));
bot.on('error', (e) => console.log(`${ms()}ms  ERROR ${e.message}`));
bot.once('spawn', () => console.log(`${ms()}ms  SPAWNED`));

setTimeout(() => { try { bot.quit(); } catch (_) {} process.exit(0); }, 12000);
