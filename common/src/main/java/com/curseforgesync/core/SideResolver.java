package com.curseforgesync.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a mod in the pack belongs on the side that is booting.
 *
 * <p>A CurseForge modpack manifest is just a list of project and file IDs; it says nothing about
 * sides, because the launcher that consumes it is always a client. Three signals are combined
 * here, in descending order of trust:
 *
 * <ol>
 *   <li>the {@code clientOnlyMods} / {@code serverOnlyMods} lists in the config, which an admin
 *       set deliberately;
 *   <li>a curated table of well-known client-only projects, which covers old uploads from before
 *       CurseForge had environment tags;
 *   <li>the author's own environment tags on the uploaded file.
 * </ol>
 *
 * <p>When nothing matches, the answer is {@link Side#BOTH}. Guessing "client-only" wrongly costs
 * you a missing mod and a crash; guessing "both" wrongly costs a few megabytes of RAM, so unknown
 * mods always get installed.
 */
public final class SideResolver {
    /** Why a particular verdict was reached, so the log can explain itself. */
    public static final class Verdict {
        public final Side side;
        public final String reason;

        Verdict(Side side, String reason) {
            this.side = side;
            this.reason = reason;
        }
    }

    /**
     * Projects that only ever do something on a client: renderers, shader loaders, input and
     * sound tweaks, HUD and menu mods. Deliberately conservative -- anything with even a small
     * server-side component (JEI, Jade, JourneyMap, FerriteCore, ModernFix) is left out so it
     * keeps being installed on servers.
     */
    private static final Set<String> CLIENT_ONLY = new HashSet<String>(Arrays.asList(
            // Renderers, shader loaders and GPU-side performance
            "rubidium", "embeddium", "sodium", "vulkanmod", "oculus", "iris",
            "iris-flywheel-compat", "optiforge", "optifabric", "magnesium-extras",
            "rubidium-extra", "sodium-extra", "textrues-embeddium-options", "immediatelyfast",
            "entity-culling", "entity-collision-fps-fix", "distant-horizons", "fps-reducer",
            "dynamic-fps", "smooth-boot", "smooth-boot-forge", "smooth-boot-reloaded", "fastload",
            "model-gap-fix", "cull-less-leaves", "moreculling", "enhanced-block-entities",
            "animatica", "continuity", "cit-resewn", "entity-texture-features-fabric",
            "entity-model-features", "custom-entity-models-cem", "skin-layers-3d", "capes", "ears",

            // Input and inventory-side conveniences
            "inventory-tweaks", "invtweaks", "inventory-tweaks-renewed", "mouse-tweaks",
            "mouse-wheelie", "controlling", "controllable", "better-third-person",
            "zoomify", "ok-zoomer", "logical-zoom", "wi-zoom", "just-zoom", "borderless-window",

            // HUD, menus, tooltips and other pure UI
            "betterf3", "blur", "dark-loading-screen", "drippy-loading-screen", "fancymenu",
            "custom-fov", "catalogue", "configured", "legendary-tooltips", "item-borders",
            "itemzoom", "toast-control", "toastcontrol", "shutup-experimental-settings", "rrls",
            "boosted-brightness", "chat-heads", "screenshot-to-clipboard", "better-mods-button",
            "loading-screen-tips", "advancement-plaques", "better-advancements", "titles",
            "overflowing-bars", "pick-up-notifier", "resource-pack-overrides", "iceberg",
            "enchantment-descriptions", "nekos-enchanted-books", "just-enough-calculation",
            "just-enough-resources-jer", "bad-wither-no-cookie-reloaded",
            "xaeros-minimap", "xaeros-world-map",

            // Sound and ambience
            "sound-physics-remastered", "ambientsounds", "presence-footsteps", "sound-filters",
            "extreme-sound-muffler", "dynamic-surroundings",

            // Animation and cosmetic
            "not-enough-animations", "first-person-model", "visuality", "particle-rain",
            "physics-mod", "falling-leaves", "make-bubbles-pop", "fancy-block-particles",

            // Rich presence
            "craftpresence", "simple-discord-rich-presence", "simple-discord-rpc", "discordrpc"
    ));

    /** Projects that only make sense on a dedicated server. */
    private static final Set<String> SERVER_ONLY = new HashSet<String>(Arrays.asList(
            "server-tab-info", "vanillatweaks-server"
    ));

    private final SyncConfig config;
    private final Map<String, Side> overrides = new HashMap<String, Side>();

    public SideResolver(SyncConfig config) {
        this.config = config;
        for (String key : config.clientOnlyMods) {
            overrides.put(key, Side.CLIENT);
        }
        for (String key : config.serverOnlyMods) {
            overrides.put(key, Side.SERVER);
        }
    }

    public Verdict resolve(CurseForgeApi.Project project, CurseForgeApi.File file) {
        String slug = project == null ? "" : normalize(project.slug);
        String id = project == null ? "" : String.valueOf(project.id);

        Side override = overrides.get(slug);
        if (override == null) {
            override = overrides.get(id);
        }
        if (override != null) {
            return new Verdict(override, "config override");
        }

        if (!slug.isEmpty() && CLIENT_ONLY.contains(slug)) {
            return new Verdict(Side.CLIENT, "known client-only project");
        }
        if (!slug.isEmpty() && SERVER_ONLY.contains(slug)) {
            return new Verdict(Side.SERVER, "known server-only project");
        }

        if (file != null) {
            boolean client = file.taggedClient();
            boolean server = file.taggedServer();
            if (client && !server) {
                return new Verdict(Side.CLIENT, "tagged Client-only on CurseForge");
            }
            if (server && !client) {
                return new Verdict(Side.SERVER, "tagged Server-only on CurseForge");
            }
        }

        return new Verdict(Side.BOTH, "no side information");
    }

    public boolean isForceIncluded(CurseForgeApi.Project project) {
        return matches(config.forceIncludeMods, project);
    }

    public boolean isExcluded(CurseForgeApi.Project project) {
        return matches(config.excludeMods, project);
    }

    private static boolean matches(Set<String> keys, CurseForgeApi.Project project) {
        if (project == null || keys.isEmpty()) {
            return false;
        }
        return keys.contains(normalize(project.slug)) || keys.contains(String.valueOf(project.id));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Exposed so the README's list of built-in exclusions can be generated rather than typed. */
    public static List<String> knownClientOnlySlugs() {
        java.util.ArrayList<String> sorted = new java.util.ArrayList<String>(CLIENT_ONLY);
        java.util.Collections.sort(sorted);
        return sorted;
    }
}
