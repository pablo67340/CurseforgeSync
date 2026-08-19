# CurseforgeSync

Tired of manually updating mods in your Forge server? Look no further. A solution to sync with a
modpack in CurseForge is here!

Drop one jar in `mods/`, put a modpack's project ID in a config file, and every boot the server
brings its own mods folder in line with the latest release of that pack — before Forge has scanned
for a single mod. Old versions are deleted, new ones are downloaded, and client-only mods like
Rubidium, Oculus and Mouse Tweaks are left out.

## Quick start

1. Grab the jar for your Minecraft version from [Releases](../../releases) and put it in `mods/`.
2. Start the server once. It writes `config/curseforgesync.json` and stops short of syncing,
   because it does not know which pack you want yet.
3. Open the pack's CurseForge page and copy the number from the **Project ID** box on the right.
4. Put it in the config and restart:

```json
{
  "modpackProjectId": 885460
}
```

That is the whole setup. From here on, every start-up checks CurseForge for a newer release of the
pack and reconciles the mods folder against it.

Before trusting it with a live server, do a dry run first — see [Trying it without a
server](#trying-it-without-a-server).

## Supported versions

Each build targets one Minecraft version and is compiled against the newest Forge for it.

| Minecraft | Forge | Early-load hook |
| --- | --- | --- |
| 1.7.10 | 10.13.4.1614 | `IFMLLoadingPlugin` coremod |
| 1.12.2 | 14.23.5.2860 | `IFMLLoadingPlugin` coremod |
| 1.16.5 | 36.2.42 | `ITransformationService` (ModLauncher 8) |
| 1.19.2 | 43.5.2 | `ITransformationService` (ModLauncher 10) |
| 1.20.6 | 50.2.10 | `ITransformationService` |
| 1.21.11 | 61.2.0 | `ITransformationService` |
| 26.1.2 | 64.1.0 | `ITransformationService` |
| 26.2 | 65.1.1 | `ITransformationService` |

Use the jar matching your server. If the pack turns out to target a different Minecraft version,
the sync refuses to run rather than filling `mods/` with jars that cannot load.

## How the early load works

A normal Forge mod is far too late to be useful here: by the time `FMLCommonSetupEvent` fires,
every jar in `mods/` has already been scanned, opened and had its classes loaded, and changing the
folder does nothing until the next restart.

So CurseforgeSync is not registered as a mod at all. It registers as part of the loader:

- **1.16.5 and newer** ship a `META-INF/services/cpw.mods.modlauncher.api.ITransformationService`
  entry. ModLauncher instantiates transformation services and calls `initialize` on them before
  Forge's mod scanner exists, and the sync runs from there.
- **1.7.10 and 1.12.2** use the `FMLCorePlugin` manifest attribute, FML's `IFMLLoadingPlugin`
  coremod mechanism, with a sorting index that puts it first.

One consequence is worth knowing: **CurseforgeSync does not appear in the server's mod list**, and
that is deliberate. Forge skips mod-scanning any jar that provides a transformation service.

The other consequence is that a sync cannot install a *second* early-loading mod and have it take
effect in the same boot, because the loader read the mods folder for coremods before we got a turn.
When that happens the sync notices and restarts the server (see `restartMode`).

## Which mods get skipped

A CurseForge pack manifest is a flat list of project IDs and nothing else. It does not say what
each entry is or which side it belongs on, because the launcher that consumes it is always a
client. CurseforgeSync works it out from two things.

**What the file is** comes from the project's CurseForge class. Packs routinely mix in resource
packs and shaders — "All of Create" is 230 mods, 42 resource packs and 2 shader packs — and those
are sorted into `resourcepacks/` and `shaderpacks/`, never `mods/`. On a server they are skipped
outright. Bukkit plugins and worlds are never installed at all.

**Which side a mod belongs on** is decided in this order, and the log records the reason for every
decision:

1. `clientOnlyMods` / `serverOnlyMods` in your config.
2. A curated list of about 100 well-known client-only projects — renderers, shader loaders, zoom
   and input mods, HUD and tooltip mods, sound and cosmetic mods, Discord presence. Every slug in
   it is checked against the live CurseForge API, so it contains no typos or dead projects.
3. The author's own Client/Server environment tags on the uploaded file.

If none of those match, the mod is installed. That asymmetry is on purpose: guessing "client-only"
wrongly costs you a missing dependency and a crash, while guessing "both" wrongly costs a few
megabytes. Anything with even a partial server-side component — JEI, Jade, JourneyMap, FerriteCore,
ModernFix — is deliberately left off the curated list.

Set `"filterClientMods": false` to install everything the pack lists.

## Tracked vs strict

`mode` controls how aggressive the cleanup is.

**`tracked`** (default) only deletes jars CurseforgeSync installed itself, which it records in
`config/curseforgesync-state.json`. Updating a mod still removes the old version, because the old
file is in that record. Anything you dropped in by hand stays where it is.

**`strict`** makes `mods/` an exact mirror of the pack and deletes every other jar, apart from
names you list in `protectedFiles`. It deliberately stops at `mods/` — wiping unrecognised resource
packs or data packs off a server is far more likely to be a mistake than a fix.

Delete the state file and tracked mode forgets everything, treating every existing jar as
hand-placed.

## Configuration

`config/curseforgesync.json`. Written with comments on first run; only `modpackProjectId` is
required.

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master switch. |
| `modpackProjectId` | `0` | The pack to follow. `0` disables the sync. |
| `modpackFileId` | `0` | Pin one specific upload. `0` follows the newest matching release. |
| `releaseChannel` | `"release"` | Lowest upload quality to accept: `release`, `beta` or `alpha`. |
| `mode` | `"tracked"` | `tracked` or `strict`, as above. |
| `side` | `"auto"` | `auto`, `server` or `client`. `auto` reads the launch target. |
| `filterClientMods` | `true` | Skip mods that are only useful on the other side. |
| `syncOverrides` | `false` | Also copy the pack's `overrides/` folder over local files. Off because it will overwrite server tuning you did by hand. |
| `overrideFolders` | `["config", "defaultconfigs", "scripts", "kubejs"]` | Which override subfolders to copy when the above is on. |
| `datapackFolder` | `""` | Where data packs go. Blank means `<level-name>/datapacks` on a server, `datapacks/` on a client. |
| `dryRun` | `false` | Report what would change and touch nothing. |
| `verbose` | `false` | Log every entry considered, not just the ones that changed. |
| `failOnError` | `false` | Abort start-up if the sync fails. Off so a CurseForge outage cannot keep your server down. |
| `allowCdnFallback` | `true` | See [Mods that cannot be downloaded](#mods-that-cannot-be-downloaded). |
| `restartMode` | `"exit"` | `exit`, `relaunch` or `none` when a coremod arrives mid-sync. |
| `restartExitCode` | `0` | Exit code used by `exit`. Set this to whatever your panel treats as "restart me". |
| `apiKey` | `""` | Your own key from console.curseforge.com. Blank uses the built-in one. `CURSEFORGE_API_KEY` works too. |
| `connectTimeoutSeconds` | `20` | |
| `readTimeoutSeconds` | `120` | |
| `maxAttempts` | `3` | Retries per request. |
| `downloadThreads` | `4` | Parallel downloads, capped at 16. |
| `clientOnlyMods` | `[]` | Force these to be treated as client-side. Slug or numeric project ID. |
| `serverOnlyMods` | `[]` | Force these to be treated as server-side. |
| `forceIncludeMods` | `[]` | Always install, whatever the filter thinks. |
| `excludeMods` | `[]` | Never install, even though the pack lists them. |
| `protectedFiles` | `[]` | File names strict mode must leave alone. |

Slugs are the last part of a CurseForge project URL: `curseforge.com/minecraft/mc-mods/rubidium`
is `rubidium`.

The mod ships with a working API key so it runs out of the box. Rate limits are per key, so on a
busy host it is worth getting your own free one from
[console.curseforge.com](https://console.curseforge.com) and setting `apiKey`.

## Restarts

Installing an ordinary mod needs no restart — the sync finishes before Forge looks at the folder.
Installing a *coremod* or another transformation service does, because those are discovered even
earlier than we run. The sync detects this by inspecting the jar it just wrote and acts on
`restartMode`:

- `exit` (default) stops the JVM with `restartExitCode`, letting your panel or start script bring
  it back. This is the right choice almost everywhere.
- `relaunch` spawns a fresh JVM with the same command line and then stops this one. Use it only if
  nothing is supervising the process.
- `none` carries on booting and the new coremod takes effect at the next manual restart.

## Mods that cannot be downloaded

Some authors opt out of third-party distribution, and the CurseForge API then refuses to hand out a
download link for their files. With `allowCdnFallback` on (the default) CurseforgeSync falls back
to the same CDN address the website gives a human clicking "Download". Turn it off to respect the
opt-out; the affected files are then listed in `logs/curseforgesync-manual-downloads.txt` for you
to install by hand.

## Trying it without a server

Every jar is also a runnable CLI, which is the easiest way to see what a sync would do before
letting it near a live server:

```
java -jar curseforgesync-1.19.2-1.0.0.jar --dir ./myserver --pack 885460 --dry-run
```

```
  --dir <path>    Server directory to sync. Defaults to the current directory.
  --pack <id>     CurseForge project ID, overriding the config for this run.
  --side <s>      server or client. Defaults to server.
  --dry-run       Report what would change without touching anything.
  --verbose, -v   Log every mod considered.
```

It is also handy for pre-seeding a new server before its first boot.

## Files it creates

| Path | |
| --- | --- |
| `config/curseforgesync.json` | Your settings. |
| `config/curseforgesync-state.json` | What the last sync installed. Delete to make tracked mode forget. |
| `logs/curseforgesync.log` | A full log of every run, written before Minecraft's logging exists. |
| `logs/curseforgesync-manual-downloads.txt` | Only when something needs installing by hand. |
| `curseforgesync-cache/` | Downloaded pack archives and mod jars, reused across runs. Safe to delete. |

## Building

Needs a JDK 17 or newer; Gradle provisions the JDK 8 toolchain for the legacy ports itself.

```
./gradlew release        # all eight ports into build/libs
./gradlew :ports:1.19.2:build   # just one
./gradlew printVersions  # what each port targets
```

No ForgeGradle and no Minecraft decompilation, because none of this code touches a Minecraft class.
Each port compiles the same shared sources in `common/` against the loader SPI for its era, which
keeps a full eight-version build to a few seconds.

```
common/                  version-independent core: API client, sync engine, config, side filter
platform/modlauncher*/   ITransformationService entry points for 1.16.5+
platform/fml*/           IFMLLoadingPlugin coremods for 1.7.10 and 1.12.2
ports/<mcVersion>/       one tiny Gradle project per version, all settings in gradle.properties
```

## License

All Rights Reserved.
