package com.muoniumplayer.core.ncm.lxsource;

/**
 * 自定义源侧的失败：脚本 reject、返回值不合规、超时、运行时未就绪都归这一类。
 *
 * <p>刻意是 checked 异常：调用方必须在「脚本失败」和「脚本给出结果」之间做出选择
 * （通常是退回内置解析链），不能一路往上抛到播放线程。</p>
 */
public class LxSourceException extends Exception {

    private static final long serialVersionUID = 1L;

    public LxSourceException(String message) {
        super(message == null || message.isEmpty() ? "自定义源请求失败" : message);
    }
}