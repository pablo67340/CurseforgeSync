package com.curseforgesync.core;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which Minecraft version and mod loader this run is attached to.
 *
 * <p>The modern jar is one file for every Forge, NeoForge and Fabric version, so none of this can
 * be baked in at build time. The launch hook passes in whatever its loader was able to say, and
 * the gaps are filled from the JVM command line and the server's {@code libraries/} folder, which
 * every installer and launcher populates. The pre-1.17 ports still bake their version in, and that
 * is used as a last resort.
 */
public final class GameInfo {
    public enum Loader {
        FORGE("Forge"),
        NEOFORGE("NeoForge"),
        FABRIC("Fabric"),
        QUILT("Quilt"),
        UNKNOWN("an unknown loader");

        public final String displayName;

        Loader(String displayName) {
            this.displayName = displayName;
        }

        /** Reads the loader out of a pack manifest's loader ID, such as {@code neoforge-21.1.264}. */
        public static Loader fromManifestId(String id) {
            String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("neoforge")) {
                return NEOFORGE;
            }
            if (normalized.startsWith("forge")) {
                return FORGE;
            }
            if (normalized.startsWith("fabric")) {
                return FABRIC;
            }
            if (normalized.startsWith("quilt")) {
                return QUILT;
            }
            return UNKNOWN;
        }

        public static Loader parse(String value) {
            for (Loader loader : values()) {
                if (loader != UNKNOWN && loader.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return loader;
                }
            }
            return UNKNOWN;
        }

