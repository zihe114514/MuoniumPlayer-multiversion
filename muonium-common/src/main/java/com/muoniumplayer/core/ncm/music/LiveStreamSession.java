package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.MpPartyClient;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Radio ownership and playback permissions. Each immutable active state has one player and
 * generation; old worker callbacks cannot clear or notify for a replacement station.
 * Player callbacks originate on its worker. UI notifications and optional public listeners
 * are dispatched to the main thread and checked again there before delivery.
 */
public final class LiveStreamSession {
    private LiveStreamSession() { }

    interface Host {
        String refusal();
        void stopOtherPlayback();
        float volume();
        LiveStreamPlayer create(String uri, String name, float volume, LiveStreamPlayer.Listener listener);
        void dispatch(Runnable callback);
        void started(String name);
        void failed(String name, String reason);
        void pauseChanged(String name, boolean paused);
    }

    private static final Controller SESSION = new Controller(new Host() {
        @Override public String refusal() {
            if (NeteaseListenTogetherService.isActive() || KuGouListenTogetherService.isActive() || MpPartyClient.inRoom()) {
                return "一起听房间中不能收听广播电台：房间播放以房主的队列为准";
            }
            if (ExternalPlayback.isMirroring()
                    || com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror.isMirroring()) {
                return "系统播放器镜像开启中，先关镜像才能收听广播电台";
            }
            return null;
        }
        @Override public void stopOtherPlayback() {
            CloudMusic.exitPersonalFmSessionForLiveStream();
            CloudMusic.stopExistingPlayThreadForLiveStream();
        }
        @Override public float volume() { return CloudMusic.getVolume(); }
        @Override public LiveStreamPlayer create(String uri, String name, float volume, LiveStreamPlayer.Listener listener) {
            return new LiveStreamPlayer(uri, name, volume, listener);
        }
        @Override public void dispatch(Runnable callback) { MultiThreadingUtil.runOnMainThread(callback); }
        @Override public void started(String name) {
            DownloadDynamicIsland.showModelOperationResult("广播电台", "正在收听「" + name + "」直播", true);
        }
        @Override public void failed(String name, String reason) { DownloadDynamicIsland.showPlaybackFailure(name, reason); }
        @Override public void pauseChanged(String name, boolean paused) {
            DownloadDynamicIsland.showModelOperationResult("广播电台", paused
                    ? "已暂停「" + name + "」（恢复时回到直播）" : "继续收听「" + name + "」", true);
        }
    });

    /** Returns after connection was scheduled, not after the stream or output was opened. */
    public static boolean start(Object station, String stationName, String playUrl, LiveStreamPlayer.Listener listenerOpt) {
        return SESSION.start(station, stationName, playUrl, listenerOpt);
    }
    public static boolean isActive() { return SESSION.isActive(); }
    public static boolean isPlaying() { return SESSION.isPlaying(); }
    public static Object activeStation() { return SESSION.activeStation(); }
    public static String activeStationName() { return SESSION.activeStationName(); }
    /** Session elapsed time; live media has no invented total duration and no seek. */
    public static long elapsedMillis() { return SESSION.elapsedMillis(); }
    public static boolean togglePause() { return SESSION.togglePause(); }
    public static void stop(String reason) { SESSION.stop(); }
    public static void applyVolume(float volume) { SESSION.applyVolume(volume); }
    public static void shutdown() { SESSION.stop(); }

    /** Native-free session coordinator; its host is injectable for deterministic lifecycle tests. */
    static final class Controller {
        private final Host host;
        private final AtomicReference<Active> active = new AtomicReference<>();
        private final AtomicLong generation = new AtomicLong();

        Controller(Host host) { this.host = host; }

        private record Active(long generation, Object station, String name, LiveStreamPlayer player) { }

        synchronized boolean start(Object station, String stationName, String uri, LiveStreamPlayer.Listener listenerOpt) {
            String name = stationName == null ? "" : stationName;
            String refusal = host.refusal();
            if (refusal != null) {
                host.failed(name, refusal);
                return false;
            }
            try { RadioStreamPolicy.uri(uri); }
            catch (IllegalArgumentException invalid) {
                host.failed(name, "这个频道暂时没有有效的 HTTP(S) 直播地址");
                return false;
            }
            host.stopOtherPlayback();
            stop();
            long id = generation.incrementAndGet();
            LiveStreamPlayer.Listener listener = new LiveStreamPlayer.Listener() {
                @Override public void onStarted(LiveStreamPlayer player) {
                    Active snapshot = active.get();
                    if (snapshot == null || snapshot.generation != id || snapshot.player != player) return;
                    host.dispatch(() -> {
                        if (active.get() != snapshot || player.isClosed() || player.isPaused()) return;
                        host.started(snapshot.name);
                        if (listenerOpt != null) listenerOpt.onStarted(player);
                    });
                }
                @Override public void onStreamEnded(LiveStreamPlayer player, String reason) {
                    Active snapshot = active.get();
                    if (snapshot == null || snapshot.generation != id || snapshot.player != player
                            || !active.compareAndSet(snapshot, null)) return;
                    host.dispatch(() -> {
                        if (generation.get() != id) return;
                        host.failed(snapshot.name, "直播中断：" + reason + "，可重新点击频道重试");
                        if (listenerOpt != null) listenerOpt.onStreamEnded(player, reason);
                    });
                }
            };
            LiveStreamPlayer player = host.create(uri, name, host.volume(), listener);
            Active next = new Active(id, station, name, player);
            player.setOnClosed(() -> active.compareAndSet(next, null));
            active.set(next);
            player.start();
            return true;
        }

        boolean isActive() {
            Active snapshot = active.get();
            return snapshot != null && !snapshot.player.isClosed();
        }
        boolean isPlaying() {
            Active snapshot = active.get();
            return snapshot != null && snapshot.player.isPlaying();
        }
        Object activeStation() {
            Active snapshot = active.get();
            return snapshot == null ? null : snapshot.station;
        }
        String activeStationName() {
            Active snapshot = active.get();
            return snapshot == null ? "" : snapshot.name;
        }
        long elapsedMillis() {
            Active snapshot = active.get();
            return snapshot == null || snapshot.player.isClosed() ? 0L : snapshot.player.elapsedMillis();
        }
        boolean togglePause() {
            Active snapshot = active.get();
            if (snapshot == null || snapshot.player.isClosed()) return false;
            boolean paused = !snapshot.player.isPaused();
            if (paused) snapshot.player.pause(); else snapshot.player.resume();
            host.dispatch(() -> {
                if (active.get() == snapshot && !snapshot.player.isClosed()) host.pauseChanged(snapshot.name, paused);
            });
            return true;
        }
        synchronized void stop() {
            generation.incrementAndGet();
            Active previous = active.getAndSet(null);
            if (previous != null) previous.player.close();
        }
        void applyVolume(float volume) {
            Active snapshot = active.get();
            if (snapshot != null) snapshot.player.setVolume(volume);
        }
    }
}
