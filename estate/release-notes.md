### New in 1.5.0
- **Stop sharing a claim with a community.** Right-click the **Community** button in a claim's menu to take it out of its community straight away (or use **Stop sharing** in the community picker, or `/land community assign none`). It was possible before, but hidden in the picker. The button now says so.
- **Hover a claim to see its community.** The claim list and a claim's menu show a **Community** line.
- **Borders keep the menu open.** The borders and preview buttons no longer close the menu.

### New in 1.4.0
- **Claim over a build you made together.** If someone helped build your base you got "X built something there, you can't claim over another player's build", even though no claim existed to trust them in. The message now has a **[Let X build with me]** button (or `/land partner add <player>`): their blocks stop counting against your claims. `/land partner remove <player>` undoes it, `/land partner` lists them. It only works one way, and you can still trust them in the claim afterwards.
- **Manage any claim from anywhere.** `/land list` now opens a claim's own menu with left-click (right-click still shows its borders): rename, show borders, resize, friends, protection and unclaim, without standing in it. `/land manage <name>` opens it directly. Opening a claim, or `/land select <name>`, also makes `/land trust`, `/land flag`, `/land rename` and the other commands act on it when you're not standing in one of your own claims. Menu buttons that used to say "stand in the claim" now let you pick one from a list.
- **Nameable claims.** Name a claim while you draw it (`/land claim name <name>`, or the name tag in the corner editor) and rename it any time (`/land rename`, or the Rename button). Names are unique per player, new claims no longer all share "Steve's claim" (they become "Steve's claim 2", ...), and anywhere a claim is asked for you can use its name or id: `/land manage Farm`, `/land info Farm`.
- **Communities.** Join groups that share claims (`/land community`, or the **Communities** button where Friends used to be). Share a claim with a community from its menu and every member counts as an owner of it: they can build, manage friends and protection and resize it, and only whoever made it can unclaim it. Members' claims can sit right next to each other with no buffer, and their blocks never stop each other from claiming. You can be in several communities.
- **Shared claims don't cost the creator.** A community has its own budget (`baseBudget` per member, up to the new `maxCommunityBudget` setting). Sharing a claim gives the creator their blocks back; stopping sharing puts the cost back on them.
- **Trust moved into each claim's menu**, with a new **Everyone else** setting (`/land defaulttier`) for what players not on the list may do.
- The database is upgraded to schema 5 on first start, with a backup (`estate.db.v4.bak`) next to it.

### New in 1.3.2
- **Outlines follow the ground.** Particle lines now run along the top of the blocks, up and down hills and steps, instead of floating at one height.
- **Much lighter on FPS.** Far fewer particles: dense right next to you, thinner further away, and each one lasts a shorter time.
- **Shorter warning** when building in someone's buffer, so it fits on screen.

### New in 1.3.1
- **Denser particle outlines.** Four particles per block within 24 blocks of you, drawn twice as often.
- **Holding the claim tool shows claims within 16 blocks** of you, so you can see where you can claim.
- **Other players' claims are glass when you're close** (within 16 blocks), particle outlines further away.
- **Auto-claims follow your build both ways.** Break blocks and the claim shrinks to what's left; knock the whole build down and the claim is removed, giving the blocks back. Claims you drew or resized yourself never shrink by themselves. (Applies to claims auto-claim makes from now on.)

### New in 1.3.0
- **PvP and mob flags removed.** Estate no longer touches PvP (Gatekeeper's `/pvp` handles it) or mobs (they behave as in vanilla; use the `mobGriefing` game rule). Explosions, including creepers, are still protected. Owners can now change explosions, fire, water and lava, pistons and animals on their own claims.
- **Menu colours fixed.** "protected" and "not protected" show in green and red instead of as `<green>` text.
- **Glass borders stay visible.** They no longer vanish when one corner of a wall is out of sight (this happened most with Sodium), and they're drawn 64 blocks around you.
- **Clearer borders.** Outlines are particles again: green for yours, red for others, yellow for buffers (now including your own). Only your own claims and the one you're drawing are filled in with glass, so crowded areas stay readable.
- **No more doubled names** when walking into a claim: "Entering Steve's claim" instead of "Entering Steve's claim Steve's claim". Renamed claims show their name.
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
