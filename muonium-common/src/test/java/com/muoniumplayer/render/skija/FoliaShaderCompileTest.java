package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.RuntimeEffect;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SkSL 语法闸门：把 {@code shaders/folia/*.sksl} 逐个交给 Skia 编译。
 *
 * <p>单独一个测试类而不是塞进栅格测试，是因为着色器语法错误在宿主里是「静默兜底」——
 * 画出一块纯色，日志里只有一行警告，非常容易被当成「参数没生效」。这一层把「能不能编译」
 * 与「画得对不对」分开：这里失败就是语法或 uniform 声明的问题，与像素无关。</p>
 */
class FoliaShaderCompileTest {

    @Test
    void allFoliaSkSLCompile() throws Exception {
        for (String name : new String[]{"latent.sksl", "nomand.sksl", "fluid.sksl"}) {
            String source = read(name);
            try {
                RuntimeEffect effect = RuntimeEffect.makeForShader(source);
                assertNotNull(effect, name + " 编译返回 null");
                effect.close();
            } catch (Throwable failure) {
                fail(name + " 编译失败: " + failure.getMessage());
            }
        }
    }

    private static String read(String name) throws Exception {
        try (InputStream in = FoliaShaderCompileTest.class.getResourceAsStream("/shaders/folia/" + name)) {
            assertNotNull(in, "缺少资源 /shaders/folia/" + name);
            StringBuilder source = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return source.toString();
        }
    }
}

