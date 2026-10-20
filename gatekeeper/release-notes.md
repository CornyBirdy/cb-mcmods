Turn ender chests, the End, the Nether, netherite armor and netherite tools on or off while the server runs, for **Paper 26.1+** and **Fabric 26.3**. Works from the server console, no restart needed. Players don't need to install anything.

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
- `gatekeeper reload`: reload the config

### Check when you first test it
- Turn ender chests off and try to open, place and craft one.
- Turn the End off next to a lit end portal: it should go dark, with the eyes still in the frames. Turn it back on: it should light up.
- With the End off, fill the last eye of a portal: the eye goes in, the portal stays dark until you turn the End on.
- Turn the Nether off next to a lit nether portal: it should go dark. Light a frame: the fire burns but no portal appears. Turn the Nether back on: both portals should light up.
- Turn the End or the Nether off while someone is in it: they should land at the overworld spawn.
- Turn netherite armor off while wearing some: it should move into your inventory, and putting it back on shouldn't work.
- Turn netherite tools off: a netherite pickaxe shouldn't break blocks and a netherite sword shouldn't hurt mobs. A diamond one should.
