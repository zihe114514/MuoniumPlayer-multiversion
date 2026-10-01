package repackage.net.sourceforge.jaad.mp4;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;

public class MP4InputStream {

	public static final int MASK8 = 0xFF;
	public static final int MASK16 = 0xFFFF;
	public static final String UTF8 = "UTF-8";
	public static final String UTF16 = "UTF-16";
	private static final int BYTE_ORDER_MASK = 0xFEFF;
	private final InputStream in;
	private final RandomAccessFile fin;
	private final LinkedList<Byte> peeked = new LinkedList<Byte>();
	private long offset; //仅与 InputStream 一起使用

	/**
	 * 构造一个从 <code>InputStream</code> 读取的 <code>MP4InputStream</code>。
	 * 它没有随机访问能力，因此无法进行跳转（seek）。
	 * 
	 * @param in 用于读取的 <code>InputStream</code>
	 */
	MP4InputStream(InputStream in) {
		this.in = in;
		fin = null;
		offset = 0;
	}

	/**
	 * 构造一个从 <code>RandomAccessFile</code> 读取的 <code>MP4InputStream</code>。
	 * 它具有随机访问能力，可以进行跳转（seek）。
	 * 
	 * @param in 用于读取的 <code>RandomAccessFile</code>
	 */
	MP4InputStream(RandomAccessFile fin) {
		this.fin = fin;
		in = null;
	}

	/**
	 * 预读输入流的下一个字节数据。该字节作为
	 * 0 到 255 范围内的 int 返回。如果因到达流末尾而没有
	 * 可用字节，则抛出 EOFException。此方法
	 * 阻塞，直到输入数据可用、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @return 下一个字节数据
	 * @throws IOException 如果检测到流末尾或发生任何 I/O 错误。
	 */
	public int peek() throws IOException {
		int i = 0;
		if(!peeked.isEmpty()){
			i = peeked.remove() & MASK8;
		}
		else if(in!=null){
			i = in.read();
		}
		else if(fin!=null){
			long currentFilePointer = fin.getFilePointer();
			try{
				i = fin.read();
			}finally{
				fin.seek(currentFilePointer);
			}
		}

		if(i==-1){
			throw new EOFException();
		}
		peeked.addFirst((byte) i);
		return i;
	}

	/**
	 * 读取输入流的下一个字节数据。该字节作为
	 * 0 到 255 范围内的 int 返回。如果因到达流末尾而没有
	 * 可用字节，则抛出 EOFException。此方法
	 * 阻塞，直到输入数据可用、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @return 下一个字节数据
	 * @throws IOException 如果检测到流末尾或发生任何 I/O 错误。
	 */
	public int read() throws IOException {
		int i = 0;
		if(!peeked.isEmpty()){
			i = peeked.remove() & MASK8;
		}
		else if(in!=null){
			i = in.read();
		}
		else if(fin!=null){
			i = fin.read();
		}

		if(i==-1){
			throw new EOFException();
		}
		else if(in!=null){
			offset++;
		}
		return i;
	}

	/**
	 * 从输入预读 <code>len</code> 个字节到数组
	 * <code>b</code>。如果 len 为零，则不读取任何字节。
	 * 
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * 如果在读取 <code>len</code> 个字节之前流结束，则抛出
	 * EOFException。
	 * 
	 * @param b 读取数据所用的缓冲区。
	 * @param off 数组 <code>b</code> 中写入数据的起始偏移。
	 * @param len 要读取的字节数。
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public void peek(final byte[] b, int off, int len) throws IOException {
		int read = 0;
		int i = 0;

		while(read<len && read < peeked.size()) {
			b[off+read] = peeked.get(read);
			read++;
		}

		long currentFilePointer=-1;
		if(fin!=null){
			currentFilePointer = fin.getFilePointer();
		}
		try{
			while(read<len) {
				if(in!=null){
					i = in.read(b, off+read, len-read);
				}
				else if(fin!=null){
					i = fin.read(b, off+read, len-read);
				}
				if(i<0) {
					throw new EOFException();
				}
				else {
					for(int j = 0; j < i; j++){
						peeked.add(b[off+j]);
					}
					read += i;
				}
			}
		}finally{
			if(fin!=null){
				fin.seek(currentFilePointer);
			}
		}
	}

	/**
	 * 从输入读取 <code>len</code> 个字节到数组
	 * <code>b</code>。如果 len 为零，则不读取任何字节。
	 * 
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * 如果在读取 <code>len</code> 个字节之前流结束，则抛出
	 * EOFException。
	 * 
	 * @param b 读取数据所用的缓冲区。
	 * @param off 数组 <code>b</code> 中写入数据的起始偏移。
	 * @param len 要读取的字节数。
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public void read(final byte[] b, int off, int len) throws IOException {
		int read = 0;
		int i = 0;

		while(read<len && ! peeked.isEmpty()) {
			b[off+read] = peeked.remove();
			read++;
		}

		while(read<len) {
			if(in!=null) i = in.read(b, off+read, len-read);
			else if(fin!=null) i = fin.read(b, off+read, len-read);
			if(i<0) throw new EOFException();
			else read += i;
		}

		offset += read;
	}

	/**
	 * 预读至多八个字节并作为 long 值返回。此方法阻塞，直到所有
	 * 字节都已读取、检测到流末尾或发生 I/O 错误。
	 * 
	 * @param n 要读取的字节数，>0 且 <=8
	 * @return 读取的字节作为 long 值
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 * @throws IndexOutOfBoundsException 如果 <code>n</code> 不在范围
	 * [1...8]（含）内。
	 */
	public long peekBytes(int n) throws IOException {
		if(n<1||n>8) throw new IndexOutOfBoundsException("invalid number of bytes to read: "+n);
		final byte[] b = new byte[n];
		peek(b, 0, n);

		long result = 0;
		for(int i = 0; i<n; i++) {
			result = (result<<8)|(b[i]&0xFF);
		}
		return result;
	}

