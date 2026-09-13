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

import repackage.org.kc7bfi.jflac.util.CRC16;
import repackage.org.kc7bfi.jflac.util.CRC8;

import java.io.IOException;
import java.io.OutputStream;


/**
 * Bit-wide output stream.
 * @author kc7bfi
 */
public class BitOutputStream {
    private static final int BITS_PER_BLURB = 8;
    //private static final int BITS_PER_BLURB_LOG2 = 3 ；
    //private static final int BYTES_PER_BLURB = 1;
    //位宽输出流。
    private static final long[] MASK32 = new long[]{0, 0x0000000000000001, 0x0000000000000003, 0x0000000000000007, 0x000000000000000F,
            0x000000000000001F, 0x000000000000003F, 0x000000000000007F, 0x00000000000000FF, 0x00000000000001FF, 0x00000000000003FF,
            0x00000000000007FF, 0x0000000000000FFF, 0x0000000000001FFF, 0x0000000000003FFF, 0x0000000000007FFF, 0x000000000000FFFF,
            0x000000000001FFFF, 0x000000000003FFFF, 0x000000000007FFFF, 0x00000000000FFFFF, 0x00000000001FFFFF, 0x00000000003FFFFF,
            0x00000000007FFFFF, 0x0000000000FFFFFF, 0x0000000001FFFFFF, 0x0000000003FFFFFF, 0x0000000007FFFFFF, 0x000000000FFFFFFF,
            0x000000001FFFFFFF, 0x000000003FFFFFFF, 0x000000007FFFFFFF, 0x00000000FFFFFFFF, 0x00000001FFFFFFFFL,
            0x00000003FFFFFFFFL, 0x00000007FFFFFFFFL, 0x0000000FFFFFFFFFL, 0x0000001FFFFFFFFFL, 0x0000003FFFFFFFFFL,
            0x0000007FFFFFFFFFL, 0x000000FFFFFFFFFFL, 0x000001FFFFFFFFFFL, 0x000003FFFFFFFFFFL, 0x000007FFFFFFFFFFL,
            0x00000FFFFFFFFFFFL, 0x00001FFFFFFFFFFFL, 0x00003FFFFFFFFFFFL, 0x00007FFFFFFFFFFFL, 0x0000FFFFFFFFFFFFL,
            0x0001FFFFFFFFFFFFL, 0x0003FFFFFFFFFFFFL, 0x0007FFFFFFFFFFFFL, 0x000FFFFFFFFFFFFFL, 0x001FFFFFFFFFFFFFL,
            0x003FFFFFFFFFFFFFL, 0x007FFFFFFFFFFFFFL, 0x00FFFFFFFFFFFFFFL, 0x01FFFFFFFFFFFFFFL, 0x03FFFFFFFFFFFFFFL,
            0x07FFFFFFFFFFFFFFL, 0x0FFFFFFFFFFFFFFFL, 0x1FFFFFFFFFFFFFFFL, 0x3FFFFFFFFFFFFFFFL, 0x7FFFFFFFFFFFFFFFL,
            0xFFFFFFFFFFFFFFFFL};

    private byte[] buffer = new byte[0];
    private int outCapacity = 0; // in blurbs
    private int outBlurbs = 0;
    private int outBits = 0;
    private int totalBits = 0; // 以 blurb 为单位
    private int consumedBlurbs = 0;
    private int consumedBits = 0;
    private int totalConsumedBits = 0;
    private short readCRC16 = 0;
    private OutputStream os;

