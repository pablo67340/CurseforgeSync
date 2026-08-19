package com.curseforgesync.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading mod IDs out of a jar is what lets a stale copy of a mod be spotted without the state
 * file, so it has to be right on real-world metadata rather than the tidy version.
 */
class ModIdTest {
    @Test
    void readsTheModIdFromModsToml() {
        String toml = "modLoader = \"javafml\"\n"
                + "loaderVersion = \"[43,)\"\n"
                + "license = \"MIT\"\n"
                + "\n"
                + "[[mods]]\n"
                + "modId = \"create\"\n"
                + "version = \"0.5.1.i\"\n";

        assertEquals(Set.of("create"), Jars.modIdsFromToml(toml));
    }

    /**
     * The trap this guards: dependency blocks declare a modId too. A naive scan reports every
     * modern jar as "forge" and "minecraft", which would make the whole mods folder look like one
     * enormous set of duplicates.
     */
    @Test
    void ignoresTheModIdsInsideDependencyBlocks() {
        String toml = "[[mods]]\n"
                + "modId = \"jei\"\n"
                + "\n"
                + "[[dependencies.jei]]\n"
                + "    modId = \"forge\"\n"
                + "    mandatory = true\n"
                + "\n"
                + "[[dependencies.jei]]\n"
                + "    modId = \"minecraft\"\n"
                + "    mandatory = true\n";

        assertEquals(Set.of("jei"), Jars.modIdsFromToml(toml));
    }

    @Test
    void handlesMultipleModsInOneJarCommentsAndOddSpacing() {
        String toml = "# a comment mentioning modId = \"decoy\"\n"
                + "[[mods]]\n"
                + "modId=\"buildcraftcore\"\n"
                + "[[dependencies.buildcraftcore]]\n"
                + "modId = \"forge\"\n"
                + "[[ mods ]]\n"
                + "  modId  =  \"buildcraftbuilders\"   # trailing comment\n";

        assertEquals(Set.of("buildcraftcore", "buildcraftbuilders"), Jars.modIdsFromToml(toml));
    }

    @Test
    void readsLegacyMcmodInfo() {
        String info = "[\n"
                + "  {\n"
                + "    \"modid\": \"BuildCraft|Core\",\n"
                + "    \"name\": \"BuildCraft\",\n"
                + "    \"dependencies\": [\"forge\"]\n"
                + "  }\n"
                + "]\n";

        assertEquals(Set.of("buildcraft|core"), Jars.modIdsFromMcmodInfo(info));
    }

    @Test
    void readsModIdsStraightFromAJar(@TempDir Path temp) throws IOException {
        Path modern = temp.resolve("jei-1.19.2-forge-11.8.1.1034.jar");
        writeZip(modern, "META-INF/mods.toml", "[[mods]]\nmodId = \"jei\"\n");
        Path legacy = temp.resolve("buildcraft-7.99.24.8.jar");
        writeZip(legacy, "mcmod.info", "[{\"modid\": \"buildcraftcore\"}]");

        assertEquals(Set.of("jei"), Jars.modIds(modern));
        assertEquals(Set.of("buildcraftcore"), Jars.modIds(legacy));
    }

    /** An unreadable or non-mod jar must come back empty so it is never called a duplicate. */
    @Test
    void returnsNothingForJarsThatDeclareNoMod(@TempDir Path temp) throws IOException {
        Path library = temp.resolve("kotlinforforge-3.12.0-all.jar");
        writeZip(library, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n");

        assertTrue(Jars.modIds(library).isEmpty());
        assertTrue(Jars.modIds(temp.resolve("does-not-exist.jar")).isEmpty());
    }

    private static void writeZip(Path target, String... nameThenContent) throws IOException {
        OutputStream raw = Files.newOutputStream(target);
        ZipOutputStream zip = new ZipOutputStream(raw);
        try {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenContent[i]));
                zip.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
    }
}
