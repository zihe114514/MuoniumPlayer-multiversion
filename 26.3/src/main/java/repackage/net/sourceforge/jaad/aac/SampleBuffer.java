package repackage.net.sourceforge.jaad.aac;

/**
 * SampleBuffer 保存解码后的 AAC 帧。它包含原始 PCM 数据
 * 及其格式。
 * @author in-somnia
 */
public class SampleBuffer {

	private int sampleRate, channels, bitsPerSample;
	private double length, bitrate, encodedBitrate;
	private byte[] data;
	private boolean bigEndian;

	public SampleBuffer() {
		data = new byte[0];
		sampleRate = 0;
		channels = 0;
		bitsPerSample = 0;
		bigEndian = true;
	}

	/**
	 * 返回缓冲区的 PCM 数据。
	 * @return 音频数据
	 */
	public byte[] getData() {
		return data;
	}

	/**
	 * 返回数据的采样率。
	 * @return 采样率
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * 返回数据缓冲区中存储的声道数量。
	 * @return 声道数量
	 */
	public int getChannels() {
		return channels;
	}

	/**
	 * 返回每个采样的位数。通常为 16，意味着一个
	 * 采样存储为两个字节。
	 * @return 每个采样的位数
	 */
	public int getBitsPerSample() {
		return bitsPerSample;
	}

	/**
	 * 返回当前帧的长度（以秒为单位）。
	 * length = samplesPerChannel / sampleRate
	 * @return 长度，单位为秒
	 */
	public double getLength() {
		return length;
	}

	/**
	 * 返回解码后 PCM 数据的比特率。
	 * <code>bitrate = (samplesPerChannel * bitsPerSample) / length</code>
	 * @return 比特率
	 */
	public double getBitrate() {
		return bitrate;
	}

	/**
	 * 返回当前帧的 AAC 比特率。
	 * @return AAC 比特率
	 */
	public double getEncodedBitrate() {
		return encodedBitrate;
	}

	/**
	 * 指明数据的字节序。
	 * 
	 * @return 如果数据为大端序返回 true，小端序返回 false
	 */
	public boolean isBigEndian() {
		return bigEndian;
	}

	/**
	 * 设置数据的字节序。
	 * 
	 * @param bigEndian 如果为 true 数据将为大端序，否则为小端
	 * 序
	 */
	public void setBigEndian(boolean bigEndian) {
		if(bigEndian!=this.bigEndian) {
			byte tmp;
			for(int i = 0; i<data.length; i += 2) {
				tmp = data[i];
				data[i] = data[i+1];
				data[i+1] = tmp;
			}
			this.bigEndian = bigEndian;
		}
	}

	public void setData(byte[] data, int sampleRate, int channels, int bitsPerSample, int bitsRead) {
		this.data = data;
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.bitsPerSample = bitsPerSample;

		if(sampleRate==0) {
			length = 0;
			bitrate = 0;
			encodedBitrate = 0;
		}
		else {
			final int bytesPerSample = bitsPerSample/8; //通常为 2
			final int samplesPerChannel = data.length/(bytesPerSample*channels); //=1024
			length = (double) samplesPerChannel/(double) sampleRate;
			bitrate = (double) (samplesPerChannel*bitsPerSample*channels)/length;
			encodedBitrate = (double) bitsRead/length;
		}
	}
}
