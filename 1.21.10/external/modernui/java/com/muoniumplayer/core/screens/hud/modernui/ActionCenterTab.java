package com.muoniumplayer.core.screens.hud.modernui;

import icyllis.modernui.ModernUI;
import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.core.Core;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.fragment.FragmentContainerView;
import icyllis.modernui.fragment.FragmentTransaction;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.ImageDrawable;
import icyllis.modernui.graphics.drawable.RippleDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.MuiScreen;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.resources.Resources;
import icyllis.modernui.resources.TypedValue;
import icyllis.modernui.util.ColorStateList;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.util.DisplayMetrics;
import icyllis.modernui.util.StateSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.RadioButton;
import icyllis.modernui.widget.RadioGroup;

import net.minecraft.client.gui.screens.Screen;

import java.util.HashSet;
import java.util.Set;

/**
 * 把 MuoniumPlayer 的 HUD 面板挂成 ModernUI 控制中心的一个页签。
 *
 * <p>ModernUI 没有给第三方 mod 留注册口：控制中心就是 {@code CenterFragment2}，左侧那一列
 * 是它在 {@code onCreateView} 里 {@code new RadioGroup(...)} 手搓的，每一点都硬编码
 * {@code replace(id_tab_container, 某页.class, ...)}；{@code UIManager.open} 又是 protected。
 * 所以这里只能走运行时注入 —— 明确写清代价：MUI 改版（页签换成翻页控件、容器不再是
 * {@code FragmentContainerView}、根布局多包一层）就会让这个页签静默不出现。</p>
 *
 * <p>三条让注入尽量不打搅主人的约束：</p>
 * <ol>
 *   <li><b>探不到就整块放弃，不去猜。</b>结构对不上直接返回，不改它的任何现有节点。</li>
 *   <li><b>不接管它的页签监听。</b>页签切换那个 {@code OnCheckedChangeListener} 仍是它的，
 *       我们只在自己的按钮上挂点击：先 {@code check(我们的 id)}（它的 switch 对陌生 id 走空分支，
 *       不会误切别人的页），再用自己的 {@code replace} 把面板放进同一个容器。</li>
 *   <li><b>只往页签列末尾追加一个节点。</b>幂等：同一个 id 已存在就不再挂。</li>
 * </ol>
 *
 * <h2>两个真机上踩出来的坑（都写进了代码，别改回「看起来更简洁」的版本）</h2>
 *
 * <p><b>其一：{@code Core.postOnUiThread} 不是「下一帧」。</b>第一版按「屏幕切换回调早于建树，
 * 逐帧重试就行」实现，用 {@code postOnUiThread} 自我续期重试 60 次。真机日志里这 60 次全落在
 * 同一毫秒 —— 它只是把 runnable 插到 UI 线程自己那条队列的队尾，而控制中心的 view 是这条队列
 * 里更靠后的任务建的，于是每次都拿到 null 然后放弃，页签永远不出现。现在按
 * {@link #RETRY_MILLIS} 真隔 50ms 再问一次。</p>
 *
 * <p><b>其二：不要只认「fragment 的 view 的第一层子节点」。</b>{@code Fragment.getView()} 在
 * 建树之前是空的，而它的 {@code onCreateView} 返回的布局外面还可能再包一层。所以找页签列改成
 * 有界的深度搜索，判据用「这一列里有 id 落在它自己那一段（1001..1010）里的按钮」而不是类型；
 * 找不到时再从 {@code UIManager.getDecorView()} 整棵树里找一遍。容器 id 仍然只认
 * 「与页签列同级的 {@code FragmentContainerView}」—— 宿主自己的容器也在同一棵树上，
 * 认错会把面板塞进别人的地盘。</p>
 *
 * <p>「静默不出现」对玩家是对的，对排查是灾难 —— 失败时日志里一行都查不到。所以这条链上每一环
 * 都留一行 {@link #dlog}，且只在控制中心真的被打开时才会输出。</p>
 */
