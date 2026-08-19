package com.curseforgesync.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Brings a server's mods folder (and friends) in line with a CurseForge modpack. */
public final class SyncEngine {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Path gameDir;
    private final Path modsDir;
    private final Path cacheDir;
    private final SyncConfig config;
    private final BuildInfo build;
    private final Side runningSide;
    private final String selfJarName;

    private final Http http;
    private final CurseForgeApi api;
    private final SideResolver sides;

    /** What a completed sync did, so the caller can decide whether a restart is needed. */
    public static final class Result {
        public final List<String> installed = new ArrayList<String>();
        public final List<String> removed = new ArrayList<String>();
        public final List<String> skippedForSide = new ArrayList<String>();
        public final List<String> manualDownloads = new ArrayList<String>();
        public final List<String> failures = new ArrayList<String>();
        public boolean restartRequired;
        public String restartReason = "";

        public boolean changedAnything() {
            return !installed.isEmpty() || !removed.isEmpty();
        }
    }

    public SyncEngine(Path gameDir, SyncConfig config, BuildInfo build, Side runningSide, String selfJarName) {
        this.gameDir = gameDir;
        this.modsDir = gameDir.resolve("mods");
        this.cacheDir = gameDir.resolve("curseforgesync-cache");
        this.config = config;
        this.build = build;
        this.runningSide = runningSide;
        this.selfJarName = selfJarName;
        this.http = new Http(config.connectTimeoutSeconds, config.readTimeoutSeconds, config.maxAttempts);
        this.api = new CurseForgeApi(http, config.apiBaseUrl, config.effectiveApiKey());
        this.sides = new SideResolver(config);
    }

    public Result run() throws IOException {
        Result result = new Result();
        Files.createDirectories(modsDir);
        Files.createDirectories(cacheDir);

        SyncState state = SyncState.load(gameDir);

        CurseForgeApi.File packFile = selectPackFile();
        CfsLog.info("Modpack upload: " + packFile.displayName + " (file " + packFile.id + ", "
                + packFile.fileDate + ")");

        Path packZip = fetchPackZip(packFile);
        String manifestJson = Jars.readTextEntry(packZip, "manifest.json");
        if (manifestJson == null) {
            throw new IOException("File " + packFile.id + " has no manifest.json in it. Point "
                    + "modpackProjectId at a CurseForge modpack project, not a mod, and make sure the "
                    + "upload is the exported pack rather than a server pack archive.");
        }
        PackManifest manifest = PackManifest.parse(manifestJson);
        CfsLog.info("Pack contents: " + manifest.describe());

        verifyPackTargetsThisServer(manifest);

        Plan plan = buildPlan(manifest, state, result);
        applyPlan(plan, state, packFile, manifest, packZip, result);
        return result;
    }

    // -------------------------------------------------------- pack selection

    private CurseForgeApi.File selectPackFile() throws IOException {
        if (config.modpackFileId > 0) {
            CfsLog.info("Pinned to pack file " + config.modpackFileId + " by config.");
            return api.file(config.modpackProjectId, config.modpackFileId);
        }

        CurseForgeApi.Project project = api.project(config.modpackProjectId);
        CfsLog.info("Following " + project.name + " (project " + project.id + ")");

        List<CurseForgeApi.File> candidates = api.projectFiles(config.modpackProjectId, build.minecraftVersion);
        if (candidates.isEmpty()) {
            // Some packs tag uploads with a different game version string than the server reports,
            // so retry without the filter before giving up.
            CfsLog.warn("No uploads tagged for Minecraft " + build.minecraftVersion
                    + "; falling back to the project's newest upload of any version.");
            candidates = api.projectFiles(config.modpackProjectId, null);
        }

        CurseForgeApi.File best = null;
        for (CurseForgeApi.File candidate : candidates) {
            if (candidate.serverPack || candidate.releaseType > config.releaseChannel) {
                continue;
            }
            if (best == null || candidate.id > best.id) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IOException("Project " + config.modpackProjectId + " has no "
                    + channelName(config.releaseChannel) + "-or-better upload for Minecraft "
                    + build.minecraftVersion + ". Loosen releaseChannel or pin modpackFileId.");
        }
        return best;
    }

