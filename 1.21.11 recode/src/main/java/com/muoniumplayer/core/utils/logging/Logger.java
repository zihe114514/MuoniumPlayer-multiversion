package com.muoniumplayer.core.utils.logging;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

@Getter
// 此日志系统未实现 TRACE 级别
public class Logger {

    private final String name;

    @Getter
    @Setter
    private LogLevel overrideLevel = null;

    public Logger() {
        this(Thread.currentThread().getName());
    }

    @SneakyThrows
    public Logger(String name) {
        this.name = name;
    }

    public void debug(String message, Object... objects) {
        LogManager.print(this, LogLevel.DEBUG, LogManager.parse(message, objects), name);
    }

    public void info(String message, Object... objects) {
        LogManager.print(this, LogLevel.INFO, LogManager.parse(message, objects), name);
    }

    public void warn(String message, Object... objects) {
        LogManager.print(this, LogLevel.WARN, LogManager.parse(message, objects), name);
    }

    public void error(Throwable t) {
        this.error(t.getMessage(), t);
    }

    public void error(String message, Object... objects) {
        LogManager.print(this, LogLevel.ERROR, LogManager.parse(message, objects), name);
    }

    public void fatal(Throwable t) {
        this.fatal(t.getMessage(), t);
    }

    public void fatal(String message, Object... objects) {
        LogManager.print(this, LogLevel.CRITICAL, LogManager.parse(message, objects), name);
    }

    public boolean isDebugEnabled() {
        return LogManager.getDefaultLogLevel().getLevel() >= LogLevel.DEBUG.getLevel();
    }

    private final StringWriter stringWriter = new StringWriter();
    private final PrintWriter writer = new PrintWriter(stringWriter);

    public String getErrorStackTrace(Throwable t) {
        stringWriter.getBuffer().delete(0, stringWriter.getBuffer().length());
        t.printStackTrace(writer);
        writer.flush();

        return stringWriter.toString();
    }

    public void catching(Level level, Throwable throwable) {
        LogManager.print(this, getLogLevel(level), getErrorStackTrace(throwable), name);
    }

    public void catching(Throwable throwable) {
        LogManager.print(this, LogLevel.ERROR, getErrorStackTrace(throwable), name);
    }

    public void debug(Marker marker, Message message) {
        debug(message.getFormattedMessage());
    }

    public void debug(Marker marker, Message message, Throwable throwable) {
        debug(message.getFormattedMessage(), throwable);
    }

    public void debug(Marker marker, Object o) {
        debug(String.valueOf(o));
    }

    public void debug(Marker marker, Object o, Throwable throwable) {
        debug(String.valueOf(o), throwable);
    }

    public void debug(Marker marker, String s) {
        debug(s);
    }

    public void debug(Marker marker, String s, Object... objects) {
        debug(s, objects);
    }

    public void debug(Marker marker, String s, Throwable throwable) {
        debug(s, throwable);
    }

    public void debug(Message message) {
        debug(message.getFormattedMessage());
    }

    public void debug(Message message, Throwable throwable) {
        debug(message.getFormattedMessage(), throwable);
    }

    public void debug(Object o) {
        debug(String.valueOf(o));
    }

    public void debug(Object o, Throwable throwable) {
        debug(String.valueOf(o), throwable);
    }

    public void debug(String s) {
        debug(s, new Object[]{});
    }

    public void debug(String s, Throwable throwable) {
        debug(s, new Object[]{throwable});
    }

    public void entry() {
        debug("entry");
    }

