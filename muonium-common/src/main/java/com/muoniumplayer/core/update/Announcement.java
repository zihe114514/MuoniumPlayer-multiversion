package com.muoniumplayer.core.update;

/**
 * 一条公告（清单里 {@code announcements} 数组的一项）。
 *
 * <p>不可变值对象：清单是在守护线程上拉的，拉完之后界面每帧都要读它，所以这里没有任何可变状态，
 * 发布一份引用之后就不需要再加锁。</p>
 *
 * <p><b>{@link #id()} 是已读记账的唯一依据</b>：客户端按 id 记「读过没有」，所以清单作者改了正文
 * 却不换 id 时，老用户永远看不到改后的内容 —— 这一条写在清单仓库的 README 里。</p>
 */
public final class Announcement {

    private final String id;
    private final String date;
    private final String kind;
    private final String title;
    private final String body;
    private final String link;
    private final String linkText;

    public Announcement(String id, String date, String kind, String title,
                        String body, String link, String linkText) {
        this.id = trim(id);
        this.date = trim(date);
        this.kind = trim(kind);
        this.title = trim(title);
        this.body = trim(body);
        this.link = trim(link);
        this.linkText = trim(linkText);
    }

    /** 记账用的稳定标识。作者必须为「内容变了的那一条」换一个新 id。 */
    public String id() {
        return this.id;
    }

    /** 展示用日期，原样显示（清单里写 {@code 2026-09-21} 就显示这个）。可为空。 */
    public String date() {
        return this.date;
    }

    /**
     * 语气：{@code feature} / {@code fix} / {@code event} / {@code warning}，其它值按 info 表现。
     *
     * <p>故意不做成枚举：清单是远端文本，以后加一个新语气不该让老客户端把整条公告丢掉。
     * 不认识的值只是退回默认配色，图标与文字照样在。</p>
     */
    public String kind() {
        return this.kind;
    }

    public String title() {
        return this.title;
    }

    /** 正文。{@code \n} 是段落分隔，段内折行由界面按当前宽度算。 */
    public String body() {
        return this.body;
    }

    /** 附带的链接，可为空；是否可用由 {@code UpdateChecker.isAllowedPage} 判。 */
    public String link() {
        return this.link;
    }

    /** 链接文字，为空时界面用自己的默认文案。 */
    public String linkText() {
        return this.linkText;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