    private static String channelName(int channel) {
        if (channel == CurseForgeApi.ALPHA) {
            return "alpha";
        }
        return channel == CurseForgeApi.BETA ? "beta" : "release";
    }

    private Path fetchPackZip(CurseForgeApi.File packFile) throws IOException {
        Path target = cacheDir.resolve("packs").resolve(packFile.id + "-" + safeName(packFile.fileName));
        if (Files.exists(target) && hashMatches(target, packFile.sha1)) {
            CfsLog.debug("Reusing the cached pack archive at " + target);
            return target;
        }
        String url = resolveDownloadUrl(null, packFile);
        if (url == null) {
            throw new IOException("CurseForge will not hand out a download link for pack file " + packFile.id + ".");
        }
        CfsLog.info("Downloading the pack manifest...");
        http.download(url, api.downloadHeaders(), target);
        return target;
    }

    /**
     * Refuses to sync a pack built for a different Minecraft version.
     *
     * <p>Nothing else in the pipeline would notice: the mods would download happily and then the
     * server would die in mod loading with an error that points nowhere near the real cause.
     */
    private void verifyPackTargetsThisServer(PackManifest manifest) throws IOException {
        if (build.minecraftVersion.isEmpty() || manifest.minecraftVersion.isEmpty()
                || manifest.minecraftVersion.equals(build.minecraftVersion)) {
            return;
        }
        throw new IOException("This pack targets Minecraft " + manifest.minecraftVersion
                + " but the server is running " + build.minecraftVersion + ". Refusing to sync. "
                + "Install the CurseforgeSync build for " + manifest.minecraftVersion
                + ", or pin modpackFileId to an upload that matches this server.");
    }

    // ------------------------------------------------------------ planning

    /** A resolved item: which project, which upload, and where the file should end up. */
    private static final class Wanted {
        CurseForgeApi.Project project;
        CurseForgeApi.File file;
        ContentKind kind = ContentKind.MOD;
        Path targetDir;
        String fileName;
        Side side = Side.BOTH;

        Path destination() {
            return targetDir.resolve(fileName);
        }

        String describe() {
            return targetDir.getFileName() + "/" + fileName;
        }
    }

    private static final class Plan {
        /** Keyed by "<folder>/<lowercased file name>" so two kinds cannot collide. */
        final Map<String, Wanted> wanted = new LinkedHashMap<String, Wanted>();
        final List<Wanted> toInstall = new ArrayList<Wanted>();
        final List<Wanted> alreadyPresent = new ArrayList<Wanted>();
        final List<Path> toRemove = new ArrayList<Path>();
    }

    private static String key(Path folder, String fileName) {
        return folder.getFileName() + "/" + fileName.toLowerCase(Locale.ROOT);
    }

