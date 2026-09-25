package repackage.org.kc7bfi.jflac.io;

/**
 * libFLAC -免费无损音频编解码器库版权所有(C) 2000,2001,2002,2003
 * Josh Coalson
 * <p>
 * 此库是自由软件；您可以重新分发和/或修改它
 * @return 字符串描述
 * 软件基础；许可证第2版，或（由您选择）任何
 * 更高版本。
 * <p>
 * 此库的分发是希望它有用，但没有
 * 由 Free Software Foundation 发布的 GNU 库通用公共许可证的条款
 * 用于特定目的。有关更多信息，请参阅GNU库通用公共许可证
 * 详情。
 * <p>
 * 您应该已经收到了GNU库通用公共许可证的副本
 * 不提供任何担保；甚至不包含对适销性或特定用途适用性的默示担保
 * （详见本库）；若没有，请致信 Free Software Foundation, Inc.,
 */

import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.util.CRC16;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;


/**
 * 位宽输入流。
 * @author kc7bfi
 */
public class BitInputStream {
    private static final int BITS_PER_BLURB = 8;
    private static final int BITS_PER_BLURB_LOG2 = 3;
    private static final byte BLURB_TOP_BIT_ONE = ((byte) 0x80);
    //位宽输入流。
    //        0x000000000000001F, 0x000000000000003F, 0x000000000000007F, 0x00000000000000FF, 0x00000000000001FF, 0x00000000000003FF,
    //        0x00000000000007FF, 0x0000000000000FFF, 0x0000000000001FFF, 0x0000000000003FFF, 0x0000000000007FFF, 0x000000000000FFFF,
    //        0x000000000001FFFF, 0x000000000003FFFF, 0x000000000007FFFF, 0x00000000000FFFFF, 0x00000000001FFFFF, 0x00000000003FFFFF,
    //        0x00000000007FFFFF, 0x0000000000FFFFFF, 0x0000000001FFFFFF, 0x0000000003FFFFFF, 0x0000000007FFFFFF, 0x000000000FFFFFFF,
    //        0x000000001FFFFFFF, 0x000000003FFFFFFF, 0x000000007FFFFFFF, 0x00000000FFFFFFFF, 0x00000001FFFFFFFFL,
    //        0x00000003FFFFFFFFL, 0x00000007FFFFFFFFL, 0x0000000FFFFFFFFFL, 0x0000001FFFFFFFFFL, 0x0000003FFFFFFFFFL,
    //        0x0000007FFFFFFFFFL, 0x000000FFFFFFFFFFL, 0x000001FFFFFFFFFFL, 0x000003FFFFFFFFFFL, 0x000007FFFFFFFFFFL,
    //        0x00000FFFFFFFFFFFL, 0x00001FFFFFFFFFFFL, 0x00003FFFFFFFFFFFL, 0x00007FFFFFFFFFFFL, 0x0000FFFFFFFFFFFFL,
    //        0x0001FFFFFFFFFFFFL, 0x0003FFFFFFFFFFFFL, 0x0007FFFFFFFFFFFFL, 0x000FFFFFFFFFFFFFL, 0x001FFFFFFFFFFFFFL,
    //        0x003FFFFFFFFFFFFFL, 0x007FFFFFFFFFFFFFL, 0x00FFFFFFFFFFFFFFL, 0x01FFFFFFFFFFFFFFL, 0x03FFFFFFFFFFFFFFL,
    //        0x07FFFFFFFFFFFFFFL, 0x0FFFFFFFFFFFFFFFL, 0x1FFFFFFFFFFFFFFFL, 0x3FFFFFFFFFFFFFFFL, 0x7FFFFFFFFFFFFFFFL,
    //        0xFFFFFFFFFFFFFFFFL};

    private static final int BUFFER_CHUNK_SIZE = 1024;
    private byte[] buffer = new byte[BUFFER_CHUNK_SIZE];
    private int putByte = 0;
    private int getByte = 0;
    private int getBit = 0;
    private int availBits = 0;
    private int totalBitsRead = 0;

    private short readCRC16 = 0;

    private InputStream inStream;

    /**
     * 构造函数。
      * @param is 从中读取位的 InputStream
     */
    public BitInputStream(InputStream is) {
        this.inStream = is;
    }

