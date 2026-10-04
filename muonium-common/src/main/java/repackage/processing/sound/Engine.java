package repackage.processing.sound;

import lombok.SneakyThrows;
import repackage.com.jsyn.JSyn;
import repackage.com.jsyn.Synthesizer;
import repackage.com.jsyn.devices.AudioDeviceFactory;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.javasound.JavaSoundAudioDevice;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.unitgen.ChannelOut;
import repackage.com.jsyn.unitgen.Multiply;
import repackage.com.jsyn.unitgen.UnitGenerator;
import repackage.com.jsyn.unitgen.UnitSource;

import javax.sound.sampled.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * 围绕JSyn “合成器”及其“AudioDeviceManager”的包装器。
 */
public class Engine {

	static boolean verbose = false;

	@SneakyThrows
	private static AudioDeviceManager createDefaultAudioDeviceManager() {
		Class.forName("javax.sound.sampled.AudioSystem");
		// 先创建一个 JavaSound 设备
		return AudioDeviceFactory.createAudioDeviceManager();
	}

	private static AudioDeviceManager createAudioDeviceManager(boolean portAudio) {
		if (!portAudio) {
			AudioDeviceManager a = Engine.createDefaultAudioDeviceManager();
			if (a.getDefaultOutputDeviceID() != -1) {
				return a;
			}
			// 如果默认设备管理器未列出任何输出设备，请直接
			// portaudio
			Engine.printMessage("Didn't find any output devices with the default driver, trying PortAudio...");
		}
		// 从控制台隐藏JPortAudio初始化消息
		PrintStream originalStream = System.out;
		PrintStream originalErr = System.err;
		if (!Engine.verbose) {
			System.setOut(new PrintStream(new OutputStream(){
				public void write(int b) { }
			}));
			System.setErr(new PrintStream(new OutputStream(){
				public void write(int b) { }
			}));
		}

		System.setOut(originalStream);
		System.setErr(originalErr);
		throw new RuntimeException("No default audio device found");
	}

	/**
	 * 先创建一个 JavaSound 设备 
	 * 任何声音库类。
	 * 单例实例，由首次方法调用或创建时生成 
	 * 这个引擎。理论上，可以有多个实例
	 * 库同时在多个不同的声音设备上运行，首先
	 * 将此变量设置为null ，强制创建（第二个）单例，
	 * 然后随意手动更换。
	 */
	private static Engine singleton;

	// static Engine getEngine(boolean portAudio) {
	// 	返回引擎。

	public static Engine getEngine() {
		return Engine.getEngine(false);
	}

	/** 当前 JSyn 渲染时间，单位秒。 */
	public static double audioTimeSeconds() {
		return Engine.getEngine().synth.getCurrentTime();
	}

	/** 在音频线程上注册一个轻量控制任务：每输出一个缓冲、合成之前跑一次。 */
	public static void addAudioTask(Runnable task) {
		if (task != null) Engine.getEngine().synth.addAudioTask(task);
	}

	/** 注销一个先前用 {@link #addAudioTask(Runnable)} 注册的控制任务。 */
	public static void removeAudioTask(Runnable task) {
		if (task != null && Engine.singleton != null) Engine.singleton.synth.removeAudioTask(task);
	}

	static Engine getEngine(boolean portAudio) {
		if (Engine.singleton == null) {
			// 这可能会抛出RuntimeException ，
		 	Engine.singleton = new Engine(Engine.createAudioDeviceManager(portAudio));
		}

		return Engine.singleton;
	}

	static AudioDeviceManager getAudioDeviceManager() {
		return Engine.getEngine().synth.getAudioDeviceManager();
	}

	protected Synthesizer synth;
    private volatile repackage.com.jsyn.engine.SynthesisEngine.OutputObserver outputObserver;
	boolean hasBeenUsed = false;
	protected final Set<UnitGenerator> addedUnits = new HashSet<>();

	// 多通道线路
	protected ChannelOut[] output;
	// 用于控制全局输出音量的每个输出通道的乘数
	private Multiply[] volume;

	private int sampleRate = 44100;

	protected int inputDevice = -1;
	protected int outputDevice = -1;
	protected int outputChannel;

