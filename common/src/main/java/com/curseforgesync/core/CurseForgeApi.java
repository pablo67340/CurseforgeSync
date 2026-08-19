package com.curseforgesync.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A read-only client for the parts of the CurseForge Core API this mod needs. */
public final class CurseForgeApi {
    public static final int GAME_MINECRAFT = 432;

    public static final int RELEASE = 1;
    public static final int BETA = 2;
    public static final int ALPHA = 3;

    /** {@code fileStatus} 4 is "Approved"; anything else is not publicly downloadable. */
    private static final int STATUS_APPROVED = 4;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int PAGE_SIZE = 50;

    private final Http http;
    private final String baseUrl;
    private final Map<String, String> headers;

    public CurseForgeApi(Http http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.headers = new HashMap<String, String>();
        this.headers.put("x-api-key", apiKey);
        this.headers.put("Accept", "application/json");
    }

    public Map<String, String> downloadHeaders() {
        // The CDN rejects the API key header on some edge nodes, so downloads go out bare.
        return Collections.emptyMap();
    }

    // ------------------------------------------------------------------ model

    /** A CurseForge project (what the site calls a mod or a modpack). */
    public static final class Project {
        public final int id;
        public final String name;
        public final String slug;
        public final int classId;
        public final boolean allowModDistribution;

        Project(Map<String, Object> json) {
            this.id = Json.intVal(json, "id", 0);
            this.name = Json.str(json, "name", "unknown");
            this.slug = Json.str(json, "slug", "");
            this.classId = Json.intVal(json, "classId", 0);
            // Absent means "allowed"; only an explicit false blocks third-party downloads.
            Object flag = json.get("allowModDistribution");
            this.allowModDistribution = !(flag instanceof Boolean) || ((Boolean) flag).booleanValue();
        }

        @Override
        public String toString() {
            return name + " (" + id + ")";
        }
    }

    /** A single uploaded file on a project. */
    public static final class File {
        public final int id;
        public final int modId;
        public final String displayName;
        public final String fileName;
        public final int releaseType;
        public final int fileStatus;
        public final boolean available;
        public final String downloadUrl;
        public final long fileLength;
        public final String sha1;
        public final List<String> gameVersions;
        public final boolean serverPack;
        public final int serverPackFileId;
        public final String fileDate;

        File(Map<String, Object> json) {
            this.id = Json.intVal(json, "id", 0);
            this.modId = Json.intVal(json, "modId", 0);
            this.displayName = Json.str(json, "displayName", "");
            this.fileName = Json.str(json, "fileName", "");
            this.releaseType = Json.intVal(json, "releaseType", RELEASE);
            this.fileStatus = Json.intVal(json, "fileStatus", STATUS_APPROVED);
            this.available = Json.bool(json, "isAvailable", true);
            this.downloadUrl = Json.str(json, "downloadUrl", null);
            this.fileLength = Json.num(json, "fileLength", -1L);
            this.gameVersions = Json.strings(json, "gameVersions");
            this.serverPack = Json.bool(json, "isServerPack", false);
            this.serverPackFileId = Json.intVal(json, "serverPackFileId", 0);
            this.fileDate = Json.str(json, "fileDate", "");

            String hash = null;
            for (Object element : Json.arr(json, "hashes")) {
                Map<String, Object> entry = Json.asObject(element);
                if (Json.intVal(entry, "algo", 0) == 1) {
                    hash = Json.str(entry, "value", null);
                }
            }
            this.sha1 = hash;
        }

        public boolean isApproved() {
            return available && fileStatus == STATUS_APPROVED;
        }

        /**
         * The CurseForge "environment" tags an author can set on an upload. Most files carry
         * neither tag, which is why this is only one of several signals used to decide a side.
         */
        public boolean taggedClient() {
            return hasTag("client");
        }

        public boolean taggedServer() {
            return hasTag("server");
        }

        private boolean hasTag(String tag) {
            for (String version : gameVersions) {
                if (version.toLowerCase(Locale.ROOT).equals(tag)) {
                    return true;
                }
            }
            return false;
        }

