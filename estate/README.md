# Estate

Land claims for survival servers, for **Fabric 26.3**. Built for a small group of friends who play at different times: everyone gets the **same flat claim budget** (playtime never buys more land), builds are **claimed automatically** if you want, and everything works **without an admin online**.
**Players don't need to install anything.** The menu is a chest screen and claims show up as tinted glass boxes the server sends to each player (nothing is placed in the world), so any vanilla client works.

| Server | Download |
|---|---|
| Fabric 26.3 (needs [Fabric API](https://modrinth.com/mod/fabric-api)) | `FabricEstate-x.y.z.jar` → `mods/` (server only) |

`/land` and `/estate` are the same command. Everything below uses `/land`.

## How it works

**Exact to the block.** A claim covers exactly the blocks you choose: a box between two corners, and boxes can be joined into any shape (an L, a plus, a courtyard). Each claim also has a **height range**: from a bit below its lowest block to a bit above its highest (10 blocks of padding by default). A tower doesn't claim extra land, and someone else's underground base can sit below a surface claim.

**A flat budget.** Everyone can claim the same number of blocks of land, counted from above so height is free (4096 by default, a 64x64 area). Unclaiming or shrinking gives the blocks back right away, so you can merge everything into one big claim. There's a limit on the number of claims (5) so the budget can't be spread into dozens of tiny ones. Admins can give one player extra blocks for an approved mega build.

**Auto-claim** (`/land autoclaim on`, or the button in the menu). Off for new players by default. When it's on, once you've placed 50 blocks close together (within 16 blocks of each other), a claim is made around your build (its box plus 4 blocks on every side), and it **grows as you keep building**: build a few blocks past the edge and the claim stretches to cover it, with no gap. Torches, rails, ladders, scaffolding and the like don't count, and a pillar or a long bridge never reaches 50 blocks in one spot. Building above or below your own claim moves its top or bottom, even with auto-claim off. If you run out of claim blocks you get one clear message, not a message for every block.

**Build tip.** If auto-claim is off and you've built something worth protecting, you get one friendly message per session explaining claims, with clickable buttons to turn on auto-claim or open `/land`. Once you've made a claim or used `/land autoclaim`, it never shows again.

**Nobody can claim over your build.** Every placed block is recorded (who, where, when). Nobody can claim land that holds another player's blocks, so a half-built house can't be sniped. Blocks of friends you trust, of players who've been away longer than the inactivity limit and of banned players don't count.

**Buffer.** Around every claim is an 8 block buffer (configurable) where other players can't build, break or claim. No walling in someone's house, no spite builds right against it, no sniping the next part of a build. Players see "This is in someone's buffer." The owner can let people in by trusting them (Build level or higher), members of a pooled claim get in automatically, and **town zones** set by admins have no buffers at all, so people can build right next to each other there. An optional height cap stops towers right next to someone's claim.

**Trust levels**, per claim, each including the one before:
1. **Access**: doors, trapdoors, gates, buttons, levers, beds, crafting tables and other workstations, villager trading, riding.
2. **Containers**: chests, barrels, furnaces, hoppers and every other block that holds items, item frames, armour stands.
3. **Build**: placing and breaking, using tools and buckets, animals, building in the buffer.
4. **Manage**: changing the trust list (except Manage itself) and resizing.

What each level allows is in the config, so it can be changed later.

**Pooled claims.** Players the owner trusts to build can put blocks from their own budget into a claim with `/land donate <blocks>` (and take them back with `/land withdraw`). That lets a group build one claim bigger than any one budget.

**Protection.** Inside a claim, by default nothing can be harmed by anyone who isn't trusted, or by anything coming from outside:

| Flag | Default | Protects against |
|---|---|---|
| `blockBreakPlace` | protected | breaking and placing blocks, using tools, buckets, bone meal, flint and steel and spawn eggs on blocks, trampling farmland |
| `containerAccess` | protected | opening chests, barrels, furnaces, shulker boxes and everything else with an inventory, taking from item frames and armour stands |
| `explosions` | protected | TNT, creepers, end crystals, beds and respawn anchors, withers; explosions from outside can never break blocks inside, whatever the flag says |
| `fireSpread` | protected | fire spreading in and burning blocks away |
| `fluidFlowIn` | protected | water and lava flowing in from outside |
| `pistonCross` | protected | pistons and flying machines pushing or pulling blocks across the border |
| `entityDamage` | protected | players hurting pets, animals and villagers, breaking item frames, paintings, armour stands, boats and minecarts |

Dispensers outside a claim can't fire into it, and projectiles from untrusted players can't break pots, light TNT or press buttons inside. The server owner sets the defaults; owners can change the flags listed in `ownerEditableFlags` (everything except building and containers by default).

Estate doesn't touch **PvP** (use Gatekeeper's `/pvp`) or **mobs**: endermen, zombies, ravagers and the like behave as in vanilla, so use the `mobGriefing` game rule if you want them tamed. Creeper and wither explosions are still covered by the `explosions` flag.

**Inactivity and bans.** A claim is removed when its owner hasn't logged in for 30 days (configurable). This counts real days since their last login, whether the server was running or not, and is checked a minute after the server starts and every hour after that. Admins can exempt a claim. A permanent ban removes the player's claims; temporary bans and players who just stop playing are left to the inactivity timer.

## The menu

`/land` opens it. Everything in it is also a command.

```
┌───────────────────────────────────────┐
│ ▪  ▪  ▪  ▪  ★  ▪  ▪  ▪  ▪ │  ★ = your land: budget bar, claims, where you are
│ ▪  👁  ▪  ✚  ▪  ⇳  ▪  ✖  ▪ │  show borders, claim land, resize, remove claim
│ ▪  👤  ▪  🛡  ▪  ⚡  ▪  ▣  ▪ │  friends, protection, auto-claim, your claims
│ ?  ▪  ▪  ▪  ▪  ▪  ▪  ▪  ⚙ │  help, server settings (admins)
└───────────────────────────────────────┘
```

Every button says what it does and ends with "» Click to …".

- **Show borders** shows nearby claims for 10 seconds as boxes of tinted glass (walls, floor and ceiling) with glowing edges you can see through terrain: **green** for yours, **red** for other players'. Buffers are a **yellow** line and town zones an **aqua** one. They follow you while you walk and fade by themselves. (`borderStyle particles` switches to particles.)
- **Claim land** gives you the **claim tool** (a golden shovel), like a Litematica selection:
  - **Left-click** a block for **corner 1** (red). Then just look around: the box follows your crosshair, and the action bar shows its size, cost and the claim blocks you'd have left.
  - **Right-click** for **corner 2** (blue). You can re-pick either corner as often as you like, up to 64 blocks away. The tool never digs, hits anything or wears out.
  - Corners are **real coordinates**. `/land pos1 [x y z]` and `/land pos2 [x y z]` set them exactly (where you stand if you leave the coordinates out).
  - The claim is exactly the blocks between the corners. If you pick corners on the ground, a few blocks below and 24 above are added for you.
  - A message with **[Confirm] [Edit corners] [Cancel]** appears. The outline is **white** when it can be confirmed and **red** when it can't (over budget, overlapping someone, in a buffer, someone else's blocks), with the reason.
  - Drawing a box that touches your own claim adds to that claim instead of making a new one.
