package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 可出现于任意视觉采样条目中。当视觉素材的
 * 宽高（由任意视觉采样条目中的宽高值声明）与轨道宽高值
 *（如 track header box 所指示）不匹配时，
 * 此 box 指示所应用的缩放方法。
 * 此 box 的实现为可选；若存在且解码器能解释，则所有采样应按
 * 此 box 指定的缩放行为显示。否则，所有采样被缩放到
 * Track Header Box 中宽高字段所指示的大小。
 * 若图像尺寸大于放映区域，且 Sample Scale Box 中应用了
 * “hidden”缩放，则无法显示整幅图像。这种情况下，
 * 提供信息以确定要显示的区域很有用。中心值将指示每个
 * 视觉采样中高优先级区域的中心。解码器可按这些值显示
 * 高优先级区域。中心值意味着序列中所有图像有一致的裁剪。
 * 当期望视觉中心低于图像中心或偏右时，偏移值为正；
 * 在上方或左侧时为负。
 * 
 * @author in-somnia
 */
public class SampleScaleBox extends FullBox {

	private boolean constrained;
	private int scaleMethod, displayCenterX, displayCenterY;

	public SampleScaleBox() {
		super("Sample Scale Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		//7 位保留，1 位标志
		constrained = (in.read()&1)==1;

		scaleMethod = in.read();
		displayCenterX = (int) in.readBytes(2);
		displayCenterY = (int) in.readBytes(2);
	}

	/**
	 * 若设置此标志，由此采样条目描述的所有采样应按
	 * 'scale_method' 字段指定的方法缩放。
	 * 否则建议所有采样按 'scale_method' 字段指定的方法缩放，
	 * 但也可以按与实现相关的方式显示，包括不缩放图像
	 *（即既不缩放到 track header box 指定的宽高，
	 * 也不按此处指示的方法）。
	 *
	 * @return 如果采样应按缩放方法缩放则返回 true
	 */
	public boolean isConstrained() {
		return constrained;
	}

	/**
	 * 应优先显示的区域中心相对于图像中心的水平偏移（像素）。
	 * 默认值为零。正值表示显示中心在图像中心右侧。
	 *
	 * @return 水平偏移
	 */
	public int getDisplayCenterX() {
		return displayCenterX;
	}

	/**
	 * 应优先显示的区域中心相对于图像中心的垂直偏移（像素）。
	 * 默认值为零。正值表示显示中心在图像中心下方。
	 * @return 垂直偏移
	 */
	public int getDisplayCenterY() {
		return displayCenterY;
	}

	/**
	 * 缩放方法为整数，定义所用的缩放模式。
	 * 在 256 个可能值中，0 到 127 保留给 ISO 使用，
	 * 128 到 255 为用户定义，本国际标准未规定；
	 * 可由应用决定如何使用。在保留值中，当前定义了以下模式：
	 * 1：按 'fill' 模式缩放。
	 * 2：按 'hidden' 模式缩放。
	 * 3：按 'meet' 模式缩放。
	 * 4：在 x 坐标上按 'slice' 模式缩放。
	 * 5：在 y 坐标上按 'slice' 模式缩放。
	 *
	 * @return 缩放方法
	 */
	public int getScaleMethod() {
		return scaleMethod;
	}
}
