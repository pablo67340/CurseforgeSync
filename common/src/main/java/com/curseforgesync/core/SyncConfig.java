package com.curseforgesync.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads, validates and (on first run) writes {@code config/curseforgesync.json}. */
public final class SyncConfig {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * The API key CurseForge hands out for community tooling. Admins can override it with their
     * own key from console.curseforge.com via {@code apiKey} or the {@code CURSEFORGE_API_KEY}
     * environment variable, which is worth doing on a busy host because rate limits are per key.
     */
    public static final String DEFAULT_API_KEY =
            "$2a$10$bL4bIL5pUWqfcO7KQtnMReakwtfHbNKh6v1uTpKlzhwoueEJQnPnm";

    /** How the mods folder is reconciled against the pack. */
    public enum Mode {
        /**
         * Only touch jars this mod installed, recorded in the state file. Anything an admin
         * dropped in by hand stays put.
         */
        TRACKED,
        /** Make the mods folder an exact mirror of the pack; delete every jar that is not in it. */
        STRICT;

        static Mode parse(String value) {
            if (value != null && value.trim().toUpperCase(Locale.ROOT).equals("STRICT")) {
                return STRICT;
            }
            return TRACKED;
        }
    }

    /** What to do when a sync installs a jar that had to be present before the loader started. */
    public enum RestartMode {
        /** Stop the JVM and let the panel or start script bring it back up. */
        EXIT,
        /** Spawn a fresh JVM with the same command line, then stop this one. */
        RELAUNCH,
        /** Carry on booting; the new jar takes effect on the next manual restart. */
        NONE;

        static RestartMode parse(String value) {
            if (value == null) {
                return EXIT;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (RestartMode mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return EXIT;
        }
    }

    public boolean enabled = true;
    public int modpackProjectId;
    /** 0 means "whatever the newest matching upload is". */
    public int modpackFileId;
    public int releaseChannel = CurseForgeApi.RELEASE;
    public Mode mode = Mode.TRACKED;
    public Side side = Side.SERVER;
    public boolean sideAuto = true;
    public boolean filterClientMods = true;
    public boolean syncOverrides;
    public List<String> overrideFolders = new ArrayList<String>(Arrays.asList("config", "defaultconfigs", "scripts", "kubejs"));
    /**
     * Where data packs listed by the pack go. Blank resolves to {@code <level-name>/datapacks} on a
     * server and {@code datapacks/} on a client, which is where each side actually reads them from.
     */
    public String datapackFolder = "";
    public boolean dryRun;
    public boolean verbose;
    public boolean failOnError;
    public boolean allowCdnFallback = true;
    public RestartMode restartMode = RestartMode.EXIT;
    public int restartExitCode;
    public String apiKey = "";
    public String apiBaseUrl = "https://api.curseforge.com";
    public int connectTimeoutSeconds = 20;
    public int readTimeoutSeconds = 120;
    public int maxAttempts = 3;
    public int downloadThreads = 4;

    public Set<String> clientOnlyMods = new LinkedHashSet<String>();
    public Set<String> serverOnlyMods = new LinkedHashSet<String>();
    public Set<String> forceIncludeMods = new LinkedHashSet<String>();
    public Set<String> excludeMods = new LinkedHashSet<String>();
    /** Jar names in the mods folder that STRICT mode must never delete. */
    public Set<String> protectedFiles = new LinkedHashSet<String>();

    public String effectiveApiKey() {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey.trim();
        }
        String fromEnvironment = System.getenv("CURSEFORGE_API_KEY");
        if (fromEnvironment != null && !fromEnvironment.trim().isEmpty()) {
            return fromEnvironment.trim();
        }
        return DEFAULT_API_KEY;
    }

    public boolean isConfigured() {
        return modpackProjectId > 0;
    }

    public static Path pathIn(Path gameDir) {
        return gameDir.resolve("config").resolve("curseforgesync.json");
    }

    public static SyncConfig loadOrCreate(Path gameDir) throws IOException {
        Path file = pathIn(gameDir);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.write(file, template().getBytes(UTF8));
            CfsLog.info("Wrote a starter config to " + file);
            return new SyncConfig();
        }
        SyncConfig config = new SyncConfig();
        config.readFrom(Json.parseObject(new String(Files.readAllBytes(file), UTF8)));
        return config;
    }