    public void entry(Object... objects) {
        StringBuilder sb = new StringBuilder("entry params(");
        for (int i = 0; i < objects.length; i++) {
            sb.append(objects[i]);
            if (i < objects.length - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        debug(sb.toString());
    }

    public void error(Marker marker, Message message) {
        error(message.getFormattedMessage());
    }

    public void error(Marker marker, Message message, Throwable throwable) {
        error(message.getFormattedMessage(), throwable);
    }

    public void error(Marker marker, Object o) {
        error(String.valueOf(o));
    }

    public void error(Marker marker, Object o, Throwable throwable) {
        error(String.valueOf(o), throwable);
    }

    public void error(Marker marker, String s) {
        error(s);
    }

    public void error(Marker marker, String s, Object... objects) {
        error(s, objects);
    }

    public void error(Marker marker, String s, Throwable throwable) {
        error(s, throwable);
    }

    public void error(Message message) {
        error(message.getFormattedMessage());
    }

    public void error(Message message, Throwable throwable) {
        error(message.getFormattedMessage(), throwable);
    }

    public void error(Object o) {
        error(String.valueOf(o));
    }

    public void error(Object o, Throwable throwable) {
        error(String.valueOf(o), throwable);
    }

    public void error(String s) {
        error(s, new Object[]{});
    }

    public void error(String s, Throwable throwable) {
        error(s, new Object[]{throwable});
    }

    public void exit() {
        debug("exit");
    }

    public <R> R exit(R r) {
        debug("exit");
        return r;
    }

    public void fatal(Marker marker, Message message) {
        fatal(message.getFormattedMessage());
    }

    public void fatal(Marker marker, Message message, Throwable throwable) {
        fatal(message.getFormattedMessage(), throwable);
    }

    public void fatal(Marker marker, Object o) {
        fatal(String.valueOf(o));
    }

    public void fatal(Marker marker, Object o, Throwable throwable) {
        fatal(String.valueOf(o), throwable);
    }

    public void fatal(Marker marker, String s) {
        fatal(s);
    }

    public void fatal(Marker marker, String s, Object... objects) {
        fatal(s, objects);
    }

    public void fatal(Marker marker, String s, Throwable throwable) {
        fatal(s, throwable);
    }

    public void fatal(Message message) {
        fatal(message.getFormattedMessage());
    }

    public void fatal(Message message, Throwable throwable) {
        fatal(message.getFormattedMessage(), throwable);
    }

    public void fatal(Object o) {
        fatal(String.valueOf(o));
    }

    public void fatal(Object o, Throwable throwable) {
        fatal(String.valueOf(o), throwable);
    }

    public void fatal(String s) {
        fatal(s, new Object[]{});
    }

    public void fatal(String s, Throwable throwable) {
        fatal(s, new Object[]{throwable});
    }

    public MessageFactory getMessageFactory() {
        return null;
    }

    public void info(Marker marker, Message message) {
        info(message.getFormattedMessage());
    }

    public void info(Marker marker, Message message, Throwable throwable) {
        info(message.getFormattedMessage(), throwable);
    }

    public void info(Marker marker, Object o) {
        info(String.valueOf(o));
    }

    public void info(Marker marker, Object o, Throwable throwable) {
        info(String.valueOf(o), throwable);
    }

    public void info(Marker marker, String s) {
        info(s);
    }

    public void info(Marker marker, String s, Object... objects) {
        info(s, objects);
    }

    public void info(Marker marker, String s, Throwable throwable) {
        info(s, throwable);
    }

    public void info(Message message) {
        info(message.getFormattedMessage());
    }

    public void info(Message message, Throwable throwable) {
        info(message.getFormattedMessage(), throwable);
    }

    public void info(Object o) {
        info(String.valueOf(o));
    }

    public void info(Object o, Throwable throwable) {
        info(String.valueOf(o), throwable);
    }

    public void info(String s) {
        info(s, new Object[]{});
    }

    public void info(String s, Throwable throwable) {
        info(s, new Object[]{throwable});
    }

    public boolean isDebugEnabled(Marker marker) {
        return isDebugEnabled();
    }

    public boolean isEnabled(Level level) {
        LogLevel logLevel = getLogLevel(level);
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= logLevel.getLevel();
    }

    public boolean isEnabled(Level level, Marker marker) {
        return isEnabled(level);
    }

    public boolean isErrorEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.ERROR.getLevel();
    }

    public boolean isErrorEnabled(Marker marker) {
        return isErrorEnabled();
    }

    public boolean isFatalEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.CRITICAL.getLevel();
    }

    public boolean isFatalEnabled(Marker marker) {
        return isFatalEnabled();
    }

