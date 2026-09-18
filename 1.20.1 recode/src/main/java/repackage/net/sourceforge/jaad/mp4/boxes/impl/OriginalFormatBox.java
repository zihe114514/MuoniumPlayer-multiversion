package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

/**
 * Original Format Box 包含原始未变换采样描述的
 * 四字代码。
 *
 * @author in-somnia
 */
public class OriginalFormatBox extends BoxImpl {

	private long originalFormat;

	public OriginalFormatBox() {
		super("Original Format Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		originalFormat = in.readBytes(4);
	}

	/**
	 * 原始格式为原始未变换采样条目的四字代码
	 *（例如若流含受保护的 MPEG-4 视觉素材则为 'mp4v'）。
	 *
	 * @return 流的原始格式
	 */
	public long getOriginalFormat() {
		return originalFormat;
	}
}
