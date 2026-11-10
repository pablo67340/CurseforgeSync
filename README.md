# CurseforgeSync

Tired of manually updating mods on your Forge, NeoForge or Fabric server? Look no further. A
solution to sync with a modpack in CurseForge is here!

Drop one jar in `mods/`, put a modpack's project ID in a config file, and every boot the server
brings its own mods folder in line with the latest release of that pack. Old versions are deleted,
new ones are downloaded, and client-only mods like Sodium, Iris and Mouse Tweaks are left out.

## Quick start

1. Grab `curseforgesync-<version>.jar` from [Releases](../../releases) and put it in `mods/`. The
   same jar works on Forge, NeoForge, Fabric and Quilt, on any Minecraft version from 1.19.2 up.
   Only 1.7.10, 1.12.2 and 1.16.5 servers need a version-specific jar.
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

| Jar | Loaders | Minecraft | Early-load hook |
| --- | --- | --- | --- |
| `curseforgesync-<version>.jar` | Forge | 1.19.2 and newer | `ITransformationService` (ModLauncher 10) |
| | NeoForge | every version | `ITransformationService` (ModLauncher 11) |
| | Fabric, Quilt | every version | `preLaunch` entrypoint |
| `curseforgesync-1.16.5-<version>.jar` | Forge 36 | 1.16.5 | `ITransformationService` (ModLauncher 8) |
| `curseforgesync-1.12.2-<version>.jar` | Forge 14.23 | 1.12.2 | `IFMLLoadingPlugin` coremod |
| `curseforgesync-1.7.10-<version>.jar` | Forge 10.13 | 1.7.10 | `IFMLLoadingPlugin` coremod |

The universal jar never touches a Minecraft class, so it has no Minecraft version of its own. It
reads the version and loader at launch, from the loader itself where possible, otherwise from the
`--fml.mcVersion` launch argument or the server's `libraries/` folder. The log says which:

```
Detected Minecraft 1.21.1 on NeoForge 21.1.264 (version from the loader).
```

That version picks the pack upload to follow. If the pack targets a different Minecraft version
or a different loader, the sync refuses to run rather than filling `mods/` with jars that cannot
load. If the pack was exported with a different loader build than yours, you get a warning.

## How the early load works

A normal mod is far too late to be useful here: by the time any mod's setup code runs, every jar in
`mods/` has already been scanned, opened and had its classes loaded, and changing the folder does
nothing until the next restart.

So on Forge and NeoForge, CurseforgeSync is not registered as a mod at all. It registers as part of
the loader:

- **Forge 1.16.5 and newer, and every NeoForge,** read a
  `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` entry. ModLauncher
  instantiates transformation services and calls `initialize` on them before the mod scanner
  exists, and the sync runs from there.
- **1.7.10 and 1.12.2** use the `FMLCorePlugin` manifest attribute, FML's `IFMLLoadingPlugin`
  coremod mechanism, with a sorting index that puts it first.

One consequence is worth knowing: **CurseforgeSync does not appear in the server's mod list on
Forge or NeoForge**, and that is deliberate. Both skip mod-scanning any jar that provides a
transformation service.

The other consequence is that a sync cannot install a *second* early-loading mod and have it take
effect in the same boot, because the loader read the mods folder for those before we got a turn.
When that happens the sync notices and restarts the server (see `restartMode`).

