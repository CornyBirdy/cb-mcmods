# EndLock

Turn **ender chests** and **the End** on or off for **Paper 26.1+** and **Fabric 26.3**, with one command that also works in the server console.
**Players don't need to install anything.**

| Server | Download |
|---|---|
| Paper 26.1 or newer | `PaperEndLock-x.y.z.jar` → `plugins/` |
| Fabric 26.3 (needs [Fabric API](https://modrinth.com/mod/fabric-api)) | `FabricEndLock-x.y.z.jar` → `mods/` (server only) |

Get them from the [EndLock releases](https://github.com/CornyBirdy/Paper-Corpes/releases?q=endlock&expanded=true).

## What happens when something is turned off

**Ender chests off**
- Right-clicking an ender chest doesn't open it.
- Ender chests can't be placed.
- Ender chests can't be crafted (on Fabric the result shows but can't be taken out; on Paper crafters can't make them either).
- On Paper, ender chests opened by other plugins (an `/enderchest` command, for example) are blocked too.
- Nothing is deleted. Items already in ender chests are still there when you turn them back on.

**The End off**
- End portals stop working, for players and for mobs and items.
- Anyone in the End is sent to the overworld spawn right away, and again if they log in there or get teleported there.
- The exit portal in the End keeps working, so nobody gets stuck.

Both switches are saved in the config, so they stay the same after a restart.

## Commands

Everything works in the server console (no slash needed there, e.g. `endlock end off`).

| Command | What it does |
|---|---|
| `/endlock` or `/endlock status` | Show whether ender chests and the End are on or off |
| `/endlock enderchests on` / `off` / `toggle` | Turn ender chests on or off |
| `/endlock end on` / `off` / `toggle` | Turn the End on or off |
| `/endlock reload` | Reload the config file |

When the End is switched, everyone online gets a message. Ender chest changes are quiet by default; set `announce-ender-chests` to `true` to announce those too.

## Permissions

| Paper | Fabric | Default | What it does |
|---|---|---|---|
| `endlock.admin` | `endlock:admin` | op (op level 2 on Fabric) | Use `/endlock` |
| `endlock.bypass.enderchests` | `endlock:bypass.enderchests` | nobody | Keep using ender chests while they're off |
| `endlock.bypass.end` | `endlock:bypass.end` | nobody | Keep using end portals and stay in the End while it's off |

Ops don't bypass anything by default, so you can test the switches as an op. Give the bypass permissions with a permissions plugin like LuckPerms. On Fabric that needs a permission mod that supports the Fabric Permission API.

## Config

Paper: `plugins/EndLock/config.yml`. Fabric: `config/endlock.json`. Both have the same options:

| Option | Default | Meaning |
|---|---|---|
| `ender-chests-enabled` | `true` | `false` = ender chests are off. `/endlock enderchests` changes this |
| `end-enabled` | `true` | `false` = the End is off. `/endlock end` changes this |
| `announce-ender-chests` | `false` | Tell everyone online when an admin turns ender chests on or off |
| `announce-end` | `true` | Tell everyone online when an admin turns the End on or off |
| `messages.*` | | All text, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format. `<state>` is replaced by the `enabled` or `disabled` message |

If you edit the file by hand, run `/endlock reload` afterwards. Options missing from an older config file use the defaults.
