package com.muoniumplayer.core.preset.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次预检 / 套用的兼容报告：给玩家看的提示都在这里，没有一条是静默吞掉的。
 *
 * <p>预检与真正套用走同一条代码路径（只差最后写不写），所以「套用前看到的提示」和「套用后实际发生的」
 * 不会对不上。</p>
 */
public final class CompatReport {

    private final Set<String> warnings = new LinkedHashSet<String>();
    private final List<String> failures = new ArrayList<String>();
    private final Set<String> touchedDomains = new LinkedHashSet<String>();
    private int unsupportedKeys;
    private int unsupportedValues;
    private int changedKeys;

    /** 一条提示（同一句只记一次）。 */
    public void warn(String message) {
        if (message != null && !message.trim().isEmpty()) {
            warnings.add(message.trim());
        }
    }

    /** 本版本不认识的键：来自更新的版本，或别人手改的。 */
    public void unsupportedKey(String domain, String key) {
        unsupportedKeys++;
    }

    /** 键认识、值不支持（枚举下标超出本地上限、类型不对）：保留当前值。 */
    public void unsupportedValue(String domain, String key) {
        unsupportedValues++;
    }

    /** 某个配置域套用时抛了异常：其余域照常，失败的这一个原样保留。 */
    public void failure(String domainLabel, Throwable error) {
        String reason = error == null ? "未知错误"
                : (error.getMessage() == null || error.getMessage().isEmpty()
                ? error.getClass().getSimpleName() : error.getMessage());
        failures.add(domainLabel + "套用失败：" + reason);
    }

    /** 某个配置域会被（或已被）改动了多少个键。 */
    public void changed(String domain, int keys) {
        if (keys > 0) {
            touchedDomains.add(domain);
            changedKeys += keys;
        }
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public List<String> failures() {
        return Collections.unmodifiableList(failures);
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public int unsupportedKeys() {
        return unsupportedKeys;
    }

    public int unsupportedValues() {
        return unsupportedValues;
    }

    public int changedKeys() {
        return changedKeys;
    }

    public Set<String> touchedDomains() {
        return Collections.unmodifiableSet(touchedDomains);
    }

    /** 全部提示（失败在前），给界面逐行显示。 */
    public List<String> lines() {
        List<String> lines = new ArrayList<String>(failures);
        int skipped = unsupportedKeys + unsupportedValues;
        if (skipped > 0) {
            lines.add(skipped + " 项设置这个版本不支持，已忽略（多半来自更新的 MuoniumPlayer）");
        }
        lines.addAll(warnings);
        return lines;
    }
}