    public boolean isInfoEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.INFO.getLevel();
    }

    public boolean isInfoEnabled(Marker marker) {
        return isInfoEnabled();
    }

    public boolean isTraceEnabled() {
        return false; // 此日志系统未实现 TRACE 级别
    }

    public boolean isTraceEnabled(Marker marker) {
        return isTraceEnabled();
    }

    public boolean isWarnEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.WARN.getLevel();
    }

    public boolean isWarnEnabled(Marker marker) {
        return isWarnEnabled();
    }

    public void log(Level level, Marker marker, Message message) {
        log(level, message.getFormattedMessage());
    }

    public void log(Level level, Marker marker, Message message, Throwable throwable) {
        log(level, message.getFormattedMessage(), throwable);
    }

    public void log(Level level, Marker marker, Object o) {
        log(level, String.valueOf(o));
    }

    public void log(Level level, Marker marker, Object o, Throwable throwable) {
        log(level, String.valueOf(o), throwable);
    }

    public void log(Level level, Marker marker, String s) {
        log(level, s);
    }

    public void log(Level level, Marker marker, String s, Object... objects) {
        log(level, s, objects);
    }

    public void log(Level level, Marker marker, String s, Throwable throwable) {
        log(level, s, throwable);
    }

    public void log(Level level, Message message) {
        log(level, message.getFormattedMessage());
    }

    public void log(Level level, Message message, Throwable throwable) {
        log(level, message.getFormattedMessage(), throwable);
    }

    public void log(Level level, Object o) {
        log(level, String.valueOf(o));
    }

    public void log(Level level, Object o, Throwable throwable) {
        log(level, String.valueOf(o), throwable);
    }

    public void log(Level level, String s) {
        log(level, s, new Object[]{});
    }

    public void log(Level level, String s, Object... objects) {
        LogLevel logLevel = getLogLevel(level);
        LogManager.print(this, logLevel, LogManager.parse(s, objects), name);
    }

    public void log(Level level, String s, Throwable throwable) {
        log(level, s, new Object[]{throwable});
    }

    public void printf(Level level, Marker marker, String s, Object... objects) {
        log(level, s, objects);
    }

    public void printf(Level level, String s, Object... objects) {
        log(level, s, objects);
    }

    public <T extends Throwable> T throwing(Level level, T t) {
        LogManager.print(this, getLogLevel(level), getErrorStackTrace(t), name);
        return t;
    }

    public <T extends Throwable> T throwing(T t) {
        LogManager.print(this, LogLevel.ERROR, getErrorStackTrace(t), name);
        return t;
    }

    public void trace(Marker marker, Message message) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, Message message, Throwable throwable) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, Object o) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, Object o, Throwable throwable) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, String s) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, String s, Object... objects) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Marker marker, String s, Throwable throwable) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Message message) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Message message, Throwable throwable) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Object o) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(Object o, Throwable throwable) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(String s) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(String s, Object... objects) {
        // 此日志系统未实现 TRACE 级别
    }

    public void trace(String s, Throwable throwable) {
        /**
     * 将 Log4j Level 转换为内部 LogLevel
     */
    }

    public void warn(Marker marker, Message message) {
        warn(message.getFormattedMessage());
    }

    public void warn(Marker marker, Message message, Throwable throwable) {
        warn(message.getFormattedMessage(), throwable);
    }

    public void warn(Marker marker, Object o) {
        warn(String.valueOf(o));
    }

    public void warn(Marker marker, Object o, Throwable throwable) {
        warn(String.valueOf(o), throwable);
    }

    public void warn(Marker marker, String s) {
        warn(s);
    }

    public void warn(Marker marker, String s, Object... objects) {
        warn(s, objects);
    }

    public void warn(Marker marker, String s, Throwable throwable) {
        warn(s, throwable);
    }

    public void warn(Message message) {
        warn(message.getFormattedMessage());
    }

    public void warn(Message message, Throwable throwable) {
        warn(message.getFormattedMessage(), throwable);
    }

    public void warn(Object o) {
        warn(String.valueOf(o));
    }

    public void warn(Object o, Throwable throwable) {
        warn(String.valueOf(o), throwable);
    }

    public void warn(String s) {
        warn(s, new Object[]{});
    }

    public void warn(String s, Throwable throwable) {
        warn(s, new Object[]{throwable});
    }

    // 由于没有 TRACE，将 TRACE 映射为 DEBUG
    private LogLevel getLogLevel(Level level) {
        if (level == Level.TRACE) {
            return LogLevel.DEBUG; // Map TRACE to DEBUG since we don't have TRACE
        } else if (level == Level.DEBUG) {
            return LogLevel.DEBUG;
        } else if (level == Level.INFO) {
            return LogLevel.INFO;
        } else if (level == Level.WARN) {
            return LogLevel.WARN;
        } else if (level == Level.ERROR) {
            return LogLevel.ERROR;
        } else if (level == Level.FATAL) {
            return LogLevel.CRITICAL;
        } else {
            return LogLevel.INFO;
        }
    }
}
