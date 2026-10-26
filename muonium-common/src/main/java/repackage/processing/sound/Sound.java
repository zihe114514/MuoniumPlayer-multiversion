package repackage.processing.sound;

import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.engine.SynthesisEngine;

import java.util.stream.IntStream;



/**
  * 此类可用于配置 Processing Sound 库。
 *
 * 此类可用于配置 Processing Sound 库。
 * 库的音频合成和回放，如输出设备、样本
 * 速率或全局输出量。
 *
 * Sound 类允许配置声音的全局属性，
 * 可用输入输出设备的信息可通过调用
 * 
 * @ webref配置：声音
 * @ webBrief此类可用于配置Processing Sound库。
 * @see MultiChannel
 */
public class Sound {

	public Sound() {
		Engine.getEngine();
	}

	/**
	 * @param sampleRate
	 *            the sample rate to be used by the synthesis engine (default 44100)
	 * 合成引擎使用的采样率（默认 44100）
	 *            应播放声音的声卡的设备ID
	 * @param inputDevice
	 *            声音应来自的声卡的设备ID
	 *            捕获
	 * @param volume
	 *            库的总输出体积（默认1.0 ）
	 */
	public Sound(int sampleRate, int outputDevice, int inputDevice, float volume) {
		this();
		sampleRate(sampleRate);
		inputDevice(inputDevice);
		outputDevice(outputDevice);
		volume(volume);
	}

	public static AudioDeviceManager getAudioDeviceManager() {
		return Engine.getAudioDeviceManager();
	}

	public static String[] deviceNames() {
		String[] names = new String[Sound.getAudioDeviceManager().getDeviceCount()];
		for (int i = 0; i < names.length; i++) {
			names[i] = Engine.getEngine().getDeviceName(i);
		}
		return names;
	}

	/**
	 * 打印并返回有关可用音频设备及其数量的信息
	 * input/output channels.
	 * 在正常情况下，您不会想调用<b> Sound.list() </b>
	 * 输入/输出声道。 
	 * 在新系统上可用，以及如何选择它们。但是，如果订单
	 * 实际 sketch 代码，而仅用于测试以弄清哪些声卡 
	 * 你系统上的设备列表容易在重启间波动，你 
	 * 在您自己的代码中按名称选择。
	 * 
	 * @param filter 仅列出其设备名称包含此内容的音频设备
	 * 也可使用该函数返回的设备名数组来自动化设备
	 * 字符串
	 *         @return 给出此计算机上所有可用音频设备名称的数组
	 * @ webref配置：声音
	 * @ webBrief显示有关可用音频设备的信息
	 */
	public static String[] list(String filter) {
		String[] deviceNames = Sound.deviceNames();
		System.out.println();
		Engine.printMessage(Sound.getAudioDeviceManager().getName() + " audio device search for '" + filter + "'\n");
		Sound.printDeviceTable(IntStream.range(0, deviceNames.length).filter(i -> deviceNames[i].contains(filter)).toArray());
		return deviceNames;
	}

	public static String[] list() {
		return Sound.list(false);
	}

	public static String[] list(boolean printAll) {
		String[] deviceNames = Sound.deviceNames();
		AudioDeviceManager audioManager = Engine.getAudioDeviceManager();
		System.out.println();
		Engine.printMessage(audioManager.getName() + " device listing\n");
		if (printAll) {
			Sound.printDeviceTable(IntStream.range(0, deviceNames.length).toArray());
		} else {
			Sound.printDeviceTable(IntStream.range(0, deviceNames.length).filter(i -> audioManager.getMaxInputChannels(i) > 0).toArray(),
					IntStream.range(0, deviceNames.length).filter(i -> audioManager.getMaxOutputChannels(i) > 0).toArray());
		}
		return Sound.deviceNames();
	}

	/**
	 * 用于格式化的帮助程序方法
	 */
	private static int longestDeviceNameLength(int[] deviceIds) {
		int longestLength = "Output device name".length();
		for (int i : deviceIds) {
			longestLength = Math.max(longestLength, Engine.getEngine().getDeviceName(i).length());
		}
		return longestLength;
	}

	private static String deviceNameTableDivider(int longestDeviceNameLength) {
		return String.format("%" + (longestDeviceNameLength+2) + "s", "").replace(' ', '-');
	}

	private static void printDeviceTable(int[] deviceIds) {
		AudioDeviceManager audioManager = Sound.getAudioDeviceManager();
		int longestLength = Sound.longestDeviceNameLength(deviceIds);
		String lineFormat = " %-3s %3s | %-" + longestLength + "s | %6s | %4s%n";

		System.out.format(lineFormat, "", "id", "Device name", "inputs", "outputs");
		System.out.println("     ----+" + Sound.deviceNameTableDivider(longestLength) + "+----");
		for (int i : deviceIds) {
			System.out.format(lineFormat,
					Engine.getEngine().inputDevice == i ? (Engine.getEngine().outputDevice == i ? "I,O" : "I") :
					(Engine.getEngine().outputDevice == i ? "O" : ""), i, Engine.getEngine().getDeviceName(i),
					audioManager.getMaxInputChannels(i),
					audioManager.getMaxOutputChannels(i));
		}
		System.out.println();
	}

