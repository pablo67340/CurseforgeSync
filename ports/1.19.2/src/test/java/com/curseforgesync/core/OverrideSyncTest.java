package com.curseforgesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overrides are reconciled, not just copied, and the rules decide whether someone's hand-edited
 * server config survives a pack update. Worth pinning precisely.
 */
class OverrideSyncTest {
    @Test
    void addsFilesThePackShipsAndRecordsThem(@TempDir Path temp) throws IOException {
        Path pack = pack(temp, "overrides/scripts/recipes.zs", "// v1");
        SyncState state = new SyncState();

        sync(temp, pack, state, SyncConfig.Mode.TRACKED);

        assertEquals("// v1", read(temp.resolve("scripts/recipes.zs")));
        assertEquals(1, state.overrides.size());
        assertEquals("scripts/recipes.zs", state.overrides.get(0).path);
    }

    @Test
    void updatesAFileThePackChangedWhenNobodyHasTouchedIt(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/scripts/recipes.zs", "// v1"), state, SyncConfig.Mode.TRACKED);

        sync(temp, pack(temp, "overrides/scripts/recipes.zs", "// v2"), state, SyncConfig.Mode.TRACKED);

        assertEquals("// v2", read(temp.resolve("scripts/recipes.zs")));
    }

    /** The gap that prompted all this: a script dropped from the pack used to live on forever. */
    @Test
    void deletesAScriptThePackNoLongerShips(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/scripts/recipes.zs", "// v1",
                "overrides/scripts/removed.zs", "// going away"), state, SyncConfig.Mode.TRACKED);
        assertTrue(Files.exists(temp.resolve("scripts/removed.zs")));

        sync(temp, pack(temp, "overrides/scripts/recipes.zs", "// v1"), state, SyncConfig.Mode.TRACKED);

        assertFalse(Files.exists(temp.resolve("scripts/removed.zs")));
        assertTrue(Files.exists(temp.resolve("scripts/recipes.zs")));
        assertEquals(1, state.overrides.size());
    }

    @Test
    void tidiesUpADirectoryLeftEmptyByADeletion(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/kubejs/server_scripts/loot.js", "// x"), state,
                SyncConfig.Mode.TRACKED);
        assertTrue(Files.isDirectory(temp.resolve("kubejs/server_scripts")));

        sync(temp, pack(temp), state, SyncConfig.Mode.TRACKED);

        assertFalse(Files.exists(temp.resolve("kubejs/server_scripts")));
    }

    @Test
    void trackedModeWillNotClobberAnEditYouMade(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/config/tuning.toml", "spawn-rate = 1"), state,
                SyncConfig.Mode.TRACKED);
        Files.write(temp.resolve("config/tuning.toml"), "spawn-rate = 99".getBytes(StandardCharsets.UTF_8));

        sync(temp, pack(temp, "overrides/config/tuning.toml", "spawn-rate = 2"), state,
                SyncConfig.Mode.TRACKED);

        assertEquals("spawn-rate = 99", read(temp.resolve("config/tuning.toml")));
    }

    @Test
    void trackedModeKeepsYourEditEvenWhenThePackDropsTheFile(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/config/tuning.toml", "spawn-rate = 1"), state,
                SyncConfig.Mode.TRACKED);
        Files.write(temp.resolve("config/tuning.toml"), "spawn-rate = 99".getBytes(StandardCharsets.UTF_8));

        sync(temp, pack(temp), state, SyncConfig.Mode.TRACKED);

        assertEquals("spawn-rate = 99", read(temp.resolve("config/tuning.toml")));
        assertTrue(state.overrides.isEmpty(), "an edited file the pack dropped stops being managed");
    }

    /** A file that was already there before CurseforgeSync ever ran is not ours to overwrite. */
    @Test
    void trackedModeLeavesAPreExistingFileAlone(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("config"));
        Files.write(temp.resolve("config/tuning.toml"), "mine".getBytes(StandardCharsets.UTF_8));

        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/config/tuning.toml", "theirs"), state, SyncConfig.Mode.TRACKED);

        assertEquals("mine", read(temp.resolve("config/tuning.toml")));
        assertTrue(state.overrides.isEmpty());
    }

    @Test
    void strictModeTakesThePacksCopyRegardless(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("config"));
        Files.write(temp.resolve("config/tuning.toml"), "mine".getBytes(StandardCharsets.UTF_8));

        SyncState state = new SyncState();
        sync(temp, pack(temp, "overrides/config/tuning.toml", "theirs"), state, SyncConfig.Mode.STRICT);

        assertEquals("theirs", read(temp.resolve("config/tuning.toml")));
    }

    @Test
    void aDryRunChangesNothing(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        Path pack = pack(temp, "overrides/scripts/recipes.zs", "// v1");

        SyncConfig config = config(SyncConfig.Mode.TRACKED);
        engine(temp, config).applyOverrides(pack, manifest(), state, true);

        assertFalse(Files.exists(temp.resolve("scripts/recipes.zs")));
        assertTrue(state.overrides.isEmpty());
    }

    @Test
    void neverWritesOutsideTheAllowedFolders(@TempDir Path temp) throws IOException {
        SyncState state = new SyncState();
        Path pack = pack(temp,
                "overrides/mods/sneaky.jar", "nope",
                "overrides/scripts/fine.zs", "yes");

        sync(temp, pack, state, SyncConfig.Mode.STRICT);

        assertFalse(Files.exists(temp.resolve("mods/sneaky.jar")));
        assertTrue(Files.exists(temp.resolve("scripts/fine.zs")));
    }

    // ------------------------------------------------------------- helpers

    private static void sync(Path gameDir, Path packZip, SyncState state, SyncConfig.Mode mode) {
        engine(gameDir, config(mode)).applyOverrides(packZip, manifest(), state, false);
    }

    private static SyncEngine engine(Path gameDir, SyncConfig config) {
        return new SyncEngine(gameDir, config, BuildInfo.load(), Side.SERVER, null);
    }

    private static SyncConfig config(SyncConfig.Mode mode) {
        SyncConfig config = new SyncConfig();
        config.mode = mode;
        config.syncOverrides = true;
        return config;
    }

    private static PackManifest manifest() {
        return PackManifest.parse("{\"overrides\": \"overrides\", \"files\": []}");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path pack(Path temp, String... nameThenContent) throws IOException {
        // A fresh file each time: the same pack path with new content would otherwise be
        // indistinguishable from the previous one in any caching layer.
        Path target = Files.createTempFile(temp, "pack", ".zip");
        OutputStream raw = Files.newOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(raw);
        try {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenContent[i]));
                zip.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        return target;
    }
}
