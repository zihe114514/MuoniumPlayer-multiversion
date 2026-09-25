package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * OMA DRM Transaction Tracking Box 启用 'OMA DRM v2.1' 第 15.3 节定义的事务跟踪。
 * 该 box 含单个 transaction-ID，可出现于 DCF 和 PDCF 中。
 * 
 * @author in-somnia
 */
public class OMATransactionTrackingBox extends FullBox {

	private String transactionID;

	public OMATransactionTrackingBox() {
		super("OMA DRM Transaction Tracking Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		transactionID = in.readString(16);
	}

	/**
	 * 分别返回 DCF 或 PDCF 的 transaction-ID。
	 * 
	 * @return transaction-ID
	 */
	public String getTransactionID() {
		return transactionID;
	}
}
