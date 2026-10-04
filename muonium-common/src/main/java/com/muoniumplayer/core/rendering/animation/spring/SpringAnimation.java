package com.muoniumplayer.core.rendering.animation.spring;

import lombok.Getter;
import java.util.function.DoubleUnaryOperator;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/12/11 23:28
 */
public class SpringAnimation {

    @Getter
    private double currentPosition;
    private double targetPosition;
    private double currentTime = 0.0;

    private SpringParams params = new SpringParams();

    private QueuedParams queueParams = null;
    private QueuedPosition queuePosition = null;

    private static final DoubleUnaryOperator ZERO = t -> 0.0;
    private final DoubleUnaryOperator stationarySolver = t -> this.targetPosition;
    private boolean settled = true;

    private DoubleUnaryOperator currentSolver;
    private DoubleUnaryOperator getV;
    private DoubleUnaryOperator getV2;

    public SpringAnimation(double current) {
        this.targetPosition = current;
        this.currentPosition = current;
        this.currentSolver = this.stationarySolver;
        this.getV = ZERO;
        this.getV2 = ZERO;
    }

    public SpringAnimation() {
        this(0.0);
    }

    public SpringAnimation(SpringParams params) {
        this();
        this.setParams(params);
    }

    private DoubleUnaryOperator solveSpring(double from, double velocity, double to, SpringParams p) {
        double delta = to - from;

        double mass = p.mass;
        double damping = p.damping;
        double stiffness = p.stiffness;
        boolean soft = p.soft;

        if (soft || damping >= 2.0 * Math.sqrt(stiffness * mass)) {
            double angular_freq = -Math.sqrt(stiffness / mass);
            double leftover = -angular_freq * delta - velocity;

            // Analytical derivatives avoid re-evaluating position 2/4 times for
            // velocity/acceleration, and retain momentum when the target changes.
            this.getV = t -> -(leftover + angular_freq * (delta + t * leftover))
                    * Math.exp(t * angular_freq);
            this.getV2 = t -> -(2.0 * angular_freq * leftover
                    + angular_freq * angular_freq * (delta + t * leftover))
                    * Math.exp(t * angular_freq);
            return t -> to - (delta + t * leftover) * Math.exp(t * angular_freq);
        }

        double damp_freq = Math.sqrt(4.0 * mass * stiffness - damping * damping);
        double leftover = (damping * delta - 2.0 * mass * velocity) / damp_freq;
        double dfm = (0.5 * damp_freq) / mass;
        double dm = -(0.5 * damping) / mass;

        this.getV = t -> -Math.exp(t * dm) * (
                dm * (Math.cos(t * dfm) * delta + Math.sin(t * dfm) * leftover)
                        + dfm * (-Math.sin(t * dfm) * delta + Math.cos(t * dfm) * leftover));
        this.getV2 = t -> -Math.exp(t * dm) * (
                (dm * dm - dfm * dfm) * (Math.cos(t * dfm) * delta + Math.sin(t * dfm) * leftover)
                        + 2.0 * dm * dfm * (-Math.sin(t * dfm) * delta + Math.cos(t * dfm) * leftover));
        return t -> to - (Math.cos(t * dfm) * delta + Math.sin(t * dfm) * leftover) * Math.exp(t * dm);
    }

    public void update(double delta) {
        this.currentTime += delta;
        if (!this.settled) {
            this.currentPosition = this.currentSolver.applyAsDouble(this.currentTime);
        }

        if (this.queueParams != null) {
            this.queueParams.time -= delta;
            if (this.queueParams.time <= 0) {
                this.setParams(queueParams.params);
                this.queueParams = null;
            }
        }

        if (this.queuePosition != null) {
            this.queuePosition.time -= delta;
            if (this.queuePosition.time <= 0) {
                this.setTargetPosition(queuePosition.position);
                this.queuePosition = null;
            }
        }

        if (!this.settled && this.isArrived()) {
            this.setPosition(this.targetPosition);
        }
    }

    public void setPosition(double position) {
        this.targetPosition = position;
        this.currentPosition = position;
        this.currentTime = 0.0;
        this.settled = true;
        this.currentSolver = this.stationarySolver;
        this.getV = ZERO;
        this.getV2 = ZERO;
    }

    public void setTargetPosition(double position, double delay) {

        // UI layout submits its target every frame. Do not restart an unchanged
        // spring or allocate new solver lambdas on that hot path.
        if (delay <= 0.0 && position == this.targetPosition && this.queuePosition == null)
            return;

        if (position == this.targetPosition && (this.queuePosition != null && this.queuePosition.time == delay))
            return;

        if (delay > 0) {
            this.queuePosition = new QueuedPosition(position, delay);
        } else {
            this.queuePosition = null;
            this.targetPosition = position;
            this.resetSolver();
        }
    }

    public void setTargetPosition(double position) {
        this.setTargetPosition(position, 0.0);
    }

    public void setParams(SpringParams newParams, double delay) {
        if (delay > 0) {
            this.queueParams = new QueuedParams(newParams, delay);
        } else {
            this.params = newParams;
            if (!this.settled) {
                this.resetSolver();
            }
        }
    }

    public void setParams(SpringParams newParams) {
        this.setParams(newParams, 0.0);
    }

    public boolean isArrived() {
        return this.queueParams == null && this.queuePosition == null
                && (this.settled || (Math.abs(this.targetPosition - this.currentPosition) < 0.01
                && Math.abs(this.getV.applyAsDouble(this.currentTime)) < 0.01
                && Math.abs(this.getV2.applyAsDouble(this.currentTime)) < 0.01));
    }

    private void resetSolver() {
        double v = this.getV.applyAsDouble(this.currentTime);
        this.currentTime = 0.0;

        this.settled = false;
        this.currentSolver = this.solveSpring(this.currentPosition, v, this.targetPosition, this.params);
    }
}