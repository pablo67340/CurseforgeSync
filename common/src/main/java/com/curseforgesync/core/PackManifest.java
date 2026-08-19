package com.curseforgesync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The {@code manifest.json} at the root of an exported CurseForge modpack. */
public final class PackManifest {
    /** One {@code projectID}/{@code fileID} pair from the manifest's {@code files} array. */
    public static final class Entry {
        public final int projectId;
        public final int fileId;
        public final boolean required;

        Entry(int projectId, int fileId, boolean required) {
            this.projectId = projectId;
            this.fileId = fileId;
            this.required = required;
        }
    }

    public final String name;
    public final String version;
    public final String author;
    public final String minecraftVersion;
    /** For example {@code forge-43.5.2}. */
    public final String modLoaderId;
    public final String overridesFolder;
    public final List<Entry> files;

    private PackManifest(String name, String version, String author, String minecraftVersion,
                         String modLoaderId, String overridesFolder, List<Entry> files) {
        this.name = name;
        this.version = version;
        this.author = author;
        this.minecraftVersion = minecraftVersion;
        this.modLoaderId = modLoaderId;
        this.overridesFolder = overridesFolder;
        this.files = files;
    }

    public static PackManifest parse(String json) {
        Map<String, Object> root = Json.parseObject(json);

        Map<String, Object> minecraft = Json.obj(root, "minecraft");
        String loaderId = "";
        for (Object element : Json.arr(minecraft, "modLoaders")) {
            Map<String, Object> loader = Json.asObject(element);
            String id = Json.str(loader, "id", "");
            if (loaderId.isEmpty() || Json.bool(loader, "primary", false)) {
                loaderId = id;
            }
        }

        List<Entry> files = new ArrayList<Entry>();
        for (Object element : Json.arr(root, "files")) {
            Map<String, Object> file = Json.asObject(element);
            int projectId = Json.intVal(file, "projectID", 0);
            int fileId = Json.intVal(file, "fileID", 0);
            if (projectId > 0 && fileId > 0) {
                files.add(new Entry(projectId, fileId, Json.bool(file, "required", true)));
            }
        }

        return new PackManifest(
                Json.str(root, "name", "unnamed pack"),
                Json.str(root, "version", ""),
                Json.str(root, "author", ""),
                Json.str(minecraft, "version", ""),
                loaderId,
                Json.str(root, "overrides", "overrides"),
                files);
    }

    /** The Forge build the pack was exported against, or an empty string if it uses another loader. */
    public String forgeVersion() {
        return modLoaderId.startsWith("forge-") ? modLoaderId.substring("forge-".length()) : "";
    }

    public String describe() {
        StringBuilder text = new StringBuilder(name);
        if (!version.isEmpty()) {
            text.append(' ').append(version);
        }
        text.append(" [Minecraft ").append(minecraftVersion);
        if (!modLoaderId.isEmpty()) {
            text.append(", ").append(modLoaderId);
        }
        text.append(", ").append(files.size()).append(" mods]");
        return text.toString();
    }
}
