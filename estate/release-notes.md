### New in 1.1.0
- **Claim tool.** `/land claim` (or Claim land in the menu) gives you a golden shovel. Right-click a block for corner 1, sneak + right-click for corner 2, Litematica style. Until you set corner 2 the box follows your crosshair, with the size and cost in the action bar.
- **Real coordinates.** Corners are absolute positions. Set them exactly with `/land pos1 x y z` / `/land pos2 x y z`, or nudge X, Y and Z one block (or one cell) at a time in the new coordinate editor (`/land claim edit`). Resize uses the same editor.
- **Block borders.** Borders are now glowing block outlines sent only to you (nothing is placed in the world), and corner 1 / corner 2 show as red and blue cubes. `borderStyle particles` brings the old look back.
- **Clearer menus.** The main menu is grouped into land / friends & protection / help rows, with a budget bar and "» Click to …" on every button.
- Settings `newClaimSize` and `resizeVerticalStep` are gone (no longer needed); they're ignored if still in your config.

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
- `/land claim`: right-click and sneak + right-click with the claim tool, try `/land pos1 x y z`, then `/land claim edit`. The outline should turn red when the claim would be too big or hit someone else's.
- `/land resize`: move a corner outwards and inwards and confirm.
- From outside the claim: TNT, a lava bucket, a piston and a dispenser aimed at it shouldn't change anything inside.
- `/land admin set baseBudget 100` from the console should take effect at once and still be there after a restart.
