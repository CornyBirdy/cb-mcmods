# Corpes

Server-side corpses for **Paper 26.1+** and **Fabric 26.3**, inspired by the [Corpse](https://modrinth.com/mod/corpse) mod.
**Players don't need to install anything.** It only uses vanilla entities and menus, so any vanilla client sees it.

When a player dies, their body (with their skin) lies where they died and holds all of their items.
Right-click the body to open it and take your stuff back.

| Server | Download |
|---|---|
| Paper 26.1 or newer | `PaperCorpes-x.y.z.jar` → `plugins/` |
| Fabric 26.3 (needs [Fabric API](https://modrinth.com/mod/fabric-api)) | `FabricCorpes-x.y.z.jar` → `mods/` (server only) |

## Features

- **Corpse with your skin.** A lying-down player body (vanilla Mannequin entity) appears where you died. Its name and time of death float above it and can be seen from 128 blocks away (configurable), even though the game only draws the body itself up close.
- **Loot menu that looks like your inventory.**
  ```
  ┌──────────────────────────────────────────────┐
  │ Helmet Chest Legs Boots Offhand  ▪  ▪  ▪  ☺  │  ☺ = who died, when, where, stacks left
  │ main inventory                               │
  │ main inventory                               │
  │ main inventory                               │
  │ hotbar                                       │
  │ extra items ×6                   ▪  ⚑  ⬇     │  ⚑ = Equip all, ⬇ = Take all
  └──────────────────────────────────────────────┘
  ```
  - Every item sits in the same spot it had in your inventory.
  - Empty armour and offhand slots are labelled, so you can see what's missing.
  - The head in the corner shows when and where you died, how, and how many stacks are left.
  - **Equip all** puts everything back in the slot it came from. **Take all** moves everything into your inventory.
  - You can take items out but never put any in.
  - Two players looting at once share the same menu, so items can't be duplicated.
- **Corpses stay until emptied.** They survive restarts and disappear once the last item is taken.
- **Skeletons.** After a set time (60 minutes by default) the body becomes a skull and bones.
- **Death history.** `/corpse history` lists your recent deaths with time, coordinates and cause. Admins can open a copy of any old death inventory to restore lost items.
- **Safe placement.** Deaths in the void, in lava or inside blocks put the corpse somewhere reachable.
- `keepInventory` is respected: no corpse spawns when you keep your items.

## Commands & permissions

| Command | Paper permission | Fabric permission | What it does |
|---|---|---|---|
| `/corpse history` | `papercorpes.use` (everyone) | `corpes:use` (everyone) | List your recent deaths |
| `/corpse history <player>` | `papercorpes.admin` (op) | `corpes:admin` (op level 2) | List someone else's deaths. Click **[View]** to open one |
| `/corpse view <player> <number>` | `papercorpes.admin` | `corpes:admin` | Open a copy of that death's inventory. Items taken out are created, which is how you restore lost items |
| `/corpse reload` | `papercorpes.admin` | `corpes:admin` | Reload the config |
| `/corpse config` | `papercorpes.config` (op) | `corpes:config` (op level 4) | List every option and its value |
| `/corpse config <option>` | same | same | Show one option |
| `/corpse config <option> <value>` | same | same | Change an option. It is saved to the config file and takes effect straight away |

The admin permission also lets you loot any corpse when `allow-anyone-loot` is `false`.
On Fabric, permission mods that support the Fabric Permission API can grant `corpes:admin`, `corpes:config` and `corpes:use`; without one, op level 2 counts as admin and only op level 4 and the server console can use `/corpse config`.
`/corpse config` changes every option in the table below, but not the `messages`; edit those in the config file.

## Config

Paper: `plugins/PaperCorpes/config.yml`. Fabric: `config/corpes.json`. Both have the same options:

| Option | Default | Meaning |
|---|---|---|
| `allow-anyone-loot` | `true` | `false` = only the owner (and admins) can open a corpse |
| `store-xp` | `false` | Keep XP in the corpse instead of dropping orbs. The first person to open it gets the XP |
| `skeleton-after-minutes` | `60` | Minutes before the body becomes a skeleton. `0` = never |
| `history-size` | `10` | Deaths remembered per player |
| `show-name` | `true` | Show the name and time of death above the corpse |
| `label-view-distance` | `128` | How far away (in blocks) that name can be seen. The body itself is only drawn within about 12 blocks, so the name is what you spot from a distance |
| `label-see-through` | `false` | `true` = the name shows through walls and terrain, like a waypoint |
| `hitbox-width` / `hitbox-height` | `2.0` / `0.6` | Size of the right-click box over the body |
| `float-on-lava` | `true` | `true` = a player who dies in lava gets their corpse on top of the lava. `false` = it stays where they died, in the lava |
| `fall-to-ground` | `true` | `true` = a player who dies in mid-air gets their corpse on the ground below. `false` = it stays in the air |
| `date-format` | `dd MMM HH:mm` | Date format for history and the corpse label |
| `messages.*` | | All text, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format. Lore entries can be a list of lines |

The Fabric mod understands the common MiniMessage tags: colours (`<gray>`, `<#ff8800>`), `<bold>`, `<italic>`, `<underlined>`, `<strikethrough>`, `<obfuscated>`, `<reset>` and closing tags.
Options missing from an older config file use the defaults, so updating never breaks your config.

## Data

| | Paper | Fabric |
|---|---|---|
| Corpses | `plugins/PaperCorpes/corpses/<id>.yml` | `<world>/corpes/corpses/<id>.dat` |
| History | `plugins/PaperCorpes/history/<player-uuid>.yml` | `<world>/corpes/history/<player-uuid>.dat` |

Items are stored with the game's data version, so they survive Minecraft updates. Corpses saved by PaperCorpes 1.0 are converted to the new menu layout automatically.

## Building

```
./gradlew build
```

Needs JDK 25. The jars end up in `paper/build/libs/` and `fabric/build/libs/`. GitHub Actions builds both on every push.
The Fabric versions (Minecraft, loader, Fabric API) are in `gradle.properties`.

To publish a release, open the **Actions** tab, pick **Release**, click **Run workflow** and enter a version (for example `1.0.1`). You can also push a tag like `v1.0.1`. The workflow builds both jars with that version, creates the tag and attaches them to a new GitHub release. Releases are published as pre-releases; once a version is tested in-game, edit the release on GitHub and tick "Set as the latest release".
