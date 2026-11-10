package com.curseforgesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the paths where a hostile or just badly-formed archive could do damage. */
class SafetyTest {
    @Test
    void fileNamesFromTheApiCannotEscapeTheModsFolder() {
        assertEquals("evil.jar", SyncEngine.safeName("../../evil.jar"));
        assertEquals("evil.jar", SyncEngine.safeName("/etc/cron.d/evil.jar"));
        assertEquals("evil.jar", SyncEngine.safeName("C:\\Windows\\evil.jar"));
        assertEquals("unnamed.jar", SyncEngine.safeName(""));
        assertEquals("unnamed.jar", SyncEngine.safeName(null));
        assertEquals("jei-1.19.2-11.6.0.1024.jar", SyncEngine.safeName("jei-1.19.2-11.6.0.1024.jar"));
    }

    @Test
    void zipEntriesCannotEscapeTheGameDirectory(@TempDir Path temp) {
        Path base = temp.resolve("server");

        assertNotNull(Jars.resolveSafely(base, "config/jei.toml"));
        assertNull(Jars.resolveSafely(base, "../outside.txt"));
        assertNull(Jars.resolveSafely(base, "config/../../outside.txt"));
    }

    @Test
    void overrideWalkHonoursTheFolderAllowList(@TempDir Path temp) throws IOException {
        Path zip = temp.resolve("pack.zip");
        writeZip(zip,
                "overrides/config/jei.toml", "allowed",
                "overrides/mods/sneaky.jar", "not allowed",
                "overrides/scripts/a.zs", "allowed",
                "overrides/../escape.txt", "blocked");

        List<String> visited = new ArrayList<>();
        Jars.forEachInFolder(zip, "overrides", List.of("config", "scripts"),
                (relativePath, content) -> visited.add(relativePath));

        assertEquals(List.of("config/jei.toml", "scripts/a.zs"), visited);
    }

    /**
     * With no allow-list the traversal entry does get visited, so the guard at the point the path
     * is resolved is the one that has to stop it.
     */
    @Test
    void overrideWalkStillLeavesTraversalToTheResolveGuard(@TempDir Path temp) throws IOException {
        Path zip = temp.resolve("pack.zip");
        writeZip(zip, "overrides/../escape.txt", "blocked");

        List<String> visited = new ArrayList<>();
        Jars.forEachInFolder(zip, "overrides", List.of(), (relativePath, content) -> visited.add(relativePath));

        assertEquals(List.of("../escape.txt"), visited);
        assertNull(Jars.resolveSafely(temp.resolve("server"), visited.get(0)));
    }

    @Test
    void detectsJarsThatHaveToBeLoadedBeforeUs(@TempDir Path temp) throws IOException {
        Path service = temp.resolve("connector.jar");
        writeZip(service, "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
                "com.example.Service");
        Path locator = temp.resolve("locator.jar");
        writeZip(locator, "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
                "com.example.Locator");
        Path coremod = temp.resolve("legacy.jar");
        writeZip(coremod, "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nFMLCorePlugin: com.example.CoreMod\n\n");
        Path ordinary = temp.resolve("ordinary.jar");
        writeZip(ordinary, "META-INF/mods.toml", "modLoader=\"javafml\"");

        assertTrue(Jars.isEarlyLoader(service));
        assertTrue(Jars.isEarlyLoader(locator));
        assertTrue(Jars.isEarlyLoader(coremod));
        assertFalse(Jars.isEarlyLoader(ordinary));
        assertFalse(Jars.isEarlyLoader(temp.resolve("does-not-exist.jar")));
    }

    @Test
    void sha1MatchesTheValuesCurseForgePublishes(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("payload.bin");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Jars.sha1(file));
    }

    private static void writeZip(Path target, String... nameThenContent) throws IOException {
        OutputStream raw = Files.newOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(raw);
        try {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenContent[i]));
                zip.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }
}
