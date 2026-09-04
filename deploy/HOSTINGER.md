# Hostinger

This repo has two halves now:

- the **Fabric server** — Java, `setup.sh` / `start.sh`, wants a 2 GB heap and
  raw TCP port 25565;
- the **Node.js app** in [`web/`](../web/) — an HTTP service that shows the
  server's live status, exposes it as JSON, and can optionally tunnel the game
  through a WebSocket.

Only the second one runs on Hostinger **web hosting**. Read the next section
before doing anything else; it decides which of the three setups below you want.

## What web hosting can and cannot do

Hostinger's shared web hosting plans (Premium, Business, Cloud) run PHP and
Node.js applications behind a shared reverse proxy. Concretely:

| | Web hosting | Hostinger VPS |
| --- | --- | --- |
| Run a Node.js HTTP app | yes | yes |
| Listen on one port, chosen by the platform (`$PORT`) | yes | you choose |
| Accept raw TCP on port 25565 | **no** | yes |
| Java 25 runtime | **no** | yes, you install it |
| 2 GB of RAM for one process | **no** | yes |

So: **a Minecraft Java server cannot run on Hostinger web hosting.** Not with
Node, not with a tunnel, not with any configuration — there is no JVM, no raw
port, and the resource limits sit below even the 2 GB this repo is tuned for.
Hostinger sells VPS plans (and a dedicated game-hosting product) for exactly
that reason; a VPS is where the Java half belongs.

If what you actually need is a reachable TCP port and web hosting was just the
thing you happened to have, **[TCP.md](TCP.md)** is the better page: it lists
every way to get real TCP, several of them free, none of which need players to
install anything.

What web hosting *is* genuinely good for here is the Node app: a status page on
your own domain, with HTTPS and a certificate you do not have to manage.

## Which setup do you want?

**A. Status page only** — the common case. The game server runs wherever it
already runs (a VPS, a home machine with a port forward, the free Oracle box in
[FREE-ORACLE.md](FREE-ORACLE.md)). Hostinger runs the Node app, which pings it
and publishes the result. Nothing about how players join changes.

**B. Status page plus tunnel.** Same as A, but the Node app also forwards the
game's TCP through a WebSocket, so players connect to your Hostinger domain on
port 443 instead of to the game host directly. Useful when the game host has no
public IP, no open port, or an address you would rather not publish. The cost is
that every player runs a small client program — which is why
**[TCP.md](TCP.md)** is worth reading first; most people are better served by
playit.gg or an SSH reverse tunnel, which need nothing on the player's side.

**C. Everything on a Hostinger VPS.** Java server and Node app on one box, no
tunnel at all. This is the setup to pick if you are buying hosting now.

## A. Status page on web hosting

### 1. Get the files onto the host

In hPanel: **Websites → your site → Dashboard → Git**, add
`https://github.com/m9cherif/minecraft-server.git`, branch `main`, and deploy.
Or upload the `web/` directory over SFTP. There are no dependencies to install —
the app uses only Node's standard library — so a missing `npm install` cannot
break the deploy.

### 2. Create the Node.js application

**Advanced → Node.js** (on plans that have it), then:

| Field | Value |
| --- | --- |
| Node.js version | 18 or newer |
| Application root | the `web` directory of the checkout |
| Application URL | your domain, or a subdomain like `status.example.com` |
| Startup file | `app.js` |

### 3. Set the environment variables

In the same screen, add at least:

```
MC_HOST=203.0.113.10        # where the game server actually runs
MC_PORT=25565
MC_NAME=My Fabric Server
MC_ADDRESS=play.example.com # what players type into Minecraft
```

[`web/.env.example`](../web/.env.example) lists every variable with its default.
Do **not** set `PORT` — Hostinger assigns it and passes it in.

### 4. Start it, then check

Hit **Restart**, then open your domain. You should get the status card. If the
page loads but says *Offline*, the app is fine and the ping is not: check
`MC_HOST`/`MC_PORT`, and that port 25565 on the game host accepts connections
from outside its own network.

`/api/status` returns the same information as JSON, and `/healthz` is a liveness
check that answers even when the game server is down.

