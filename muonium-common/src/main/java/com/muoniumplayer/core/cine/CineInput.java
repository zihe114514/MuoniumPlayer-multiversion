package com.muoniumplayer.core.cine;

import java.util.HashSet;
import java.util.Set;

/** 客户端线程的真实输入闩；按住状态与事件沿分开，不从玩家位移/朝向推断输入。 */
public final class CineInput {

    private final Set<Integer> keys = new HashSet<>();
    private final Set<Integer> buttons = new HashSet<>();
    private boolean activity;
    private boolean cursorKnown;
    private double cursorX;
    private double cursorY;

    /** action: 0 释放，1 按下，2 重复；七树宿主回调已分别核实此语义。 */
    public void key(int key, int action) {
        if (key < 0) return;
        if (action == 0) keys.remove(key);
        else keys.add(key);
        activity = true;
    }

    public void button(int button, int action) {
        if (button < 0) return;
        if (action == 0) buttons.remove(button);
        else buttons.add(button);
        activity = true;
    }

    public void scroll(double x, double y) {
        if (x != 0d || y != 0d) activity = true;
    }

    public void move(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return;
        if (cursorKnown && (x != cursorX || y != cursorY)) activity = true;
        cursorX = x;
        cursorY = y;
        cursorKnown = true;
    }

    public void relativeMove(double dx, double dy) {
        if (Double.isFinite(dx) && Double.isFinite(dy) && (dx != 0d || dy != 0d)) activity = true;
    }

    public boolean consume(boolean focused, boolean mappedKeyDown) {
        if (!focused) {
            clear();
            return true;
        }
        boolean result = activity || !keys.isEmpty() || !buttons.isEmpty() || mappedKeyDown;
        activity = false;
        return result;
    }

    public void clear() {
        keys.clear();
        buttons.clear();
        activity = false;
        cursorKnown = false;
    }
}