final class ActionCenterTab {

    private static final String TAG = "[MuoniumPlayer/MuiCenter] ";

    /** 控制中心的类名。按名字认，是为了它改名/挪包时我们只是不出现，而不是抛异常。 */
    private static final String CENTER_CLASS = "icyllis.modernui.mc.ui.CenterFragment2";

    /** 我们的页签 id。它自己用 1001..1006，这里避开整个区段。 */
    private static final int TAB_ID = 0x4D75;

    /**
     * 页签文字。它那一列宽只有 88dp（{@code createNavButton} 里写死的），
     * 所以「MuoniumPlayer」会折成两行、把这一列的高度顶高一档 —— 用品牌名 Muonium。
     */
    private static final String TAB_LABEL = "Muonium";

    /**
     * 图标图集 {@code modernui:gui/new_icon.png} 里我们占的那一格：512x512、每格 72px、7x7。
     * 它自己用掉的是 (2,0) 主页、(0,0) 偏好、(1,1) 音乐、(0,6) 高级选项与 Markdown，
     * (0,5) 那格是「推子/滑块」，正好是调参页的语义，而且没人占。
     */
    private static final int ICON_ROW = 0;
    private static final int ICON_COL = 5;

    /** 它自己那一段页签 id，用来认出「哪一列才是页签列」。 */
    private static final int THEIR_TAB_ID_FIRST = 1001;
    private static final int THEIR_TAB_ID_LAST = 1010;

    /** 建树等待上限：配合 {@link #RETRY_MILLIS} 约 3 秒。 */
    private static final int MAX_ATTEMPTS = 60;

    /** 两次探测之间的间隔（毫秒）。见类注释「其一」。 */
    private static final long RETRY_MILLIS = 50L;

    /** 深度搜索的上限：控制中心只有「根 → 页签列 / 容器」两层，留到 8 层足够宽容又不误伤。 */
    private static final int MAX_DEPTH = 8;

    private static boolean hooked;

    /** 每种界面/fragment 类只报一次，免得每次开关背包都刷一行。 */
    private static final Set<String> SEEN_SCREENS = new HashSet<>();
    private static final Set<String> SEEN_FRAGMENTS = new HashSet<>();

    private ActionCenterTab() {
    }

    /**
     * 诊断输出。刻意走 {@code System.out} 而不是 MUI 的 logger：本项目别处的现场日志
     * （SkijaHost、音频侧）都是这个形状，日志里能一致地按前缀 grep 到。
     */
    static void dlog(String message) {
        System.out.println(TAG + message);
    }

    /** 装上监听就返回 true；ModernUI 不在或版本对不上时返回 false，由调用方什么也不做。 */
    static boolean register() {
        if (hooked) return true;
        try {
            MuiModApi.addOnScreenChangeListener(new MuiModApi.OnScreenChangeListener() {
                @Override
                public void onScreenChange(Screen oldScreen, Screen newScreen) {
                    noteScreen(newScreen);
                    if (!(newScreen instanceof MuiScreen)) return;
                    Fragment center = null;
                    try {
                        center = ((MuiScreen) newScreen).getFragment();
                    } catch (Throwable failure) {
                        dlog("getFragment() 抛了：" + failure);
                    }
                    if (center == null || !CENTER_CLASS.equals(center.getClass().getName())) return;
                    if (SEEN_FRAGMENTS.add(center.getClass().getName())) {
                        dlog("控制中心打开了：" + center.getClass().getName());
                    }
                    poll(center, 0);
                }
            });
            hooked = true;
            dlog("已注册 MuiModApi 屏幕切换监听");
            return true;
        } catch (Throwable failure) {
            dlog("注册屏幕切换监听失败：" + failure);
            return false;
        }
    }

