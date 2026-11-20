package com.muoniumplayer.core.input;

/** Prevents the screen-level GLFW scroll fallback from counting a callback twice. */
public final class WheelCallbackGuard {
    private final ThreadLocal<Integer> callbackDepth = new ThreadLocal<>();

    public boolean shouldAccumulateScreenFallback() {
        Integer depth = callbackDepth.get();
        return depth == null || depth == 0;
    }

    /** Run the original GLFW callback while marking any synchronous Screen scroll it dispatches. */
    public void forwardToScreen(Runnable callback) {
        Integer savedDepth = callbackDepth.get();
        int previousDepth = savedDepth == null ? 0 : savedDepth;
        callbackDepth.set(previousDepth + 1);
        try {
            callback.run();
        } finally {
            if (previousDepth == 0) callbackDepth.remove();
            else callbackDepth.set(previousDepth);
        }
    }
}