        /**
         * Whether a pack built for {@code packLoader} can be installed on this loader. Quilt loads
         * Fabric mods, and on 1.20.1 NeoForge is still a Forge fork that loads Forge mods.
         */
        public boolean canRunPackFor(Loader packLoader, String minecraftVersion) {
            if (this == UNKNOWN || packLoader == UNKNOWN || this == packLoader) {
                return true;
            }
            if (this == QUILT && packLoader == FABRIC) {
                return true;
            }
            boolean forgeFamily = (this == FORGE || this == NEOFORGE) && (packLoader == FORGE || packLoader == NEOFORGE);
            return forgeFamily && "1.20.1".equals(minecraftVersion);
        }
    }

    /** What a launch hook knows first-hand. Anything left blank is detected. */
    public static final class Hints {
        public Loader loader = Loader.UNKNOWN;
        public String minecraftVersion = "";
        public String loaderVersion = "";
        /**
         * True when the loader has already decided which mods to load by the time the sync runs.
         * Fabric works this way, so every change to {@code mods/} needs a restart there.
         */
        public boolean modsAlreadyDiscovered;
    }

    public final String minecraftVersion;
    public final Loader loader;
    public final String loaderVersion;
    public final boolean modsAlreadyDiscovered;
    /** Where {@link #minecraftVersion} came from, for the log. */
    public final String minecraftVersionSource;

    private GameInfo(String minecraftVersion, Loader loader, String loaderVersion, boolean modsAlreadyDiscovered,
                     String minecraftVersionSource) {
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.modsAlreadyDiscovered = modsAlreadyDiscovered;
        this.minecraftVersionSource = minecraftVersionSource;
    }

    public static GameInfo of(String minecraftVersion, Loader loader) {
        return new GameInfo(minecraftVersion, loader, "", false, "given");
    }

    /**
     * @param configuredVersion the config's {@code minecraftVersion}; wins over everything when set
     * @param builtFor          the version baked into a pre-1.17 port, or empty
     */
    public static GameInfo detect(Path gameDir, Hints hints, String configuredVersion, String builtFor) {
        Hints from = hints == null ? new Hints() : hints;
        String mc = from.minecraftVersion == null ? "" : from.minecraftVersion.trim();
        String source = "the loader";
        Loader loader = from.loader == null ? Loader.UNKNOWN : from.loader;
        String loaderVersion = from.loaderVersion == null ? "" : from.loaderVersion.trim();

        Hints commandLine = fromCommandLine(System.getProperty("sun.java.command", ""));
        Hints libraries = fromLibraries(gameDir.resolve("libraries"));

        if (mc.isEmpty() && !commandLine.minecraftVersion.isEmpty()) {
            mc = commandLine.minecraftVersion;
            source = "the launch arguments";
        }
        if (mc.isEmpty() && !libraries.minecraftVersion.isEmpty()) {
            mc = libraries.minecraftVersion;
            source = "the libraries folder";
        }
        if (mc.isEmpty() && builtFor != null && !builtFor.isEmpty()) {
            mc = builtFor;
            source = "this build";
        }
        if (configuredVersion != null && !configuredVersion.trim().isEmpty()) {
            mc = configuredVersion.trim();
            source = "the config";
        }

        for (Hints fallback : new Hints[] {commandLine, libraries}) {
            if (loader == Loader.UNKNOWN) {
                loader = fallback.loader;
            }
            if (loaderVersion.isEmpty() && loader == fallback.loader) {
                loaderVersion = fallback.loaderVersion;
            }
        }
        return new GameInfo(mc, loader, loaderVersion, from.modsAlreadyDiscovered, mc.isEmpty() ? "nowhere" : source);
    }

    /**
     * Forge and NeoForge both pass {@code --fml.mcVersion} and their own version to ModLauncher, and
     * on a normal launch those end up in {@code sun.java.command}, argument files included.
     */
    static Hints fromCommandLine(String command) {
        Hints hints = new Hints();
        String[] parts = command == null ? new String[0] : command.trim().split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            String argument = parts[i];
            String value = parts[i + 1];
            if (argument.equals("--fml.mcVersion")) {
                hints.minecraftVersion = value;
            } else if (argument.equals("--fml.neoForgeVersion")) {
                hints.loader = Loader.NEOFORGE;
                hints.loaderVersion = value;
            } else if (argument.equals("--fml.forgeVersion") && hints.loader != Loader.NEOFORGE) {
                hints.loader = Loader.FORGE;
                hints.loaderVersion = value;
            }
        }
        return hints;
    }

    /**
     * Server installers lay the loader and the game out under {@code libraries/} with the version
     * in the folder name. This is what catches wrappers like Arclight that launch through a jar of
     * their own and hide the FML arguments. A server that has been upgraded keeps the old folders,
     * so the most recently written one wins.
     */
    static Hints fromLibraries(Path librariesDir) {
        Hints hints = new Hints();
        File libraries = librariesDir.toFile();
        if (!libraries.isDirectory()) {
            return hints;
        }

        String neoForge = newestChild(new File(libraries, "net/neoforged/neoforge"));
        String legacyNeoForge = newestChild(new File(libraries, "net/neoforged/forge"));
        String forge = newestChild(new File(libraries, "net/minecraftforge/forge"));
        String fabricGame = newestChild(new File(libraries, "net/fabricmc/intermediary"));
        String fabricLoader = newestChild(new File(libraries, "net/fabricmc/fabric-loader"));
        String vanilla = newestChild(new File(libraries, "net/minecraft/server"));

        if (neoForge != null) {
            hints.loader = Loader.NEOFORGE;
            hints.loaderVersion = neoForge;
            hints.minecraftVersion = minecraftFromNeoForge(neoForge);
        } else if (legacyNeoForge != null) {
            // NeoForge's 1.20.1 builds kept Forge's "<mc>-<version>" layout.
            hints.loader = Loader.NEOFORGE;
            hints.minecraftVersion = beforeDash(legacyNeoForge);
            hints.loaderVersion = afterDash(legacyNeoForge);
        } else if (forge != null) {
            hints.loader = Loader.FORGE;
            hints.minecraftVersion = beforeDash(forge);
            hints.loaderVersion = afterDash(forge);
        } else if (fabricLoader != null) {
            hints.loader = Loader.FABRIC;
            hints.loaderVersion = fabricLoader;
            hints.minecraftVersion = fabricGame == null ? "" : fabricGame;
        }
        if (hints.minecraftVersion.isEmpty() && vanilla != null) {
            hints.minecraftVersion = beforeDash(vanilla);
        }
        return hints;
    }

    /**
     * NeoForge numbers its builds after the game: 21.1.x is 1.21.1, 21.0.x is 1.21, 20.4.x is 1.20.4.
     * From the 26.x versions on, the game version itself makes up the first three parts.
     */
    static String minecraftFromNeoForge(String neoForgeVersion) {
        String[] parts = beforeDash(neoForgeVersion).split("\\.");
        if (parts.length < 2) {
            return "";
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major >= 25) {
                int patch = parts.length >= 4 ? Integer.parseInt(parts[2]) : 0;
                return major + "." + minor + (patch == 0 ? "" : "." + patch);
            }
            return "1." + major + (minor == 0 ? "" : "." + minor);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String newestChild(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        List<File> folders = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) {
                folders.add(child);
            }
        }
        File newest = null;
        for (File folder : folders) {
            if (newest == null || folder.lastModified() > newest.lastModified()) {
                newest = folder;
            }
        }
        return newest == null ? null : newest.getName();
    }

    private static String beforeDash(String value) {
        int dash = value.indexOf('-');
        return dash < 0 ? value : value.substring(0, dash);
    }

    private static String afterDash(String value) {
        int dash = value.indexOf('-');
        return dash < 0 ? "" : value.substring(dash + 1);
    }

    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(minecraftVersion.isEmpty() ? "an unknown Minecraft version" : "Minecraft " + minecraftVersion);
        text.append(" on ").append(loader.displayName);
        if (!loaderVersion.isEmpty()) {
            text.append(' ').append(loaderVersion);
        }
        return text.toString();
    }
}
