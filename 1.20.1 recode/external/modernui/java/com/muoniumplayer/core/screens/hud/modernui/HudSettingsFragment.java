package com.muoniumplayer.core.screens.hud.modernui;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import icyllis.modernui.R;
import icyllis.modernui.animation.MotionEasingUtils;
import icyllis.modernui.animation.ObjectAnimator;
import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.resources.Resources;
import icyllis.modernui.resources.TypedValue;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.OneShotPreDrawListener;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.PagerAdapter;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.Switch;
import icyllis.modernui.widget.TabLayout;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.ViewPager;

import java.awt.Color;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MuoniumPlayer 的 HUD 设置在 ModernUI 控制中心里的条目集合。
 *
 * <p>这一页的骨架、组件、动画全部照 {@code PreferencesFragment}（控制中心「偏好设置」那一页）
 * 抄，不是「看着像」：外层是 {@code FrameLayout} + {@code ViewPager} + {@code TabLayout}
 * （顶栏底色走 {@code colorSurfaceContainerLow}、内容靠 {@code topMargin=48dp} 让出顶栏、
 * 入场是整块翻页容器绕 Y 轴 45°→0 配 {@code MOTION_EASING_EMPHASIZED} 曲线），每一页是一个
 * {@code ClampingScrollView}（本树按 3.12 那一代叫 {@code setMaxWidth(800dp + 2*20dp)}，
 * 上游 3.13 改名为 {@code setChildMaxWidth}）套一个左右 20dp 内边距的
 * 竖排，卡片用 {@code createCategoryList}，开关用 {@code BooleanOption}，滑块用
 * {@code FloatOption} 的子类，取值用 {@code DropDownOption}。所以字体、卡片描边、圆角、间距、
 * 内容宽度、悬停提示都跟主人家一起变，包括他换主题、换深浅色的时候。</p>
 *
 * <p>条目本身仍然一条一条来自 {@link HudSettingsModel}，读写仍然落在 {@code HudConfig} 那套
 * 静态字段上（{@code config/muonium/hud.json}）。面板不持有第二份配置，也不新增字段：</p>
 * <ul>
 *   <li>{@code SLIDER → StepOption}（{@code FloatOption} 的子类），取值问题见那里。</li>
 *   <li>{@code TOGGLE → BooleanOption}。setter 里先比对再 {@code activate()}，因为模型那边的动作是
 *       「翻转」而不是「赋值」，少这一步会出现拨回来了值却没变的情况。</li>
 *   <li>{@code CHOICE → DropDownOption}，仅当当前读数能在候选里找到时；找不到就退回点击循环，
 *       不给它一个会抛异常的 {@code setSelection(-1)}。</li>
 *   <li>{@code COLOR → createInputBox} 那一行的 HEX 输入，右侧挂一小块色板。没有搬内置界面的
 *       HSV 取色器：它要「按住并在画布上连续拖」的鼠标语义，而画布这时归 ModernUI 的视图体系管。</li>
 *   <li>{@code ACTION / THEME_GRID} 控制中心没有对应组件，于是借 {@code createSwitchLayout} 的
 *       外壳（同一套高度与边距），把里面那个 Switch 换成按钮。</li>
 * </ul>
 *
 * <p>落盘是一次性的：拖动过程中 MUI 会反复回调 setter，每回调一次就写一次 JSON 会把磁盘刷爆，
 * 所以这里只把改动记成脏标记，关屏时在 {@code onDestroyView()} 里存一次。</p>
 */
public final class HudSettingsFragment extends Fragment implements ScreenCallback {

    private float density = 1f;
    private boolean dirty;
    private HudPagerAdapter adapter;

    /**
     * 页的世代号。换主题预设会让一整页的可见条目跟着变，而 {@code getItemPosition} 只要按位置返回，
     * 翻页容器就认为「这些页还在原位」从而不重建 —— 把世代号编进 tag，换代后所有页都算错位，
     * 于是整页重建。
     */
    private int generation;