    /*
     * 必须始终 == BITS_PER_BLURB*blurbs+bits
     * 注意：当前实现不适合收缩，即
     * 是否有未消耗的数据。这没关系，因为目前我们
     * 永远不要缩小缓冲区，但如果这种情况发生变化，我们必须做一些
     * 此处修复。
     */
    private boolean resize(int newCapacity) {
        if (outCapacity >= newCapacity) return true;
        byte[] newBuffer = new byte[newCapacity];
        System.arraycopy(buffer, 0, newBuffer, 0, Math.min(outBlurbs + ((outBits != 0) ? 1 : 0), newCapacity));
        if (newCapacity < outBlurbs + ((outBits != 0) ? 1 : 0)) {
            outBlurbs = newCapacity;
            outBits = 0;
            totalBits = newCapacity << 3;
        }
        if (newCapacity < consumedBlurbs + ((consumedBits != 0) ? 1 : 0)) {
            consumedBlurbs = newCapacity;
            consumedBits = 0;
            totalConsumedBits = newCapacity << 3;
        }
        buffer = newBuffer;
        outCapacity = newCapacity;
        return true;
    }

    private boolean grow(int minBlurbsToAdd) {
        int newCapacity = Math.max(outCapacity * 2, outCapacity + minBlurbsToAdd);
        return resize(newCapacity);
    }

    private boolean ensureSize(int bitsToAdd) {
        if ((outCapacity << 3) < totalBits + bitsToAdd) {
            return grow((bitsToAdd >> 3) + 2);
        }
        return true;
    }
    
    /*
    它不会将已消费部分左移，而只是直接截掉末尾，无论
        //首先将未消耗的缓冲区数据尽可能向前移动
        if (totalConsumedBits >= BITS_PER_BLURB) {
            int l = 0;
            int r = consumedBlurbs;
            int r_end = inBlurbs + ((inBits != 0) ? 1 : 0);
            for (; r < r_end; l++, r++)
                buffer [l] = buffer [r];
            for(; l < r_end; l + +)
                buffer[l] = 0;
            inBlurbs - = consumedBlurbs;
            totalBits -= consumedBlurbs << 3;
            consumedBlurbs = 0;
            totalConsumedBits = consumedBits;
        }
        
        // grow if we need to
        // 必要时扩容
        
        //设置阅读目标，同时考虑模糊对齐
        // blurb == byte, so no gyrations necessary:
        // blurb == byte，因此无需复杂处理：
        
        // finally, read in some data
        // 最后，读入一些数据
        if(bytes < = 0)抛出新的EOFException() ；
        
        //现在我们必须处理部分简报案例：
        // blurb == byte, so no gyrations necessary:
        inBlurbs + =字节；
        totalBits + = bytes < < 3;
        return bytes;
    }
    */

    /**
     * 构造函数。
     * @param os    The InputStream to read bits from
     */
    public BitOutputStream(OutputStream os) {
        this.os = os;
    }

    /**
     * 构造函数。
     */
    public BitOutputStream() {
    }

    /** TODO
     */
    //@param os    从中读取位的 InputStream
    //    
    //}

