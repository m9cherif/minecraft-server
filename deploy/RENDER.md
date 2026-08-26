# Running on Render (Docker + WebSocket tunnel)

Render is an HTTP hosting platform. It has no way to expose Minecraft's raw TCP
port, or a second UDP port for voice chat, to the public internet — that is a
platform limit, not a setting to enable.

This setup works around the TCP limit by tunneling Minecraft's traffic inside a
WebSocket connection, which Render does proxy (it is an HTTP upgrade). Every
player runs a small client that undoes the tunnel locally, so Minecraft itself
still just connects to `localhost`.

**Read this before using it — it is a real workaround, not a free lunch:**

- **No voice chat.** Simple Voice Chat needs its own UDP port. There is no
  tunnel for that here, so it is left out of this build entirely. Veinminer and
  Fabric API are included; both are TCP-only.
- **Every player must run a small extra program.** Not just you — everyone who
  joins. If that is a dealbreaker, use [deploy/BEGINNER.md](BEGINNER.md) on a
  normal VPS instead, which needs nothing extra on the player's side.
- **Added latency.** Every packet now goes: player → wstunnel client → WebSocket
  → Render → wstunnel server → Minecraft, and back. Expect noticeably worse ping
  than a direct connection.
- **The free plan will not stay up.** Render's free web services sleep after 15
  minutes with no traffic and cold-start on the next connection. For this to be
  actually always-on, use at least the **Starter** plan.
- **The world does not survive a redeploy or restart unless you attach a Disk**
  (a paid, persistent volume). Without one, the container's filesystem — world
  included — is wiped on every deploy and every restart.

Once a paid plan and a Disk are in the picture, the honest comparison is a
$5-10/month VPS running this repo directly: no tunnel, no per-player client, no
added latency, and voice chat works. This Render path exists because it was
asked for, not because it is the better option — see
[deploy/README.md](README.md) for that comparison.

## What's already done

A Render web service has been created in your account:

- **Dashboard:** https://dashboard.render.com/web/srv-da7jf5ifngtc73fnc6fg
- **Name:** `mc-tcp-tunnel-probe` (rename it in the dashboard if you like —
  Settings → Name)
- **Plan:** currently `free` — see [Step 1](#step-1-set-the-plan) to change it
- Connected to this repo's `claude/repo-cleanup-8ilbjw` branch, auto-deploying
  on every push

## Step 1 — Set the plan

The free plan sleeps after 15 minutes idle, which defeats "24/7." In the
dashboard: **Settings → Instance Type → Starter** (or higher).

## Step 2 — Add a persistent Disk (optional, but the world depends on it)

**Settings → Disks → Add Disk.** Mount path `/server/world`, at least 2 GB
(worlds grow). Without this, every redeploy or restart throws the world away.

## Step 3 — Set the tunnel secret

**Environment → Add Environment Variable:**

| Key | Value |
| --- | --- |
| `WSTUNNEL_SECRET` | any long random string — this is effectively your server's front-door password |

The container will not start without this set — that is deliberate, so the
tunnel never comes up unsecured by accident.

## Step 4 — Deploy

Push to `claude/repo-cleanup-8ilbjw` (already done if you're reading this after
that push) or click **Manual Deploy → Deploy latest commit**. Watch progress in
the dashboard's **Logs** tab. First build downloads Fabric, Fabric API and
wstunnel, then boots Minecraft — expect several minutes.

## Step 5 — Install the client (every player does this)

Each player downloads `wstunnel` for their OS from
https://github.com/erebe/wstunnel/releases, then runs:

```bash
wstunnel client -L 'tcp://25565:127.0.0.1:25565' \
  --http-upgrade-path-prefix YOUR_WSTUNNEL_SECRET \
  wss://mc-tcp-tunnel-probe.onrender.com
```

Replace `YOUR_WSTUNNEL_SECRET` with the value from Step 3, and the hostname with
your actual `.onrender.com` URL. Leave this running, then in Minecraft:
**Multiplayer → Add Server → `localhost:25565`.**

This command is the same for everyone — put it somewhere your players can copy
it from (a pinned Discord message, for example) along with the secret.

## Step 6 — Lock it down

Same as every other deployment in this repo: `online-mode=false` means anyone
who reaches the server can join as any username, including an opped one. The
`WSTUNNEL_SECRET` keeps random internet scanners out, but anyone you give it to
can connect — so still turn on the whitelist in `server.properties`
(`white-list=true`, `enforce-whitelist=true`) before sharing the secret widely.

## Troubleshooting

**Build fails in the dashboard Logs tab.** Read the actual error — it is either
a Fabric/Modrinth API hiccup (retry the deploy) or a real problem worth pasting
back for help fixing.

**Container starts then immediately exits.** Almost always `WSTUNNEL_SECRET`
not set (Step 3) — the entrypoint refuses to start without it, on purpose.

**Client connects but Minecraft can't reach `localhost:25565`.** Something else
is already using port 25565 on the player's machine — change the `-L` mapping
on both sides, e.g. `-L 'tcp://25566:127.0.0.1:25565'` and join
`localhost:25566` instead.

**High ping / rubber-banding.** Expected to some degree — see the latency note
above. If it's unplayable, that's the tunnel's real cost, not a bug to fix.
