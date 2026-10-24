### New in 1.3.0
- **PvP and mob flags removed.** Estate no longer touches PvP (Gatekeeper's `/pvp` handles it) or mobs (they behave as in vanilla; use the `mobGriefing` game rule). Explosions, including creepers, are still protected. Owners can now change explosions, fire, water and lava, pistons and animals on their own claims.
- **Menu colours fixed.** "protected" and "not protected" show in green and red instead of as `<green>` text.
- **Glass borders stay visible.** They no longer vanish when one corner of a wall is out of sight (this happened most with Sodium), and they're drawn 64 blocks around you.
- **Clearer borders in crowded areas.** Only your own claims (and the one you're drawing) are glass boxes; other players' claims are red edges. Your own buffer now shows as a yellow line too.
- **The claim tool can't be dropped.** A dropped tool vanishes, and you get it back while you're drawing a claim.

### Since 1.2.0
- Claims are exact to the block, shown as glass boxes, and drawn with the claim tool: left-click for corner 1, right-click for corner 2.

Land claims for survival servers, for **Fabric 26.3**. Everyone gets the same flat claim budget, builds can be claimed automatically, buffers stop spite builds, and everything works without an admin online. Players don't need to install anything.

### Install
Put `FabricEstate-x.y.z.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) in the server's `mods/`. Needs Minecraft 26.3 (Java 25). Config: `config/estate.json` and `config/estate-messages.json`. Data: `<world>/estate/estate.db`. Clients don't need it.

### Quick start
- `/land` (or `/estate`) opens the menu: budget, show borders, claim land, resize, friends, auto-claim, protection, unclaim.
- `/land autoclaim on`: once you've placed 50 blocks in one spot, they're claimed, and the claim grows as you build.
- `/land trust <player> [access|containers|build|manage]` lets friends in.
- Admins: `/land admin` (also from the console), `estate:admin` permission or op level 2.

### Check when you first test it
- With auto-claim off, build about 50 blocks: you should get the build tip once. Turn auto-claim on and keep building: a claim should appear and grow.
- Have a second player try to break, place, open a chest, hit an animal and use a bucket inside the claim, and to build right next to it (the buffer). All should be refused with a clear message.
- Trust them as Build: all of that should work. As Access: only doors and buttons.
- Use up the budget: one "You've used all your claim blocks" message, not one per block.
- `/land claim`: left-click and right-click with the claim tool (it shouldn't break anything or lose durability), try `/land pos1 x y z`, then `/land claim edit`. The glass box should turn red when the claim would be too big or hit someone else's, and shouldn't flicker as you look around.
- `/land resize`: move a corner outwards and inwards and confirm.
- From outside the claim: TNT, a lava bucket, a piston and a dispenser aimed at it shouldn't change anything inside.
- `/land admin set baseBudget 100` from the console should take effect at once and still be there after a restart.
