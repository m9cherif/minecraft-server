#!/usr/bin/env node
'use strict';

// Check whether a Minecraft server is reachable, from wherever you run this.
//
// The point is verification: after setting up playit.gg, a port forward or a
// tunnel, run this against the address you hand to players. It speaks the real
// Server List Ping protocol, so a success here means a player would get in.
//
//   node bin/mc-ping.js yourname.joinmc.link
//   node bin/mc-ping.js 203.0.113.10:25577
//   node bin/mc-ping.js play.example.com --json
//
// Exit code 0 if the server answered, 1 if it did not — so it works in a
// health check or a cron job as well as by hand.

const { pingServer } = require('../src/ping');

const USAGE = `Usage: mc-ping <address[:port]> [--json] [--timeout 5000]

  <address[:port]>   the address players would type; port defaults to 25565
  --json             print the raw result instead of a summary
  --timeout          milliseconds to wait (default 5000)
`;

const args = process.argv.slice(2);
let target = null;
let asJson = false;
let timeoutMs = 5000;

for (let i = 0; i < args.length; i++) {
  const arg = args[i];
  if (arg === '--json') asJson = true;
  else if (arg === '--timeout') timeoutMs = Number.parseInt(args[++i], 10);
  else if (arg === '-h' || arg === '--help') {
    process.stdout.write(USAGE);
    process.exit(0);
  } else if (!target) target = arg;
  else {
    process.stderr.write(USAGE);
    process.exit(1);
  }
}

if (!target || !Number.isInteger(timeoutMs)) {
  process.stderr.write(USAGE);
  process.exit(1);
}

// Split host:port from the right, so IPv6 literals in brackets survive.
const match = /^\[(.+)\](?::(\d+))?$/.exec(target) || /^([^:]+)(?::(\d+))?$/.exec(target);
if (!match) {
  console.error(`Could not read an address out of "${target}".`);
  process.exit(1);
}
const host = match[1];
const port = match[2] ? Number.parseInt(match[2], 10) : 25565;

pingServer({ host, port, timeoutMs }).then((result) => {
  if (asJson) {
    console.log(JSON.stringify(result, null, 2));
    process.exit(result.online ? 0 : 1);
  }

  if (!result.online) {
    console.log(`OFFLINE  ${host}:${port}`);
    console.log(`         ${result.error}`);
    console.log('');
    console.log('The server did not answer. Either it is not running, or nothing');
    console.log('is carrying port ' + port + ' from here to it — see deploy/TCP.md.');
    process.exit(1);
  }

  const { online, max, sample } = result.players;
  console.log(`ONLINE   ${host}:${port}`);
  console.log(`         ${result.version}  ·  ${online}/${max} players  ·  ${result.latencyMs} ms`);
  if (result.motd) console.log(`         "${result.motd.replace(/\n/g, ' ')}"`);
  if (sample.length) console.log(`         online now: ${sample.join(', ')}`);
  process.exit(0);
});
