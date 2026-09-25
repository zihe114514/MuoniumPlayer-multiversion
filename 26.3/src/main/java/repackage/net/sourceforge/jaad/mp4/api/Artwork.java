package repackage.net.sourceforge.jaad.mp4.api;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.meta.ITunesMetadataBox.DataType;

public class Artwork {

	//TODO: 需要这个枚举吗？它只是复制了 DataType
	public enum Type {

		GIF, JPEG, PNG, BMP;

		static Type forDataType(DataType dataType) {
			Type type;
			switch(dataType) {
				case GIF:
					type = GIF;
					break;
				case JPEG:
					type = JPEG;
					break;
				case PNG:
					type = PNG;
					break;
				case BMP:
					type = BMP;
					break;
				default:
					type = null;
			}
			return type;
		}
	}
	private Type type;
	private byte[] data;
	private Image image;

	Artwork(Type type, byte[] data) {
		this.type = type;
		this.data = data;
	}

	/**
	 * 返回此封面（artwork）中的数据类型。
	 *
	 * @see Type
	 * @return 数据的类型
	 */
	public Type getType() {
		return type;
	}

	/**
	 * 返回此封面的编码数据。
	 *
	 * @return 编码后的数据
	 */
	public byte[] getData() {
		return data;
	}

	/**
	 * 返回可绘制的解码后图像。
	 *
	 * @return 解码后的图像
	 * @throws IOException 如果解码失败
	 */
	public Image getImage() throws IOException {
		try {
			if(image==null) image = ImageIO.read(new ByteArrayInputStream(data));
			return image;
		}
		catch(IOException e) {
			Logger.getLogger("MP4 API").log(Level.SEVERE, "Artwork.getImage failed: {0}", e.toString());
			throw e;
		}
	}
}
