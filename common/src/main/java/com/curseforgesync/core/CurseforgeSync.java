package com.curseforgesync.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Locale;

/**
 * The single entry point every version-specific hook calls into.
 *
 * <p>Each supported Minecraft version reaches this from a different place -- a ModLauncher
 * transformation service on 1.16.5 and newer, an FML coremod on 1.7.10 and 1.12.2 -- but they all
 * arrive here with the same two facts: where the game directory is and which side is booting.
 * Everything past this point is plain Java with no game classes involved.
 */
public final class CurseforgeSync {
    private static boolean alreadyRan;

    private CurseforgeSync() {
    }

    public static synchronized void bootstrap(Path gameDir, Side detectedSide, String platform) {
        if (alreadyRan) {
            // Some loaders construct a service more than once; syncing twice would be wasteful
            // at best and would fight with itself at worst.
            return;
        }
        alreadyRan = true;

        CfsLog.openLogFile(gameDir);
        BuildInfo build = BuildInfo.load();
        CfsLog.info("CurseforgeSync " + build + " starting via " + platform + ".");

        SyncConfig config;
        try {
            config = SyncConfig.loadOrCreate(gameDir);
        } catch (Exception e) {
            CfsLog.error("Could not read " + SyncConfig.pathIn(gameDir) + "; skipping the sync", e);
            CfsLog.close();
            return;
        }
        CfsLog.setVerbose(config.verbose);

        if (!config.enabled) {
            CfsLog.info("Disabled in the config; leaving the mods folder alone.");
            CfsLog.close();
            return;
        }
        if (!config.isConfigured()) {
            CfsLog.warn("No modpack set. Put the pack's CurseForge project ID into modpackProjectId in "
                    + SyncConfig.pathIn(gameDir) + " and restart.");
            CfsLog.close();
            return;
        }

        Side side = config.sideAuto ? detectedSide : config.side;
        CfsLog.info("Running as " + side.name().toLowerCase(Locale.ROOT) + " in " + gameDir.toAbsolutePath()
                + " (mode: " + config.mode.name().toLowerCase(Locale.ROOT) + ").");

        long startedAt = System.currentTimeMillis();
        SyncEngine.Result result = null;
        try {
            result = new SyncEngine(gameDir, config, build, side, selfJarName()).run();
        } catch (Exception e) {
            CfsLog.error("Sync failed", e);
            if (config.failOnError) {
                CfsLog.error("failOnError is on, so start-up is being aborted.");
                CfsLog.close();
                throw new IllegalStateException("CurseforgeSync could not sync the mods folder", e);
            }
            CfsLog.warn("Carrying on with the mods that are already installed.");
            CfsLog.close();
            return;
        }

        report(result, System.currentTimeMillis() - startedAt);

        if (!result.failures.isEmpty() && config.failOnError) {
            CfsLog.error("failOnError is on and " + result.failures.size()
                    + " mod(s) could not be synced, so start-up is being aborted.");
            CfsLog.close();
            throw new IllegalStateException("CurseforgeSync could not sync " + result.failures.size() + " mod(s)");
        }

        if (result.restartRequired) {
            Restarter.restart(config.restartMode, config.restartExitCode, result.restartReason);
        }
        CfsLog.close();
    }

    private static void report(SyncEngine.Result result, long elapsedMillis) {
        if (!result.changedAnything()) {
            CfsLog.info("Already in sync (" + elapsedMillis + "ms). Handing off to Forge.");
        } else {
            CfsLog.info("Synced in " + elapsedMillis + "ms: " + result.installed.size() + " installed, "
                    + result.removed.size() + " removed.");
        }
        if (!result.skippedForSide.isEmpty()) {
            CfsLog.info(result.skippedForSide.size() + " mod(s) skipped as wrong-side. Set verbose to true "
                    + "to list them, or filterClientMods to false to install them anyway.");
            for (String line : result.skippedForSide) {
                CfsLog.debug("  skipped: " + line);
            }
        }
        for (String failure : result.failures) {
            CfsLog.warn("  problem: " + failure);
        }
    }

    /**
     * Works out this jar's own file name so the sync never deletes the thing it is running from.
     * Strict mode would otherwise happily remove it, since it is not part of the pack.
     */
    private static String selfJarName() {
        try {
            CodeSource source = CurseforgeSync.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            Path path = "jar".equals(uri.getScheme())
                    ? Paths.get(URI.create(uri.getSchemeSpecificPart().split("!")[0]))
                    : Paths.get(uri);
            String name = path.getFileName().toString();
            return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name : null;
        } catch (Exception e) {
            // Running from a classes directory in a dev environment, or a loader that hides the
            // code source. Either way there is no jar of ours in mods/ to protect.
            return null;
        }
    }

    /** Convenience for the hooks: resolve a game directory from a possibly-null candidate. */
    public static Path resolveGameDir(Path candidate) {
        if (candidate != null) {
            return candidate;
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** Best-effort side detection from a ModLauncher launch target such as {@code forgeserver}. */
    public static Side sideFromLaunchTarget(String launchTarget) {
        if (launchTarget != null) {
            String normalized = launchTarget.toLowerCase(Locale.ROOT);
            if (normalized.contains("server") || normalized.contains("data")) {
                return Side.SERVER;
            }
            if (normalized.contains("client")) {
                return Side.CLIENT;
            }
        }
        return sideFromCommandLine();
    }

    /**
     * Falls back to the JVM's own command line. A dedicated server is launched through a main
     * class or jar with "server" in the name, or with {@code --nogui}, on every host that matters.
     */
    public static Side sideFromCommandLine() {
        String command = System.getProperty("sun.java.command", "");
        String normalized = command.toLowerCase(Locale.ROOT);
        if (normalized.contains("nogui") || normalized.contains("server")) {
            return Side.SERVER;
        }
        if (normalized.contains("client") || normalized.contains("launchwrapper")) {
            return Side.CLIENT;
        }
        try {
            Class.forName("net.minecraft.client.main.Main", false, CurseforgeSync.class.getClassLoader());
            return Side.CLIENT;
        } catch (Throwable ignored) {
            // The client entry point is not reachable, so assume a server. This is also the safer
            // default: a server that installs a client mod merely wastes memory.
        }
        return Side.SERVER;
    }

    /** Lets a hook surface a failure without pulling in the core's exception types. */
    public static RuntimeException wrap(String message, IOException cause) {
        return new IllegalStateException(message, cause);
    }
}