    // ==================== 界面骨架（照 PreferencesFragment） ====================

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet savedState) {
        Context context = requireContext();
        density = context.getResources().getDisplayMetrics().density;

        FrameLayout base = new FrameLayout(context);

        ViewPager pager = new ViewPager(context);
        adapter = new HudPagerAdapter();
        pager.setAdapter(adapter);
        pager.setFocusableInTouchMode(true);
        pager.setKeyboardNavigationCluster(true);
        // 它那一页的入场是整块翻页容器绕 Y 轴从 45° 转回 0°，配 emphasized 曲线。
        // 少这一段的话，从左边页签切过来时我们这块是「啪」地出现的，跟前面四页不一样。
        OneShotPreDrawListener.add(pager, new Runnable() {
            @Override
            public void run() {
                ObjectAnimator animator = ObjectAnimator.ofFloat(pager, View.ROTATION_Y,
                        pager.isLayoutRtl() ? -45 : 45, 0);
                animator.setInterpolator(MotionEasingUtils.MOTION_EASING_EMPHASIZED);
                animator.start();
            }
        });
        FrameLayout.LayoutParams pagerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pagerParams.gravity = Gravity.CENTER_HORIZONTAL;
        pagerParams.topMargin = dp(48);
        base.addView(pager, pagerParams);

        TabLayout tabs = new TabLayout(context);
        TypedValue value = new TypedValue();
        Resources.Theme theme = context.getTheme();
        if (theme.resolveAttribute(R.ns, R.attr.colorSurfaceContainerLow, value, true)) {
            // 第二处分歧：3.13 的 Resources 有 public loadDrawable(TypedValue, ResourceId, Theme)，
            // 3.12（本树跟的那一代）压根没有公开的取 drawable 入口，只有 ColorStateList 那套。
            // 这个属性本来就是个颜色，所以直接按 ActionCenterTab 解析主题色同一写法读 value.data，
            // 包一个 ColorDrawable —— 与 3.13 那边把同一个 TypedValue 交给 loadDrawable 的结果等价。
            tabs.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(value.data));
        }
        tabs.setElevation(dp(3));
        tabs.setTabMode(TabLayout.MODE_AUTO);
        tabs.setTabGravity(TabLayout.GRAVITY_CENTER);
        tabs.setupWithViewPager(pager);
        FrameLayout.LayoutParams tabsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        base.addView(tabs, tabsParams);

        return base;
    }

    @Override
    public void onDestroyView() {
        // 唯一一次落盘。拖动期间的写入都只在内存里，渲染层读的就是这些字段。
        try {
            if (dirty) {
                HudConfigSave.save();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroyView();
    }

    /** 换主题预设之后整页重建：可见条目和读数都跟着变。 */
    private void refreshPages() {
        generation++;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** 一个分类一页，页签文字就是分类名 —— 与它「屏幕/视图/字体/扩展/文本」那一排同构。 */
    private final class HudPagerAdapter extends PagerAdapter {

        @Override
        public int getCount() {
            return HudSettingsModel.categories().length;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            int dp20 = container.dp(20);
            int maxWidth = container.dp(800) + dp20 + dp20;
            Context context = container.getContext();

            ClampingScrollView scroll = new ClampingScrollView(context);
            // 本树编译期用的是上游 1.20.1-3.12.0.1，跟的是 ModernUI 3.12 那一代 API：
            // ClampingScrollView 只有 setMaxWidth(int)，3.13 才把它改名成 setChildMaxWidth(int)。
            // 这是本文件与 1.21.1 那份的两处差异之一（另一处是上面 TabLayout 背景那里）。
            // 别顺手「同步」回去 —— 那会让这棵树编不过；也别反过来改 1.21.1 那份。
            scroll.setMaxWidth(maxWidth);
            scroll.setTag(generation * 100 + position);

            LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp20, 0, dp20, 0);
            buildCategory(page, position);

            scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            container.addView(scroll);
            return scroll;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(Object object) {
            if (object instanceof View) {
                Object tag = ((View) object).getTag();
                if (tag instanceof Integer) {
                    int stamped = (Integer) tag;
                    return stamped / 100 == generation ? stamped % 100 : POSITION_NONE;
                }
            }
            return POSITION_NONE;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return HudSettingsModel.categories()[position].title;
        }
    }

    /** 建某一页：一组一张卡片，整组在这一页一条可用条目都没有就别留张空卡。 */
    private void buildCategory(LinearLayout page, int index) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        if (index < 0 || index >= categories.length) return;
        Context context = requireContext();
        for (HudSettingsModel.Group group : categories[index].groups) {
            // 注意 createCategoryList 只把「组名」那个标题挂到 page 上，返回的卡片本身是没上树的
            // —— 早先误读成它连卡片一起挂了，结果整页只剩一排组名、一行条目都没有。
            LinearLayout card = PreferencesFragment.createCategoryList(page, group.title);
            int added = 0;
            for (HudSettingsModel.Entry entry : group.entries) {
                if (!entry.isVisible()) continue;
                if (addOption(context, card, entry)) added++;
            }
            if (added > 0) {
                page.addView(card);
            } else {
                // 卡片不要了，顺手把刚挂上去的那个组名也撤掉，别留个光杆标题
                int last = page.getChildCount() - 1;
                if (last >= 0) {
                    View orphan = page.getChildAt(last);
                    if (orphan.getId() == R.id.title) {
                        page.removeView(orphan);
                    }
                }
            }
        }
    }

    /** 一条模型条目 → 一行控制中心组件。返回 false 表示这条没画出来。 */
    private boolean addOption(Context context, LinearLayout card, HudSettingsModel.Entry entry) {
        View row;
        switch (entry.kind) {
            case SLIDER:
                row = addSlider(context, card, entry);
                break;
            case TOGGLE:
                row = addToggle(context, card, entry);
                break;
            case CHOICE:
                row = addChoice(context, card, entry);
                break;
            case ACTION:
                row = addAction(context, card, entry);
                break;
            case COLOR:
                row = addColor(context, card, entry);
                break;
            case THEME_GRID:
                row = addThemeGrid(context, card);
                break;
            default:
                row = null;
                break;
        }
        if (row == null) return false;
        if (entry.description != null && !entry.description.trim().isEmpty()) {
            // 控制中心那条路是靠语言文件里的 <name>.tooltip 出悬停说明，我们的文案在模型里，
            // 所以直接把这行说明挂成组件自带的 tooltip，不另外占一行高度。
            //
            // 但挂在**标题**上，不挂在整行上：取值那一行的下拉是在控件正下方弹出来的，
            // 而 tooltip 跟着指针走 —— 挂整行的话指针停在下拉上（也就是展开列表的那只手）
            // 说明框就会压在候选项上，把要点的东西挡住。挂标题只有指在左边文字上才出说明。
            View host = titleOf(row);
            (host != null ? host : row).setTooltipText(entry.description);
        }
        return true;
    }

    /**
     * 行里那个标题 TextView —— MUI 的 {@code createSwitchLayout}/{@code createInputBox} 都把它放第一个。
     * 要排掉 {@code EditText}：它也是 TextView 的子类，挂到输入框上等于白改。
     */
    private static TextView titleOf(View row) {
        if (!(row instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && !(child instanceof EditText)) return (TextView) child;
        }
        return null;
    }

    // ==================== 各类条目 ====================

    /**
     * 滑块行。用 {@link StepOption} 而不是直接用 {@code FloatOption}，因为真机上这一行的读数
     * 出现过 {@code 0.8796992481203008}、{@code 2.111111111111111} 这种尾巴。
     *
     * <p>两个来源叠在一起。MUI 那一边：回读时算 {@code progress / denominator + min}，而分母
     * 早先是按跨度反推出来的任意数（133、9 这种），二进制除法带出一长串尾数；它格式化文本用的
     * {@code Math.round(v * d) / d} 在这种分母下等于没收口。我们这一边：{@code HudSetting} 存的是
     * float，{@code (double) 0.88f} 本身就是 {@code 0.8796992481203008}。</p>
     */
    private View addSlider(Context context, LinearLayout card, HudSettingsModel.Entry entry) {
        final com.muoniumplayer.core.settings.HudSetting setting = entry.setting;
        if (setting == null) return null;
        double min = clean(setting.getMin());
        double max = clean(setting.getMax());
        if (!(max > min)) return null;
        StepOption option = new StepOption(context, setting.getLabel(),
                new Supplier<Double>() {
                    @Override
                    public Double get() {
                        return clean(setting.getValue());
                    }
                },
                new Consumer<Double>() {
                    @Override
                    public void accept(Double v) {
                        setting.setValue(v.floatValue());
                        dirty = true;
                    }
                },
                min, max, pickStep(min, max));
        return option.create(card);
    }

    /**
     * float → double 必须走这一趟，不能直接隐式拓宽。
     *
     * <p>{@code HudSetting} 的 min/max/值全是 float，而 {@code (double) 0.6f} 是
     * {@code 0.6000000238418579} —— 之前那一列 {@code 0.88000000238418579}、
     * {@code 0.99999999928474426} 就是这么来的：档位算得再干净，起点是脏的，
     * {@link BigDecimal} 只会把这份脏如实加下去。{@code Float.toString} 给的是能唯一表示
     * 这个 float 的最短十进制，再 parse 回 double 就是干净的 0.6。</p>
     */
    private static double clean(float value) {
        return Double.parseDouble(Float.toString(value));
    }

    /** 开关行。模型那边的动作是「翻转」，所以先比对目标值，避免拨回来了值却没变。 */
    private View addToggle(Context context, LinearLayout card, final HudSettingsModel.Entry entry) {
        PreferencesFragment.BooleanOption option = new PreferencesFragment.BooleanOption(context,
                entry.getLabel(),
                new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return entry.isOn();
                    }
                },
                new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean checked) {
                        if (entry.isOn() != checked) {
                            entry.activate();
                            dirty = true;
                        }
                    }
                });
        return option.create(card);
    }

    /** 取值行：当前读数能在候选里找到才用下拉，否则退回点击循环。 */
    private View addChoice(Context context, LinearLayout card, final HudSettingsModel.Entry entry) {
        final String[] values = entry.getChoices();
        final String current = entry.getValueText();
        final int index = indexOf(values, current);
        if (values != null && values.length > 0 && index >= 0) {
            PreferencesFragment.DropDownOption<String> option =
                    new PreferencesFragment.DropDownOption<>(context, entry.getLabel(), values,
                            new java.util.function.ToIntFunction<String>() {
                                @Override
                                public int applyAsInt(String value) {
                                    int at = indexOf(values, value);
                                    return at < 0 ? 0 : at;
                                }
                            },
                            new Supplier<String>() {
                                @Override
                                public String get() {
                                    String text = entry.getValueText();
                                    return indexOf(values, text) < 0 ? values[0] : text;
                                }
                            },
                            new Consumer<String>() {
                                @Override
                                public void accept(String value) {
                                    entry.setChoice(value);
                                    dirty = true;
                                }
                            });
            return option.create(card);
        }
        return addCyclingButton(context, card, entry);
    }

    /** 动作行。控制中心没有这一类，所以借开关行的外壳，把里面的 Switch 换成按钮。 */
    private View addAction(Context context, LinearLayout card, final HudSettingsModel.Entry entry) {
        final Button button = new Button(context);
        button.setTextSize(11f);
        button.setText(entry.getLabel());
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entry.activate();
                // 有些动作的标签本身带读数（缓存清理会报当前占用），执行完重新取一次。
                button.setText(entry.getLabel());
            }
        });
        return swapControl(context, card, entry.getLabel(), button);
    }

    /** 颜色行：HEX 输入框（就是控制中心数字行那个输入框）加一小块色板。 */
    private View addColor(Context context, LinearLayout card, final HudSettingsModel.Entry entry) {
        LinearLayout row = PreferencesFragment.createInputBox(context, entry.getLabel());
        final EditText input = row.requireViewById(R.id.input);
        input.setText(hex(entry.getColor()));
        final View swatch = new View(context);
        swatch.setMinimumWidth(dp(28));
        swatch.setMinimumHeight(dp(18));
        paintSwatch(swatch, entry.getColor());
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(28), dp(18));
        swatchParams.gravity = Gravity.CENTER_VERTICAL;
        swatchParams.setMargins(dp(6), 0, dp(6), 0);
        row.addView(swatch, swatchParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String value = String.valueOf(editable).trim().replace("#", "");
                if (value.length() != 6) return;
                try {
                    int rgb = Integer.parseInt(value, 16);
                    entry.setColor(new Color(rgb));
                    paintSwatch(swatch, new Color(rgb));
                    dirty = true;
                } catch (NumberFormatException ignored) {
                    // 输入到一半（"9F" 之类）的中间态，等它长够六位再说。
                }
            }
        });
        card.addView(row);
        return row;
    }

    /** 主题预设：一排按钮横向滚动，预设数量会随版本长，所以不换行而是让它可滚。 */
    private View addThemeGrid(Context context, LinearLayout card) {
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (final com.muoniumplayer.core.screens.ncm.NCMTheme.ThemePreset preset
                : com.muoniumplayer.core.screens.ncm.NCMTheme.ThemePreset.values()) {
            Button chip = new Button(context);
            chip.setTextSize(11f);
            chip.setText(preset.getDisplayName());
            chip.setSelected(preset == com.muoniumplayer.core.screens.ncm.NCMTheme.getCurrent());
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    com.muoniumplayer.core.screens.ncm.NCMTheme.setCurrent(preset);
                    HudConfigSave.save();
                    DownloadDynamicIsland.showTheme(preset.getDisplayName());
                    dirty = false; // showTheme 那条路径已经落过盘，别在关屏时再写一次
                    refreshPages();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(2), 0, dp(2), 0);
            strip.addView(chip, params);
        }
        scroller.addView(strip);
        return swapControl(context, card, "配色预设", scroller);
    }

    /** 当前读数找不到下拉可用时的那一档：点一下进下一个，与内置界面同一语义。 */
    private View addCyclingButton(Context context, LinearLayout card, final HudSettingsModel.Entry entry) {
        final Button button = new Button(context);
        button.setTextSize(11f);
        button.setText(entry.getValueText());
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                entry.activate();
                button.setText(entry.getValueText());
                dirty = true;
            }
        });
        return swapControl(context, card, entry.getLabel(), button);
    }

    /**
     * 借 {@code createSwitchLayout} 的外壳（同一套高度、边距、提示位），把里面那个 Switch 换成我们的控件。
     *
     * <p>比另起一行自己排版值在这里：MUI 哪天改了行的尺寸或对齐，这一行跟着一起变，不会出现
     * 「四行里有一行高了一档」的错位。</p>
     */
    private View swapControl(Context context, LinearLayout card, String label, View control) {
        LinearLayout row = PreferencesFragment.createSwitchLayout(context, label);
        Switch stale = row.requireViewById(R.id.button1);
        if (stale != null) {
            row.removeView(stale);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        row.addView(control, params);
        card.addView(row);
        return row;
    }

    // ==================== ScreenCallback ====================

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean hasDefaultBackground() {
        return false;
    }

    @Override
    public boolean shouldBlurBackground() {
        return false;
    }

    // ==================== 小工具 ====================

    private static int indexOf(String[] values, String target) {
        if (values == null || target == null) return -1;
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) return i;
        }
        return -1;
    }

    private void paintSwatch(View swatch, Color color) {
        swatch.setBackground(new icyllis.modernui.graphics.drawable.ColorDrawable(rgb(color)));
    }

    private static int rgb(Color color) {
        return 0xFF000000 | (color.getRGB() & 0xFFFFFF);
    }

    private static String hex(Color color) {
        return String.format("%06X", color.getRGB() & 0xFFFFFF);
    }

    /** 这个框架的布局参数单位是物理像素，所以设计值都按 dp 给再换算。 */
    private int dp(float value) {
        return (int) (value * density + 0.5f);
    }

    /** 落盘只留一个入口，异常一律不外抛：关屏路径上抛出来只会把界面卡在关闭一半的状态。 */
    private static final class HudConfigSave {
        private HudConfigSave() {
        }

        static void save() {
            try {
                com.muoniumplayer.core.settings.HudConfig.save();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 带十进制档位的滑块行 ====================

    /**
     * 候选档位：全是十进制整档。挑的原则是「一档不超过 100 格里最细的那一档」，
     * 而不是「不超过 200」—— 后者会把 0..1.3 这种跨度切成 130 档，读数就变成
     * {@code 1.32}、{@code 1.51}、{@code 1.95} 这种没人会手打的尾数；切到 26/65/55 档
     * 就自然落在 {@code 1.3}、{@code 1.5}、{@code 2} 上。跨度真需要细调的（比如 0.95..1.16
     * 的整行缩放）仍然留得住 0.01。
     */
    private static final double[] STEPS = {
            0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 25, 50
    };

    /** 一次拖动的格数上限。MUI 那边超过 200 格就不画滑块了，这里比它更收。 */
    private static final int MAX_STEPS = 100;

    /** 取「跨度除它不超过 100 格」里最细的那一档。 */
    private static double pickStep(double min, double max) {
        double span = max - min;
        for (double step : STEPS) {
            if (span / step <= MAX_STEPS) return step;
        }
        return STEPS[STEPS.length - 1];
    }

    /**
     * 读数写法：整数就写成 {@code 280}，别写成 {@code 280.0}；小数按十进制最短写法。
     *
     * <p>控制中心自己那一页显示的是 {@code 200}、{@code 7}、{@code 30}、{@code 0.5}，
     * 而 {@code Double.toString} 对任何 double 都强塞一个 {@code ".0"} —— 像素宽度、
     * 字号、秒数这些跟着一起带小数点，就是「一整列抽象的小数」的来历。</p>
     */
    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * {@code FloatOption} 的取值收口版：布局、滑块、输入框、长按恢复默认全都用它的，
     * 只有「档位 ↔ 数值」这一段换成十进制算法。
     *
     * <p>用 {@link BigDecimal} 而不是直接乘除：{@code 88 * 0.01} 在二进制里是
     * {@code 0.8800000000000001}，而 {@code BigDecimal.valueOf(0.01)} 走的是十进制字面量，
     * 加完再回 {@code double} 就是 0.88，{@code Double.toString} 出来也就是「0.88」。</p>
     *
     * <p>另一处有意的差别：MUI 只在松手（{@code onStopTrackingTouch}）时才写值，我们这边拖动过程中
     * 就写。因为 HUD 是实时画的，而这一页背景是透明的（{@link #hasDefaultBackground()} 返回 false），
     * 拖到哪一格就该在画面上看到哪一格。</p>
     */
    private final class StepOption extends PreferencesFragment.FloatOption {

        private final double step;

        StepOption(Context context, String name, Supplier<Double> getter, Consumer<Double> setter,
                   double min, double max, double step) {
            super(context, name, getter, setter);
            this.step = step;
            // 分母在 MUI 那边只用来决定「画不画滑块」和「初始进度」，数值换算已经整个接管
            setRange(min, max, 1.0 / step);
        }

        private double valueAt(int progress) {
            return BigDecimal.valueOf(minValue)
                    .add(BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(progress)))
                    .doubleValue();
        }

        private int progressOf(double value) {
            return (int) Math.round((value - minValue) / step);
        }

        /** 把配置里那个 float 读回来的二进制尾巴抹回档位上。 */
        private double quantized() {
            return valueAt(progressOf(getter.get()));
        }

        private void showText(String text) {
            input.setText(text);
            input.setSelection(text.length());
        }

        @Override
        public LinearLayout create(ViewGroup parent) {
            return create(parent, -1);
        }

        @Override
        public LinearLayout create(ViewGroup parent, int maxLength) {
            LinearLayout row = super.create(parent, maxLength);
            // 它先按自己那套 progress/denominator 填了一次文本，这里按档位重填
            showText(format(quantized()));
            return row;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            double value = valueAt(seekBar.getProgress());
            showText(format(value));
            if (fromUser) {
                setter.accept(value);
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            double value = valueAt(seekBar.getProgress());
            setter.accept(value);
            showText(format(value));
        }

        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            if (hasFocus) return;
            // 手打的值尊重手打的值：只做区间夹取，不强行吸附到档位上
            double value;
            try {
                value = Double.parseDouble(input.getText().toString());
            } catch (NumberFormatException failure) {
                value = getter.get();
            }
            value = Math.max(minValue, Math.min(maxValue, value));
            setter.accept(value);
            if (slider != null) {
                slider.setProgress(progressOf(value), false);
            }
            showText(format(value));
        }
    }
}
