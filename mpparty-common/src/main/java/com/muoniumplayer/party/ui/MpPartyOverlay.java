package com.muoniumplayer.party.ui;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.party.MpMember;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpRoom;
import com.muoniumplayer.party.MpTrack;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * 服务器一起听（mpsync 服务端 mod）的二级弹窗：没装服务端 / 大厅 / 房间三态，外加
 * 「创建 · 加入 · 邀请 · 成员 · 链接点歌」五个子页。
 *
 * <p>刻意与网易云自带的 {@code ListenTogetherOverlay} 用同一套写法：房间状态由
 * {@link MpPartyClient} 的网络线程推进，随时可能变，所以<b>不按状态重建控件树</b> ——
 * 三段内容一次建好，每帧只切 {@code setHidden}；重建只发生在用户主动换页时。</p>
 *
 * <p>本文件放在共用模块里：四个版本工程的控件框架（{@code core.rendering.ui.*}、
 * {@code NCMPanel}、{@code FontManager}）目前逐字节相同，因此这一份能同时编译进四个工程。
 * 一旦哪个版本的控件框架分叉，就得把这个文件拆回各工程自己的 {@code screens/ncm} 里。</p>
 */
public final class MpPartyOverlay extends NCMPanel {

    /** 歌单一次显示几行。超出的部分靠滚轮翻。 */
    private static final int QUEUE_ROWS = 5;
    /** 成员页一次显示几行。 */
    private static final int MEMBER_ROWS = 6;

    private static final int OK_COLOR = 0x53C68C;
    private static final int WARN_COLOR = 0xD5A44A;
    private static final int ERROR_COLOR = 0xF1767D;

    private static final long COPIED_HINT_MILLIS = 1400L;

    /** 按钮配色：普通 / 主操作 / 破坏性 / 列表行内。 */
    private static final int STYLE_PLAIN = 0;
    private static final int STYLE_PRIMARY = 1;
    private static final int STYLE_DANGER = 2;
    private static final int STYLE_ROW = 3;
    private static final int STYLE_ROW_DANGER = 4;

    private enum Page { OVERVIEW, CREATE, JOIN, INVITE, MEMBERS, LINK }

    private boolean closing;
    private Page page = Page.OVERVIEW;

    /** 弹窗入场进度；同时驱动上浮位移与整体淡入。 */
    private double presentation;
    /** 弹窗高度的当帧值：三段内容高矮不同，朝目标高度插值过去。 */
    private double dialogHeight;

    /** 当前页的输入框（没有输入框的页为 null）。 */
    private TextFieldWidget field;
    private String hint = "";
    private int hintColor = 0xAEB5C4;

    private long copiedAt;

    private int queueScroll;
    private int memberScroll;

    /**
     * 本帧的滚轮已经被列表吃掉了。
     *
     * <p>{@code AbstractWidget.renderWidget} 在<b>每一层</b>的末尾都会派发一次滚轮，
     * 同一个 notch 会沿着 overlay → dialog → section → list 被送到四次。清标记放在
     * 根组件的 {@code onRender} 里（它在所有子组件与所有派发之前跑），列表只吃第一次。</p>
     */
    private boolean wheelConsumed;

    /** 进度条正在被拖：松手才真正下发 seek，免得一次拖动打出几十条房间指令。 */
    private boolean barDragging;
    private double barOverride = -1.0;

    @Override
    public void onInit() {
        showOverview();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        wheelConsumed = false;
        super.onRender(mouseX, mouseY);
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
    }

