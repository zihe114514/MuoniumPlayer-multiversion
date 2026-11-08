package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PresetConfigBridgeTest {
    @TempDir Path temp;
    private FakeDomain hud;
    private PresetService service;

    @BeforeEach void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder().facet(Facet.LYRICS, "glow").exclude("local", "machineOnly").build(),
                PresetEngineTest.json("{\"glow\":0.5,\"machineOnly\":\"local\"}"));
        service = new PresetService(new PresetEngine(Collections.singletonList(hud)),
                new PresetRepository(temp.resolve("presets").toFile(), temp.resolve("backups").toFile(), name -> null));
    }

    @Test void nativeUiRegistrationUsesActualProtocolAndRetainsFallback() {
        List<String> modes = new ArrayList<>();
        Function<String, Boolean> opener = mode -> { modes.add(mode); return !"import".equals(mode); };
        Function<String, Boolean> other = mode -> true;
        try {
            assertFalse(PresetConfigBridge.registerUi(PresetConfigBridge.PROTOCOL_VERSION + 1, opener));
            assertTrue(PresetConfigBridge.registerUi(PresetConfigBridge.PROTOCOL_VERSION, opener));
            assertFalse(PresetConfigBridge.registerUi(PresetConfigBridge.PROTOCOL_VERSION, other));
            PresetConfigBridge.unregisterUi(other);
            assertTrue(PresetConfigBridge.tryOpenUi("manage"));
            assertFalse(PresetConfigBridge.tryOpenUi("import"));
            assertFalse(PresetConfigBridge.tryOpenUi("MANAGE"));
            assertEquals(List.of("manage", "import"), modes);
            assertTrue(JsonParser.parseString(PresetConfigBridge.capabilities()).getAsJsonObject().get("nativeUiRegistered").getAsBoolean());
        } finally { PresetConfigBridge.unregisterUi(opener); }
        assertFalse(PresetConfigBridge.tryOpenUi("manage"));
    }

    @Test void throwingNativeUiNeverBlocksBuiltInPage() {
        Function<String, Boolean> opener = mode -> { throw new LinkageError("old host"); };
        try {
            assertTrue(PresetConfigBridge.registerUi(PresetConfigBridge.PROTOCOL_VERSION, opener));
            assertFalse(PresetConfigBridge.tryOpenUi("save"));
        } finally { PresetConfigBridge.unregisterUi(opener); }
    }

    @Test void requestDoesNotMutateBeforeMainThreadDispatchAndCancellationSkipsAction() {
        List<Runnable> queue = new ArrayList<>();
        CompletableFuture<String> result = PresetConfigBridge.enqueue(queue::add, () -> {
            hud.state.addProperty("glow", 0.8);
            return "done";
        });
        assertFalse(result.isDone()); assertEquals(0.5, hud.state.get("glow").getAsDouble());
        queue.remove(0).run(); assertEquals("done", result.join());
        CompletableFuture<String> cancelled = PresetConfigBridge.enqueue(queue::add, () -> fail("Cancelled action ran"));
        cancelled.cancel(false); queue.remove(0).run(); assertTrue(cancelled.isCancelled());
    }

    @Test void actionProtocolRejectsOversizeDeepAndLocalRestoreRequests() {
        assertFalse(json(PresetConfigBridge.execute(service, "x".repeat(PresetLimits.MAX_SHARE_TEXT_CHARS + 4097))).get("ok").getAsBoolean());
        assertFalse(json(PresetConfigBridge.execute(service, "[".repeat(20) + "0" + "]".repeat(20))).get("ok").getAsBoolean());
        assertFalse(json(PresetConfigBridge.execute(service, "{\"protocolVersion\":99,\"action\":\"catalog\"}")).get("ok").getAsBoolean());
        assertFalse(json(PresetConfigBridge.execute(service, "{\"protocolVersion\":\"1\",\"action\":\"catalog\"}")).get("ok").getAsBoolean());
        String local = PresetConfigBridge.localEnvelope(service).toString();
        assertFalse(json(PresetConfigBridge.execute(service, local)).get("ok").getAsBoolean());
        JsonObject catalog = json(PresetConfigBridge.execute(service, "{\"action\":\"catalog\"}"));
        assertEquals(PresetConfigBridge.PROTOCOL_VERSION, catalog.get("protocolVersion").getAsInt());
        assertFalse(catalog.toString().contains("machineOnly"));
    }

    @Test void trustedLocalRestoreRoundTripsExcludedFieldsAndReportsTransactionFailure() {
        JsonObject local = PresetConfigBridge.localEnvelope(service);
        hud.state.addProperty("machineOnly", "changed"); hud.state.addProperty("glow", 0.9);
        assertTrue(PresetConfigBridge.restoreLocal(service, local.toString()).get("ok").getAsBoolean());
        assertEquals("local", hud.state.get("machineOnly").getAsString());
        hud.state.addProperty("glow", 0.8); JsonObject before = hud.snapshot();
        hud.failOnRestore = new IllegalStateException("restore failed"); hud.mutateBeforeFailure = true;
        JsonObject failure = PresetConfigBridge.restoreLocal(service, local.toString());
        assertFalse(failure.get("ok").getAsBoolean()); assertTrue(failure.getAsJsonObject("report").get("rolledBack").getAsBoolean());
        assertEquals(before, hud.state);
        local.addProperty("version", 99);
        assertFalse(PresetConfigBridge.restoreLocal(service, local.toString()).get("ok").getAsBoolean());
        assertEquals(before, hud.state);
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}
