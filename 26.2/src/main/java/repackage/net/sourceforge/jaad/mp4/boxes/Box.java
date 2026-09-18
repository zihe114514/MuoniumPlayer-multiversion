package repackage.net.sourceforge.jaad.mp4.boxes;

import java.util.List;

public interface Box {

	Box getParent();

	/**
	 * 返回此 box 的大小，包括其头。
	 *
	 * @return 此 box 的大小
	 */
	long getSize();

	/**
	 * 以转换为 long 的 4CC 形式返回此 box 的类型。
	 * 
	 * @return 此 box 的类型
	 */
	long getType();

	/**
	 * 返回此 box 在流/文件中的偏移。这作为随机访问的
	 * 跳转点。
	 *
	 * @return 此 box 的偏移
	 */
	long getOffset();

	/**
	 * 以人类可读字符串返回此 box 的名称
	 *（例如 "Track Header Box"）。
	 *
	 * @return 此 box 的名称
	 */
	String getName();

	/**
	 * 表明此 box 是否有子级。
	 *
	 * @return 如果此 box 至少包含一个子级则返回 true
	 */
	boolean hasChildren();

	/**
	 * 表明该 box 是否有指定类型的子级。
	 *
	 * @param type 要查找的子 box 类型
	 * @return 如果此 box 至少包含一个指定类型的子级则返回 true
	 */
	boolean hasChild(long type);

	/**
	 * 返回此 box 所有直接子级的有序、不可修改列表。
	 * 该列表不包含子级的子级。
	 *
	 * @return 此 box 的子级
	 */
	List<Box> getChildren();

	/**
	 * 返回此 box 所有指定类型的直接子级的有序、不可修改列表。
	 * 该列表不包含子级的
	 * 子级。如果没有指定类型的子级，列表将为
	 * 空。
	 *
	 * @param type 要查找的子 box 类型
	 * @return 此 box 中指定类型的子级
	 */
	List<Box> getChildren(long type);

	/**
	 * 返回指定类型的子 box。如果此 box 没有指定类型的子级，
	 * 则返回 null。要检查是否存在这样的子级，
	 * 可使用 <code>hasChild(type)</code>。
	 * 如果存在多个相同类型的子级，将始终返回第一个。
	 * 可通过 <code>getChildren(type)</code> 获取该类型的所有子级列表。
	 *
	 * @param type 要查找的子 box 类型
	 * @return 指定类型的第一个子 box；若未找到则返回 null
	 */
	Box getChild(long type);
}
