/*
 * Copyright 2010 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn;

import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.engine.SynthesisEngine;

/**
 * 用于 Java 的 JSyn 合成器。使用此工厂类创建合成器。以下代码演示
 * 如何开始播放正弦波：
 *
 * <pre><code>
	// 为合成器创建上下文。
	synth = JSyn.createSynthesizer();

	//使用44100 Hz的默认立体声输出启动合成器。
	synth.start();

	//添加音调生成器。
	synth.add(osc = new SineOscillator());
	//添加立体声音频输出单元。
	synth.add(lineOut = new LineOut());

	//将振荡器连接到输出的两个通道。
	osc.output.connect(0, lineOut.input, 0);
	osc.output.connect( 0, lineOut.input, 1 );

	//设置正弦波的频率和振幅。
	osc.frequency.set(345.0) ；
	osc.amplitude.set( 0.6 );

	// 我们只需启动 LineOut，它会从振荡器拉取数据。
	lineOut.start();
</code> </pre>
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public class JSyn {
    // 为每个版本更新这些内容。
    private final static int VERSION_MAJOR = 17;
    private final static int VERSION_MINOR = 1;
    private final static int VERSION_REVISION = 1;
    public final static int BUILD_NUMBER = 466;
    private final static String BUILD_DATE = "2023-08-28";

    // 这些值由更新后的数值构建。
    public final static String VERSION = VERSION_MAJOR + "." + VERSION_MINOR + "."
            + VERSION_REVISION;
    public final static int VERSION_CODE = (VERSION_MAJOR << 16) + (VERSION_MINOR << 8)
            + VERSION_REVISION;
    public final static String VERSION_TEXT = "V" + VERSION + " (build " + BUILD_NUMBER + ", "
            + BUILD_DATE + ")";

    public static Synthesizer createSynthesizer() {
        return new SynthesisEngine();
    }

    public static Synthesizer createSynthesizer(AudioDeviceManager audioDeviceManager) {
        return new SynthesisEngine(audioDeviceManager);
    }
}
