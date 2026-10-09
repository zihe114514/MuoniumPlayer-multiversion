package com.muoniumplayer.core.viper;

/**
 * 字段值类型的公开视图（调参界面用）。
 *
 * <p>{@link ViperPresetSchema} 是包级私有，界面拿不到它的 {@code Kind}；本枚举把同一组
 * 类型一对一映射成公开常量，映射关系由 {@link ViperPresetEditor} 维护。</p>
 */
public enum ViperFieldKind {
    BOOL, INT, FLOAT, STRING, NULLABLE_LONG,
    INT_ARRAY, FLOAT_ARRAY, BOOL_ARRAY, DOUBLE_ARRAY;

    static ViperFieldKind of(ViperPresetSchema.Kind kind) {
        switch (kind) {
            case BOOL: return BOOL;
            case INT: return INT;
            case FLOAT: return FLOAT;
            case STRING: return STRING;
            case NULLABLE_LONG: return NULLABLE_LONG;
            case INT_ARRAY: return INT_ARRAY;
            case FLOAT_ARRAY: return FLOAT_ARRAY;
            case BOOL_ARRAY: return BOOL_ARRAY;
            case DOUBLE_ARRAY: return DOUBLE_ARRAY;
            default: return STRING;
        }
    }

    /** 是否为数组类型（界面按只读处理）。 */
    public boolean isArray() {
        return this == INT_ARRAY || this == FLOAT_ARRAY || this == BOOL_ARRAY || this == DOUBLE_ARRAY;
    }
}
