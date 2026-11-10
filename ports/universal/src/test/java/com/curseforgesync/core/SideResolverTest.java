package com.curseforgesync.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideResolverTest {
    private static CurseForgeApi.Project project(int id, String slug) {
        return new CurseForgeApi.Project(Json.parseObject(
                "{\"id\":" + id + ",\"name\":\"" + slug + "\",\"slug\":\"" + slug + "\",\"classId\":6}"));
    }

    private static CurseForgeApi.File file(String... environmentTags) {
        StringBuilder versions = new StringBuilder("[\"1.19.2\",\"Forge\"");
        for (String tag : environmentTags) {
            versions.append(",\"").append(tag).append('"');
        }
        versions.append(']');
        return new CurseForgeApi.File(Json.parseObject(
                "{\"id\":1,\"modId\":2,\"fileName\":\"x.jar\",\"gameVersions\":" + versions + "}"));
    }

    @Test
    void unknownModsAreInstalledEverywhere() {
        SideResolver resolver = new SideResolver(new SyncConfig());
        SideResolver.Verdict verdict = resolver.resolve(project(1, "some-obscure-mod"), file());

        assertEquals(Side.BOTH, verdict.side);
        assertTrue(verdict.side.neededOn(Side.SERVER));
    }

    @Test
    void recognisesTheUsualClientOnlySuspects() {
        SideResolver resolver = new SideResolver(new SyncConfig());

        for (String slug : new String[]{"rubidium", "oculus", "inventory-tweaks", "mouse-tweaks", "sodium"}) {
            SideResolver.Verdict verdict = resolver.resolve(project(1, slug), file());
            assertEquals(Side.CLIENT, verdict.side, slug + " should be client-only");
            assertFalse(verdict.side.neededOn(Side.SERVER), slug + " should not be installed on a server");
        }
    }

    @Test
    void leavesMixedSideMainstaysAlone() {
        SideResolver resolver = new SideResolver(new SyncConfig());

        // These all have a real server-side component; misfiling one breaks a server.
        for (String slug : new String[]{"jei", "jade", "journeymap", "ferritecore", "modernfix", "create"}) {
            assertEquals(Side.BOTH, resolver.resolve(project(1, slug), file()).side, slug + " must stay on both");
        }
    }

    @Test
    void usesTheAuthorsEnvironmentTagWhenTheSlugIsUnknown() {
        SideResolver resolver = new SideResolver(new SyncConfig());

        assertEquals(Side.CLIENT, resolver.resolve(project(1, "brand-new-shader"), file("Client")).side);
        assertEquals(Side.SERVER, resolver.resolve(project(1, "brand-new-daemon"), file("Server")).side);
        assertEquals(Side.BOTH, resolver.resolve(project(1, "brand-new-thing"), file("Client", "Server")).side);
    }

    @Test
    void configOverridesBeatEverythingElse() {
        SyncConfig config = new SyncConfig();
        config.clientOnlyMods.add("some-mod");
        config.serverOnlyMods.add("1234");
        SideResolver resolver = new SideResolver(config);

        // Tagged for both sides on CurseForge, but the admin said client-only.
        assertEquals(Side.CLIENT, resolver.resolve(project(1, "some-mod"), file("Client", "Server")).side);
        // Matched by numeric project ID rather than slug.
        assertEquals(Side.SERVER, resolver.resolve(project(1234, "whatever"), file()).side);
    }

    @Test
    void forceIncludeAndExcludeMatchOnEitherSlugOrId() {
        SyncConfig config = new SyncConfig();
        config.forceIncludeMods.add("rubidium");
        config.excludeMods.add("9999");
        SideResolver resolver = new SideResolver(config);

        assertTrue(resolver.isForceIncluded(project(1, "rubidium")));
        assertFalse(resolver.isForceIncluded(project(1, "oculus")));
        assertTrue(resolver.isExcluded(project(9999, "anything")));
        assertFalse(resolver.isExcluded(project(1, "anything")));
    }

    @Test
    void sideMatchingIsSymmetric() {
        assertTrue(Side.BOTH.neededOn(Side.SERVER));
        assertTrue(Side.BOTH.neededOn(Side.CLIENT));
        assertTrue(Side.SERVER.neededOn(Side.SERVER));
        assertFalse(Side.SERVER.neededOn(Side.CLIENT));
        assertFalse(Side.CLIENT.neededOn(Side.SERVER));
    }

    @Test
    void slugMatchingIgnoresCaseAndSurroundingSpace() {
        SyncConfig config = new SyncConfig();
        config.readFrom(Json.parseObject("{\"clientOnlyMods\":[\"  Some-Mod  \"]}"));
        SideResolver resolver = new SideResolver(config);

        assertEquals(Side.CLIENT, resolver.resolve(project(1, "SOME-MOD"), file()).side);
    }

    @Test
    void fileTagsAreReadOffTheGameVersionsArray() {
        CurseForgeApi.File clientOnly = file("Client");
        assertTrue(clientOnly.taggedClient());
        assertFalse(clientOnly.taggedServer());
        assertTrue(clientOnly.supports("1.19.2"));
        assertFalse(clientOnly.supports("1.20.1"));
    }

    @Test
    void treatsAbsentDistributionFlagAsAllowed() {
        Map<String, Object> withoutFlag = Json.parseObject("{\"id\":1,\"name\":\"x\",\"slug\":\"x\"}");
        Map<String, Object> optedOut = Json.parseObject("{\"id\":1,\"name\":\"x\",\"slug\":\"x\","
                + "\"allowModDistribution\":false}");

        assertTrue(new CurseForgeApi.Project(withoutFlag).allowModDistribution);
        assertFalse(new CurseForgeApi.Project(optedOut).allowModDistribution);
    }
}
