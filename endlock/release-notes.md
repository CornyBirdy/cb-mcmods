Turn ender chests and the End on or off for **Paper 26.1+** and **Fabric 26.3**. Works from the server console. Players don't need to install anything.

### Install
- **Paper:** put `PaperEndLock-x.y.z.jar` in `plugins/`. Needs Paper 26.1 or newer (Java 25). Config: `plugins/EndLock/config.yml`.
- **Fabric:** put `FabricEndLock-x.y.z.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in the server's `mods/`. Needs Minecraft 26.3 (Java 25). Config: `config/endlock.json`. Clients don't need it.

### Commands (in game or in the console)
- `endlock status`: what's on and off
- `endlock enderchests on|off|toggle`: ender chests can't be opened, placed or crafted while off. Items in them are kept.
- `endlock end on|off|toggle`: end portals stop working while off and players in the End are sent to spawn
- `endlock reload`: reload the config

### Check when you first test it
- Turn ender chests off and try to open, place and craft one.
- Turn the End off while someone is in the End: they should land at the overworld spawn.
- Walk into an end portal while the End is off: nothing should happen apart from a message.
