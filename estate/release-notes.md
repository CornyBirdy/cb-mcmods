### New in 1.2.0
- **Claims are exact to the block.** No more 8x8 plots: a claim is exactly the blocks between your two corners, and boxes can be joined into any shape. Budgets are counted in blocks of land (4096 by default, a 64x64 area, same as before). Existing claims, budgets, grants and donations are converted automatically when you update, and the old database and config are backed up next to the new ones.
- **Glass borders.** Show borders draws claims as boxes of tinted glass with glowing edges: green for yours, red for others. They follow you while you walk and no longer flicker.
- **Claim tool fixed.** Left-click for corner 1, right-click for corner 2 (like Litematica). It doesn't dig, hit mobs or lose durability any more.
- **Auto-claim** boxes your build with 4 blocks of land around it (`autoClaimMargin`) and stretches to cover new building nearby without leaving gaps.
- Message texts you haven't changed now update with Estate. This time your old `estate-messages.json` is backed up and replaced once, because the wording changed from cells to blocks.

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
