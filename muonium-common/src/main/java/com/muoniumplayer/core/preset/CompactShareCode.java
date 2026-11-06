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

/**
 * 分享码 v2：{@code MPP2:<base62>}，几十到两三百个字符。
 *
 * <h3>为什么能短</h3>
 * <p>v1 是「整份 JSON 压缩后 base64」：键名、引号、默认值全在里面，一套全外观就是两三千字。v2 和游戏里的
 * 配装码同一个思路 —— 双方手里有同一份「字典」，码里只写编号和值：</p>
 * <ul>
 *   <li><b>键 → 编号</b>：每个配置域有一张只许追加的键表（{@link PresetDomain#shareKeys()}），码里写 1~2 字节的编号，
 *       不写 {@code "currentGlowStrength"} 这种二十几字节的名字；</li>
 *   <li><b>只写与出厂值不同的</b>：存为预设都是 {@code base: defaults}，没写的键套用时本来就会回到出厂值，
 *       所以等于默认的一个都不用写（多数人只改过几十项）；</li>
 *   <li><b>值按类型最短写</b>：布尔不占字节（编进线型里），整数 varint，小数保留 4 位有效数字后取最短十进制
 *       （拖动滑条得到的 0.5258621 写成 0.5259，相对误差不到万分之五，肉眼不可见），
 *       大块内容由配置域先压小（ViPER 预设体只留非默认字段）；</li>
 *   <li><b>键编号写差值</b>：条目按编号从小到大写，只记与上一条的差，多半一个字节就放得下；</li>
 *   <li>整体再试一次 deflate，变短才用；尾部 2 字节校验；最后 base62（只有字母数字）。</li>
 * </ul>
 *
 * <h3>二进制布局</h3>
 * <pre>
 * [头 1B] 高 4 位版本(=2) | 低 4 位标志(1=出厂基准, 2=正文已 deflate)
 * [正文]  varint 分面位掩码 · 名字(varint 长度 + UTF-8) · 作者(同)
 *         重复 { varint 域编号 · varint 字节数 · 条目… }      ← 有字节数，旧版本能整段跳过不认识的域
 *         条目 = varint(与上一条的编号差 &lt;&lt; 3 | 线型) + 值   （第一条的「上一条」算 0）
 * [校验 2B] CRC32(头 + 未压缩正文) 的低 16 位
 * </pre>
 * 线型：0 假 · 1 真 · 2 整数(zigzag varint) · 3 小数(varint(zigzag(尾数) &lt;&lt; 3 | 小数位数)) · 4 float32 ·
 * 5 字符串 · 6 JSON(对象/数组) · 7 null。线型自描述，所以新版本加的键旧版本能读过去（记作不支持）。
 *
 * <h3>和服务器短码的区别</h3>
 * <p>游戏的改枪码短到二十来位，是因为配装存在服务器上、码只是一条记录的编号。这里不依赖任何服务器：
 * 码本身就是全部内容，离线可用、永不过期；代价是长度随「改了多少项」增长。前缀 {@code MPP1R:}
 * 仍然留给将来的在线短码。</p>
 */
public final class CompactShareCode {

    public static final String PREFIX = "MPP2:";
    static final int VERSION = 2;

    private static final int FLAG_BASE_DEFAULTS = 1;
    private static final int FLAG_DEFLATED = 2;

    static final int W_FALSE = 0;
    static final int W_TRUE = 1;
    static final int W_INT = 2;
    static final int W_DECIMAL = 3;
    static final int W_FLOAT = 4;
    static final int W_STRING = 5;
    static final int W_JSON = 6;
    static final int W_NULL = 7;

    /**
     * 小数保留几位有效数字。设置里最窄的滑条（整行缩放 0.95~1.16）在 4 位下步长是量程的 0.5‰，
     * 比滑条自己能拖出来的分辨率还细；再少一位就开始有能察觉的偏差。
     */
    static final int SIGNIFICANT_DIGITS = 4;

