# Changelog

## 1.0.2

Scripts and configs from the pack are now reconciled, not just copied. Turn on `syncOverrides` to
use this.

Previously the pack's `overrides/` folder was extracted over the top of your game directory, which
only ever added files. A CraftTweaker script the pack had since deleted stayed on your server
forever, still changing recipes, with nothing to say where it came from.

**What is new.**

- A file the pack adds is written, a file it changes is updated, and **a file it deletes is removed
  from your server too**
- Directories left empty by a deletion are tidied up
- `dryRun` now reports override changes as well, so you can see what would happen to your configs
  before enabling anything

**Your own edits are safe.** Every file written is recorded with the hash of what was written, so
each file is one of three things on the next sync: untouched since CurseforgeSync wrote it, edited
by you, or never managed here at all. Tracked mode only ever overwrites or deletes the first, and
says so in the log when it leaves something alone. Strict mode treats the pack as authoritative and
takes its copy regardless. If the pack drops a file you have edited, tracked mode keeps your version
and stops managing it rather than warning you on every boot.

`overrideFolders` still gates which top-level folders are eligible — `config`, `defaultconfigs`,
`scripts` and `kubejs` by default — so a pack cannot write into `mods/` this way.

## 1.0.1

Fixes a bug that could leave two versions of the same mod in `mods/` after a pack update, which
prevents a Forge server from starting.

**The problem.** Installs were decided from the filesystem, but removals were decided only from
`config/curseforgesync-state.json`. If that record was missing or incomplete, the new version of a
mod was downloaded while the old one stayed behind. The most likely way to hit this was the very
first sync on a server whose mods were already installed some other way — a server built from the
pack by hand or by the CurseForge launcher. Nothing in the state file claimed responsibility for
those jars, so when the pack updated a mod, its old copy was left in place.

**The fix.**

- After installing, both `tracked` and `strict` mode now sweep `mods/` for older copies of the mods
  just installed
- Stale copies are identified by reading mod IDs out of the jars themselves, so this works even
  when the state file is missing, deleted, or was never written
- Jars that declare no mod are never matched, and `protectedFiles` still wins, so a build you
  placed by hand on purpose survives

If you were affected, just update and restart — the next sync cleans up the duplicates on its own.
There is no need to clear the mods folder.

Mods that the pack *dropped* entirely are still left alone in `tracked` mode when there is no state
file to identify them, since there is no way to tell them apart from jars you added yourself. They
are harmless, unlike duplicates. Use `strict` mode if you want the mods folder mirrored exactly.

## 1.0.0

Initial release.

- Syncs your mods folder against a CurseForge modpack at every server start-up
- Installs new mods, deletes old versions, leaves hand-added jars alone
- Runs before Forge scans for mods, so changes apply in the same boot
- Skips client-only mods; sorts resource packs and shaders out of `mods/`
- Optional strict mode mirrors the pack exactly
- Dry-run mode and a built-in command-line tool for testing
- Supports Minecraft 1.7.10, 1.12.2, 1.16.5, 1.19.2, 1.20.6, 1.21.11, 26.1.2 and 26.2
