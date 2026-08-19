package com.curseforgesync.launch;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * The 1.12.2 hook. Same mechanism as the 1.7.10 coremod -- FML's {@code CoreModManager} reads the
 * {@code FMLCorePlugin} manifest attribute and runs us as a tweaker before {@code Loader} scans
 * the mods folder -- only the FML package name changed in 1.8.
 *
 * <p>See the 1.7.10 coremod for why a pack that changes a coremod triggers a restart.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
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
        LegacyCoreModSupport.run(data, "FML coremod (1.12.2)");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
