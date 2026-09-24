package com.muoniumplayer;

import com.mojang.blaze3d.platform.InputConstants;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.MusicHotkeys;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import com.muoniumplayer.core.rendering.AutomixTransitionAnimation;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.GuiAnnouncements;
import com.muoniumplayer.core.screens.GuiFfmpegNotice;
import com.muoniumplayer.core.screens.GuiUpdateNotice;
import com.muoniumplayer.core.screens.hud.GuiHudEditor;
import com.muoniumplayer.core.screens.hud.GuiHudEditorFolia;
import com.muoniumplayer.core.screens.hud.GuiHudEditorPro;
import com.muoniumplayer.core.screens.hud.ModernUiHudBridge;
import com.muoniumplayer.core.screens.hud.OneConfigHudBridge;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.update.UpdateChecker;
import com.muoniumplayer.render.skija.SkijaHost;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import today.opai.api.OpenAPI;
import today.opai.api.events.EventRender2D;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.impl.OpenAPIImpl;
import today.opai.api.impl.WindowResolutionImpl;
import today.opai.api.interfaces.EventHandler;

import java.util.List;

/**
 * 与加载器无关的客户端主体（原 {@code MuoniumPlayerMod} 的全部业务逻辑）。
 *
 * <p>拆出这一层的原因：Fabric 与 NeoForge 的差别只有四件事 —— 入口注解、按键注册、
 * 事件挂点、自定义通道。把这四件事留给 {@code fabric/} 与 {@code neoforge/} 两个子工程，
 * 其余（初始化顺序、tick 里的按键语义、Skia 帧的开合、渲染兜底闸门）在这里只写一份。
 *
 * <p>调用契约（两个加载器都必须照做，顺序不能换）：
 * <ol>
 *   <li>{@link #createKeyMappings()} —— 只构造 {@link KeyMapping}，注册交给加载器
 *       （Fabric 用 {@code KeyBindingHelper}，NeoForge 用 {@code RegisterKeyMappingsEvent}）；</li>
 *   <li>{@link #init()} —— 注入 OpenAPI 单例并初始化扩展。必须在任何引用
 *       {@code Extension.getAPI()} / {@code SharedConstants.api} 的类加载之前完成；</li>
 *   <li>加载器自己注册一起听通道（{@code MpNetFabric} / {@code MpNetNeoForge}）；</li>
 *   <li>每 tick 调 {@link #onClientTick(Minecraft)}，HUD 画完调 {@link #onHudRender()}，
 *       Screen 画完调 {@link #onScreenRendered(Screen)}。</li>
 * </ol>
 */
public final class MuoniumPlayerClient {

    public static final String MOD_ID = "muonium";
    public static final String MOD_NAME = "MuoniumPlayer";
    public static final String MOD_VERSION = "1.6.1";

    /**
     * 本工程对应的 Minecraft 版本。刻意写死而不是问原版要：共享源码不许引用加载器 API，
     * 而原版取版本号的入口四个版本各不相同。静态测试钉住它与 gradle.properties 的
     * minecraft_version 一致，改一处漏另一处会直接构建失败。
     */
    public static final String MC_VERSION = "1.21.1";

    /**
     * 加载器 id（{@code fabric} / {@code forge} / {@code neoforge}），由入口外壳在 {@link #init()}
     * 之前登记一次。共享源码没法自己判断跑在哪个加载器上（判断本身就要引用加载器 API），
     * 而更新清单是按「MC 版本 + 加载器」分桶的，所以必须由外壳告诉它。
     */
    private static volatile String loaderId = "unknown";

    /** 由 Fabric / NeoForge 外壳在 {@link #init()} 之前调用。 */
    public static void setLoaderId(String id) {
        if (id != null && !id.trim().isEmpty()) {
            loaderId = id.trim();
        }
    }

    /** 登记过的加载器 id；没登记时是 {@code unknown}，更新检查会退到清单里的 fallback。 */
    public static String loaderId() {
        return loaderId;
    }

    /** 按键分类名，在「选项 → 控制」中作为分组标题显示。 */
    public static final String KEY_CATEGORY = "key.categories.muonium";

