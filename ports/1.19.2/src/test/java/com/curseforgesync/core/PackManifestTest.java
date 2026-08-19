package com.curseforgesync.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManifestTest {
    /** Trimmed from a real CurseForge export. */
    private static final String SAMPLE = "{\n"
            + "  \"minecraft\": {\n"
            + "    \"version\": \"1.19.2\",\n"
            + "    \"modLoaders\": [{\"id\": \"forge-43.5.2\", \"primary\": true}]\n"
            + "  },\n"
            + "  \"manifestType\": \"minecraftModpack\",\n"
            + "  \"manifestVersion\": 1,\n"
            + "  \"name\": \"Test Pack\",\n"
            + "  \"version\": \"2.1\",\n"
            + "  \"author\": \"Bryce\",\n"
            + "  \"files\": [\n"
            + "    {\"projectID\": 238222, \"fileID\": 4712868, \"required\": true},\n"
            + "    {\"projectID\": 574856, \"fileID\": 4712570, \"required\": false},\n"
            + "    {\"projectID\": 0, \"fileID\": 12345, \"required\": true}\n"
            + "  ],\n"
            + "  \"overrides\": \"overrides\"\n"
            + "}";

    @Test
    void readsTheHeader() {
        PackManifest manifest = PackManifest.parse(SAMPLE);

        assertEquals("Test Pack", manifest.name);
        assertEquals("2.1", manifest.version);
        assertEquals("1.19.2", manifest.minecraftVersion);
        assertEquals("forge-43.5.2", manifest.modLoaderId);
        assertEquals("43.5.2", manifest.forgeVersion());
        assertEquals("overrides", manifest.overridesFolder);
    }

    @Test
    void dropsEntriesWithoutBothIdsAndKeepsTheRequiredFlag() {
        PackManifest manifest = PackManifest.parse(SAMPLE);

        assertEquals(2, manifest.files.size());
        assertEquals(238222, manifest.files.get(0).projectId);
        assertTrue(manifest.files.get(0).required);
        assertFalse(manifest.files.get(1).required);
    }

    @Test
    void picksThePrimaryLoaderWhenSeveralAreListed() {
        PackManifest manifest = PackManifest.parse("{\"minecraft\":{\"version\":\"1.12.2\",\"modLoaders\":["
                + "{\"id\":\"forge-14.23.5.2859\",\"primary\":false},"
                + "{\"id\":\"forge-14.23.5.2860\",\"primary\":true}]},\"files\":[]}");

        assertEquals("14.23.5.2860", manifest.forgeVersion());
    }

    @Test
    void reportsNoForgeVersionForOtherLoaders() {
        PackManifest manifest = PackManifest.parse(
                "{\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"fabric-0.15.6\",\"primary\":true}]},"
                        + "\"files\":[]}");

        assertEquals("", manifest.forgeVersion());
    }

    @Test
    void survivesAnEmptyPack() {
        PackManifest manifest = PackManifest.parse("{\"minecraft\":{\"version\":\"1.19.2\"},\"files\":[]}");

        assertTrue(manifest.files.isEmpty());
        assertTrue(manifest.describe().contains("0 mods"));
    }
}