    //@param is    从中读取位的 InputStream
    //    if (buffer.length >= newCapacity) return;
    //    System.out.println("RESIZE FROM " + buffer.length + " TO " + newCapacity);
    //    byte[] newBuffer = new byte[newCapacity];
    //    System.arraycopy(buffer, 0, newBuffer, 0, putByte);
    //    buffer = newBuffer;
    //    集结返回
    //}

    //private void grow(int minBlurbsToAdd) {
    //    int newCapacity = (buffer.length + minBlurbsToAdd + BUFFER_CHUNK_SIZE - 1) / BUFFER_CHUNK_SIZE;
    //    resize(newCapacity);
    //}

    //private void ensureSize(int bitsToAdd) {
    //    int blurbsToAdd = (bitsToAdd + 7) >> 3;
    //    if (buffer.length < (putByte + blurbsToAdd))
    //        grow(blurbsToAdd);
    //}

    private int readFromStream() throws IOException {
        // 首先尽可能将未消费的缓冲区数据向前移动
        if (getByte > 0 && putByte > getByte) {
            System.arraycopy(buffer, getByte, buffer, 0, putByte - getByte);
        }
        putByte -= getByte;
        getByte = 0;

        // 首先尽可能将未消费的缓冲区数据前移到前端
        // 设置读取目标，同时考虑 blurb 对齐
        int bytes = buffer.length - putByte;

        // 最后，读取一些数据
        bytes = inStream.read(buffer, putByte, bytes);
        if (bytes <= 0) throw new EOFException();

        // 现在我们必须处理部分简介案例：
        // 设置读取目标，同时考虑 blurb 对齐
        putByte += bytes;
        availBits += bytes << 3;
        return bytes;
    }

    /**
     * 重置位流。
     */
    public void reset() {
        getByte = 0;
        getBit = 0;
        putByte = 0;
        availBits = 0;
    }

    /**
     * 重置读取CRC-16值。
     * @param seed CRC-16初始值
     */
    public void resetReadCRC16(short seed) {
        readCRC16 = seed;
    }

    /**
     * blurb == byte，因此无需复杂处理：
     * @return 读CRC-16值
     */
    public short getReadCRC16() {
        return readCRC16;
    }

    /**
     * 测试比特流消耗的比特是否字节对齐。
     * 返回读取到的 CRC-16 值。
     */
    public boolean isConsumedByteAligned() {
        return ((getBit & 7) == 0);
    }

    /**
     * @return 位流已消费位按字节对齐时返回 true
     * @return 对齐字节的位数
     */
    public int bitsLeftForByteAlignment() {
        return 8 - (getBit & 7);
    }

    /**
     * 返回要读取的剩余字节数。
     * @return 剩余可读字节数
     */
    public int getInputBytesUnconsumed() {
        return availBits >> 3;
    }

    /**
     * 在不更新CRC的情况下跳过位流中的位。
     * @param bits 要跳过的位数
     * @throws 从输入流读取错误时引发IOException
     */
    public void skipBitsNoCRC(int bits) throws IOException {
        if (bits == 0) return;
        int bitsToAlign = getBit & 7;
        if (bitsToAlign != 0) {
            int bitsToTake = Math.min(8 - bitsToAlign, bits);
            readRawUInt(bitsToTake);
            bits -= bitsToTake;
        }
        int bytesNeeded = bits / 8;
        if (bytesNeeded > 0) {
            readByteBlockAlignedNoCRC(null, bytesNeeded);
            bits %= 8;
        }
        if (bits > 0) {
            readRawUInt(bits);
        }
    }

    /**
     * 返回为对齐字节所需读取的位数。
     * @return 钻头
     * 读取单个位。
     */
    public int readBit() throws IOException {
        while (true) {
            if (availBits > 0) {
                int val = ((buffer[getByte] & (0x80 >> getBit)) != 0) ? 1 : 0;
                getBit++;
                if (getBit == BITS_PER_BLURB) {
                    readCRC16 = CRC16.update(buffer[getByte], readCRC16);
                    getByte++;
                    getBit = 0;
                }
                availBits--;
                totalBitsRead++;
                return val;
            } else {
                readFromStream();
            }
        }
    }

