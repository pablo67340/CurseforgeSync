package com.curseforgesync.launch;

import com.curseforgesync.core.CurseforgeSync;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The hook that gets CurseforgeSync running before Forge looks for mods (1.19.2 and newer).
 *
 * <p>Forge's {@code ModDirTransformerDiscoverer} sweeps the mods folder for jars that declare a
 * {@code cpw.mods.modlauncher.api.ITransformationService}, and ModLauncher loads what it finds
 * into the service layer. Every service is then taken through {@code onLoad} and
 * {@code initialize} before any of them is asked to {@code beginScanning} -- and FML's own mod
 * discovery is what happens inside its {@code beginScanning}. Syncing from {@code initialize}
 * therefore lands in the gap between "the loader has started" and "the loader has decided which
 * mods exist", which is the whole trick: jars added here are picked up on this very boot, and jars
 * removed here are not open in any handle yet.
 *
 * <p>A side effect of being discovered this way is that Forge deliberately excludes this jar from
 * normal mod scanning, so CurseforgeSync never appears in the server's mod list. That is intended;
 * it means it can never cause a client/server mod mismatch.
 *
 * <p>No transformers are registered. This is a loading hook, not a coremod.
 */
public final class CurseforgeSyncService implements ITransformationService {
    @Override
    public String name() {
        return "curseforgesync";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        // Nothing to check here. The game directory is not populated in the environment yet,
        // so the real work waits for initialize().
    }

    @Override
    public void initialize(IEnvironment environment) {
        CurseforgeSync.bootstrap(
                ModLauncherSupport.gameDir(environment),
                ModLauncherSupport.side(environment),
                "ModLauncher");
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }
}
