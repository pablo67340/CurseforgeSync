package com.curseforgesync.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What this particular build targets.
 *
 * <p>The pre-1.17 ports bake their Minecraft version into {@code curseforgesync-build.properties},
 * because LaunchWrapper and ModLauncher 8 give an early hook no reliable way to ask. The universal
 * jar leaves it blank and works the version out at launch instead; see {@link GameInfo}.
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
        if (minecraftVersion.isEmpty()) {
            return modVersion + " (universal)";
        }
        return modVersion + " for Minecraft " + minecraftVersion + " (Forge " + forgeVersion + ")";
    }
}
