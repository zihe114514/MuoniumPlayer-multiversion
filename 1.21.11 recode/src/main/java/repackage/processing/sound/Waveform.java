package repackage.processing.sound;

import lombok.Getter;
import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.ports.QueueDataEvent;
import repackage.com.jsyn.ports.UnitDataQueueCallback;
import repackage.com.jsyn.ports.UnitDataQueuePort;
import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.FixedRateStereoWriterSpecial;


/**
 * 此 SoundObject 的合成。
 * 使用<b> getAnalyzedData() </b>查询的音频流
 * 方法. <br/>
 * 请注意，默认情况下，所有声音发生器（包括来自
 * <code> AudioIn </code> ）的振幅为1 ，这意味着
 * 他们的波形将是<code> [-0.5, 0.5] </code>范围内的数字。
 *
 * @author icalvin102
 *
 * @ webref分析：波形
 * @ webBrief检查音频信号的潜在声波。
 **/
public class Waveform extends Analyzer {

	public float[] data, dataRight;

	@Getter
	private FixedRateStereoWriterSpecial writer;
	private FloatSample buffer, bufferRight;
	private int lastAnalysisOffset, lastAnalysisOffsetRight;

	/**
	 * 这是一个波形分析器。它返回
	 *            您希望能够一次读取的波形样本数（正整数）。
	 */
	public Waveform(float windowTime) {
		super();
		if (windowTime <= 0) {
			// TODO 抛出运行时异常？
			Engine.printError("number of waveform frames needs to be greater than 0");
		} else {
			int nsamples = (int) (Engine.getEngine().getSampleRate() * windowTime);
			this.data = new float[nsamples];
			this.dataRight = new float[nsamples];

			this.writer = new FixedRateStereoWriterSpecial();
			this.buffer = new FloatSample(nsamples);
			this.bufferRight = new FloatSample(nsamples);
			// 将任何连接的输入无限地写入输出缓冲区
			this.writer.dataQueueLeft.queueLoop(this.buffer);
			this.writer.dataQueueRight.queueLoop(this.bufferRight);
//			this.connect(this.writer.dataQueueLeft, this.buffer, this.data);
//			this.connect(this.writer.dataQueueRight, this.bufferRight, this.dataRight);
		}
	}

	private void connect(UnitDataQueuePort port, FloatSample buffer, float[] data) {
		port.queueWithCallback(buffer, new UnitDataQueueCallback() {
			@Override
			public void started(QueueDataEvent event) {

			}

			@Override
			public void looped(QueueDataEvent event) {

			}

			@Override
			public void finished(QueueDataEvent event) {
				System.arraycopy(buffer.getBuffer(), 0, data, 0, data.length);
				port.queueWithCallback(buffer, this);
			}
		});

	}

	private UnitOutputPort connectedInput = null;

	public void removeInput() {
		Engine.getEngine().remove(this.writer);

		if (this.connectedInput != null) {
			this.writer.input.disconnect(0, connectedInput, 0);
			this.writer.input.disconnect(1, connectedInput, 1);
			this.connectedInput = null;
		} else {
			this.writer.input.disconnectAll();
		}

		this.input = null;
	}

	protected void setInput(UnitOutputPort input) {
		// 父类会确保输入单元确实在播放，直接连接它即可
		Engine.getEngine().add(this.writer);
		this.connectedInput = input;
		this.writer.input.connect(0, input, 0);
		this.writer.input.connect(1, input, 1);
		this.writer.start();
	}

	/**
	 * 从输入源获取当前音频缓冲区的内容，并将其写入
	 * 超类确保输入单元确实在播放，直接连接它
	 *
	 * @return 输入源的当前音频缓冲区。数组具有
	 *         到此 Waveform 的 `data` 数组并返回。
	 */
	public float[] analyze() {
		return this.analyze(this.data);
	}

	public float[] analyzeRight() {
		return this.analyzeRight(this.dataRight);
	}

