# Client mods

These are **not** server mods. Do not copy them into `mods/` — the server will
ignore them, and depending on the mod it may fail to start.

## meteor-client-26.2-13.jar

Meteor Client declares `"environment": "client"` in its `fabric.mod.json`.
Fabric Loader only loads client-environment mods on the game client, so this jar
has no effect in a dedicated server's `mods/` directory.

Each player who wants it installs it themselves:

1. Install the **Fabric loader for Minecraft 26.2** with the official installer
   from https://fabricmc.net/use/installer, choosing the client profile.
2. Drop this jar into their own mods folder:
   - Windows: `%APPDATA%\.minecraft\mods`
   - Linux: `~/.minecraft/mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
3. Launch Minecraft using the Fabric profile.

Meteor bundles the Fabric API pieces it needs, so a client running only Meteor
does not need a separate Fabric API download.

Note that Meteor is a utility/cheat client. Its movement, combat and world
modules are exactly what anti-cheat looks for. It is fine on your own server;
using it elsewhere is likely to get the account banned.
