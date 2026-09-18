package repackage.net.sourceforge.jaad.aac.tools;

import repackage.net.sourceforge.jaad.aac.AACException;

/**
 * MSMask 表明是否将 MS 应用于特定的 ICStream。
 * @author in-somnia
 */
public enum MSMask {

	TYPE_ALL_0(0),
	TYPE_USED(1),
	TYPE_ALL_1(2),
	TYPE_RESERVED(3);

	public static MSMask forInt(int i) throws AACException {
		MSMask m;
		switch(i) {
			case 0:
				m = TYPE_ALL_0;
				break;
			case 1:
				m = TYPE_USED;
				break;
			case 2:
				m = TYPE_ALL_1;
				break;
			case 3:
				m = TYPE_RESERVED;
				break;
			default:
				throw new AACException("unknown MS mask type");
		}
		return m;
	}
	private int num;

	private MSMask(int num) {
		this.num = num;
	}
}