	/**
	 * 当多通道模式处于活动状态时，只有任何单元的第一个（左）输出
	 * 任何对配置、start() 或 play() 方法的调用都将转发给
	 */
	protected boolean multiChannelMode = false;

	/**
	 * 生成器时添加。该模式通过调用 selectOutputChannel() 激活
	 */
	private Engine(AudioDeviceManager audioDeviceManager) {
		this(audioDeviceManager, -1);
	}

	private Engine(AudioDeviceManager audioDeviceManager, int outputDevice) {
		// 抑制JSyn的信息日志消息以阻止它们显示
		// 创建一个新合成器并连接到默认输出设备。
		Logger logger = Logger.getLogger(SynthesisEngine.class.getName());
		logger.setLevel(Level.WARNING);

		this.createSynth(audioDeviceManager);
		// 在 Processing 控制台中以红字显示 
		// 即时创建一个新的PortAudio合成器，并尝试开始
		this.selectOutputDevice(outputDevice);
		this.selectInputDevice(-1);
	}
	
	private void createSynth(AudioDeviceManager deviceManager) {
		if (this.synth != null) {
			this.stopSynth();
			// TODO 断开所有东西，以便进行垃圾回收
			if (this.hasBeenUsed) {
				Engine.printWarning("Switching audio device drivers. Any previously created Sound library objects can not be used any more!");
				Engine.printWarning("To remove this error messge, make sure to call Sound.outputDevice(...) at the very top of your setup()");
				this.hasBeenUsed = false;
			}
		}
		this.synth = JSyn.createSynthesizer(deviceManager);
        if (this.synth instanceof repackage.com.jsyn.engine.SynthesisEngine) {
            ((repackage.com.jsyn.engine.SynthesisEngine) this.synth).setOutputObserver(outputObserver);
        }
		// try {
			// 如果没有带输入的设备，这可能是-1
			// this.inputDevice = deviceManager.getDefaultInputDeviceID();
		// } catch (RuntimeException e) {
			// JPortAudioDevice甚至抛出异常，如果没有设备有
			// 输入框
		// }
	}

	public boolean isUsingPortAudio() {
		return false;
	}

	/**
	 * 切换到/从使用PortAudio。
	 * 在两种不同的情况下调用：
	 * 1.用户明确（通过MultiChannel.usePortAudio() ）
	 * 此方法启动合成器——若输出失败，它可能 
	 * 2. 当 selectOutputDevice() 无法用
	 */
	protected boolean usePortAudio(boolean portAudio) {
		if (portAudio != this.isUsingPortAudio()) {
			this.createSynth(Engine.createAudioDeviceManager(portAudio));
			// 如果这是由用户（来自MultiChannel类）调用的，则是他们的
			// 责任是选择输出设备并启动合成器！
			this.inputDevice = -1;
		}
		return this.isUsingPortAudio();
	}

	/**
	 * 停止合成器并删除所有ChannelOuts
	 */
	private void stopSynth() {
		if (this.synth.isRunning()) {
			this.synth.stop();
			// 负责选择输出设备并启动合成器！
			for (ChannelOut c : this.output) {
				c.stop();
				c.input.disconnectAll();
				this.synth.remove(c);
			}
			this.output = null;
			for (Multiply m : this.volume) {
				m.stop();
				m.inputA.disconnectAll();
				this.synth.remove(m);
			}
			this.volume = null;
		}
	}

