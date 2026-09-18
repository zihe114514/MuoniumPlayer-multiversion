package repackage.net.sourceforge.jaad.util.wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class WaveFileWriter {

	private static final int HEADER_LENGTH = 44;
	private static final int RIFF = 1380533830; //'RIFF'
	private static final long WAVE_FMT = 6287401410857104416l; //'WAVEfmt '
	private static final int DATA = 1684108385; //'data'
	private static final int BYTE_MASK = 0xFF;
	private final RandomAccessFile out;
	private final int sampleRate;
	private final int channels;
	private final int bitsPerSample;
	private int bytesWritten;

	public WaveFileWriter(File output, int sampleRate, int channels, int bitsPerSample) throws IOException {
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.bitsPerSample = bitsPerSample;
		bytesWritten = 0;

		out = new RandomAccessFile(output, "rw");
		out.write(new byte[HEADER_LENGTH]); //为头留出空间
	}

	public void write(byte[] data) throws IOException {
		write(data, 0, data.length);
	}

	public void write(byte[] data, int off, int len) throws IOException {
		//转换为小端
		byte tmp;
		for(int i = off; i<off+data.length; i += 2) {
			tmp = data[i+1];
			data[i+1] = data[i];
			data[i] = tmp;
		}
		out.write(data, off, len);
		bytesWritten += data.length;
	}

	public void write(short[] data) throws IOException {
		write(data, 0, data.length);
	}

	public void write(short[] data, int off, int len) throws IOException {
		for(int i = off; i<off+data.length; i++) {
			out.write(data[i]&BYTE_MASK);
			out.write((data[i]>>8)&BYTE_MASK);
			bytesWritten += 2;
		}
	}

	public void close() throws IOException {
		writeWaveHeader();
		out.close();
	}

	private void writeWaveHeader() throws IOException {
		out.seek(0);
		final int bytesPerSec = (bitsPerSample+7)/8;

		out.writeInt(RIFF); //wave 标签
		out.writeInt(Integer.reverseBytes(bytesWritten+36)); //不含头的字节长度
		out.writeLong(WAVE_FMT);
		out.writeInt(Integer.reverseBytes(16)); //pcm 格式声明区长度
		out.writeShort(Short.reverseBytes((short) 1)); //是 PCM
		out.writeShort(Short.reverseBytes((short) channels)); //声道数
		out.writeInt(Integer.reverseBytes(sampleRate)); //采样率
		out.writeInt(Integer.reverseBytes(sampleRate*channels*bytesPerSec)); //每秒字节数
		out.writeShort(Short.reverseBytes((short) (channels*bytesPerSec))); //每采样时间字节数
		out.writeShort(Short.reverseBytes((short) bitsPerSample)); //每采样位数
		out.writeInt(DATA); //数据段标签
		out.writeInt(Integer.reverseBytes(bytesWritten)); //原始 pcm 数据的字节长度
	}
}
