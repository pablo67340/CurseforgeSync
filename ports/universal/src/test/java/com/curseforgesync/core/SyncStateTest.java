package com.curseforgesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncStateTest {
    @Test
    void survivesARoundTrip(@TempDir Path gameDir) throws IOException {
        SyncState written = new SyncState();
        written.packProjectId = 12345;
        written.packFileId = 67890;
        written.packName = "Test Pack";
        written.packVersion = "1.4";
        written.stampNow();

        SyncState.Entry entry = new SyncState.Entry();
        entry.projectId = 238222;
        entry.fileId = 4712868;
        entry.fileName = "jei-1.19.2-11.6.0.1024.jar";
        entry.slug = "jei";
        entry.sha1 = "deadbeef";
        entry.side = Side.BOTH;
        written.installed.add(entry);
        written.save(gameDir);

        SyncState read = SyncState.load(gameDir);

        assertEquals(12345, read.packProjectId);
        assertEquals(67890, read.packFileId);
        assertEquals("Test Pack", read.packName);
        assertEquals(1, read.installed.size());
        assertEquals("jei-1.19.2-11.6.0.1024.jar", read.installed.get(0).fileName);
        assertEquals(Side.BOTH, read.installed.get(0).side);
        assertTrue(read.installedFileNames().contains("jei-1.19.2-11.6.0.1024.jar"));
        assertTrue(read.byProjectId().containsKey(238222));
        assertFalse(read.syncedAt.isEmpty());
    }

    @Test
    void anAbsentStateFileMeansNothingIsOwned(@TempDir Path gameDir) {
        SyncState state = SyncState.load(gameDir);

        assertTrue(state.installed.isEmpty());
        assertEquals(0, state.packFileId);
    }

    /**
     * A truncated state file must not be treated as "these jars are mine to delete". Falling back
     * to an empty state makes tracked mode leave every existing jar alone, which is the safe way
     * to be wrong.
     */
    @Test
    void aCorruptStateFileFallsBackToOwningNothing(@TempDir Path gameDir) throws IOException {
        Path file = SyncState.pathIn(gameDir);
        Files.createDirectories(file.getParent());
        Files.write(file, "{\"installed\": [{\"fileName\": \"trunc".getBytes(StandardCharsets.UTF_8));

        SyncState state = SyncState.load(gameDir);

        assertTrue(state.installed.isEmpty());
    }

    @Test
    void writesTheConfigTemplateOnFirstRunAndReadsItBack(@TempDir Path gameDir) throws IOException {
        SyncConfig first = SyncConfig.loadOrCreate(gameDir);
        assertFalse(first.isConfigured());
        assertTrue(Files.exists(SyncConfig.pathIn(gameDir)));

        // Fill in a project ID the way an admin would, then confirm it survives a reload.
        Path config = SyncConfig.pathIn(gameDir);
        String edited = new String(Files.readAllBytes(config), StandardCharsets.UTF_8)
                .replace("\"modpackProjectId\": 0", "\"modpackProjectId\": 1243993")
                .replace("\"mode\": \"tracked\"", "\"mode\": \"strict\"");
        Files.write(config, edited.getBytes(StandardCharsets.UTF_8));

        SyncConfig reloaded = SyncConfig.loadOrCreate(gameDir);

        assertTrue(reloaded.isConfigured());
        assertEquals(1243993, reloaded.modpackProjectId);
        assertEquals(SyncConfig.Mode.STRICT, reloaded.mode);
        assertEquals(SyncConfig.DEFAULT_API_KEY, reloaded.effectiveApiKey());
    }
}
