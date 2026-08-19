package com.curseforgesync.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs a sync from the command line: {@code java -jar curseforgesync-<mc>-<version>.jar}.
 *
 * <p>Handy for seeding a brand new server before its first boot, for checking what a pack update
 * would do with {@code --dry-run}, and for warming the download cache somewhere with a fast
 * connection. The game is never involved -- this is the same engine the loader hook calls.
 */
public final class Cli {
    private Cli() {
    }

    public static void main(String[] args) {
        Path gameDir = Paths.get(".").toAbsolutePath().normalize();
        Side side = null;
        Boolean dryRun = null;
        boolean verbose = false;
        Integer projectId = null;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--help") || argument.equals("-h")) {
                usage();
                return;
            } else if (argument.equals("--dir") && i + 1 < args.length) {
                gameDir = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if (argument.equals("--side") && i + 1 < args.length) {
                side = Side.parse(args[++i], Side.SERVER);
            } else if (argument.equals("--pack") && i + 1 < args.length) {
                projectId = Integer.valueOf(args[++i]);
            } else if (argument.equals("--dry-run")) {
                dryRun = Boolean.TRUE;
            } else if (argument.equals("--verbose") || argument.equals("-v")) {
                verbose = true;
            } else {
                System.err.println("Unrecognised option: " + argument);
                usage();
                System.exit(2);
                return;
            }
        }

        CfsLog.openLogFile(gameDir);
        CfsLog.setVerbose(verbose);
        BuildInfo build = BuildInfo.load();
        CfsLog.info("CurseforgeSync " + build + " (command line)");

        SyncConfig config;
        try {
            config = SyncConfig.loadOrCreate(gameDir);
        } catch (Exception e) {
            CfsLog.error("Could not read the config", e);
            System.exit(1);
            return;
        }

        if (projectId != null) {
            config.modpackProjectId = projectId.intValue();
        }
        if (dryRun != null) {
            config.dryRun = dryRun.booleanValue();
        }
        if (verbose) {
            config.verbose = true;
        }
        CfsLog.setVerbose(config.verbose);

        if (!config.isConfigured()) {
            CfsLog.error("No modpack set. Pass --pack <projectId> or fill in modpackProjectId in "
                    + SyncConfig.pathIn(gameDir) + ".");
            System.exit(1);
            return;
        }

        Side effectiveSide = side != null ? side : (config.sideAuto ? Side.SERVER : config.side);
        try {
            SyncEngine.Result result = new SyncEngine(gameDir, config, build, effectiveSide, null).run();
            CfsLog.info("Done: " + result.installed.size() + " installed, " + result.removed.size()
                    + " removed, " + result.skippedForSide.size() + " skipped as wrong-side, "
                    + result.failures.size() + " failed.");
            CfsLog.close();
            System.exit(result.failures.isEmpty() ? 0 : 1);
        } catch (Exception e) {
            CfsLog.error("Sync failed", e);
            CfsLog.close();
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("CurseforgeSync -- sync a mods folder against a CurseForge modpack");
        System.out.println();
        System.out.println("  java -jar curseforgesync-<mc>-<version>.jar [options]");
        System.out.println();
        System.out.println("  --dir <path>    Server directory to sync. Defaults to the current directory.");
        System.out.println("  --pack <id>     CurseForge project ID, overriding the config for this run.");
        System.out.println("  --side <s>      server or client. Defaults to server.");
        System.out.println("  --dry-run       Report what would change without touching anything.");
        System.out.println("  --verbose, -v   Log every mod considered.");
        System.out.println("  --help, -h      Show this.");
        System.out.println();
        System.out.println("Settings live in <dir>/config/curseforgesync.json, which is created on first run.");
    }
}
