/*
 * Copyright 2009 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn.io;

public interface AudioInputStream {
    double read();

    /**
     * 尝试填充整个缓冲区。
     * 
     * @param buffer
     * @return 读取样本数
     */
    int read(double[] buffer);

    /**
     * 从流中读取。阻止，直到一些数据可用。
     * 
     * @param buffer
     * 缓冲液中第一个样品的@param start 指数
     * @param count 要读取的采样数，例如 count=8 表示 4 个立体声帧
     * @return 读取样本数
     */
    int read(double[] buffer, int start, int count);

    void close();

    /**
     * @return 当前可用于无阻塞读取的样本数
     */
    int available();
}