    public void handleEscape() {
        if (page == Page.OVERVIEW) {
            dispose();
        } else {
            showOverview();
        }
    }

    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        // ESC 必须留给 NCMScreen 的模态链路（回总览 / 关弹窗），不能被输入框吃掉。
        if (keyCode == Keyboard.KEY_ESCAPE) return false;
        TextFieldWidget input = field;
        if (input != null && input.isFocused()) {
            input.onKeyTyped(typedChar, keyCode);
            return true;
        }
        return false;
    }

    // ──────────────────────────── 总览 ────────────────────────────

    private void showOverview() {
        page = Page.OVERVIEW;
        field = null;
        queueScroll = 0;
        getChildren().clear();

        final Panel[] sections = new Panel[3];
        Panel dialog = createDialog(536, this::targetOverviewHeight, () -> {
            boolean present = MpPartyClient.serverPresent();
            boolean inRoom = MpPartyClient.inRoom();
            sections[0].setHidden(present);
            sections[1].setHidden(!present || inRoom);
            sections[2].setHidden(!present || !inRoom);
            if (!inRoom) {
                queueScroll = 0;
                barDragging = false;
                barOverride = -1.0;
            }
        });
        addTitle(dialog, "一起听 · 服务器房间",
                "同一台服务器里的玩家共享一条播放进度 · 不限播放源");
        // 双向入口：这一层是从「一起听」面板点进来的，右上角给一条回去的路。
        btn(dialog, () -> "‹ 一起听", null, () -> {
            dispose();
            NCMScreen.getInstance().openListenTogether();
        }, STYLE_PLAIN, b -> {
            b.setBounds(80, 28);
            b.setPosition(Math.max(1, dialog.getWidth() - 16 - b.getWidth()), 14);
        });

        sections[0] = section(dialog);
        buildNoServer(sections[0]);
        sections[1] = section(dialog);
        buildLobby(sections[1]);
        sections[2] = section(dialog);
        buildRoom(sections[2]);

        addStatusLine(dialog);
    }

    private double targetOverviewHeight() {
        if (!MpPartyClient.serverPresent()) return 252.0;
        return MpPartyClient.inRoom() ? 478.0 : 292.0;
    }

    // ──────────────────────────── 服务端未安装 ────────────────────────────

    private void buildNoServer(final Panel host) {
        card(host, 68, 96);

        LabelWidget icon = label(host, FontelloIcons.WARNING, FontManager.fontello22);
        icon.setBeforeRenderCallback(() -> icon.setColor(WARN_COLOR).setPosition(32, 92));

        LabelWidget title = label(host, "这台服务器没有一起听服务端", FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(68, 86));

        LabelWidget detail = label(host,
                "房间、点歌与全房进度都由服务端安排。请让服主装上 mpsync 服务端 mod"
                        + "（Fabric / Paper 均可），进服后这里会自动亮起来。",
                FontManager.pf12);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, host.getWidth() - 100));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.SCROLL);
            detail.setPosition(68, 106);
        });

        LabelWidget note = label(host, "服务端装好后，房间也可以用聊天指令 /mp 操作。", FontManager.pf12);
        note.setBeforeRenderCallback(() -> note
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(32, 176));

        // 探测状态直接显示出来：channel 是「服务器声明了这个通道」（Paper 不会声明），
        // hello 是「服务端回过话」。两个都为 false 才真的是没装。
        LabelWidget probe = label(host, MpPartyClient::diag, FontManager.pf12);
        probe.setBeforeRenderCallback(() -> {
            probe.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            probe.setMaxWidth(Math.max(1, host.getWidth() - 64));
            probe.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            probe.setPosition(32, 198);
        });
    }

    // ──────────────────────────── 大厅（未在房间） ────────────────────────────

    private void buildLobby(final Panel host) {
        card(host, 68, 60);

        LabelWidget hintLabel = label(host,
                "创建一个房间把好友请进来，或者用别人给的邀请码加入。", FontManager.pf12);
        hintLabel.setBeforeRenderCallback(() -> {
            hintLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            hintLabel.setMaxWidth(Math.max(1, host.getWidth() - 64));
            hintLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hintLabel.setPosition(32, 84);
        });

        // 收到的邀请：有邀请码才亮，没有就整条隐形（alpha + clickable，不用 setHidden）。
        LabelWidget invite = label(host, () -> {
            String code = MpPartyClient.inviteCode();
            if (code == null || code.isEmpty()) return "还没有收到邀请。";
            return "来自 " + MpPartyClient.inviteFrom() + " 的邀请 · 房间「"
                    + MpPartyClient.inviteRoomName() + "」· 邀请码 " + code;
        }, FontManager.pf12);
        invite.setBeforeRenderCallback(() -> {
            boolean has = has(MpPartyClient.inviteCode());
            invite.setColor(has ? OK_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            invite.setMaxWidth(Math.max(1, host.getWidth() - 64));
            invite.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            invite.setPosition(32, 104);
        });

        btn(host, () -> "创建房间", null, this::showCreatePage, STYLE_PRIMARY, b -> {
            double total = Math.max(1, host.getWidth() - 32);
            b.setBounds((total - 16) / 3.0, 34);
            b.setPosition(16, 148);
        });
        btn(host, () -> "加入房间", null, this::showJoinPage, STYLE_PLAIN, b -> {
            double total = Math.max(1, host.getWidth() - 32);
            double w = (total - 16) / 3.0;
            b.setBounds(w, 34);
            b.setPosition(16 + w + 8, 148);
        });
        btn(host, () -> "接受邀请", () -> has(MpPartyClient.inviteCode()), () -> {
            MpPartyClient.joinRoom(MpPartyClient.inviteCode());
        }, STYLE_PLAIN, b -> {
            double total = Math.max(1, host.getWidth() - 32);
            double w = (total - 16) / 3.0;
            b.setBounds(w, 34);
            b.setPosition(16 + (w + 8) * 2, 148);
        });

        LabelWidget tip = label(host,
                "房主与管理员可以切歌、暂停、拖进度；其他成员可以排队点歌。", FontManager.pf12);
        tip.setBeforeRenderCallback(() -> {
            tip.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            tip.setMaxWidth(Math.max(1, host.getWidth() - 32));
            tip.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            tip.setPosition(16, 194);
        });
    }

    // ──────────────────────────── 房间内 ────────────────────────────

    private void buildRoom(final Panel host) {
        buildRoomHeader(host);
        buildNowPlaying(host);
        buildTransportRow(host);
        buildQueue(host);
        buildRoomFooter(host);
    }

    private void buildRoomHeader(final Panel host) {
        card(host, 68, 44);

        LabelWidget name = label(host, () -> {
            MpRoom room = MpPartyClient.room();
            return room == null ? "" : room.name;
        }, FontManager.pf14bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, host.getWidth() - 220));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(32, 76);
        });

        LabelWidget meta = label(host, () -> {
            MpRoom room = MpPartyClient.room();
            if (room == null) return "";
            MpMember me = MpPartyClient.self();
            return "邀请码 " + room.code + " · " + room.members.size() + " 人在房 · "
                    + room.listeners + " 人在听 · " + room.repeatLabel()
                    + " · 我是" + (me == null ? "成员" : me.roleLabel());
        }, FontManager.pf12);
        meta.setBeforeRenderCallback(() -> {
            meta.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            meta.setMaxWidth(Math.max(1, host.getWidth() - 220));
            meta.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            meta.setPosition(32, 94);
        });

        btn(host, () -> System.currentTimeMillis() - copiedAt < COPIED_HINT_MILLIS
                ? "已复制" : "复制邀请码", () -> MpPartyClient.inRoom(), () -> {
            MpRoom room = MpPartyClient.room();
            if (room == null) return;
            KeyboardUtils.setClipboardString(room.code);
            copiedAt = System.currentTimeMillis();
        }, STYLE_PLAIN, b -> {
            b.setBounds(84, 26);
            b.setPosition(host.getWidth() - 32 - b.getWidth(), 77);
        });
    }

    private void buildNowPlaying(final Panel host) {
        card(host, 122, 84);

        LabelWidget title = label(host, () -> {
            MpTrack track = MpPartyClient.currentTrack();
            if (track == null) return MpPartyClient.waitingUrl() ? "正在等待取流链接…" : "房间还没有在播的歌";
            return track.name;
        }, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, host.getWidth() - 64));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(32, 132);
        });

        LabelWidget sub = label(host, () -> {
            MpTrack track = MpPartyClient.currentTrack();
            if (track == null) return "房主或管理员可以直接切歌，成员点的歌会排进歌单。";
            String artist = track.artist == null || track.artist.trim().isEmpty() ? "未知歌手" : track.artist;
            String who = has(track.requesterName) ? " · 点歌：" + track.requesterName : "";
            return artist + who + (MpPartyClient.playing() ? "" : " · 已暂停");
        }, FontManager.pf12);
        sub.setBeforeRenderCallback(() -> {
            sub.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            sub.setMaxWidth(Math.max(1, host.getWidth() - 64));
            sub.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            sub.setPosition(32, 151);
        });

        buildProgressBar(host);

        LabelWidget cur = label(host, () -> mmss(displayPositionMs()), FontManager.pf12);
        cur.setBeforeRenderCallback(() -> cur
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(32, 182));

        LabelWidget total = label(host, () -> mmss(MpPartyClient.durationMs()), FontManager.pf12);
        total.setBeforeRenderCallback(() -> total
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(host.getWidth() - 32 - total.getWidth(), 182));
    }

    /**
     * 房间进度条。拖动时只改本地显示值，松手那一帧才下发一条 {@code seek}：
     * 服务端会把它广播给全房，每帧都发等于让所有人的播放器一直重新对齐。
     */
    private void buildProgressBar(final Panel host) {
        final RoundedRectWidget bg = new RoundedRectWidget() {

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                boolean down = Mouse.isButtonDown(0);
                boolean controller = MpPartyClient.isController() && MpPartyClient.durationMs() > 0L;
                if (!controller) {
                    barDragging = false;
                    barOverride = -1.0;
                    return;
                }
                if (!barDragging && down && this.testHovered(mouseX, mouseY, 4)) {
                    barDragging = true;
                }
                if (barDragging) {
                    double delta = Math.max(0, Math.min(this.getWidth(), mouseX - this.getX()));
                    barOverride = this.getWidth() <= 0 ? 0 : delta / this.getWidth();
                    if (!down) {
                        barDragging = false;
                        MpPartyClient.seek((long) (barOverride * MpPartyClient.durationMs()));
                        barOverride = -1.0;
                    }
                }
            }
        };
        host.addChild(bg);
        bg.setRadius(1)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> bg
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                        .setBounds(Math.max(1, host.getWidth() - 64), 4)
                        .setPosition(32, 172));

        final RoundedRectWidget fill = new RoundedRectWidget();
        bg.addChild(fill);
        fill.setClickable(false).setBeforeRenderCallback(() -> {
            long duration = MpPartyClient.durationMs();
            double percent = barOverride >= 0.0 ? barOverride
                    : (duration <= 0L ? 0.0 : Math.max(0.0, Math.min(1.0, displayPositionMs() / (double) duration)));
            fill.setMargin(0);
            fill.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                    .setWidth(percent * bg.getWidth())
                    .setRadius(1);
        });
    }

    private long displayPositionMs() {
        if (barOverride >= 0.0) return (long) (barOverride * MpPartyClient.durationMs());
        return MpPartyClient.positionMs();
    }

    /** 上一曲 / 播放暂停 / 下一曲 / 循环模式 / 清空歌单：只有房主与管理员能按。 */
    private void buildTransportRow(final Panel host) {
        final BooleanSupplier controls = MpPartyClient::isController;
        final String[] labels = {"上一曲", "", "下一曲", "", "清空歌单"};
        final Runnable[] actions = {
                MpPartyClient::prev,
                () -> {
                    if (MpPartyClient.playing()) MpPartyClient.pause();
                    else MpPartyClient.resume();
                },
                MpPartyClient::next,
                () -> {
                    MpRoom room = MpPartyClient.room();
                    String cur = room == null || room.repeat == null ? "off" : room.repeat;
                    MpPartyClient.setRepeat("off".equals(cur) ? "one" : ("one".equals(cur) ? "all" : "off"));
                },
                MpPartyClient::clearQueue
        };
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            Supplier<String> text;
            if (slot == 1) {
                text = () -> MpPartyClient.playing() ? "暂停" : "播放";
            } else if (slot == 3) {
                text = () -> {
                    MpRoom room = MpPartyClient.room();
                    return room == null ? "顺序播放" : room.repeatLabel();
                };
            } else {
                text = () -> labels[slot];
            }
            btn(host, text, controls, actions[slot], slot == 1 ? STYLE_PRIMARY : STYLE_PLAIN, b -> {
                double total = Math.max(1, host.getWidth() - 64);
                double w = (total - 8 * 4) / 5.0;
                b.setBounds(w, 30);
                b.setPosition(32 + (w + 8) * slot, 214);
            });
        }
    }

    private void buildQueue(final Panel host) {
        LabelWidget header = label(host, () -> {
            int size = MpPartyClient.queue().size();
            String scroll = size > QUEUE_ROWS ? "（滚轮翻页）" : "";
            return "一起听歌单 · " + size + " 首待播" + scroll;
        }, FontManager.pf12bold);
        header.setBeforeRenderCallback(() -> header
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(32, 252));

        LabelWidget empty = label(host, () -> MpPartyClient.membersCanEnqueue()
                ? "歌单是空的。任何成员都可以点歌排队 —— 在播放器里点「下一首播放」，或者用「链接点歌」。"
                : "歌单是空的。当前只有房主与管理员可以点歌。", FontManager.pf12);
        empty.setBeforeRenderCallback(() -> {
            boolean show = MpPartyClient.queue().isEmpty();
            empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            empty.setMaxWidth(Math.max(1, host.getWidth() - 64));
            empty.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            empty.setPosition(32, 276);
            empty.setAlpha(show ? host.getAlpha() : 0f);
        });

        // 滚轮翻页挂在列表容器上：让整块区域（含行内按钮）都能滚。
        final Panel list = new Panel() {

            @Override
            public boolean canBeScrolled() {
                return MpPartyClient.queue().size() > QUEUE_ROWS;
            }

            @Override
            public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
                if (dWheel == 0 || wheelConsumed) return true;
                wheelConsumed = true;
                int max = Math.max(0, MpPartyClient.queue().size() - QUEUE_ROWS);
                queueScroll = Math.max(0, Math.min(max, queueScroll + (dWheel > 0 ? -1 : 1)));
                return true;
            }
        };
        host.addChild(list);
        list.setBeforeRenderCallback(() -> {
            int max = Math.max(0, MpPartyClient.queue().size() - QUEUE_ROWS);
            if (queueScroll > max) queueScroll = max;
            list.setBounds(Math.max(1, host.getWidth() - 64), QUEUE_ROWS * 28);
            list.setPosition(32, 268);
        });

        for (int index = 0; index < QUEUE_ROWS; index++) {
            buildQueueRow(list, index);
        }
    }

    private void buildQueueRow(final Panel list, final int row) {
        final double[] reveal = new double[1];

        final RoundedRectWidget bg = new RoundedRectWidget();
        list.addChild(bg);
        bg.setClickable(false).setRadius(7).setBeforeRenderCallback(() -> {
            MpTrack track = queueAt(row);
            reveal[0] = Interpolations.interpolate(reveal[0], track == null ? 0.0 : 1.0, .2f);
            bg.setBounds(Math.max(1, list.getWidth()), 24);
            bg.setPosition(0, row * 28);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            bg.setAlpha((float) (list.getAlpha() * reveal[0]));
        });

        LabelWidget text = label(bg, () -> {
            MpTrack track = queueAt(row);
            if (track == null) return "";
            return (queueScroll + row + 1) + ". " + track.display();
        }, FontManager.pf12);
        text.setBeforeRenderCallback(() -> {
            text.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            text.setMaxWidth(Math.max(1, bg.getWidth() - 210));
            text.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            text.setPosition(10, 7);
        });

        LabelWidget who = label(bg, () -> {
            MpTrack track = queueAt(row);
            if (track == null || !has(track.requesterName)) return "";
            return "点歌：" + track.requesterName;
        }, FontManager.pf12);
        who.setBeforeRenderCallback(() -> {
            who.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            who.setMaxWidth(120);
            who.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            who.setPosition(Math.max(1, bg.getWidth() - 200), 7);
        });

        btn(bg, () -> "插播", () -> queueAt(row) != null && MpPartyClient.isController(), () -> {
            MpTrack track = queueAt(row);
            if (track != null) MpPartyClient.playEntry(track.entryId);
        }, STYLE_ROW, b -> {
            b.setBounds(38, 18);
            b.setPosition(bg.getWidth() - 92, 3);
        });

        btn(bg, () -> "置顶", () -> queueAt(row) != null && MpPartyClient.isController(), () -> {
            MpTrack track = queueAt(row);
            if (track != null) MpPartyClient.moveEntry(track.entryId, 0);
        }, STYLE_ROW, b -> {
            b.setBounds(38, 18);
            b.setPosition(bg.getWidth() - 50, 3);
        });

        // 移除对自己点的那一首也开放：服务端仍会再校验一次权限。
        btn(bg, () -> "×", () -> {
            MpTrack track = queueAt(row);
            if (track == null) return false;
            if (MpPartyClient.isController()) return true;
            MpMember me = MpPartyClient.self();
            return me != null && me.name != null && me.name.equals(track.requesterName);
        }, () -> {
            MpTrack track = queueAt(row);
            if (track != null) MpPartyClient.removeEntry(track.entryId);
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(16, 18);
            b.setPosition(bg.getWidth() - 24, 3);
        });
    }

    /** 歌单第 {@code row} 行当前对应的曲目；这一行空着时返回 null。 */
    private MpTrack queueAt(int row) {
        List<MpTrack> queue = MpPartyClient.queue();
        int index = queueScroll + row;
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    private void buildRoomFooter(final Panel host) {
        final String[] labels = {"链接点歌", "邀请玩家", "成员管理", "同步一次", ""};
        final Runnable[] actions = {
                this::showLinkPage,
                this::showInvitePage,
                this::showMembersPage,
                MpPartyClient::sync,
                () -> {
                    if (MpPartyClient.isOwner()) MpPartyClient.disband();
                    else MpPartyClient.leaveRoom();
                }
        };
        for (int index = 0; index < labels.length; index++) {
            final int slot = index;
            Supplier<String> text = slot == labels.length - 1
                    ? () -> MpPartyClient.isOwner() ? "解散房间" : "退出房间"
                    : () -> labels[slot];
            btn(host, text, null, actions[slot],
                    slot == labels.length - 1 ? STYLE_DANGER : STYLE_PLAIN, widget -> {
                        double total = Math.max(1, host.getWidth() - 32);
                        double w = (total - 8 * 4) / 5.0;
                        widget.setBounds(w, 32);
                        widget.setPosition(16 + (w + 8) * slot, host.getHeight() - 62);
                    });
        }
    }

    // ──────────────────────────── 子页 ────────────────────────────

    private void showCreatePage() {
        inputPage(Page.CREATE, "创建房间", "给房间起个名字（留空就用默认名）。创建后你就是房主。",
                "建好之后可以邀请玩家，也可以把邀请码发给别人。",
                "例如：深夜电台", 32, "创建房间", value -> {
                    MpPartyClient.createRoom(value);
                    showOverview();
                }, false);
    }

    private void showJoinPage() {
        inputPage(Page.JOIN, "加入房间", "粘贴好友给的邀请码。房主在房间面板里点「复制邀请码」就能拿到。",
                "加入后播放器跟着房间走：本地起播会自动变成点歌排队。",
                "邀请码", 64, "加入房间", value -> {
                    if (value.isEmpty()) {
                        hint = "请先填写邀请码";
                        hintColor = ERROR_COLOR;
                        return;
                    }
                    MpPartyClient.joinRoom(value);
                    showOverview();
                }, true);
    }

    private void showInvitePage() {
        inputPage(Page.INVITE, "邀请玩家", "填写服务器里的玩家名，对方会收到一条带邀请码的提示。",
                "对方也得装着播放器 mod 才听得到声音。",
                "玩家名", 32, "发送邀请", value -> {
                    if (value.isEmpty()) {
                        hint = "请先填写玩家名";
                        hintColor = ERROR_COLOR;
                        return;
                    }
                    MpPartyClient.invite(value);
                    showOverview();
                }, true);
    }

    /** 链接点歌：整段分享文案 / 短链 / 纯歌曲 ID 都收。 */
    private void showLinkPage() {
        page = Page.LINK;
        field = null;
        hint = "支持整段分享文案、song?id=… 链接、163cn.tv 短链，或只有歌曲 ID";
        hintColor = 0xAEB5C4;
        getChildren().clear();

        final Panel dialog = createDialog(468, () -> 268.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "链接点歌", "解析在你本机完成，只把歌名与播放链接发给服务端");

        final TextFieldWidget input = inputField(dialog, "https://music.163.com/song?id=… 或 1234567", 512);

        LabelWidget hintLabel = label(dialog, () -> hint, FontManager.pf12);
        hintLabel.setBeforeRenderCallback(() -> {
            hintLabel.setColor(hintColor);
            hintLabel.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            hintLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hintLabel.setPosition(16, 132);
        });

        btn(dialog, () -> "排队点歌", null, () -> {
            String value = text(input);
            if (value.isEmpty()) {
                hint = "请先粘贴链接或歌曲 ID";
                hintColor = ERROR_COLOR;
                return;
            }
            MpPartyClient.requestSongByLink(value);
            showOverview();
        }, STYLE_PRIMARY, b -> {
            double total = Math.max(1, dialog.getWidth() - 32);
            b.setBounds((total - 8) * .5, 34);
            b.setPosition(16, dialog.getHeight() - 56);
        });

        btn(dialog, () -> "立即播放", MpPartyClient::isController, () -> {
            String value = text(input);
            if (value.isEmpty()) {
                hint = "请先粘贴链接或歌曲 ID";
                hintColor = ERROR_COLOR;
                return;
            }
            MpPartyClient.playNowByLink(value);
            showOverview();
        }, STYLE_PLAIN, b -> {
            double total = Math.max(1, dialog.getWidth() - 32);
            double w = (total - 8) * .5;
            b.setBounds(w, 34);
            b.setPosition(16 + w + 8, dialog.getHeight() - 56);
        });
    }

    private void showMembersPage() {
        page = Page.MEMBERS;
        field = null;
        memberScroll = 0;
        getChildren().clear();

        final Panel dialog = createDialog(508, () -> 408.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "成员管理", "管理员与房主职权完全相同：都能切歌、暂停、拖进度");

        LabelWidget header = label(dialog, () -> {
            int size = MpPartyClient.members().size();
            return size + " 人在房" + (size > MEMBER_ROWS ? " · 滚轮翻页" : "");
        }, FontManager.pf12bold);
        header.setBeforeRenderCallback(() -> header
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(16, 72));

        final Panel list = new Panel() {

            @Override
            public boolean canBeScrolled() {
                return MpPartyClient.members().size() > MEMBER_ROWS;
            }

            @Override
            public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
                if (dWheel == 0 || wheelConsumed) return true;
                wheelConsumed = true;
                int max = Math.max(0, MpPartyClient.members().size() - MEMBER_ROWS);
                memberScroll = Math.max(0, Math.min(max, memberScroll + (dWheel > 0 ? -1 : 1)));
                return true;
            }
        };
        dialog.addChild(list);
        list.setBeforeRenderCallback(() -> {
            int max = Math.max(0, MpPartyClient.members().size() - MEMBER_ROWS);
            if (memberScroll > max) memberScroll = max;
            list.setBounds(Math.max(1, dialog.getWidth() - 32), MEMBER_ROWS * 44);
            list.setPosition(16, 94);
        });

        for (int index = 0; index < MEMBER_ROWS; index++) {
            buildMemberRow(list, index);
        }

        LabelWidget tip = label(dialog,
                "转让房主后你会变成管理员。踢出只影响房间，不会影响服务器。", FontManager.pf12);
        tip.setBeforeRenderCallback(() -> {
            tip.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            tip.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            tip.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            tip.setPosition(16, dialog.getHeight() - 28);
        });
    }

    private void buildMemberRow(final Panel list, final int row) {
        final double[] reveal = new double[1];

        final RoundedRectWidget bg = new RoundedRectWidget();
        list.addChild(bg);
        bg.setClickable(false).setRadius(8).setBeforeRenderCallback(() -> {
            MpMember member = memberAt(row);
            reveal[0] = Interpolations.interpolate(reveal[0], member == null ? 0.0 : 1.0, .2f);
            bg.setBounds(Math.max(1, list.getWidth()), 38);
            bg.setPosition(0, row * 44);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            bg.setAlpha((float) (list.getAlpha() * reveal[0]));
        });

        LabelWidget name = label(bg, () -> {
            MpMember member = memberAt(row);
            return member == null ? "" : member.name;
        }, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, bg.getWidth() - 230));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(12, 7);
        });

        LabelWidget role = label(bg, () -> {
            MpMember member = memberAt(row);
            if (member == null) return "";
            return member.roleLabel() + (member.online ? (member.listening ? " · 在听" : " · 在线") : " · 离线");
        }, FontManager.pf12);
        role.setBeforeRenderCallback(() -> {
            MpMember member = memberAt(row);
            role.setColor(member != null && member.controls() ? OK_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            role.setPosition(12, 22);
        });

        btn(bg, () -> {
            MpMember member = memberAt(row);
            return member != null && "admin".equals(member.role) ? "撤销管理" : "设为管理";
        }, () -> {
            MpMember member = memberAt(row);
            return member != null && MpPartyClient.isOwner() && !isSelf(member) && !"owner".equals(member.role);
        }, () -> {
            MpMember member = memberAt(row);
            if (member != null) MpPartyClient.setAdmin(member.name, !"admin".equals(member.role));
        }, STYLE_ROW, b -> {
            b.setBounds(62, 24);
            b.setPosition(bg.getWidth() - 202, 7);
        });

        btn(bg, () -> "转让房主", () -> {
            MpMember member = memberAt(row);
            return member != null && MpPartyClient.isOwner() && !isSelf(member);
        }, () -> {
            MpMember member = memberAt(row);
            if (member != null) MpPartyClient.transferOwner(member.name);
        }, STYLE_ROW, b -> {
            b.setBounds(62, 24);
            b.setPosition(bg.getWidth() - 136, 7);
        });

        btn(bg, () -> "踢出", () -> {
            MpMember member = memberAt(row);
            return member != null && MpPartyClient.isController() && !isSelf(member) && !"owner".equals(member.role);
        }, () -> {
            MpMember member = memberAt(row);
            if (member != null) MpPartyClient.kick(member.name);
        }, STYLE_ROW_DANGER, b -> {
            b.setBounds(46, 24);
            b.setPosition(bg.getWidth() - 58, 7);
        });
    }

    /** 成员页第 {@code row} 行当前对应的成员；这一行空着时返回 null。 */
    private MpMember memberAt(int row) {
        List<MpMember> members = MpPartyClient.members();
        int index = memberScroll + row;
        return index >= 0 && index < members.size() ? members.get(index) : null;
    }

    private static boolean isSelf(MpMember member) {
        MpMember me = MpPartyClient.self();
        return me != null && member != null && me.uuid.equals(member.uuid);
    }

    // ──────────────────────────── 输入型子页骨架 ────────────────────────────

    private void inputPage(final Page target, final String title, final String subtitle,
                           final String hintText, final String placeholder, final int maxLength,
                           final String confirmText,
                           final java.util.function.Consumer<String> action, final boolean required) {
        page = target;
        field = null;
        // 提示行与副标题不能是同一句话，否则同一段文字会在弹窗里出现两遍。
        hint = hintText;
        hintColor = 0xAEB5C4;
        getChildren().clear();

        final Panel dialog = createDialog(452, () -> 244.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, title, subtitle);

        final TextFieldWidget input = inputField(dialog, placeholder, maxLength);

        LabelWidget hintLabel = label(dialog, () -> hint, FontManager.pf12);
        hintLabel.setBeforeRenderCallback(() -> {
            hintLabel.setColor(hintColor);
            hintLabel.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            hintLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hintLabel.setPosition(16, 132);
        });

        btn(dialog, () -> confirmText, null, () -> {
            String value = text(input);
            if (required && value.isEmpty()) {
                hint = "这里不能留空";
                hintColor = ERROR_COLOR;
                return;
            }
            action.accept(value);
        }, STYLE_PRIMARY, b -> {
            b.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            b.setPosition(16, dialog.getHeight() - 56);
        });
    }

    private TextFieldWidget inputField(final Panel dialog, String placeholder, int maxLength) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setClickable(false).setRadius(7).setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            surface.setPosition(16, 86);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        final TextFieldWidget input = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(input);
        input.getTextField().setMaxStringLength(maxLength);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder(placeholder);
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, surface.getWidth() - 12), Math.max(1, surface.getHeight() - 8));
            input.setPosition(surface.getRelativeX() + 6, surface.getRelativeY() + 4);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        field = input;
        return input;
    }

    private static String text(TextFieldWidget input) {
        String value = input == null ? null : input.getText();
        return value == null ? "" : value.trim();
    }

    // ──────────────────────────── 对话框骨架 ────────────────────────────

    private Panel createDialog(final double preferredWidth, final DoubleSupplier preferredHeight,
                               final Runnable perFrame) {
        presentation = 0.0;
        dialogHeight = 0.0;

        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0).setAlpha(.48f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        final Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            presentation = Interpolations.interpolate(presentation, 1.0, .18f);
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double target = Math.max(1, Math.min(preferredHeight.getAsDouble(), getHeight() - 24));
            // 第一帧直接落在目标高度，之后才插值；否则入场会从 1px 拉开，像卷帘门。
            dialogHeight = dialogHeight <= 0.0 ? target : Interpolations.interpolate(dialogHeight, target, .2f);
            dialog.setBounds(width, dialogHeight);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - dialogHeight * .5 + (1.0 - presentation) * 10.0);
            if (perFrame != null) perFrame.run();
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(13);
        background.setBeforeRenderCallback(() -> {
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            background.setMargin(0);
        });
        return dialog;
    }

    private void addTitle(final Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = label(dialog, titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(16, 14));

        LabelWidget subtitle = label(dialog, subtitleText, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 右上角留给「‹ 一起听」返回按钮。
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 116));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16, 39);
        });
    }

    private void addDetailTitle(final Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = label(dialog, titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(56, 14));

        LabelWidget subtitle = label(dialog, subtitleText, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 72));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(56, 39);
        });
    }

    private void addBackButton(final Panel dialog, final Runnable action) {
        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        dialog.addChild(back);
        back.setRadius(7);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 24);
            back.setPosition(16, 13);
            back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void addStatusLine(final Panel dialog) {
        LabelWidget status = label(dialog, () -> {
            String message = MpPartyClient.lastMessage();
            if (message != null && !message.isEmpty()) return message;
            if (MpPartyClient.waitingUrl()) return "服务端在等有人补一条可用的取流链接…";
            if (MpPartyClient.inRoom()) return "房间已连接。";
            return "";
        }, FontManager.pf12);
        status.setBeforeRenderCallback(() -> {
            status.setColor(MpPartyClient.lastMessageIsError() ? ERROR_COLOR
                    : MpPartyClient.inRoom() ? OK_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, dialog.getHeight() - 22);
        });
    }

    /** 一段内容的容器：本身不画任何东西，只是用来整段切显隐。 */
    private Panel section(final Panel dialog) {
        Panel panel = new Panel();
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, dialog.getWidth()), Math.max(1, dialog.getHeight()));
            panel.setPosition(0, 0);
        });
        return panel;
    }

    // ──────────────────────────── 小工具 ────────────────────────────

    private RoundedRectWidget card(final Panel host, final double top, final double height) {
        RoundedRectWidget widget = new RoundedRectWidget();
        host.addChild(widget);
        widget.setClickable(false).setRadius(10).setBeforeRenderCallback(() -> {
            widget.setBounds(Math.max(1, host.getWidth() - 32), height);
            widget.setPosition(16, top);
            widget.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        return widget;
    }

    private LabelWidget label(final AbstractWidget<?> host, String text, CFontRenderer font) {
        LabelWidget widget = new LabelWidget(text, font);
        host.addChild(widget);
        widget.setClickable(false);
        return widget;
    }

    private LabelWidget label(final AbstractWidget<?> host, Supplier<String> text, CFontRenderer font) {
        LabelWidget widget = new LabelWidget(text, font);
        host.addChild(widget);
        widget.setClickable(false);
        return widget;
    }

    private interface Layout {
        void apply(RoundedButtonWidget button);
    }

    /**
     * 一个按钮。{@code enabled} 为 null 表示永远可按。
     *
     * <p>不可用时用 alpha + {@code setClickable(false)} 变暗（行内按钮直接变透明），
     * 而不是 {@code setHidden}：被隐藏的组件不再执行自己的回调，也就再没有机会把自己显示回来。</p>
     */
    private RoundedButtonWidget btn(final AbstractWidget<?> host, final Supplier<String> text,
                                    final BooleanSupplier enabled, final Runnable action,
                                    final int style, final Layout layout) {
        final double[] hover = new double[1];
        final RoundedButtonWidget button = new RoundedButtonWidget(text, FontManager.pf12bold);
        host.addChild(button);
        boolean row = style == STYLE_ROW || style == STYLE_ROW_DANGER;
        button.setRadius(row ? 6 : 8);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            // 不可用时也吃掉这次点击：否则会穿到底下的遮罩，把整个弹窗关掉。
            if (enabled != null && !enabled.getAsBoolean()) return true;
            action.run();
            return true;
        });
        button.setBeforeRenderCallback(() -> {
            boolean usable = enabled == null || enabled.getAsBoolean();
            hover[0] = Interpolations.interpolate(hover[0], usable && button.isHovering() ? 1.0 : 0.0, .22f);
            layout.apply(button);
            switch (style) {
                case STYLE_PRIMARY:
                    button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .16));
                    button.setTextColor(0xFFFFFF);
                    break;
                case STYLE_DANGER:
                    button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), ERROR_COLOR,
                            .3 + hover[0] * .55));
                    button.setTextColor(0xFFFFFF);
                    break;
                case STYLE_ROW:
                    button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                            NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
                    button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    break;
                case STYLE_ROW_DANGER:
                    button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND), ERROR_COLOR,
                            .26 + hover[0] * .5));
                    button.setTextColor(0xFFFFFF);
                    break;
                default:
                    button.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                            NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
                    button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    break;
            }
            button.setClickable(usable);
            // 行内按钮不适用的时候整颗消失（那一行本来就可能是空行）；页面级按钮只变暗。
            button.setAlpha(usable ? host.getAlpha() : (row ? 0f : host.getAlpha() * .35f));
        });
        return button;
    }

    private static boolean has(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String mmss(long millis) {
        if (millis <= 0L) return "00:00";
        long total = millis / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    /**
     * 在两个 {@code 0xRRGGBB} 之间线性插值。
     *
     * <p>只混 RGB：{@code AbstractWidget.setColor(int)} 走 {@code new Color(int)}，高位 alpha
     * 会被丢掉（透明度由组件自己的 alpha 决定），传半透明色没有意义。</p>
     */
    private static int mix(int from, int to, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, double t) {
        return Math.max(0, Math.min(255, (int) Math.round(from + (to - from) * t)));
    }
}