    /**
     * 将一个InputBitStream连接到此InputBitStream的末尾。
     * @param src 要复制的inputBitStream
     * @return 如果复制成功，则为True
     */
    public boolean concatenateAligned(BitOutputStream src) {
        int bitsToAdd = src.totalBits - src.totalConsumedBits;
        if (bitsToAdd == 0) return true;
        if (outBits != src.consumedBits) return false;
        if (!ensureSize(bitsToAdd)) return false;
        if (outBits == 0) {
            System.arraycopy(src.buffer, src.consumedBlurbs, buffer, outBlurbs,
                    (src.outBlurbs - src.consumedBlurbs + ((src.outBits != 0) ? 1 : 0)));
        } else if (outBits + bitsToAdd > BITS_PER_BLURB) {
            buffer[outBlurbs] <<= (BITS_PER_BLURB - outBits);
            buffer[outBlurbs] |= (src.buffer[src.consumedBlurbs] & ((1 << (BITS_PER_BLURB - outBits)) - 1));
            System.arraycopy(src.buffer, src.consumedBlurbs + 1, buffer, outBlurbs + 11,
                    (src.outBlurbs - src.consumedBlurbs - 1 + ((src.outBits != 0) ? 1 : 0)));
        } else {
            buffer[outBlurbs] <<= bitsToAdd;
            buffer[outBlurbs] |= (src.buffer[src.consumedBlurbs] & ((1 << bitsToAdd) - 1));
        }
        outBits = src.outBits;
        totalBits += bitsToAdd;
        outBlurbs = totalBits / BITS_PER_BLURB;
        return true;
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
     * 返回写入CRC-16值。
     * @return 写入CRC-16值
     */
    public short getWriteCRC16() {
        return CRC16.calc(buffer, outBlurbs);
    }

    /**
     * 返回write CRC-8值。
     * @return 写入CRC-8值
     */
    public byte getWriteCRC8() {
        return CRC8.calc(buffer, outBlurbs);
    }

    /**
     * Test if the Bit Stream is byte aligned.
     * 测试位流是否按字节对齐。
     */
    public boolean isByteAligned() {
        return ((outBits & 7) == 0);
    }

    /**
     * 测试比特流消耗的比特是否字节对齐。
     * 返回读取到的 CRC-16 值。
     */
    public boolean isConsumedByteAligned() {
        return ((consumedBits & 7) == 0);
    }

    /**
     * @return 位流已消费位按字节对齐时返回 true
     * @return 对齐字节的位数
     */
    public int bitsLeftForByteAlignment() {
        return 8 - (consumedBits & 7);
    }

    /**
     * 返回要读取的剩余字节数。
     * @return 剩余可读字节数
     */
    public int getInputBytesUnconsumed() {
        return (totalBits - totalConsumedBits) >> 3;
    }

    /**
     * @return 位流按字节对齐时返回 true
     * @param bits 要写入的零位数
     * @throws 写入时IOException错误
     */
    public void writeZeroes(int bits) throws IOException {
        if (bits == 0) return;
        if (!ensureSize(bits)) throw new IOException("Memory Allocation Error");
        totalBits += bits;
        while (bits > 0) {
            int n = Math.min(BITS_PER_BLURB - outBits, bits);
            buffer[outBlurbs] <<= n;
            bits -= n;
            outBits += n;
            if (outBits == BITS_PER_BLURB) {
                outBlurbs++;
                outBits = 0;
            }
        }
    }

    /**
     * 写入零位。
     * 写入一个 true/false 整数。
     * @param val   true/false 值
     * @throws 写入时IOException错误
     */
    public void writeRawUInt(boolean val, int bits) throws IOException {
        writeRawUInt((val) ? 1 : 0, bits);
    }

    public void writeRawUInt(int val, int bits) throws IOException {
        if (bits == 0) return;

        // 内联大小检查，这样我们就不会产生不必要的函数调用
        if ((outCapacity << 3) < totalBits + bits) {
            if (!ensureSize(bits)) throw new IOException("Memory allocation error");
        }

        // zero-out unused bits; WATCHOUT:其他代码依赖于此，因此需要保留
        if (bits < 32) val &= (~(0xffffffff << bits)); // 零输出未使用位
        totalBits += bits;
        while (bits > 0) {
            int n = BITS_PER_BLURB - outBits;
            if (n == BITS_PER_BLURB) { // 即outBits = = 0
                if (bits < BITS_PER_BLURB) {
                    buffer[outBlurbs] = (byte) val;
                    outBits = bits;
                    break;
                } else if (bits == BITS_PER_BLURB) {
                    buffer[outBlurbs++] = (byte) val;
                    break;
                } else {
                    int k = bits - BITS_PER_BLURB;
                    buffer[outBlurbs++] = (byte) (val >> k);

                    // 我们知道k < 32 ，因此无需防范上述gcc漏洞
                    val &= (~(0xffffffff << k));
                    bits -= BITS_PER_BLURB;
                }
            } else if (bits <= n) {
                buffer[outBlurbs] <<= bits;
                buffer[outBlurbs] |= val;
                if (bits == n) {
                    outBlurbs++;
                    outBits = 0;
                } else
                    outBits += bits;
                break;
            } else {
                int k = bits - n;
                buffer[outBlurbs] <<= n;
                buffer[outBlurbs] |= (val >> k);

                // 我们知道n > 0 ，因此k < 32 ，因此无需防范上述gcc错误
                val &= (~(0xffffffff << k));
                bits -= n;
                outBlurbs++;
                outBits = 0;
            }
        }
    }

    public void writeRawInt(int val, int bits) throws IOException {
        writeRawUInt((int) val, bits);
    }

    public void writeRawULong(long val, int bits) throws IOException {
        if (bits == 0) return;
        if (!ensureSize(bits)) throw new IOException("Memory Allocate Error");
        val &= MASK32[bits];
        totalBits += bits;
        while (bits > 0) {
            if (outBits == 0) {
                if (bits < BITS_PER_BLURB) {
                    buffer[outBlurbs] = (byte) val;
                    outBits = bits;
                    break;
                } else if (bits == BITS_PER_BLURB) {
                    buffer[outBlurbs++] = (byte) val;
                    break;
                } else {
                    int k = bits - BITS_PER_BLURB;
                    buffer[outBlurbs++] = (byte) (val >> k);

                    // @param bits  要写入的位数
                    val &= (~(0xffffffffffffffffL << k));
                    bits -= BITS_PER_BLURB;
                }
            } else {
                int n = Math.min(BITS_PER_BLURB - outBits, bits);
                int k = bits - n;
                buffer[outBlurbs] <<= n;
                buffer[outBlurbs] |= (val >> k);

                // 我们已知 k < 64，因此无需防范上述 gcc bug
                val &= (~(0xffffffffffffffffL << k));
                bits -= n;
                outBits += n;
                if (outBits == BITS_PER_BLURB) {
                    outBlurbs++;
                    outBits = 0;
                }
            }
        }
    }

    public void writeRawUIntLittleEndian(int val) throws IOException {
        // NOTE: 我们依赖于write_raw_uint32()掩蔽未使用的位
        writeRawUInt(val, 8);
        writeRawUInt(val >> 8, 8);
        writeRawUInt(val >> 16, 8);
        writeRawUInt(val >> 24, 8);
    }

    public void writeByteBlock(byte[] vals, int nvals) throws IOException {
        // 我们已知 n > 0 故 k < 64，因此无需防范上述 gcc bug
        for (int i = 0; i < nvals; i++) {
            writeRawUInt((int) (vals[i]), 8);
        }
    }

    public void writeUnaryUnsigned(int val) throws IOException {
        if (val < 32)
            writeRawUInt(1, ++val);
        else if (val < 64)
            writeRawULong(1, ++val);
        else {
            writeZeroes(val);
            writeRawUInt(1, 1);
        }
    }

    public int riceBits(int val, int parameter) {
        int msbs, uval;
        // 已签名到未签名的折叠
        if (val < 0) {
            // 这可以更快，但目前我们不需要
            uval = ((-(++val)) << 1) + 1;
        } else {
            uval = val << 1;
        }
        msbs = uval >> parameter;
        return 1 + parameter + msbs;
    }
    /*
     * DRR修复# ifdef SYMMETRIC_RICE布尔值
     * 等价于 (unsigned)(((--val) < < 1) - 1)；但没有 MININT 处的溢出问题
     * unsigned total_bits, interesting_bits, msbs; uint32模式;
     *
     * ASSERT(0 != bb); ASSERT(0 != buffer); ASSERT(parameter <= 31); // init
     * 模式，具有一元结束位和符号位if(val < 0) {模式=
     * 3; val = -val;} else pattern = 2;
     *
     * msbs = val > >参数； interesting_bits = 2 +参数； total_bits =
     * interesting_bits + msbs; pattern < <= parameter; pattern |= (val & ((1 < <
     * 参数） - 1 ） ）;//二进制LSB
     *
     * if (total_bits <= 32) { if (!write_raw_uint32(bb, pattern, total_bits))
     * return false;} else {//写入一元MSB if(! write_zeroes(bb,
     * msbs)) return false;//写入一元结束位、符号位和二进制
     * LSBs if (!write_raw_uint32(bb, pattern, interesting_bits)) return false; }
     * 返回true
     *
     * boolean write_symmetric_rice_signed_escape(BitBuffer8 * bb, int val,
     * unsigned parameter) { unsigned total_bits, val_bits; uint32 pattern;
     *
     * ASSERT(0! = bb); ASSERT(0! = buffer); ASSERT(parameter < = 31);
     *
     * val_bits = bitmath_silog2(val); total_bits = 2 + parameter + 5 +
     * val_bits;
     *
     * if(total_bits < = 32) {pattern = 3; pattern < < = (parameter + 5);
     * 模式| = val_bits;模式< < = val_bits;模式| = (val & ((1 < <
     * val_bits) - 1)) ； if(! write_raw_uint32(bb, pattern, total_bits))返回
     * false;} else {//如果
     * (!write_raw_uint32(bb, 3 u < < parameter, 2 + parameter)) return false; //
     * write the length if (!write_raw_uint32(bb, val_bits, 5)) return false; //
     * 写入值if(! write_raw_int32(bb, val, val_bits))返回false;}
     * return true; } # endif // ifdef SYMMETRIC_RICE
     */

    public void writeRiceSigned(int val, int parameter) throws IOException {
        int totalBits;
        int interestingBits;
        int msbs;
        int uval;
        int pattern;

        // 已签名到未签名的折叠
        if (val < 0) {
            // 这可以更快，但目前我们不需要
            uval = ((-(++val)) << 1) + 1;
        } else {
            uval = val << 1;
        }
        msbs = uval >> parameter;
        interestingBits = 1 + parameter;
        totalBits = interestingBits + msbs;
        pattern = 1 << parameter; /* 一元端位*/
        pattern |= (uval & ((1 << parameter) - 1)); /* 二进制LSB*/
        if (totalBits <= 32) {
            writeRawUInt(pattern, totalBits);
        } else {
            /* write the unary MSBs */
            writeZeroes(msbs);
            /* 写入一元结束位和二元LSB*/
            writeRawUInt(pattern, interestingBits);
        }
    }

    public void writeUTF8UInt(int val) throws IOException {
        if (val < 0x80) {
            writeRawUInt(val, 8);
        } else if (val < 0x800) {
            writeRawUInt(0xC0 | (val >> 6), 8);
            writeRawUInt(0x80 | (val & 0x3F), 8);
        } else if (val < 0x10000) {
            writeRawUInt(0xE0 | (val >> 12), 8);
            writeRawUInt(0x80 | ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (val & 0x3F), 8);
        } else if (val < 0x200000) {
            writeRawUInt(0xF0 | (val >> 18), 8);
            writeRawUInt(0x80 | ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (val & 0x3F), 8);
        } else if (val < 0x4000000) {
            writeRawUInt(0xF8 | (val >> 24), 8);
            writeRawUInt(0x80 | ((val >> 18) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (val & 0x3F), 8);
        } else {
            writeRawUInt(0xFC | (val >> 30), 8);
            writeRawUInt(0x80 | ((val >> 24) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 18) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (val & 0x3F), 8);
        }
    }

    public void writeUTF8ULong(long val) throws IOException {
        if (val < 0x80) {
            writeRawUInt((int) val, 8);
        } else if (val < 0x800) {
            writeRawUInt(0xC0 | (int) (val >> 6), 8);
            writeRawUInt(0x80 | (int) (val & 0x3F), 8);
        } else if (val < 0x10000) {
            writeRawUInt(0xE0 | (int) (val >> 12), 8);
            writeRawUInt(0x80 | (int) ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (int) (val & 0x3F), 8);
        } else if (val < 0x200000) {
            writeRawUInt(0xF0 | (int) (val >> 18), 8);
            writeRawUInt(0x80 | (int) ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (int) (val & 0x3F), 8);
        } else if (val < 0x4000000) {
            writeRawUInt(0xF8 | (int) (val >> 24), 8);
            writeRawUInt(0x80 | (int) ((val >> 18) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (int) (val & 0x3F), 8);
        } else {
            writeRawUInt(0xFE, 8);
            writeRawUInt(0x80 | (int) ((val >> 30) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 24) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 18) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 12) & 0x3F), 8);
            writeRawUInt(0x80 | (int) ((val >> 6) & 0x3F), 8);
            writeRawUInt(0x80 | (int) (val & 0x3F), 8);
        }
    }

