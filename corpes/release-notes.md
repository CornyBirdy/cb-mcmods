**New: a death browser.** `/corpse history` now opens a screen with your recent deaths, newest in the top left. Click one to see what was in it, or right-click to make the body glow for 30 seconds (through walls) so you can find it. Admins get an **All players** button to browse everyone's deaths. Two switches in the config control the admin side: `admin-see-all-deaths` (on) and `admin-restore` (off), which lets admins take copies of items out of a death. Death inventories are read-only for everyone else. Highlighting only works while the body still exists; once it has been emptied there is nothing left to find.

Server-side corpses for **Paper 26.1+** and **Fabric 26.3**. Players don't need to install anything.

When a player dies, their body (with their skin) lies where they died and holds all their items. Right-click it to open a loot menu and take everything back.

### Install
- **Paper:** put `PaperCorpes-x.y.z.jar` in `plugins/`. Needs Paper 26.1 or newer (Java 25). Config: `plugins/PaperCorpes/config.yml`.
- **Fabric:** put `FabricCorpes-x.y.z.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in the server's `mods/`. Needs Minecraft 26.3 (Java 25). Config: `config/corpes.json`. Clients don't need it.

### Features
- A lying-down corpse with the player's skin (vanilla Mannequin entity) holds their items.
- The loot menu looks like your inventory: armour and offhand on top (labelled when empty), then inventory and hotbar, with **Equip all** and **Take all** at the bottom. A head shows who died, when, where, and how many stacks are left.
- Items can only be taken out, and two players looting at once can't duplicate items.
- Corpses survive restarts and disappear once emptied.
- Bodies turn into a skull and bones after 60 minutes (configurable).
- `/corpse history` shows recent deaths. Admins can preview old death inventories (read-only).

### Check when you first test it
- Right-click the body. If clicks miss, raise `hitbox-width` in the config.
- Set `skeleton-after-minutes: 1` and check that the skeleton looks right.
