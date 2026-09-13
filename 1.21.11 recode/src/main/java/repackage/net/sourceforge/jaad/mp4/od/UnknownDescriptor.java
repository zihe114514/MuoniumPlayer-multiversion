package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 当流中发现任何未知 Descriptor 时使用此类。该 Descriptor 的所有内容将被跳过。
 *
 * @author in-somnia
 */
public class UnknownDescriptor extends Descriptor {

	@Override
	void decode(MP4InputStream in) throws IOException {
		//内容将被跳过
	}
}
