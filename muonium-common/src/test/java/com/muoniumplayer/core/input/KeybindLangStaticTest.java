package com.muoniumplayer.core.input;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 七棵树的按键绑定翻译契约。
 *
 * <p>按键列表里的标题来自语言文件，缺一条原版就把翻译键原样画出来——玩家看到的是
 * {@code key.muonium.ambient_now} 这种「像类名」的串，而不是「立即按环境切一首」。
 * 这类问题不会让构建失败，只有人坐进「选项 → 控制」才看得见，所以钉在这里。
 *
 * <p>分类标题另有一条代际差异，且**复数形式是错的**：1.20.1 / 1.21.1 的
 * {@code KeyMapping} 直接收一个分类翻译键字符串（{@code key.categories.muonium}），
 * 由原版对整串做翻译；1.21.9 起换成了 {@code KeyMapping.Category} 记录，标题由
 * {@code Category.label()} 按 {@code Identifier.toLanguageKey("key.category")} 拼出
 * {@code key.category.<namespace>.<path>}——单数，且命名空间与路径都在键里。
 * 写成 {@code key.categories.muonium.player} 永远命中不了，分组标题就会显示成键名本身。
 * 这条错误曾经一字不差地写进四棵树的注释里，连注释一起钉住。
 */
class KeybindLangStaticTest {

    /** 各版本树的名字。新树进来要同时补这里与 SHARE-MANIFEST。 */
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3"
    };

    /** {@code key("key.muonium.xxx", ...)} —— createKeyMappings 的注册口。 */
    private static final Pattern REGISTERED_KEY =
            Pattern.compile("key\\(\"(key\\.muonium\\.[a-z0-9_]+)\"");

    @Test
    @DisplayName("每棵树注册的每个按键，en_us / zh_cn 都有翻译")
    void everyRegisteredKeyIsTranslated() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        List<String> missing = new ArrayList<String>();
        for (String tree : TREES) {
            Set<String> keys = registeredKeys(repo, tree);
            assertFalse(keys.isEmpty(), tree + " 的 createKeyMappings 里一个 key() 都没解析到，"
                    + "注册写法变了吗？");
            for (String locale : new String[]{"en_us.json", "zh_cn.json"}) {
                String lang = read(langFile(repo, tree, locale));
                for (String key : keys) {
                    if (!lang.contains("\"" + key + "\"")) {
                        missing.add(tree + " / " + locale + " 缺 " + key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "注册了按键却没有翻译，游戏里会直接显示翻译键：\n"
                + String.join("\n", missing));
    }

    @Test
    @DisplayName("分类标题键与该代 KeyMapping API 推导出的键一致")
    void categoryKeyMatchesItsGeneration() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        List<String> problems = new ArrayList<String>();
        for (String tree : TREES) {
            String client = read(clientFile(repo, tree));
            boolean recordCategory = client.contains("KeyMapping.Category.register");
            for (String locale : new String[]{"en_us.json", "zh_cn.json"}) {
                String lang = read(langFile(repo, tree, locale));
                if (recordCategory) {
                    // Category.label() = Component.translatable(id.toLanguageKey("key.category"))
                    if (!lang.contains("\"key.category.muonium.player\"")) {
                        problems.add(tree + " / " + locale
                                + " 缺 key.category.muonium.player（单数，Category.label() 只会查这个）");
                    }
                    if (lang.contains("\"key.categories.muonium")) {
                        problems.add(tree + " / " + locale
                                + " 还留着旧式 key.categories.muonium*（本代按 key.category 单数推导，永不命中）");
                    }
                } else if (!lang.contains("\"key.categories.muonium\"")) {
                    problems.add(tree + " / " + locale
                            + " 缺 key.categories.muonium（本代分类标题就是这串，原版直接拿去翻译）");
                }
            }
        }
        assertTrue(problems.isEmpty(), "分类标题会显示成翻译键本身：\n" + String.join("\n", problems));
    }

    // ------------------------------------------------------------------ 工具

    /** 从本树的 createKeyMappings 里抠出所有注册的翻译键。 */
    private static Set<String> registeredKeys(Path repo, String tree) throws IOException {
        String source = read(clientFile(repo, tree));
        Set<String> keys = new LinkedHashSet<String>();
        Matcher matcher = REGISTERED_KEY.matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static Path clientFile(Path repo, String tree) {
        return repo.resolve(Paths.get(tree, "src", "main", "java",
                "com", "muoniumplayer", "MuoniumPlayerClient.java"));
    }

    private static Path langFile(Path repo, String tree, String locale) {
        return repo.resolve(Paths.get(tree, "src", "main", "resources",
                "assets", "muonium", "lang", locale));
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "文件不存在: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
