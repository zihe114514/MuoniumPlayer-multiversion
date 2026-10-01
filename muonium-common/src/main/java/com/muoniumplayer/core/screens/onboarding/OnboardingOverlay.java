package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.SkijaEffects;

/**
 * 压在引导页之上的那层招呼字（hello / welcome）：背景糊一档、中央一笔一笔描出彩虹、下面两行小字。
 *
 * <h2>为什么是覆盖层而不是单独一页</h2>
 *
 * <p>原来它是一整屏（{@code OnboardingIntroScreen}），描完再翻进向导——于是玩家要在四秒后
 * 看着一屏换成另一屏，面板跳一次位。现在引导页自己就是播放器的骨架（{@link OnboardingChrome}），
 * 招呼字只是压在它上面的一层：收掉的那一下，露出来的就是刚才那副骨架，一个像素都不挪。</p>
 *
 * <p>能这么叠是因为要糊的东西<b>本来就在 Skia 自己这一层里</b>：游戏画面由
 * {@link OnboardingBackdrop} 抓帧成一张图先铺好，引导骨架再画在它上面，
 * 所以 {@link SkijaEffects#backdropBlur} 一次就能把「画面 + 骨架」一起糊掉。
 * 收层时把同一个模糊的 alpha 从 1 拉到 0，就是淡出，不需要第二张离屏。</p>
 *
 * <h2>退出方式只有两条，而且都得先等满门禁</h2>
 *
 * <ul>
 *   <li><b>ESC</b> = 连引导一起不要（{@link OnboardingManager#skipAll()}）；</li>
 *   <li><b>点任意处 / 回车 / 空格</b> = 现在就去看引导，这一次点击<b>被吃掉</b>，
 *       不会穿到下面的控件上。</li>
 * </ul>
 *
 * <p>两条路共用 {@link #SKIP_GATE_SECONDS} 这一道闸：招呼字没放够这么久之前，
 * 四条输入一律什么都不发生（那一记输入仍然被吞掉，不会穿到下面的控件上）。
 * 没有这道闸，「打招呼」就还是一段可以被一秒闪过去的过场——玩家随手一点，笔画刚起了个头。
 * 门禁读的是<b>动画时间轴</b>（{@link OnboardingGreeting#elapsedSeconds()}）而不是墙钟，
 * 理由和描字同源：首屏那几个大帧按墙钟足够把 5 秒等满，按动画连一枚字都还没画完。</p>
 *
 * <p>描完之后它<b>就一直待在那儿</b>，不再自己走，而且这些字是<b>来回</b>放的：一枚正描一遍、
 * 停在满字两秒、倒着擦回空白，然后下一枚接着落笔（hello → welcome → hello…，见 {@link OnboardingGreeting}）。
 * 这一段是给玩家打招呼的，不是过场动画：笔画走完的那一帧才是它最好看的时候，
 * 紧接着 320ms 溶掉等于把人刚要看清的东西撤走。
 * 唯一不受门禁的情况是「压根没画出来」（素材缺失或渲染层没起来）——那不能把引导永久压住。</p>
 */
final class OnboardingOverlay {

    /**
     * 覆盖层那一下的模糊半径（逻辑像素）。
     *
     * <p>比抓帧图里已经烘掉的那层<b>轻</b>：抓帧那层负责把游戏变成氛围，这一层只负责把骨架
     * 也压进同一团氛围里，让玩家的眼睛只读那枚字。要「稍微模糊」而不是「读不出界面」。</p>
     */
    private static final float BLUR_SIGMA = 5.0f;

    /** 压暗量：笔画要立在背景上读得清，靠这层而不是把描边加粗。 */
    private static final int SCRIM_RGB = 0x0A0B0F;
    private static final int SCRIM_ALPHA = 132;

    /** 收层淡出的时长。比描字本身短一个量级——它是「让开」，不是另一段表演。 */
    private static final long FADE_NANOS = 320_000_000L;

    /**
     * 第一遍描到几成时那两行提示开始淡入。
     *
     * <p>绑在「第一遍」而不是「画面上现在有多少字」：字来回放的时候后者每轮掉回 0，
     * 提示行会跟着呼吸闪，眼睛被那行小字拽走。取 .72 是最后一笔快收尾、字已经认得出写的是什么。
     * 另一个约束由测试钉着：它必须<b>早于</b> {@link #SKIP_GATE_SECONDS}，否则玩家在整个等待期里
     * 一行字都看不见，门禁就成了一段没有解释的「点了没反应」。</p>
     */
    static final double HINT_FROM = .72;

