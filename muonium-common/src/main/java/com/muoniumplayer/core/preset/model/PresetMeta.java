package com.muoniumplayer.core.preset.model;

/**
 * 预设的元信息。全是给人看的文字，不参与任何套用逻辑；{@code generator} 只做诊断，不做门禁。
 *
 * <p>长度与字符在 {@code PresetJson} 读入时已经截断、去掉控制字符，这里不再重复校验。</p>
 */
public final class PresetMeta {

    private final String id;
    private final String name;
    private final String author;
    private final String description;
    private final String created;
    private final String generatorMod;
    private final String generatorMc;

    public PresetMeta(String id, String name, String author, String description, String created,
                      String generatorMod, String generatorMc) {
        this.id = nz(id);
        this.name = nz(name);
        this.author = nz(author);
        this.description = nz(description);
        this.created = nz(created);
        this.generatorMod = nz(generatorMod);
        this.generatorMc = nz(generatorMc);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** {@code builtin:<slug>} / {@code user:<uuid>} / {@code backup:<时间戳>}。 */
    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String author() {
        return author;
    }

    public String description() {
        return description;
    }

    /** ISO-8601 时间（UTC），可能为空。 */
    public String created() {
        return created;
    }

    /** 生成这份预设的 mod 版本，可能为空。 */
    public String generatorMod() {
        return generatorMod;
    }

    /** 生成这份预设的 Minecraft 版本，可能为空。 */
    public String generatorMc() {
        return generatorMc;
    }

    public PresetMeta withId(String newId) {
        return new PresetMeta(newId, name, author, description, created, generatorMod, generatorMc);
    }

    public PresetMeta withName(String newName) {
        return new PresetMeta(id, newName, author, description, created, generatorMod, generatorMc);
    }
}
