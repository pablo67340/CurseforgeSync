package com.curseforgesync.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The record of what CurseforgeSync put in the mods folder.
 *
 * <p>Without this there is no safe way to tell a jar the previous sync installed apart from one an
 * admin added deliberately, and tracked mode would either leak stale mods forever or delete things
 * it has no business deleting.
 */
public final class SyncState {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Entry {
        public int projectId;
        public int fileId;
        public String fileName = "";
        /**
         * Where the file was installed, relative to the game directory. Older state files predate
         * multi-folder support and are read back as "mods".
         */
        public String folder = "mods";
        public String slug = "";
        public String sha1 = "";
        public Side side = Side.BOTH;
        public ContentKind kind = ContentKind.MOD;

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("projectId", Integer.valueOf(projectId));
            json.put("fileId", Integer.valueOf(fileId));
            json.put("fileName", fileName);
            json.put("folder", folder == null || folder.isEmpty() ? "mods" : folder);
            json.put("slug", slug);
            json.put("sha1", sha1 == null ? "" : sha1);
            json.put("side", side.name());
            json.put("kind", kind.name());
            return json;
        }

        static Entry fromJson(Map<String, Object> json) {
            Entry entry = new Entry();
            entry.projectId = Json.intVal(json, "projectId", 0);
            entry.fileId = Json.intVal(json, "fileId", 0);
            entry.fileName = Json.str(json, "fileName", "");
            entry.folder = Json.str(json, "folder", "mods");
            entry.slug = Json.str(json, "slug", "");
            entry.sha1 = Json.str(json, "sha1", "");
            entry.side = Side.parse(Json.str(json, "side", "BOTH"), Side.BOTH);
            String kind = Json.str(json, "kind", ContentKind.MOD.name());
            try {
                entry.kind = ContentKind.valueOf(kind);
            } catch (IllegalArgumentException e) {
                entry.kind = ContentKind.UNKNOWN;
            }
            return entry;
        }
    }

    /**
     * One file copied out of the pack's overrides folder, and the hash of what was written.
     *
     * <p>The hash is what separates "the admin has since edited this" from "this is still exactly
     * what we put down", which decides whether a file can be safely overwritten or deleted.
     */
    public static final class Override {
        /** Relative to the game directory, with forward slashes. */
        public String path = "";
        public String sha1 = "";

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("path", path);
            json.put("sha1", sha1 == null ? "" : sha1);
            return json;
        }

        static Override fromJson(Map<String, Object> json) {
            Override entry = new Override();
            entry.path = Json.str(json, "path", "");
            entry.sha1 = Json.str(json, "sha1", "");
            return entry;
        }
    }

    public int packProjectId;
    public int packFileId;
    public String packName = "";
    public String packVersion = "";
    public String syncedAt = "";
    public List<Entry> installed = new ArrayList<Entry>();
    public List<Override> overrides = new ArrayList<Override>();

    public static Path pathIn(Path gameDir) {
        return gameDir.resolve("config").resolve("curseforgesync-state.json");
    }

    public static SyncState load(Path gameDir) {
        Path file = pathIn(gameDir);
        SyncState state = new SyncState();
        if (!Files.exists(file)) {
            return state;
        }
        try {
            Map<String, Object> json = Json.parseObject(new String(Files.readAllBytes(file), UTF8));
            state.packProjectId = Json.intVal(json, "packProjectId", 0);
            state.packFileId = Json.intVal(json, "packFileId", 0);
            state.packName = Json.str(json, "packName", "");
            state.packVersion = Json.str(json, "packVersion", "");
            state.syncedAt = Json.str(json, "syncedAt", "");
            for (Object element : Json.arr(json, "installed")) {
                state.installed.add(Entry.fromJson(Json.asObject(element)));
            }
            for (Object element : Json.arr(json, "overrides")) {
                state.overrides.add(Override.fromJson(Json.asObject(element)));
            }
        } catch (Exception e) {
            // A corrupt state file must not brick the server. Starting from empty means tracked
            // mode treats existing jars as hand-placed, which is the conservative direction.
            CfsLog.warn("Could not read " + file + " (" + e + "); treating every existing jar as manually installed");
            return new SyncState();
        }
        return state;
    }

    public void save(Path gameDir) throws IOException {
        Path file = pathIn(gameDir);
        Files.createDirectories(file.getParent());

        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("_comment", "Written by CurseforgeSync. Deleting this makes the next sync treat every jar in mods/ as manually installed.");
        json.put("packProjectId", Integer.valueOf(packProjectId));
        json.put("packFileId", Integer.valueOf(packFileId));
        json.put("packName", packName);
        json.put("packVersion", packVersion);
        json.put("syncedAt", syncedAt);
        List<Object> entries = new ArrayList<Object>();
        for (Entry entry : installed) {
            entries.add(entry.toJson());
        }
        json.put("installed", entries);
        List<Object> overrideEntries = new ArrayList<Object>();
        for (Override entry : overrides) {
            overrideEntries.add(entry.toJson());
        }
        json.put("overrides", overrideEntries);

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, Json.write(json).getBytes(UTF8));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public Set<String> installedFileNames() {
        Set<String> names = new LinkedHashSet<String>();
        for (Entry entry : installed) {
            if (entry.fileName != null && !entry.fileName.isEmpty()) {
                names.add(entry.fileName);
            }
        }
        return names;
    }

    public Map<Integer, Entry> byProjectId() {
        Map<Integer, Entry> byProject = new LinkedHashMap<Integer, Entry>();
        for (Entry entry : installed) {
            byProject.put(Integer.valueOf(entry.projectId), entry);
        }
        return byProject;
    }

    public void stampNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        syncedAt = format.format(new Date());
    }
}
