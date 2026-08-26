# minecraft-server

A [PaperMC](https://papermc.io/) Minecraft server packaged as a Docker image, set up to run on [Render](https://render.com/).

## How it works

`start.sh` does three things:

1. Starts a throwaway `python3 -m http.server` on `$PORT` (default `10000`). Render only
   considers a web service healthy once something binds its port, so this keeps the
   service alive.
2. Downloads the Paper jar to `server.jar` if it isn't there yet.
3. Launches the server with `java -jar server.jar nogui`.

## Configuration

Everything is overridable through environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `10000` | Port the keep-alive HTTP server binds (set by Render). |
| `PAPER_VERSION` | `26.1.2` | Paper (Minecraft) version. |
| `PAPER_BUILD` | `69` | Paper build number. |
| `PAPER_SHA256` | see `start.sh` | Object hash used to build the download URL. |
| `PAPER_URL` | derived | Full jar URL; set this to bypass the three fields above. |
| `JAVA_MIN_HEAP` | `64M` | `-Xms` value. |
| `JAVA_MAX_HEAP` | `256M` | `-Xmx` value. |

Server settings live in `server.properties`. Both it and `eula.txt` are copied into the
image, so edits there take effect on the next build.

## Running locally

```bash
docker build -t minecraft-server .
docker run --rm -p 25565:25565 -p 10000:10000 minecraft-server
```

To keep worlds between restarts, mount a volume:

```bash
docker run --rm -p 25565:25565 -v "$PWD/data:/server" minecraft-server
```

## Note on memory

The default `256M` max heap is very small for a Paper server — it fits a free Render
instance but will struggle with more than a couple of players. Raise `JAVA_MAX_HEAP`
if your plan allows it.
