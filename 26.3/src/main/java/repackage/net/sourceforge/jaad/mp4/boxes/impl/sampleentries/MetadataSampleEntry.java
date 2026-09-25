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

abstract class MetadataSampleEntry extends SampleEntry {

	private String contentEncoding;

	MetadataSampleEntry(String name) {
		super(name);
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		contentEncoding = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
	}

	/**
	 * 提供 MIME 类型的字符串，标识定时元数据的内容编码。
	 * 若不存在（提供空字符串），则定时元数据未编码。
	 * 此字段示例为 'application/zip'。
	 * @return 编码的 MIME 类型
	 */
	public String getContentEncoding() {
		return contentEncoding;
	}
}
