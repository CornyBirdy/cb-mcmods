Turn ender chests, the End, the Nether, netherite armor and netherite tools on or off while the server runs, for **Paper 26.1+** and **Fabric 26.3**. Works from the server console, no restart needed. Players don't need to install anything.

**New in 1.4.0:** `/pvp on` and `/pvp off` are gone; `/pvp toggle` is the one way to switch, so there's no confusion between them.

**Opt-in PvP.** Everyone starts with PvP off and switches it with `/pvp toggle`; players only hurt each other when both have it on. Changing it has a cooldown (60s), players in a fight can't turn it off until the combat timer runs out (30s after the last hit), and killing players who log out in combat can be turned on with `/gatekeeper combatlog on` (off by default). Admins change all of it with `/gatekeeper pvpdefault|pvpcooldown|pvpcombat|combatlog`. Keep `pvp=true` in `server.properties`.

Exceptions: two players who `/pvp allow` each other can fight even with PvP off (`/pvp list` shows who), and admins can lock someone's PvP on or off with `/gatekeeper pvpforce <player> on|off|clear`.

Gatekeeper used to be called **EndLock**. If you had EndLock 1.0.0, delete its jar; Gatekeeper picks up its config and closed portals by itself.

### Install
- **Paper:** put `PaperGatekeeper-x.y.z.jar` in `plugins/`. Needs Paper 26.1 or newer (Java 25). Config: `plugins/Gatekeeper/config.yml`.
- **Fabric:** put `FabricGatekeeper-x.y.z.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in the server's `mods/`. Needs Minecraft 26.3 (Java 25). Config: `config/gatekeeper.json`. Clients don't need it.

### Commands (in game or in the console, `gk` works too)
- `gatekeeper status`: what's on and off
- `gatekeeper enderchests on|off|toggle`: ender chests can't be opened, placed or crafted while off. Items in them are kept.
- `gatekeeper end on|off|toggle`: while off, end portals go dark and players in the End are sent to spawn. Eyes can still be put in the frames, and every portal lights up the moment the End is turned back on.
- `gatekeeper nether on|off|toggle`: the same for nether portals and the Nether. Frames can still be lit; the portals appear once it's back on.
- `gatekeeper netheritearmor on|off|toggle`: netherite armor can't be made or worn while off. Anyone wearing it has it moved into their inventory.
- `gatekeeper netheritetools on|off|toggle`: netherite tools can't be made, mine, hit or be used while off. Players keep them.
- `gatekeeper pvp`: the PvP settings. `gatekeeper pvpdefault on|off`, `gatekeeper pvpcooldown <seconds>`, `gatekeeper pvpcombat <seconds>`, `gatekeeper combatlog on|off|toggle` change them.
- `gatekeeper reload`: reload the config
- `gatekeeper pvpforce <player> on|off|clear`: lock someone's PvP on or off, or let them choose again
- `pvp` (every player): show whether your PvP is on; `pvp toggle` switches it
- `pvp allow|unallow <player>`, `pvp list` (every player): fight a friend even with PvP off, once you've both allowed each other

### Check when you first test it
- Turn ender chests off and try to open, place and craft one.
- Turn the End off next to a lit end portal: it should go dark, with the eyes still in the frames. Turn it back on: it should light up.
- With the End off, fill the last eye of a portal: the eye goes in, the portal stays dark until you turn the End on.
- Turn the Nether off next to a lit nether portal: it should go dark. Light a frame: the fire burns but no portal appears. Turn the Nether back on: both portals should light up.
- Turn the End or the Nether off while someone is in it: they should land at the overworld spawn.
- Turn netherite armor off while wearing some: it should move into your inventory, and putting it back on shouldn't work.
- Turn netherite tools off: a netherite pickaxe shouldn't break blocks and a netherite sword shouldn't hurt mobs. A diamond one should.
- PvP: with both players' PvP off, hitting does nothing and says so. Both `/pvp toggle` to on: hits land, and toggling off right after says you're in a fight. Wait 30s: it then says to wait for the cooldown.
- Turn on `/gatekeeper combatlog on`, start a fight and log out: you should die and drop your items, and others see a message.
- Both players with PvP off: `/pvp allow` each other, then hits should land. `/pvp unallow` during the fight should say you're in a fight.
- `/gatekeeper pvpforce <player> off`: nobody can hit them, even if allowed, and `/pvp toggle` tells them it's locked. `clear` gives control back.
