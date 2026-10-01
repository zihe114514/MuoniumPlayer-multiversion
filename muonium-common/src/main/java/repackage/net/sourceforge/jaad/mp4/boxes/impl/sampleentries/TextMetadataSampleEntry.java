/*
 *  Copyright (C) 2011 in-somnia
 * 
 *  This file is part of JAAD.
 * 
 *  JAAD is free software; you can redistribute it and/or modify it 
 *  under the terms of the GNU Lesser General Public License as 
 *  published by the Free Software Foundation; either version 3 of the 
 *  License, or (at your option) any later version.
 *
 *  JAAD is distributed in the hope that it will be useful, but WITHOUT 
 
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General 
 *  Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library.
 *  If not, see <http://www.gnu.org/licenses/>.
 */
package repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;

public class TextMetadataSampleEntry extends MetadataSampleEntry {

	private String mimeType;

	public TextMetadataSampleEntry() {
		super("Text Metadata Sample Entry");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		mimeType = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
	}

	/**
	 * 提供 MIME 类型，标识定时元数据的内容格式。
	 * 此字段示例为 'text/html' 和 'text/plain'。
	 * 
	 * @return 内容的 MIME 类型
	 */
	public String getMimeType() {
		return mimeType;
	}
}