- **Edit corners** (`/land claim edit`) opens the coordinate editor: corner 1 and corner 2 each have an **X**, **Y** and **Z** button showing the value. Left-click **+1**, right-click **-1**, shift for 10. There's also "corner where I stand", "type corner" (fills in `/land posN` in chat), which way you're facing, and **Confirm**.
- **Resize** opens the same editor with the corners on the claim's current bounding box. Move them out to grow it, in to shrink it.
- **Friends**: left-click a head to change their level, right-click to remove them, or add someone who's online.
- An action bar message shows when you walk into or out of a claim.

## Commands

| Command | What it does |
|---|---|
| `/land` | Open the menu (in the console: show help) |
| `/land autoclaim [on\|off]` (also `/autoclaim`) | Turn auto-claim on or off |
| `/land claim` | Get the claim tool and start drawing a claim |
| `/land pos1 [x y z]`, `/land pos2 [x y z]` | Set a corner to exact coordinates (or where you stand) |
| `/land claim confirm\|edit\|cancel` | Confirm the box, open the coordinate editor, or stop |
| `/land unclaim [confirm]` | Remove the claim you're standing in (or your only claim) |
| `/land resize` | Resize the claim you're standing in (coordinate editor) |
| `/land trust <player> [access\|containers\|build\|manage]` | Trust someone (Build if no level is given). Works for offline players |
| `/land untrust <player>`, `/land trustlist` | Remove someone, see the list |
| `/land info` | What's here: whose claim, its size, trust list, flags, expiry |
| `/land list` | Your claims |
| `/land borders` | Show borders |
| `/land rename <name>` | Rename the claim you're in |
| `/land flags`, `/land flag <flag> on\|off\|default` | See or change the protection flags you're allowed to change |
| `/land donate <blocks>`, `/land withdraw <blocks>` | Put claim blocks into the claim you're standing in, or take them back |

### Admin (`estate:admin`)

Everything that doesn't need a position or a menu works in the server console (without the slash).

| Command | What it does |
|---|---|
| `/land admin reload` | Reload `config/estate.json` and `config/estate-messages.json` |
| `/land admin settings`, `/land admin menu` | List all settings, or change them in a menu (left +1, right -1, shift for 10) |
| `/land admin set <setting> <value>`, `/land admin get <setting>` | Change any setting live; it's saved straight away. Lists are comma-separated; single flag defaults and trust levels use a dot, e.g. `flagDefaults.explosions false`, `trustTiers.access interact,containers` |
| `/land admin bypass [on\|off]` | Build and open everything everywhere |
| `/land admin budget <player> [grant <blocks>\|add <blocks>]` | See a player's budget, or give them extra blocks (up to `maxGrantedBudget`) |
| `/land admin claims [player]` | List all claims, or one player's |
| `/land admin info\|delete <id>`, `/land admin transfer <id> <player>` | See, remove or give away any claim |
| `/land admin exempt <id> [on\|off]` | Keep a claim from expiring |
| `/land admin town create <name> <radius>` | A town zone around you (no buffers inside) |
| `/land admin town create <name> <x1> <z1> <x2> <z2>`, `town remove <name>`, `town list` | Town zones by coordinates |
| `/land admin lookup` | Who placed the block you're looking at, plus the claim and its recent changes |
| `/land admin lookup at <x> <y> <z>`, `lookup player <name>` | The same for a spot, or a player's recent blocks and claim actions |
| `/land admin log [count]` | The latest claim and admin actions |
| `/land admin expire` | Run the inactivity check now |

