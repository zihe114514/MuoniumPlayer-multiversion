package com.muoniumplayer.core.ncm.lxsource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义源脚本头部注释里的元信息。
 *
 * <p>字段名、长度上限、缺省行为都照抄 lx-music-desktop 的
 * {@code src/main/modules/userApi/utils.ts}：脚本作者是按那份实现写的头注释，
 * 这里放宽或收紧任何一条都会让同一个脚本在两个播放器里显示出不同的名字。</p>
 */
public final class LxScriptInfo {

    /** 头部必须是文件开头的第一个块注释，和上游 {@code /^\/\*[\S|\s]+?\*\//} 一致。 */
    private static final Pattern HEADER = Pattern.compile("\\A/\\*[\\s\\S]+?\\*/");
    /** 上游逐行匹配 {@code ^\s?\*\s?@(\w+)\s(.+)$}，注意 {@code \s?} 只允许一个前导空白。 */
    private static final Pattern FIELD = Pattern.compile("^\\s?\\*\\s?@(\\w+)\\s(.+)$");

    private static final int MAX_NAME = 24;
    private static final int MAX_DESCRIPTION = 36;
    private static final int MAX_AUTHOR = 56;
    private static final int MAX_HOMEPAGE = 1024;
    private static final int MAX_VERSION = 36;

    private final String name;
    private final String description;
    private final String version;
    private final String author;
    private final String homepage;

    private LxScriptInfo(String name, String description, String version, String author, String homepage) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.author = author;
        this.homepage = homepage;
    }

    /**
     * @throws IllegalArgumentException 头部注释缺失时抛出，对应上游的「无效的自定义源文件」
     */
    public static LxScriptInfo parse(String script) {
        if (script == null) throw new IllegalArgumentException("无效的自定义源文件：内容为空");
        Matcher header = HEADER.matcher(script);
        if (!header.find()) throw new IllegalArgumentException("无效的自定义源文件：开头缺少 @name 注释块");

        String name = "";
        String description = "";
        String version = "";
        String author = "";
        String homepage = "";
        for (String line : header.group().split("\\r?\\n")) {
            Matcher field = FIELD.matcher(line);
            if (!field.matches()) continue;
            String value = field.group(2).trim();
            switch (field.group(1)) {
                case "name": name = cap(value, MAX_NAME); break;
                case "description": description = cap(value, MAX_DESCRIPTION); break;
                case "version": version = cap(value, MAX_VERSION); break;
                case "author": author = cap(value, MAX_AUTHOR); break;
                case "homepage": homepage = cap(value, MAX_HOMEPAGE); break;
                default: break;
            }
        }
        return new LxScriptInfo(name, description, version, author, homepage);
    }

    /** 超长按上游的方式截断并补省略号，而不是拒绝导入。 */
    private static String cap(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit) + "..." : value;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getHomepage() {
        return homepage;
    }

    /** 卡片副标题：版本与作者都可能缺，拼接时不要留下孤零零的分隔符。 */
    public String getByline() {
        StringBuilder sb = new StringBuilder();
        if (!version.isEmpty()) {
            // 作者常把 @version 写成 v1.1.2，再补一个 v 就成了 vv1.1.2。
            if (version.charAt(0) != 'v' && version.charAt(0) != 'V') sb.append('v');
            sb.append(version);
        }
        if (!author.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(author);
        }
        return sb.toString();
    }
}