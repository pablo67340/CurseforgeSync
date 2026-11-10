package com.curseforgesync.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {
    @Test
    void parsesTheShapesTheCurseForgeApiReturns() {
        Map<String, Object> root = Json.parseObject(
                "{\"data\":{\"id\":1650052,\"name\":\"RP Tubes\",\"allowModDistribution\":true,"
                        + "\"hashes\":[{\"value\":\"abc\",\"algo\":1},{\"value\":\"def\",\"algo\":2}],"
                        + "\"gameVersions\":[\"1.19.2\",\"Forge\",\"Server\"],\"fileLength\":393310}}");

        Map<String, Object> data = Json.obj(root, "data");
        assertEquals(1650052, Json.intVal(data, "id", 0));
        assertEquals("RP Tubes", Json.str(data, "name", ""));
        assertTrue(Json.bool(data, "allowModDistribution", false));
        assertEquals(393310L, Json.num(data, "fileLength", 0));
        assertEquals(List.of("1.19.2", "Forge", "Server"), Json.strings(data, "gameVersions"));
        assertEquals(2, Json.arr(data, "hashes").size());
    }

    @Test
    void keepsLargeFileIdsExactRatherThanRoundingThroughDouble() {
        Map<String, Object> root = Json.parseObject("{\"fileID\":9007199254740993}");
        assertEquals(9007199254740993L, Json.num(root, "fileID", 0));
    }

    @Test
    void acceptsCommentsAndTrailingCommasSoTheConfigCanExplainItself() {
        Map<String, Object> root = Json.parseObject(
                "{\n  // which pack to follow\n  \"modpackProjectId\": 42, /* inline */\n"
                        + "  \"excludeMods\": [\"a\", \"b\",],\n}");
        assertEquals(42, Json.intVal(root, "modpackProjectId", 0));
        assertEquals(List.of("a", "b"), Json.strings(root, "excludeMods"));
    }

    @Test
    void handlesEscapesAndUnicode() {
        Map<String, Object> root = Json.parseObject("{\"a\":\"line\\nbreak \\u00e9 \\\"quoted\\\" c:\\\\path\"}");
        assertEquals("line\nbreak \u00e9 \"quoted\" c:\\path", Json.str(root, "a", ""));
    }

    @Test
    void distinguishesMissingKeysFromNullValues() {
        Map<String, Object> root = Json.parseObject("{\"downloadUrl\":null}");
        assertTrue(root.containsKey("downloadUrl"));
        assertNull(root.get("downloadUrl"));
        assertEquals("fallback", Json.str(root, "downloadUrl", "fallback"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1}{"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,2"));
    }

    @Test
    void roundTripsThroughTheWriter() {
        String original = "{\"packFileId\":123,\"installed\":[{\"fileName\":\"a.jar\",\"side\":\"BOTH\"}],"
                + "\"empty\":[],\"flag\":true}";
        Object parsed = Json.parse(original);
        Map<String, Object> reparsed = Json.parseObject(Json.write(parsed));

        assertEquals(123, Json.intVal(reparsed, "packFileId", 0));
        assertTrue(Json.bool(reparsed, "flag", false));
        assertTrue(Json.arr(reparsed, "empty").isEmpty());
        assertEquals("a.jar", Json.str(Json.asObject(Json.arr(reparsed, "installed").get(0)), "fileName", ""));
    }

    @Test
    void theDefaultConfigTemplateIsItselfParseable() {
        SyncConfig config = new SyncConfig();
        config.readFrom(Json.parseObject(SyncConfig.template()));

        assertTrue(config.enabled);
        assertEquals(0, config.modpackProjectId);
        assertEquals(SyncConfig.Mode.TRACKED, config.mode);
        assertTrue(config.sideAuto);
        assertTrue(config.filterClientMods);
        assertEquals(SyncConfig.RestartMode.EXIT, config.restartMode);
    }
}