    // ==================== 可配置按键绑定 ====================
    // 由 createKeyMappings() 构造、由各加载器注册；tick 里只读。
    /** 打开网易云音乐主界面，默认右 Shift。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyOpenMusic;
    /** 打开 HUD 位置/缩放编辑器，默认 H。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyEditHud;
    /** 切换到上一曲，默认 Page Up。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyPreviousTrack;
    /** 切换到下一曲，默认 Page Down。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyNextTrack;
    /** 增加播放器音量，默认数字键盘 +。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyVolumeUp;
    /** 降低播放器音量，默认数字键盘 -。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyVolumeDown;
    /** 暂停 / 继续播放，默认数字键盘 *。可在「选项 → 控制」中修改。 */
    public static KeyMapping keyTogglePause;

    private static final MuoniumPlayerClient INSTANCE = new MuoniumPlayerClient();

    private OpenAPIImpl api;

    private MuoniumPlayerClient() {
    }

    public static MuoniumPlayerClient getInstance() {
        return INSTANCE;
    }

    /**
     * 构造七个可重绑定按键（不注册）。
     *
     * <p>默认值仍写作 {@code Keyboard.KEY_*}（LWJGL2 码值），构造时用
     * {@link Keyboard#toGlfw(int)} 翻成 GLFW 码值，这样默认键位与 1.8.9 一模一样。
     *
     * <p>幂等：重复调用会重新构造并覆盖静态字段，方便 NeoForge 在
     * {@code RegisterKeyMappingsEvent} 里按需取用。
     */
    public static List<KeyMapping> createKeyMappings() {
        keyOpenMusic = key("key.muonium.open_music", Keyboard.KEY_RSHIFT);
        keyEditHud = key("key.muonium.edit_hud", Keyboard.KEY_H);
        // Page Up / Page Down 避开原版移动与物品栏按键，
        // 同时仍可在「选项 → 控制」中完全重绑定。
        keyPreviousTrack = key("key.muonium.previous_track", Keyboard.KEY_PRIOR);
        keyNextTrack = key("key.muonium.next_track", Keyboard.KEY_NEXT);
        keyVolumeUp = key("key.muonium.volume_up", Keyboard.KEY_ADD);
        keyVolumeDown = key("key.muonium.volume_down", Keyboard.KEY_SUBTRACT);
        // 和音量的小键盘 +/- 放在一起，构成一组「媒体键」；原版没有占用小键盘 *，因此默认不冲突。
        keyTogglePause = key("key.muonium.toggle_pause", Keyboard.KEY_MULTIPLY);
        return List.of(keyOpenMusic, keyEditHud, keyPreviousTrack, keyNextTrack,
                keyVolumeUp, keyVolumeDown, keyTogglePause);
    }

