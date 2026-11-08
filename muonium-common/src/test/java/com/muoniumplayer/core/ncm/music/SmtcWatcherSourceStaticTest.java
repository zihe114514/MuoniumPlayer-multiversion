package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMTC 助手 C# 源码的静态守护。
 *
 * <p>那份源码在用户机上用系统自带的 csc.exe（C# 5）现场编译，任何 C# 6+ 语法都会
 * 表现为「用户开启镜像时编译失败」，而现场只有一行 csc 的中文乱码报错。与其等
 * 真机炸，不如在 CI 里把语法门槛钉死。协议形状的关键标记也一起钉：这些字符串
 * 是 Java 侧解析与 SmtcTooling 引用清单的契约，改名字必须两边一起改。</p>
 */
public class SmtcWatcherSourceStaticTest {

    private static String source() throws Exception {
        InputStream stream = SmtcWatcherSourceStaticTest.class
                .getResourceAsStream("/muonium/external/SmtcWatcher.cs");
        assertTrue(stream != null, "jar 里必须带着 muonium/external/SmtcWatcher.cs（打包漏了 = 用户机上 NO_SOURCE）");
        try (InputStream input = stream) {
            byte[] bytes = new byte[256 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(bytes, total, bytes.length - total)) > 0) {
                total += read;
                if (total == bytes.length) break;
            }
            return new String(bytes, 0, total, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void staysWithinCSharpFiveSyntax() throws Exception {
        // 只查代码不查注释：头注释里那句「不允许 $""、?.、=>」自己就带着这些字符。
        String code = stripComments(source());
        // csc.exe（C# 5）编不出来的语法特征。都不是风格偏好，是编译门槛。
        assertFalse(code.contains("$\""), "插值字符串是 C# 6 语法");
        assertFalse(code.contains("?. "), "空条件运算符是 C# 6 语法");
        assertFalse(code.contains("?.[") , "空条件索引是 C# 6 语法");
        assertFalse(code.contains("=> "), "表达式体成员是 C# 6/7 语法；连 lambda 都不用，留给 delegate");
        assertFalse(code.contains("nameof("), "nameof 是 C# 6 语法");
        assertFalse(code.contains("out var "), "内联 out 声明是 C# 7 语法");
        assertFalse(code.contains("out MSG msg"), "内联 out 声明是 C# 7 语法（发布线程踩过）");
    }

    /** 剥掉块注释与行注释。这份源码里没有字符串字面量携带注释形状的字符。 */
    private static String stripComments(String source) {
        String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder lineComments = new StringBuilder(withoutBlocks.length());
        for (String line : withoutBlocks.split("\n", -1)) {
            int marker = line.indexOf("//");
            lineComments.append(marker >= 0 ? line.substring(0, marker) : line).append('\n');
        }
        return lineComments.toString();
    }

    @Test
    public void mirrorSideProtocolMarkersArePresent() throws Exception {
        String source = source();
        // 多会话选择与事件驱动是 v2 的两根支柱；名字动了一条，Java 侧的解析与
        // SmtcTooling 的引用清单就都得跟着动。
        assertTrue(source.contains("GetSessions()"), "多会话枚举（v1 只有 GetCurrentSession）");
        assertTrue(source.contains("GetCurrentSession()"), "自动挑选的最后一级回退");
        assertTrue(source.contains("CurrentSessionChanged += "), "事件驱动：管理器事件");
        assertTrue(source.contains("MediaPropertiesChanged += "), "事件驱动：换歌感知");
        assertTrue(source.contains("System.Runtime.InteropServices.WindowsRuntime"),
                "事件投影依赖的 GAC 程序集（SmtcTooling 的引用清单也要带上它）");
        assertTrue(source.contains("LastUpdatedTime"), "时间轴外推锚点");
        assertTrue(source.contains("\"event\":\"pubcmd\""), "发布端媒体键回传");
        assertTrue(source.contains("\"v\":2"), "状态行版本标记");
    }

    @Test
    public void publishSideInteropIsTheDocumentedOne() throws Exception {
        String source = source();
        // ISystemMediaTransportControlsInterop 的 IID 抄自 SDK 头
        // （systemmediatransportcontrolsinterop.h，mingw-w64 镜像可核对）。
        // 历史教训：凭记忆写 COM 的 IID 全错过一遍，所以这里钉死。
        assertTrue(source.contains("DDB0472D-C911-4A1F-86D9-DC3D71A95F5A"),
                "ISystemMediaTransportControlsInterop 的 IID");
        assertTrue(source.contains("GetForWindow"), "桌面进程接入 SMTC 的唯一正路");
        assertTrue(source.contains("RoGetActivationFactory"), "激活工厂路径");
        assertTrue(source.contains("\"Windows.Media.SystemMediaTransportControls\""),
                "激活的运行类名");
    }
}
