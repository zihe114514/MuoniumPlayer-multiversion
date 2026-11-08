package com.muoniumplayer.core.ncm.music;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;

/**
 * Simple Voice Chat 客户端插件入口——「语音广播」的注册面。
 *
 * <p>三种加载器一份类通吃：SVC 在 Fabric 用 {@code fabric.mod.json} 的 {@code voicechat}
 * entrypoint 发现本类；在 Forge / NeoForge 扫 {@link ForgeVoicechatPlugin} 注解。SVC 没装时
 * 没人来实例化本类，它连加载都不会发生；SVC 装了但初始化失败也只是它自己 warn 一条日志
 * （{@code PluginManager} 逐插件 try/catch），带不垮任何一方。</p>
 *
 * <p>「语音广播」上行注入用的是 {@link MergeClientSoundEvent}：SVC 麦克风线程每 20ms 采一帧
 * 上行音频就派发一次（<b>麦克风静音、未触发语音激活也照派</b>，语音聊天在设置里禁用才不派），
 * 回调里 {@code mergeAudio(short[960])} 把音乐帧合并进那一帧——之后 Opus 编码、以玩家位置
 * 做位置语音分发全是 SVC 自己的事。定拍、编码、发包都借麦克风线程的节拍，本 mod 不设线程。</p>
 *
 * <p>本类是共享层<b>唯一</b> import {@code de.maxhenkel.voicechat.api} 的文件
 * （{@code VoicechatBroadcastStaticTest} 钉着）：SVC API 的编译类路径来自各树
 * {@code libs/voicechat-api-*.jar}，运行期类由 SVC mod 自己提供。</p>
 *
 * <p>已知边界（写进设置文案与 docs/实现约定-语音广播.md）：没有麦克风的玩家 SVC 起不了
 * 麦克风线程，事件不来，广播不出声；电台/直播（{@link LiveStreamPlayer}）绕过引擎不广播。</p>
 */
@ForgeVoicechatPlugin
public final class MuoniumVoicechatPlugin implements VoicechatPlugin {

    /** 插件 id：SVC 要求唯一。 */
    public static final String PLUGIN_ID = "muonium_music";

    /**
     * SVC 用 {@code getDeclaredConstructor().newInstance()} 反射实例化插件（无参构造、
     * 不 setAccessible），这个构造器必须保持 public——util 类惯用的 private 构造在这里会炸。
     */
    public MuoniumVoicechatPlugin() {
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // 不需要任何 API 对象：注入只靠 MergeClientSoundEvent 的回调参数。
        // 这里也不挂音频采集——那发生在 AudioPlayer.loadUsableSoundFile（引擎存在的时机）。
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MergeClientSoundEvent.class, MuoniumVoicechatPlugin::onMicFrame);
    }

    /** 麦克风线程每 20ms 一次。取到一帧就合并进当前帧；没数据（积压空了）本帧不动。 */
    private static void onMicFrame(MergeClientSoundEvent event) {
        short[] frame = VoicechatBroadcast.pollFrame();
        if (frame != null) {
            event.mergeAudio(frame);
        }
    }
}