    void readFrom(Map<String, Object> json) {
        enabled = Json.bool(json, "enabled", enabled);
        modpackProjectId = Json.intVal(json, "modpackProjectId", modpackProjectId);
        modpackFileId = Json.intVal(json, "modpackFileId", modpackFileId);
        releaseChannel = parseChannel(Json.str(json, "releaseChannel", "release"));
        mode = Mode.parse(Json.str(json, "mode", "tracked"));

        String rawSide = Json.str(json, "side", "auto");
        sideAuto = rawSide == null || rawSide.trim().isEmpty() || rawSide.trim().equalsIgnoreCase("auto");
        side = Side.parse(rawSide, Side.SERVER);

        filterClientMods = Json.bool(json, "filterClientMods", filterClientMods);
        syncOverrides = Json.bool(json, "syncOverrides", syncOverrides);
        if (json.containsKey("overrideFolders")) {
            overrideFolders = Json.strings(json, "overrideFolders");
        }
        datapackFolder = Json.str(json, "datapackFolder", datapackFolder);
        dryRun = Json.bool(json, "dryRun", dryRun);
        verbose = Json.bool(json, "verbose", verbose);
        failOnError = Json.bool(json, "failOnError", failOnError);
        allowCdnFallback = Json.bool(json, "allowCdnFallback", allowCdnFallback);
        restartMode = RestartMode.parse(Json.str(json, "restartMode", "exit"));
        restartExitCode = Json.intVal(json, "restartExitCode", restartExitCode);
        apiKey = Json.str(json, "apiKey", apiKey);
        apiBaseUrl = Json.str(json, "apiBaseUrl", apiBaseUrl);
        connectTimeoutSeconds = Json.intVal(json, "connectTimeoutSeconds", connectTimeoutSeconds);
        readTimeoutSeconds = Json.intVal(json, "readTimeoutSeconds", readTimeoutSeconds);
        maxAttempts = Json.intVal(json, "maxAttempts", maxAttempts);
        downloadThreads = Math.max(1, Math.min(16, Json.intVal(json, "downloadThreads", downloadThreads)));

        clientOnlyMods = normalize(Json.strings(json, "clientOnlyMods"));
        serverOnlyMods = normalize(Json.strings(json, "serverOnlyMods"));
        forceIncludeMods = normalize(Json.strings(json, "forceIncludeMods"));
        excludeMods = normalize(Json.strings(json, "excludeMods"));
        protectedFiles = new LinkedHashSet<String>(Json.strings(json, "protectedFiles"));
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> out = new LinkedHashSet<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static int parseChannel(String value) {
        if (value == null) {
            return CurseForgeApi.RELEASE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("alpha")) {
            return CurseForgeApi.ALPHA;
        }
        if (normalized.equals("beta")) {
            return CurseForgeApi.BETA;
        }
        return CurseForgeApi.RELEASE;
    }

    /** The commented default file written on first start-up. */
    public static String template() {
        return "{\n"
                + "  // ---------------------------------------------------------------------------\n"
                + "  // CurseforgeSync\n"
                + "  //\n"
                + "  // Point this at a CurseForge modpack and the mods folder is brought in line with\n"
                + "  // it every time the server boots, before Forge scans for mods.\n"
                + "  //\n"
                + "  // The only setting you have to fill in is modpackProjectId. Find it in the\n"
                + "  // \"Project ID\" box on the right-hand side of the pack's CurseForge page.\n"
                + "  // ---------------------------------------------------------------------------\n"
                + "\n"
                + "  \"enabled\": true,\n"
                + "\n"
                + "  // CurseForge project ID of the modpack to follow. 0 disables the sync.\n"
                + "  \"modpackProjectId\": 0,\n"
                + "\n"
                + "  // Pin to one specific pack upload. 0 follows the newest matching release.\n"
                + "  \"modpackFileId\": 0,\n"
                + "\n"
                + "  // Lowest upload quality to accept when following the newest: release, beta or alpha.\n"
                + "  \"releaseChannel\": \"release\",\n"
                + "\n"
                + "  // tracked = only remove jars CurseforgeSync installed itself (recorded in\n"
                + "  //           config/curseforgesync-state.json), leaving hand-added jars alone.\n"
                + "  // strict  = make the mods folder an exact mirror of the pack and delete\n"
                + "  //           anything else, except names listed in protectedFiles.\n"
                + "  \"mode\": \"tracked\",\n"
                + "\n"
                + "  // auto, server or client. auto detects the running side from the launch target.\n"
                + "  \"side\": \"auto\",\n"
                + "\n"
                + "  // Skip mods that are only useful on the other side (Rubidium, Oculus, Mouse\n"
                + "  // Tweaks and friends on a server). Every decision is written to the log, and\n"
                + "  // the lists at the bottom of this file override it.\n"
                + "  \"filterClientMods\": true,\n"
                + "\n"
                + "  // Also copy the pack's overrides/ folder (configs, scripts) over local files.\n"
                + "  // Off by default: it will overwrite server-side tuning you have done by hand.\n"
                + "  \"syncOverrides\": false,\n"
                + "  \"overrideFolders\": [\"config\", \"defaultconfigs\", \"scripts\", \"kubejs\"],\n"
                + "\n"
                + "  // Packs list resource packs and shaders alongside mods; those are sorted into\n"
                + "  // resourcepacks/ and shaderpacks/ and skipped entirely on a server. Data packs\n"
                + "  // have no fixed home, so blank means <level-name>/datapacks on a server and\n"
                + "  // datapacks/ on a client. Set a path here to override.\n"
                + "  \"datapackFolder\": \"\",\n"
                + "\n"
                + "  // Report what would change without touching a single file.\n"
                + "  \"dryRun\": false,\n"
                + "\n"
                + "  // Log every mod considered, not just the ones that changed.\n"
                + "  \"verbose\": false,\n"
                + "\n"
                + "  // Abort start-up if the sync fails. Off by default so a CurseForge outage\n"
                + "  // cannot keep your server down.\n"
                + "  \"failOnError\": false,\n"
                + "\n"
                + "  // Some authors opt out of third-party distribution and the API then refuses to\n"
                + "  // hand out a download link. With this on, CurseforgeSync falls back to the same\n"
                + "  // CDN address the website uses. Turn it off to respect the opt-out and get a\n"
                + "  // list of files to install by hand instead.\n"
                + "  \"allowCdnFallback\": true,\n"
                + "\n"
                + "  // Installing a coremod or loader plugin needs a restart, because those are read\n"
                + "  // before CurseforgeSync gets a turn. exit = stop and let your panel restart us,\n"
                + "  // relaunch = start a fresh JVM ourselves, none = carry on and pick it up later.\n"
                + "  \"restartMode\": \"exit\",\n"
                + "  \"restartExitCode\": 0,\n"
                + "\n"
                + "  // Your own key from console.curseforge.com. Blank uses the built-in one.\n"
                + "  // The CURSEFORGE_API_KEY environment variable works too.\n"
                + "  \"apiKey\": \"\",\n"
                + "  \"apiBaseUrl\": \"https://api.curseforge.com\",\n"
                + "\n"
                + "  \"connectTimeoutSeconds\": 20,\n"
                + "  \"readTimeoutSeconds\": 120,\n"
                + "  \"maxAttempts\": 3,\n"
                + "  \"downloadThreads\": 4,\n"
                + "\n"
                + "  // Overrides for the side filter, by CurseForge slug (the last part of the\n"
                + "  // project URL) or numeric project ID. These win over everything else.\n"
                + "  \"clientOnlyMods\": [],\n"
                + "  \"serverOnlyMods\": [],\n"
                + "\n"
                + "  // Always install these, whatever the filter thinks.\n"
                + "  \"forceIncludeMods\": [],\n"
                + "\n"
                + "  // Never install these, even though the pack lists them.\n"
                + "  \"excludeMods\": [],\n"
                + "\n"
                + "  // Jar file names strict mode must leave alone.\n"
                + "  \"protectedFiles\": []\n"
                + "}\n";
    }
}
