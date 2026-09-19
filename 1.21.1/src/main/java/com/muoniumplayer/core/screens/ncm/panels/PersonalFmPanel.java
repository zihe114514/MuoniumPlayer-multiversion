package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.PersonalFmManager;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 私人 FM 发现界面，由一次一条的推荐拉取支撑。 */
public final class PersonalFmPanel extends NCMPanel {

    private static final double MARGIN = 12.0;
    private static final double BUTTON_GAP = 6.0;

    private static final PersonalFmManager.Mode[] MODES = {
            PersonalFmManager.Mode.DEFAULT, PersonalFmManager.Mode.FAMILIAR,
            PersonalFmManager.Mode.EXPLORE, PersonalFmManager.Mode.SCENE_RCMD,
            PersonalFmManager.Mode.PUZZLE_MODE_RCMD, PersonalFmManager.Mode.AIDJ
    };
    private static final String[] MODE_LABELS = new String[MODES.length];

    /** 场景子模式的分组展示：分组标题 + 组内中文名，成员顺序与 PersonalFmManager 的分组一致。 */
    private static final String[][] SCENE_GROUP_LABELS = new String[PersonalFmManager.SCENE_GROUP_MODES.length][];

    static {
        for (int index = 0; index < MODES.length; index++) MODE_LABELS[index] = MODES[index].getDisplayName();
        for (int group = 0; group < SCENE_GROUP_LABELS.length; group++) {
            String[] modes = PersonalFmManager.SCENE_GROUP_MODES[group];
            String[] labels = new String[modes.length];
            for (int index = 0; index < modes.length; index++) labels[index] = PersonalFmManager.sceneLabel(modes[index]);
            SCENE_GROUP_LABELS[group] = labels;
        }
    }

    private static final double SCENE_TOP = 94.0;
    private static final double HEADER_ROW = 17.0;
    private static final double CHIP_H = 17.0;
    private static final double CHIP_GAP = 6.0;
    private static final double ROW_GAP = 5.0;
    private static final double GROUP_GAP = 8.0;

    @Override
    public void onInit() {
        renderLayout();
        PersonalFmManager.ensureInitialLoad();
    }

