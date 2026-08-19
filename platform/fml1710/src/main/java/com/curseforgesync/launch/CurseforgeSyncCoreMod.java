package com.curseforgesync.launch;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * The 1.7.10 hook.
 *
 * <p>There is no ModLauncher here. Instead FML's {@code CoreModManager} walks the mods folder
 * looking for jars whose manifest names an {@code FMLCorePlugin}, instantiates each one, and runs
 * it as a LaunchWrapper tweaker -- all long before {@code Loader} enumerates the folder again to
 * find actual mods. Syncing from {@link #injectData} lands in that window.
 *
 * <p>One caveat the modern ports do not have: coremod discovery has already finished by the time
 * we run, so a pack that adds or changes a coremod needs a restart for it to be seen. The engine
 * detects that case and asks {@code Restarter} to bounce the server.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("CurseforgeSync")
@IFMLLoadingPlugin.SortingIndex(-10000)
@IFMLLoadingPlugin.TransformerExclusions("com.curseforgesync.")
public final class CurseforgeSyncCoreMod implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        LegacyCoreModSupport.run(data, "FML coremod (1.7.10)");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
