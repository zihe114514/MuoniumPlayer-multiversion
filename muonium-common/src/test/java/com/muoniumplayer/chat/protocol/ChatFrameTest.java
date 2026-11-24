package com.muoniumplayer.chat.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatFrameTest {
    @Test
    void buildsVersionedRequestWithGeneratedId() throws Exception {
        JsonObject request = ChatFrame.request("ping", ChatFrame.data("ts", 17));
        ChatFrame.Inbound parsed = ChatFrame.parse(request.toString());
        assertEquals("ping", parsed.type);
        assertEquals(17, parsed.data.get("ts").getAsInt());
        assertEquals(1, request.get("v").getAsInt());
    }

    @Test
    void rejectsUnknownEnvelopeFieldsAndWrongVersions() {
        assertThrows(ChatFrame.ProtocolException.class,
                () -> ChatFrame.parse("{\"v\":1,\"t\":\"ping\",\"d\":{},\"extra\":true}"));
        assertThrows(ChatFrame.ProtocolException.class,
                () -> ChatFrame.parse("{\"v\":2,\"t\":\"ping\",\"d\":{}}"));
    }

    @Test
    void rejectsOversizedOrNonObjectFrames() {
        assertThrows(ChatFrame.ProtocolException.class, () -> ChatFrame.parse("[]"));
        assertThrows(ChatFrame.ProtocolException.class,
                () -> ChatFrame.parse("x".repeat(ChatFrame.MAX_FRAME_BYTES + 1)));
    }

    @Test
    void acceptsShareFramesAtShareLimitAndRejectsOversizedOrdinaryFrames() throws Exception {
        String shareText = "x".repeat(ChatFrame.MAX_FRAME_BYTES + 32);
        String shareFrame = "{\"v\":1,\"t\":\"msg.new\",\"d\":{\"kind\":\"share\",\"text\":\""
                + shareText + "\"}}";
        assertEquals("msg.new", ChatFrame.parse(shareFrame).type);

        String ordinaryFrame = "{\"v\":1,\"t\":\"msg.new\",\"d\":{\"kind\":\"text\",\"text\":\""
                + shareText + "\"}}";
        assertThrows(ChatFrame.ProtocolException.class, () -> ChatFrame.parse(ordinaryFrame));
    }

    @Test
    void rejectsUnknownPayloadFields() {
        JsonObject payload = new JsonObject();
        payload.addProperty("channel", "#大厅");
        payload.addProperty("role", "admin");
        assertThrows(ChatFrame.ProtocolException.class,
                () -> ChatFrame.requireKeys(payload, java.util.Set.of("channel", "password")));
    }
}
