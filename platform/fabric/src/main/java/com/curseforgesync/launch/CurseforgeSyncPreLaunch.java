package com.curseforgesync.launch;

import com.curseforgesync.core.CurseforgeSync;
import com.curseforgesync.core.GameInfo;
import com.curseforgesync.core.Side;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.util.Optional;

/**
 * The Fabric and Quilt hook: {@code preLaunch} is the earliest point either loader runs mod code.
 *
 * <p>Unlike Forge, Fabric has no hook that runs before it decides which mods exist. By the time
 * this is called the mod set is fixed, so the sync still brings {@code mods/} up to date but any
 * change it makes only takes effect on the next start. {@code modsAlreadyDiscovered} tells the
 * engine to ask for that restart, which {@code restartMode} then carries out.
 *
 * <p>Nothing here touches a Minecraft class, so the one jar works on every game version.
 */
public final class CurseforgeSyncPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        FabricLoader fabric = FabricLoader.getInstance();
        boolean quilt = fabric.isModLoaded("quilt_loader");

        GameInfo.Hints hints = new GameInfo.Hints();
        hints.loader = quilt ? GameInfo.Loader.QUILT : GameInfo.Loader.FABRIC;
        hints.minecraftVersion = versionOf(fabric, "minecraft");
        hints.loaderVersion = versionOf(fabric, quilt ? "quilt_loader" : "fabricloader");
        hints.modsAlreadyDiscovered = true;

        Side side = fabric.getEnvironmentType() == EnvType.SERVER ? Side.SERVER : Side.CLIENT;
        CurseforgeSync.bootstrap(fabric.getGameDir(), side, quilt ? "Quilt preLaunch" : "Fabric preLaunch", hints);
    }

    private static String versionOf(FabricLoader fabric, String modId) {
        Optional<ModContainer> container = fabric.getModContainer(modId);
        return container.isPresent() ? container.get().getMetadata().getVersion().getFriendlyString() : "";
    }
}
