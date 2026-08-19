package com.curseforgesync.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What this particular build targets.
 *
 * <p>CurseforgeSync runs before Forge has worked out anything about the game, so it cannot ask the
 * runtime which Minecraft version it is attached to. Instead each port bakes its own numbers into
 * {@code curseforgesync-build.properties} at build time, which is also what lets the engine pick
 * the right pack upload and refuse a pack built for a different version.
 */
public final class BuildInfo {
    public final String minecraftVersion;
    public final String forgeVersion;
    public final String modVersion;
    public final String platform;

    private BuildInfo(String minecraftVersion, String forgeVersion, String modVersion, String platform) {
        this.minecraftVersion = minecraftVersion;
        this.forgeVersion = forgeVersion;
        this.modVersion = modVersion;
        this.platform = platform;
    }

    public static BuildInfo load() {
        Properties properties = new Properties();
        InputStream in = BuildInfo.class.getResourceAsStream("/curseforgesync-build.properties");
        if (in != null) {
            try {
                properties.load(in);
            } catch (IOException e) {
                CfsLog.debug("Could not read the build descriptor: " + e);
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
        }
        return new BuildInfo(
                properties.getProperty("minecraft_version", ""),
                properties.getProperty("forge_version", ""),
                properties.getProperty("mod_version", "dev"),
                properties.getProperty("platform", "unknown"));
    }

    /** Only used by tests, which need a descriptor without a jar to read it from. */
    public static BuildInfo of(String minecraftVersion, String forgeVersion, String modVersion, String platform) {
        return new BuildInfo(minecraftVersion, forgeVersion, modVersion, platform);
    }

    @Override
    public String toString() {
        return modVersion + " for Minecraft " + minecraftVersion + " (Forge " + forgeVersion + ")";
    }
}