    private Plan buildPlan(PackManifest manifest, SyncState state, Result result) throws IOException {
        List<Integer> projectIds = new ArrayList<Integer>();
        List<Integer> fileIds = new ArrayList<Integer>();
        for (PackManifest.Entry entry : manifest.files) {
            projectIds.add(Integer.valueOf(entry.projectId));
            fileIds.add(Integer.valueOf(entry.fileId));
        }

        CfsLog.info("Resolving " + manifest.files.size() + " pack entries against the CurseForge API...");
        Map<Integer, CurseForgeApi.Project> projects = api.projects(projectIds);
        Map<Integer, CurseForgeApi.File> files = api.files(fileIds);

        Plan plan = new Plan();
        Map<ContentKind, Integer> tally = new LinkedHashMap<ContentKind, Integer>();

        for (PackManifest.Entry entry : manifest.files) {
            CurseForgeApi.Project project = projects.get(Integer.valueOf(entry.projectId));
            CurseForgeApi.File file = files.get(Integer.valueOf(entry.fileId));

            if (file == null) {
                String label = project != null ? project.name : ("project " + entry.projectId);
                if (entry.required) {
                    result.failures.add(label + ": file " + entry.fileId + " is no longer on CurseForge");
                    CfsLog.warn("Skipping " + label + ": file " + entry.fileId + " is no longer available.");
                } else {
                    CfsLog.debug("Skipping optional " + label + ": file " + entry.fileId + " is gone.");
                }
                continue;
            }

            if (sides.isExcluded(project)) {
                CfsLog.info("Excluded by config: " + describe(project, file));
                continue;
            }

            ContentKind kind = ContentKind.forClassId(project == null ? 0 : project.classId);
            Integer seen = tally.get(kind);
            tally.put(kind, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));

            if (!kind.installable()) {
                CfsLog.debug("Ignoring " + describe(project, file) + ": a " + kind.label()
                        + " is not something a Forge server installs.");
                continue;
            }

            boolean forced = sides.isForceIncluded(project);
            Side side;
            String reason;
            if (forced) {
                side = Side.BOTH;
                reason = "force-included by config";
            } else if (kind != ContentKind.MOD && kind.impliedSide != Side.BOTH) {
                side = kind.impliedSide;
                reason = "a " + kind.label() + " is " + kind.impliedSide.name().toLowerCase(Locale.ROOT) + "-side";
            } else {
                SideResolver.Verdict verdict = sides.resolve(project, file);
                side = verdict.side;
                reason = verdict.reason;
            }

            if (config.filterClientMods && !side.neededOn(runningSide)) {
                String line = describe(project, file) + " -- " + side.name().toLowerCase(Locale.ROOT)
                        + "-only (" + reason + ")";
                result.skippedForSide.add(line);
                CfsLog.debug("Not needed on this side: " + line);
                continue;
            }

            Wanted wanted = new Wanted();
            wanted.project = project;
            wanted.file = file;
            wanted.kind = kind;
            wanted.side = side;
            wanted.targetDir = folderFor(kind);
            wanted.fileName = safeName(file.fileName.isEmpty() ? (file.id + ".jar") : file.fileName);
            plan.wanted.put(key(wanted.targetDir, wanted.fileName), wanted);

            if (CfsLog.isVerbose()) {
                CfsLog.debug("Keeping " + wanted.describe() + " -- " + side.name().toLowerCase(Locale.ROOT)
                        + " (" + reason + ")");
            }
        }

        StringBuilder composition = new StringBuilder();
        for (Map.Entry<ContentKind, Integer> counted : tally.entrySet()) {
            if (composition.length() > 0) {
                composition.append(", ");
            }
            composition.append(counted.getValue()).append(' ').append(counted.getKey().label())
                    .append(counted.getValue().intValue() == 1 ? "" : "s");
        }
        CfsLog.info("Pack breaks down as: " + composition + ".");

        for (Wanted wanted : plan.wanted.values()) {
            if (Files.exists(wanted.destination()) && hashMatches(wanted.destination(), wanted.file.sha1)) {
                plan.alreadyPresent.add(wanted);
            } else {
                plan.toInstall.add(wanted);
            }
        }