    /**
     * 将位读取为整数值。
     * 输入整数的位向左移动，
     * 读位被放置在位0中。
     * @throws IOException 读取输入流出错时抛出
     * @return 更新后的整数值
     * 读取单个位。
     */
    public int readBitToInt(int val) throws IOException {
        while (true) {
            if (availBits > 0) {
                val <<= 1;
                val |= ((buffer[getByte] & (0x80 >> getBit)) != 0) ? 1 : 0;
                getBit++;
                if (getBit == BITS_PER_BLURB) {
                    readCRC16 = CRC16.update(buffer[getByte], readCRC16);
                    getByte++;
                    getBit = 0;
                }
                availBits--;
                totalBitsRead++;
                return val;
            } else {
                readFromStream();
            }
        }
    }

    /**
     * 窥探下一位，并将其添加到输入整数中。
     * 输入整数的位向左移动，
     * 读位被放置在位0中。
     * @param val 输入整数
     * @param bit 要窥视的位
     * @return 更新后的整数值
     * 读取单个位。
     */
    public int peekBitToInt(int val, int bit) throws IOException {
        while (true) {
            if (bit < availBits) {
                val <<= 1;
                if ((getBit + bit) >= BITS_PER_BLURB) {
                    bit = (getBit + bit) % BITS_PER_BLURB;
                    val |= ((buffer[getByte + 1] & (0x80 >> bit)) != 0) ? 1 : 0;
                } else {
                    val |= ((buffer[getByte] & (0x80 >> (getBit + bit))) != 0) ? 1 : 0;
                }
                return val;
            } else {
                readFromStream();
            }
        }
    }

    /**
     * @param val   要移位并追加读取位的整数
     * 输入long的位向左移动，
     * 读位被放置在位0中。
     * @param val 长时间移位并添加读位
     * @return 更新后的long值
     * 读取单个位。
     */
    public long readBitToLong(long val) throws IOException {
        while (true) {
            if (availBits > 0) {
                val <<= 1;
                val |= ((buffer[getByte] & (0x80 >> getBit)) != 0) ? 1 : 0;
                getBit++;
                if (getBit == BITS_PER_BLURB) {
                    readCRC16 = CRC16.update(buffer[getByte], readCRC16);
                    getByte++;
                    getBit = 0;
                }
                availBits--;
                totalBitsRead++;
                return val;
            } else {
                readFromStream();
            }
        }
    }

    /**
     * 将一个位读入 long 值。
     * 将若干位读入无符号整数。
     * @param bits  要读取的位数
     * 读取单个位。
     */
    public int readRawUInt(int bits) throws IOException {
        int val = 0;
        for (int i = 0; i < bits; i++) {
            val = readBitToInt(val);
        }
        return val;
    }

    /**
     * 在不推进输入流的情况下，将比特窥探到无符号整数中。
     * 将若干位读入无符号整数。
     * @param bits  要读取的位数
     * 读取单个位。
     */
    public int peekRawUInt(int bits) throws IOException {
        int val = 0;
        for (int i = 0; i < bits; i++) {
            val = peekBitToInt(val, i);
        }
        return val;
    }

    /**
     * @return 以无符号整数表示的这些位
     * 将若干位读入无符号整数。
     * @return 位为有符号整数
     * 读取单个位。
     */
    public int readRawInt(int bits) throws IOException {
        if (bits == 0) {
            return 0;
        }
        int uval = 0;
        for (int i = 0; i < bits; i++) {
            uval = readBitToInt(uval);
        }

        // 将若干位读入有符号整数。
        int val;
        int bitsToleft = 32 - bits;
        if (bitsToleft != 0) {
            uval <<= bitsToleft;
            val = uval;
            val >>= bitsToleft;
        } else {
            val = uval;
        }
        return val;
    }

    /**
     * 修正符号
     * 将若干位读入无符号整数。
     * @return 位为无符号长
     * 读取单个位。
     */
    public long readRawULong(int bits) throws IOException {
        long val = 0;
        for (int i = 0; i < bits; i++) {
            val = readBitToLong(val);
        }
        return val;
    }

