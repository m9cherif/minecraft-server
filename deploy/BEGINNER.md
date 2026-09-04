# Step-by-step guide (no experience needed)

Goal: your Minecraft server running 24/7 at your own domain.

Everything below is copy-paste. Where you see `example.com` or `203.0.113.10`,
swap in your own domain and your server's IP.

Budget about an hour, and around €25-30/month for the server.

---

## Step 1 — Rent a server

Your own PC will not work well for this: it has to stay on permanently, and most
home internet connections block incoming players.

Go to [Hetzner Cloud](https://www.hetzner.com/cloud) (cheapest good option),
[Contabo](https://contabo.com/) or [OVH](https://www.ovhcloud.com/), make an
account, and create a server with:

- **RAM: 4 GB** — this is the important one. The server is tuned for a 2 GB
  heap, and the rest is for the JVM and the operating system
- **CPU: 4+ cores.** Minecraft mostly uses one core hard, so a faster core beats
  more cores
- **Disk: 40 GB+** — worlds grow over time
- **Image: Ubuntu 24.04**
- **Location:** whichever is closest to you and your players — this decides ping

On Hetzner the **CPX41** plan fits. When it is created you get an **IP address**
like `203.0.113.10` and either a root password by email or an SSH key you chose.

Write the IP down. You need it twice.

## Step 2 — Connect to it

On your own computer open a terminal:

- **Windows:** press Start, type `powershell`, open it
- **Mac:** press Cmd+Space, type `terminal`, open it

Then:

```bash
ssh root@203.0.113.10
```

Type `yes` if it asks about authenticity, then your password. Nothing appears
while you type the password — that is normal, keep typing and press Enter.

You are now controlling the server. Every command from here goes in this window.

## Step 3 — Install what the server needs

```bash
apt update && apt upgrade -y
apt install -y openjdk-25-jre-headless git curl jq ufw
```

Minecraft 26.2 needs **Java 25**. Older Java will not start it.

Check it worked:

```bash
java -version
```

You should see `openjdk version "25...`.

## Step 4 — Install the server

```bash
useradd --system --home /opt/minecraft-server --shell /usr/sbin/nologin minecraft
git clone https://github.com/m9cherif/minecraft-server.git /opt/minecraft-server
chown -R minecraft:minecraft /opt/minecraft-server
sudo -u minecraft /opt/minecraft-server/setup.sh
```

That downloads Fabric and Fabric API. Takes a minute or two. Veinminer is
already included.

## Step 5 — Make it run 24/7

```bash
cp /opt/minecraft-server/deploy/minecraft-server.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now minecraft-server
```

That is the 24/7 part. It starts the server now, starts it again automatically
if the machine reboots, and restarts it if it ever crashes.

Watch it start up:

```bash
journalctl -u minecraft-server -f
```

First start takes a few minutes while it builds the world. Wait for a line saying
**Done**. Press `Ctrl+C` to stop watching — this does **not** stop the server.

Useful later:

```bash
systemctl status minecraft-server     # is it running?
systemctl restart minecraft-server    # restart it
systemctl stop minecraft-server       # stop it (saves the world first)
```

## Step 6 — Open the ports

```bash
ufw allow OpenSSH
ufw allow 25565/tcp
ufw --force enable
```

`ufw allow OpenSSH` must come first, or you lock yourself out of your own server.
The second is Minecraft, and it is the only port the server needs.

Many providers have a **second** firewall in their web panel, separate from
`ufw` — Hetzner and OVH both do. Open 25565/tcp there too, or the port stays
shut no matter what `ufw` says.

## Step 7 — Test before touching DNS

In Minecraft (with Fabric 26.2 installed): **Multiplayer → Add Server**, address
`203.0.113.10`. Your IP, no domain yet.

If you get in, the server works. If not, go to [Troubleshooting](#troubleshooting)
before continuing — DNS will not fix a server that is down.

## Step 8 — Point your domain at it

Buy a domain if you do not have one — [Namecheap](https://www.namecheap.com/),
[Porkbun](https://porkbun.com/) or [Cloudflare](https://www.cloudflare.com/) are
all fine, roughly €10/year.

In your registrar's control panel find **DNS** or **DNS records**, and add two.

**Record 1** — points a name at your server:

| Field | Value |
| --- | --- |
| Type | `A` |
| Name / Host | `mc` |
| Value / Points to | `203.0.113.10` |
| TTL | 300 (or Automatic) |

**Record 2** — lets players type just `example.com` with no port:

| Field | Value |
| --- | --- |
| Type | `SRV` |
| Name / Host | `_minecraft._tcp` |
| Priority | `0` |
| Weight | `5` |
| Port | `25565` |
| Target / Value | `mc.example.com` |

Some registrars split that name into separate "Service" (`_minecraft`) and
"Protocol" (`_tcp`) boxes. Same record either way.

Wait 5-30 minutes, then check from your server:

```bash
dig +short mc.example.com
dig +short SRV _minecraft._tcp.example.com
```

The first should print your IP. Now players can join at **`example.com`**.

## Step 9 — Make yourself admin, and lock the door

**This matters.** Your server has `online-mode=false`, so Minecraft accounts are
not verified: anyone who knows your address can join using **any** username —
including yours, which would give them your admin powers.

Turn on the whitelist so only people you name can join:

```bash
nano /opt/minecraft-server/server.properties
```

Change these two lines:

```
white-list=true
enforce-whitelist=true
```

Save with `Ctrl+O`, Enter, then `Ctrl+X`. Restart:

```bash
systemctl restart minecraft-server
```

To add players and make yourself admin you need the server console, which means
turning on RCON. In the same file set:

```
enable-rcon=true
rcon.password=PickALongRandomPasswordHere
```

Restart again, then:

```bash
apt install -y mcrcon
mcrcon -H 127.0.0.1 -P 25575 -p PickALongRandomPasswordHere
```

At the prompt:

```
whitelist add YourMinecraftName
op YourMinecraftName
whitelist add FriendName
```

Type `exit` (or press Ctrl+D) when done.

## Step 10 — Tell your players what to install

Everyone joining needs, for Minecraft **26.2**:

1. **Fabric loader** — https://fabricmc.net/use/installer
2. **Fabric API** and **Veinminer** in their own mods folder — without these,
   vein mining will not work
3. Optionally **Meteor Client**, from the `client-mods/` folder of this repo

Their mods folder:
- Windows: `%APPDATA%\.minecraft\mods`
- Mac: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

---

## Troubleshooting

**Cannot connect at all.** Is it running? `systemctl status minecraft-server`.
Look for errors: `journalctl -u minecraft-server -n 50`.

**Server says it is running but nobody can join.** Firewall — redo Step 6 and
check `ufw status`. Also check your provider's own firewall in their web panel;
Hetzner and OVH have one separate from `ufw`.

**Domain does not work but the IP does.** DNS has not propagated — wait longer.
Check the A record points at the right IP.

**Server keeps restarting.** Usually out of memory. Check with `free -m` how much
the machine really has. The heap needs roughly a gigabyte less than the total:
edit `/etc/systemd/system/minecraft-server.service`, change `Environment=HEAP=2G`
to `1400M` on a 2 GB box, then
`systemctl daemon-reload && systemctl restart minecraft-server`.

**Laggy with several players.** Lower `view-distance` in `server.properties` from
8 to 6, and `simulation-distance` from 6 to 4, then restart.

**Nothing you try makes the port reachable.** Some connections cannot be opened
at all — home internet behind CGNAT is the usual case. The free way around it is
one command on the server: `sudo ./deploy/playit-setup.sh`. Full list of options
in [TCP.md](TCP.md).

## Back up your world

Nothing backs up automatically. One command, run occasionally:

```bash
tar czf ~/world-backup-$(date +%F).tar.gz -C /opt/minecraft-server world
```

Copy those files off the server sometimes. A dead disk otherwise means the world
is gone permanently.
