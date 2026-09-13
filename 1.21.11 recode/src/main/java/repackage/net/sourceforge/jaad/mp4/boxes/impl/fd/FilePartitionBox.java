package repackage.net.sourceforge.jaad.mp4.boxes.impl.fd;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

public class FilePartitionBox extends FullBox {

	private int itemID, packetPayloadSize, fecEncodingID, fecInstanceID,
			maxSourceBlockLength, encodingSymbolLength, maxNumberOfEncodingSymbols;
	private String schemeSpecificInfo;
	private int[] blockCounts;
	private long[] blockSizes;

	public FilePartitionBox() {
		super("File Partition Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		itemID = (int) in.readBytes(2);
		packetPayloadSize = (int) in.readBytes(2);
		in.skipBytes(1); //保留
		fecEncodingID = in.read();
		fecInstanceID = (int) in.readBytes(2);
		maxSourceBlockLength = (int) in.readBytes(2);
		encodingSymbolLength = (int) in.readBytes(2);
		maxNumberOfEncodingSymbols = (int) in.readBytes(2);
		schemeSpecificInfo = new String(Base64Decoder.decode(in.readTerminated((int) getLeft(in), 0)));

		final int entryCount = (int) in.readBytes(2);
		blockCounts = new int[entryCount];
		blockSizes = new long[entryCount];
		for(int i = 0; i<entryCount; i++) {
			blockCounts[i] = (int) in.readBytes(2);
			blockSizes[i] = (int) in.readBytes(4);
		}
	}

	/**
	 * item ID 引用 item location box 中文件分区所应用的条目。
	 *
	 * @return item ID
	 */
	public int getItemID() {
		return itemID;
	}

	/**
	 * 包负载大小给出分区算法的目标 ALC/LCT 或 FLUTE 包负载大小。
	 * 注意 UDP 包负载更大，因为它们还含 ALC/LCT 或 FLUTE 头。
	 * 
	 * @return 包负载大小
	 */
	public int getPacketPayloadSize() {
		return packetPayloadSize;
	}

	/**
	 * FEC encoding ID 需经 IANA 注册（见 RFC 3452）。注意：
	 * - 值 0 对应“Compact No-Code FEC scheme”，也称
	 * “Null-FEC”（RFC 3695）；
	 * - 值 1 对应“MBMS FEC”（3GPP TS 26.346）；
	 * - 对于 0 到 127（含）范围内的值，FEC 方案为完全规定（Fully-Specified）；
	 * 而对于 128 到 255（含）范围内的值，FEC 方案为规定不全（Under-Specified）。
	 *
	 * @return FEC encoding ID
	 */
	public int getFECEncodingID() {
		return fecEncodingID;
	}

	/**
	 * FEC instance ID 对用于规定不全 FEC 方案的 FEC 编码器给出更具体的标识。
	 * 对完全规定的 FEC 方案，此值应设为 0；当解析 FEC encoding ID 在
	 * 0 到 127（含）范围内的文件时应忽略此值。
	 * FEC instance ID 的作用域由 FEC encoding ID 决定。
	 * 详见 RFC 3452。
	 *
	 * @return FEC instance ID
	 */
	public int getFECInstanceID() {
		return fecInstanceID;
	}

	/**
	 * 最大源块长度给出每个源块中源符号的最大数量。
	 *
	 * @return 最大源块长度
	 */
	public int getMaxSourceBlockLength() {
		return maxSourceBlockLength;
	}

	/**
	 * 编码符号长度给出一个编码符号的大小（字节）。
	 * 一个条目的所有编码符号长度相同，最后一个符号可能较短。
	 *
	 * @return 编码符号长度
	 */
	public int getEncodingSymbolLength() {
		return encodingSymbolLength;
	}

	/**
	 * 对那些“最大编码符号数”有意义的 FEC 方案，
	 * 给出一个源块可生成的最大编码符号数，
	 * 例如 RFC 3452 中定义的 FEC encoding ID 129。
	 * 对“最大编码符号数”无意义的 FEC 方案，此字段语义未定义。
	 *
	 * @return 最大编码符号数
	 */
	public int getMaxNumberOfEncodingSymbols() {
		return maxNumberOfEncodingSymbols;
	}

	/**
	 * scheme specific info 是方案相关对象传输信息
	 *（FEC-OTI-Scheme-Specific-Info）的字符串。
	 * 该信息的定义取决于 EC encoding ID。
	 * 
	 * @return scheme specific info
	 */
	public String getSchemeSpecificInfo() {
		return schemeSpecificInfo;
	}

	/**
	 * block count 指示具有指定大小的连续源块数量。
	 *
	 * @return 所有 block count
	 */
	public int[] getBlockCounts() {
		return blockCounts;
	}

	/**
	 * block size 给出一个块的大小（字节）。
	 * 若 block_size 不是编码符号长度的整数倍，
	 * 则对 Compact No-Code FEC 表示最后一个源符号包含未存于条目中的填充。
	 * 对 MBMS FEC（3GPP TS 26.346），填充可跨多个符号，
	 * 但填充大小绝不应超过编码符号长度。
	 *
	 * @return 所有 block size
	 */
	public long[] getBlockSizes() {
		return blockSizes;
	}
}
