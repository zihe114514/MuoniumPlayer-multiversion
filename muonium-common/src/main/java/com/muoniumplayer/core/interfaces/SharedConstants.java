package com.muoniumplayer.core.interfaces;

import com.muoniumplayer.api.Extension;
import com.muoniumplayer.api.OpenAPI;

/**
 * 各类之间共享的通用常量。
 *
 * 原项目对应 {@code com.muoniumplayer.api.OpenAPI api = ExtensionEntry.getAPI();}，
 * 此处经 com.muoniumplayer.api.Extension.getAPI() 适配到本 Mod 的 OpenAPI 单例。
 * 注意：OpenAPI 单例在 {@code MuoniumPlayerMod.preInit} 注入，首次加载本接口的实现类
 * （CloudMusic / 各 Widget）必须晚于 preInit，否则 api 会捕获为 null。
 */
public interface SharedConstants {

    OpenAPI api = Extension.getAPI();

}