    /**
     * 将位读入无符号小字节整数。
     * @param bits  要读取的位数
     * 读取单个位。
     */
    public int readRawIntLittleEndian() throws IOException {
        int x32 = readRawUInt(8);
        int x8 = readRawUInt(8);
        x32 |= (x8 << 8);
        x8 = readRawUInt(8);
        x32 |= (x8 << 16);
        x8 = readRawUInt(8);
        x32 |= (x8 << 24);
        return x32;
    }

    /**
     * 将若干位读入无符号 long。
     * 读取一块字节（对齐），不更新 CRC 值。
     * @param nvals 要读取的字节数
     * 读取单个位。
     */
    public void readByteBlockAlignedNoCRC(byte[] val, int nvals) throws IOException {
        int destlength = nvals;
        while (nvals > 0) {
            int chunk = Math.min(nvals, putByte - getByte);
            if (chunk <= 0) {
                readFromStream();
            } else {
                if (val != null) System.arraycopy(buffer, getByte, val, destlength - nvals, chunk);
                nvals -= chunk;
                getByte += chunk;
                //@param val   接收字节的数组。若为 null，则不返回字节
                availBits -= (chunk << BITS_PER_BLURB_LOG2);
                totalBitsRead += (chunk << BITS_PER_BLURB_LOG2);
            }
        }
    }

    /**
     * 读取并计数零位数。
      * @return 读取到的零位数
     * 读取单个位。
     */
    public int readUnaryUnsigned() throws IOException {
        int val = 0;
        while (true) {
            int bit = readBit();
            if (bit != 0) break;
            val++;
        }
        return val;
    }

