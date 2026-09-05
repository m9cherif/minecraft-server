# minecraft-server

A modded **Minecraft 26.2 / Fabric** dedicated server, tuned for a **2 GB heap**
so it runs on a small VPS or a spare machine.

Ships with **Veinminer**, pulls **Fabric API** at setup time, and carries
**Meteor Client** for players to install on their own machines.

## Requirements

- Linux or macOS (Windows via WSL)
- **Java 25 or newer** — Minecraft 26.2 and Meteor both require it
- `curl` and `jq`
- **3 GB RAM** or more (2 GB of heap plus room for the JVM and page cache),
  and disk space that grows with the world

```bash
sudo apt update && sudo apt install -y openjdk-25-jre-headless curl jq
```

## Quick start

```bash
git clone https://github.com/m9cherif/minecraft-server.git
cd minecraft-server
./setup.sh     # downloads the Fabric server + Fabric API
./start.sh     # runs it
```

First start generates the world and takes a few minutes. Stop the server by
typing `stop` in the console.

## Ports

| Port | Protocol | What |
| --- | --- | --- |
| 25565 | TCP | Minecraft |

That one port is all the server needs, and getting it reachable from the
internet is the whole networking problem. If you cannot forward it — CGNAT, a
router you do not control, a host that only speaks HTTP — **[deploy/TCP.md](deploy/TCP.md)**
is the ranked list of every way to get real TCP working anyway, starting with
the free ones that need nothing installed on players' machines.

## Mods

| Mod | Where it runs | How it gets installed |
| --- | --- | --- |
| Veinminer 3.1.3 | server + client | Ships in `mods/` |
| Fabric API | server + client | `setup.sh` |
| Meteor Client 26.2-13 | **client only** | Players install it themselves — see [`client-mods/`](client-mods/) |

Meteor Client declares `"environment": "client"`, so Fabric Loader will not load
it on a dedicated server. It is kept in `client-mods/` for players to download
rather than in `mods/`, where it would do nothing.

### What players need

Everyone joining needs the Fabric loader for 26.2 plus **Fabric API** and
**Veinminer** on their client — Veinminer has a client half, and joining without
it means no vein mining. Meteor is optional and personal.

Add more server mods by dropping Fabric jars for 26.2 into `mods/` and
restarting. `setup.sh` leaves anything it did not install alone.

## Tuning

