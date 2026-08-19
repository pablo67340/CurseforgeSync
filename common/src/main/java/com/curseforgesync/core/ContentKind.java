package com.curseforgesync.core;

/**
 * What a project in a pack manifest actually is, and where it belongs.
 *
 * <p>A CurseForge modpack manifest is a flat list of project IDs with nothing to say what each one
 * is. In practice a pack mixes mods with resource packs, shader packs and the occasional data
 * pack -- "All of Create" is 230 mods, 42 resource packs and 2 shader packs -- and dropping any of
 * those into the mods folder gives Forge a pile of zips it cannot load. The project's
 * {@code classId} is what tells them apart.
 */
public enum ContentKind {
    MOD(6, "mods", Side.BOTH),
    RESOURCE_PACK(12, "resourcepacks", Side.CLIENT),
    SHADER_PACK(6552, "shaderpacks", Side.CLIENT),
    /** Placement depends on the world, so the engine resolves the folder rather than using this one. */
    DATA_PACK(6945, "datapacks", Side.BOTH),
    /** A whole saved world. Never something a sync should drop on a running server. */
    WORLD(17, null, Side.SERVER),
    /** Bukkit/Spigot plugins, which a Forge server cannot load at all. */
    PLUGIN(5, null, Side.SERVER),
    /** Anything else CurseForge starts publishing; treated as a mod. */
    UNKNOWN(0, "mods", Side.BOTH);

    public final int classId;
    /** Folder relative to the game directory, or {@code null} when this kind is never installed. */
    public final String folder;
    /** The side this kind is inherently useful on, before any per-project rules are applied. */
    public final Side impliedSide;

    ContentKind(int classId, String folder, Side impliedSide) {
        this.classId = classId;
        this.folder = folder;
        this.impliedSide = impliedSide;
    }

    public boolean installable() {
        return folder != null;
    }

    public static ContentKind forClassId(int classId) {
        for (ContentKind kind : values()) {
            if (kind != UNKNOWN && kind.classId == classId) {
                return kind;
            }
        }
        return UNKNOWN;
    }

    public String label() {
        switch (this) {
            case RESOURCE_PACK:
                return "resource pack";
            case SHADER_PACK:
                return "shader pack";
            case DATA_PACK:
                return "data pack";
            case WORLD:
                return "world";
            case PLUGIN:
                return "Bukkit plugin";
            default:
                return "mod";
        }
    }
}