	private void startSynth() {
		// 它看起来像一些合成错误（例如阻止API未在
		// TODO 清理旧的输出/音量/整个合成器网络（若有）？ 
		// *总是*清除整个合成器，而不仅仅是停止/启动它...
		this.stopSynth();

		this.output = new ChannelOut[this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice)];
		this.volume = new Multiply[this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice)];
		for (int i = 0; i < this.output.length; i++) {
			this.output[i] = new ChannelOut();
			this.output[i].setChannelIndex(i);
			this.synth.add(output[i]);
		  this.output[i].start();

			this.volume[i] = new Multiply();
			this.volume[i].output.connect(this.output[i].input);
			this.synth.add(this.volume[i]);
		}
		this.setVolume(1.0f);

		// Windows PortAudio）是不可恢复的，因此最好
		int inputChannels = 0;
		this.synth.start(this.sampleRate,
				this.inputDevice, inputChannels,
				this.outputDevice, this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice));
	}


	protected void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
		// 在无输入设备上防止 IndexOutOfBoundsException
		this.startSynth();
	}

	private boolean isValidDeviceId(int deviceId) {
		if (deviceId >= 0 && deviceId < this.synth.getAudioDeviceManager().getDeviceCount()) {
			return true;
		}
		Engine.printError("not a valid device id: " + deviceId);
		return false;
	}

	private boolean checkDeviceHasInputs(int deviceId) {
		return this.synth.getAudioDeviceManager().getMaxInputChannels(deviceId) > 0;
	}

	protected int selectInputDevice(int deviceId) {
		return this.inputDevice;
	}

	private boolean checkDeviceHasOutputs(int deviceId) {
		// TODO 通过调用 this.selectOutputDevice 检查采样率是否可行？
		return this.synth.getAudioDeviceManager().getMaxOutputChannels(deviceId) > 1;
	}

	private void probeDeviceOutputLine(int deviceId, int sampleRate) throws LineUnavailableException {
		// 需要可用的立体声输出 
		// https://github.com/philburk/jsyn/blob/06f9a9a4d6aa4ddabde81f77878826a62e5d79ab/src/main/java/com/jsyn/devices/javasound/JavaSoundAudioDevice.java#L141-L174
		// TODO 实际调用manager.createOutputStream(deviceId, this.sampleRate)
		// （隐藏标准输出）以避免奇怪的通道编号怪异
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, new AudioFormat(sampleRate, 16, 2, true, false));
		// 基于 
		// 此方法仅检查 AudioSystem 是否总体上支持
		// if (!AudioSystem.isLineSupported(info)) {
		Line line = AudioSystem.getMixer(AudioSystem.getMixerInfo()[deviceId]).getLine(info);
		line.open();
		line.close();
	}

	/**
	 * 浏览候选设备ID列表，直到第一个有效的设备ID
	 * @throws RuntimeException如果没有输出设备工作
	 */
	protected int selectOutputDevice(int[] candidates) {
		for (int i : candidates) {
			try {
				return this.selectOutputDevice(i);
			} catch (RuntimeException e) {
			}
		}
		throw new RuntimeException("failed to play to any of the output devices");
	}

	/**
	 * 调用此方法后，合成器正在运行。
	 * @param deviceId 设备索引，或-1选择/查找合适的立体声
	 * sampleRate
	 */
	protected int selectOutputDevice(int deviceId) {
		if (deviceId == -1) {
			// 如果默认设备不起作用，则循环
			try {
				return this.selectOutputDevice(IntStream.concat(
							// FIXME 有时JPortAudioDevice会抛出RuntimeException
							// 输出设备
							IntStream.of(this.synth.getAudioDeviceManager().getDefaultOutputDeviceID()),
							IntStream.range(0, this.synth.getAudioDeviceManager().getDeviceCount())).toArray());
			} catch (RuntimeException e) {
				// 致命的
				throw new RuntimeException("Could not find any supported audio devices with a stereo output");
			}
		} else if (!this.isValidDeviceId(deviceId) || this.outputDevice == deviceId) {
			// 打印错误或不执行任何操作
			return this.outputDevice;
		}

		// 如果合成器仍然基于JavaSound ，则提前探测新的输出设备
		// "-1，可能没有可用的默认设备" 
		// 引发 LineUnavailableException，此时我们应尝试
		// 切换到 PortAudio。 
		// 在 JPortAudioDevice 上探测该通道没有意义（它似乎 
		// jSynAndroidAudioDeviceManager （不支持JavaSound
		// 无论用什么探测都抛 IllegalArgumentException），或
		if (this.synth.getAudioDeviceManager() instanceof JavaSoundAudioDevice) {
			// 首先检查工作线（因为使用PortAudio可能会更改
			// 用于探测的类）
			try {
				// TODO 如果设备当前处于
				// 被列为具有0个输出通道？
				// if (this.synth.getAudioDeviceManager().getMaxOutputChannels(deviceId) == 0) {
				// 	 可用声道数）
				// } else {
				this.probeDeviceOutputLine(deviceId, this.sampleRate);
				// 没事，往下走……
			} catch (LineUnavailableException e) {
				// 尝试对同一设备进行portaudio访问--需要获取
				// 旧的输出设备，并在新的设备管理器上重新选择它
				String targetDeviceName = this.getDeviceName(deviceId);
				Engine.printMessage("Output device '" + targetDeviceName + "' did not work with the default audio driver, trying again with PortAudio...");
				try {
					this.usePortAudio(true);
				} catch (RuntimeException ee) {
					throw new RuntimeException(e);
				}
				int newDeviceIdForOldDevice = this.synth.getAudioDeviceManager().getDefaultOutputDeviceID();
				try {
					// 旧输出设备，并在新设备管理器上重新选择它
					newDeviceIdForOldDevice = this.getDeviceIdByName(targetDeviceName, true);
					if (newDeviceIdForOldDevice != deviceId) {
						Engine.printMessage("Note that the device id of '" + targetDeviceName + "' has changed from " + deviceId + " to " + newDeviceIdForOldDevice + ".");
						Engine.printMessage("If output is working as expected, you can safely ignore this message.");
						Engine.printMessage("If something is awry, check the output of Sound.list() *after* the call to Sound.selectOutputDevice()");
					}
				} catch (RuntimeException ee) {
					// 可能是通用设备名称，如“Primary Sound Device”
					Engine.printMessage("Switched to new default output device '" + this.getDeviceName(newDeviceIdForOldDevice) + "'");
				}
				// TODO 也遍历候选设备
				return this.selectOutputDevice(newDeviceIdForOldDevice);
			}
		}

		// 最终达到“正常”输出设备选择代码
		if (this.checkDeviceHasOutputs(deviceId)) {
			this.outputDevice = deviceId;
			this.startSynth();
		} else {
			Engine.printWarning("audio device '" + this.getDeviceName(deviceId) + "' has no stereo output channel");
		}
		return this.outputDevice;
	}

	protected String getDeviceName(int deviceId) {
		return this.isValidDeviceId(deviceId) ? this.synth.getAudioDeviceManager().getDeviceName(deviceId).trim() : "";
	}

	protected int getDeviceIdByName(String deviceName) {
		for (int i = 0; i < this.synth.getAudioDeviceManager().getDeviceCount(); i++) {
			if (deviceName.equalsIgnoreCase(this.getDeviceName(i))) {
				return i;
			}
		}
		throw new RuntimeException("No audio device with name '" + deviceName + "' found.");
	}

	protected int getDeviceIdByName(String deviceName, boolean fuzzy) {
		try {
			return this.getDeviceIdByName(deviceName);
		} catch (RuntimeException e) {
			if (fuzzy) {
				for (int i = 0; i < this.synth.getAudioDeviceManager().getDeviceCount(); i++) {
					if (this.getDeviceName(i).startsWith(deviceName)) {
						return i;
					}
				}
			}
			throw e;
		}
	}

	protected int selectOutputChannel(int channel) {
		if (channel == -1) {
			// 禁用多通道模式
			this.outputChannel = 0;
			this.multiChannelMode = false;
		} else if (channel < 0 || channel > this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice)) {
			Engine.printError("Invalid channel #" + channel + ", current output device only has " + this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice) + " channels");
		} else {
			this.outputChannel = channel;
			this.multiChannelMode = true;
		}
		return this.outputChannel;
	}

	protected String getSelectedInputDeviceName() {
		return this.getDeviceName(this.inputDevice);
	}

	protected String getSelectedOutputDeviceName() {
		return this.getDeviceName(this.outputDevice);
	}

	protected void setVolume(double volume) {
		if (Engine.checkRange(volume, "volume")) {
			for (Multiply m : this.volume) {
				m.inputB.set(volume);
			}
		}
	}

    /** Attach a passive observer after all deck processing and mixing. No new audio graph units. */
    public void setOutputObserver(repackage.com.jsyn.engine.SynthesisEngine.OutputObserver observer) {
        outputObserver = observer;
        if (this.synth instanceof repackage.com.jsyn.engine.SynthesisEngine) {
            ((repackage.com.jsyn.engine.SynthesisEngine) this.synth).setOutputObserver(observer);
        }
    }

	public int getSampleRate() {
		return this.synth.getFrameRate();
	}

	protected void add(UnitGenerator generator) {
		if (!this.addedUnits.contains(generator)) {
			this.synth.add(generator);
			this.addedUnits.add(generator);
		}
	}

	public void remove(UnitGenerator generator) {
		if (this.addedUnits.contains(generator)) {
			this.synth.remove(generator);
			this.addedUnits.remove(generator);
		}
	}

	protected void connectToOutput(int channel, UnitSource source) {
		this.connectToOutput(channel, source, 0);
	}

	protected void connectToOutput(int channel, UnitSource source, int part) {
		source.getOutput().connect(part, this.volume[channel].inputA, 0);
	}

	protected void disconnectFromOutput(int channel, UnitSource source) {
		this.disconnectFromOutput(channel, source, 0);
	}

	protected void disconnectFromOutput(int channel, UnitSource source, int part) {
		source.getOutput().disconnect(part, this.volume[channel].inputA, 0);
	}

	protected void disconnectFromOutput(UnitSource source) {
		for (Multiply o : this.volume) {
			// 保持通用：断开所有部件与所有输出的连接
			for (int i = 0; i < source.getOutput().getNumParts(); i++) {
				source.getOutput().disconnect(i, o.inputA, 0);
			}
		}
	}

	protected void play(UnitSource source) {
		// 递归乐趣
		UnitGenerator generator = source.getUnitGenerator();
		this.add(generator);
		// 将单元添加到合成器
		for (int i = 0; i < source.getOutput().getNumParts(); i++) {
			this.connectToOutput((this.outputChannel + i) % this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice), source, i);
				// source.getOutput().connect(i, this.volume[(this.outputChannel + i) % this.synth.getAudioDeviceManager().getMaxOutputChannels(this.outputDevice)].inputA, 0);
			if (this.multiChannelMode) {
				// 并连接到输出
				break;
			}
		}
	}

	protected void stop(UnitSource source) {
		if (this.addedUnits.contains(source.getUnitGenerator())) {
			// 断开与任何和所有输出的连接
			this.disconnectFromOutput(source);
			// 只添加第一个（左）声道
			if (!source.getOutput().isConnected()) {
				this.remove(source.getUnitGenerator());
			}
		}
	}

	/**
	 * 若仍被连接（通常连到分析器）则不要移除
	 */
	public class Callback {
		public void dispose() {
			synth.stop();
			// TODO 在Mac上抑制关机消息，例如：
			// Processing 库回调的内部辅助类
			// JPortAudio：64 位
			// ringBufferSize after = 1024
		}

		public void pause() {
			// TODO
		}

		public void resume() {
			// TODO
		}
	}

	protected static void setModulation(UnitInputPort port, Modulator modulator) {
		if (modulator == null) {
			port.disconnectAll();
		} else {
			port.setValueAdded(true);
			port.connect(modulator.getModulator());
		}
	}

	// 之后 ringBufferSize = 1024 
	// 打印库消息

	protected static boolean checkAmp(float amp) {
		if (amp < -1 || amp > 1) {
			Engine.printError("amplitude has to be in [-1,1]");
			return false;
		} else if (amp == 0.0) {
			Engine.printWarning("an amplitude of 0 means this sound is not audible now");
		}
		return true;
	}

	protected static boolean checkPan(float pan) {
		if (pan < -1 || pan > 1) {
			Engine.printError("pan has to be in [-1,1]");
			return false;
		}
		return true;
	}

	protected static boolean checkRange(double value, String name) {
		if (value < 0 || value > 1) {
			Engine.printError(name + " parameter has to be between 0 and 1 (inclusive)");
			return false;
		}
		return true;
	}

	protected static void println(String message) {
		System.out.println("[Sound Processing]: " + message);
	}

	protected static void println() {
		Engine.println("");
	}

	protected static void printMessage(String message) {
		Engine.println("Sound library: " + message);
	}

	public static void printWarning(String message) {
		Engine.println("Sound library warning: " + message);
	}

	public static void printError(String message) {
		Engine.println("Sound library error: " + message);
	}

}