Both scripts read environment variables, so nothing needs editing to change:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HEAP` | `2G` | Java heap. See the note below. |
| `RESTART_ON_CRASH` | `true` | Restart automatically after a crash. |
| `MC_VERSION` | `26.2` | Minecraft version `setup.sh` resolves mods against. |
| `LOADER_VERSION` | newest stable | Pin the Fabric loader. |

```bash
HEAP=4G ./start.sh
```

**2G of heap wants about 3 GB of machine.** The heap is not the JVM's whole
footprint — metaspace, GC structures, thread stacks and network buffers all live
outside it, and the OS page cache is what keeps region-file reads fast. Giving
the heap everything the box has drives it into swap, which costs far more
performance than the extra heap buys. On a machine with exactly 2 GB, use
`HEAP=1400M`; on a 4 GB one, `HEAP=3G` is comfortable.

`start.sh` uses [Aikar's flags](https://docs.papermc.io/paper/aikar-flags), and
picks between their two shapes from the heap size you set: below 12 GB a smaller
young generation and 8M regions, above it the large-heap variant. Changing
`HEAP` therefore cannot leave the GC mistuned.

`server.properties` is commented throughout. The three settings that decide
whether 2 GB is enough are `view-distance` (8), `simulation-distance` (6) and
`max-players` (20) — raise any of them and raise `HEAP` with it.

## World and player limits

`max-players` is 20, which is what a 2 GB heap holds with room to spare — each
player loads their own chunks, so the ceiling is memory rather than a setting.
Raising it without raising `HEAP` turns "server is full" into
`OutOfMemoryError`, which is worse.

The world border is left at the engine maximum (29,999,984 blocks), so world
size is capped only by your disk — a heavily explored world grows to tens of GB.

## Security: this server is cracked

`online-mode=false` disables Mojang account verification, as requested. That
means **anyone who knows the address can join as any username**, including one
that is already opped. There is no password.

If the server is reachable from the internet, do at least one of:

- **Use the whitelist.** Set `white-list=true` and `enforce-whitelist=true` in
  `server.properties`, then `/whitelist add <name>` for each player. This is the
  simplest real protection and costs nothing.
- **Firewall it** to known IPs, or keep it on a LAN/VPN such as Tailscale.
- **Add an auth mod** so players register a password on first join.

Op accounts are the thing to guard: with offline mode and no whitelist, someone
joining as your username gets your permissions. Also note `enable-rcon` is off
and `rcon.password` is empty — set a real password before ever turning it on.

## Running 24/7 and using your own domain

`./start.sh` in a terminal dies with your SSH session and does not come back after
a reboot. For a permanent server, install the systemd unit in [`deploy/`](deploy/),
which starts on boot and restarts on crashes, then point a DNS A record plus an
SRV record at the host so players can join at your domain without typing a port.

Full instructions, including firewall rules and what to do without a public IP:
**[deploy/README.md](deploy/README.md)**. New to this? Follow
**[deploy/BEGINNER.md](deploy/BEGINNER.md)** instead, which assumes nothing.
For free 24/7 hosting, see **[deploy/FREE-ORACLE.md](deploy/FREE-ORACLE.md)**.

Cannot open port 25565? **[deploy/TCP.md](deploy/TCP.md)** lists every way to get
real TCP working — router forwarding, playit.gg, an SSH reverse tunnel through a
cheap VPS, ngrok, Tailscale — with what each costs and which ones need nothing
installed on players' machines. Two of them are scripted:

```bash
sudo ./deploy/playit-setup.sh                    # free, no port forwarding
RELAY=user@your-vps ./deploy/tcp-tunnel.sh       # your own relay over SSH
```

Either way, check the result before telling anyone the address:

```bash
node web/bin/mc-ping.js yourname.joinmc.link
```

Want to use Render specifically? Render only proxies HTTP(S), never raw TCP, so
this repo also ships a Docker build that tunnels Minecraft's TCP inside a
WebSocket to get through that limit. It needs a paid Render plan to stay up and
a client program every player must run. Full explanation and setup:
**[deploy/RENDER.md](deploy/RENDER.md)**.

Using Hostinger? Their **web hosting** plans cannot run the game — no Java, no
raw TCP port, nowhere near the RAM — but they run Node.js well, so this repo
also ships a small Node app in [`web/`](web/) that gives the server a status
page on your own domain, a JSON API, and an optional WebSocket tunnel for
players who cannot reach the game host directly. Which of those you want, and
what a Hostinger **VPS** changes: **[deploy/HOSTINGER.md](deploy/HOSTINGER.md)**.

## Status page

`web/` is a dependency-free Node.js app (Node 18+) that pings the server and
publishes what it finds:

```bash
cd web
cp .env.example .env    # set MC_HOST
npm start               # http://localhost:3000
```

It shows online/offline, player count and names, version, MOTD and latency, and
serves the same data at `/api/status`. It runs anywhere Node runs — shared
hosting, a VPS next to the game server, or your own machine. Details in
[`web/README.md`](web/README.md).

## Layout

```
setup.sh                        one-time install / re-run to update
start.sh                        launcher, picks JVM flags from HEAP
server.properties               server config, commented
mods/                           server-side mods
client-mods/                    mods players install themselves
deploy/                         systemd units, TCP options, host setup
web/                            Node.js status page, JSON API and tunnel
```

`setup.sh` is safe to re-run — it re-resolves the newest compatible builds and
replaces the copies it installed previously.
