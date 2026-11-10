package com.curseforgesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The universal jar has no Minecraft version of its own, so picking the right pack upload and
 * refusing the wrong one both rest on reading the version and loader correctly at launch.
 */
class GameInfoTest {
    @Test
    void readsNeoForgeLaunchArguments() {
        GameInfo.Hints hints = GameInfo.fromCommandLine("cpw.mods.bootstraplauncher.BootstrapLauncher "
                + "--launchTarget forgeserver --fml.fmlVersion 4.0.45 --fml.mcVersion 1.21.1 "
                + "--fml.neoForgeVersion 21.1.264 --fml.neoFormVersion 20240808.144430 nogui");

        assertEquals("1.21.1", hints.minecraftVersion);
        assertEquals(GameInfo.Loader.NEOFORGE, hints.loader);
        assertEquals("21.1.264", hints.loaderVersion);
    }

    @Test
    void readsForgeLaunchArguments() {
        GameInfo.Hints hints = GameInfo.fromCommandLine("cpw.mods.bootstraplauncher.BootstrapLauncher "
                + "--launchTarget forgeserver --fml.forgeVersion 47.4.0 --fml.mcVersion 1.20.1 "
                + "--fml.forgeGroup net.minecraftforge --fml.mcpVersion 20230612.114412");

        assertEquals("1.20.1", hints.minecraftVersion);
        assertEquals(GameInfo.Loader.FORGE, hints.loader);
        assertEquals("47.4.0", hints.loaderVersion);
    }

    @Test
    void findsNothingOnAPlainCommandLine() {
        GameInfo.Hints hints = GameInfo.fromCommandLine("arclight-server.jar nogui");

        assertEquals("", hints.minecraftVersion);
        assertEquals(GameInfo.Loader.UNKNOWN, hints.loader);
    }

    @Test
    void mapsNeoForgeVersionsToTheGameVersion() {
        assertEquals("1.21.1", GameInfo.minecraftFromNeoForge("21.1.264"));
        assertEquals("1.21", GameInfo.minecraftFromNeoForge("21.0.167"));
        assertEquals("1.20.4", GameInfo.minecraftFromNeoForge("20.4.237"));
        assertEquals("1.21.11", GameInfo.minecraftFromNeoForge("21.11.17-beta"));
        assertEquals("26.1", GameInfo.minecraftFromNeoForge("26.1.0.4-beta"));
        assertEquals("26.1.2", GameInfo.minecraftFromNeoForge("26.1.2.7"));
        assertEquals("", GameInfo.minecraftFromNeoForge("garbage"));
    }

    /** The layout on an Arclight server, where the command line carries no FML arguments. */
    @Test
    void readsANeoForgeServersLibrariesFolder(@TempDir Path gameDir) throws IOException {
        Files.createDirectories(gameDir.resolve("libraries/net/neoforged/neoforge/21.1.264"));
        Files.createDirectories(gameDir.resolve("libraries/net/minecraft/server/1.21.1-20240808.144430"));

        GameInfo.Hints hints = GameInfo.fromLibraries(gameDir.resolve("libraries"));

        assertEquals(GameInfo.Loader.NEOFORGE, hints.loader);
        assertEquals("21.1.264", hints.loaderVersion);
        assertEquals("1.21.1", hints.minecraftVersion);
    }

    @Test
    void readsForgeAndFabricLibrariesFolders(@TempDir Path temp) throws IOException {
        Path forge = temp.resolve("forge");
        Files.createDirectories(forge.resolve("libraries/net/minecraftforge/forge/1.20.1-47.4.0"));
        Path fabric = temp.resolve("fabric");
        Files.createDirectories(fabric.resolve("libraries/net/fabricmc/fabric-loader/0.16.14"));
        Files.createDirectories(fabric.resolve("libraries/net/fabricmc/intermediary/1.21.1"));

        GameInfo.Hints forgeHints = GameInfo.fromLibraries(forge.resolve("libraries"));
        GameInfo.Hints fabricHints = GameInfo.fromLibraries(fabric.resolve("libraries"));

        assertEquals(GameInfo.Loader.FORGE, forgeHints.loader);
        assertEquals("1.20.1", forgeHints.minecraftVersion);
        assertEquals("47.4.0", forgeHints.loaderVersion);
        assertEquals(GameInfo.Loader.FABRIC, fabricHints.loader);
        assertEquals("1.21.1", fabricHints.minecraftVersion);
    }

    @Test
    void theLoaderWinsAndTheConfigWinsOverEverything(@TempDir Path gameDir) throws IOException {
        Files.createDirectories(gameDir.resolve("libraries/net/neoforged/neoforge/21.1.264"));
        GameInfo.Hints fromLoader = new GameInfo.Hints();
        fromLoader.loader = GameInfo.Loader.NEOFORGE;
        fromLoader.minecraftVersion = "1.21.1";

        GameInfo detected = GameInfo.detect(gameDir, fromLoader, "", "");
        GameInfo overridden = GameInfo.detect(gameDir, fromLoader, "1.21.2", "");
        GameInfo fromBuild = GameInfo.detect(gameDir.resolve("empty"), new GameInfo.Hints(), "", "1.12.2");

        assertEquals("1.21.1", detected.minecraftVersion);
        assertEquals("21.1.264", detected.loaderVersion);
        assertEquals("1.21.2", overridden.minecraftVersion);
        assertEquals("the config", overridden.minecraftVersionSource);
        assertEquals("1.12.2", fromBuild.minecraftVersion);
    }

    @Test
    void readsTheLoaderOutOfAPackManifest() {
        assertEquals(GameInfo.Loader.NEOFORGE, GameInfo.Loader.fromManifestId("neoforge-21.1.264"));
        assertEquals(GameInfo.Loader.FORGE, GameInfo.Loader.fromManifestId("forge-47.4.0"));
        assertEquals(GameInfo.Loader.FABRIC, GameInfo.Loader.fromManifestId("fabric-0.16.14"));
        assertEquals(GameInfo.Loader.QUILT, GameInfo.Loader.fromManifestId("quilt-0.29.0"));
        assertEquals(GameInfo.Loader.UNKNOWN, GameInfo.Loader.fromManifestId(""));
    }

    @Test
    void refusesPacksForAnotherLoader() {
        GameInfo.Loader fabric = GameInfo.Loader.FABRIC;
        GameInfo.Loader neoForge = GameInfo.Loader.NEOFORGE;
        GameInfo.Loader forge = GameInfo.Loader.FORGE;

        assertFalse(fabric.canRunPackFor(neoForge, "1.21.1"));
        assertFalse(neoForge.canRunPackFor(fabric, "1.21.1"));
        assertFalse(forge.canRunPackFor(neoForge, "1.21.1"));
        assertTrue(neoForge.canRunPackFor(neoForge, "1.21.1"));
        assertTrue(GameInfo.Loader.QUILT.canRunPackFor(fabric, "1.21.1"));
        assertTrue(forge.canRunPackFor(neoForge, "1.20.1"));
        assertTrue(GameInfo.Loader.UNKNOWN.canRunPackFor(neoForge, "1.21.1"));
    }
}
