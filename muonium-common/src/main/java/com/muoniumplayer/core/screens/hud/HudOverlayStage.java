package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 叠加层（歌曲信息卡 / 桌面歌词）在编辑器里的「舞台」：点选、拖动、边缘缩放与吸附的
 * 状态机与几何。只做判定与数值，不画一笔 —— 渲染由 {@link GuiHudEditorPro} 拿这里的几何去画，
 * 「一份几何、两处消费」与设置面板那条纪律一致。
 *
 * <p>交互模型（对应桌面窗口的手感）：</p>
 * <ul>
 *   <li><b>按下不动、位移小于 {@link #SELECT_THRESHOLD} 再松开 = 选中/取消选中。</b>
 *       按下即拖走 = 移动。选中之前边缘没有把手，选中后四边四角出现把手。</li>
 *   <li><b>拖把手 = 只改对应轴的尺寸，对边/对角固定。</b>歌词卡写
 *       {@code lyricWidth/lyricHeight}（未缩放单位，区间来自 {@link HudSetting} 的滑条声明，
 *       与设置页同一条钳制）；信息卡没有独立宽高，边缘拖拽改整体 {@code infoScale}，
 *       锚在对面那条边/角。</li>
 *   <li><b>吸附 = 中心线 + 屏幕边缘 + 两块互相之间的边缘对齐</b>，全部是弱磁吸并画参考线。
 *       中心线沿用既有 {@link #SNAP_STRENGTH} 弱磁吸语义（贴近辅助、可无感拖离）。</li>
 *   <li>所有改动只在松手时由调用方落一次盘（{@code HudConfig.save()}）。</li>
 * </ul>
 *
 * <p>坐标约定：外部世界全是屏幕像素；只有读写 HudConfig 的一刻做归一化（位置）与
 * 「除以 scale」（歌词宽高）的换算。窗口尺寸由 {@code update} 每帧传入，不缓存。</p>
 */
final class HudOverlayStage {

    /** 参与舞台的两块叠加层。灵动岛与频谱没有位置字段，只做只读预览，不进这张表。 */
    enum Kind {
        NONE, INFO, LYRICS
    }

    /** 拖拽进行中的角色：移动，或某个方向的缩放。 */
    enum Zone {
        MOVE, N, S, E, W, NE, NW, SE, SW
    }

    /** 点按位移超过这一像素才算拖动；以内松开算「点选」。 */
    private static final int SELECT_THRESHOLD = 4;
    /** 边缘/角落把手的命中带（从矩形边线往内外各延这么多像素）。 */
    private static final int HANDLE_BAND = 7;
    /** 吸附判定阈值（像素）。 */
    private static final int SNAP_THRESHOLD = 8;
    /** 弱磁吸强度：越接近目标被拉得越狠，但永远拉不满（可无感拖离）。 */
    private static final double SNAP_STRENGTH = 0.35;

    /** 当前选中的叠加层；NONE = 无选中（两块都只画虚线框）。 */
    private Kind selected = Kind.NONE;
    /** 本轮按住的是哪一块。 */
    private Kind engaged = Kind.NONE;
    private Zone zone = Zone.MOVE;
    private boolean pressed;
    private boolean moved;
    private int pressX;
    private int pressY;
    private int grabOffX;
    private int grabOffY;
    /** 缩放起点的各原始值（换算回配置单位），用于「对边固定」的反算。 */
    private float originX;
    private float originY;
    private float originW;
    private float originH;
    private float originScale;
    /** 按下那一刻的像素跨度（宽、高），作为信息卡等比缩放的比例分母。 */
    private int originPxW;
    private int originPxH;
    /** 本帧要画的参考线（1px 横/竖线）。 */
    final List<int[]> guides = new ArrayList<int[]>();
    /** 缩放期间跟随的尺寸读数（如 {@code 480 × 160}）；非缩放期间为 null。 */
    String bubble;
    /** 上一次松手时是否有值得落盘的改动（移动、缩放或滚轮）。 */
    private boolean releaseDirty;

    Kind selected() {
        return selected;
    }

    boolean hasSelection() {
        return selected != Kind.NONE;
    }

    /** 点击面板内部等场景：外部明确要求清掉选中态（同时撤销未完成的按住）。 */
    void clearSelection() {
        selected = Kind.NONE;
        pressed = false;
        engaged = Kind.NONE;
        zone = Zone.MOVE;
        guides.clear();
        bubble = null;
    }

    /**
     * 每帧推进一次。
     *
     * @param lmb      左键是否处于按下（且界面层允许操作叠加层）
     * @param wheel    本帧滚轮增量（只在悬停叠加层时用于整体缩放）
     * @param mx/my    鼠标屏幕坐标
     * @param info     信息卡矩形 {@code [x,y,w,h]}（像素）
     * @param lyrics   歌词卡矩形
     * @param infoBase 信息卡未缩放基准尺寸（宽、高），换算 scale 用
     * @param screenW/H 屏幕尺寸
     * @return 本帧是否发生了「松手且有改动」—— 调用方据此 {@code HudConfig.save()} 一次
     */
    boolean update(boolean lmb, int wheel, int mx, int my,
                   int[] info, int[] lyrics, int[] infoBase, int screenW, int screenH) {
        guides.clear();
        bubble = null;
        releaseDirty = false;

        if (!lmb) {
            if (pressed) {
                // 位移没过阈值就松开 = 点选：选中这一块（再点同一块 = 取消）。
                if (!moved && zone == Zone.MOVE) {
                    selected = selected == engaged ? Kind.NONE : engaged;
                }
                pressed = false;
                engaged = Kind.NONE;
                zone = Zone.MOVE;
            }
            applyWheel(wheel, mx, my, info, lyrics, infoBase);
            return takeReleaseDirty();
        }

        if (!pressed) {
            Kind hit = kindAt(mx, my, info, lyrics);
            if (hit == Kind.NONE) {
                // 点在空白处（面板/小窗已被调用方挡在外面）：有选中就收掉，与桌面「点掉选中」一致。
                selected = Kind.NONE;
                applyWheel(wheel, mx, my, info, lyrics, infoBase);
                return takeReleaseDirty();
            }
            pressed = true;
            moved = false;
            engaged = hit;
            int[] rect = rectOf(hit, info, lyrics);
            zone = hit == selected ? zoneAt(rect, mx, my) : Zone.MOVE;
            pressX = mx;
            pressY = my;
            grabOffX = mx - rect[0];
            grabOffY = my - rect[1];
            originPxW = rect[2];
            originPxH = rect[3];
            snapshotOrigin(hit);
            return takeReleaseDirty();
        }

        // 按住中：过阈值才动手，纯粹的点按（哪怕是按在把手上不动）不算拖动。
        if (!moved) {
            if (Math.abs(mx - pressX) <= SELECT_THRESHOLD && Math.abs(my - pressY) <= SELECT_THRESHOLD) {
                return takeReleaseDirty();
            }
            moved = true;
        }
        if (zone == Zone.MOVE) {
            applyMove(engaged, mx, my, info, lyrics, screenW, screenH);
        } else {
            applyResize(engaged, mx, my, info, lyrics, infoBase, screenW, screenH);
        }
        return takeReleaseDirty();
    }

    private boolean takeReleaseDirty() {
        boolean dirty = releaseDirty || wheelDirty;
        wheelDirty = false;
        releaseDirty = false;
        return dirty;
    }

    private boolean wheelDirty;

    // ==================== 命中判定 ====================

    private static int[] rectOf(Kind kind, int[] info, int[] lyrics) {
        return kind == Kind.INFO ? info : lyrics;
    }

    /** 两块重叠时选中的那块优先，其次信息卡优先（与既有的命中顺序一致）。 */
    private Kind kindAt(int mx, int my, int[] info, int[] lyrics) {
        boolean inInfo = inside(mx, my, info);
        boolean inLyrics = inside(mx, my, lyrics);
        if (selected == Kind.INFO && inInfo) return Kind.INFO;
        if (selected == Kind.LYRICS && inLyrics) return Kind.LYRICS;
        if (inInfo) return Kind.INFO;
        if (inLyrics) return Kind.LYRICS;
        return Kind.NONE;
    }

    private static boolean inside(int mx, int my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /** 已选中矩形上的把手判定：先四角后四边（角比边窄，优先命中角）。 */
    static Zone zoneAt(int[] r, int mx, int my) {
        int x2 = r[0] + r[2];
        int y2 = r[1] + r[3];
        boolean nearL = Math.abs(mx - r[0]) <= HANDLE_BAND;
        boolean nearR = Math.abs(mx - x2) <= HANDLE_BAND;
        boolean nearT = Math.abs(my - r[1]) <= HANDLE_BAND;
        boolean nearB = Math.abs(my - y2) <= HANDLE_BAND;
        boolean inBox = mx >= r[0] - HANDLE_BAND && mx <= x2 + HANDLE_BAND
                && my >= r[1] - HANDLE_BAND && my <= y2 + HANDLE_BAND;
        if (!inBox) return Zone.MOVE;
        if (nearL && nearT) return Zone.NW;
        if (nearR && nearT) return Zone.NE;
        if (nearL && nearB) return Zone.SW;
        if (nearR && nearB) return Zone.SE;
        if (nearT) return Zone.N;
        if (nearB) return Zone.S;
        if (nearL) return Zone.W;
        if (nearR) return Zone.E;
        return Zone.MOVE;
    }

    // ==================== 移动与吸附 ====================

    private void snapshotOrigin(Kind kind) {
        if (kind == Kind.INFO) {
            originX = HudConfig.infoX;
            originY = HudConfig.infoY;
            originScale = HudConfig.infoScale;
        } else {
            originX = HudConfig.lyricX;
            originY = HudConfig.lyricY;
            originW = HudConfig.lyricWidth;
            originH = HudConfig.lyricHeight;
            originScale = HudConfig.lyricScale;
        }
    }

    private void applyMove(Kind kind, int mx, int my, int[] info, int[] lyrics, int screenW, int screenH) {
        int[] r = rectOf(kind, info, lyrics);
        int maxX = Math.max(0, screenW - r[2]);
        int maxY = Math.max(0, screenH - r[3]);
        int nx = clamp(mx - grabOffX, 0, maxX);
        int ny = clamp(my - grabOffY, 0, maxY);
        int[] other = rectOf(kind == Kind.INFO ? Kind.LYRICS : Kind.INFO, info, lyrics);

        nx = snapAxis(nx, new int[] { maxX / 2, 0, maxX, other[0], other[0] + other[2] },
                screenW, true, r[2]);
        ny = snapAxis(ny, new int[] { maxY / 2, 0, maxY, other[1], other[1] + other[3] },
                screenH, false, r[3]);

        if (kind == Kind.INFO) {
            HudConfig.infoX = maxX > 0 ? (float) nx / maxX : 0f;
            HudConfig.infoY = maxY > 0 ? (float) ny / maxY : 0f;
        } else {
            HudConfig.lyricX = maxX > 0 ? (float) nx / maxX : 0f;
            HudConfig.lyricY = maxY > 0 ? (float) ny / maxY : 0f;
        }
    }

    /**
     * 单轴吸附：候选点集 = 中心线、屏幕两侧、另一块的两侧。命中候选就把参考线加进
     * {@link #guides}，并用弱磁吸拉近。返回吸附后的坐标。
     */
    private int snapAxis(int value, int[] candidates, int screenSpan, boolean horizontal, int widgetSpan) {
        int best = Integer.MIN_VALUE;
        int bestDelta = Integer.MAX_VALUE;
        for (int candidate : candidates) {
            int delta = Math.abs(value - candidate);
            if (delta < SNAP_THRESHOLD && delta < bestDelta) {
                best = candidate;
                bestDelta = delta;
            }
        }
        if (best == Integer.MIN_VALUE) return value;
        int softened = softenSnap(value, best);
        if (horizontal) {
            guides.add(new int[] { softened, 0, 1, screenSpan });
        } else {
            guides.add(new int[] { 0, softened, screenSpan, 1 });
        }
        return softened;
    }

    /** 中心吸附从硬跳降级为弱磁吸：贴近时辅助对齐，仍可无感拖离。 */
    private static int softenSnap(int value, int target) {
        int delta = target - value;
        int distance = Math.abs(delta);
        if (distance >= SNAP_THRESHOLD) return value;
        if (distance <= 1) return target;
        return value + (int) Math.round(delta * SNAP_STRENGTH);
    }

    // ==================== 缩放 ====================

    private void applyResize(Kind kind, int mx, int my, int[] info, int[] lyrics,
                             int[] infoBase, int screenW, int screenH) {
        int[] r = rectOf(kind, info, lyrics);
        boolean east = zone == Zone.E || zone == Zone.NE || zone == Zone.SE;
        boolean west = zone == Zone.W || zone == Zone.NW || zone == Zone.SW;
        boolean south = zone == Zone.S || zone == Zone.SE || zone == Zone.SW;
        boolean north = zone == Zone.N || zone == Zone.NE || zone == Zone.NW;

        if (kind == Kind.INFO) {
            // 信息卡没有独立宽高：按拖拽方向从按下时的像素跨度缩放整体，锚在对面那条边/角。
            float ratioW = east ? (mx - r[0]) / (float) Math.max(1, originPxW)
                    : west ? (r[0] + r[2] - mx) / (float) Math.max(1, originPxW) : 1f;
            float ratioH = south ? (my - r[1]) / (float) Math.max(1, originPxH)
                    : north ? (r[1] + r[3] - my) / (float) Math.max(1, originPxH) : 1f;
            float ratio = 1f;
            if (ratioW != 1f && ratioH != 1f) ratio = (ratioW + ratioH) / 2f;
            else if (ratioW != 1f) ratio = ratioW;
            else if (ratioH != 1f) ratio = ratioH;
            float scale = HudConfig.clampScale(originScale * Math.max(0.1f, ratio));
            int newW = Math.round(infoBase[0] * scale);
            int newH = Math.round(infoBase[1] * scale);
            // 对边固定：按被拖的那条边反推左上角（纯缩放方向居中收缩），再归一化。
            int left = west ? (r[0] + r[2] - newW) : east ? r[0] : r[0] + (r[2] - newW) / 2;
            int top = north ? (r[1] + r[3] - newH) : south ? r[1] : r[1] + (r[3] - newH) / 2;
            int maxX = Math.max(0, screenW - newW);
            int maxY = Math.max(0, screenH - newH);
            HudConfig.infoScale = scale;
            HudConfig.infoX = maxX > 0 ? clamp(left, 0, maxX) / (float) maxX : 0f;
            HudConfig.infoY = maxY > 0 ? clamp(top, 0, maxY) / (float) maxY : 0f;
            bubble = Math.round(newW) + " × " + Math.round(newH) + " · " + String.format("%.2f×", scale);
            return;
        }

        // 歌词卡：写未缩放的 lyricWidth/lyricHeight（与设置页同一条钳制），对边固定。
        float scale = HudConfig.lyricScale;
        float width = HudConfig.lyricWidth;
        float height = HudConfig.lyricHeight;
        float minX = HudSetting.OSD_WIDTH.getMin();
        float maxXv = HudSetting.OSD_WIDTH.getMax();
        float minY = HudSetting.OSD_HEIGHT.getMin();
        float maxYv = HudSetting.OSD_HEIGHT.getMax();
        int fixedRight = r[0] + r[2];
        int fixedBottom = r[1] + r[3];
        if (east) width = clamp((mx - r[0]) / scale, minX, maxXv);
        if (west) width = clamp((fixedRight - mx) / scale, minX, maxXv);
        if (south) height = clamp((my - r[1]) / scale, minY, maxYv);
        if (north) height = clamp((fixedBottom - my) / scale, minY, maxYv);
        HudConfig.lyricWidth = width;
        HudConfig.lyricHeight = height;
        int newW = Math.round(width * scale);
        int newH = Math.round(height * scale);
        int left = west ? fixedRight - newW : r[0];
        int top = north ? fixedBottom - newH : r[1];
        int maxPx = Math.max(0, screenW - newW);
        int maxPy = Math.max(0, screenH - newH);
        HudConfig.lyricX = maxPx > 0 ? clamp(left, 0, maxPx) / (float) maxPx : 0f;
        HudConfig.lyricY = maxPy > 0 ? clamp(top, 0, maxPy) / (float) maxPy : 0f;
        bubble = Math.round(width) + " × " + Math.round(height);
    }

    private void applyWheel(int wheel, int mx, int my, int[] info, int[] lyrics, int[] infoBase) {
        if (wheel == 0) return;
        Kind over = kindAt(mx, my, info, lyrics);
        if (over == Kind.NONE) return;
        float delta = wheel > 0 ? 0.05f : -0.05f;
        if (over == Kind.INFO) {
            HudConfig.infoScale = HudConfig.clampScale(HudConfig.infoScale + delta);
        } else {
            HudConfig.lyricScale = HudConfig.clampScale(HudConfig.lyricScale + delta);
        }
        wheelDirty = true;
    }

    // ==================== 供渲染查询的几何 ====================

    /** 选中块上的 8 个把手矩形（角 12×12、边按轴向拉长），画与命中共用。 */
    List<int[]> handleRects(int[] r) {
        List<int[]> handles = new ArrayList<int[]>();
        int grip = 6;
        handles.add(new int[] { r[0] - grip, r[1] - grip, grip * 2, grip * 2 });          // NW
        handles.add(new int[] { r[0] + r[2] - grip, r[1] - grip, grip * 2, grip * 2 });   // NE
        handles.add(new int[] { r[0] - grip, r[1] + r[3] - grip, grip * 2, grip * 2 });   // SW
        handles.add(new int[] { r[0] + r[2] - grip, r[1] + r[3] - grip, grip * 2, grip * 2 }); // SE
        handles.add(new int[] { r[0] + r[2] / 2 - grip, r[1] - grip, grip * 2, grip * 2 });    // N
        handles.add(new int[] { r[0] + r[2] / 2 - grip, r[1] + r[3] - grip, grip * 2, grip * 2 }); // S
        handles.add(new int[] { r[0] - grip, r[1] + r[3] / 2 - grip, grip * 2, grip * 2 });    // W
        handles.add(new int[] { r[0] + r[2] - grip, r[1] + r[3] / 2 - grip, grip * 2, grip * 2 }); // E
        return handles;
    }

    /**
     * 快捷配置小窗的矩形 {@code [x,y,w,h]}：优先放选中块的右侧，放不下换左侧、下方、上方；
     * 四个方向都放不下（极端小屏）就贴着屏幕右缘盖在块旁边，保证永远出得来。
     */
    static int[] popoverBounds(int[] r, int popW, int popH, int screenW, int screenH) {
        int gap = 10;
        int x = r[0] + r[2] + gap;
        if (x + popW > screenW - 4) x = r[0] - popW - gap;
        if (x < 4) x = r[0] + r[2] + gap >= screenW - 4 ? 4 : Math.min(screenW - popW - 4, r[0] + r[2] + gap);
        int y = r[1];
        if (y + popH > screenH - 4) y = Math.max(4, screenH - popH - 4);
        return new int[] { Math.max(4, x), y, popW, popH };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
