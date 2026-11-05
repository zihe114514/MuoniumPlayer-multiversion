package com.muoniumplayer.core.preset.codec;

/**
 * 外来预设的硬上限。分享码与预设文件一律按不可信输入处理：先量尺寸、再量嵌套深度、最后才解析。
 *
 * <p>数字取「正常预设的十倍以上」：全外观 + 一份 ViPER 预设的 JSON 也就几 KB，这些上限只拦恶意或
 * 损坏的输入，不会误伤真实预设。</p>
 */
public final class PresetLimits {

    /** 预设 JSON 文本（文件或解出来的分享码）最多多少字符。 */
    public static final int MAX_JSON_CHARS = 512 * 1024;
    /** 剪贴板里的整段文字最多看多少字符（分享码前后可能夹着聊天内容）。 */
    public static final int MAX_SHARE_TEXT_CHARS = 512 * 1024;
    /** 分享码解压后的上限：防压缩炸弹。 */
    public static final int MAX_INFLATED_BYTES = 512 * 1024;
    /** JSON 嵌套深度上限：先扫文本、再交给解析器，深嵌套在解析前就被拒掉。 */
    public static final int MAX_DEPTH = 16;
    /** 一份预设最多带多少个配置域。 */
    public static final int MAX_DOMAINS = 32;
    /** 一个配置域拍平后最多多少个键。 */
    public static final int MAX_KEYS_PER_DOMAIN = 2048;
    /** 值里单个字符串的长度上限。 */
    public static final int MAX_VALUE_STRING_CHARS = 16 * 1024;
    /** 分面声明的条数上限。 */
    public static final int MAX_FACETS = 64;

    public static final int MAX_NAME_CHARS = 40;
    public static final int MAX_AUTHOR_CHARS = 32;
    public static final int MAX_DESCRIPTION_CHARS = 240;
    public static final int MAX_ID_CHARS = 96;
    public static final int MAX_SHORT_TEXT_CHARS = 48;

    private PresetLimits() {
    }

    /**
     * 不解析、只扫一遍文本量出 {@code {} / []} 的最大嵌套深度（跳过字符串字面量）。
     *
     * <p>放在解析之前：Gson 构建 JsonElement 树是递归的，几十万层的 {@code [[[[…} 能直接把栈打爆，
     * 必须在交给它之前就拒掉。</p>
     */
    public static int nestingDepth(CharSequence text) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth > max) {
                    max = depth;
                }
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }
        return max;
    }
}