    private static void noteScreen(Screen newScreen) {
        String name = newScreen == null ? "(无界面)" : newScreen.getClass().getName();
        if (SEEN_SCREENS.add(name)) {
            dlog("界面切换 -> " + name + (newScreen instanceof MuiScreen ? " (MuiScreen)" : ""));
        }
    }

    /** 隔 {@link #RETRY_MILLIS} 再问一次，直到它的 view 树建起来或者次数用完。 */
    private static void poll(final Fragment center, final int attempt) {
        try {
            Core.getUiHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryInject(center, attempt);
                }
            }, RETRY_MILLIS);
        } catch (Throwable failure) {
            dlog("投递 UI 线程任务失败：" + failure);
        }
    }

    private static void tryInject(Fragment center, int attempt) {
        if (!center.isAdded()) {
            dlog("控制中心已经关掉了（第 " + attempt + " 次探测），放弃这次注入");
            return;
        }
        View fragmentView = null;
        try {
            fragmentView = center.getView();
        } catch (Throwable failure) {
            dlog("getView() 抛了：" + failure);
        }

        RadioGroup nav = searchNav(fragmentView instanceof ViewGroup
                ? (ViewGroup) fragmentView : null);
        String where = "fragment 的 view";
        if (nav == null) {
            ViewGroup decor = decorView();
            if (decor != null && decor != fragmentView) {
                nav = searchNav(decor);
                where = "UIManager 的 decor view";
            }
        }
        if (nav == null) {
            if (attempt < MAX_ATTEMPTS) {
                poll(center, attempt + 1);
                return;
            }
            dlog("等了 " + (MAX_ATTEMPTS * RETRY_MILLIS) + "ms 还没找到页签列。"
                    + "fragment.view = " + describe(fragmentView));
            dlog("整棵树：" + dump(fragmentView != null ? fragmentView : decorView(), 0));
            return;
        }
        if (attempt > 0) {
            dlog("第 " + attempt + " 次探测拿到 view（页签列在" + where + "里）");
        }
        inject(center, nav);
    }

    private static ViewGroup decorView() {
        try {
            return UIManager.getInstance().getDecorView();
        } catch (Throwable failure) {
            dlog("UIManager.getDecorView() 失败：" + failure);
            return null;
        }
    }

    /**
     * 深度搜索页签列。判据不是「它是 RadioGroup」而是「它里面坐着 id 在 1001..1010 的按钮」——
     * 控制中心的内容页里也可能有别的 RadioGroup，那种没有它自己那一段 id 的不算。
     */
    private static RadioGroup searchNav(ViewGroup node) {
        if (node == null) return null;
        if (node instanceof RadioGroup && holdsTheirTabs((RadioGroup) node)) {
            return (RadioGroup) node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            View child = node.getChildAt(i);
            if (child instanceof ViewGroup) {
                RadioGroup found = searchNav((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean holdsTheirTabs(RadioGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            int id = group.getChildAt(i).getId();
            if (id >= THEIR_TAB_ID_FIRST && id <= THEIR_TAB_ID_LAST) return true;
        }
        return false;
    }

    /** 只认与页签列同级的那个 {@code FragmentContainerView}：它的 {@code id_tab_container} 就在那儿。 */
    private static int findContainer(RadioGroup nav) {
        if (!(nav.getParent() instanceof ViewGroup)) return -1;
        ViewGroup parent = (ViewGroup) nav.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof FragmentContainerView && child.getId() > 0) {
                return child.getId();
            }
        }
        return -1;
    }

    private static void inject(Fragment center, RadioGroup nav) {
        int containerId = findContainer(nav);
        if (containerId <= 0) {
            dlog("找到页签列了，但它旁边没有 FragmentContainerView（父节点 "
                    + nav.getParent() + "）：不注入，免得把面板塞进别人的容器");
            return;
        }
        if (nav.findViewById(TAB_ID) != null) return;

        final RadioGroup owner = nav;
        final Fragment host = center;
        final int container = containerId;
        RadioButton tab = createTab(nav);
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                owner.check(TAB_ID);
                try {
                    host.getChildFragmentManager().beginTransaction()
                            .replace(container, HudSettingsFragment.class, (DataSet) null, "muonium")
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                            .setReorderingAllowed(true)
                            .commit();
                    dlog("页签被点击，已提交 replace(HudSettingsFragment)");
                } catch (Throwable failure) {
                    // 它的 FragmentManager 状态不允许提交（例如正在保存状态）：这次点击就当没发生。
                    dlog("提交面板失败：" + failure);
                }
            }
        });
        // 布局参数在 createTab 里就按它的写法设好了（88dp 宽），这里跟它一样只 addView(view)
        owner.addView(tab);
        dlog("已追加页签（页签列现有 " + owner.getChildCount() + " 项，容器 id=0x"
                + Integer.toHexString(container) + "），稍后回报尺寸");
        reportSize(tab, owner, 0);
    }

    /**
     * 追加成功不等于看得见：页签列是 {@code gravity=CENTER} 的竖排，一旦总高超过容器高，
     * 上下两头会被裁掉，多出来的那一项正好消失在视野外。所以追完再看一眼实际尺寸。
     */
    private static void reportSize(final View tab, final ViewGroup nav, final int attempt) {
        try {
            Core.getUiHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (tab.getWidth() > 0 || attempt >= 20) {
                        dlog("页签尺寸 " + tab.getWidth() + "x" + tab.getHeight()
                                + " 测量 " + tab.getMeasuredWidth() + "x" + tab.getMeasuredHeight()
                                + " 可见性=" + tab.getVisibility()
                                + " / 页签列 " + nav.getWidth() + "x" + nav.getHeight()
                                + " 测量 " + nav.getMeasuredWidth() + "x" + nav.getMeasuredHeight()
                                + " 项数=" + nav.getChildCount());
                        return;
                    }
                    reportSize(tab, nav, attempt + 1);
                }
            }, RETRY_MILLIS);
        } catch (Throwable ignored) {
        }
    }

    /** 类名 + 是不是 ViewGroup + 有几个孩子，够定位「我们看到的根到底是什么」。 */
    private static String describe(View node) {
        if (node == null) return "null";
        return node.getClass().getName() + "(" +
                (node instanceof ViewGroup ? ((ViewGroup) node).getChildCount() + " 个子节点" : "叶子") + ")";
    }

    /** 放弃时把树倒出来看：真机上「为什么没出现」全靠这一行。 */
    private static String dump(View node, int depth) {
        StringBuilder sb = new StringBuilder();
        appendNode(sb, node, depth);
        return sb.toString();
    }

    private static void appendNode(StringBuilder sb, View node, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(node.getClass().getSimpleName())
                .append(" id=0x").append(Integer.toHexString(node.getId()));
        if (node instanceof RadioGroup) sb.append(" [RadioGroup]");
        if (node instanceof FragmentContainerView) sb.append(" [Container]");
        sb.append('\n');
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                appendNode(sb, group.getChildAt(i), depth + 1);
            }
        }
    }

    /**
     * 造一个与主人家完全同款的页签按钮。
     *
     * <p>「同款」是逐条照 {@code CenterFragment2.createNavButton} 抄的，不是看着像：
     * 四参构造（一参那个会带上系统默认的圆圈按钮 drawable，正是真机上那一列里唯一多出来的东西）、
     * 12sp 居中、上下 8dp、选中色走 {@code colorSecondary}、图标着色走
     * {@code colorOnSecondaryContainer}、圆角胶囊底走 {@code colorSecondaryContainer}、
     * 按下/悬停走 {@code RippleDrawable}，图标直接从它那张图集里取一格，密度同样按 xxhigh 设。
     * 主题色全部走 {@code resolveAttribute}，所以主人家换主题、换深浅色，我们这一格跟着一起变。</p>
     */
    private static RadioButton createTab(RadioGroup nav) {
        Context context = nav.getContext();
        Resources.Theme theme = context.getTheme();
        TypedValue value = new TypedValue();

        ColorStateList itemTextColor;
        ColorStateList itemIconTint;
        ColorStateList itemRippleColor;
        ColorStateList activeIndicatorColor;
        {
            int[] colors = new int[2];
            theme.resolveAttribute(R.ns, R.attr.colorSecondary, value, true);
            colors[0] = value.data; // 选中
            theme.resolveAttribute(R.ns, R.attr.colorOnSurfaceVariant, value, true);
            colors[1] = value.data;
            itemTextColor = new ColorStateList(
                    new int[][] {new int[] {R.attr.state_checked}, StateSet.WILD_CARD}, colors);
        }
        int colorOnSecondaryContainer;
        {
            int[] colors = new int[2];
            theme.resolveAttribute(R.ns, R.attr.colorOnSecondaryContainer, value, true);
            colors[0] = colorOnSecondaryContainer = value.data;
            theme.resolveAttribute(R.ns, R.attr.colorOnSurfaceVariant, value, true);
            colors[1] = value.data;
            itemIconTint = new ColorStateList(
                    new int[][] {new int[] {R.attr.state_checked}, StateSet.WILD_CARD}, colors);
        }
        itemRippleColor = new ColorStateList(
                new int[][] {
                        new int[] {R.attr.state_pressed},
                        new int[] {R.attr.state_focused},
                        new int[] {R.attr.state_hovered},
                        StateSet.WILD_CARD
                },
                new int[] {
                        ColorStateList.modulateColor(colorOnSecondaryContainer, 0.1f),
                        ColorStateList.modulateColor(colorOnSecondaryContainer, 0.1f),
                        ColorStateList.modulateColor(colorOnSecondaryContainer, 0.08f),
                        ColorStateList.modulateColor(colorOnSecondaryContainer, 0.08f)
                });
        {
            int[] colors = new int[2];
            theme.resolveAttribute(R.ns, R.attr.colorSecondaryContainer, value, true);
            colors[0] = value.data;
            activeIndicatorColor = new ColorStateList(
                    new int[][] {new int[] {R.attr.state_checked}, StateSet.WILD_CARD}, colors);
        }

        RadioButton button = new RadioButton(context, null, null, null);
        button.setFocusable(true);
        button.setClickable(true);
        button.setId(TAB_ID);
        button.setText(TAB_LABEL);
        button.setTextSize(12f);
        button.setTextColor(itemTextColor);
        button.setGravity(Gravity.CENTER);
        final int dp8 = button.dp(8);
        button.setPadding(0, dp8, 0, dp8);

        ShapeDrawable indicator = new ShapeDrawable();
        indicator.setSize(button.dp(56), button.dp(32));
        indicator.setColor(activeIndicatorColor);
        indicator.setCornerRadius(1000);
        RippleDrawable ripple = new RippleDrawable(itemRippleColor, indicator, null);
        Image icons = Image.create(ModernUI.ID, "gui/new_icon.png");
        if (icons != null) {
            // 那张图集是按 xxhigh 画的，不声明密度会小一圈
            icons.setDensity(DisplayMetrics.DENSITY_DEFAULT * 3);
            ImageDrawable icon = new ImageDrawable(context.getResources(), icons);
            icon.setSrcRect(ICON_COL * 72, ICON_ROW * 72, (ICON_COL + 1) * 72, (ICON_ROW + 1) * 72);
            icon.setTintList(itemIconTint);
            icon.setGravity(Gravity.CENTER);
            ripple.addLayer(icon);
        } else {
            dlog("拿不到 modernui:gui/new_icon.png，这一格只有文字没有图标");
        }
        button.setCompoundDrawablesWithIntrinsicBounds(null, ripple, null, null);
        button.setCompoundDrawablePadding(button.dp(4));
        button.setLayoutParams(new LinearLayout.LayoutParams(button.dp(88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }
}
