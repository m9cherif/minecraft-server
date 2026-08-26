# Running 24/7 and publishing to a domain

This directory holds what you need to keep the server up permanently on a machine
you control, and to put it behind your own domain name.

You need a host that stays on: a home machine that never sleeps, a VPS, or a
dedicated box. 16 GB RAM, Java 25, and a public IP (see
[No public IP](#no-public-ip) if you are behind CGNAT).

## 1. Install

```bash
sudo useradd --system --home /opt/minecraft-server --shell /usr/sbin/nologin minecraft
sudo git clone https://github.com/m9cherif/minecraft-server.git /opt/minecraft-server
sudo chown -R minecraft:minecraft /opt/minecraft-server

sudo -u minecraft /opt/minecraft-server/setup.sh
```

## 2. Enable the service

```bash
sudo cp /opt/minecraft-server/deploy/minecraft-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-server
```

`enable` starts it on boot; `Restart=always` brings it back from crashes and OOM
kills. That combination is what "24/7" actually means — a bare `./start.sh` in a
terminal dies with your SSH session and never returns after a reboot.

```bash
systemctl status minecraft-server      # is it up
journalctl -u minecraft-server -f      # live console output
sudo systemctl restart minecraft-server
sudo systemctl stop minecraft-server   # saves the world first
```

Stopping sends SIGTERM, which triggers Minecraft's shutdown hook: it saves chunks
and exits cleanly. The unit allows 300 s for that before forcing the issue, so a
big world is not killed mid-save.

To run server commands (`/op`, `/whitelist add`) without a console, enable RCON in
`server.properties` — **set a real password first** — and use a client like
`mcrcon`. Otherwise use `journalctl` for output only.

## 3. Firewall

```bash
sudo ufw allow 25565/tcp   # Minecraft
sudo ufw allow 24454/udp   # Simple Voice Chat
```

The voice port is **UDP** and is the single most common thing people forget. If
players connect but voice chat says it cannot connect, this is why.

## 4. Point your domain at it

Replace `example.com` with your domain and `203.0.113.10` with your server's
public IPv4 (`curl -4 https://api.ipify.org` on the server).

**A record** — the host itself:

| Type | Name | Value | TTL |
| --- | --- | --- | --- |
| A | `mc` | `203.0.113.10` | 300 |

Players can now join at `mc.example.com:25565`.

**SRV record** — lets them type just `example.com`, no port:

| Type | Name | Service | Proto | Priority | Weight | Port | Target |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SRV | `@` | `_minecraft` | `_tcp` | 0 | 5 | 25565 | `mc.example.com` |

In zone-file form:

```
mc              300  IN  A    203.0.113.10
_minecraft._tcp 300  IN  SRV  0 5 25565 mc.example.com.
```

Registrars differ in how they split that up — some want the full
`_minecraft._tcp` in the name field, others ask for service and protocol
separately. Both describe the same record.

Check it once DNS propagates:

```bash
dig +short mc.example.com
dig +short SRV _minecraft._tcp.example.com
```

**Voice chat needs no DNS record.** Simple Voice Chat tells the client which UDP
port to use during the handshake, so it follows the game connection automatically.
Just keep 24454/udp open on the same host. If your host's public address differs
from what the server sees (NAT, a proxy), set the reachable address in
`config/voicechat/voicechat-server.properties`.

If your IP is dynamic, either ask your ISP for a static one or run a dynamic-DNS
updater against the `mc` record — otherwise the domain breaks whenever the IP
changes.

## No public IP

Behind CGNAT or a router you do not control, no DNS record will reach you, because
there is nothing routable to point at. Two options:

- **A VPS.** The reliable answer. Point the A record at it and run the server
  there.
- **A tunnel** such as playit.gg or ngrok. These give you a hostname without port
  forwarding. Check the tunnel forwards **UDP** before relying on it, or voice
  chat will not work even though the game connects. With most tunnels you get
  their hostname, and your own domain can only CNAME to it — the SRV record above
  should then target the tunnel hostname.
- **Tailscale** works well if the server is only for friends: no ports exposed at
  all, but everyone has to be on your tailnet.

## Before you expose it

`online-mode=false` means anyone who knows the address can join as any username,
including an opped one. A domain makes the server easy to find. Turn on the
whitelist before publishing:

```
white-list=true
enforce-whitelist=true
```

then `/whitelist add <name>` per player.

## Backups

Nothing here backs up your world. For a server meant to run indefinitely, add a
cron job or systemd timer that archives `world/` somewhere off the machine — a
disk failure or a bad chunk otherwise ends the world permanently.