    private void renderLayout() {
        getChildren().clear();

        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        addChild(back);
        back.setShouldOverrideMouseCursor(true);
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 22).setPosition(Math.max(MARGIN, getWidth() - back.getWidth() - MARGIN), 8).setRadius(6)
                    .setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        LabelWidget title = new LabelWidget("私人 FM", FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(MARGIN, 31)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));

        LabelWidget subtitle = new LabelWidget(PersonalFmManager::getStatus, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(MARGIN, 56)
                .setMaxWidth(Math.max(1.0, getWidth() - MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL));

        RoundedButtonWidget refresh = new RoundedButtonWidget("刷新", FontManager.pf12bold);
        addChild(refresh);
        refresh.setShouldOverrideMouseCursor(true);
        refresh.setBeforeRenderCallback(() -> {
            refresh.setBounds(40, 17)
                    .setPosition(Math.max(MARGIN, getWidth() - MARGIN - refresh.getWidth()), 31)
                    .setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setAlpha(PersonalFmManager.isLoading() ? .45f : 1.0f)
                    .setColor(refresh.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            refresh.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        refresh.setOnClickCallback((x, y, button) -> {
            if (button != 0 || PersonalFmManager.isLoading()) return button == 0;
            PersonalFmManager.openOrRefresh();
            return true;
        });

        for (int index = 0; index < MODES.length; index++) addModeButton(MODES[index], index);

        boolean sceneSelected = PersonalFmManager.getSelectedMode() == PersonalFmManager.Mode.SCENE_RCMD;
        if (sceneSelected) {
            for (int group = 0; group < SCENE_GROUP_LABELS.length; group++) {
                addSceneGroupHeader(group);
                for (int index = 0; index < SCENE_GROUP_LABELS[group].length; index++) {
                    addSceneChip(group, index);
                }
            }
        }

        ScrollPanel songs = new ScrollPanel();
        addChild(songs);
        songs.setSpacing(0).setScrollStrength(42).setAlignment(ScrollPanel.Alignment.VERTICAL);
        songs.setBeforeRenderCallback(() -> {
            double bottom = sceneSelected ? sceneAreaBottom(getWidth()) : 92.0;
            songs.setBounds(MARGIN, bottom, Math.max(1.0, getWidth() - MARGIN * 2.0),
                    Math.max(1.0, getHeight() - bottom - 8.0));
        });

        List<Music> batch = PersonalFmManager.getCurrentBatchSnapshot();
        if (batch.isEmpty()) {
            LabelWidget empty = new LabelWidget(PersonalFmManager.isLoading() ? "正在获取推荐…" : "暂无私人 FM 推荐", FontManager.pf14bold);
            songs.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> empty
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, songs.getWidth()), 24));
            return;
        }

        PlayList fmPlaylist = new PlayList(-900001L, "私人 FM", "", batch.size(), 0L, null, "个人推荐", 0L);
        fmPlaylist.setSearchMode(true);
        fmPlaylist.setPersonalFm(true);
        fmPlaylist.setMusics(new CopyOnWriteArrayList<>(batch));
        fmPlaylist.setMusicsQueried(true);
        fmPlaylist.setMusicsLoaded(true);

        List<AbstractWidget<?>> rows = new ArrayList<>();
        for (int index = 0; index < batch.size(); index++) {
            MusicWidget row = new MusicWidget(batch.get(index), fmPlaylist, index);
            row.setShouldOverrideMouseCursor(true);
            rows.add(row);
        }
        songs.addChild(rows);
    }

    private void addModeButton(PersonalFmManager.Mode mode, int index) {
        RoundedButtonWidget button = new RoundedButtonWidget(mode.getDisplayName(), FontManager.pf12bold);
        addChild(button);
        button.setShouldOverrideMouseCursor(true);
        button.setBeforeRenderCallback(() -> {
            boolean selected = PersonalFmManager.getSelectedMode() == mode;
            button.setBounds(labelWidth(mode.getDisplayName()), 17)
                    .setPosition(rowStartX(MODE_LABELS, index), 73).setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setColor(selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || PersonalFmManager.isLoading()) return mouseButton == 0;
            PersonalFmManager.selectMode(mode, mode.getDefaultSubMode());
            return true;
        });
    }

    private void addSceneGroupHeader(int group) {
        LabelWidget header = new LabelWidget(PersonalFmManager.SCENE_GROUP_TITLES[group], FontManager.pf12bold);
        addChild(header);
        header.setClickable(false);
        header.setBeforeRenderCallback(() -> {
            double[] at = scenePosition(group, -1, getWidth());
            header.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)).setPosition(at[0], at[1]);
        });
    }

    private void addSceneChip(int group, int indexInGroup) {
        String label = SCENE_GROUP_LABELS[group][indexInGroup];
        String subMode = PersonalFmManager.SCENE_GROUP_MODES[group][indexInGroup];
        RoundedButtonWidget button = new RoundedButtonWidget(label, FontManager.pf12bold);
        addChild(button);
        button.setShouldOverrideMouseCursor(true);
        button.setBeforeRenderCallback(() -> {
            boolean selected = subMode.equals(PersonalFmManager.getSelectedSubMode());
            double[] at = scenePosition(group, indexInGroup, getWidth());
            button.setBounds(labelWidth(label), CHIP_H).setPosition(at[0], at[1]).setRadius(4)
                    .setClickable(!PersonalFmManager.isLoading())
                    .setColor(selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                            : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
            button.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || PersonalFmManager.isLoading()) return mouseButton == 0;
            PersonalFmManager.selectMode(PersonalFmManager.Mode.SCENE_RCMD, subMode);
            return true;
        });
    }

    private static double labelWidth(String label) {
        return Math.max(31.0, FontManager.pf12bold.getStringWidth(label) + 12.0);
    }

    /** 行内第 index 个按钮的起点：累加前面每个按钮的实际宽度，名字变长也不会互相叠压。 */
    private static double rowStartX(String[] labels, int index) {
        double x = MARGIN;
        for (int i = 0; i < index; i++) x += labelWidth(labels[i]) + BUTTON_GAP;
        return x;
    }

    /**
     * 重演从第一个分组起到目标控件为止的全部摆放，返回其左上角。chipIndex 为 -1 表示取分组标题。
     * 每帧重算的代价是几十次加法，换来的是窗口宽度变化时场景区自己完成换行回流。
     */
    private static double[] scenePosition(int groupIndex, int chipIndex, double panelWidth) {
        double y = SCENE_TOP;
        for (int g = 0; g < groupIndex; g++) y += groupHeight(g, panelWidth);
        if (chipIndex < 0) return new double[]{MARGIN, y};
        y += HEADER_ROW;
        double x = MARGIN;
        String[] labels = SCENE_GROUP_LABELS[groupIndex];
        for (int i = 0; i < chipIndex; i++) {
            double w = labelWidth(labels[i]);
            if (x > MARGIN && x + w > panelWidth - MARGIN) {
                x = MARGIN;
                y += CHIP_H + ROW_GAP;
            }
            x += w + CHIP_GAP;
        }
        return new double[]{x, y};
    }

    /** 单个分组（标题 + 换行后的全部 chip + 组间距）占用的总高度。 */
    private static double groupHeight(int groupIndex, double panelWidth) {
        double height = HEADER_ROW;
        double x = MARGIN;
        String[] labels = SCENE_GROUP_LABELS[groupIndex];
        for (String label : labels) {
            double w = labelWidth(label);
            if (x > MARGIN && x + w > panelWidth - MARGIN) {
                x = MARGIN;
                height += CHIP_H + ROW_GAP;
            }
            x += w + CHIP_GAP;
        }
        return height + CHIP_H + GROUP_GAP;
    }

    /** 场景区（含最后一个分组）底缘的 y 坐标，歌曲列表从这里开始。 */
    private static double sceneAreaBottom(double panelWidth) {
        double y = SCENE_TOP;
        for (int g = 0; g < SCENE_GROUP_LABELS.length; g++) y += groupHeight(g, panelWidth);
        return y - GROUP_GAP + 4.0;
    }
}