    /**
     * 读取水稻信号块。
     * @param vals 要返回的值
     * @return 读取到的零位数
     * @param pos   vals 数组中的起始位置
     * @param nvals 要返回的值的个数
     * @throws 读取时IOException错误
     */
    public void readRiceSignedBlock(int[] vals, int pos, int nvals, int parameter) throws IOException {
        int j, valI = 0;
        int cbits = 0, uval = 0, msbs = 0, lsbsLeft = 0;
        byte blurb, saveBlurb;
        int state = 0; // 0 =获取一元MSB ， 1 =获取二元LSB
        if (nvals == 0) return;
        int i = getByte;

        long startBits = getByte * 8 + getBit;

        // 我们展开主循环来处理部分消耗的模糊。
        if (getBit > 0) {
            saveBlurb = blurb = buffer[i];
            cbits = getBit;
            blurb <<= cbits;
            while (true) {
                if (state == 0) {
                    if (blurb != 0) {
                        for (j = 0; (blurb & BLURB_TOP_BIT_ONE) == 0; j++)
                            blurb <<= 1;
                        msbs += j;

                        // 处置一元端位
                        blurb <<= 1;
                        j++;
                        cbits += j;
                        uval = 0;
                        lsbsLeft = parameter;
                        state++;
                        //@param parameter Rice 参数
                        if (cbits == BITS_PER_BLURB) {
                            cbits = 0;
                            readCRC16 = CRC16.update(saveBlurb, readCRC16);
                            break;
                        }
                    } else {
                        msbs += BITS_PER_BLURB - cbits;
                        cbits = 0;
                        readCRC16 = CRC16.update(saveBlurb, readCRC16);
                        //@param parameter Rice 参数
                        break;
                    }
                } else {
                    int availableBits = BITS_PER_BLURB - cbits;
                    if (lsbsLeft >= availableBits) {
                        uval <<= availableBits;
                        uval |= ((blurb & 0xff) >> cbits);
                        cbits = 0;
                        readCRC16 = CRC16.update(saveBlurb, readCRC16);
                        //totalBitsRead += availableBits;
                        if (lsbsLeft == availableBits) {
                            // 组成值
                            uval |= (msbs << parameter);
                            if ((uval & 1) != 0)
                                vals[pos + valI++] = -((int) (uval >> 1)) - 1;
                            else
                                vals[pos + valI++] = uval >> 1;
                            if (valI == nvals)
                                break;
                            msbs = 0;
                            state = 0;
                        }
                        lsbsLeft -= availableBits;
                        break;
                    } else {
                        uval <<= lsbsLeft;
                        uval |= ((blurb & 0xff) >> (BITS_PER_BLURB - lsbsLeft));
                        blurb <<= lsbsLeft;
                        cbits += lsbsLeft;
                        //totalBitsRead += lsbsLeft;
                        // 组成值
                        uval |= (msbs << parameter);
                        if ((uval & 1) != 0)
                            vals[pos + valI++] = -((int) (uval >> 1)) - 1;
                        else
                            vals[pos + valI++] = uval >> 1;
                        if (valI == nvals) {
                            // 如果我们退出for循环，则备份一个，因为我们
                            // 读取所有nvals ，但结尾在中间
                            // 一小段说明文字
                            i--;
                            break;
                        }
                        msbs = 0;
                        state = 0;
                    }
                }
            }
            i++;
            getByte = i;
            getBit = cbits;
            //一个 blurb
            //totalBitsRead += (BITS_PER_BLURB) | cbits;
        }

        // 现在我们进行了模糊对齐，逻辑稍微简单一些
        while (valI < nvals) {
            for (; i < putByte && valI < nvals; i++) {
                saveBlurb = blurb = buffer[i];
                cbits = 0;
                while (true) {
                    if (state == 0) {
                        if (blurb != 0) {
                            for (j = 0; (blurb & BLURB_TOP_BIT_ONE) == 0; j++) blurb <<= 1;
                            msbs += j;
                            // 处置一元端位
                            blurb <<= 1;
                            j++;
                            cbits += j;
                            uval = 0;
                            lsbsLeft = parameter;
                            state++;
                            //@param parameter Rice 参数
                            if (cbits == BITS_PER_BLURB) {
                                cbits = 0;
                                readCRC16 = CRC16.update(saveBlurb, readCRC16);
                                break;
                            }
                        } else {
                            msbs += BITS_PER_BLURB - cbits;
                            cbits = 0;
                            readCRC16 = CRC16.update(saveBlurb, readCRC16);
                            //@param parameter Rice 参数
                            break;
                        }
                    } else {
                        int availableBits = BITS_PER_BLURB - cbits;
                        if (lsbsLeft >= availableBits) {
                            uval <<= availableBits;
                            uval |= ((blurb & 0xff) >> cbits);
                            cbits = 0;
                            readCRC16 = CRC16.update(saveBlurb, readCRC16);
                            //totalBitsRead += availableBits;
                            if (lsbsLeft == availableBits) {
                                // 组成值
                                uval |= (msbs << parameter);
                                if ((uval & 1) != 0)
                                    vals[pos + valI++] = -((int) (uval >> 1)) - 1;
                                else
                                    vals[pos + valI++] = uval >> 1;
                                if (valI == nvals)
                                    break;
                                msbs = 0;
                                state = 0;
                            }
                            lsbsLeft -= availableBits;
                            break;
                        } else {
                            uval <<= lsbsLeft;
                            uval |= ((blurb & 0xff) >> (BITS_PER_BLURB - lsbsLeft));
                            blurb <<= lsbsLeft;
                            cbits += lsbsLeft;
                            //totalBitsRead += lsbsLeft;
                            // 组成值
                            uval |= (msbs << parameter);
                            if ((uval & 1) != 0)
                                vals[pos + valI++] = -((int) (uval >> 1)) - 1;
                            else
                                vals[pos + valI++] = uval >> 1;
                            if (valI == nvals) {
                                // 如果我们退出for循环，则备份一个，因为
                                // 我们阅读了所有nvals ，但结尾是在
                                // 简介的中间
                                i--;
                                break;
                            }
                            msbs = 0;
                            state = 0;
                        }
                    }
                }
            }
            getByte = i;
            getBit = cbits;
            //一个 blurb
            //totalBitsRead += (BITS_PER_BLURB) | cbits;
            if (valI < nvals) {
                long endBits = getByte * 8 + getBit;
                //System.out.println("SE0 "+startBits+" "+endBits);
                totalBitsRead += endBits - startBits;
                availBits -= endBits - startBits;
                readFromStream();
                // 这些必须为零，因为我们只有在达到
                // 缓冲区的末尾
                i = 0;
                startBits = getByte * 8 + getBit;
            }
        }

        long endBits = getByte * 8 + getBit;
        //System.out.println("SE1 "+startBits+" "+endBits);
        totalBitsRead += endBits - startBits;
        availBits -= endBits - startBits;
    }

