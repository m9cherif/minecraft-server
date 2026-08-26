# Free 24/7 hosting on Oracle Cloud

Oracle's **Always Free** tier gives you a permanently free server with **4 CPUs and
24 GB RAM** — more than this setup needs. It is not a trial and does not expire.

Three things to know before you start:

- Signup needs a **credit card for identity verification**. You are not charged.
  Always Free resources cannot run up a bill on their own.
- Creating the free ARM instance often fails with **"Out of host capacity"**.
  This is normal and very common. See [If you get "out of capacity"](#if-you-get-out-of-capacity).
- The free machines use **ARM** chips (Ampere), not Intel. Everything here works
  on ARM; just make sure you pick the ARM image where the guide says so.

---

## Step 1 — Create the account

1. Go to https://www.oracle.com/cloud/free/ and click **Start for free**.
2. Fill in your details. **Pick your home region carefully** — you cannot change
   it later, and it decides both your ping and how likely you are to hit the
   capacity problem. Choose a region near you, but if you are in a busy area,
   a slightly further region is often much easier to get a machine in.
3. Verify your card. Watch for the words **Always Free** on the account page when
   you finish.

Approval usually takes minutes, occasionally a few hours.

## Step 2 — Create the server

In the Oracle Cloud console: **Menu → Compute → Instances → Create instance**.

Set these:

| Setting | What to choose |
| --- | --- |
| Name | `minecraft` |
| Image | **Ubuntu 24.04** (make sure it is the **aarch64 / ARM** build) |
| Shape | **Ampere · VM.Standard.A1.Flex** |
| OCPUs | **4** |
| Memory | **24 GB** |
| Boot volume | **100 GB** (free tier allows up to 200 GB total) |

Every one of those must say **"Always Free eligible"**. If a shape does not,
you are about to create something billable — go back and change it.

Under **Add SSH keys** choose **Generate a key pair for me** and **download the
private key**. You cannot download it again later. Save it somewhere safe.

Click **Create**. After a minute you get a **Public IP address** — write it down.

## Step 3 — Connect

On your own computer, open PowerShell (Windows) or Terminal (Mac). Go to wherever
the downloaded key is, then:

```bash
chmod 600 ssh-key-*.key
ssh -i ssh-key-*.key ubuntu@YOUR-IP
```

On Windows, if `chmod` is not recognised, skip that line — it is only needed on
Mac and Linux.

Note the username is **`ubuntu`**, not `root`, on Oracle images.

## Step 4 — Open the ports (Oracle needs this done TWICE)

This is the step that catches almost everyone. Oracle blocks ports in **two
separate places**, and you must open both or nothing works.

**4a — In the Oracle website:**

1. On your instance page click the **Virtual cloud network** link
2. Click **Security Lists** → **Default Security List**
3. **Add Ingress Rules**, and add these two:

| Source CIDR | IP Protocol | Destination Port |
| --- | --- | --- |
| `0.0.0.0/0` | TCP | `25565` |
| `0.0.0.0/0` | UDP | `24454` |

**4b — On the server itself** (in your SSH window):

```bash
sudo iptables -I INPUT -p tcp --dport 25565 -j ACCEPT
sudo iptables -I INPUT -p udp --dport 24454 -j ACCEPT
sudo netfilter-persistent save
```

Oracle's Ubuntu images ship with firewall rules that block everything except SSH,
which is why the website rules alone are not enough.

The UDP one is voice chat. Miss it and the game will connect fine while voice
chat fails — the most common complaint with this setup.

## Step 5 — Install the server

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-25-jre-headless git curl jq

sudo useradd --system --home /opt/minecraft-server --shell /usr/sbin/nologin minecraft
sudo git clone https://github.com/m9cherif/minecraft-server.git /opt/minecraft-server
sudo chown -R minecraft:minecraft /opt/minecraft-server
sudo -u minecraft /opt/minecraft-server/setup.sh
```

Check Java is version 25 — Minecraft 26.2 will not start on anything older:

```bash
java -version
```

## Step 6 — Turn on 24/7 mode

```bash
sudo cp /opt/minecraft-server/deploy/minecraft-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft-server
```

You have 24 GB here, so raise the heap from the 12 G default:

```bash
sudo systemctl edit minecraft-server
```

Paste this in the empty area at the top, then save (`Ctrl+O`, Enter, `Ctrl+X`):

```
[Service]
Environment=HEAP=16G
```

Then:

```bash
sudo systemctl restart minecraft-server
```

Watch it boot:

```bash
journalctl -u minecraft-server -f
```

First start takes a few minutes to build the world. Wait for **Done**, then press
`Ctrl+C` — that stops watching, not the server.

## Step 7 — Join

In Minecraft (Fabric 26.2 installed), **Multiplayer → Add Server**, and enter your
Oracle public IP.

Once that works you can add a domain — see
[deploy/README.md](README.md#4-point-your-domain-at-it) for the DNS records.

## Step 8 — Lock it down

Your server has `online-mode=false`, so **anyone who learns the IP can join as any
username, including yours** — which would give them admin. Do this now:

```bash
sudo nano /opt/minecraft-server/server.properties
```

Set:

```
white-list=true
enforce-whitelist=true
```

Save (`Ctrl+O`, Enter, `Ctrl+X`), then `sudo systemctl restart minecraft-server`.

Step 9 of [BEGINNER.md](BEGINNER.md) explains how to add players and make yourself
admin using RCON.

---

## If you get "out of capacity"

The error `Out of host capacity` means Oracle has no free ARM machines in your
region right now. It is the single most common problem with the free tier, and it
is not something you did wrong.

What works:

- **Just retry.** Capacity frees up constantly. Try several times a day.
- **Try a different availability domain** if your region has more than one.
- **Try 1 OCPU / 6 GB first.** Smaller requests succeed far more often, and you
  can resize up later. The server runs on 6 GB — set `HEAP=4G` — just with fewer
  players and a lower view distance.
- **Wait a few days.** Availability changes a lot week to week.

If your region simply never has capacity, a new account in a quieter region is the
usual fix — remember the home region cannot be changed after signup.

## Keeping the account

Oracle may reclaim **idle** Always Free compute instances. A Minecraft server that
is actually running and using CPU is not idle, so a real server is generally fine.
Log into the console occasionally so the account does not look abandoned.

## Backups

```bash
sudo tar czf ~/world-backup-$(date +%F).tar.gz -C /opt/minecraft-server world
```

Copy those off the server now and then. Free hosting comes with no guarantees —
if the instance vanishes, your world goes with it unless you have a copy.
