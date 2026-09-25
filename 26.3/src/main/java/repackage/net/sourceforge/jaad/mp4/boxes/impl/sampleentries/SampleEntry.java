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
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

public abstract class SampleEntry extends BoxImpl {

	private long dataReferenceIndex;

	protected SampleEntry(String name) {
		super(name);
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		in.skipBytes(6); //保留
		dataReferenceIndex = in.readBytes(2);
	}

	/**
	 * data reference index 为整数，含数据引用的索引，
	 * 用于检索使用此采样描述的采样所关联的数据。
	 * 数据引用存于 Data Reference Box。索引范围从 1 到数据引用数。
	 */
	public long getDataReferenceIndex() {
		return dataReferenceIndex;
	}
}