        public boolean supports(String minecraftVersion) {
            for (String version : gameVersions) {
                if (version.equals(minecraftVersion)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return fileName + " (file " + id + ")";
        }
    }

    // --------------------------------------------------------------- requests

    public Project project(int projectId) throws IOException {
        Map<String, Object> body = getJson("/v1/mods/" + projectId);
        return new Project(Json.obj(body, "data"));
    }

    public Map<Integer, Project> projects(List<Integer> projectIds) throws IOException {
        Map<Integer, Project> byId = new LinkedHashMap<Integer, Project>();
        // The bulk endpoint caps out well below the size of a big pack, so page through it.
        for (int start = 0; start < projectIds.size(); start += PAGE_SIZE) {
            List<Integer> slice = projectIds.subList(start, Math.min(projectIds.size(), start + PAGE_SIZE));
            StringBuilder payload = new StringBuilder("{\"modIds\":[");
            for (int i = 0; i < slice.size(); i++) {
                if (i > 0) {
                    payload.append(',');
                }
                payload.append(slice.get(i));
            }
            payload.append("]}");
            Map<String, Object> body = postJson("/v1/mods", payload.toString());
            for (Object element : Json.arr(body, "data")) {
                Project project = new Project(Json.asObject(element));
                byId.put(Integer.valueOf(project.id), project);
            }
        }
        return byId;
    }

    public Map<Integer, File> files(List<Integer> fileIds) throws IOException {
        Map<Integer, File> byId = new LinkedHashMap<Integer, File>();
        for (int start = 0; start < fileIds.size(); start += PAGE_SIZE) {
            List<Integer> slice = fileIds.subList(start, Math.min(fileIds.size(), start + PAGE_SIZE));
            StringBuilder payload = new StringBuilder("{\"fileIds\":[");
            for (int i = 0; i < slice.size(); i++) {
                if (i > 0) {
                    payload.append(',');
                }
                payload.append(slice.get(i));
            }
            payload.append("]}");
            Map<String, Object> body = postJson("/v1/mods/files", payload.toString());
            for (Object element : Json.arr(body, "data")) {
                File file = new File(Json.asObject(element));
                byId.put(Integer.valueOf(file.id), file);
            }
        }
        return byId;
    }

    public File file(int projectId, int fileId) throws IOException {
        Map<String, Object> body = getJson("/v1/mods/" + projectId + "/files/" + fileId);
        return new File(Json.obj(body, "data"));
    }

    /** Newest-first list of a project's files, filtered to approved uploads. */
    public List<File> projectFiles(int projectId, String minecraftVersion) throws IOException {
        List<File> found = new ArrayList<File>();
        for (int index = 0; index < 1000; index += PAGE_SIZE) {
            StringBuilder path = new StringBuilder("/v1/mods/").append(projectId).append("/files?pageSize=")
                    .append(PAGE_SIZE).append("&index=").append(index);
            if (minecraftVersion != null && !minecraftVersion.isEmpty()) {
                path.append("&gameVersion=").append(urlEncode(minecraftVersion));
            }
            Map<String, Object> body = getJson(path.toString());
            List<Object> data = Json.arr(body, "data");
            for (Object element : data) {
                File file = new File(Json.asObject(element));
                if (file.isApproved()) {
                    found.add(file);
                }
            }
            Map<String, Object> pagination = Json.obj(body, "pagination");
            long total = Json.num(pagination, "totalCount", data.size());
            if (data.size() < PAGE_SIZE || index + PAGE_SIZE >= total) {
                break;
            }
        }
        return found;
    }

    /**
     * Asks the API for a download URL. Projects whose authors opted out of third-party
     * distribution return nothing here, and the caller has to decide what to do about it.
     */
    public String downloadUrl(int projectId, int fileId) throws IOException {
        Map<String, Object> body = getJson("/v1/mods/" + projectId + "/files/" + fileId + "/download-url");
        Object data = body.get("data");
        return data instanceof String && !((String) data).isEmpty() ? (String) data : null;
    }

    /**
     * Rebuilds the CDN path CurseForge itself serves files from.
     *
     * <p>Only used when the API withholds a download URL. It is the same address the website hands
     * out to a human clicking "download", so it is not a bypass of any access control, but it does
     * sidestep an author's third-party distribution preference. Off by default in the config.
     */
    public static String cdnUrl(int fileId, String fileName) {
        int high = fileId / 1000;
        int low = fileId % 1000;
        return "https://edge.forgecdn.net/files/" + high + "/" + low + "/" + urlEncode(fileName);
    }

    private Map<String, Object> getJson(String path) throws IOException {
        byte[] response = http.get(baseUrl + path, headers);
        return Json.parseObject(new String(response, UTF8));
    }

    private Map<String, Object> postJson(String path, String payload) throws IOException {
        byte[] response = http.post(baseUrl + path, headers, payload.getBytes(UTF8));
        return Json.parseObject(new String(response, UTF8));
    }

    private static String urlEncode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        byte[] bytes = value.getBytes(UTF8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            boolean safe = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~';
            if (safe) {
                out.append((char) b);
            } else if (b == ' ') {
                out.append("%20");
            } else {
                out.append('%').append(String.format("%02X", Integer.valueOf(b)));
            }
        }
        return out.toString();
    }
}