## Permissions

| Permission | Default | What it does |
|---|---|---|
| `estate:admin` | op level 2 | `/land admin`, the settings menu, changing any flag, managing any claim in bypass mode |

Give it with a permissions mod that supports the Fabric Permission API, like LuckPerms (where it may show as `estate.admin`). Without one, op level 2 is used.

## Config

`config/estate.json`. Change values with `/land admin set` or in the file (then `/land admin reload`). Values missing from an older file are added with their defaults; nothing you set is ever changed by an update.

| Setting | Default | Meaning |
|---|---|---|
| `baseBudget` | `4096` | Blocks of land everyone can claim (counted from above; 4096 = 64x64) |
| `maxTotalBudget` | `4096` | Most blocks one player can hold across all claims without an admin grant |
| `maxGrantedBudget` | `16384` | Most blocks a player with an admin grant can hold |
| `maxClaimsPerPlayer` | `5` | Stops many tiny claims |
| `verticalPadding` | `10` | Blocks above the highest and below the lowest block of a claim |
| `bufferDistance` | `8` | Free space around claims, in blocks. `0` turns buffers off |
| `bufferHeightCap` | `0` (off) | If set (try `12`), blocks next to someone's claim can't go more than this many blocks above its ground level |
| `autoClaimThreshold` | `50` | Blocks in one spot before auto-claim claims them (or the build tip shows) |
| `autoClaimClusterRadius` | `16` | Blocks within this distance count as one build |
| `autoClaimMargin` | `4` | Blocks of land auto-claim adds around a build on every side |
| `autoClaimDefault` | `false` | Auto-claim for new players |
| `buildPrompt` | `true` | Show the build tip to players with auto-claim off |
| `autoExtendVertical` | `true` | Move a claim's top or bottom when the owner builds above or below it |
| `newClaimHeadroom` | `24` | Blocks of room added above corners picked on the ground |
| `inactivityDays` | `30` | Remove claims of players away this long. `0` = never |
| `unclaimOnPermanentBan` | `true` | Remove the claims of permanently banned players |
| `borderParticleSeconds` | `10` | How long borders stay visible |
| `borderStyle` | `blocks` | `blocks` (glass boxes with glowing edges) or `particles` |
| `placedBlockRetentionDays` | `90` | How long placed-block records are kept. `0` = forever |
| `enterLeaveMessages` | `true` | Action bar message when walking into or out of a claim |
| `claimableWorlds` | `minecraft:overworld` | Dimensions where claims can be made |
| `flagDefaults` | see the table above | `true` = protected |
| `ownerEditableFlags` | `explosions, fireSpread, fluidFlowIn, pistonCross, entityDamage` | Flags owners may change on their own claims |
| `trustTiers` | see above | Permissions per level: `interact`, `containers`, `build`, `buffer`, `trust`, `resize` |
| `accessBlocks` | doors, trapdoors, gates, buttons, lever, beds, bell, workstations, ender chest | Blocks that only need Access (ids or `#tags`) |
| `containerBlocks` | none | Extra blocks to treat as containers |
| `autoClaimIgnoredBlocks` | torches, ladders, scaffolding, rails, TNT, fire | Blocks that don't count towards auto-claim |

All text is in `config/estate-messages.json` ([MiniMessage](https://docs.advntr.dev/minimessage/format.html)-style colours like `<gray>`). Messages you haven't changed follow the new wording when Estate is updated; the ones you changed are kept.

## Data

Everything is kept in one SQLite file per world, `<world>/estate/estate.db`: claims, trust lists, flags, town zones, players, placed-block records and the claim/admin log. Every change is committed before the command finishes, and placed-block records are written once a second, so stopping the server at any moment loses at most the last second of block records and never damages claims.

The file has a schema version. A newer Estate upgrades it automatically after making a backup next to it (`estate.db.v1.bak`, ...); an older Estate refuses to touch a file from a newer version instead of guessing.

## For other mods

`dev.cornybirdy.estate.EstateApi` has `ownerAt(level, pos)` and `mayBuild(level, pos, player)`.

## Building

`./gradlew :estate-core:build :estate-fabric:build`. The claim rules are in `estate/core`, which has no Minecraft code (so a Paper version can reuse it) and its own unit tests; `estate/fabric` adds the Fabric side and a headless game test that checks protection on a real server.
