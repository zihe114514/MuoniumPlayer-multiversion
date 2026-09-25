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

import java.util.LinkedList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 按时间排序顺序存储对象。
 */
public class ScheduledQueue<T> {
    private final SortedMap<TimeStamp, List<T>> timeNodes;

    public ScheduledQueue() {
        timeNodes = new TreeMap<>();
    }

    public boolean isEmpty() {
        return timeNodes.isEmpty();
    }

    public synchronized void add(TimeStamp time, T obj) {
        List<T> timeList = timeNodes.computeIfAbsent(time, k -> new LinkedList<>());
        timeList.add(obj);
    }

    public synchronized List<T> removeNextList(TimeStamp time) {
        List<T> timeList = null;
        if (!timeNodes.isEmpty()) {
            TimeStamp lowestTime = timeNodes.firstKey();
            // 为指定时间之前的最低时间或等于指定时间。
            if (lowestTime.compareTo(time) <= 0) {
                timeList = timeNodes.remove(lowestTime);
            }
        }
        return timeList;
    }

    public synchronized Object removeNext(TimeStamp time) {
        Object next = null;
        if (!timeNodes.isEmpty()) {
            TimeStamp lowestTime = timeNodes.firstKey();
            // 为指定时间之前的最低时间或等于指定时间。
            if (lowestTime.compareTo(time) <= 0) {
                List<T> timeList = timeNodes.get(lowestTime);
                if (timeList != null) {
                    next = timeList.remove(0);
                    if (timeList.isEmpty()) {
                        timeNodes.remove(lowestTime);
                    }
                }
            }
        }
        return next;
    }

    public synchronized void clear() {
        timeNodes.clear();
    }

    public TimeStamp getNextTime() {
        return timeNodes.firstKey();
    }

}
