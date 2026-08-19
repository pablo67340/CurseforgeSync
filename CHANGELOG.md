# Changelog

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