## B. Adding the tunnel

The tunnel exists for one situation: the game server is running, but players
cannot reach it directly. It carries Minecraft's TCP stream inside a WebSocket,
which is the one thing web hosting will proxy, and unwraps it on the other side.
This is the same technique as [RENDER.md](RENDER.md), written in Node so the
host needs no extra binary.

Understand the trade-offs first:

- **Every player runs a client program.** They cannot join with a plain
  Minecraft client — the address they type points at their own machine.
- **All traffic goes through your web host.** Latency goes up by the detour, and
  the bandwidth counts against whatever your plan allows.
- It does **not** let you host the game on web hosting. The Java server still
  runs somewhere else; the tunnel only changes the route players take to it.

### Server side

Generate a secret and add the tunnel variables:

```bash
node -e "console.log(require('crypto').randomBytes(24).toString('base64url'))"
```

```
TUNNEL_ENABLED=true
TUNNEL_SECRET=<the 32-character string that just printed>
TUNNEL_TARGET_HOST=203.0.113.10
TUNNEL_TARGET_PORT=25565
```

Restart the app. The secret is the first path segment of the tunnel URL, and it
is the only thing standing between a URL scanner and your endpoint — any other
path gets a plain `404`, the same as a mistyped page. The destination is fixed
by `TUNNEL_TARGET_HOST`, so the endpoint cannot be used to reach anything else.

Treat the secret like a password: share it only with your players, and rotate it
(new value, restart) if it leaks.

### Player side

Each player needs Node.js 18+ and the two files `web/bin/mc-tunnel-client.js`
and `web/src/ws.js`. Then:

```bash
node bin/mc-tunnel-client.js wss://play.example.com/YOUR_TUNNEL_SECRET
```

It prints `Minecraft -> 127.0.0.1:25565` and waits. In Minecraft, add a server
with the address **`127.0.0.1`** — the tunnel client is listening there and
forwards everything on. `--port 25566` moves it if something else already has
25565 (for instance a server they run locally).

Leave it running while playing; Ctrl-C stops it.

## C. Everything on a Hostinger VPS

A VPS is a normal Linux box, so the main [deploy/README.md](README.md) applies
unchanged — install Java 25, run `setup.sh`, install the systemd unit, open
25565/TCP in the firewall. The Node app is then a second, tiny service
alongside it:

```bash
sudo cp deploy/minecraft-web.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-web
```

Edit the unit first if your checkout is not in `/opt/minecraft-server` or you
want a port other than 3000. With both on one machine, `MC_HOST=127.0.0.1` is
correct and no tunnel is needed. Put nginx or Caddy in front for HTTPS on your
domain, proxying to `127.0.0.1:3000`.

## Running it locally

```bash
cd web
cp .env.example .env     # edit MC_HOST at minimum
npm start                # http://localhost:3000
npm test                 # 10 integration tests, no network needed
```

## Troubleshooting

**The page loads but always says Offline.** The app is running; the ping is
failing. Test the same thing from a shell:
`node -e "require('./web/src/ping').pingServer({host:'HOST',port:25565}).then(console.log)"`.
A refused connection means the port is closed or firewalled; a timeout usually
means the packets are not arriving at all.

**hPanel shows the app as running but the domain returns 503.** The app almost
always exited on startup. Check the application log — with `TUNNEL_ENABLED=true`
and a missing `TUNNEL_SECRET` or `TUNNEL_TARGET_HOST`, it deliberately refuses
to start and prints which one is missing.

**The tunnel client says "tunnel refused the connection: HTTP/1.1 404".** The
secret in the URL does not match `TUNNEL_SECRET`. That is the same answer any
wrong URL gets, by design.

**The tunnel client connects, but Minecraft times out.** The tunnel reached your
app and the app could not reach the game server — check `TUNNEL_TARGET_HOST` and
`TUNNEL_TARGET_PORT` from the web host's point of view, not yours.

**You would rather players did not need the client program.** Then the tunnel is
the wrong tool — see [TCP.md](TCP.md) for the options that give real TCP.