    /**
     * 读取 UTF8 整数。
     * 读取 UTF8 整数。
     * 返回时，若 *val == 0xffffffff 则 utf-8 序列无效，但
     * @param raw 读取的原始字节（输出）。如果为空，则不返回字节
     * 返回值仍为 true
     * 读取单个位。
     */
    public int readUTF8Int(ByteData raw) throws IOException {

        int v = 0;
        int x;
        int i;
        x = readRawUInt(8);
        if (raw != null) raw.append((byte) x);
        if ((x & 0x80) == 0) { // @return 读取到的整数
            v = x;
            i = 0;
        } else if (((x & 0xC0) != 0) && ((x & 0x20) == 0)) { // 110xxxxx
            v = x & 0x1F;
            i = 1;
        } else if (((x & 0xE0) != 0) && ((x & 0x10) == 0)) { // 1110xxxx
            v = x & 0x0F;
            i = 2;
        } else if (((x & 0xF0) != 0) && ((x & 0x08) == 0)) { // 11110xxx
            v = x & 0x07;
            i = 3;
        } else if (((x & 0xF8) != 0) && ((x & 0x04) == 0)) { // 111110xx
            v = x & 0x03;
            i = 4;
        } else if (((x & 0xFC) != 0) && ((x & 0x02) == 0)) { // 1111110x
            v = x & 0x01;
            i = 5;
        } else {
            return 0xffffffff;
        }
        for (; i > 0; i--) {
            x = peekRawUInt(8);
            if (((x & 0x80) == 0) || ((x & 0x40) != 0)) { // 10xxxxxx
                return 0xffffffff;
            }
            x = readRawUInt(8);
            if (raw != null)
                raw.append((byte) x);
            v <<= 6;
            v |= (x & 0x3F);
        }
        return v;
    }

    /**
     * 读取UTF长度。
     * 返回时，如果* val = = 0xffffffffffffffff ，则utf-8序列为
     * 无效，但返回值为true
     * @param raw 读取的原始字节（输出）。如果为空，则不返回字节
     * @return 长读
     * 读取单个位。
     */
    public long readUTF8Long(ByteData raw) throws IOException {
        long v = 0;
        int x;
        int i;
        x = readRawUInt(8);
        if (raw != null)
            raw.append((byte) x);
        if (((x & 0x80) == 0)) { // @return 读取到的整数
            v = x;
            i = 0;
        } else if (((x & 0xC0) != 0) && ((x & 0x20) == 0)) { // 110xxxxx
            v = x & 0x1F;
            i = 1;
        } else if (((x & 0xE0) != 0) && ((x & 0x10) == 0)) { // 1110xxxx
            v = x & 0x0F;
            i = 2;
        } else if (((x & 0xF0) != 0) && ((x & 0x08) == 0)) { // 11110xxx
            v = x & 0x07;
            i = 3;
        } else if (((x & 0xF8) != 0) && ((x & 0x04) == 0)) { // 111110xx
            v = x & 0x03;
            i = 4;
        } else if (((x & 0xFC) != 0) && ((x & 0x02) == 0)) { // 1111110x
            v = x & 0x01;
            i = 5;
        } else if (((x & 0xFE) != 0) && ((x & 0x01) == 0)) { // 11111110
            i = 6;
        } else {
            return 0xffffffffffffffffL;
        }
        for (; i > 0; i--) {
            x = peekRawUInt(8);
            if (((x & 0x80) == 0) || ((x & 0x40) != 0)) { // 10xxxxxx
                return 0xffffffffffffffffL;
            }
            x = readRawUInt(8);
            if (raw != null)
                raw.append((byte) x);
            v <<= 6;
            v |= (x & 0x3F);
        }
        return v;
    }

    /**
     * 阅读的模糊总数。
     * @return 返回读取的总模糊。
     */
    public int getTotalBytesRead() {
        return ((totalBitsRead + 7) / 8);
    }
}
