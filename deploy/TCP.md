# Making TCP work

Minecraft needs one thing from the network: a TCP port that players can open a
connection to. Everything else in this repo is downstream of that. This page is
the full list of ways to get it, ranked by how well they actually work — start
at the top and stop at the first one you can do.

Every option here gives **real TCP**, so players join with an ordinary Minecraft
client and type an address. The one exception is at the bottom, and it is last
for a reason.

## First: do you already have a public IP?

```bash
curl -s https://api.ipify.org; echo          # what the internet sees
ip -4 addr show scope global | grep inet     # what your machine has
```

If those two match, you have a public IP and **option 1** works. If the first is
a normal address and the second is `10.x`, `192.168.x` or `172.16–31.x`, you are
behind a router — still fine, forward the port. If the second is `100.64–127.x`,
you are behind **CGNAT**: your ISP shares that address with other customers, no
port forward is possible, and you need option 2 or 3.

## 1. Forward the port on your router

Free, no middleman, lowest latency. Nothing beats it when it is available.

Forward **TCP 25565** to the machine running the server, then open it locally:

```bash
sudo ufw allow 25565/tcp
```

Give the machine a static local IP (or a DHCP reservation) first, or the forward
breaks the next time it reboots and gets a different one.

Verify from **outside** your network — a phone on mobile data, not Wi-Fi:

```bash
nc -vz your-public-ip 25565
```

`succeeded` means done: players connect to that IP. Testing from inside your own
LAN proves nothing, because the traffic never leaves the router.

## 2. playit.gg

Free, and the answer when you cannot forward a port. An agent runs on the server
and gives you a public hostname; players type it into Minecraft and join.
Vanilla clients, nothing installed on their side.

```bash
curl -fSL https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-amd64 \
  -o playit && chmod +x playit && ./playit
```

It prints a URL to claim the agent in a browser. Add a **TCP tunnel** to
`127.0.0.1:25565`, and it hands you an address like `example.joinmc.link`.

Run it as a service so it survives reboots — the same shape as the units in this
directory. It also forwards UDP, which matters if you ever add a mod that needs
it.

The cost: your traffic passes through their network, latency goes up by the
detour, and a free account's hostname is theirs, not yours.

## 3. SSH reverse tunnel to a VPS you control

The self-hosted version of option 2: rent the cheapest VPS you can find (a few
dollars a month, 512 MB is plenty — it forwards packets, it does not run the
game), and SSH opens a real listening port on it that carries traffic back to
your server.

[`tcp-tunnel.sh`](tcp-tunnel.sh) does this and reconnects when the link drops:

```bash
RELAY=minecraft@your-vps.example.com ./deploy/tcp-tunnel.sh
```

Three things on the relay first, once:

1. The server's SSH key in the relay's `~/.ssh/authorized_keys`.
2. `GatewayPorts clientspecified` in `/etc/ssh/sshd_config`, then
   `sudo systemctl restart ssh`. **This is the step everyone misses.** Without
   it SSH binds the forwarded port to the relay's loopback and nothing from
   outside can reach it — the tunnel looks up and nobody can join.
3. `sudo ufw allow 25565/tcp` on the relay, plus the same rule in its cloud
   provider's security group.

Then players connect to the relay's address. Install it permanently with
[`minecraft-tunnel.service`](minecraft-tunnel.service):

```bash
sudo cp deploy/minecraft-tunnel.service /etc/systemd/system/
sudo systemctl edit --full minecraft-tunnel    # set RELAY= to your VPS
sudo systemctl enable --now minecraft-tunnel
```

No third-party account, no traffic through someone else's business, and the
address is yours. The cost is the VPS and the round trip through it.

## 4. Just run the server on the VPS

If you are renting a machine anyway, run the game on it and skip the tunnel
entirely — one hop, no relay, nothing to reconnect. With the 2 GB tuning in this
repo, a 4 GB VPS is enough for a small group.

Follow [README.md](README.md) in this directory: install Java 25, run
`setup.sh`, enable `minecraft-server.service`, open 25565/TCP.

This is what Hostinger's VPS plans are for, and it is the right answer if you
are choosing hosting now. Their shared **web hosting** cannot do it — see
[HOSTINGER.md](HOSTINGER.md).

## 5. ngrok

Works, and takes one command:

```bash
ngrok tcp 25565
```

Free accounts get a random hostname and port that **change every restart**, so
you re-tell your players the address each time, and there are session limits.
Fine for testing an afternoon, painful as a permanent setup. A paid plan gets
you a fixed address, at which point option 3 is cheaper.

## 6. Tailscale or ZeroTier

Puts the server and the players on one private network. No port is exposed to
the internet at all, which makes it the most secure option here — and it is a
real fix for a cracked server, since nobody who is not on your tailnet can even
reach the port.

```bash
curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
```

Players install Tailscale, you invite them, and they connect to the server's
`100.x` address. The catch is exactly that: **every player installs something
and joins your network**. Good for a fixed group of friends, unworkable for a
public server.

## 7. What does *not* work

**Cloudflare Tunnel.** It carries HTTP well, but Minecraft is not HTTP.
`cloudflared access tcp` can carry TCP — with a copy of `cloudflared` running on
every player's machine, which is the thing we are trying to avoid. Cloudflare's
real TCP proxy (Spectrum) is an Enterprise product. So: not a solution here.

**Shared web hosting, of any provider.** No raw TCP port, no second port, no
JVM. See [HOSTINGER.md](HOSTINGER.md) for the details.

**Port 80 or 443 tricks.** Minecraft can run on port 443 — `server-port=443`
and players type `host:443` — but that only helps if you can already open a
port. It does not defeat CGNAT and it does not turn HTTP hosting into TCP
hosting.

## 8. Last resort: the WebSocket tunnel in `web/`

When the only thing you have is HTTP hosting, [`web/`](../web/) can carry the
game's TCP inside a WebSocket. It works, but every player must run a client
program, so use it only when nothing above is possible. Setup:
[HOSTINGER.md](HOSTINGER.md#b-adding-the-tunnel).

## Hiding the port with DNS

Once players can connect, an SRV record lets them type `play.example.com`
instead of `play.example.com:25577`:

| Type | Name | Value |
| --- | --- | --- |
| A | `play` | the public IP |
| SRV | `_minecraft._tcp.play` | `0 5 25577 play.example.com.` |

The A record must point at the same host the SRV names. Full walkthrough in
[README.md](README.md).

## Before you open a port

`online-mode=false` in this repo means anyone who reaches the port can join
under any username, including an opped one. Whichever option above you pick,
turn on the whitelist before telling people the address:

```
white-list=true
enforce-whitelist=true
```

then `/whitelist add <name>` per player.
