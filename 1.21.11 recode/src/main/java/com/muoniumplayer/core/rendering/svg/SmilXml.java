package com.muoniumplayer.core.rendering.svg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 SVG 用的极简 XML 读取器：只要「标签 + 属性 + 子节点」。
 *
 * <p>不用 {@code javax.xml}：那套要建 DocumentBuilderFactory，默认还会去解析外部实体，
 * 而这里要的只是把导出器写出来的机器文件摊平成一棵树（素材无注释、无 CDATA、无 DTD）。</p>
 *
 * <p>解析失败一律抛 {@link IllegalStateException}，由 {@link SmilSvg#load} 兜住并退回静态图标 ——
 * 一份坏素材绝不能把界面开不出来。</p>
 */
final class SmilXml {

    static final class Element {
        final String tag;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Element> children = new ArrayList<>();
        Element parent;

        private Element(String tag) {
            this.tag = tag;
        }

        String attr(String name) {
            return attributes.get(name);
        }

        String attr(String name, String fallback) {
            String value = attributes.get(name);
            return value == null ? fallback : value;
        }

        /** {@code <svg>} 可能写成 {@code <svg:svg>}，取冒号后面那截。 */
        String localTag() {
            int colon = tag.indexOf(':');
            return colon < 0 ? tag : tag.substring(colon + 1);
        }
    }

    private SmilXml() {
    }

    static Element parse(String text) {
        Cursor cursor = new Cursor(text);
        cursor.skipMisc();
        Element root = parseElement(cursor);
        cursor.skipMisc();
        if (!cursor.eof()) {
            throw new IllegalStateException("根元素后面还有内容: " + cursor.preview());
        }
        return root;
    }

    private static Element parseElement(Cursor cursor) {
        expect(cursor, '<');
        Element element = new Element(readName(cursor));
        parseAttributes(cursor, element);
        if (element.tag.equals("animate") || element.tag.equals("animateTransform")
                || element.tag.equals("animateMotion")) {
            // SMIL 元素在这批素材里都是自闭合的；真遇到配对写法也照读，只是它不会有子节点被用到。
            skipChildren(cursor, element);
        } else {
            parseChildren(cursor, element);
        }
        return element;
    }

    /** 吃掉开标签剩下的部分；自闭合就在此返回，否则落回给调用方去读子树。 */
    private static void parseAttributes(Cursor cursor, Element element) {
        while (true) {
            cursor.skipWhitespace();
            if (cursor.eof()) {
                throw new IllegalStateException("元素没写完: " + element.tag);
            }
            char c = cursor.peek();
            if (c == '/') {
                cursor.pos++;
                expect(cursor, '>');
                cursor.closedSelf = true;
                return;
            }
            if (c == '>') {
                cursor.pos++;
                return;
            }
            String name = readName(cursor);
            cursor.skipWhitespace();
            expect(cursor, '=');
            cursor.skipWhitespace();
            element.attributes.put(name, readQuoted(cursor));
        }
    }

    private static void parseChildren(Cursor cursor, Element element) {
        if (cursor.closedSelf) {
            cursor.closedSelf = false;
            return;
        }
        while (true) {
            cursor.skipWhitespace();
            if (cursor.eof()) {
                throw new IllegalStateException("元素没闭合: " + element.tag);
            }
            if (cursor.startsWith("</")) {
                readClosing(cursor, element);
                return;
            }
            if (!cursor.startsWith("<")) {
                // 文本节点：图标素材里没有成段文字，直接跳到下一个标签。
                cursor.skipThrough("<");
                continue;
            }
            cursor.skipMisc();
            if (cursor.eof() || cursor.startsWith("</")) {
                continue;
            }
            Element child = parseElement(cursor);
            child.parent = element;
            element.children.add(child);
        }
    }

    private static void skipChildren(Cursor cursor, Element element) {
        if (cursor.closedSelf) {
            cursor.closedSelf = false;
            return;
        }
        while (true) {
            cursor.skipWhitespace();
            if (cursor.eof()) {
                throw new IllegalStateException("元素没闭合: " + element.tag);
            }
            if (cursor.startsWith("</")) {
                readClosing(cursor, element);
                return;
            }
            cursor.skipThrough("<");
        }
    }

    private static void readClosing(Cursor cursor, Element element) {
        cursor.pos += 2;
        String name = readName(cursor);
        cursor.skipWhitespace();
        expect(cursor, '>');
        if (!name.equals(element.tag)) {
            throw new IllegalStateException("闭合标签不配对: 开 <" + element.tag + "> 闭 </" + name + ">");
        }
    }

    private static String readName(Cursor cursor) {
        int start = cursor.pos;
        while (!cursor.eof()) {
            char c = cursor.peek();
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.') {
                cursor.pos++;
            } else {
                break;
            }
        }
        if (cursor.pos == start) {
            throw new IllegalStateException("标签或属性名为空: " + cursor.preview());
        }
        return cursor.text.substring(start, cursor.pos);
    }

    private static String readQuoted(Cursor cursor) {
        char quote = cursor.peek();
        if (quote != '"' && quote != '\'') {
            throw new IllegalStateException("属性值没有引号: " + cursor.preview());
        }
        cursor.pos++;
        int start = cursor.pos;
        int end = cursor.text.indexOf(quote, start);
        if (end < 0) {
            throw new IllegalStateException("属性值没闭合: " + cursor.preview());
        }
        cursor.pos = end + 1;
        return unescape(cursor.text.substring(start, end));
    }

    private static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int semicolon = value.indexOf(';', i);
            if (semicolon < 0) {
                out.append(c);
                continue;
            }
            String entity = value.substring(i + 1, semicolon);
            switch (entity) {
                case "amp":
                    out.append('&');
                    break;
                case "lt":
                    out.append('<');
                    break;
                case "gt":
                    out.append('>');
                    break;
                case "quot":
                    out.append('"');
                    break;
                case "apos":
                    out.append('\'');
                    break;
                default:
                    if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        out.append((char) Integer.parseInt(entity.substring(2), 16));
                    } else if (entity.startsWith("#")) {
                        out.append((char) Integer.parseInt(entity.substring(1)));
                    } else {
                        // 认不出的实体原样留着：素材里不该有，真有了也不该因此丢掉整枚图标。
                        out.append('&').append(entity).append(';');
                    }
                    break;
            }
            i = semicolon;
        }
        return out.toString();
    }

    private static void expect(Cursor cursor, char c) {
        if (cursor.eof() || cursor.peek() != c) {
            throw new IllegalStateException("期望 '" + c + "'，实际: " + cursor.preview());
        }
        cursor.pos++;
    }

    private static final class Cursor {
        final String text;
        int pos;
        /** 刚读过的开标签是不是 {@code />} 收尾 —— 决定还要不要读子树。 */
        boolean closedSelf;

        Cursor(String text) {
            this.text = text;
        }

        boolean eof() {
            return pos >= text.length();
        }

        char peek() {
            return text.charAt(pos);
        }

        boolean startsWith(String prefix) {
            return text.startsWith(prefix, pos);
        }

        void skipWhitespace() {
            while (!eof() && Character.isWhitespace(peek())) {
                pos++;
            }
        }

        void skipThrough(String stop) {
            int end = text.indexOf(stop, pos);
            pos = end < 0 ? text.length() : end + stop.length();
        }

        /** 跳过处理指令与 {@code <!…>}（声明、文档类型、注释）。 */
        void skipMisc() {
            while (true) {
                skipWhitespace();
                if (startsWith("<?")) {
                    skipThrough("?>");
                    continue;
                }
                if (startsWith("<!")) {
                    skipThrough(">");
                    continue;
                }
                return;
            }
        }

        /** 出错信息里带的那一小段原文。 */
        String preview() {
            int from = Math.max(0, pos - 20);
            int to = Math.min(text.length(), pos + 20);
            return "…" + text.substring(from, to) + "…";
        }
    }
}