    /** 解开后的正文上限：真实预设远小于这个数，超了就是坏码或炸弹。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_CODE_CHARS = MAX_BODY_BYTES * 2;
    private static final int MAX_SHORT_STRING = 64;
    private static final int READABLE_NAME_CHARS = 16;

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private final List<PresetDomain> domains;

    public CompactShareCode(List<PresetDomain> domains) {
        this.domains = Collections.unmodifiableList(new ArrayList<PresetDomain>(domains));
    }

    // ════════════════════════════ 编码 ════════════════════════════

    /** 只出分享码本身（{@code MPP2:…}）。 */
    public String encode(StylePreset preset) {
        boolean fromDefaults = preset.base() == StylePreset.Base.DEFAULTS;
        Writer body = new Writer();
        body.varint(facetMask(preset.facets()));
        body.string(PresetJson.clean(preset.meta().name(), PresetLimits.MAX_NAME_CHARS));
        body.string(PresetJson.clean(preset.meta().author(), PresetLimits.MAX_AUTHOR_CHARS));
        for (PresetDomain domain : domains) {
            DomainFragment fragment = preset.domains().get(domain.id());
            if (fragment == null) {
                continue;
            }
            KeyPolicy policy = domain.policy();
            Map<String, JsonElement> values = JsonPaths.flatten(fragment.values(), policy::isRegistered);
            Map<String, JsonElement> defaults = fromDefaults
                    ? JsonPaths.flatten(domain.defaults(), policy::isRegistered)
                    : Collections.<String, JsonElement>emptyMap();
            Writer entries = new Writer();
            List<String> keys = domain.shareKeys();
            int previous = 0;
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                JsonElement value = values.get(key);
                KeyPolicy.Rule rule = policy.rule(key);
                if (value == null || rule == null || rule.excluded() || !preset.facets().contains(rule.facet())) {
                    continue;
                }
                if (fromDefaults && PresetEngine.sameValue(defaults.get(key), value)) {
                    continue; // 出厂基准下，等于默认的键套用时本来就会回到默认：一个字节都不用写。
                }
                writeEntry(entries, i + 1 - previous, domain.compactValue(key, value));
                previous = i + 1;
            }
            if (entries.size() > 0) {
                body.varint(domain.shareCode());
                body.varint(entries.size());
                body.raw(entries.toByteArray());
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
        bytes[bytes.length - 2] = (byte) (crc >>> 8);
        bytes[bytes.length - 1] = (byte) crc;
        return PREFIX + Base62.encode(bytes);
    }

    /** 复制到剪贴板的一行：短名字 + 分享码。解码只认 {@link #PREFIX} 之后那一段，前面写什么都不影响。 */
    public String shareLine(StylePreset preset) {
        String name = PresetJson.clean(preset.meta().name(), READABLE_NAME_CHARS);
        return "【模板预设】" + (name.isEmpty() ? "" : name + " ") + encode(preset);
    }

    private static void writeEntry(Writer out, int codeDelta, JsonElement value) {
        long tag = (long) codeDelta << 3;
        if (value == null || value.isJsonNull()) {
            out.varint(tag | W_NULL);
            return;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.varint(tag | (primitive.getAsBoolean() ? W_TRUE : W_FALSE));
                return;
            }
            if (primitive.isNumber()) {
                writeNumber(out, tag, primitive);
                return;
            }
            out.varint(tag | W_STRING);
            out.string(primitive.getAsString());
            return;
        }
        out.varint(tag | W_JSON);
        out.string(COMPACT.toJson(value));
    }

