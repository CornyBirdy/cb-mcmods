Server-side corpses for **Paper 26.1+**. Players don't need to install anything.

When a player dies, their body (with their skin) lies where they died and holds all their items. Right-click it to open a loot menu and take everything back.

### Install
1. Download `PaperCorpes-x.y.z.jar` below.
2. Put it in your server's `plugins/` folder. The server needs Paper 26.1 or newer (Java 25).
3. Restart. The config is at `plugins/PaperCorpes/config.yml`.

### Features
- A lying-down corpse with the player's skin (vanilla Mannequin entity) holds their items.
- The loot menu keeps the inventory layout, with **Equip all** and **Take all** buttons. Items can only be taken out, and two players looting at once can't duplicate items.
- Corpses survive restarts and disappear once emptied.
- Bodies turn into a skull and bones after 60 minutes (configurable).
- `/corpse history` shows recent deaths. Admins can open copies of old death inventories to restore lost items.

### Check when you first test it
- Right-click the body. If clicks miss, raise `hitbox-width` in the config.
- Set `skeleton-after-minutes: 1` and check that the skeleton looks right.
