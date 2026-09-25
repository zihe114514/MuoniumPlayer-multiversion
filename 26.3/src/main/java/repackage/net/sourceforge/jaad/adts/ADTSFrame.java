package repackage.net.sourceforge.jaad.adts;

import java.io.DataInputStream;
import java.io.IOException;
import repackage.net.sourceforge.jaad.aac.ChannelConfiguration;
import repackage.net.sourceforge.jaad.aac.SampleFrequency;

class ADTSFrame {

	//固定
	private boolean id, protectionAbsent, privateBit, copy, home;
	private int layer, profile, sampleFrequency, channelConfiguration;
	//可变
	private boolean copyrightIDBit, copyrightIDStart;
	private int frameLength, adtsBufferFullness, rawDataBlockCount;
	//错误检查
	private int[] rawDataBlockPosition;
	private int crcCheck;
	//解码器特定信息
	private byte[] info;

	ADTSFrame(DataInputStream in) throws IOException {
		readHeader(in);

		if(!protectionAbsent) crcCheck = in.readUnsignedShort();
		if(rawDataBlockCount==0) {
			//raw_data_block();
		}
		else {
			int i;
			//头错误检查
			if(!protectionAbsent) {
				rawDataBlockPosition = new int[rawDataBlockCount];
				for(i = 0; i<rawDataBlockCount; i++) {
					rawDataBlockPosition[i] = in.readUnsignedShort();
				}
				crcCheck = in.readUnsignedShort();
			}
			//原始数据块
			for(i = 0; i<rawDataBlockCount; i++) {
				//raw_data_block();
				if(!protectionAbsent) crcCheck = in.readUnsignedShort();
			}
		}
	}

	private void readHeader(DataInputStream in) throws IOException {
		//固定头：
		//1 位 ID，2 位层，1 位保护缺失
		int i = in.read();
		id = ((i>>3)&0x1)==1;
		layer = (i>>1)&0x3;
		protectionAbsent = (i&0x1)==1;

		//2 位 profile，4 位采样频率，1 位私有位
		i = in.read();
		profile = ((i>>6)&0x3)+1;
		sampleFrequency = (i>>2)&0xF;
		privateBit = ((i>>1)&0x1)==1;

		//3 位声道配置，1 位拷贝，1 位源端
		i = (i<<8)|in.read();
		channelConfiguration = ((i>>6)&0x7);
		copy = ((i>>5)&0x1)==1;
		home = ((i>>4)&0x1)==1;
		//int emphasis = in.readBits(2);

		//可变头：
		//1 位 copyrightIDBit，1 位 copyrightIDStart，13 位帧长度，
		//11 位 adtsBufferFullness，2 位 rawDataBlockCount
		copyrightIDBit = ((i>>3)&0x1)==1;
		copyrightIDStart = ((i>>2)&0x1)==1;
		i = (i<<16)|in.readUnsignedShort();
		frameLength = (i>>5)&0x1FFF;
		i = (i<<8)|in.read();
		adtsBufferFullness = (i>>2)&0x7FF;
		rawDataBlockCount = i&0x3;
	}

	int getFrameLength() {
		return frameLength-(protectionAbsent ? 7 : 9);
	}

	byte[] createDecoderSpecificInfo() {
		if(info==null) {
			//5 位 profile，4 位采样频率，4 位声道配置
			info = new byte[2];
			info[0] = (byte) (profile<<3);
			info[0] |= (sampleFrequency>>1)&0x7;
			info[1] = (byte) ((sampleFrequency&0x1)<<7);
			info[1] |= (channelConfiguration<<3);
			/*1 位帧长度标志，1 位取决于核心编码器，
			1 位扩展标志（这三位目前均为 0）*/
		}

		return info;
	}

	int getSampleFrequency() {
		return SampleFrequency.forInt(sampleFrequency).getFrequency();
	}

	int getChannelCount() {
		return ChannelConfiguration.forInt(channelConfiguration).getChannelCount();
	}
}
