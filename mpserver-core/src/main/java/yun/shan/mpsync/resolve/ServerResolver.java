package yun.shan.mpsync.resolve;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.util.SourceGuard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 服务端代解析：拿服主配置的平台凭据自己签一条直链，成功后像成员上传的链接一样喂回房间。
 *
 * <p><b>它解决什么问题。</b>房间共用点歌人那一条直链，所以「非会员点会员曲」原本必须靠房间里
 * 恰好有个会员成员应答。服主配了 Token 之后这条依赖就没了 —— 而且玩家侧什么都不用做，也不需要
 * 谁把自己的账号交出去。
 *
 * <p><b>线程。</b>HTTP 一定不能在服务器主线程上发（一次超时就是几秒的卡服）。所以这里有自己的
 * 守护线程池，解析完成后通过 {@code hop}（由适配层给的 {@code MpPlatform::execute}）回到主线程
 * 再动房间状态。
 *
 * <p><b>去重。</b>同一首歌在极短时间里可能被问好几次（换歌时的 {@code url_needed}、队列预解析、
 * 过期续签），每次都发一遍请求既慢又容易撞风控。所以按 {@code source:songId} 记在飞的请求。
 */
public final class ServerResolver {

    /** 解析成功后的落地口，由房间层提供（内部会走一次主线程 hop）。 */
    @FunctionalInterface
    public interface Sink {
        void supply(String source, String songId, String url, long expiresAt, long servedMs);
    }

    private final MpConfig cfg;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mpsync-resolve");
        t.setDaemon(true);      // 守护线程：服务器关停时不该被一个挂住的 HTTP 请求拖着
        return t;
    });
    /** {@code source:songId -> 发起时刻}，用于去重。 */
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();
    private volatile Sink sink;
    private volatile java.util.function.Consumer<Runnable> hop = Runnable::run;

    public ServerResolver(MpConfig cfg) {
        this.cfg = cfg;
    }

    public void bind(Sink sink, java.util.function.Consumer<Runnable> mainThreadHop) {
        this.sink = sink;
        if (mainThreadHop != null) this.hop = mainThreadHop;
    }

    /** 这个平台配了凭据或解析接口吗。 */
    public boolean enabled(String source) {
        return cfg != null && cfg.hasServerToken(source);
    }

    /** 服主到底配了哪些平台（写进 hello，客户端据此告诉玩家「这台服务器能代解析」）。 */
    public boolean anyEnabled() {
        if (cfg == null) return false;
        if (cfg.serverTokens != null) {
            for (String v : cfg.serverTokens.values()) if (v != null && !v.isBlank()) return true;
        }
        if (cfg.resolverEndpoints != null) {
            for (String v : cfg.resolverEndpoints.values()) if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    /**
     * 异步解析一条直链。没配这个平台、或同一首歌已经在解析中，直接返回（不是错误）。
     */
    public void request(String source, String songId, String name) {
        String src = NeteaseTokenApi.lower(source);
        if (src.isEmpty()) src = Proto.SOURCE_NETEASE;
        if (songId == null || songId.isBlank() || !enabled(src)) return;
        String key = src + ":" + songId;
        long now = System.currentTimeMillis();
        Long started = inFlight.get(key);
        // 在飞的请求超过超时时间还没回，就允许再发一次 —— 否则一次卡死的请求会永久堵住这首歌。
        if (started != null && now - started < cfg.serverResolveTimeoutMs) return;
        inFlight.put(key, now);

        final String fSrc = src;
        final String fId = songId.trim();
        pool.submit(() -> {
            try {
                Resolved r = resolve(fSrc, fId);
                if (r == null || r.url == null || r.url.isBlank()) return;
                // 只放行公网 http(s)：解析接口是服主指的，但它返回什么不受服主控制，
                // 不能让房间把全房客户端指向内网地址。
                if (!SourceGuard.isPublicHttpUrl(r.url)) return;
                Sink s = sink;
                if (s == null) return;
                hop.accept(() -> s.supply(fSrc, fId, r.url,
                        System.currentTimeMillis() + cfg.assumedUrlLifetimeMs, r.servedMs));
            } catch (Throwable ignored) {
                // 解析失败不是错误：房间照旧会去问客户端。
            } finally {
                // 只清自己放的那一笔：若本次解析耗时跨过了 serverResolveTimeoutMs，期间可能已有
                // 第二次请求判定「在飞的超时了」而重新 put 了一个更新的时刻并提交了新任务。无条件
                // remove 会把那一笔抹掉，于是后续请求又不去重、再提交 —— 几个任务并发在跑，更容易
                // 撞平台风控。remove(key, now) 只在值仍是本任务登记的时刻时才删，天然避开这一幕。
                inFlight.remove(key, now);
            }
        });
    }

    public void shutdown() {
        inFlight.clear();
        pool.shutdownNow();
        try {
            pool.awaitTermination(200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record Resolved(String url, long servedMs) {}

    private Resolved resolve(String source, String songId) throws Exception {
        String token = cfg.serverTokens == null ? null : cfg.serverTokens.get(source);
        String template = cfg.resolverEndpoints == null ? null : cfg.resolverEndpoints.get(source);

        // 模板优先：服主显式指了接口，说明他不想走内置那条。
        if (template != null && !template.isBlank()) {
            String url = template
                    .replace("{songId}", songId)
                    .replace("{id}", songId)
                    .replace("{source}", source)
                    .replace("{token}", token == null ? "" : token);
            String body = NeteaseTokenApi.get(url, cfg.serverResolveTimeoutMs);
            return new Resolved(pickUrl(body), 0L);
        }
        if (Proto.SOURCE_NETEASE.equals(source) && token != null && !token.isBlank()) {
            NeteaseTokenApi.Stream st = NeteaseTokenApi.songUrl(token, songId, cfg.serverResolveTimeoutMs);
            return new Resolved(st.url(), st.servedMs());
        }
        return null;
    }

    /**
     * 从解析接口的响应里挑出那条地址。
     *
     * <p>纯文本就是它本身；JSON 依次找 {@code url}、{@code data.url}、{@code data[0].url}
     * —— 这三种是各家自建解析服务里最常见的形状。都没有就当解析失败。
     */
    static String pickUrl(String body) {
        if (body == null) return "";
        String t = body.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("http://") || t.startsWith("https://")) {
            // 纯文本响应可能带尾随换行或引号。
            int end = t.length();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c <= ' ' || c == '"' || c == '\'') { end = i; break; }
            }
            return t.substring(0, end);
        }
        try {
            JsonElement el = JsonParser.parseString(t);
            if (el == null || !el.isJsonObject()) return "";
            JsonObject o = el.getAsJsonObject();
            String direct = Proto.str(o, "url", "");
            if (!direct.isBlank()) return direct;
            JsonObject data = Proto.obj(o, "data");
            if (data != null) {
                String nested = Proto.str(data, "url", "");
                if (!nested.isBlank()) return nested;
            }
            JsonArray arr = Proto.arr(o, "data");
            if (arr != null && !arr.isEmpty() && arr.get(0).isJsonObject()) {
                return Proto.str(arr.get(0).getAsJsonObject(), "url", "");
            }
        } catch (Throwable ignored) {
            // 不是 JSON 也不是裸 URL —— 解析失败。
        }
        return "";
    }
}