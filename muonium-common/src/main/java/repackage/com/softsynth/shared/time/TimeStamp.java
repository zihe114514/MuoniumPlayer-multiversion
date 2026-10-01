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

package repackage.com.softsynth.shared.time;

/**
 * @author Phil Burk, (C) 2009 Mobileer Inc
 */
public class TimeStamp implements Comparable<TimeStamp> {
    private final double time;

    public TimeStamp(double time) {
        this.time = time;
    }

    public double getTime() {
        return time;
    }

    /**
     * @return 若(this &lt; t2) 则为 -1，相等则为 0，否则为 +1
     */
    @Override
    public int compareTo(TimeStamp t2) {
        return Double.compare(time, t2.time);
    }

    /**
     * 以秒为单位的相对偏移量创建新的时间戳。
     *
     * @param delta
     * @return 早晚时间戳
     */
    public TimeStamp makeRelative(double delta) {
        return new TimeStamp(time + delta);
    }

}