**Fabric and Quilt** have no hook that runs before mod discovery. The earliest point is the
`preLaunch` entrypoint, by which time the mod set is already fixed. The sync still runs there and
brings `mods/` up to date, but a boot that changed anything ends with a restart so the new mods
are actually loaded. A boot with nothing to change carries straight on. If you would rather avoid
that restart, run the [command-line sync](#trying-it-without-a-server) from your start script
before the server starts. On Windows that also avoids Fabric holding old jars open while the sync
tries to delete them.

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

### Stale copies are removed either way

Both modes also sweep the mods folder for older copies of the mods they just installed, found by
reading mod IDs out of the jars themselves rather than by consulting the state file.

This matters because the state file is bookkeeping, and bookkeeping can go missing — a first sync
onto a server whose mods arrived some other way, a state file that was deleted, a run that ended
before it could save. Installs are decided from the filesystem and happen regardless, so a lost
state file would otherwise leave you with two versions of the same mod, which is fatal to a Forge
server. Mod IDs live inside the jars and cannot drift out of step, so the duplicate gets caught
however the records ended up wrong.

If you deliberately run a different build of a mod that the pack also ships, list its file name in
`protectedFiles`.

## Scripts and configs

Packs ship more than mods. The `overrides/` folder inside a pack carries configs, CraftTweaker
scripts and KubeJS, and those go out of date exactly like a mod does. Turn on `syncOverrides` and
they are reconciled rather than merely copied:

- A file the pack adds is written
- A file the pack changes is updated
- **A script the pack deletes is deleted from your server too**

That last one is the reason this is reconciliation and not a copy. A CraftTweaker script that only
ever gets added will sit there changing recipes long after the pack dropped it, with nothing to say
where it came from.

Every file written is recorded with the hash of what was written, which is what makes your own work
safe. On the next sync a file is one of three things: untouched since CurseforgeSync wrote it, so
it can be updated or removed freely; edited by you; or never managed here at all. Tracked mode
never overwrites or deletes the last two, and says so in the log:

```
Leaving config/tuning.toml alone: the pack ships a different version, but this copy
is not the one CurseforgeSync wrote. Delete it, or switch mode to strict, to take
the pack's copy.
```

Strict mode treats the pack as authoritative and takes its copy regardless. If the pack drops a
file you have edited, tracked mode keeps your version and stops managing it, so you are not warned
about it on every boot.

`overrideFolders` limits which top-level folders are eligible, and defaults to `config`,
`defaultconfigs`, `scripts` and `kubejs`. Nothing outside that list is ever written, so a pack
cannot drop a jar into `mods/` this way.

Run with `dryRun` first if you have tuned your server by hand — it lists every override that would
be added, updated or removed without touching anything.

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
| `minecraftVersion` | `""` | Blank detects it. Only set it if the log says the version could not be detected. |
| `filterClientMods` | `true` | Skip mods that are only useful on the other side. |
| `syncOverrides` | `false` | Also reconcile the pack's `overrides/` folder — configs, CraftTweaker scripts, KubeJS. See [Scripts and configs](#scripts-and-configs). |
| `overrideFolders` | `["config", "defaultconfigs", "scripts", "kubejs"]` | Which override subfolders to copy when the above is on. |
| `datapackFolder` | `""` | Where data packs go. Blank means `<level-name>/datapacks` on a server, `datapacks/` on a client. |
| `dryRun` | `false` | Report what would change and touch nothing. |
| `verbose` | `false` | Log every entry considered, not just the ones that changed. |
| `failOnError` | `false` | Abort start-up if the sync fails. Off so a CurseForge outage cannot keep your server down. |
| `allowCdnFallback` | `true` | See [Mods that cannot be downloaded](#mods-that-cannot-be-downloaded). |
| `restartMode` | `"exit"` | `exit`, `relaunch` or `none` when a sync needs a restart to take effect. |
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

On Forge and NeoForge, installing an ordinary mod needs no restart — the sync finishes before the
loader looks at the folder. Installing a *coremod* or another loader plugin does, because those
are discovered even earlier than we run. The sync detects this by inspecting the jar it just wrote.
On Fabric and Quilt, any change to `mods/` needs a restart, as explained above. Either way the sync
then acts on `restartMode`:

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
java -jar curseforgesync-1.1.0.jar --dir ./myserver --pack 885460 --dry-run
```

```
  --dir <path>    Server directory to sync. Defaults to the current directory.
  --pack <id>     CurseForge project ID, overriding the config for this run.
  --side <s>      server or client. Defaults to server.
  --minecraft <v> Minecraft version, if it cannot be read from <dir>/libraries.
  --loader <l>    forge, neoforge, fabric or quilt, likewise.
  --dry-run       Report what would change without touching anything.
  --verbose, -v   Log every mod considered.
```

The CLI reads the Minecraft version and loader from the server's `libraries/` folder, so point
`--dir` at an installed server, or pass `--minecraft` and `--loader`. It is also handy for
pre-seeding a new server before its first boot, and for syncing a Fabric server from its start
script so the server itself never needs to restart.

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
./gradlew release                 # every jar into build/libs
./gradlew :ports:universal:build  # just the universal jar, with tests
./gradlew printVersions           # what each port targets
```

No ForgeGradle, Loom or ModDevGradle and no Minecraft decompilation, because none of this code
touches a Minecraft class. Each port compiles the same shared sources in `common/` against the
loader SPI for its era, which keeps a full build to a few seconds.

```
common/                     loader-independent core: API client, sync engine, config, side filter
platform/modlauncher-common ModLauncher helpers, mods.toml and the service file
platform/modlauncher/       ITransformationService for ModLauncher 10 and 11 (Forge 1.19.2+, NeoForge)
platform/modlauncher8/      ITransformationService for ModLauncher 8 (Forge 1.16.5)
platform/neoforge/          neoforge.mods.toml
platform/fabric/            preLaunch entrypoint and fabric.mod.json (Fabric, Quilt)
platform/fml*/              IFMLLoadingPlugin coremods for 1.7.10 and 1.12.2
ports/universal/            the one modern jar: every platform tree above that applies
ports/<mcVersion>/          one tiny Gradle project per legacy version
```

### Supporting a new Minecraft version

Usually nothing needs building. The universal jar has no Minecraft version of its own, so a new
release only needs its tag added to the CurseForge upload, which `scripts/upload_curseforge.py`
does by itself for every Minecraft version from 1.19.2 up.

A new port is only needed when a loader changes the interface the hook implements, as ModLauncher
did between 8 and 10. In that case add a `platform/<name>/` tree with the new hook and a
`ports/<name>/gradle.properties` that lists it in `platform=`, and add the port to `settings.gradle`
and the upload script.

## License

All Rights Reserved.
