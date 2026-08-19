package com.curseforgesync.launch;

import com.curseforgesync.core.CfsLog;
import com.curseforgesync.core.CurseforgeSync;
import com.curseforgesync.core.Side;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared plumbing for the two LaunchWrapper-era ports.
 *
 * <p>1.7.10 and 1.12.2 differ only in where FML's classes live -- {@code cpw.mods.fml} became
 * {@code net.minecraftforge.fml} in 1.8 -- so the coremod class has to be written twice, but
 * everything it does lives here.
 */
public final class LegacyCoreModSupport {
    /**
     * FML's own idea of which side is booting, looked up reflectively so one implementation
     * serves both package layouts. By the time a coremod's {@code injectData} runs, the tweaker
     * has already told {@code FMLLaunchHandler} which side it is, making this far more reliable
     * than picking apart the command line.
     */
    private static final String[] LAUNCH_HANDLERS = {
            "net.minecraftforge.fml.relauncher.FMLLaunchHandler",
            "cpw.mods.fml.relauncher.FMLLaunchHandler",
    };

    private LegacyCoreModSupport() {
    }

    public static void run(Map<String, Object> data, String platform) {
        CurseforgeSync.bootstrap(gameDir(data), side(), platform);
    }

    private static Path gameDir(Map<String, Object> data) {
        Object location = data == null ? null : data.get("mcLocation");
        if (location instanceof File) {
            return ((File) location).toPath().toAbsolutePath().normalize();
        }
        return CurseforgeSync.resolveGameDir(null);
    }

    private static Side side() {
        for (String className : LAUNCH_HANDLERS) {
            try {
                Class<?> handler = Class.forName(className, true, LegacyCoreModSupport.class.getClassLoader());
                Method sideMethod = handler.getMethod("side");
                Object fmlSide = sideMethod.invoke(null);
                if (fmlSide == null) {
                    continue;
                }
                Object isServer = fmlSide.getClass().getMethod("isServer").invoke(fmlSide);
                return Boolean.TRUE.equals(isServer) ? Side.SERVER : Side.CLIENT;
            } catch (Throwable t) {
                CfsLog.debug("Could not ask " + className + " which side is booting: " + t);
            }
        }
        return CurseforgeSync.sideFromCommandLine();
    }
}
