package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;

/**
 * 任何可以作为调制器传递给振荡器的对象的接口
 * freq()和amp()方法。
 */
public interface Modulator {

	UnitOutputPort getModulator();

}
