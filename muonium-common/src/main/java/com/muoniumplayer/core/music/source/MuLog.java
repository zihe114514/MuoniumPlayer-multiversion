package com.muoniumplayer.core.music.source;

/**
 * 内置音源层日志出口。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>宿主（MuoniumPlayer）启动时通过 {@link MuLog#setLogger} 把输出桥到 mod 自己的日志；
 * 默认实现静默。</p>
 */
public final class MuLog {
    private static volatile Logger logger = new Logger() {
    };

    private MuLog() {
    }

    public interface Logger {
        default void debug(String msg) {
        }

        default void info(String msg) {
        }

        default void warn(String msg) {
        }

        default void error(String msg, Throwable t) {
        }
    }

    public static Logger getLogger() {
        return logger;
    }

    public static void setLogger(Logger replacement) {
        if (replacement != null) logger = replacement;
    }

    public static void debug(String msg) {
        logger.debug(msg);
    }

    public static void info(String msg) {
        logger.info(msg);
    }

    public static void warn(String msg) {
        logger.warn(msg);
    }

    public static void error(String msg, Throwable t) {
        logger.error(msg, t);
    }
}