    /** 门禁放开之后第二行小字换回的那句。 */
    private static final String HINT_READY = "点任意处 / 回车继续 · 按 ESC 跳过整段引导";

    /**
     * skip 门禁：招呼字<b>按动画时间轴</b>放够这么多秒之前，四条输入（点任意处 / 回车 / 空格 / ESC）
     * 一律不让层收掉。
     *
     * <p>取 5 秒不是随便一个整数：hello 一枚字正描 3.45 秒、之后停在满字 2 秒（到 5.45 秒），
     * 所以门禁打开那一刻屏幕上正是<b>写满的第一枚字</b>——不早到只剩半个「h」，也不晚到已经开始
     * 往回擦。这两头由 {@code OnboardingGreetingStaticTest} 钉着。</p>
     */
    static final double SKIP_GATE_SECONDS = 5.0;

    /**
     * 「等满了」的容差（秒）。
     *
     * <p>时间轴是按帧累加出来的浮点数，走到 5 秒那一刻很可能停在 4.999999999999938 而不是 5.0。
     * 闸的判据和读数的判据<b>必须用同一个容差</b>，否则会差出一格真实可见的错：闸还差 6e-14 秒没开，
     * 而「还有 0.1 秒」那行字已经按到 0 消失了——玩家面对的就是「点了没反应，屏幕上也没话说」。
     * 一微秒远小于一帧（60fps 是 16 ms），放行的时机不受影响。</p>
     */
    static final double GATE_EPSILON_SECONDS = 1e-6;

    private final OnboardingGreeting greeting;
    private boolean retired;
    private long fadeStartedAt = -1L;
    private double fade;

    OnboardingOverlay() {
        this.greeting = new OnboardingGreeting();
    }

    /** 还挂着没收：引导页要把鼠标事件全部吃掉，并且不给正文传 hover。 */
    boolean blocking() {
        return !retired && fadeStartedAt < 0L;
    }

    /** 已经完全收掉了（连淡出也走完）。引导页据此决定还要不要每帧问一次。 */
    boolean gone() {
        return retired;
    }

    /**
     * 推进一次时间轴。
     *
     * <p>淡出只有两个起点：玩家给了<b>过了门禁</b>的输入（{@link #dismiss()}），或者这层压根没
     * 画出来过（{@link OnboardingGreeting#stranded()}）。后者不受门禁管——它等的是「动画放够 5 秒」，
     * 而这里压根没有动画可放，等下去只会把引导一直压在一层模糊底下。描完字<b>不是</b>收层的理由。</p>
     *
     * @return 这一次调用<b>刚好</b>把覆盖层收干净，调用方据此落一次状态（记 introSeen）
     */
    boolean tick() {
        if (retired) {
            return false;
        }
        if (fadeStartedAt < 0L) {
            if (!greeting.stranded()) {
                return false;
            }
            fadeStartedAt = System.nanoTime();
        }
        fade = Math.min(1.0, (System.nanoTime() - fadeStartedAt) / (double) FADE_NANOS);
        if (fade < 1.0) {
            return false;
        }
        retired = true;
        return true;
    }

    /**
     * 玩家给了输入（点任意处 / 回车 / 空格，或者门禁开之后的 ESC）。
     *
     * @return 真的开始收层才 true。还在门禁里返回 false——这一记输入<b>照样被吃掉</b>，
     *         只是不让这层动，绝不代表「按了没反应，界面卡死了」
     */
    boolean dismiss() {
        if (retired || fadeStartedAt >= 0L || !gateOpen()) {
            return false;
        }
        fadeStartedAt = System.nanoTime();
        fade = 0.0;
        return true;
    }

    /** 门禁开没开。开着才允许收层，也才把第二行小字换回「点任意处 / 回车继续…」。 */
    boolean gateOpen() {
        return gateRemainingSeconds(greeting.elapsedSeconds()) <= GATE_EPSILON_SECONDS;
    }

    /** 还要放多少秒（动画时间轴，不是墙钟）才让 skip。已经放开就是 0。 */
    double gateRemaining() {
        return gateRemainingSeconds(greeting.elapsedSeconds());
    }

    /** 纯函数：门禁还要不要等。写成静态是为了在游戏外面钉（见 {@code OnboardingGreetingStaticTest}）。 */
    static double gateRemainingSeconds(double elapsedSeconds) {
        return Math.max(0.0, SKIP_GATE_SECONDS - Math.max(0.0, elapsedSeconds));
    }