	/**
	 * 读取至多八个字节并作为 long 值返回。此方法阻塞，直到所有
	 * 字节都已读取、检测到流末尾或发生 I/O 错误。
	 * 
	 * @param n 要读取的字节数，>0 且 <=8
	 * @return 读取的字节作为 long 值
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 * @throws IndexOutOfBoundsException 如果 <code>n</code> 不在范围
	 * [1...8]（含）内。
	 */
	public long readBytes(int n) throws IOException {
		if(n<1||n>8) throw new IndexOutOfBoundsException("invalid number of bytes to read: "+n);
		final byte[] b = new byte[n];
		read(b, 0, n);

		long result = 0;
		for(int i = 0; i<n; i++) {
			result = (result<<8)|(b[i]&0xFF);
		}
		return result;
	}

	/**
	 * 从输入流预读数据并存入缓冲数组 b。
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 如果 b 的长度为零，则不读取任何字节。
	 * 
	 * @param b 读取数据所用的缓冲区。
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public void peekBytes(final byte[] b) throws IOException {
		peek(b, 0, b.length);
	}

	/**
	 * 从输入流读取数据并存入缓冲数组 b。
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 如果 b 的长度为零，则不读取任何字节。
	 * 
	 * @param b 读取数据所用的缓冲区。
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public void readBytes(final byte[] b) throws IOException {
		read(b, 0, b.length);
	}

	/**
	 * 从输入读取 <code>n</code> 个字节并作为 String 返回。这些字节
	 * 直接转换为字符。如果读取的字节不足，则抛出
	 * EOFException。
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @param n 该 String 的长度。
	 * @return 读取到的 String
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public String readString(final int n) throws IOException {
		int i = -1;
		int pos = 0;
		char[] c = new char[n];
		while(pos<n) {
			i = read();
			c[pos] = (char) i;
			pos++;
		}
		return new String(c, 0, pos);
	}

	/**
	 * 从输入读取一个以 null 结尾的 UTF 编码 String。必须指定
	 * 在出现 null 之前最多可读取的字节数。
	 * 尽管该方法偏向于 unicode，但编码可以是系统支持的
	 * 任意字符集名称。
	 * 
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @param max 在 null 终止符必须出现之前最多可读取的字节数。
	 * @param encoding 用于编码该 String 的字符集
	 * @return 解码后的 String
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public String readUTFString(int max, String encoding) throws IOException {
		return new String(readTerminated(max, 0), Charset.forName(encoding));
	}

	/**
	 * 从输入读取一个以 null 结尾的 UTF 编码 String。必须指定
	 * 在出现 null 之前最多可读取的字节数。
	 * 编码自动检测，可能为 UTF-8 或 UTF-16
	 * （由开头的字节序掩码决定）。
	 * 
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @param max 在 null 终止符必须出现之前最多可读取的字节数。
	 * @return 解码后的 String
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public String readUTFString(int max) throws IOException {
		//读取字节序掩码
		final byte[] bom = new byte[2];
		read(bom, 0, 2);
		if(bom[0]==0||bom[1]==0) return new String();
		final int i = (bom[0]<<8)|bom[1];

		//读取以 null 结尾
		final byte[] b = readTerminated(max-2, 0);
		//复制 bom
		byte[] b2 = new byte[b.length+bom.length];
		System.arraycopy(bom, 0, b2, 0, bom.length);
		System.arraycopy(b, 0, b2, bom.length, b.length);

		return new String(b2, Charset.forName((i==BYTE_ORDER_MASK) ? UTF16 : UTF8));
	}

	/**
	 * 从输入读取一个由特定字节终止的字节数组
	 *（即'终止符'）。必须指定在终止符
	 * 出现之前最多可读取的字节数。
	 * 终止符本身不会包含在返回的数组中。
	 * 
	 * 此方法阻塞，直到所有字节都已读取、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @param max 在终止符必须出现之前最多可读取的字节数。
	 * @param terminator 标示数组结束的字节
	 * @return 读取数据所用的缓冲区。
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public byte[] readTerminated(int max, int terminator) throws IOException {
		final byte[] b = new byte[max];
		int pos = 0;
		int i = 0;
		while(pos<max&&i!=-1) {
			i = read();
			if(i!=-1) b[pos++] = (byte) i;
		}
		return Arrays.copyOf(b, pos);
	}

	/**
	 * 从输入读取一个定点数。该数作为
	 * <code>m.n</code> 值读取，由一个整数除以
	 * 2<sup>n</sup> 得到。
	 * 
	 * @param m 小数点前的位数
	 * @param n 小数点后的位数
	 * @return 数值相同的浮点数
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 * @throws IllegalArgumentException 如果总位数（m+n）不是
	 * 8 的倍数
	 */
	public double readFixedPoint(int m, int n) throws IOException {
		final int bits = m+n;
		if((bits%8)!=0) throw new IllegalArgumentException("number of bits is not a multiple of 8: "+(m+n));
		final long l = readBytes(bits/8);
		final double x = Math.pow(2, n);
		double d = ((double) l)/x;
		return d;
	}

