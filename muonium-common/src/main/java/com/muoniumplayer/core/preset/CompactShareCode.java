package com.muoniumplayer.core.preset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.codec.Base62;
import com.muoniumplayer.core.preset.codec.JsonPaths;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetJson;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** MPP2 wire format: published domain/key numbers, delta tags, optional raw deflate and CRC16. */
public final class CompactShareCode {
    public static final String PREFIX = "MPP2:";
    static final int VERSION = 2;
    private static final int FLAG_BASE_DEFAULTS = 1, FLAG_DEFLATED = 2;
    static final int W_FALSE = 0, W_TRUE = 1, W_INT = 2, W_DECIMAL = 3, W_FLOAT = 4,
            W_STRING = 5, W_JSON = 6, W_NULL = 7;
    static final int SIGNIFICANT_DIGITS = 4;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_CODE_CHARS = MAX_BODY_BYTES * 2;
    private static final int READABLE_NAME_CHARS = 16;
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private final List<PresetDomain> domains;
    public CompactShareCode(List<PresetDomain> domains) {
        Set<Integer> codes = new java.util.HashSet<Integer>();
        for (PresetDomain domain : domains) {
            if (domain.shareCode() <= 0 || !codes.add(domain.shareCode())) throw new IllegalArgumentException("重复或无效配置域编号");
            if (new java.util.HashSet<String>(domain.shareKeys()).size() != domain.shareKeys().size())
                throw new IllegalArgumentException("重复分享键编号：" + domain.id());
        }
        this.domains = Collections.unmodifiableList(new ArrayList<PresetDomain>(domains));
    }
    public String encode(StylePreset preset) {
        boolean fromDefaults = preset.base() == StylePreset.Base.DEFAULTS;
        Writer body = new Writer();
        body.varint(facetMask(preset.facets()));
        body.string(PresetJson.clean(preset.meta().name(), PresetLimits.MAX_NAME_CHARS));
        body.string(PresetJson.clean(preset.meta().author(), PresetLimits.MAX_AUTHOR_CHARS));
        for (PresetDomain domain : domains) {
            DomainFragment fragment = preset.domains().get(domain.id());
            if (fragment == null) continue;
            KeyPolicy policy = domain.policy();
            Map<String, JsonElement> values = JsonPaths.flatten(fragment.values(), policy::isRegistered);
            Map<String, JsonElement> defaults = fromDefaults ? JsonPaths.flatten(domain.defaults(), policy::isRegistered)
                    : Collections.<String, JsonElement>emptyMap();
            Writer entries = new Writer();
            List<String> keys = domain.shareKeys();
            int previous = 0;
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                JsonElement value = values.get(key);
                KeyPolicy.Rule rule = policy.rule(key);
                if (value == null || rule == null || rule.excluded() || !preset.facets().contains(rule.facet())) continue;
                if (fromDefaults && PresetEngine.sameValue(defaults.get(key), value)) continue;
                writeEntry(entries, i + 1 - previous, domain.compactValue(key, value));
                previous = i + 1;
            }
            if (entries.size() > 0) {
                body.varint(domain.shareCode()); body.varint(entries.size()); body.raw(entries.toByteArray());
            }
        }
        byte[] plain = body.toByteArray();
        byte[] packed = deflate(plain);
        boolean deflated = packed.length < plain.length;
        int header = (VERSION << 4) | (fromDefaults ? FLAG_BASE_DEFAULTS : 0) | (deflated ? FLAG_DEFLATED : 0);
        int crc = crc16(header, plain);
        byte[] payload = deflated ? packed : plain;
        byte[] bytes = new byte[1 + payload.length + 2];
        bytes[0] = (byte) header;
        System.arraycopy(payload, 0, bytes, 1, payload.length);
        bytes[bytes.length - 2] = (byte) (crc >>> 8); bytes[bytes.length - 1] = (byte) crc;
        return PREFIX + Base62.encode(bytes);
    }
    public String shareLine(StylePreset preset) {
        String name = PresetJson.clean(preset.meta().name(), READABLE_NAME_CHARS);
        return "【模板预设】" + (name.isEmpty() ? "" : name + " ") + encode(preset);
    }
    private static void writeEntry(Writer out, int codeDelta, JsonElement value) {
        long tag = (long) codeDelta << 3;
        if (value == null || value.isJsonNull()) { out.varint(tag | W_NULL); return; }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) { out.varint(tag | (primitive.getAsBoolean() ? W_TRUE : W_FALSE)); return; }
            if (primitive.isNumber()) { writeNumber(out, tag, primitive); return; }
            out.varint(tag | W_STRING); out.string(primitive.getAsString()); return;
        }
        out.varint(tag | W_JSON); out.string(COMPACT.toJson(value));
    }
    private static void writeNumber(Writer out, long tag, JsonPrimitive primitive) {
        if (!Float.isFinite(primitive.getAsFloat())) throw new IllegalArgumentException("预设数值无效");
        BigDecimal exact;
        try {
            exact = primitive.getAsBigDecimal().stripTrailingZeros();
            if (exact.scale() > 0) exact = exact.round(new MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        } catch (RuntimeException notDecimal) {
            out.varint(tag | W_FLOAT); out.int32(Float.floatToIntBits(primitive.getAsFloat())); return;
        }
        if (exact.scale() <= 0 && exact.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE >> 4)) < 0) {
            out.varint(tag | W_INT); out.varint(zigzag(exact.longValueExact())); return;
        }
        BigInteger unscaled = exact.unscaledValue();
        if (exact.scale() >= 1 && exact.scale() <= 7 && unscaled.bitLength() < 56) {
            long packed = (zigzag(unscaled.longValue()) << 3) | exact.scale();
            if (varintSize(packed) <= 4) { out.varint(tag | W_DECIMAL); out.varint(packed); return; }
        }
        out.varint(tag | W_FLOAT); out.int32(Float.floatToIntBits(primitive.getAsFloat()));
    }
    public static boolean contains(String text) { return text != null && text.contains(PREFIX); }
    public StylePreset decode(String text) throws PresetFormatException {
        if (text == null || text.length() > PresetLimits.MAX_SHARE_TEXT_CHARS) throw new PresetFormatException("剪贴板里的内容过长，不像是分享码");
        int at = text.indexOf(PREFIX);
        if (at < 0) throw new PresetFormatException("没有找到分享码（以 " + PREFIX + " 开头）");
        int start = at + PREFIX.length(), end = start;
        while (end < text.length() && Base62.isDigit(text.charAt(end))) end++;
        if (end - start > MAX_CODE_CHARS) throw new PresetFormatException("分享码过长，已拒绝读取");
        byte[] bytes;
        try { bytes = Base62.decode(text.substring(start, end)); }
        catch (IllegalArgumentException broken) { throw new PresetFormatException("分享码里有无法识别的字符", broken); }
        if (bytes.length < 4) throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
        int header = bytes[0] & 0xFF, version = header >>> 4;
        if (version > VERSION) throw new PresetFormatException("这个分享码来自更新版本的 MuoniumPlayer，请先更新再导入");
        if (version != VERSION || (header & 0x0C) != 0) throw new PresetFormatException("分享码版本或标志无效");
        byte[] payload = new byte[bytes.length - 3];
        System.arraycopy(bytes, 1, payload, 0, payload.length);
        byte[] plain = (header & FLAG_DEFLATED) != 0 ? inflate(payload) : payload;
        if (plain.length > MAX_BODY_BYTES) throw new PresetFormatException("分享码解开后过大，已拒绝读取");
        int expected = ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
        if (crc16(header, plain) != expected) throw new PresetFormatException("分享码校验失败（可能被改动或复制不完整）");
        try { return readBody(new Reader(plain), (header & FLAG_BASE_DEFAULTS) != 0); }
        catch (IndexOutOfBoundsException | IllegalStateException | NumberFormatException broken) {
            throw new PresetFormatException("分享码内容损坏", broken);
        }
    }
    private StylePreset readBody(Reader in, boolean fromDefaults) throws PresetFormatException {
        long mask = in.varint();
        if (mask < 0) throw new IllegalStateException("分面掩码无效");
        Set<Facet> facets = EnumSet.noneOf(Facet.class);
        List<String> unknownFacets = new ArrayList<String>();
        for (int bit = 0; bit < 63; bit++) {
            if ((mask & (1L << bit)) == 0) continue;
            Facet facet = Facet.byCode(bit + 1);
            if (facet != null) facets.add(facet); else unknownFacets.add("code-" + (bit + 1));
        }
        String name = PresetJson.clean(in.string(PresetLimits.MAX_NAME_CHARS * 4), PresetLimits.MAX_NAME_CHARS);
        String author = PresetJson.clean(in.string(PresetLimits.MAX_AUTHOR_CHARS * 4), PresetLimits.MAX_AUTHOR_CHARS);
        Map<String, DomainFragment> fragments = new LinkedHashMap<String, DomainFragment>();
        Set<Integer> seen = new java.util.HashSet<Integer>();
        int domainCount = 0;
        while (in.remaining() > 0) {
            if (++domainCount > PresetLimits.MAX_DOMAINS) throw new PresetFormatException("分享码里的配置域过多，已拒绝读取");
            int domainCode = in.positiveInt();
            if (!seen.add(domainCode)) throw new IllegalStateException("重复配置域");
            byte[] chunk = in.bytes(in.length());
            PresetDomain domain = byShareCode(domainCode);
            if (domain == null) { fragments.put("code-" + domainCode, new DomainFragment(1, new JsonObject())); continue; }
            List<String> keys = domain.shareKeys();
            Map<String, JsonElement> flat = new LinkedHashMap<String, JsonElement>();
            Reader entries = new Reader(chunk);
            long code = 0;
            while (entries.remaining() > 0) {
                if (flat.size() >= PresetLimits.MAX_KEYS_PER_DOMAIN) throw new PresetFormatException("分享码里的设置项过多，已拒绝读取");
                long tag = entries.varint(), delta = tag >>> 3;
                if (delta < 1 || delta > Integer.MAX_VALUE || code + delta > Integer.MAX_VALUE) throw new IllegalStateException("键编号不递增");
                code += delta;
                JsonElement value = readValue(entries, (int) (tag & 7));
                String key = code <= keys.size() ? keys.get((int) code - 1) : "code-" + code;
                flat.put(key, value);
            }
            fragments.put(domain.id(), new DomainFragment(domain.fragmentVersion(), JsonPaths.unflatten(flat)));
        }
        return new StylePreset(new PresetMeta("", name.isEmpty() ? "分享的预设" : name, author, "", "", "", ""),
                fromDefaults ? StylePreset.Base.DEFAULTS : StylePreset.Base.CURRENT, facets, unknownFacets, fragments, StylePreset.Source.USER, null);
    }
    private static JsonElement readValue(Reader in, int wire) throws PresetFormatException {
        switch (wire) {
            case W_FALSE: return new JsonPrimitive(false);
            case W_TRUE: return new JsonPrimitive(true);
            case W_INT: return new JsonPrimitive(unzigzag(in.varint()));
            case W_DECIMAL: {
                long packed = in.varint();
                return new JsonPrimitive(BigDecimal.valueOf(unzigzag(packed >>> 3), (int) (packed & 7)));
            }
            case W_FLOAT: {
                float value = Float.intBitsToFloat(in.int32());
                if (!Float.isFinite(value)) throw new IllegalStateException("数值无效");
                return new JsonPrimitive(value);
            }
            case W_STRING: return new JsonPrimitive(in.string(PresetLimits.MAX_VALUE_STRING_CHARS));
            case W_JSON: {
                String json = in.string(PresetLimits.MAX_VALUE_STRING_CHARS * 2);
                if (PresetLimits.nestingDepth(json) > PresetLimits.MAX_DEPTH) throw new PresetFormatException("分享码里的内容嵌套过深，已拒绝读取");
                try { return JsonParser.parseString(json); }
                catch (RuntimeException broken) { throw new PresetFormatException("分享码内容损坏", broken); }
            }
            default: return JsonNull.INSTANCE;
        }
    }
    private PresetDomain byShareCode(int code) {
        for (PresetDomain domain : domains) if (domain.shareCode() == code) return domain;
        return null;
    }
    static long facetMask(Set<Facet> facets) {
        long mask = 0;
        for (Facet facet : facets) mask |= 1L << (facet.code() - 1);
        return mask;
    }
    private static int crc16(int header, byte[] plain) {
        CRC32 crc = new CRC32(); crc.update(header); crc.update(plain, 0, plain.length);
        return (int) (crc.getValue() & 0xFFFF);
    }
    private static byte[] deflate(byte[] plain) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(plain); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 16);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally { deflater.end(); }
    }
    private static byte[] inflate(byte[] packed) throws PresetFormatException {
        Inflater inflater = new Inflater(true);
        try {
            byte[] input = new byte[packed.length + 1];
            System.arraycopy(packed, 0, input, 0, packed.length);
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, packed.length * 3 + 16));
            byte[] buffer = new byte[4096];
            int stalls = 0;
            while (!inflater.finished()) {
                int n;
                try { n = inflater.inflate(buffer); }
                catch (DataFormatException broken) { throw new PresetFormatException("分享码已损坏，无法解开", broken); }
                if (n == 0) {
                    if (inflater.finished()) break;
                    if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 8) throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
                    continue;
                }
                stalls = 0;
                if (out.size() + n > MAX_BODY_BYTES) throw new PresetFormatException("分享码解开后过大，已拒绝读取");
                out.write(buffer, 0, n);
            }
            if (inflater.getRemaining() > 1) throw new PresetFormatException("分享码内容损坏（多余的压缩数据）");
            return out.toByteArray();
        } finally { inflater.end(); }
    }
    static long zigzag(long value) { return (value << 1) ^ (value >> 63); }
    static long unzigzag(long value) { return (value >>> 1) ^ -(value & 1); }
    static int varintSize(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0) { value >>>= 7; size++; }
        return size;
    }
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void varint(long value) {
            while ((value & ~0x7FL) != 0) { out.write((int) ((value & 0x7F) | 0x80)); value >>>= 7; }
            out.write((int) value); check();
        }
        void int32(int value) { out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value); check(); }
        void string(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            varint(bytes.length); out.write(bytes, 0, bytes.length); check();
        }
        void raw(byte[] bytes) { out.write(bytes, 0, bytes.length); check(); }
        int size() { return out.size(); }
        byte[] toByteArray() { return out.toByteArray(); }
        private void check() { if (out.size() > MAX_BODY_BYTES) throw new IllegalArgumentException("分享码内容过大"); }
    }
    private static final class Reader {
        private final byte[] data;
        private int at;
        Reader(byte[] data) { this.data = data; }
        int remaining() { return data.length - at; }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at >= data.length) throw new IndexOutOfBoundsException("varint 越界");
                int b = data[at++] & 0xFF;
                if (shift == 63 && (b & 0xFE) != 0) throw new IllegalStateException("varint 溢出");
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalStateException("varint 过长");
        }
        int positiveInt() { int value = length(); if (value <= 0) throw new IllegalStateException("编号无效"); return value; }
        int length() { long value = varint(); if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalStateException("长度越界"); return (int) value; }
        int int32() {
            byte[] four = bytes(4);
            return ((four[0] & 0xFF) << 24) | ((four[1] & 0xFF) << 16) | ((four[2] & 0xFF) << 8) | (four[3] & 0xFF);
        }
        byte[] bytes(int length) {
            if (length < 0 || length > remaining()) throw new IndexOutOfBoundsException("长度越界：" + length);
            byte[] out = new byte[length]; System.arraycopy(data, at, out, 0, length); at += length; return out;
        }
        String string(int maxChars) {
            int length = length();
            if (length > (long) maxChars * 4 || length > remaining()) throw new IllegalStateException("字符串过长");
            String text = new String(bytes(length), StandardCharsets.UTF_8);
            if (text.length() > maxChars) throw new IllegalStateException("字符串过长");
            return text;
        }
    }
}
