# Gatekeeper

Turn **ender chests**, **the End**, **the Nether**, **netherite armor** and **netherite tools** on or off while the server runs, for **Paper 26.1+** and **Fabric 26.3**. One command does it, in game or in the server console, and nothing needs a restart. (Vanilla's `allow-nether` in `server.properties` only takes effect after a restart.)
**Players don't need to install anything.**

| Server | Download |
|---|---|
| Paper 26.1 or newer | `PaperGatekeeper-x.y.z.jar` → `plugins/` |
| Fabric 26.3 (needs [Fabric API](https://modrinth.com/mod/fabric-api)) | `FabricGatekeeper-x.y.z.jar` → `mods/` (server only) |

Get them from the [Gatekeeper releases](https://github.com/CornyBirdy/cb-blocklab/releases?q=gatekeeper&expanded=true).

> Gatekeeper used to be called **EndLock**. If you had EndLock 1.0.0, delete its jar; Gatekeeper picks up its config and closed portals by itself.

## What happens when something is turned off

**Ender chests off**
- Right-clicking an ender chest doesn't open it.
- Ender chests can't be placed.
- Ender chests can't be crafted (on Fabric the result shows but can't be taken out; on Paper crafters can't make them either).
- On Paper, ender chests opened by other plugins (an `/enderchest` command, for example) are blocked too.
- Nothing is deleted. Items already in ender chests are still there when you turn them back on.

**The End off**
- End portals go dark: lit portals switch off, both ones in loaded chunks and ones found later as their chunks load.
- Eyes of ender can still be put in the frames (with the usual sound), but the portal doesn't light up. The player gets told it will open once the End is back on.
- **When you turn the End back on, every one of those portals lights up at once**, and everyone hears the "portal opened" sound.
- Anyone in the End is sent to the overworld spawn right away, and again if they log in there or get teleported there.
- The exit portal in the End keeps working, so nobody gets stuck.

**The Nether off**
- Nether portals outside the Nether go dark, the same way as end portals.
- Frames can still be lit with flint and steel or a fire charge. The fire burns, but the portal doesn't appear, and players nearby get told it will open once the Nether is back on.
- **When you turn the Nether back on, every one of those portals lights up again.**
- Anyone in the Nether is sent to the overworld spawn right away, and again if they log in there or get teleported there.
- Portals inside the Nether are left alone.

**Netherite armor off**
- Netherite armor can't be made at a smithing table (the upgrade shows but can't be taken out on Fabric, and doesn't show on Paper).
- It can't be worn. Anyone wearing it has it moved into their inventory within half a second, or dropped at their feet if the inventory is full. Right-clicking to put it on and dispensers don't work either.
- Players keep the armor. It can be worn again once you turn it back on.

**Netherite tools off**
- Netherite swords, pickaxes, axes, shovels, hoes and spears can't be made at a smithing table.
- They can't break blocks or hit anything, and right-clicking with them does nothing (no charging spears; on Paper, also no stripping logs or making paths).
- Players keep the tools. Diamond and other tools work as normal.

Armor and tools are separate switches, so you can ban one and keep the other.

Portals in chunks nobody is near light up as soon as someone gets close. If someone built something in a portal's space in the meantime, that block is left alone.

All switches are saved in the config, so they stay the same after a restart. Closed portals are remembered in `plugins/Gatekeeper/closed-*-portals.yml` (Paper) or `<world>/gatekeeper/closed-*-portals.json` (Fabric).

## Commands

Everything works in the server console (no slash needed there, e.g. `gatekeeper nether off`). `/gk` is short for `/gatekeeper`.

| Command | What it does |
|---|---|
| `/gatekeeper` or `/gatekeeper status` | Show what's on and off |
| `/gatekeeper enderchests on` / `off` / `toggle` | Turn ender chests on or off |
| `/gatekeeper end on` / `off` / `toggle` | Turn the End on or off |
| `/gatekeeper nether on` / `off` / `toggle` | Turn the Nether on or off |
| `/gatekeeper netheritearmor on` / `off` / `toggle` | Turn netherite armor on or off |
| `/gatekeeper netheritetools on` / `off` / `toggle` | Turn netherite tools on or off |
| `/gatekeeper reload` | Reload the config file |

When the End or the Nether is switched, everyone online gets a message. Ender chest and netherite changes are quiet by default; set `announce-ender-chests`, `announce-netherite-armor` or `announce-netherite-tools` to `true` to announce those too.

## Permissions

| Paper | Fabric | Default | What it does |
|---|---|---|---|
| `gatekeeper.admin` | `gatekeeper:admin` | op (op level 2 on Fabric) | Use `/gatekeeper` |
| `gatekeeper.bypass.enderchests` | `gatekeeper:bypass.enderchests` | nobody | Keep using ender chests while they're off |
| `gatekeeper.bypass.end` | `gatekeeper:bypass.end` | nobody | Stay in the End while it's off |
| `gatekeeper.bypass.nether` | `gatekeeper:bypass.nether` | nobody | Stay in the Nether while it's off |
| `gatekeeper.bypass.netheritearmor` | `gatekeeper:bypass.netheritearmor` | nobody | Keep making and wearing netherite armor while it's off |
| `gatekeeper.bypass.netheritetools` | `gatekeeper:bypass.netheritetools` | nobody | Keep making and using netherite tools while they're off |

Ops don't bypass anything by default, so you can test the switches as an op. Give the bypass permissions with a permissions plugin like LuckPerms. On Fabric that needs a permission mod that supports the Fabric Permission API. Portals stay dark for everyone, so players with a bypass get in with `/tp` or similar.

## Config

Paper: `plugins/Gatekeeper/config.yml`. Fabric: `config/gatekeeper.json`. Both have the same options:

| Option | Default | Meaning |
|---|---|---|
| `ender-chests-enabled` | `true` | `false` = ender chests are off. `/gatekeeper enderchests` changes this |
| `end-enabled` | `true` | `false` = the End is off. `/gatekeeper end` changes this |
| `nether-enabled` | `true` | `false` = the Nether is off. `/gatekeeper nether` changes this |
| `netherite-armor-enabled` | `true` | `false` = netherite armor is off. `/gatekeeper netheritearmor` changes this |
| `netherite-tools-enabled` | `true` | `false` = netherite tools are off. `/gatekeeper netheritetools` changes this |
| `announce-ender-chests` | `false` | Tell everyone online when an admin turns ender chests on or off |
| `announce-end` | `true` | Tell everyone online when an admin turns the End on or off |
| `announce-nether` | `true` | Tell everyone online when an admin turns the Nether on or off |
| `announce-netherite-armor` | `false` | Tell everyone online when an admin turns netherite armor on or off |
| `announce-netherite-tools` | `false` | Tell everyone online when an admin turns netherite tools on or off |
| `messages.*` | | All text, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format. `<state>` is replaced by the `enabled` or `disabled` message |

If you edit the file by hand, run `/gatekeeper reload` afterwards. Options missing from an older config file use the defaults.
Leave `allow-nether` in `server.properties` set to `true`; Gatekeeper does the switching.