	/**
	 * 在输入中跳过 <code>n</code> 个字节。此方法阻塞，直到所有
	 * 字节都已跳过、检测到流末尾
	 * 或发生 I/O 错误。
	 * 
	 * @param n 要跳过的字节数
	 * @throws IOException 如果检测到流末尾、输入流已关闭，或发生其他 I/O 错误。
	 */
	public void skipBytes(final long n) throws IOException {
		long l = 0;
		while(l<n && !peeked.isEmpty()){
			peeked.remove();
			l++;
		}

		while(l<n) {
			if(in!=null) l += in.skip((n-l));
			else if(fin!=null) l += fin.skipBytes((int) (n-l));
		}

		offset += l;
	}

	/**
	 * 返回当前在流中的偏移。
	 * 
	 * @return 当前偏移
	 * @throws IOException 如果发生 I/O 错误（仅在使用 RandomAccessFile 时）
	 */
	public long getOffset() throws IOException {
		long l = -1;
		if(in!=null) l = offset;
		else if(fin!=null) l = fin.getFilePointer();
		return l;
	}

	/**
	 * 跳转到流中的指定偏移。这仅在
	 * 使用 RandomAccessFile 时才可能。如果使用的是 InputStream，此方法将抛出
	 * IOException。
	 * 
	 * @param pos 偏移位置，以字节为单位，从流
	 * 起点计
	 * @throws IOException 如果使用 InputStream、pos 小于 0 或发生
	 * I/O 错误
	 */
	public void seek(long pos) throws IOException {
		peeked.clear();
		if(fin!=null) fin.seek(pos);
		else throw new IOException("could not seek: no random access");
	}

	/**
	 * 表明是否支持随机访问。也就是说，此
	 * <code>MP4InputStream</code> 是否由 RandomAccessFile 构造。如果
	 * 此方法返回 false，则无法跳转（seek）。
	 * 
	 * @return 如果支持随机访问则返回 true
	 */
	public boolean hasRandomAccess() {
		return fin!=null;
	}

	/**
	 * 表明输入是否还有剩余数据。
	 * 
	 * @return 如果至少还剩一个字节则返回 true
	 * @throws IOException 如果发生 I/O 错误
	 */
	public boolean hasLeft() throws IOException {
		final boolean b;
		if(!peeked.isEmpty()){
			b = true;
		}else if(fin!=null){
			b = fin.getFilePointer()<(fin.length()-1);
		} else {
			final int i = in.read();
			b = (i!=-1);
			if(b) peeked.add((byte) i);
		}
		return b;
	}

	/**
	 * 关闭输入并释放与其关联的所有系统资源。
	 * 流一旦关闭，进一步读取或跳过将抛出
	 * IOException。关闭已关闭的流没有效果。
	 * 
	 * @throws IOException 如果发生 I/O 错误
	 */
	void close() throws IOException {
		peeked.clear();
		if(in!=null) in.close();
		else if(fin!=null) fin.close();
	}
}
