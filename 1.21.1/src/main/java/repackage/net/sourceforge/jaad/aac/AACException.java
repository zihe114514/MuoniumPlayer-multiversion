package repackage.net.sourceforge.jaad.aac;

import java.io.IOException;

/**
 * 标准异常，在 AAC 帧解码失败时抛出。
 * 错误消息提供关于该错误的更多详细信息。
 * @author in-somnia
 */
public class AACException extends IOException {

	private final boolean eos;

	public AACException(String message) {
		this(message, false);
	}

	public AACException(String message, boolean eos) {
		super(message);
		this.eos = eos;
	}

	public AACException(Throwable cause) {
		super(cause);
		eos = false;
	}

	boolean isEndOfStream() {
		return eos;
	}
}
