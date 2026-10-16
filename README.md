# PaperCorpes

A server-side corpse plugin for **Paper 26.1+**, inspired by the [Corpse](https://modrinth.com/mod/corpse) mod.
**Players don't need to install anything.** It only uses vanilla entities, so any vanilla client sees it.

When a player dies, their body (with their skin) lies where they died and holds all of their items.
Right-click the body to open it and take your stuff back.

## Features

- **Corpse with your skin.** A lying-down player body (vanilla Mannequin entity) appears where you died. Its name and time of death show above it.
- **Loot menu.** Right-click the corpse to open a chest menu. Items stay in the layout they had in your inventory:
  - main inventory and hotbar
  - armour and offhand
  - extra drops from other plugins
  - **Equip all** puts everything back in the slot it came from.
  - **Take all** moves everything into your inventory.
  - You can take items out but never put any in.
  - Two players looting at once share the same menu, so items can't be duplicated.
- **Corpses stay until emptied.** They survive restarts and disappear once the last item is taken.
- **Skeletons.** After a set time (60 minutes by default) the body becomes a skull and bones.
- **Death history.** `/corpse history` lists your recent deaths with time, coordinates and cause. Admins can open a copy of any old death inventory to restore lost items.
- **Safe placement.** Deaths in the void, in lava or inside blocks put the corpse somewhere reachable.
- `keepInventory` is respected: no corpse spawns when you keep your items.

## Install

1. Download `PaperCorpes-x.y.z.jar` from the [Releases page](https://github.com/CornyBirdy/Paper-Corpes/releases).
2. Put the jar in your server's `plugins/` folder.
3. Restart the server. A config is created at `plugins/PaperCorpes/config.yml`.

The server needs Paper **26.1 or newer** (Java 25). Older versions don't have the Mannequin entity.

## Commands & permissions

| Command | Permission | What it does |
|---|---|---|
| `/corpse history` | `papercorpes.use` (everyone) | List your recent deaths |
| `/corpse history <player>` | `papercorpes.admin` (op) | List someone else's deaths. Click **[View]** to open one |
| `/corpse view <player> <number>` | `papercorpes.admin` | Open a copy of that death's inventory. Items taken out are created, which is how you restore lost items |
| `/corpse reload` | `papercorpes.admin` | Reload `config.yml` |

`papercorpes.admin` also lets you loot any corpse when `allow-anyone-loot` is `false`.

## Config

| Option | Default | Meaning |
|---|---|---|
| `allow-anyone-loot` | `true` | `false` = only the owner (and admins) can open a corpse |
| `store-xp` | `false` | Keep XP in the corpse instead of dropping orbs. The first person to open it gets the XP |
| `skeleton-after-minutes` | `60` | Minutes before the body becomes a skeleton. `0` = never |
| `history-size` | `10` | Deaths remembered per player |
| `show-name` | `true` | Show the name above the corpse |
| `hitbox-width` / `hitbox-height` | `2.0` / `0.6` | Size of the right-click box over the body |
| `date-format` | `dd MMM HH:mm` | Date format for history and the corpse label |
| `messages.*` | | All text, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format |

## Data

- `plugins/PaperCorpes/corpses/<id>.yml` holds one file per existing corpse (its items, location and age).
- `plugins/PaperCorpes/history/<player-uuid>.yml` holds a player's recent deaths.

Items are stored in Paper's versioned format, so they survive Minecraft updates.

## Building

```
./gradlew build
```

Needs JDK 25. The jar ends up in `build/libs/`. GitHub Actions builds it on every push.

To publish a release, open the **Actions** tab, pick **Release**, click **Run workflow** and enter a version (for example `1.0.1`). You can also push a tag like `v1.0.1`. The workflow builds the jar with that version, creates the tag and attaches the jar to a new GitHub release. Releases are published as pre-releases; once a version is tested in-game, edit the release on GitHub and tick "Set as the latest release".
