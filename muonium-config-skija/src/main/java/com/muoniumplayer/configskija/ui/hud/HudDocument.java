package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.model.HudElement.Anchor;

import java.io.*;
import java.util.*;

/** Detached profile document. No Java object serialization, native handles or game classes. */
public record HudDocument(int schema, List<HudDesigner.State> elements) {
    public static final int SCHEMA = 1;

    public HudDocument {
        if (schema != SCHEMA)
            throw new IllegalArgumentException("Unsupported HUD schema: " + schema);
        elements = List.copyOf(elements);
        if (elements.size() > 512) throw new IllegalArgumentException("Too many HUDs");
        Map<String, HudDesigner.State> ids = new HashMap<>();
        for (var state : elements) {
            if (state.id().isBlank()
                    || state.id().length() > 256
                    || state.type().isBlank()
                    || state.type().length() > 128)
                throw new IllegalArgumentException("Invalid HUD identity");
            if (ids.put(state.id(), state) != null)
                throw new IllegalArgumentException("Duplicate HUD id");
            finite(state.x(), state.y(), state.width(), state.height(), state.scale());
            if (state.width() < 0 || state.height() < 0 || state.scale() <= 0)
                throw new IllegalArgumentException("Invalid HUD dimensions");
            if (state.properties().size() > 256)
                throw new IllegalArgumentException("Too many HUD properties");
            for (var property : state.properties().entrySet()) {
                if (property.getKey().length() > 1024 || property.getValue().length() > 8192)
                    throw new IllegalArgumentException("HUD property too long");
            }
            if (state.anchor() != null) finite(state.anchor().offsetX(), state.anchor().offsetY());
        }
        for (var state : elements) {
            Set<String> visited = new HashSet<>();
            String current = state.id();
            for (int depth = 0; current != null; depth++) {
                if (!visited.add(current)) throw new IllegalArgumentException("Cyclic HUD anchors");
                if (depth > 16) throw new IllegalArgumentException("HUD anchor chain too deep");
                var item = ids.get(current);
                current = item == null || item.anchor() == null ? null : item.anchor().parent();
            }
        }
    }

    public HudDocument(List<HudDesigner.State> elements) {
        this(SCHEMA, elements);
    }

    public static HudDocument empty() {
        return new HudDocument(List.of());
    }

    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4d485544);
            out.writeInt(schema);
            out.writeInt(elements.size());
            for (var s : elements) {
                out.writeUTF(s.id());
                out.writeUTF(s.type());
                out.writeDouble(s.x());
                out.writeDouble(s.y());
                out.writeDouble(s.width());
                out.writeDouble(s.height());
                out.writeDouble(s.scale());
                out.writeBoolean(s.hidden());
                out.writeBoolean(s.locked());
                out.writeInt(s.order());
                out.writeInt(s.properties().size());
                for (var p : new TreeMap<>(s.properties()).entrySet()) {
                    out.writeUTF(p.getKey());
                    out.writeUTF(p.getValue());
                }
                var a = s.anchor();
                out.writeBoolean(a != null);
                if (a != null) {
                    out.writeUTF(a.parent());
                    out.writeUTF(a.target().name());
                    out.writeUTF(a.self().name());
                    out.writeDouble(a.offsetX());
                    out.writeDouble(a.offsetY());
                }
            }
            out.flush();
            if (bytes.size() > 2_000_000)
                throw new IllegalArgumentException("HUD document too large");
            return "MHUD1:" + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to encode HUD document", e);
        }
    }

    public static HudDocument decode(String encoded) {
        if (encoded == null || !encoded.startsWith("MHUD1:") || encoded.length() > 2_700_000)
            throw new IllegalArgumentException("Invalid HUD document header/size");
        try {
            DataInputStream in =
                    new DataInputStream(
                            new ByteArrayInputStream(
                                    Base64.getDecoder().decode(encoded.substring(6))));
            if (in.readInt() != 0x4d485544) throw new IllegalArgumentException("Invalid HUD magic");
            int schema = in.readInt(), count = bounded(in.readInt(), 512);
            List<HudDesigner.State> states = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = string(in, 256), type = string(in, 128);
                double x = in.readDouble(),
                        y = in.readDouble(),
                        w = in.readDouble(),
                        h = in.readDouble(),
                        s = in.readDouble();
                boolean hidden = in.readBoolean(), locked = in.readBoolean();
                int order = in.readInt(), properties = bounded(in.readInt(), 256);
                Map<String, String> values = new LinkedHashMap<>();
                for (int j = 0; j < properties; j++) {
                    String key = string(in, 1024), value = string(in, 8192);
                    if (values.put(key, value) != null)
                        throw new IllegalArgumentException("Duplicate property");
                }
                HudDesigner.Link link = null;
                if (in.readBoolean())
                    link =
                            new HudDesigner.Link(
                                    string(in, 256),
                                    Anchor.valueOf(string(in, 32)),
                                    Anchor.valueOf(string(in, 32)),
                                    in.readDouble(),
                                    in.readDouble());
                states.add(
                        new HudDesigner.State(
                                id, x, y, w, h, s, hidden, locked, type, values, link, order));
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing HUD data");
            return new HudDocument(schema, states);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid HUD document", e);
        }
    }

    private static String string(DataInputStream in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IllegalArgumentException("HUD string too long");
        return s;
    }

    private static int bounded(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Invalid HUD count");
        return value;
    }

    private static void finite(double... values) {
        for (double d : values)
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite HUD data");
    }
}
