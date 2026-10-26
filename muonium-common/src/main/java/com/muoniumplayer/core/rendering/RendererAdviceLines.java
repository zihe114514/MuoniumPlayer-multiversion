package com.muoniumplayer.core.rendering;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染层停用提示的<b>渲染器路由文案</b>：按「渲染器环境 × 游戏版本」给出用户下一步动作。
 *
 * <h2>为什么收进共享层</h2>
 * 五棵树的 {@code RenderLayerNotice} 各留一份留守代码（GuiGraphics 画法两代 API 拼不成
 * 共享源码），但「安卓上该给哪条渲染器建议」是纯策略——留在五棵树里必然漂移
 * （历史上 26.3 已经先漂过一次：那里改成了推荐 MobileGL，其余四树还是 MobileGlues）。
 * 本类把策略收成纯函数：入参全是字符串与枚举，出参是要展示的文案行，JUnit 直接喂数据。
 *
 * <h2>路由表（2026-10-26，依据启动器与渲染器源码调研 + 真机记录）</h2>
 * <ul>
 *   <li>MobileGL：26.3+ 的正解（GL 4.2）。mod 已做 GOT 重定向适配，提示里让它查
 *       {@code [MuoniumPlayer/SkijaGlRedirect]} 日志行确认门对齐；</li>
 *   <li>MobileGlues：1.20.1~26.2 的实测可用组合是「MobileGlues + 关 ANGLE」；
 *       26.3+ 上它是旧结论——那里正解是 MobileGL，照抄会把用户引离适配路线；</li>
 *   <li>NG-GL4ES（Krypton）：可能弄坏世界贴图，两个版本段都改推对应正解；</li>
 *   <li>Zink / VirGL / Freedreno / Panfrost（Mesa 真驱动）：真玻璃实验档只在它们身上
 *       开放，指引到 HUD 设置里的开关；</li>
 *   <li>识别不出（UNKNOWN）：给通用移动端建议。</li>
 * </ul>
 */
public final class RendererAdviceLines {

    /** Minecraft 版本段的两个路由分支；由各树把版本串传进来。 */
    public enum GameBranch {
        /** 1.20.1 ~ 26.2：MobileGlues（关 ANGLE）是实测正解。 */
        LEGACY,
        /** 26.3+：MobileGL 是正解（NG-GL4ES / Zink 等上限 26.2，启动器已拒）。 */
        RENDER_PEARL
    }

    private RendererAdviceLines() {
    }

    /**
     * 渲染器建议文案行。返回列表可为空（桌面 / 非安卓家族不给渲染器建议）。
     *
     * @param branch   游戏版本段（各树按自己的 MC 版本传）
     * @param envKind  识别出的渲染器 kind id（{@code MobileRendererEnv.Kind#id}）；
     *                 识别层没接上时传 {@code "unknown"}
     * @param android  当前是否安卓运行时（各树从 {@code SkijaNatives.Status#mappedPlatform} 取）
     */
    public static List<String> of(GameBranch branch, String envKind, boolean android) {
        if (!android) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        String kind = envKind == null ? "unknown" : envKind;
        switch (kind) {
            case "mobilegl" -> {
                lines.add("渲染器 MobileGL 正确（26.3+ 的适配路线）");
                lines.add("若界面仍不显示：日志里搜 MuoniumPlayer/SkijaGlRedirect，"
                        + "确认有「重定向 N 个 GL/EGL 导入」一行");
            }
            case "mobileglues" -> {
                if (branch == GameBranch.RENDER_PEARL) {
                    // MobileGlues 对 26.3 没有版本上限声明，但 26.3 玩家的主流与适配路线都是 MobileGL。
                    lines.add("当前渲染器是 MobileGlues；26.3+ 建议改用 MobileGL（本模组已适配并实测）");
                } else {
                    lines.add("渲染器请选 MobileGlues（MG）并关闭 ANGLE，再装伴生包");
                    lines.add("ANGLE 开着 Skia 会装不出图形上下文；换 Krypton 包装则可能弄坏世界贴图");
                }
            }
            case "ng_gl4es", "holy_gl4es" -> {
                lines.add("GL4ES 系渲染器可能弄坏世界贴图与界面材质");
                lines.add(branch == GameBranch.RENDER_PEARL
                        ? "26.3+ 请改用 MobileGL 渲染器"
                        : "请改用 MobileGlues（MG）并关闭 ANGLE");
            }
            case "zink", "virgl", "freedreno", "panfrost" -> {
                lines.add("Mesa 渲染器（" + kind + "）上 Skia 的 GL 门已自动对齐（过程地址桥）");
                lines.add("若界面仍不显示：日志里搜 MuoniumPlayer/SkijaGlRedirect，"
                        + "确认有「过程地址门」或「dlsym 门」命中一行");
                lines.add("真玻璃在 Mesa 渲染器上以实验档开放：HUD 设置 → 真玻璃材质 → 真玻璃（实验）");
            }
            default -> {
                // 未知渲染器 / 属性被启动器省略：给两段各自的通用正解。
                if (branch == GameBranch.RENDER_PEARL) {
                    lines.add("26.3 安卓请使用 MobileGL 渲染器（本模组已适配并实测）");
                    lines.add("Krypton 包装的渲染器可能弄坏世界贴图，不建议使用");
                } else {
                    lines.add("渲染器请选 MobileGlues（MG）并关闭 ANGLE，再装伴生包");
                    lines.add("ANGLE 开着 Skia 会装不出图形上下文；换 Krypton 包装则可能弄坏世界贴图");
                }
            }
        }
        return lines;
    }
}
