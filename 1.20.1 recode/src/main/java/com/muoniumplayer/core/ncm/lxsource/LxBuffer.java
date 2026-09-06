package com.muoniumplayer.core.ncm.lxsource;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 给脚本用的 Node {@code Buffer} 替身。
 *
 * <p>只做自定义源真正会用到的那几件事：{@code lx.utils.buffer.from}、{@code toString(format)}、
 * 以及把字节交给 {@code lx.utils.crypto}/{@code zlib}。刻意不去实现完整的 Buffer —— 上游能用的
 * 也只是 preload 里透出来的那两个方法，脚本拿到的本来就不是一个能随便当数组用的对象。</p>
 *
 * <p>底层是 Java 的 {@code byte[]}：加解密与压缩都在 Java 侧做，如果这里用 JS 数组存字节，
 * 每次调用都要在两个世界之间抄一遍，AES 的 key/iv 这种小东西无所谓，但 inflate 出来的
 * 几十 KB 就会明显卡住 JS 线程。</p>
 */
public final class LxBuffer extends ScriptableObject {

    private static final long serialVersionUID = 1L;

    private byte[] bytes;

    /** Rhino 反序列化 / 原型对象需要的无参构造。 */
    public LxBuffer() {
        this.bytes = new byte[0];
    }

    private LxBuffer(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes;
        installMembers();
    }

    public static LxBuffer of(byte[] bytes) {
        return new LxBuffer(bytes);
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public String getClassName() {
        return "Buffer";
    }

    /** {@code buf.length} 与索引读取都是脚本里最常见的两种用法，其余属性交给 ScriptableObject。 */
    private void installMembers() {
        defineProperty("length", Integer.valueOf(bytes.length), PERMANENT | READONLY);
        put("toString", this, new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                String format = args.length > 0 && args[0] != null && !Undefined.isUndefined(args[0])
                        ? Context.toString(args[0]) : "utf8";
                return encode(bytes, format);
            }
        });
        put("slice", this, new BaseFunction() {
            private static final long serialVersionUID = 1L;

            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                int start = args.length > 0 ? clamp((int) Context.toNumber(args[0])) : 0;
                int end = args.length > 1 && !Undefined.isUndefined(args[1])
                        ? clamp((int) Context.toNumber(args[1])) : bytes.length;
                if (end < start) end = start;
                byte[] copy = new byte[end - start];
                System.arraycopy(bytes, start, copy, 0, copy.length);
                return LxBuffer.of(copy);
            }
        });
    }

    private int clamp(int index) {
        if (index < 0) index = bytes.length + index;
        return Math.max(0, Math.min(bytes.length, index));
    }

    @Override
    public Object get(int index, Scriptable start) {
        if (index >= 0 && index < bytes.length) return Integer.valueOf(bytes[index] & 0xFF);
        return super.get(index, start);
    }

    @Override
    public boolean has(int index, Scriptable start) {
        return (index >= 0 && index < bytes.length) || super.has(index, start);
    }

    /**
     * {@code Buffer.from(value, encoding)}。
     *
     * <p>数组分支要认 JS 数组、类型化数组和另一个 Buffer：脚本里 {@code Buffer.from([1,2,3])}
     * 与 {@code Buffer.from(otherBuf)} 都出现过。</p>
     */
    public static LxBuffer from(Object value, String encoding) {
        if (value instanceof LxBuffer) {
            byte[] src = ((LxBuffer) value).bytes;
            byte[] copy = new byte[src.length];
            System.arraycopy(src, 0, copy, 0, src.length);
            return LxBuffer.of(copy);
        }
        if (value instanceof CharSequence) {
            return LxBuffer.of(decode(value.toString(), encoding));
        }
        if (value instanceof Scriptable) {
            Scriptable array = (Scriptable) value;
            Object lengthValue = ScriptableObject.getProperty(array, "length");
            int length = lengthValue == Scriptable.NOT_FOUND || Undefined.isUndefined(lengthValue)
                    ? 0 : (int) Context.toNumber(lengthValue);
            byte[] out = new byte[Math.max(0, length)];
            for (int i = 0; i < out.length; i++) {
                Object element = ScriptableObject.getProperty(array, i);
                out[i] = element == Scriptable.NOT_FOUND ? 0 : (byte) (int) Context.toNumber(element);
            }
            return LxBuffer.of(out);
        }
        return LxBuffer.of(decode(Context.toString(value), encoding));
    }

    /** 字符串 → 字节。未知编码按 utf8 处理，和 Node 的宽容度一致。 */
    public static byte[] decode(String text, String encoding) {
        String format = encoding == null ? "utf8" : encoding.trim().toLowerCase();
        if ("hex".equals(format)) return fromHex(text);
        if ("base64".equals(format) || "base64url".equals(format)) return fromBase64(text);
        if ("binary".equals(format) || "latin1".equals(format) || "ascii".equals(format)) {
            byte[] out = new byte[text.length()];
            for (int i = 0; i < out.length; i++) out[i] = (byte) (text.charAt(i) & 0xFF);
            return out;
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 字节 → 字符串。 */
    public static String encode(byte[] bytes, String encoding) {
        String format = encoding == null ? "utf8" : encoding.trim().toLowerCase();
        if ("hex".equals(format)) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        }
        if ("base64".equals(format)) return Base64.getEncoder().encodeToString(bytes);
        if ("base64url".equals(format)) return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if ("binary".equals(format) || "latin1".equals(format) || "ascii".equals(format)) {
            StringBuilder sb = new StringBuilder(bytes.length);
            for (byte b : bytes) sb.append((char) (b & 0xFF));
            return sb.toString();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] fromHex(String text) {
        String clean = text.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 脚本里的 base64 常带换行或 URL 变体，解不动时不该把整个请求崩掉。 */
    private static byte[] fromBase64(String text) {
        String clean = text.replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(clean);
            } catch (IllegalArgumentException broken) {
                return new byte[0];
            }
        }
    }
}