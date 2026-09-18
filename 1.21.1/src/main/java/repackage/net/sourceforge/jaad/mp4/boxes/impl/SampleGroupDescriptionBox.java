package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxFactory;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.samplegroupentries.*;
import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;

/**
 * 此描述表给出关于采样组特征的信息。描述性信息是定义或刻画
 * 采样组所需的任何其他信息。
 *
 * 若轨道中的采样有多个采样分组，则此 box 可有多个实例。
 * SampleGroupDescriptionBox 的每个实例都有一个类型代码，
 * 区分不同的采样分组。在一个轨道内，特定分组类型的此 box
 * 至多有一个实例。关联的 SampleToGroupBox 应对分组类型指示
 * 相同的值。
 *
 * 该信息存于采样组描述 box 中 entry-count 之后。定义了一个抽象条目类型，
 * 各采样分组应定义派生类型来表示每个采样组的描述。
 * 对于视频轨道，使用抽象的 VisualSampleGroupEntry；
 * 音频和提示轨道使用类似类型。
 *
 * @author in-somnia
 */
public class SampleGroupDescriptionBox extends FullBox {

	private long groupingType, defaultLength, descriptionLength;
	private SampleGroupDescriptionEntry[] entries;

	public SampleGroupDescriptionBox() {
		super("Sample Group Description Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		groupingType = in.readBytes(4);
		defaultLength = (version==1)?in.readBytes(4):0;

		final int entryCount = (int) in.readBytes(4);

		//TODO!
		/*final HandlerBox hdlr = (HandlerBox) parent.getParent().getParent().getChild(BoxTypes.HANDLER_BOX);
		final int handlerType = (int) hdlr.getHandlerType();
		
		final Class<? extends BoxImpl> boxClass;
		switch(handlerType) {
		case HandlerBox.TYPE_VIDEO:
		boxClass = VisualSampleGroupEntry.class;
		break;
		case HandlerBox.TYPE_SOUND:
		boxClass = AudioSampleGroupEntry.class;
		break;
		case HandlerBox.TYPE_HINT:
		boxClass = HintSampleGroupEntry.class;
		break;
		default:
		boxClass = null;
		}
		
		for(int i = 1; i<entryCount; i++) {
		if(version==1&&defaultLength==0) {
		descriptionLength = in.readBytes(4);
		left -= 4;
		}
		if(boxClass!=null) {
		entries[i] = (SampleGroupDescriptionEntry) BoxFactory.parseBox(in, boxClass);
		if(entries[i]!=null) left -= entries[i].getSize();
		}
		}*/
	}

	/**
	 * 分组类型为整数，标识与此采样组描述关联的 SampleToGroup box。
	 */
	public long getGroupingType() {
		return groupingType;
	}

	/**
	 * 默认长度指示每个组条目的长度（若长度恒定），
	 * 若可变则为零（0）。
	 */
	public long getDefaultLength() {
		return defaultLength;
	}

	/**
	 * 描述长度指示单个组条目的长度，
	 * 用于条目长度彼此不同、因而默认长度为 0 的情形。
	 */
	public long getDescriptionLength() {
		return descriptionLength;
	}
}