        collectRemovals(plan, state);
        return plan;
    }

    /**
     * Data packs are the one kind whose home depends on the world rather than the game directory:
     * a dedicated server reads them from {@code <level-name>/datapacks}.
     */
    private Path folderFor(ContentKind kind) {
        if (kind != ContentKind.DATA_PACK) {
            return gameDir.resolve(kind.folder);
        }
        if (!config.datapackFolder.trim().isEmpty()) {
            return gameDir.resolve(config.datapackFolder.trim());
        }
        if (runningSide == Side.SERVER) {
            return gameDir.resolve(levelName()).resolve("datapacks");
        }
        return gameDir.resolve("datapacks");
    }

    private String levelName() {
        Path serverProperties = gameDir.resolve("server.properties");
        if (Files.isRegularFile(serverProperties)) {
            InputStream in = null;
            try {
                in = Files.newInputStream(serverProperties);
                Properties properties = new Properties();
                properties.load(in);
                String level = properties.getProperty("level-name", "").trim();
                if (!level.isEmpty()) {
                    return level;
                }
            } catch (IOException e) {
                CfsLog.debug("Could not read level-name from server.properties: " + e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // Nothing to do.
                    }
                }
            }
        }
        return "world";
    }

    private void collectRemovals(Plan plan, SyncState state) throws IOException {
        Set<String> protectedNames = new LinkedHashSet<String>();
        for (String name : config.protectedFiles) {
            protectedNames.add(name.toLowerCase(Locale.ROOT));
        }
        if (selfJarName != null) {
            protectedNames.add(selfJarName.toLowerCase(Locale.ROOT));
        }

        // Tracked removals apply to every folder: anything a previous sync installed that the pack
        // no longer lists. This is what turns a mod update into "delete the old jar, add the new".
        for (SyncState.Entry entry : state.installed) {
            String name = entry.fileName == null ? "" : entry.fileName;
            if (name.isEmpty() || protectedNames.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Path folder = entry.folder == null || entry.folder.isEmpty()
                    ? modsDir : gameDir.resolve(entry.folder);
            if (plan.wanted.containsKey(key(folder, name))) {
                continue;
            }
            Path candidate = folder.resolve(name);
            if (Files.isRegularFile(candidate)) {
                plan.toRemove.add(candidate);
            }
        }

        if (config.mode != SyncConfig.Mode.STRICT) {
            return;
        }

        // Strict mode mirrors the mods folder exactly. It deliberately stops there: wiping unknown
        // resource packs or data packs off a server is far more likely to be a mistake than a fix.
        Set<String> alreadyQueued = new LinkedHashSet<String>();
        for (Path path : plan.toRemove) {
            alreadyQueued.add(path.toAbsolutePath().normalize().toString());
        }
        DirectoryStream<Path> listing = Files.newDirectoryStream(modsDir);
        try {
            for (Path candidate : listing) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String name = candidate.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        || protectedNames.contains(name.toLowerCase(Locale.ROOT))
                        || plan.wanted.containsKey(key(modsDir, name))
                        || alreadyQueued.contains(candidate.toAbsolutePath().normalize().toString())) {
                    continue;
                }
                plan.toRemove.add(candidate);
            }
        } finally {
            listing.close();
        }
    }

    // ------------------------------------------------------------ execution

    private void applyPlan(Plan plan, SyncState state, CurseForgeApi.File packFile, PackManifest manifest,
                           Path packZip, Result result) throws IOException {
        CfsLog.info("Plan: " + plan.toInstall.size() + " to install, " + plan.toRemove.size()
                + " to remove, " + plan.alreadyPresent.size() + " already up to date, "
                + result.skippedForSide.size() + " skipped as "
                + (runningSide == Side.SERVER ? "client" : "server") + "-side.");

        if (config.dryRun) {
            for (Wanted wanted : plan.toInstall) {
                CfsLog.info("  would install " + wanted.describe());
            }
            for (Path path : plan.toRemove) {
                CfsLog.info("  would remove  " + gameDir.relativize(path));
            }
            CfsLog.info("dryRun is on, so nothing was changed.");
            return;
        }

        List<Wanted> downloaded = download(plan.toInstall, result);

        for (Wanted wanted : downloaded) {
            try {
                Jars.copy(cachePathFor(wanted), wanted.destination());
                result.installed.add(wanted.describe());
                CfsLog.info("  + " + wanted.describe());
                if (wanted.kind == ContentKind.MOD && !result.restartRequired
                        && Jars.isEarlyLoader(wanted.destination())) {
                    result.restartRequired = true;
                    result.restartReason = wanted.fileName + " is a coremod or loader plugin, and those "
                            + "are read before CurseforgeSync runs";
                }
            } catch (IOException e) {
                result.failures.add(wanted.fileName + ": " + e.getMessage());
                CfsLog.error("Could not install " + wanted.describe(), e);
            }
        }

        for (Path path : plan.toRemove) {
            boolean early = Jars.isEarlyLoader(path);
            try {
                Files.delete(path);
                result.removed.add(gameDir.relativize(path).toString());
                CfsLog.info("  - " + gameDir.relativize(path));
                if (early && !result.restartRequired) {
                    result.restartRequired = true;
                    result.restartReason = path.getFileName() + " was a coremod or loader plugin and its "
                            + "classes are already loaded";
                }
            } catch (IOException e) {
                result.failures.add(path.getFileName() + ": " + e.getMessage());
                CfsLog.error("Could not delete " + path.getFileName() + " -- it may be in use", e);
            }
        }

        if (config.syncOverrides) {
            applyOverrides(packZip, manifest);
        }

        writeState(plan, state, packFile, manifest, result);
        writeManualDownloadList(result);
    }

    private List<Wanted> download(List<Wanted> pending, final Result result) {
        final List<Wanted> succeeded = Collections.synchronizedList(new ArrayList<Wanted>());
        if (pending.isEmpty()) {
            return succeeded;
        }

        final AtomicInteger completed = new AtomicInteger();
        final int total = pending.size();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(config.downloadThreads, pending.size()), new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "CurseforgeSync-download-" + counter.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    }
                });

        List<Future<?>> tasks = new ArrayList<Future<?>>();
        for (final Wanted wanted : pending) {
            tasks.add(pool.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    try {
                        fetchToCache(wanted);
                        succeeded.add(wanted);
                    } catch (ManualDownloadRequired e) {
                        result.manualDownloads.add(e.getMessage());
                        CfsLog.warn(e.getMessage());
                    } catch (Exception e) {
                        result.failures.add(wanted.fileName + ": " + e);
                        CfsLog.error("Download failed for " + wanted.fileName, e);
                    } finally {
                        int done = completed.incrementAndGet();
                        if (total >= 20 && done % 25 == 0) {
                            CfsLog.info("  ...downloaded " + done + " of " + total);
                        }
                    }
                    return null;
                }
            }));
        }

        pool.shutdown();
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                CfsLog.error("A download task failed unexpectedly", e.getCause() == null ? e : e.getCause());
            }
        }

        // Preserve the plan's ordering so the log reads the same way between runs.
        List<Wanted> ordered = new ArrayList<Wanted>();
        for (Wanted wanted : pending) {
            if (succeeded.contains(wanted)) {
                ordered.add(wanted);
            }
        }
        return ordered;
    }

    private static final class ManualDownloadRequired extends Exception {
        private static final long serialVersionUID = 1L;

        ManualDownloadRequired(String message) {
            super(message);
        }
    }

    private void fetchToCache(Wanted wanted) throws IOException, ManualDownloadRequired {
        Path cached = cachePathFor(wanted);
        if (Files.exists(cached) && hashMatches(cached, wanted.file.sha1)) {
            CfsLog.debug("Cache hit for " + wanted.fileName);
            return;
        }

        String url = resolveDownloadUrl(wanted.project, wanted.file);
        if (url == null) {
            throw new ManualDownloadRequired(describe(wanted.project, wanted.file)
                    + " cannot be downloaded automatically: its author opted out of third-party "
                    + "distribution. Download " + wanted.fileName + " by hand into "
                    + wanted.targetDir.getFileName() + "/ and add it to protectedFiles.");
        }

        http.download(url, api.downloadHeaders(), cached);

        if (wanted.file.sha1 != null && !wanted.file.sha1.isEmpty() && !hashMatches(cached, wanted.file.sha1)) {
            Files.deleteIfExists(cached);
            throw new IOException("Checksum mismatch on " + wanted.fileName + "; the download was discarded.");
        }
        CfsLog.debug("Fetched " + wanted.fileName);
    }

    private String resolveDownloadUrl(CurseForgeApi.Project project, CurseForgeApi.File file) throws IOException {
        if (file.downloadUrl != null && !file.downloadUrl.isEmpty()) {
            return file.downloadUrl;
        }
        try {
            String fromApi = api.downloadUrl(file.modId > 0 ? file.modId : (project == null ? 0 : project.id), file.id);
            if (fromApi != null) {
                return fromApi;
            }
        } catch (IOException e) {
            CfsLog.debug("download-url lookup failed for file " + file.id + ": " + e);
        }
        if (config.allowCdnFallback && !file.fileName.isEmpty()) {
            CfsLog.debug("Using the CDN address for file " + file.id + " (the API withheld a link).");
            return CurseForgeApi.cdnUrl(file.id, file.fileName);
        }
        return null;
    }

    private void applyOverrides(Path packZip, PackManifest manifest) {
        try {
            int written = Jars.extractFolder(packZip, manifest.overridesFolder, gameDir, config.overrideFolders);
            CfsLog.info("Copied " + written + " file(s) from the pack's " + manifest.overridesFolder + " folder.");
        } catch (IOException e) {
            CfsLog.error("Could not apply the pack's overrides", e);
        }
    }

    private void writeState(Plan plan, SyncState state, CurseForgeApi.File packFile, PackManifest manifest,
                            Result result) {
        Set<String> present = new LinkedHashSet<String>(result.installed);
        for (Wanted wanted : plan.alreadyPresent) {
            present.add(wanted.describe());
        }

        state.installed.clear();
        for (Wanted wanted : plan.wanted.values()) {
            if (!present.contains(wanted.describe())) {
                continue;
            }
            SyncState.Entry entry = new SyncState.Entry();
            entry.projectId = wanted.project == null ? 0 : wanted.project.id;
            entry.fileId = wanted.file.id;
            entry.fileName = wanted.fileName;
            entry.folder = gameDir.relativize(wanted.targetDir).toString().replace('\\', '/');
            entry.slug = wanted.project == null ? "" : wanted.project.slug;
            entry.sha1 = wanted.file.sha1;
            entry.side = wanted.side;
            entry.kind = wanted.kind;
            state.installed.add(entry);
        }
        state.packProjectId = config.modpackProjectId;
        state.packFileId = packFile.id;
        state.packName = manifest.name;
        state.packVersion = manifest.version;
        state.stampNow();

        try {
            state.save(gameDir);
        } catch (IOException e) {
            CfsLog.error("Could not save the sync state; the next run will treat these files as manually "
                    + "installed and leave them alone", e);
        }
    }

    private void writeManualDownloadList(Result result) {
        Path listing = gameDir.resolve("logs").resolve("curseforgesync-manual-downloads.txt");
        try {
            if (result.manualDownloads.isEmpty()) {
                Files.deleteIfExists(listing);
                return;
            }
            StringBuilder text = new StringBuilder();
            text.append("These files could not be downloaded automatically because their authors opted\n");
            text.append("out of third-party distribution. Download each one from CurseForge, put it in the\n");
            text.append("folder named below, and add the file name to protectedFiles in\n");
            text.append("config/curseforgesync.json.\n\n");
            for (String line : result.manualDownloads) {
                text.append("  - ").append(line).append('\n');
            }
            Files.createDirectories(listing.getParent());
            Files.write(listing, text.toString().getBytes(UTF8));
            CfsLog.warn(result.manualDownloads.size() + " file(s) need downloading by hand. See " + listing);
        } catch (IOException e) {
            CfsLog.debug("Could not write the manual download list: " + e);
        }
    }

    // -------------------------------------------------------------- helpers

    private Path cachePathFor(Wanted wanted) {
        return cacheDir.resolve("files").resolve(wanted.file.id + "-" + wanted.fileName);
    }

    private static boolean hashMatches(Path file, String expectedSha1) {
        if (expectedSha1 == null || expectedSha1.isEmpty()) {
            // No hash published: presence is the best check available.
            return Files.isRegularFile(file);
        }
        try {
            return Jars.sha1(file).equalsIgnoreCase(expectedSha1);
        } catch (IOException e) {
            return false;
        }
    }

    private static String describe(CurseForgeApi.Project project, CurseForgeApi.File file) {
        String name = project != null ? project.name : "unknown project";
        return name + " / " + (file == null ? "?" : file.fileName);
    }

    /** Strips any path separators a malicious or malformed file name might contain. */
    static String safeName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_");
        return name.isEmpty() ? "unnamed.jar" : name;
    }
}
