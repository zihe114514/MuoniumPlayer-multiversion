package com.muoniumplayer.core.rendering.animation.spring;

/**
 */
public class QueuedParams {
    SpringParams params;
    double time;

    public QueuedParams(SpringParams params, double time) {
        this.params = params;
        this.time = time;
    }
}
