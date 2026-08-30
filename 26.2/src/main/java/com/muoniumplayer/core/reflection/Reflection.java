package com.muoniumplayer.core.reflection;

import today.opai.api.OpenAPI;

/**
 *
 * 且独立 Mod 的歌词由 MusicLyricsPanel 渲染。故此处按 CLAUDE.md §14 决策树归为 E（可删除）：
 * DYNAMIC_ISLAND_SUPPORTED 恒为 false，init 为空操作。对外只保留原项目实际引用的两个静态成员。
 */
public class Reflection {

    public static boolean DYNAMIC_ISLAND_SUPPORTED = false;

    /** 原项目注册一个 10 帧后异步执行 initDynIsland 的事件处理器；独立 Mod 无需任何动作。 */
    public static void init(OpenAPI api) {
    }
}
