package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.TwoInDualOut;
import repackage.com.jsyn.unitgen.UnitFilter;

import java.util.HashSet;
import java.util.Set;

/**
 * 对于高级用户：所有效果类型的通用超类
 * @ webref效果
 */
// 辅助类，用于在两个通道上应用相同的效果（具有相同的参数）。
// <p>与 {@link Effect#stop()} 不同，它容忍源已经丢弃效果（因此它
// 输入源--我们是否认为它们都会产生相同的效果
// 一个基本设计问题是：若同一效果应用于多个不同
// 单元，该怎么办？还是每次调用 process() 都实例化新单元？
// 单元，它们的输入信号会自动加在一起。调用
// stop() on效果也会断开所有输入源的连接，然后再删除
// 目前所有输入源都连接到相同的两个左/右效果
public abstract class Effect<EffectType extends UnitFilter> {

	// 从合成器中移除效果。
	protected Set<SoundObject> inputs = new HashSet<>();

	protected EffectType left;
	protected EffectType right;
	protected UnitOutputPort output;

	// 被子类调用
	protected Effect() {
		Engine.getEngine();
		this.left = this.newInstance();
		this.right = this.newInstance();
		TwoInDualOut merge = new TwoInDualOut();
		merge.inputA.connect(this.left.output);
		merge.inputB.connect(this.right.output);
		this.output = merge.output;
	}

	protected abstract EffectType newInstance();

	/**
	 * 保存所有输入源的引用
	 * 获取此效果当前是否处于活动状态的信息。
	 */
	public boolean isProcessing() {
		return ! this.inputs.isEmpty();
	}

	/**
	 * 开始效果。
	 * @param input 输入声源
	 * @return 若此效果当前正在处理至少一个声源则为 true
	 */
	public void process(SoundObject input) {
		if (this.inputs.add(input)) {
			// 将效果附加到电路，直到使用effect.stop()删除
			input.setEffect(this);
		} else {
			Engine.printWarning("the effect is already processing this sound source");
		}
	}

	/**
	 * 停止效果。
	 * @return 若此效果当前正在处理至少一个声源则为 true
	 */
	public void stop() {
		if (this.inputs.isEmpty()) {
//			Engine.printWarning("此效果当前未处理任何信号。");
		} else {
			for (SoundObject o : this.inputs) {
				o.removeEffect(this);
			}
			this.inputs.clear();
			Engine.getEngine().remove(this.left);
			Engine.getEngine().remove(this.right);
		}
	}
}
