package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私人 FM 模式选择的回归测试。
 *
 * <p>同样分两半。<b>数值</b>一半钉住纯逻辑：官方文档「私人 FM 模式选择」列出的 42 个场景子模式
 * 必须全部登记、分组不重不漏、中文名齐全（含 {@code NIGHT_EMO} 是「伤感」而非「夜间」这一易错点），
 * 合法子模式原样透传、非法值回落到 {@code SCENE_RCMD} 声明的默认子模式，模式枚举必须含
 * {@code PUZZLE_MODE_RCMD}（拼图模式）。<b>接线</b>一半静态扫源码，钉住最容易在后续改动里
 * 被拆掉的三条数据流：子模式必须以 wire key {@code subMode}（大写 M，与 api-enhanced 的
 * {@code module/personal_fm_mode.js} 对齐）发给 {@code /api/v1/radio/get}；只有「默认模式且
 * 没有子模式」才允许走旧的 {@code personalFm()}，其余一律带模式请求；用户换模式必须立刻落盘、
 * 启动后第一次触碰选择必须先读盘。</p>
 *
 * <p>不启动 Minecraft、不联网。</p>
 */
class PersonalFmModeStaticTest {

    private static final Path MUSIC_DIR = ProjectPaths.sharedJava()
            .resolve(path("com/muoniumplayer/core/ncm/music"));
    private static final Path API_DIR = ProjectPaths.sharedJava()
            .resolve(path("com/muoniumplayer/core/ncm/api"));
    private static final Path SETTINGS_DIR = ProjectPaths.sharedJava()
            .resolve(path("com/muoniumplayer/core/settings"));

    private static final Path PANEL_DIR = ProjectPaths.sharedJava()
            .resolve(path("com/muoniumplayer/core/screens/ncm/panels"));

    /** 官方文档 home.md「私人 FM 模式选择」列出的全部场景子模式。 */
    private static final String[] OFFICIAL_SCENES = {
            "EXERCISE", "FOCUS", "NIGHT_EMO", "SLEEP_HELP", "RELAX", "CHEERFUL", "LYRICAL",
            "CURE", "SWEET", "RHYTHM_BLUES", "RAINY", "GAMES", "RAP", "K_POP",
            "ORIGINAL_MUSICIAL", "ELECTRONIC", "COMMUTE", "TAKE_SHOWER", "COFFEE_SHOP", "ROCK",
            "INSPIRATIONAL", "CHINESE", "ENGLISH", "YUEYU", "MANYAO", "JINGDIAN", "LIGHT",
            "GUOFENG", "FOLK", "ACG", "GUDIAN", "JAZZ", "JAPANESE", "GLOBAL", "FRANCH", "BLUE",
            "DANCE", "LATIN", "PUNK", "COUNTRY", "MUSICAL", "YINGSHI"
    };

    private static Path path(String relative) {
        return Path.of(relative.replace('/', java.io.File.separatorChar));
    }

