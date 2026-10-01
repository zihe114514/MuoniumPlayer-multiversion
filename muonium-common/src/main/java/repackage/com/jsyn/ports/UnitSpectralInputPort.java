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

package repackage.com.jsyn.ports;

import repackage.com.jsyn.data.Spectrum;

public class UnitSpectralInputPort extends UnitPort implements ConnectableInput {
    private UnitSpectralOutputPort other;

    private Spectrum spectrum;

    public UnitSpectralInputPort() {
        this("Output");
    }

    public UnitSpectralInputPort(String name) {
        super(name);
    }

    public void setSpectrum(Spectrum spectrum) {
        this.spectrum = spectrum;
    }

    public Spectrum getSpectrum() {
        if (other == null) {
            return spectrum;
        } else {
            return other.getSpectrum();
        }
    }

    @Override
    public void connect(ConnectableOutput other) {
        if (other instanceof UnitSpectralOutputPort) {
            this.other = (UnitSpectralOutputPort) other;
        } else {
            throw new RuntimeException(
                    "Can only connect UnitSpectralOutputPort to UnitSpectralInputPort!");
        }
    }

    @Override
    public void disconnect(ConnectableOutput other) {
        if (this.other == other) {
            this.other = null;
        }
    }

    @Override
    public PortBlockPart getPortBlockPart() {
        return null;
    }

    @Override
    public void pullData(long frameCount, int start, int limit) {
        if (other != null) {
            other.getUnitGenerator().pullData(frameCount, start, limit);
        }
    }

    public boolean isAvailable() {
        if (other != null) {
            return other.isAvailable();
        } else {
            return (spectrum != null);
        }
    }

}
