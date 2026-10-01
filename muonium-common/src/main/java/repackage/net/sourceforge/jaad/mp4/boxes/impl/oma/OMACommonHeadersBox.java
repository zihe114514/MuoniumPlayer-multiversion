package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

//TODO: 补充其余 javadoc
public class OMACommonHeadersBox extends FullBox {

	private int encryptionMethod, paddingScheme;
	private long plaintextLength;
	private byte[] contentID, rightsIssuerURL;
	private Map<String, String> textualHeaders;

	public OMACommonHeadersBox() {
		super("OMA DRM Common Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		encryptionMethod = in.read();
		paddingScheme = in.read();
		plaintextLength = in.readBytes(8);
		final int contentIDLength = (int) in.readBytes(2);
		final int rightsIssuerURLLength = (int) in.readBytes(2);
		int textualHeadersLength = (int) in.readBytes(2);

		contentID = new byte[contentIDLength];
		in.readBytes(contentID);
		rightsIssuerURL = new byte[rightsIssuerURLLength];
		in.readBytes(rightsIssuerURL);

		textualHeaders = new HashMap<String, String>();
		String key, value;
		while(textualHeadersLength>0) {
			key = new String(in.readTerminated((int) getLeft(in), ':'));
			value = new String(in.readTerminated((int) getLeft(in), 0));
			textualHeaders.put(key, value);

			textualHeadersLength -= key.length()+value.length()+2;
		}

		readChildren(in);
	}

	/**
	 * 加密方法定义加密内容如何解密。
	 * 该字段取值见下表：
	 * 
	 * <table>
	 * <tr><th>值</th><th>算法</th></tr>
	 * <tr><td>0</td><td>不使用加密</td></tr>
	 * <tr><td>1</td><td>AES_128_CBC：<br />由 NIST 定义的 AES 对称加密。
	 * 128 位密钥，密文分组链接模式（CBC）。第一个分组使用 128 位初始化向量（IV）。
	 * 对 DCF 文件，IV 作为加密数据的前缀包含在 OMADRMData 中。
	 * 对不可流式 PDCF 文件，IV 包含在 OMAAUHeader 的 IV 字段中，
	 * 且 OMAAUFormatBox 中的 IVLength 字段必须设为 16。按 RFC 2630 填充</td></tr>
	 * <tr><td>2</td><td>AES_128_CTR：<br />由 NIST 定义的 AES 对称加密。
	 * 128 位密钥，计数器模式（CTR）。计数分组长度为 128 位。
	 * 对 DCF 文件，初始计数值作为加密数据前缀包含在 OMADRMData 中。
	 * 对不可流式 PDCF 文件，初始计数值包含在 OMAAUHeader 的 IV 字段中，
	 * 且 OMAAUFormatBox 中的 IVLength 字段必须设为 16。
	 * 每个密文分组计数器加 1（模 2^128）。不填充。</td></tr>
	 * </table>
	 * 
	 * @return 加密方法
	 */
	public int getEncryptionMethod() {
		return encryptionMethod;
	}

	/**
	 * 填充方案定义最后一个密文分组如何填充。
	 * padding scheme 字段取值见下表：
	 * 
	 * <table>
	 * <tr><th>值</th><th>填充方案</th></tr>
	 * <tr><td>0</td><td>不填充（例如使用 NULL 或 CTR 算法时）</td></tr>
	 * <tr><td>1</td><td>按 RFC 2630 填充</td></tr>
	 * </table>
	 * 
	 * @return 填充方案
	 */
	public int getPaddingScheme() {
		return paddingScheme;
	}

	public long getPlaintextLength() {
		return plaintextLength;
	}

	public byte[] getContentID() {
		return contentID;
	}

	public byte[] getRightsIssuerURL() {
		return rightsIssuerURL;
	}

	public Map<String, String> getTextualHeaders() {
		return textualHeaders;
	}
}