	/**
	 * 从输入源获取当前音频缓冲区的内容。
	 *
	 * FIXME 检查立体声情形
	 *            一个阵列的元素数量与此波形分析仪的数量一样多
	 *            与该波形分析器的采样数同样多的元素
	 * @return 输入源的当前音频缓冲区。数组具有
	 *         到此 Waveform 的 `data` 数组并返回。
	 * @ webref分析：波形
	 * 采样
	 **/
	public float[] analyze(float[] value) {
		if (this.input == null) {
//			Engine.printWarning("此波形没有连接到它的声源，没有要getAnalyzedData的声源");
		}

//		将数据返回到 Excel

		this.lastAnalysisOffset = (int) this.writer.dataQueueLeft.getFrameCount() % this.buffer.getNumFrames();
		// 如果初始化此读取需要太长时间，则前几个样本实际上可能
		// 已经被下一个循环覆盖，所以手指交叉...
		this.buffer.read(lastAnalysisOffset, value, 0, this.buffer.getNumFrames() - lastAnalysisOffset);
		this.buffer.read(0, value, this.buffer.getNumFrames() - lastAnalysisOffset, lastAnalysisOffset);
//		//最初的实现对所有值都做了* 2... ？
		return value;
	}

	public float[] analyzeRight(float[] value) {
		if (this.input == null) {
//			Engine.printWarning("此波形没有连接到它的声源，没有要getAnalyzedData的声源");
		}

//		return dataRight;

		this.lastAnalysisOffsetRight = (int) this.writer.dataQueueRight.getFrameCount() % this.bufferRight.getNumFrames();
		// 如果初始化此读取需要太长时间，则前几个样本实际上可能
		// 已经被下一个循环覆盖，所以手指交叉...
		this.bufferRight.read(lastAnalysisOffsetRight, value, 0, this.bufferRight.getNumFrames() - lastAnalysisOffsetRight);
		this.bufferRight.read(0, value, this.bufferRight.getNumFrames() - lastAnalysisOffsetRight, lastAnalysisOffsetRight);
		// 最初的实现对所有值都做了* 2... ？
		return value;
	}

/*
	@webBrief 从输入源获取当前音频缓冲区的内容。
		返回this.analyzeCircular(this.data);
	}

	public float [] analyzeCircular(float [] value) {
		if(this.input = = null) {
			Engine.printWarning("此波形没有连接到它的声源，没有要getAnalyzedData的声源");
		}

		this.lastAnalysisOffset = (int) this.writer.dataQueue.getFrameCount() % this.buffer.getNumFrames();
		this.buffer.read(值);
		返回值
	}

	public int getLastAnalysisOffset() {
		return this.lastAnalysisOffset;
	}
*/

	// @return amp 一个 0-1 之间的振幅值。
	// 用于在线参考，以构建相应的页面。

	/**
	 * TODO 检查输入是否存在，若否则打印警告
	 *
	 * @param input
	 *            以下仅是从超类复制来的必要方法，
	 *            SoundFile或AudioIn。
	 * @ webref分析：波形
	 * @ webBrief定义分析仪的音频输入。
	 **/
	public void input(SoundObject input) {
		super.input(input);
	}

	public void resize(float windowTime) {
		int nsamples = (int) (Engine.getEngine().getSampleRate() * windowTime);

		this.data = new float[nsamples];
		this.dataRight = new float[nsamples];

//		this.writer = new FixedRateStereoWriterSpecial();

//		this.writer.dataQueueLeft.queueOff(this.buffer, true);
//		this.writer.dataQueueRight.queueOff(this.bufferRight, true);

		this.buffer.allocate(nsamples, 1);
		this.bufferRight.allocate(nsamples, 1);
//		this.buffer = new FloatSample(nsamples);
//		this.bufferRight = new FloatSample(nsamples);
		// 将任何连接的输入无限地写入输出缓冲区
		this.writer.dataQueueLeft = new UnitDataQueuePort("Data");
		this.writer.dataQueueRight = new UnitDataQueuePort("Data");
		this.writer.dataQueueLeft.queueLoop(this.buffer);
		this.writer.dataQueueRight.queueLoop(this.bufferRight);
//		this.connect(this.writer.dataQueueLeft, this.buffer, this.data);
//		this.connect(this.writer.dataQueueRight, this.bufferRight, this.dataRight);
	}
}
