package com.muoniumplayer.core.rendering;

public class ChatAllowedCharacters {
    /**
     * Minecraft 任意文本绘制中允许使用的特殊字符数组。
     */
    public static final char[] allowedCharactersArray = new char[]{'/', '\n', '\r', '\t', '\u0000', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':'};

    public static boolean isAllowedCharacter(char character) {
        return character != 167 && character >= 32 && character != 127;
    }

    /**
     * 过滤字符串，仅保留 isAllowedCharacter() 返回 true 的字符。
     */
    public static String filterAllowedCharacters(String input) {
        StringBuilder stringbuilder = new StringBuilder();

        for (char c0 : input.toCharArray()) {
            if (isAllowedCharacter(c0)) {
                stringbuilder.append(c0);
            }
        }

        return stringbuilder.toString();
    }
}
