package com.muoniumplayer.chat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatConfigTest {
    @Test
    void validatesServerEndpointWithoutAllowingCredentialsOrQueryTokens() {
        assertEquals("wss://chat.example.net/ws", ChatConfig.normalizeEndpoint("wss://Chat.Example.NET/ws"));
        assertEquals("ws://127.0.0.1:8791/ws", ChatConfig.normalizeEndpoint("ws://127.0.0.1:8791/ws"));
        assertEquals("ws://localhost/ws", ChatConfig.normalizeEndpoint("ws://localhost/ws"));
        assertNull(ChatConfig.normalizeEndpoint("ws://chat.example.net/ws"));
        assertNull(ChatConfig.normalizeEndpoint("https://chat.example.net/ws"));
        assertNull(ChatConfig.normalizeEndpoint("wss://user:password@chat.example.net/ws"));
        assertNull(ChatConfig.normalizeEndpoint("wss://chat.example.net/ws?token=secret"));
        assertNull(ChatConfig.normalizeEndpoint("wss://chat.example.net/other"));
    }
}