    private static String read(Path dir, String fileName) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(fileName)), StandardCharsets.UTF_8);
    }

    private static Object normalize(String subMode) throws Exception {
        Method method = PersonalFmManager.class.getDeclaredMethod("normalizeSceneSubMode", String.class);
        method.setAccessible(true);
        return method.invoke(null, subMode);
    }

    @Test
    @DisplayName("官方文档的 42 个场景子模式全部登记且分组不重不漏")
    void allOfficialScenesRegistered() {
        assertEquals(OFFICIAL_SCENES.length, PersonalFmManager.sceneCount());
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (String[] group : PersonalFmManager.SCENE_GROUP_MODES) {
            for (String subMode : group) {
                assertTrue(seen.add(subMode), "场景子模式重复登记：" + subMode);
                assertFalse(PersonalFmManager.sceneLabel(subMode).isEmpty(), "场景缺少中文名：" + subMode);
                total++;
            }
        }
        assertEquals(OFFICIAL_SCENES.length, total, "分组展平后必须与官方文档数量一致");
        for (String subMode : OFFICIAL_SCENES) assertTrue(seen.contains(subMode), "官方场景未登记：" + subMode);
        assertEquals(PersonalFmManager.SCENE_GROUP_TITLES.length, PersonalFmManager.SCENE_GROUP_MODES.length,
                "分组标题数必须与分组数一致");
    }

    @Test
    @DisplayName("易错中文名按官方文档钉死")
    void sceneLabelsFollowOfficialNaming() {
        assertEquals("伤感", PersonalFmManager.sceneLabel(PersonalFmManager.SCENE_NIGHT_EMO));
        assertEquals("运动", PersonalFmManager.sceneLabel(PersonalFmManager.SCENE_EXERCISE));
        assertEquals("专注", PersonalFmManager.sceneLabel(PersonalFmManager.SCENE_FOCUS));
        assertEquals("", PersonalFmManager.sceneLabel(""));
        assertEquals("", PersonalFmManager.sceneLabel(null));
        assertEquals("", PersonalFmManager.sceneLabel("NOT_A_SCENE"));
    }

    @Test
    @DisplayName("官方子模式全部透传，非法值回落到模式声明的默认子模式")
    void sceneSubModeNormalization() throws Exception {
        String fallback = PersonalFmManager.Mode.SCENE_RCMD.getDefaultSubMode();
        for (String subMode : OFFICIAL_SCENES) assertEquals(subMode, normalize(subMode), subMode + " 必须原样透传");
        assertEquals(fallback, normalize(""));
        assertEquals(fallback, normalize(null));
        assertEquals(fallback, normalize("NOT_A_SCENE"));
    }

    @Test
    @DisplayName("模式枚举含拼图模式，且只有场景推荐带默认子模式")
    void modeEnumMatchesOfficialModes() {
        assertSame(PersonalFmManager.Mode.PUZZLE_MODE_RCMD, PersonalFmManager.Mode.valueOf("PUZZLE_MODE_RCMD"));
        assertEquals(PersonalFmManager.SCENE_FOCUS, PersonalFmManager.Mode.SCENE_RCMD.getDefaultSubMode());
        for (PersonalFmManager.Mode mode : PersonalFmManager.Mode.values()) {
            if (mode == PersonalFmManager.Mode.SCENE_RCMD) continue;
            assertEquals("", mode.getDefaultSubMode(), mode.name() + " 不应带默认子模式");
        }
    }

    @Test
    @DisplayName("选中场景推荐时，完整模式文案带出子模式短名")
    void selectionLabelReflectsScene() throws Exception {
        java.lang.reflect.Field modeField = field("selectedMode");
        java.lang.reflect.Field subField = field("selectedSubMode");
        Object oldMode = modeField.get(null);
        Object oldSub = subField.get(null);
        try {
            modeField.set(null, PersonalFmManager.Mode.SCENE_RCMD);
            subField.set(null, PersonalFmManager.SCENE_NIGHT_EMO);
            assertEquals("场景推荐 · 伤感", PersonalFmManager.getSelectionLabel());
            subField.set(null, "GUOFENG");
            assertEquals("场景推荐 · 国风", PersonalFmManager.getSelectionLabel());
            subField.set(null, "");
            assertEquals("场景推荐", PersonalFmManager.getSelectionLabel());
            modeField.set(null, PersonalFmManager.Mode.PUZZLE_MODE_RCMD);
            assertEquals("拼图模式", PersonalFmManager.getSelectionLabel());
        } finally {
            modeField.set(null, oldMode);
            subField.set(null, oldSub);
        }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field field = PersonalFmManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    @DisplayName("子模式必须以大写 M 的 subMode 发给 /api/v1/radio/get")
    void apiSendsCamelCaseSubModeKey() throws IOException {
        String api = read(API_DIR, "CloudMusicApi.java");
        assertTrue(api.contains("data.put(\"subMode\""), "CloudMusicApi 必须用 wire key subMode（对齐 api-enhanced 的 personal_fm_mode.js）");
        assertFalse(api.contains("data.put(\"submode\""), "小写 submode 会被网易云端忽略，场景子模式将全部等效");
        assertTrue(api.contains("\"/api/v1/radio/get\""), "模式请求必须仍打 /api/v1/radio/get");
    }

    @Test
    @DisplayName("只有默认模式且无子模式走旧接口，其余带模式请求；AIDJ 用小写 aidj")
    void fetchRecommendationsRouting() throws IOException {
        String manager = read(MUSIC_DIR, "PersonalFmManager.java");
        assertTrue(manager.contains("\"aidj\""), "AIDJ 模式的线上传参是小写 aidj，不是枚举名 AIDJ");
        assertTrue(manager.contains("CloudMusicApi.personalFm()"), "默认模式保留旧的 personalFm() 通路");
        assertTrue(manager.contains("CloudMusicApi.personalFmMode(apiMode, selectedSubMode, BATCH_SIZE)"),
                "其余模式必须走带模式参数的 personalFmMode()");
    }

    @Test
    @DisplayName("模式选择必须落盘并在首次触碰时读回")
    void modeSelectionIsPersisted() throws IOException {
        String manager = read(MUSIC_DIR, "PersonalFmManager.java");
        assertTrue(manager.contains("ConfigPaths.PERSONAL_FM_MODE"), "模式选择必须持久化到注册过的配置文件");
        String selectMode = manager.substring(manager.indexOf("public static void selectMode"),
                manager.indexOf("skipCurrentAndRequestNext"));
        assertTrue(selectMode.contains("ensureSettingsLoaded()"), "selectMode 前必须先读回磁盘选择，避免覆盖未加载的旧状态");
        assertTrue(selectMode.contains("persistSelection()"), "selectMode 必须立刻落盘");
        assertTrue(manager.contains("root.addProperty(\"subMode\""), "落盘格式的子模式键必须是 subMode");

        String configPaths = read(SETTINGS_DIR, "ConfigPaths.java");
        assertNotNull(configPaths);
        assertTrue(configPaths.contains("\"personal_fm_mode.json\""), "ConfigPaths 必须注册私人 FM 模式的持久化文件");
    }

    @Test
    @DisplayName("面板的完整模式文案来自 getSelectionLabel，场景区按分组渲染")
    void statusShowsFullSelection() throws IOException {
        String manager = read(MUSIC_DIR, "PersonalFmManager.java");
        assertTrue(manager.contains("getSelectionLabel() + \" · \""),
                "推荐成功/正在播放的状态文案必须带完整模式（含场景子模式）");

        String panel = read(PANEL_DIR, "PersonalFmPanel.java");
        assertTrue(panel.contains("PersonalFmManager.Mode.PUZZLE_MODE_RCMD"), "模式按钮行必须含拼图模式");
        assertTrue(panel.contains("SCENE_GROUP_TITLES"), "场景区必须按官方 42 项分组渲染，而不是只放三个按钮");
    }
}