    /**
     * 数字取最短写法：整数 → varint；小数先保留 {@link #SIGNIFICANT_DIGITS} 位有效数字，再写「尾数 + 小数位数」；
     * 太大或太小的 → float32（设置项都是 float）。整数永远精确（颜色、档位、计数都走这条）。
     */
    private static void writeNumber(Writer out, long tag, JsonPrimitive primitive) {
        BigDecimal exact;
        try {
            exact = primitive.getAsBigDecimal().stripTrailingZeros();
            if (exact.scale() > 0) {
                exact = exact.round(new MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)).stripTrailingZeros();
            }
        } catch (RuntimeException notDecimal) {
            out.varint(tag | W_FLOAT);
            out.int32(Float.floatToIntBits(primitive.getAsFloat()));
            return;
        }
        if (exact.scale() <= 0 && exact.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE >> 4)) < 0) {
            out.varint(tag | W_INT);
            out.varint(zigzag(exact.longValueExact()));
            return;
        }
        BigInteger unscaled = exact.unscaledValue();
        if (exact.scale() >= 1 && exact.scale() <= 7 && unscaled.bitLength() < 56) {
            long packed = (zigzag(unscaled.longValue()) << 3) | exact.scale();
            if (varintSize(packed) <= 4) {
                out.varint(tag | W_DECIMAL);
                out.varint(packed);
                return;
            }
        }
        out.varint(tag | W_FLOAT);
        out.int32(Float.floatToIntBits(primitive.getAsFloat()));
    }

    // ════════════════════════════ 解码 ════════════════════════════

    /** 这段文字里有没有 v2 分享码。 */
    public static boolean contains(String text) {
        return text != null && text.contains(PREFIX);
    }

    /**
     * 解出一份预设（来源记作 USER、尚未落盘、没有 id —— 由调用方分配）。
     *
     * @throws PresetFormatException 带一句给玩家看的原因
     */
    public StylePreset decode(String text) throws PresetFormatException {
        if (text == null || text.length() > PresetLimits.MAX_SHARE_TEXT_CHARS) {
            throw new PresetFormatException("剪贴板里的内容过长，不像是分享码");
        }
        int at = text.indexOf(PREFIX);
        if (at < 0) {
            throw new PresetFormatException("没有找到分享码（以 " + PREFIX + " 开头）");
        }
        int start = at + PREFIX.length();
        int end = start;
        while (end < text.length() && Base62.isDigit(text.charAt(end))) {
            end++;
        }
        if (end - start > MAX_CODE_CHARS) {
            throw new PresetFormatException("分享码过长，已拒绝读取");
        }
        byte[] bytes;
        try {
            bytes = Base62.decode(text.substring(start, end));
        } catch (IllegalArgumentException broken) {
            throw new PresetFormatException("分享码里有无法识别的字符", broken);
        }
        if (bytes.length < 4) {
            throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
        }
        int header = bytes[0] & 0xFF;
        int version = header >>> 4;
        if (version > VERSION) {
            throw new PresetFormatException("这个分享码来自更新版本的 MuoniumPlayer，请先更新再导入");
        }
        if (version != VERSION) {
            throw new PresetFormatException("分享码版本无效");
        }
        byte[] payload = new byte[bytes.length - 3];
        System.arraycopy(bytes, 1, payload, 0, payload.length);
        byte[] plain = (header & FLAG_DEFLATED) != 0 ? inflate(payload) : payload;
        int expected = ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
        if (crc16(header, plain) != expected) {
            throw new PresetFormatException("分享码校验失败（可能被改动或复制不完整）");
        }
        try {
            return readBody(new Reader(plain), (header & FLAG_BASE_DEFAULTS) != 0);
        } catch (IndexOutOfBoundsException | IllegalStateException | NumberFormatException broken) {
            throw new PresetFormatException("分享码内容损坏", broken);
        }
    }

    private StylePreset readBody(Reader in, boolean fromDefaults) throws PresetFormatException {
        long mask = in.varint();
        Set<Facet> facets = EnumSet.noneOf(Facet.class);
        List<String> unknownFacets = new ArrayList<String>();
        for (int bit = 0; bit < 63; bit++) {
            if ((mask & (1L << bit)) == 0) {
                continue;
            }
            Facet facet = Facet.byCode(bit + 1);
            if (facet != null) {
                facets.add(facet);
            } else {
                unknownFacets.add("code-" + (bit + 1));
            }
        }
        String name = PresetJson.clean(in.string(PresetLimits.MAX_NAME_CHARS * 4), PresetLimits.MAX_NAME_CHARS);
        String author = PresetJson.clean(in.string(PresetLimits.MAX_AUTHOR_CHARS * 4), PresetLimits.MAX_AUTHOR_CHARS);

        Map<String, DomainFragment> fragments = new LinkedHashMap<String, DomainFragment>();
        int domainCount = 0;
        while (in.remaining() > 0) {
            if (++domainCount > PresetLimits.MAX_DOMAINS) {
                throw new PresetFormatException("分享码里的配置域过多，已拒绝读取");
            }
            int domainCode = (int) in.varint();
            byte[] chunk = in.bytes((int) in.varint());
            PresetDomain domain = byShareCode(domainCode);
            if (domain == null) {
                // 新版本才有的配置域：留一个占位，引擎会提示「这个版本不认识」。
                fragments.put("code-" + domainCode, new DomainFragment(1, new JsonObject()));
                continue;
            }
            List<String> keys = domain.shareKeys();
            Map<String, JsonElement> flat = new LinkedHashMap<String, JsonElement>();
            Reader entries = new Reader(chunk);
            long code = 0;
            while (entries.remaining() > 0) {
                if (flat.size() >= PresetLimits.MAX_KEYS_PER_DOMAIN) {
                    throw new PresetFormatException("分享码里的设置项过多，已拒绝读取");
                }
                long tag = entries.varint();
                long delta = tag >>> 3;
                if (delta < 1 || delta > Integer.MAX_VALUE) {
                    throw new IllegalStateException("键编号不递增");
                }
                code += delta;
                JsonElement value = readValue(entries, (int) (tag & 7));
                String key = code >= 1 && code <= keys.size() ? keys.get((int) code - 1) : "code-" + code;
                flat.put(key, value);
            }
            fragments.put(domain.id(), new DomainFragment(domain.fragmentVersion(), JsonPaths.unflatten(flat)));
        }
        PresetMeta meta = new PresetMeta("", name.isEmpty() ? "分享的预设" : name, author, "", "", "", "");
        return new StylePreset(meta, fromDefaults ? StylePreset.Base.DEFAULTS : StylePreset.Base.CURRENT,
                facets, unknownFacets, fragments, StylePreset.Source.USER, null);
    }

    private static JsonElement readValue(Reader in, int wire) throws PresetFormatException {
        switch (wire) {
            case W_FALSE:
                return new JsonPrimitive(false);
            case W_TRUE:
                return new JsonPrimitive(true);
            case W_INT:
                return new JsonPrimitive(unzigzag(in.varint()));
            case W_DECIMAL: {
                long packed = in.varint();
                return new JsonPrimitive(BigDecimal.valueOf(unzigzag(packed >>> 3), (int) (packed & 7)));
            }
            case W_FLOAT:
                return new JsonPrimitive(Float.intBitsToFloat(in.int32()));
            case W_STRING:
                return new JsonPrimitive(in.string(PresetLimits.MAX_VALUE_STRING_CHARS));
            case W_JSON: {
                String json = in.string(PresetLimits.MAX_VALUE_STRING_CHARS * 2);
                if (PresetLimits.nestingDepth(json) > PresetLimits.MAX_DEPTH) {
                    throw new PresetFormatException("分享码里的内容嵌套过深，已拒绝读取");
                }
                try {
                    return JsonParser.parseString(json);
                } catch (RuntimeException broken) {
                    throw new PresetFormatException("分享码内容损坏", broken);
                }
            }
            default:
                return JsonNull.INSTANCE;
        }
    }

    private PresetDomain byShareCode(int code) {
        for (PresetDomain domain : domains) {
            if (domain.shareCode() == code) {
                return domain;
            }
        }
        return null;
    }

    // ════════════════════════════ 小工具 ════════════════════════════

    static long facetMask(Set<Facet> facets) {
        long mask = 0;
        for (Facet facet : facets) {
            mask |= 1L << (facet.code() - 1);
        }
        return mask;
    }

    private static int crc16(int header, byte[] plain) {
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(plain, 0, plain.length);
        return (int) (crc.getValue() & 0xFFFF);
    }

    private static byte[] deflate(byte[] plain) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(plain);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 16);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] packed) throws PresetFormatException {
        Inflater inflater = new Inflater(true);
        try {
            // nowrap 模式下 zlib 需要多喂一个哑字节才能可靠地收尾（Inflater 的文档约定）。
            byte[] input = new byte[packed.length + 1];
            System.arraycopy(packed, 0, input, 0, packed.length);
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 3 + 16);
            byte[] buffer = new byte[4096];
            int stalls = 0;
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buffer);
                } catch (DataFormatException broken) {
                    throw new PresetFormatException("分享码已损坏，无法解开", broken);
                }
                if (n == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    if (inflater.needsInput() || inflater.needsDictionary() || ++stalls > 8) {
                        throw new PresetFormatException("分享码不完整（可能复制时被截断了）");
                    }
                    continue;
                }
                stalls = 0;
                if (out.size() + n > MAX_BODY_BYTES) {
                    throw new PresetFormatException("分享码解开后过大，已拒绝读取");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    static int varintSize(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    /** 小端 varint（7 位一组，高位续接）写缓冲。 */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void varint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }

        void int32(int value) {
            out.write(value >>> 24);
            out.write(value >>> 16);
            out.write(value >>> 8);
            out.write(value);
        }

        void string(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            varint(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        void raw(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
        }

        int size() {
            return out.size();
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** 读缓冲：任何越界、过长的 varint 或长度都抛异常，由 {@link #decode} 统一转成「内容损坏」。 */
    private static final class Reader {
        private final byte[] data;
        private int at;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - at;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at >= data.length) {
                    throw new IndexOutOfBoundsException("varint 越界");
                }
                int b = data[at++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalStateException("varint 过长");
        }

        int int32() {
            byte[] four = bytes(4);
            return ((four[0] & 0xFF) << 24) | ((four[1] & 0xFF) << 16) | ((four[2] & 0xFF) << 8) | (four[3] & 0xFF);
        }

        byte[] bytes(int length) {
            if (length < 0 || length > remaining()) {
                throw new IndexOutOfBoundsException("长度越界：" + length);
            }
            byte[] out = new byte[length];
            System.arraycopy(data, at, out, 0, length);
            at += length;
            return out;
        }

        String string(int maxChars) {
            long length = varint();
            if (length > (long) maxChars * 4 || length > remaining()) {
                throw new IllegalStateException("字符串过长");
            }
            return new String(bytes((int) length), StandardCharsets.UTF_8);
        }
    }
}
