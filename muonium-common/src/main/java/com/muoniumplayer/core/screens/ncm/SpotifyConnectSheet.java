package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.spotify.SpotifyAuthStore;
import com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror;
import com.muoniumplayer.core.ncm.spotify.SpotifyConnectService;
import com.muoniumplayer.core.ncm.spotify.SpotifyMusicService;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;

/**
 * Spotify 设备控制面板（方案一「客户端控制」的 UI 面）。
 *
 * <p>三块内容：当前播放卡（曲名/进度/传输控制/音量）、设备列表（每台可「转移播放」）、
 * 以及「接管到模组界面」开关——开关打开即 {@link SpotifyConnectMirror} 的镜像模式，
 * 底栏进度条、歌词页、灵动岛从此跟随 Spotify 播放。</p>
 *
 * <p><b>数据不是这棵控件树自己拉的</b>：每 2.5 秒捶一次 {@link SpotifyConnectService#refreshAsync}
 * （自带在飞去重，与镜像轮询共用同一条快照缓存），所有标签用 Supplier 每帧现读缓存。
 * 所以列表行数变化不需要重建页面——固定 6 个槽位，空槽把按钮藏起来、文字留白。</p>
 */
public final class SpotifyConnectSheet extends NcmSheet {

    /** 设备列表的固定槽位数。超过 6 台设备时只显示前 6 台（真实场景很少见）。 */
    private static final int DEVICE_SLOTS = 6;
    private static final double DEVICE_ROW_HEIGHT = 46.0;
    private static final long AUTO_REFRESH_INTERVAL_MS = 2_500L;
    /** 一次操作的反馈文案停留多久，之后回到实时状态行。 */
    private static final long STATUS_HOLD_MS = 6_000L;

    private volatile String statusText = "";
    private volatile int statusTone;
    private volatile long statusAtMillis;
    private volatile long lastAutoRefreshAt;

    @Override
    public void onInit() {
        statusText = "";
        SpotifyConnectService.refreshAsync(null);
        showMain();
    }

    /** ESC 关闭：与其它浮层一样是本类自有的方法（NCMScreen 的键盘分发按鸭子类型调用）。 */
    public void handleEscape() {
        dispose();
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        super.onRender(mouseX, mouseY);
        long now = System.currentTimeMillis();
        if (now - lastAutoRefreshAt >= AUTO_REFRESH_INTERVAL_MS) {
            lastAutoRefreshAt = now;
            SpotifyConnectService.refreshAsync(null);
        }
    }

    // ── 页面骨架 ─────────────────────────────────────────────────────────────

    private void showMain() {
        beginPage();
        final Panel dialog = sheet(476, () -> 430.0);
        subHeader(dialog, back(this::dispose), () -> "Spotify · 设备控制",
                () -> SpotifyConnectMirror.isMirroring() ? SpotifyConnectMirror.statusLabel()
                        : "遥控你账号里正在播放的 Spotify 设备",
                MusicPlatform.SPOTIFY::getBrandColor);
        tip(dialog, this::statusGlyph, this::liveStatus, this::statusColor, () -> 60.0);

        final Panel body = body(dialog, () -> 84.0, () -> 10.0);
        final ScrollPanel list = feed(body);
        buildNowPlaying(list);
        buildDevices(list);
        buildMirrorSwitch(list);
    }

    // ── 当前播放卡 ───────────────────────────────────────────────────────────

