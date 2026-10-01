package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * metabox relation box 指示同一层级（即文件顶层、Movie Box 或
 * Track Box）下两个 meta box 之间的关系。
 * 若两个 meta box 之间没有 metabox relation box，
 * 则其关系未指定。meta box 通过指定其处理器类型来引用。
 *
 * @author in-somnia
 */
public class MetaBoxRelationBox extends FullBox {

	private long firstMetaboxHandlerType, secondMetaboxHandlerType;
	private int metaboxRelation;

	public MetaBoxRelationBox() {
		super("Meta Box Relation Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		firstMetaboxHandlerType = in.readBytes(4);
		secondMetaboxHandlerType = in.readBytes(4);
		metaboxRelation = in.read();
	}

	/**
	 * 第一个相关联的 meta box。
	 */
	public long getFirstMetaboxHandlerType() {
		return firstMetaboxHandlerType;
	}

	/**
	 * 第二个相关联的 meta box。
	 */
	public long getSecondMetaboxHandlerType() {
		return secondMetaboxHandlerType;
	}

	/**
	 * metabox relation 指示两个 meta box 之间的关系。
	 * 定义了以下取值：
	 * <ol start="1">
	 * <li>两 box 之间的关系未知（此 box 不存在时的默认值）</li>
	 * <li>两 box 在语义上无关（例如一个是放映，
	 * 另一个是标注）</li>
	 * <li>两 box 在语义上相关但互补（例如用两种不同元数据系统表达的
	 * 两组不相交的元数据）
	 * </li>
	 * <li>两 box 在语义上相关但重叠（例如两组元数据，
	 * 互不为子集）；彼此都不“优先”于对方</li>
	 * <li>两 box 在语义上相关，但第二个是第一个的真子集或较弱版本；
	 * 第一个优先</li>
	 * <li>两 box 在语义上相关且等价（例如用两种不同元数据系统表达的、
	 * 两组本质相同的元数据）</li>
	 * </ol>
	 */
	public int getMetaboxRelation() {
		return metaboxRelation;
	}
}