	private static void printDeviceTable(int[] inputDeviceIds, int[] outputDeviceIds) {
		AudioDeviceManager audioManager = Sound.getAudioDeviceManager();
		int longestLength = Math.max(Sound.longestDeviceNameLength(inputDeviceIds),
				Sound.longestDeviceNameLength(outputDeviceIds));
		String lineFormat = " %1s %3s | %-" + longestLength + "s | %4s%n";

		System.out.format(lineFormat, " ", "id", "Input device name", "inputs");
		System.out.println("   ----+" + Sound.deviceNameTableDivider(longestLength) + "+--------");
		for (int i : inputDeviceIds) {
			System.out.format(lineFormat, Engine.getEngine().inputDevice == i ? "I" : " ", i, Engine.getEngine().getDeviceName(i), audioManager.getMaxInputChannels(i));
		}
		System.out.println();
		System.out.format(lineFormat, " ", "id", "Output device name", "outputs");
		System.out.println("   ----+" + Sound.deviceNameTableDivider(longestLength) + "+--------");
		for (int i : outputDeviceIds) {
			System.out.format(lineFormat, Engine.getEngine().outputDevice == i ? "O" : " ",  i, Engine.getEngine().getDeviceName(i), audioManager.getMaxOutputChannels(i));
		}
		System.out.println();
	}

	public static int sampleRate() {
		return Engine.getEngine().getSampleRate();
	}

	/**
	 * 获取或设置合成引擎的内部采样率。
	 * 
	 * @param sampleRate
	 *            the sample rate to be used by the synthesis engine (default 44100)
	  * @return 合成引擎所使用的内部采样率
	 * @ webref配置：声音
	 * @ webBrief获取或设置合成引擎的内部采样率。
	 */
	public static int sampleRate(int sampleRate) {
		Engine.getEngine().setSampleRate(sampleRate);
		return Sound.sampleRate();
	}

	/**
	 * 选择应用于获取音频输入的设备（声卡）
	 * @return 合成引擎使用的内部采样率 
	 * 不一定与输入通道的数量相同。如果您的声音
	 * 使用 AudioIn。注意此设置会影响声卡选择，若
	 * 声卡有多个输入声道，你可在
	 * 
	 * AudioIn 类的构造函数中指定使用哪个声道。
	 *            从Sound.list()获得的设备ID
	 * @see Sound#list()
	 * @ webref配置：声音
	 * @ webBrief选择用于获取音频输入的音频设备。
	 */
	public static int inputDevice(int deviceId) {
		return Engine.getEngine().selectInputDevice(deviceId);
	}

	public static int inputDevice(String deviceName) {
		return Engine.getEngine().selectInputDevice(Engine.getEngine().getDeviceIdByName(deviceName));
	}

	/**
	 * 选择声音库音频输出应使用的设备（声卡）
	 * 发送到。输出设备应支持立体声输出（2 通道）。
	 * 
	 * AudioIn 类的构造函数中指定使用哪个声道。
	 *            从Sound.list()获得的设备ID
	 * @see Sound#list()
	 * @ webref配置：声音
	 * @ webBrief选择将音频输出发送到哪个音频设备。
	 */
	public static int outputDevice(int deviceId) {
		return Engine.getEngine().selectOutputDevice(deviceId);
	}

	public static int outputDevice(String deviceName) {
		return Engine.getEngine().selectOutputDevice(Engine.getEngine().getDeviceIdByName(deviceName));
	}

	public static int defaultOutputDevice() {
		return Sound.outputDevice(Engine.getAudioDeviceManager().getDefaultOutputDeviceID());
	}

	public static int defaultInputDevice() {
		return Sound.inputDevice(Engine.getAudioDeviceManager().getDefaultInputDeviceID());
	}

	/**
	 * 发送到。输出设备应支持立体声输出（2 声道）。
	 *
	 * @param volume
	 *            所需的输出体积，通常在0.0到1.0之间（默认值
	 *            设置 Processing 声音库的总输出音量。
	 * @ webref配置：声音
	 */
	public static void volume(float volume) {
		Engine.getEngine().setVolume(volume);
	}

	/**
	 * 为 1.0） 
	 * 个人风险
	 * @see SynthesisEngine
	 */
	public static SynthesisEngine getSynthesisEngine() {
		return (SynthesisEngine) Engine.getEngine().synth;
	}

	/**
	 * 直接访问底层 JSyn SynthesisEngine 对象。自行承担 
	 * 到控制台。
	 * 打印声音库当前内存和 CPU 使用情况 
	 * CPU使用率
	 * @ webref配置：声音
	 */
	public static void status() {
		SynthesisEngine e = Sound.getSynthesisEngine();
		Engine.println();
		Engine.printMessage("synthesis has been running for " + String.format("%.2f", e.getCurrentTime()) + " seconds, generated " + e.getFrameCount() + " frames (framerate " + e.getFrameRate() + ")");
		Engine.println("  audio devices used by " + e.getAudioDeviceManager().getName() + ":");
		if (Engine.getEngine().inputDevice != -1) {
			Engine.println("    input from '" + e.getAudioDeviceManager().getDeviceName(Engine.getEngine().inputDevice) + "': " + e.getAudioDeviceManager().getMaxInputChannels(Engine.getEngine().inputDevice) + " channels, latency " + Math.round(1000*e.getInputLatency()) + "ms");
		}
		Engine.println("    output on '" + e.getAudioDeviceManager().getDeviceName(Engine.getEngine().outputDevice) + "': " + e.getAudioDeviceManager().getMaxOutputChannels(Engine.getEngine().outputDevice) + " channels, latency " + Math.round(1000*e.getOutputLatency()) + "ms");
		Engine.println("\n  nodes in synthesizer network: " + Engine.getEngine().addedUnits.size());
		Engine.println("  CPU usage: " + Math.round(100 * e.getUsage()) + "%");
		Engine.println();
	}

	public static void verbose(boolean verbose) {
		Engine.verbose = verbose;
	}

	public static void printConnections() {
		Sound.getSynthesisEngine().printConnections();
	}
}