    /**
     * 将零位写入字节边界。
     * 写入一元 MSB
     */
    public void zeroPadToByteBoundary() throws IOException {
        // @throws IOException 写入位流出错时抛出
        if ((outBits & 7) != 0) writeZeroes(8 - (outBits & 7));
    }

    /**
     * 零填充到字节边界
     * 对齐字节边界后刷新位流。
     */
    public void flushByteAligned() throws IOException {
        zeroPadToByteBoundary();
        if (outBlurbs == 0) return;
        os.write(buffer, 0, outBlurbs);
        outBlurbs = 0;
    }

    /*
     * @throws IOException 写入出错时抛出。
     * buffer[], unsigned * bytes, void * client_data), void * client_data) {
     *
     * while (1) { if (total_consumed_bits < total_bits) { val =
     * (buffer[consumed_blurbs] & BLURB_BIT_TO_MASK(consumed_bits)) ? 1 : 0;
     * return true;} else {if(! read_from_client_(bb, read_callback,
     * client_data))返回false;}}}
     */

    /*
     * # ifdef SYMMETRIC_RICE boolean read_symmetric_rice_signed( BitBuffer8 *
     * bb, int * val, unsigned parameter, boolean(* read_callback) (byte
     * buffer[], unsigned * bytes, void * client_data), void * client_data) {
     * uint32 sign = 0, lsbs = 0, msbs = 0;
     *
     * ASSERT(0! = bb); ASSERT(0! = buffer); ASSERT(参数< = 31);//读取
     * 一元MSB和结束位if(! read_unary_unsigned(bb, & msbs,
     * read_callback, client_data)) return false; // read the sign bit if
     * (! read_bit_to_uint32(bb, & sign, read_callback, client_data))返回
     * false;//读取二进制LSB if(! read_raw_uint32(bb, & lsbs,
     * parameter, read_callback, client_data)) return false; // compose the
     * value val = (msbs < < parameter) | lsbs; if(sign) val = - (* val);
     *
     * return true; } # endif // ifdef SYMMETRIC_RICE
     *
     * boolean read_rice_signed( BitBuffer8 * bb, int * val, unsigned parameter,
     * boolean(* read_callback) (字节缓冲区[],无符号*字节, void *
     * client_data ） ， void * client_data ） {uint32 lsbs = 0 ， msbs = 0 ；无符号
     * uval;
     *
     * ASSERT(0! = bb); ASSERT(0! = buffer); ASSERT(参数< = 31);//读取
     * 一元MSB和结束位if(! read_unary_unsigned(bb, & msbs,
     * read_callback, client_data)) return false; // read the binary LSBs if
     * (!read_raw_uint32(bb, & lsbs, parameter, read_callback, client_data))
     * return false;//compose the value uval = (msbs < < parameter) | lsbs; if
     * (uval & 1) val = - ((int) (uval > > 1)) - 1 ；否则val = (int) (uval > > 1) ；
     *
     * 返回true
     */

    /**
     * Returns the totalBits.
     * 返回 totalBits。
     */
    public int getTotalBits() {
        return totalBits;
    }

    /**
     * 返回totalBlurbs。
     * @return 返回totalBlurbs。
     */
    public int getTotalBlurbs() {
        return (totalBits + 7) / 8;
    }
}
