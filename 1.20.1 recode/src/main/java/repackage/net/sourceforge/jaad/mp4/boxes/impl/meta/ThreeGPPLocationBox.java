package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 此 box 包含关于某位置的元信息。
 * 
 * 若位置信息指向随时间变化的位置，名称应表达高层位置，
 * 例如芬兰的多处地点用“Finland”，芬兰和瑞典的多处地点用“Finland-Sweden”。
 * 随时间变化位置的更多细节可作为附加说明给出。
 * 
 * 经度、纬度和海拔值提供媒体内容的粗略 GPS 信息。
 * 
 * 经度（纬度）值小于 –180（-90）或大于 180（90），
 * 表示 GPS 坐标（经度、纬度、海拔）未指定，
 * 即经度、纬度或海拔所给值均无效。
 * 
 * @author in-somnia
 */
public class ThreeGPPLocationBox extends ThreeGPPMetadataBox {

	private int role;
	private double longitude, latitude, altitude;
	private String placeName, astronomicalBody, additionalNotes;

	public ThreeGPPLocationBox() {
		super("3GPP Location Information Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		decodeCommon(in);

		placeName = in.readUTFString((int) getLeft(in));
		role = in.read();
		longitude = in.readFixedPoint(16, 16);
		latitude = in.readFixedPoint(16, 16);
		altitude = in.readFixedPoint(16, 16);

		astronomicalBody = in.readUTFString((int) getLeft(in));
		additionalNotes = in.readUTFString((int) getLeft(in));
	}

	/**
	 * 表示地名的字符串。
	 * 
	 * @return 地名
	 */
	public String getPlaceName() {
		return placeName;
	}

	/**
	 * 地点的角色：<br />
	 * <ol start="0">
	 * <li>“拍摄地点”</li>
	 * <li>“真实地点”</li>
	 * <li>“虚构地点”</li>
	 * </ol><br />
	 * 其他值保留。
	 * 
	 * @return 地点的角色
	 */
	public int getRole() {
		return role;
	}

	/**
	 * 表示经度（度）的浮点数。负值表示西经。
	 * 
	 * @return 经度
	 */
	public double getLongitude() {
		return longitude;
	}

	/**
	 * 表示纬度（度）的浮点数。负值表示南纬。
	 * 
	 * @return 纬度
	 */
	public double getLatitude() {
		return latitude;
	}

	/**
	 * 表示海拔（米）的浮点数。以零表示的参考海拔设为海平面。
	 * 
	 * @return 海拔
	 */
	public double getAltitude() {
		return altitude;
	}

	/**
	 * 表示该位置所在天体的字符串，例如“earth”。
	 * 
	 * @return 天体
	 */
	public String getAstronomicalBody() {
		return astronomicalBody;
	}

	/**
	 * 含任何附加位置相关信息的字符串。
	 * 
	 * @return 附加说明
	 */
	public String getAdditionalNotes() {
		return additionalNotes;
	}
}