    private static KeyMapping key(String translationKey, int lwjgl2DefaultKey) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM,
                Keyboard.toGlfw(lwjgl2DefaultKey), KEY_CATEGORY);
    }

    /**
     * 客户端初始化（原 Forge 1.8.9 的 preInit + init 两阶段合并）。
     *
     * <p>两阶段之间原本没有跨 mod 依赖，合并不改变顺序语义。
     */
    public void init() {
        // 1. 注入 OpenAPI 单例（必须在任何引用 Extension.getAPI() / SharedConstants.api 的类加载之前完成）
        this.api = new OpenAPIImpl();
        OpenAPI.setInstance(this.api);

        // 2. 初始化 MuoniumPlayer 音乐扩展（注册事件处理器、模块、Widget）
        MuoniumPlayerExtension.getInstance().init(this.api);

        // 3. 动态封面与 B 站音频解析都要外部 ffmpeg。这里只是在守护线程上探一次并把结论缓存下来：
        //    缺少时由 GuiFfmpegNotice 在主菜单弹一次全屏提示，同时也让 HUD 编辑器的「未检测到 ffmpeg」
        //    标签在用户放第一首歌之前就有结论。探测会创建子进程，绝不能放在主线程上。
        FfmpegNotice.beginProbe();

        // 4. 更新检查：守护线程上最多拉一次远端版本清单（结果缓存 30 分钟，失败静默），
        //    有新版本时由 GuiUpdateNotice 在主菜单弹一次提示。检查不可关闭，用户只能「先不更新」有限几次。
        UpdateChecker.beginCheck(MOD_VERSION, MC_VERSION, loaderId);

        // 5. 公告：与更新检查读同一份远端清单，但自己拉、自己记「哪些看过」，两条路互不牵连。
        //    有未读时由 GuiAnnouncements 在主菜单弹一次，关掉即整批记为已读；拉不到什么都不提示。
        AnnouncementChecker.beginFetch(MOD_VERSION, MC_VERSION, loaderId);
    }

    /** 供加载器侧的事件回调判断初始化是否已完成（NeoForge 的事件可能早于 clientSetup 触发）。 */
    public boolean isReady() {
        return this.api != null;
    }

    // ==================== 事件桥接 ====================

    /**
     * 客户端 Tick（对应 1.8.9 的 {@code TickEvent.ClientTickEvent} END 阶段）：
     *  - 驱动 OpenAPI EventHandler.onLoop()
     *  - 驱动所有模块（含模块自身的 eventHandler）的 onTick()
     *  - 检测可配置按键绑定（打开音乐界面 / HUD 编辑器）
     */
    public void onClientTick(Minecraft mc) {
        if (this.api == null) {
            return;
        }

        // ---- 驱动所有注册的 EventHandler（如 MuoniumPlayerEventHandler 的调度队列）----
        for (EventHandler handler : this.api.getEventHandlers()) {
            handler.onLoop();
        }

        // ---- 驱动所有模块：onTick + 模块自身 eventHandler 的 onLoop ----
        for (ExtensionModule module : this.api.getModules()) {
            // 模块自身的 EventHandler（MusicInfoWidget / MusicLyricsWidget / OpenNCMScreen 均 setEventHandler(this)）
            EventHandler moduleHandler = module.getEventHandler();
            if (moduleHandler != null) {
                moduleHandler.onLoop();
            }
            // 启用的模块执行 onTick（OpenNCMScreen.onTick 会自动 setEnabled(false)，实现按钮式触发）
            if (module.isEnabled()) {
                module.onTick();
            }
        }

        // ---- 缺少 ffmpeg 的一次性全屏提示 ----
        // 每 tick 问一次而不是在界面切换事件里换掉主菜单：探测在后台线程上跑，主菜单出现的那一刻
        // 结论往往还没出来，换 GUI 只有一次机会，轮询则下一 tick 再问一次就行。内部自带
        // 「本次启动只弹一次」与「永不展示」两道闸，条件不满足时只是一次布尔判断。
        GuiFfmpegNotice.tickAutoShow(mc);

        // ---- 有新版本的一次性主菜单提示 ----
        // 与上面 ffmpeg 那条同构，闸门也一样在内部：本次启动只弹一次 / 先不更新有次数上限。
        // ffmpeg 提示已经占住界面时，这里读到的当前界面不是主菜单，本 tick 直接跳过 —— 两个提示天然排队。
        GuiUpdateNotice.tickAutoShow(mc);

        // ---- 有未读公告时的一次性主菜单提示 ----
        // 排在更新提示后面调：前两个提示任一在屏幕上时，这里读到的当前界面不是主菜单，本 tick 跳过 ——
        // 三个提示天然排队。公告自己只管「这批看过没有」，不参与更新提示那套次数额度。
        GuiAnnouncements.tickAutoShow(mc);

        // ---- 系统播放器镜像（SMTC）----
        // 主线程每 tick 问一次：换歌检测、解析结果落地、命令回执播报都在里面。真正的耗时
        // 部分（起进程、联网搜曲库）都在这个调用返回之后由后台线程做，这里只是一次内存读
        // 加几个布尔判断。放在按键检测之前，是因为按键那一段要求 mc.player != null，而镜像
        // 在主菜单里也该继续跟随外部播放器（岛的常驻页在那儿也画）。
        ExternalPlayback.onClientTick();

        // ---- ModernUI 控制中心的页签注册 ----
        // 每 tick 问一次、闸门在桥里（没装 / 本树没面板 / 已挂过 都是一次布尔比较）：
        // ModernUI 的初始化时机不由我们掌握，注册晚了就赶不上第一次打开控制中心。
        ModernUiHudBridge.registerActionCenterTab();

        // ---- 可配置按键检测（仅在未打开 GUI 时响应，避免与界面内输入冲突）----
        // 1.8.9 的 KeyMapping.isPressed() 是上升沿触发；现在同语义的方法叫 consumeClick()
        // （isDown() 表示「当前按住」，用它会导致按住时每 tick 重复触发）。
        if (keyOpenMusic == null) {
            return;
        }
        if (mc.player != null && mc.screen == null) {
            if (keyOpenMusic.consumeClick()) {
                MuoniumPlayerExtension.getInstance().musicPlayerModule.setEnabled(true);
            }
            if (keyEditHud.consumeClick()) {
                openHudEditor(mc);
            }

            // 复用与上一曲/下一曲按钮完全相同的播放器控制路径。
            // consumeClick() 是边沿触发，因此长按某个键绝不会排队重复切歌。
            // 门禁问 hasPlaybackClock() 而不是 player != null：镜像态下出声的是外部播放器，
            // 我们的音频引擎没参与，但这两个键的意义（让「正在播的那首」换一首）完全成立。
            if (keyPreviousTrack.consumeClick()
                    && CloudMusic.hasPlaybackClock()
                    && CloudMusic.currentlyPlaying != null) {
                CloudMusic.prev();
            }
            if (keyNextTrack.consumeClick()
                    && CloudMusic.hasPlaybackClock()
                    && CloudMusic.currentlyPlaying != null) {
                CloudMusic.next();
            }

            // 两个播放器滑块共用同一条持久化音频路径，因此
            // 快捷键的改动能跨曲目保留，并报出准确的百分比。
            if (keyVolumeUp.consumeClick()) {
                CloudMusic.adjustVolume(.05f);
            }
            if (keyVolumeDown.consumeClick()) {
                CloudMusic.adjustVolume(-.05f);
            }

            // 暂停 / 继续与播放器界面的空格键共用同一条 CloudMusic.togglePlayPause()，反馈也在那里统一给出。
            if (keyTogglePause.consumeClick()) {
                MusicHotkeys.togglePlayback();
            }
        } else {
            // 打开 GUI 时把这一 tick 积累的边沿消费掉，避免关掉界面的瞬间一次性全部触发。
            // 1.8.9 靠 Forge 的按键处理天然不会积累；现在 consumeClick 的计数会留到下次读取。
            keyOpenMusic.consumeClick();
            keyEditHud.consumeClick();
            keyPreviousTrack.consumeClick();
            keyNextTrack.consumeClick();
            keyVolumeUp.consumeClick();
            keyVolumeDown.consumeClick();
            keyTogglePause.consumeClick();
        }
    }

    /**
     * 渲染后端移植未完成期间的兜底闸门。
     *
     * <p>HUD / Screen 回调里抛出的异常会直接让游戏崩溃，因此这里加闸：
     * 首次失败记一条明确日志并永久关闭该条渲染路径，保证「mod 装上后游戏还能正常玩」。
     * 渲染重写完成后这两个 boolean 会一直是 false，闸门不产生任何开销。
     */
    private static boolean hudRenderDisabled;
    private static boolean screenRenderDisabled;

    private static void reportRenderFailure(String where, Throwable t) {
        System.err.println("[MuoniumPlayer] " + where + " 渲染已关闭：" + t);
        t.printStackTrace();
    }

    /**
     * 把一段界面绘制包在一个 Skia 帧里。
     *
     * <p>{@code beginFrame} 返回 null 表示这一帧不可用（还没初始化 / 已停用 / 窗口尺寸异常），
     * 这时整段跳过而不是画一半——半帧的画面比不画更难排查。
     *
     * <p>{@code endFrame} 放在 finally 里：它同时负责「把离屏内容合成回游戏画面」和
     * 「把 GL 状态交还给 Minecraft」。漏掉后者会让 Minecraft 随后的绘制全部错乱，
     * 所以哪怕界面绘制抛了异常也必须执行。
     */
    private void inSkijaFrame(Runnable draw) {
        if (SkijaHost.beginFrame() == null) {
            return;
        }
        try {
            draw.run();
        } finally {
            SkijaHost.endFrame();
        }
    }

    /**
     * HUD 渲染（对应 1.8.9 的 {@code RenderGameOverlayEvent.Post} + {@code ElementType.TEXT}）。
     *
     * <p>两个加载器都在「原版 HUD 全部画完之后」调用本方法：Fabric 是
     * {@code HudRenderCallback}，NeoForge 是 {@code RenderGuiEvent.Post}。
     * 打开 GuiScreen 时 HUD 不渲染，界面之上的灵动岛由 {@link #onScreenRendered(Screen)} 负责，
     * 因此同一帧只更新一次动画。
     */
    public void onHudRender() {
        if (hudRenderDisabled || this.api == null) {
            return;
        }
        try {
            inSkijaFrame(this::onHudRenderUnguarded);
        } catch (Throwable t) {
            hudRenderDisabled = true;
            reportRenderFailure("HUD", t);
        }
    }

    /**
     * 驱动所有 HUD 绘制。
     *
     * <p>界面全部画在 Skia 自己的离屏目标上，与 Minecraft 的 GL 状态只在
     * {@code SkijaHost.endFrame()} 那一刻交接一次，状态泄漏窗口不存在。
     *
     * <p>仍然压一层变换与绘制状态：业务代码里存在 push/pop 不配对的写法，
     * 压一层能把影响限制在本帧（宿主层每帧还会再兜一次底）。
     */
    private void onHudRenderUnguarded() {
        api.getGLStateManager().pushAttrib();
        api.getGLStateManager().pushMatrix();
        try {
            EventRender2D renderEvent = new EventRender2D(new WindowResolutionImpl());

            // 驱动所有注册的 EventHandler 的 2D 渲染
            for (EventHandler handler : this.api.getEventHandlers()) {
                handler.onRender2D(renderEvent);
            }

            // 驱动所有模块自身 eventHandler 的 2D 渲染
            for (ExtensionModule module : this.api.getModules()) {
                EventHandler moduleHandler = module.getEventHandler();
                if (moduleHandler != null) {
                    moduleHandler.onRender2D(renderEvent);
                }
            }

            // 驱动所有满足渲染条件的 Widget（歌曲信息卡片 / 歌词等）
            for (ExtensionWidget widget : this.api.getWidgets()) {
                if (widget.renderPredicate()) {
                    widget.render();
                }
            }
        } finally {
            // 收尾本身也可能抛异常；不能让它盖住上面真正的根因。
            try {
                api.getGLStateManager().popMatrix();
            } catch (Throwable ignored) {
            }
            try {
                api.getGLStateManager().popAttrib();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按用户选择打开 HUD 设置；外部界面（ModernUI / OneConfig）不可用时自动回退到内置界面。 */
    private void openHudEditor(Minecraft mc) {
        if (HudConfig.HUD_EDITOR_UI_MODERNUI.equals(HudConfig.hudEditorUi)
                && ModernUiHudBridge.open()) {
            return;
        }
        if (HudConfig.HUD_EDITOR_UI_ONECONFIG.equals(HudConfig.hudEditorUi)
                && OneConfigHudBridge.open()) {
            return;
        }
        if (HudConfig.HUD_EDITOR_STYLE_CLASSIC.equals(HudConfig.hudEditorStyle)) {
            mc.setScreen(new GuiHudEditor());
        } else if (HudConfig.HUD_EDITOR_STYLE_FOLIA.equals(HudConfig.hudEditorStyle)) {
            mc.setScreen(new GuiHudEditorFolia());
        } else {
            mc.setScreen(new GuiHudEditorPro());
        }
    }

    /**
     * Screen 顶层覆盖（对应 1.8.9 的 {@code GuiScreenEvent.DrawScreenEvent.Post}）：
     * 灵动岛不再由 NCMScreen 调用，因此玩家打开播放器、背包或其他界面时
     * 都使用同一套全局坐标和尺寸。HUD 编辑器已经绘制专用实时预览，避免重复叠加。
     */
    public void onScreenRendered(Screen screen) {
        if (screenRenderDisabled || this.api == null) {
            return;
        }
        try {
            inSkijaFrame(() -> onScreenRenderedUnguarded(screen));
        } catch (Throwable t) {
            screenRenderDisabled = true;
            reportRenderFailure("GuiScreen 覆盖层", t);
        }
    }

    private void onScreenRenderedUnguarded(Screen screen) {
        RenderSystem.refreshResolution();
        Interpolations.calcFrameDelta();

        // 两套 HUD 设置界面都自己画灵动岛的专用预览，这里再画一次会叠成双层。
        if (DownloadDynamicIsland.isDownloadActive()
                || (!(screen instanceof GuiHudEditor) && !(screen instanceof GuiHudEditorPro))) {
            DownloadDynamicIsland.render();
        }
        AutomixTransitionAnimation.render();
    }
}
