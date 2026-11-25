# 内置 Skija 设置中心验收记录

核对日期：2026-11-24。

设置中心主体位于 `muonium-common/src/main/java/com/muoniumplayer/configskija`，资源同样位于共享根。七树在各自宿主适配窗口、声音、键盘、头像、鼠标 Mixin；H 键默认打开内置 Skija 调参页，HUD 工作室由页内按钮进入，可选 ModernUI 路由保留。无需安装独立 OneConfig、MuoniumConfig 或 Skija 验收 MOD。

## 本次修复

- 大部分旧分类 ID 为空，导致多个页签指向同一路由；现按持久标识或稳定名称摘要生成唯一分类、分组和选项身份。
- 切分类重置滚动与焦点，前进/后退恢复各分类历史。
- HUD 数字标签与精确输入分离，增加独立连续滑条；保留步进、范围、取消和一拖一次撤销。缩放步长为 0.01，窄列自动换行。
- 主项目 tick、HUD/Screen 绘制、退出释放和背景采样直接接线；HUD 编辑页避免重复绘制真实 HUD 与预览。
- 旧配置包下载、注册、更新重启和建议依赖已从主路径移除。旧设置值迁移至内置档，ModernUI 选择保留。

## 验证结果

只执行 26.3 Fabric；未启动游戏。`compileJava`、`assemble` 通过。整轮 JUnit 共 3110 项：2870 通过、239 跳过、1 失败。唯一剩余失败为 `SkijaTrailWiringStaticTest.capablePortsKeepTheSameQuadPathAndRasterFallbackWhileLegacyMipStaysIntact` 的 26.3 世界歌词源码守卫，与本次设置中心修改无关，未修改或放宽该守卫；完整 `build` 因此不能记为通过。

真实分类唯一性、逐页点击/选项展示、历史滚动三项回归通过。真实模型及截图探针通过独立测试 JVM 隔离旧全局单例。离屏设置控件、HUD 拖动/精确输入/滑条/撤销以及窄屏布局测试通过，详见工具输出日志。

- 主成品：`../26.3/fabric/build/libs/MuoniumPlayer-26.3-fabric-2.6.1.jar`
- 整轮日志：`../26.3/build/embedded-config-validation-final.log`
- 打包日志：`../26.3/build/embedded-config-assemble.log`
- 真实设置 11 页：`../26.3/build/embedded-config-probes/`
- 全分类拼图：`../26.3/build/embedded-config-probes/contact-sheet.jpg`
- HUD 9 张夹具图：`build/hud-previews/`；其宿主与游戏背景是确定性夹具，不能代替游戏实测。

开发 `26.3/fabric/run/mods` 中三个已核实旧包已改名追加 `.disabled-skija-integrated` 保留，下次开发启动使用工程本身的代码。普通启动器验收请使用新主包，停用先前独立配置/验收包，H 直接打开调参页，侧栏“HUD 工作室”进入编辑模式，工具栏“返回”回到调参。

其余六树与 26.3 NeoForge 已接线但本轮未构建、未实测；不声明十四目标兼容通过。人工重点验收页签内容、滑条手感、精确输入、撤销、缩放/GUI scale、失焦与关闭后保存。


## 第二轮人工反馈修复（2026-11-25）

- 侧栏、按钮、分类、下拉及数字输入使用真实字体字形边界垂直居中；普通段落保持行顶布局。
- 滚轮加入可打断的平滑跟随，分类切换采用短淡入和轻位移；过期点击区域、焦点和历史状态有回归覆盖。
- 修复播放预览的经典歌词裁剪/矩阵释放顺序，阻止歌词缩放影响 HUD 工具栏和面板；宿主预览离开时恢复绘制深度。播放场景使用真实绘制、无声音频状态夹具离屏核查，无游戏启动。
- H 默认进入调参页；HUD 工作室保留独立入口。工具栏返回箭头改用实际存在的资源。

本轮日志：`../26.3/build/embedded-config-round2-targeted.log`（定向回归），`../26.3/build/embedded-config-round2-final.log`（最终全量测试及 assemble）。最终执行 `:fabric:test :fabric:assemble --continue`（仅 26.3 Fabric）：compileJava、jar、sourcesJar、assemble 任务成功；JUnit 共 3111 项，2871 通过、239 跳过、1 失败。唯一失败仍为上一轮相同的 `SkijaTrailWiringStaticTest` 世界歌词源码守卫，本轮未修改该无关测试。整体 Gradle 调用因 test 失败返回失败，不记为完整 build 全通过。

定向的播放预览矩阵/裁剪回归、调参默认路由、实际分类点击与返回、设置中心守卫通过。独立 Java 17 CPU 套件完整通过，包含新增平滑滚动524项、交互722项、滚动轨迹122项、HUD数字布局891项及输入/字体像素检查。旧 HUD 细节测试对“手动滚轮立即停止在当前位置”的断言，已按新平滑交互契约更新为检查取消自动分组滚动、手动轨迹在两者之间前进、精准落在手动目标、后续不恢复已取消spring；整套preview验证无失败。

新版已输出同名主包，包内确认客户端调用 openSettings、设置 Screen 显式选择 SETTINGS，未生成独立配置 MOD。15 张夹具页、9 张 HUD 面板图及 11 张真实分类页已更新；真实播放状态 CPU 编辑器图位于 `../26.3/build/embedded-config-probes/hud-playing-classic.png`。均未启动 Minecraft。