    /** 纯函数：这一份剩余秒数还算不算「在等」（{@link #GATE_EPSILON_SECONDS} 那笔账）。 */
    static boolean gateWaiting(double remainingSeconds) {
        return remainingSeconds > GATE_EPSILON_SECONDS;
    }

    /**
     * 纯函数：等待期间第二行小字说什么；放开之后返回 {@code null}，调用方换回正常那句。
     *
     * <p>两位都不许将就：① <b>向上取整到十分之一秒</b>，于是这句永远不会显示「还有 0.0 秒」
     * ——那一帧门禁已经开了，写出来就是「还要等 0 秒」这种自相矛盾的话；判据还得和
     * {@link #gateWaiting(double)} 用同一个容差，不然会出现「闸没开、读数也没了」那一格；
     * ② 自己拼整数与小数，不走 {@code String.format} 也不碰默认 {@code Locale}——某些语言下
     * {@code %.1f} 会打成「还有 2,4 秒」，一位小数点变成逗号没人会想起来查。</p>
     */
    static String gateHintText(double remainingSeconds) {
        if (!gateWaiting(remainingSeconds)) {
            return null;
        }
        // 减一个远小于十分之一秒的容差再取整：0.3 这一档在二进制里是 0.299999999999999987，
        // 直接 ceil(× 10) 会因为最后那一位多出来的 4 而跳到 0.4，读数凭空长出一格。
        long tenths = (long) Math.ceil(remainingSeconds * 10.0 - 1e-6);
        return "还有 " + (tenths / 10) + "." + (tenths % 10) + " 秒可跳过";
    }

    /**
     * 画这一帧的覆盖层。
     *
     * @param screenAlpha 引导页自己的淡入，覆盖层跟着它起，免得第一帧就是一片死黑
     */
    void draw(double width, double height, float screenAlpha) {
        if (retired || screenAlpha <= .01f) {
            return;
        }
        float presence = (float) ((1.0 - fade) * screenAlpha);
        if (presence <= .004f) {
            return;
        }
        // 先糊再压暗：模糊拿到的必须是「已经画好的画面 + 骨架」，压暗必须在糊之后，
        // 否则暗角会被一起糊开，中间那块反而比边上亮。
        SkijaEffects.backdropBlur(0, 0, width, height, BLUR_SIGMA, presence);
        Rect.draw(0, 0, width, height, RGBA.color(SCRIM_RGB, (int) (SCRIM_ALPHA * presence)));

        double centerX = width * .5;
        double centerY = height * .42;
        greeting.render(centerX, centerY,
                Math.min(width * .74, 660), Math.min(height * .30, 170), presence);

        // 两行小字跟着笔画走完再淡入：一上来就把「按 ESC 跳过」摆出来，
        // 眼睛会先去读那行小字，描字那一段就白演了。
        double reveal = greeting.firstPassProgress();
        float textAlpha = (float) (Math.max(0.0, Math.min(1.0,
                (reveal - HINT_FROM) / (1.0 - HINT_FROM))) * presence);
        if (textAlpha <= .01f) {
            return;
        }
        drawCentered(FontManager.pf14, "Welcome to MuoniumPlayer, " + playerName(),
                centerX, centerY + Math.min(height * .30, 170) * .5 + 12,
                OnboardingUi.colorWithAlpha(OnboardingUi.WHITE, (int) (235 * textAlpha)));
        // 门禁期间这一行说的是「还要等多久才让跳」，放开之后才换成那三条输入。
        // 不空着一行：让玩家知道刚才那几下没白按，只是这层还不想走。
        String waiting = gateHintText(gateRemaining());
        drawCentered(FontManager.pf12, waiting == null ? HINT_READY : waiting,
                centerX, centerY + Math.min(height * .30, 170) * .5 + 34,
                OnboardingUi.colorWithAlpha(OnboardingUi.DIM, (int) (215 * textAlpha)));
    }

    private String playerName() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            String name = client == null ? null : client.getUser().getName();
            return name == null || name.isEmpty() ? "玩家" : name;
        } catch (Throwable unavailable) {
            // 取不到名字不该让一行欢迎文字把整层带崩。
            return "玩家";
        }
    }

    private static void drawCentered(CFontRenderer font, String text, double centerX,
                                     double y, int color) {
        if (font == null || text == null) {
            return;
        }
        OnboardingUi.drawText(font, text, centerX - font.getStringWidthD(text) * .5, y, color);
    }

    void close() {
        greeting.close();
    }
}
