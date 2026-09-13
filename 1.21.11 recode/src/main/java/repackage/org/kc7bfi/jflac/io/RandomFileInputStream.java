/*
 * 创建于2004年6月23日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac.io;

import java.io.*;


/**
 * @return 返回 totalBits。
 *
 * @author kc7bfi
 */
public class RandomFileInputStream extends InputStream {

    protected RandomAccessFile randomFile = null;

    /**
     * 制造商
     *
     * @param file 要读取的文件
     * @throws FileNotFoundException如果找不到文件
     */
    public RandomFileInputStream(File file) throws FileNotFoundException {
        super();
        randomFile = new RandomAccessFile(file, "r");
    }

    /**
     * 制造商
     *
     * @param fileName 要读取的文件名
     * @throws FileNotFoundException如果找不到文件。
     */
    public RandomFileInputStream(String fileName) throws FileNotFoundException {
        super();
        randomFile = new RandomAccessFile(fileName, "r");
    }

    /**
     * 制造商
     *
     * @param randomFile 要读取的文件
     */
    public RandomFileInputStream(RandomAccessFile randomFile) {
        this.randomFile = randomFile;
    }

    /**
     * 读取一个字节值。
     *
     * 这是一个使用随机访问文件的 FileInputStream。
     * IO错误时出现@throws IOException
     * @return 字节值
     */
    public int read() throws IOException {
        return randomFile.read();
    }

    /**
     * @see InputStream#reset()
     */
    public synchronized void reset() {
        throw new UnsupportedOperationException("reset");
    }

    /**
     * Close the file.
     *
     * IO错误时出现@throws IOException
     * @see InputStream#close()
     */
    public void close() throws IOException {
        randomFile.close();
    }

    /**
     * 返回下划线文件的长度
     *
     * 关闭文件。
     * @throws IOException
     */
    public long getLength() throws IOException {
        return randomFile.length();
    }

    /**
     * @return 文件长度
     *
     * 如果支持文件标记，则@return 为true
     * @see InputStream#markSupported()
     */
    public boolean markSupported() {
        return false;
    }

    /**
     * 是否支持文件标记。
     */
    public synchronized void mark(int pos) {
        throw new UnsupportedOperationException("mark");
    }

    /**
     * 跳过输入文件中的字节。
     *
     * @param bytes 要跳过的字节数
     * @return the number of bytes skipped
     * IO错误时出现@throws IOException
     * @return 跳过的字节数
     */
    public long skip(long bytes) throws IOException {
        long pos = randomFile.getFilePointer();
        randomFile.seek(pos + bytes);
        return randomFile.getFilePointer() - pos;
    }

    /**
     * Read bytes into an array.
     *
     * 将字节读入数组。
     * @return 字节读取
     * IO错误时出现@throws IOException
     * @see InputStream#read(byte[])
     */
    public int read(byte[] buffer) throws IOException {
        return randomFile.read(buffer);
    }

    /**
     * Read bytes into an array.
     *
     * 将字节读入数组。
     * @param pos 缓冲器中的起始位置
     * @param bytes 要读取的字节数
     * @return 字节读取
     * IO错误时出现@throws IOException
     * @see InputStream#read(byte[], int, int)
     */
    public int read(byte[] buffer, int pos, int bytes) throws IOException {
        return randomFile.read(buffer, pos, bytes);
    }

    /**
     * 查找文件中的某个位置。
     *
     * @param pos 寻找位置
     * @param buffer 读入字节的缓冲区
     */
    public void seek(long pos) throws IOException {
        randomFile.seek(pos);
    }

    /**
     * 返回文件中的当前读取位置
     *
     * @return
     * @throws IOException
     */
    public long getPosition() throws IOException {
        return randomFile.getFilePointer();
    }
}