    private void buildNowPlaying(final ScrollPanel list) {
        sectionLabel(strip(list, 18.0), "当前播放", () -> 4.0, MusicPlatform.SPOTIFY::getBrandColor);
        final Panel row = strip(list, () -> 132.0);
        final Card surface = plain(card(row, 0, () -> 4.0, () -> 124.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));

        final LabelWidget title = label(surface, this::nowTitle, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setMaxWidth(Math.max(1, surface.getWidth() - 24));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(12, inkTop(title, 20.0));
        });
        final LabelWidget subtitle = label(surface, this::nowSubtitle, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, surface.getWidth() - 24));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(12, inkTop(subtitle, 40.0));
        });

        final RoundedRectWidget trackBg = new RoundedRectWidget();
        surface.addChild(trackBg);
        trackBg.setClickable(false).setRadius(2).setBeforeRenderCallback(() -> {
            trackBg.setBounds(Math.max(1, surface.getWidth() - 24), 4);
            trackBg.setPosition(12, 56);
            trackBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        final RoundedRectWidget trackFill = new RoundedRectWidget();
        surface.addChild(trackFill);
        trackFill.setClickable(false).setRadius(2).setBeforeRenderCallback(() -> {
            SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
            long duration = state == null ? 0L : state.durationMs;
            double fraction = duration <= 0L ? 0.0
                    : clamp01(state.positionMsNow() / (double) Math.max(1L, duration));
            trackFill.setBounds(Math.max(1.0, (surface.getWidth() - 24) * fraction), 4);
            trackFill.setPosition(12, 56);
            trackFill.setColor(MusicPlatform.SPOTIFY.getBrandColor());
        });
        final LabelWidget elapsed = label(surface, () -> {
            SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
            return formatTime(state == null ? 0L : state.positionMsNow());
        }, FontManager.pf10bold);
        elapsed.setBeforeRenderCallback(() -> {
            elapsed.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            elapsed.setPosition(12, inkTop(elapsed, 72.0));
        });
        final LabelWidget remaining = label(surface, this::nowDuration, FontManager.pf10bold);
        remaining.setBeforeRenderCallback(() -> {
            remaining.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            remaining.setPosition(Math.max(12.0, surface.getWidth() - 12 - remaining.getWidth()),
                    inkTop(remaining, 72.0));
        });

        // 传输控制三键：图标整组居中由 action() 负责，这里只摆位置。device_id 一律空 ——
        // 让 Spotify 自己路由到「当前活动设备」，用户在客户端里切了设备命令也不会打错地方。
        final java.util.function.DoubleSupplier centerX = () -> surface.getWidth() * .5;
        final RoundedButtonWidget prevBtn = action(surface, () -> PartyIcons.SKIP_PREVIOUS, () -> "",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> runCommand(() -> SpotifyConnectService.previous("")));
        prevBtn.setBeforeRenderCallback(() -> {
            prevBtn.setBounds(44, 26);
            prevBtn.setPosition(centerX.getAsDouble() - 40 - 44, 84);
            prevBtn.setColor(prevBtn.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });
        final RoundedButtonWidget playBtn = action(surface, this::transportGlyph, () -> "",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> runCommand(() -> toggleTransport()));
        playBtn.setBeforeRenderCallback(() -> {
            playBtn.setBounds(80, 26);
            playBtn.setPosition(centerX.getAsDouble() - 40, 84);
            playBtn.setColor(playBtn.isHovering()
                    ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0x000000, .12));
        });
        final RoundedButtonWidget nextBtn = action(surface, () -> PartyIcons.SKIP_NEXT, () -> "",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> runCommand(() -> SpotifyConnectService.next("")));
        nextBtn.setBeforeRenderCallback(() -> {
            nextBtn.setBounds(44, 26);
            nextBtn.setPosition(centerX.getAsDouble() + 40, 84);
            nextBtn.setColor(nextBtn.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        // 音量行：设备报告 supports_volume 才可调；不可调时按钮藏起来，只留只读百分比。
        final LabelWidget volume = label(surface, this::volumeText, FontManager.pf10bold);
        volume.setBeforeRenderCallback(() -> {
            volume.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            volume.setPosition(12, inkTop(volume, 110.0));
        });
        final RoundedButtonWidget volumeDown = action(surface, () -> "−", () -> "",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> stepVolume(-5));
        final RoundedButtonWidget volumeUp = action(surface, () -> "＋", () -> "",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                () -> stepVolume(5));
        volumeDown.setBeforeRenderCallback(() -> {
            volumeDown.setBounds(26, 20);
            volumeDown.setPosition(surface.getWidth() - 12 - 26 - 30 - 26, 100);
            volumeDown.setColor(volumeDown.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            volumeDown.setHidden(!deviceVolumeAdjustable());
        });
        volumeUp.setBeforeRenderCallback(() -> {
            volumeUp.setBounds(26, 20);
            volumeUp.setPosition(surface.getWidth() - 12 - 26, 100);
            volumeUp.setColor(volumeUp.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            volumeUp.setHidden(!deviceVolumeAdjustable());
        });
    }

    private String nowTitle() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null) return "Spotify 没在播放";
        if (state.track == null) return "正在播非曲目内容（播客/电台）";
        return state.track.name.isEmpty() ? "未知曲目" : state.track.name;
    }

    private String nowSubtitle() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null) return "在任意 Spotify 客户端点一首歌，这里就会跟着动";
        String device = state.deviceName.isEmpty() ? "" : " · " + state.deviceName;
        String artistAlbum = state.track == null ? "" : state.track.artist;
        if (artistAlbum.isEmpty() && state.track != null && !state.track.album.isEmpty()) {
            artistAlbum = state.track.album;
        } else if (!artistAlbum.isEmpty() && !state.track.album.isEmpty()) {
            artistAlbum += " · " + state.track.album;
        }
        return artistAlbum + device;
    }

    private String nowDuration() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        return formatTime(state == null ? 0L : state.durationMs);
    }

    private String transportGlyph() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        boolean playing = state != null && state.playing;
        return playing ? PartyIcons.PAUSE : PartyIcons.PLAY;
    }

    private void toggleTransport() throws Exception {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        boolean playing = state != null && state.playing;
        if (playing) {
            SpotifyConnectService.pause("");
        } else {
            SpotifyConnectService.resume("");
        }
    }

    private String volumeText() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null || state.volumePercent < 0) return "音量未知";
        return "音量 " + state.volumePercent + "%";
    }

    private boolean deviceVolumeAdjustable() {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        return state != null && state.supportsVolume && state.volumePercent >= 0;
    }

    private void stepVolume(int delta) {
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null || !deviceVolumeAdjustable()) return;
        final int target = Math.max(0, Math.min(100, state.volumePercent + delta));
        runCommand(() -> SpotifyConnectService.setVolume(target, ""));
    }

    // ── 设备列表 ─────────────────────────────────────────────────────────────

    private void buildDevices(final ScrollPanel list) {
        sectionLabel(strip(list, 18.0), "设备", () -> 4.0, MusicPlatform.SPOTIFY::getBrandColor);
        final Panel row = strip(list, () -> 12.0 + deviceListHeight());
        final Card surface = plain(card(row, 1, () -> 4.0, () -> deviceListHeight(),
                () -> NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));

        final LabelWidget empty = label(surface, this::deviceEmptyText, FontManager.pf12);
        empty.setBeforeRenderCallback(() -> {
            empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            empty.setMaxWidth(Math.max(1, surface.getWidth() - 28));
            empty.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            empty.setPosition(14, inkTop(empty, 24.0));
        });

        for (int i = 0; i < DEVICE_SLOTS; i++) {
            final int index = i;
            final double rowTop = 8.0 + index * DEVICE_ROW_HEIGHT;

            final LabelWidget name = label(surface, () -> {
                SpotifyConnectService.Device device = deviceAt(index);
                return device == null ? "" : device.name;
            }, FontManager.pf12bold);
            final LabelWidget type = label(surface, () -> {
                SpotifyConnectService.Device device = deviceAt(index);
                return device == null ? "" : deviceTypeText(device.type);
            }, FontManager.pf10bold);

            // 「当前」徽标与「转移播放」按钮的可见性由每帧都活着的 name 回调统一管——
            // 被藏起来的控件自己的回调不会再跑，没人管它们就回不来了。
            final RoundedButtonWidget transfer = rowButton(surface, "转移播放",
                    () -> {
                        SpotifyConnectService.Device device = deviceAt(index);
                        if (device == null) return;
                        runCommand(() -> SpotifyConnectService.transfer(device.id, true));
                    });
            name.setBeforeRenderCallback(() -> {
                SpotifyConnectService.Device device = deviceAt(index);
                boolean present = device != null;
                name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                name.setMaxWidth(Math.max(1, surface.getWidth() - 220));
                name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                name.setPosition(14, inkTop(name, rowTop + 14.0));
                type.setHidden(!present);
                transfer.setHidden(!present);
            });
            type.setBeforeRenderCallback(() -> {
                type.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                type.setPosition(14, inkTop(type, rowTop + 32.0));
            });
            transfer.setBeforeRenderCallback(() -> {
                transfer.setBounds(72, 24);
                transfer.setPosition(Math.max(14.0, surface.getWidth() - 14 - 72), rowTop + 11.0);
                transfer.setColor(transfer.isHovering()
                        ? mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, .16)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            });
            // 当前活动设备画一枚绿点：正在出声的就是它，转移按钮对它没有意义但也无害。
            final RoundedRectWidget activeDot = new RoundedRectWidget();
            surface.addChild(activeDot);
            activeDot.setClickable(false).setRadius(2.5).setBeforeRenderCallback(() -> {
                SpotifyConnectService.Device device = deviceAt(index);
                boolean active = device != null && device.active;
                activeDot.setHidden(!active);
                activeDot.setBounds(5, 5);
                activeDot.setPosition(surface.getWidth() - 14 - 72 - 14, rowTop + 13.0);
                activeDot.setColor(OK_COLOR);
            });
        }
    }

    private String deviceEmptyText() {
        return SpotifyConnectService.lastKnownDevices().isEmpty()
                ? "没有在线的 Spotify 设备：先在手机/电脑/音箱的 Spotify 客户端里点一首歌" : "";
    }

    private static double deviceListHeight() {
        int devices = Math.min(DEVICE_SLOTS, SpotifyConnectService.lastKnownDevices().size());
        return 16.0 + Math.max(1, devices) * DEVICE_ROW_HEIGHT;
    }

    private static SpotifyConnectService.Device deviceAt(int index) {
        List<SpotifyConnectService.Device> devices = SpotifyConnectService.lastKnownDevices();
        return index < devices.size() ? devices.get(index) : null;
    }

    private static String deviceTypeText(String type) {
        if (type == null || type.isEmpty()) return "Spotify 设备";
        switch (type) {
            case "Computer": return "电脑";
            case "Tablet": return "平板";
            case "Smartphone": return "手机";
            case "Speaker": return "音箱";
            case "TV": return "电视";
            case "CastVideo": return "投屏视频";
            case "CastAudio": return "投屏音频";
            case "Automobile": return "汽车";
            case "Console": return "游戏机";
            default: return type;
        }
    }

    // ── 接管开关 ─────────────────────────────────────────────────────────────

    private void buildMirrorSwitch(final ScrollPanel list) {
        final Panel row = strip(list, 66.0);
        final Card surface = plain(card(row, 2, () -> 4.0, () -> 58.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
        final LabelWidget title = label(surface, () -> "接管到模组界面", FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(14, inkTop(title, 16.0));
        });
        final LabelWidget description = label(surface, () ->
                SpotifyConnectMirror.isMirroring()
                        ? "接管中：进度条、歌词、灵动岛跟随 Spotify；内置播放已让位"
                        : "模组界面的进度条与歌词跟随 Spotify 播放（不落盘，重开游戏需重新打开）",
                FontManager.pf10bold);
        description.setBeforeRenderCallback(() -> {
            description.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            description.setMaxWidth(Math.max(1, surface.getWidth() - 28 - 50));
            description.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            description.setPosition(14, inkTop(description, 38.0));
        });
        toggle(surface, SpotifyConnectMirror::isMirroring, this::flipMirror,
                () -> surface.getWidth() - 12, () -> 18.0, MusicPlatform.SPOTIFY::getBrandColor);
    }

    private void flipMirror() {
        if (SpotifyConnectMirror.isMirroring()) {
            SpotifyConnectMirror.stop("已断开 Spotify 接管");
            setStatus("已断开接管，模组界面回到内置播放器", 0);
            return;
        }
        if (!SpotifyMusicService.isLoggedIn()) {
            setStatus("先在「音乐来源 · Spotify」完成登录", 2);
            return;
        }
        if (!SpotifyAuthStore.snapshot().hasPlaybackScopes()) {
            setStatus("当前授权缺少播放控制权限（旧授权），请在登录页重新授权一遍", 2);
            return;
        }
        SpotifyConnectMirror.start("");
        setStatus("接管已开启：模组界面跟随 Spotify 播放", 1);
    }

    // ── 命令与状态行 ─────────────────────────────────────────────────────────

    private interface SpotifyCommand {
        void run() throws Exception;
    }

    /** 控制命令的后台线程包装；失败翻成用户话术落进状态行。 */
    private void runCommand(SpotifyCommand command) {
        if (!SpotifyMusicService.isLoggedIn()) {
            setStatus("先在「音乐来源 · Spotify」完成登录", 2);
            return;
        }
        MultiThreadingUtil.runAsync(() -> {
            try {
                command.run();
            } catch (Throwable failure) {
                String message = SpotifyConnectService.userMessage(failure);
                setStatus(message.isEmpty()
                        ? "命令失败：" + failure.getMessage() : message, 2);
            }
        });
    }

    private void setStatus(String text, int tone) {
        statusText = text == null ? "" : text;
        statusTone = tone;
        statusAtMillis = System.currentTimeMillis();
    }

    private String liveStatus() {
        long now = System.currentTimeMillis();
        if (!statusText.isEmpty() && now - statusAtMillis < STATUS_HOLD_MS) return statusText;
        if (!statusText.isEmpty()) statusText = "";
        if (!SpotifyMusicService.isLoggedIn()) return SpotifyMusicService.describeStatus();
        SpotifyAuthStore.Session session = SpotifyAuthStore.snapshot();
        if (!session.hasPlaybackScopes()) {
            return "当前授权缺少播放控制权限（旧授权），请到「音乐来源 · Spotify」重新授权一遍";
        }
        SpotifyConnectService.PlaybackState state = SpotifyConnectService.lastKnownPlayback();
        if (state == null) return "没有正在播放的 Spotify 设备；在任意客户端点一首歌就会出现";
        String device = state.deviceName.isEmpty() ? "Spotify 设备" : state.deviceName;
        String mirror = SpotifyConnectMirror.isMirroring() ? " · 接管中" : "";
        return device + " · " + (state.playing ? "播放中" : "已暂停") + mirror;
    }

    private int statusColor() {
        long now = System.currentTimeMillis();
        boolean showingFeedback = !statusText.isEmpty() && now - statusAtMillis < STATUS_HOLD_MS;
        if (showingFeedback && statusTone == 2) return BAD_COLOR;
        if (showingFeedback && statusTone == 1) return OK_COLOR;
        if (!SpotifyMusicService.isLoggedIn()) return WARN_COLOR;
        return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusGlyph() {
        long now = System.currentTimeMillis();
        boolean showingFeedback = !statusText.isEmpty() && now - statusAtMillis < STATUS_HOLD_MS;
        if (showingFeedback && statusTone == 2) return PartyIcons.CLOSE;
        if (showingFeedback && statusTone == 1) return PartyIcons.CHECK;
        return PartyIcons.INFO;
    }

    /** 行内小按钮：构造期给死文案（「转移播放」不换字），点击行为由调用方给。 */
    private RoundedButtonWidget rowButton(final AbstractWidget<?> host, final String text,
                                          final Runnable onClick) {
        final RoundedButtonWidget button = new RoundedButtonWidget(text, FontManager.pf10bold);
        host.addChild(button);
        button.setRadius(7);
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            onClick.run();
            return true;
        });
        return button;
    }

    private static String formatTime(long millis) {
        if (millis < 0L) millis = 0L;
        long totalSeconds = millis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
