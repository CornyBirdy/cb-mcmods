# cornybirdy's Minecraft mods

Server-side mods for **Paper** and **Fabric**. Players never need to install anything: every mod works with plain vanilla clients.

## Mods

| Mod | What it does | Paper | Fabric | Download |
|---|---|---|---|---|
| [**Corpes**](corpes/) | When you die, your body lies where you died and holds all your items. Right-click it to get them back. | 26.1+ | 26.3 | [Releases](https://github.com/CornyBirdy/Paper-Corpes/releases?q=corpes&expanded=true) |
| [**Estate**](estate/) | Land claims with a flat budget for everyone, automatic claims around builds, buffers against spite builds, trust levels and a `/land` menu. | - | 26.3 | [Releases](https://github.com/CornyBirdy/Paper-Corpes/releases?q=estate&expanded=true) |
| [**Gatekeeper**](gatekeeper/) | Turn ender chests, the End, the Nether, netherite armor and netherite tools on or off with `/gatekeeper` while the server runs, also from the console. No restart needed. Also opt-in PvP with `/pvp`. (Used to be called EndLock.) | 26.1+ | 26.3 | [Releases](https://github.com/CornyBirdy/Paper-Corpes/releases?q=gatekeeper&expanded=true) |

Click a mod's name for its features, commands and config.

## Which file do I download?

Every release has a jar for each server type it supports (Estate is Fabric only for now). Pick the one for your server:

| Your server | File | Where it goes |
|---|---|---|
| Paper (or a Paper fork like Purpur) | `Paper<Mod>-x.y.z.jar`, e.g. `PaperGatekeeper-1.0.0.jar` | `plugins/` |
| Fabric | `Fabric<Mod>-x.y.z.jar`, e.g. `FabricGatekeeper-1.0.0.jar` | `mods/`, together with [Fabric API](https://modrinth.com/mod/fabric-api) |

All mods need Java 25. Releases are marked as pre-releases until they've been tested in game.

Want the newest build before it's released? Open the [Actions tab](https://github.com/CornyBirdy/Paper-Corpes/actions/workflows/build.yml), click the latest green run and download the mod from **Artifacts** at the bottom.

## Repository layout

```
corpes/            Corpes
  paper/             Paper plugin
  fabric/            Fabric mod
  README.md          features, commands, config
  release-notes.md   text shown on its GitHub releases
gatekeeper/        Gatekeeper (same layout)
estate/            Estate (Fabric only so far)
  core/              claim rules without Minecraft code, shared by future ports
  fabric/            Fabric mod
```

## Building

```
./gradlew build
```

Needs JDK 25. Each mod's jars end up in `<mod>/paper/build/libs/` and `<mod>/fabric/build/libs/`. To build just one mod, run for example `./gradlew :gatekeeper-paper:build :gatekeeper-fabric:build`. GitHub Actions builds every mod on every push.
The Fabric versions (Minecraft, loader, Fabric API) are in `gradle.properties` and shared by all mods.

### Releasing a mod

Open the **Actions** tab, pick **Release**, click **Run workflow**, choose the mod and enter a version (for example `1.0.1`). You can also push a tag named `<mod>-v<version>`, like `gatekeeper-v1.0.1`. The workflow builds that mod's two jars, creates the tag and attaches them to a new GitHub release named after the mod. Releases are published as pre-releases; once a version is tested in game, edit the release on GitHub and tick "Set as the latest release".

### Adding a new mod

1. Create `<mod>/paper/` and `<mod>/fabric/` (copy an existing mod's `build.gradle.kts` and change the jar name).
2. Add the mod's name to the list in `settings.gradle.kts`.
3. Add it to the artifact uploads in `.github/workflows/build.yml` and to the choices and the name lookup in `.github/workflows/release.yml`.
4. Add a `README.md` and `release-notes.md` in its folder and a row to the table above.
