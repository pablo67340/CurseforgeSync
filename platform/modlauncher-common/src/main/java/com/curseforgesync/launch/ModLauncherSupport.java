package com.curseforgesync.launch;

import com.curseforgesync.core.CfsLog;
import com.curseforgesync.core.CurseforgeSync;
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
