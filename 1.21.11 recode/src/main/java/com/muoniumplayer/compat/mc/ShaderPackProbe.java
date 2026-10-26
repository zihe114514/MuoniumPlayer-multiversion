package com.muoniumplayer.compat.mc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 3D 世界歌词的光影包探测 —— Iris / Oculus 的官方 API 桥。
 *
 * <h2>为什么需要探测</h2>
 * 光影包激活时，Iris/Oculus 会拦截 {@code getRendertypeTextSeeThroughShader}，
 * 把原版文字程序替换成包里的 {@code gbuffers_entities_translucent}（实体打光程序）：
 * 对歌词重算方向光照、采样阴影图、乘夜间场景光 —— 而我们的字形顶点没有法线，
 * 光照数学拿到未定义值，实测表现为黑字/忽明忽暗；BSL 还会把纯白压暗 20%。
 * 探测到光影包激活时，渲染层改走信标光柱程序（全亮、不受光照图、无 alpha 裁剪，
 * 是原版唯一的「自发光」语义），观感在光影下保持稳定。
 *
 * <h2>探测方式</h2>
 * Iris 与 Oculus（1.20.1 的 Iris fork）自带同一个 {@code net.irisshaders.iris.api.v0.IrisApi}，
 * {@code isShaderPackInUse()} 即官方文档推荐的「光影包是否在用」判据（包编译失败也算未用）。
 * API 类不在编译类路径上 —— 全程反射 MethodHandle，缓存后每帧一次调用零分配。
 * 不装 Iris/Oculus 时探测结果是常量 false，热路径只有一次 volatile 读。
 *
 * <p>探测时机：{@link #refresh()} 在世界歌词渲染的帧头调用（每帧一次），
 * {@link #isActive()} 是本帧缓存值。用户装上/卸下光影包后下一帧自然跟上。</p>
 */
public final class ShaderPackProbe {

    private static volatile boolean active;
    private static volatile boolean probed;

    /** 惰性解析的 API 调用柄；null = 没装 Iris/Oculus 或解析失败（不再重试）。 */
    private static volatile MethodHandle packInUse;
    private static boolean resolved;

    private ShaderPackProbe() {
    }

    /**
     * 帧头刷新：把「当前光影包是否在渲染」解析进本帧缓存。
     * 每帧最多一次反射调用；未安装光影加载器时是纯 volatile 读。
     */
    public static void refresh() {
        active = probePackInUse();
        probed = true;
    }

    /** 本帧光影包是否在用（{@link #refresh()} 之后读；未刷新过时现场探测一次）。 */
    public static boolean isActive() {
        if (!probed) {
            active = probePackInUse();
            probed = true;
        }
        return active;
    }

    private static boolean probePackInUse() {
        MethodHandle handle = resolve();
        if (handle == null) {
            return false;
        }
        try {
            Object api = invokeGetInstance();
            if (api == null) {
                return false;
            }
            return (boolean) handle.invoke(api);
        } catch (Throwable t) {
            // API 面变化（Iris 大版本改动）—— 永久降级为「无光影」，别每帧炸异常。
            packInUse = null;
            return false;
        }
    }

    private static Object invokeGetInstance() throws Throwable {
        MethodHandle getInstance = getInstanceHandle;
        if (getInstance == null) {
            return null;
        }
        return getInstance.invoke();
    }

    private static volatile MethodHandle getInstanceHandle;

    private static MethodHandle resolve() {
        if (resolved) {
            return packInUse;
        }
        synchronized (ShaderPackProbe.class) {
            if (resolved) {
                return packInUse;
            }
            resolved = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                getInstanceHandle = lookup.findStatic(api, "getInstance",
                        MethodType.methodType(api));
                packInUse = lookup.findVirtual(api, "isShaderPackInUse",
                        MethodType.methodType(boolean.class));
            } catch (Throwable t) {
                packInUse = null;
            }
        }
        return packInUse;
    }
}
