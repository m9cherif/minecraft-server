# web/ — Node.js companion app

An HTTP service that sits beside the Fabric server:

- a **status page** on your own domain — online/offline, player count, version,
  MOTD, latency;
- **`GET /api/status`**, the same thing as JSON, for bots and dashboards;
- **`GET /healthz`**, a liveness check that answers even when the game is down;
- an optional **WebSocket tunnel** that carries Minecraft's TCP to players who
  cannot reach the game server directly.

It runs on Hostinger web hosting, on a VPS, or on your laptop. It does **not**
run Minecraft — see [../deploy/HOSTINGER.md](../deploy/HOSTINGER.md) for what
shared hosting can and cannot do.

## Run it

```bash
cd web
cp .env.example .env    # MC_HOST is the one you must set
npm start               # http://localhost:3000
```

Node 18 or newer. **No dependencies** — the status ping, the WebSocket framing
and the HTTP layer are all written against Node's standard library, so
`npm install` has nothing to do and cannot fail on a shared host.

```bash
npm test                # 10 integration tests; no network access needed
```

## Checking a server from the command line

`mc-ping` speaks the real Server List Ping protocol, so a success means a player
would get in. It is the way to check a port forward or a tunnel actually works:

```bash
node bin/mc-ping.js yourname.joinmc.link
node bin/mc-ping.js 203.0.113.10:25577 --json
```

It exits 0 when the server answered and 1 when it did not, so it drops into a
health check or a cron job unchanged.

## Configuration

Everything is environment variables, because that is all hPanel gives you.
A `.env` file next to `app.js` is read at startup; real environment variables
take precedence. [`.env.example`](.env.example) documents every one.

The essentials:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | Set by the host. Leave it alone on Hostinger. |
| `MC_HOST` | `127.0.0.1` | The Minecraft server to query. |
| `MC_PORT` | `25565` | Its port. |
| `MC_ADDRESS` | `MC_HOST[:port]` | What the page tells players to type. |
| `MC_NAME` | `Minecraft` | Page heading. |
| `TUNNEL_ENABLED` | `false` | Turn the tunnel on. |
| `TUNNEL_SECRET` | — | Required with the tunnel; 16+ URL-safe characters. |
| `TUNNEL_TARGET_HOST` | — | Required with the tunnel; the only host it may reach. |

With `TUNNEL_ENABLED=true` and a missing secret or target, the app refuses to
start and says which one is missing — a misconfigured tunnel should not come up
half-working.

## The tunnel

Web hosts proxy HTTP and WebSocket on one port and nothing else, while Minecraft
speaks raw TCP. The tunnel wraps that TCP stream in WebSocket frames so it fits
through:

```
Minecraft client                                            Fabric server
      |                                                            ^
      v                                                            |
127.0.0.1:25565  ==>  bin/mc-tunnel-client.js  ==(wss:443)==>  src/tunnel.js
```

Players run the client half:

```bash
node bin/mc-tunnel-client.js wss://play.example.com/YOUR_TUNNEL_SECRET
```

...then join at `127.0.0.1` in Minecraft. Full setup, and the cost — every
player needs the client — is in
[../deploy/HOSTINGER.md](../deploy/HOSTINGER.md#b-adding-the-tunnel).
[../deploy/TCP.md](../deploy/TCP.md) covers the alternatives that need no
client at all, which is what most people want.

Two limits keep the public endpoint from being an open proxy: the destination is
fixed by configuration and never comes from the client, and the URL must carry
the secret or the upgrade gets a plain `404`.

## Layout

```
app.js                    entry point — the file Hostinger starts
src/config.js             environment + .env parsing, validated at startup
src/server.js             routes, security headers
src/page.js               the status page (one self-contained document)
src/status.js             ping cache + single-flight
src/ping.js               Minecraft Server List Ping over TCP
src/ws.js                 RFC 6455 framing, shared by both tunnel halves
src/tunnel.js             WebSocket -> TCP relay (server half)
bin/mc-tunnel-client.js   TCP -> WebSocket relay (player half)
bin/mc-ping.js            check any server address from a terminal
test/                     integration tests + a fake Minecraft server
```
