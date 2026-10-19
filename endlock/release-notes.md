Turn ender chests and the End on or off for **Paper 26.1+** and **Fabric 26.3**. Works from the server console. Players don't need to install anything.

### Install
- **Paper:** put `PaperEndLock-x.y.z.jar` in `plugins/`. Needs Paper 26.1 or newer (Java 25). Config: `plugins/EndLock/config.yml`.
- **Fabric:** put `FabricEndLock-x.y.z.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in the server's `mods/`. Needs Minecraft 26.3 (Java 25). Config: `config/endlock.json`. Clients don't need it.

### Commands (in game or in the console)
- `endlock status`: what's on and off
- `endlock enderchests on|off|toggle`: ender chests can't be opened, placed or crafted while off. Items in them are kept.
- `endlock end on|off|toggle`: while off, end portals go dark and players in the End are sent to spawn. Eyes can still be put in the frames, and every portal lights up the moment the End is turned back on.
- `endlock reload`: reload the config

### Check when you first test it
- Turn ender chests off and try to open, place and craft one.
- Turn the End off while someone is in the End: they should land at the overworld spawn.
- Turn the End off next to a lit portal: it should go dark, with the eyes still in the frames. Turn it back on: it should light up.
- With the End off, fill the last eye of a portal: the eye goes in, the portal stays dark until you turn the End on.
