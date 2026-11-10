package com.curseforgesync.launch;

import com.curseforgesync.core.CfsLog;
import com.curseforgesync.core.CurseforgeSync;
import com.curseforgesync.core.GameInfo;
import com.curseforgesync.core.Side;

import cpw.mods.modlauncher.api.IEnvironment;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Pulls the two facts the sync needs out of ModLauncher's environment.
 *
 * <p>Shared by the 1.16.5 service and the 1.19.2+ one: {@code IEnvironment} and its key set have
 * not changed between ModLauncher 8 and 10, only {@code ITransformationService} has.
 */
final class ModLauncherSupport {
    private ModLauncherSupport() {
    }

    /**
     * ModLauncher fills in {@code GAMEDIR} while processing arguments, which happens after every
     * service's {@code onLoad} but before any {@code initialize}. Calling this from
     * {@code initialize} is what makes the value reliable.
     */
    static Path gameDir(IEnvironment environment) {
        try {
            Optional<Path> fromEnvironment = environment.getProperty(IEnvironment.Keys.GAMEDIR.get());
            if (fromEnvironment.isPresent()) {
                return fromEnvironment.get();
            }
        } catch (Throwable t) {
            CfsLog.debug("Could not read GAMEDIR from the launch environment: " + t);
        }
        return CurseforgeSync.resolveGameDir(null);
    }

    /**
     * Forge and NeoForge both run on ModLauncher, and the jar cannot tell them apart by its hook
     * alone, so it looks for each one's FML loader class. If FML has already initialised, its
     * {@code VersionInfo} also gives the exact game and loader version; if not, those are left for
     * {@link GameInfo} to find on the command line or in {@code libraries/}.
     */
    static GameInfo.Hints hints() {
        GameInfo.Hints hints = new GameInfo.Hints();
        String[][] loaders = {
                {"net.neoforged.fml.loading.FMLLoader", "NEOFORGE", "neoForgeVersion"},
                {"net.minecraftforge.fml.loading.FMLLoader", "FORGE", "forgeVersion"},
        };
        for (String[] candidate : loaders) {
            Class<?> fmlLoader;
            try {
                fmlLoader = Class.forName(candidate[0], false, ModLauncherSupport.class.getClassLoader());
            } catch (Throwable t) {
                continue;
            }
            hints.loader = GameInfo.Loader.valueOf(candidate[1]);
            try {
                Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
                if (versionInfo != null) {
                    hints.minecraftVersion = stringProperty(versionInfo, "mcVersion");
                    hints.loaderVersion = stringProperty(versionInfo, candidate[2]);
                }
            } catch (Throwable t) {
                CfsLog.debug("FML has not published its version info yet: " + t);
            }
            break;
        }
        return hints;
    }

    private static String stringProperty(Object target, String accessor) {
        try {
            Object value = target.getClass().getMethod(accessor).invoke(target);
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static Side side(IEnvironment environment) {
        String launchTarget = null;
        try {
            Optional<String> fromEnvironment = environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get());
            if (fromEnvironment.isPresent()) {
                launchTarget = fromEnvironment.get();
            }
        } catch (Throwable t) {
            CfsLog.debug("Could not read LAUNCHTARGET from the launch environment: " + t);
        }
        return CurseforgeSync.sideFromLaunchTarget(launchTarget);
    }
}
