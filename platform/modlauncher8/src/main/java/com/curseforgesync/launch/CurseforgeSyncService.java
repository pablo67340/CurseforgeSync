package com.curseforgesync.launch;

import com.curseforgesync.core.CurseforgeSync;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The 1.16.5 hook. Identical in purpose to the 1.19.2+ service, but ModLauncher 8 declares
 * {@code beginScanning} as an abstract {@code void} rather than a defaulted method that returns
 * resources, so it has to be implemented explicitly.
 *
 * <p>See {@code platform/modlauncher} for why {@code initialize} is the right place to sync.
 */
public final class CurseforgeSyncService implements ITransformationService {
    @Override
    public String name() {
        return "curseforgesync";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        // The game directory is not in the environment yet; see initialize().
    }

    @Override
    public void initialize(IEnvironment environment) {
        CurseforgeSync.bootstrap(
                ModLauncherSupport.gameDir(environment),
                ModLauncherSupport.side(environment),
                "ModLauncher 8");
    }

    @Override
    public void beginScanning(IEnvironment environment) {
        // FML does its mod discovery in its own beginScanning; we have nothing to add to the scan.
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }
}
