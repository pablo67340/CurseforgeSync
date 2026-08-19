package com.curseforgesync.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Zip, jar and hashing helpers. */
public final class Jars {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Service files that make a jar load before regular mods do.
     *
     * <p>Forge's {@code ModDirTransformerDiscoverer} sweeps the mods folder for these before any
     * transformation service (including this one) is constructed. A jar carrying one that arrives
     * during our own sync has already missed its turn, so the server has to be restarted for it to
     * take effect. This is exactly the mechanism CurseforgeSync itself uses to run early.
     */
    private static final List<String> EARLY_LOADER_SERVICES = Arrays.asList(
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
            "META-INF/services/cpw.mods.modlauncher.serviceapi.ITransformerDiscoveryService");

    private Jars() {
    }

    /** True when installing this jar requires a restart before Forge will honour it. */
    public static boolean isEarlyLoader(Path jar) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar.toFile());
            for (String service : EARLY_LOADER_SERVICES) {
                if (zip.getEntry(service) != null) {
                    return true;
                }
            }
            // 1.7.10 and 1.12.2 announce coremods through the jar manifest instead.
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                InputStream in = zip.getInputStream(manifestEntry);
                try {
                    Attributes attributes = new Manifest(in).getMainAttributes();
                    if (attributes.getValue("FMLCorePlugin") != null
                            || attributes.getValue("TweakClass") != null) {
                        return true;
                    }
                } finally {
                    in.close();
                }
            }
            return false;
        } catch (IOException e) {
            CfsLog.debug("Could not inspect " + jar.getFileName() + " for early-loading services: " + e);
            return false;
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * The mod IDs a jar declares, lowercased.
     *
     * <p>Two jars claiming the same mod ID always kill a Forge server, so this is what makes it
     * possible to spot a stale copy of a mod without relying on the state file having survived.
     * Returns empty for anything that is not a mod, which is the safe answer: an unreadable or
     * unrecognised jar is never treated as a duplicate of something else.
     */
    public static Set<String> modIds(Path jar) {
        Set<String> ids = new LinkedHashSet<String>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar.toFile());
            ZipEntry toml = zip.getEntry("META-INF/mods.toml");
            if (toml != null) {
                ids.addAll(modIdsFromToml(readEntry(zip, toml)));
            }
            ZipEntry legacy = zip.getEntry("mcmod.info");
            if (legacy != null) {
                ids.addAll(modIdsFromMcmodInfo(readEntry(zip, legacy)));
            }
        } catch (IOException e) {
            CfsLog.debug("Could not read mod IDs from " + jar.getFileName() + ": " + e);
        } finally {
            closeQuietly(zip);
        }
        return ids;
    }

    /** 1.16.5 and newer. */
    static Set<String> modIdsFromToml(String text) {
        Set<String> ids = new LinkedHashSet<String>();
        boolean inModsBlock = false;
        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                // Dependency blocks declare a modId too, so only [[mods]] entries count.
                inModsBlock = line.replace(" ", "").startsWith("[[mods]]");
                continue;
            }
            int equals = line.indexOf('=');
            if (!inModsBlock || equals < 0 || !line.substring(0, equals).trim().equals("modId")) {
                continue;
            }
            String value = unquote(line.substring(equals + 1).trim());
            if (!value.isEmpty()) {
                ids.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    /** 1.7.10 and 1.12.2. */
    static Set<String> modIdsFromMcmodInfo(String text) {
        Set<String> ids = new LinkedHashSet<String>();
        Matcher matcher = MCMOD_INFO_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static final Pattern MCMOD_INFO_ID =
            Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static String unquote(String value) {
        int open = value.indexOf('"');
        if (open < 0) {
            // Unquoted values are not legal TOML for a string, but be forgiving about a
            // trailing comment rather than returning something with a '#' in it.
            int comment = value.indexOf('#');
            return (comment < 0 ? value : value.substring(0, comment)).trim();
        }
        int close = value.indexOf('"', open + 1);
        return close < 0 ? "" : value.substring(open + 1, close);
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        try {
            return new String(readFully(in), UTF8);
        } finally {
            in.close();
        }
    }

    public static String readTextEntry(Path zipPath, String entryName) throws IOException {
        ZipFile zip = new ZipFile(zipPath.toFile());
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            try {
                return new String(readFully(in), UTF8);
            } finally {
                in.close();
            }
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * Extracts everything under {@code prefix} into {@code target}.
     *
     * @param allowedRoots when non-empty, only these first-level folders inside the prefix are copied
     * @return how many files were written
     */
    public static int extractFolder(Path zipPath, String prefix, Path target, List<String> allowedRoots)
            throws IOException {
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        int written = 0;
        ZipFile zip = new ZipFile(zipPath.toFile());
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(normalizedPrefix)) {
                    continue;
                }
                String relative = entry.getName().substring(normalizedPrefix.length());
                if (relative.isEmpty()) {
                    continue;
                }
                if (!allowedRoots.isEmpty()) {
                    int slash = relative.indexOf('/');
                    String root = slash < 0 ? relative : relative.substring(0, slash);
                    if (!allowedRoots.contains(root)) {
                        continue;
                    }
                }
                Path destination = resolveSafely(target, relative);
                if (destination == null) {
                    CfsLog.warn("Refusing to extract " + entry.getName() + ": it escapes the game directory");
                    continue;
                }
                Files.createDirectories(destination.getParent());
                InputStream in = zip.getInputStream(entry);
                OutputStream out = Files.newOutputStream(destination);
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                written++;
            }
        } finally {
            closeQuietly(zip);
        }
        return written;
    }

    /** Guards against zip entries like {@code ../../etc/passwd}. */
    static Path resolveSafely(Path base, String relative) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path candidate = normalizedBase.resolve(relative).normalize();
        return candidate.startsWith(normalizedBase) ? candidate : null;
    }

    public static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable on this JVM", e);
        }
        InputStream in = Files.newInputStream(file);
        try {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder hex = new StringBuilder(40);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // Nothing to do about it.
            }
        }
    }
}
