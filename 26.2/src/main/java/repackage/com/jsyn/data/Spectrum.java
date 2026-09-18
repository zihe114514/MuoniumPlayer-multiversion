/*
 * 版权所有2013 Phil Burk ， Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.data;


/**
 * 具有真实和虚构部分的复杂光谱。与每个箱柜关联的频率
 * 频谱是：
 * 
 * <pre>
 * frequency = binIndex * sampleRate/size
 * </pre>
 * 
 * 请注意，频谱的上半部分在奈奎斯特频率之上。这些频率是
 * 在奈奎斯特频率周围镜像。请注意，此频谱API是实验性的，可能会发生变化
 * 在任意时刻。
 * 
 * @author Phil Burk (C) 2013 Mobileer Inc
 * @version 016
 */
public class Spectrum {
    private double[] real;
    private double[] imaginary;
    public static final int DEFAULT_SIZE_LOG_2 = 9;
    public static final int DEFAULT_SIZE = 1 << DEFAULT_SIZE_LOG_2;

    public Spectrum() {
        this(DEFAULT_SIZE);
    }

    public Spectrum(int size) {
        setSize(size);
    }

    public double[] getReal() {
        return real;
    }

    public double[] getImaginary() {
        return imaginary;
    }

    /**
     * 若更改频谱大小，则实部和虚部数组将被
     * 另行拨付*
     * 
     * @param size
     */
    public void setSize(int size) {
        if ((real == null) || (real.length != size)) {
            real = new double[size];
            imaginary = new double[size];
        }
    }

    public int size() {
        return real.length;
    }

    /**
     * 将此频谱复制到另一个等长的频谱。
     * 
     * @param destination
     */
    public void copyTo(Spectrum destination) {
        assert (size() == destination.size());
        System.arraycopy(real, 0, destination.real, 0, real.length);
        System.arraycopy(imaginary, 0, destination.imaginary, 0, imaginary.length);
    }

    public void clear() {
        for (int i = 0; i < real.length; i++) {
            real[i] = 0.0;
            imaginary[i] = 0.0;
        }
    }
}
