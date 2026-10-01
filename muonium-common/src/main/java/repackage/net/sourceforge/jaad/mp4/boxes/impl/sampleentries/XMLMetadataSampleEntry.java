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

public class XMLMetadataSampleEntry extends MetadataSampleEntry {

	private String namespace, schemaLocation;

	public XMLMetadataSampleEntry() {
		super("XML Metadata Sample Entry");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		namespace = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
		schemaLocation = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
	}

	/**
	 * 给出定时 XML 元数据的 schema 命名空间。
	 * 这用于标识元数据类型，例如 gBSD 或 AQoS（MPEG-21-7），
	 * 以及用 BiM 等 XML 感知编码机制解码。
	 * @return 命名空间
	 */
	public String getNamespace() {
		return namespace;
	}

	/**
	 * 可选地提供 URL，以找到与命名空间对应的 schema。
	 * 这对于用 BiM 等 XML 感知编码机制解码定时元数据是必需的。
	 * @return schema 的 URL
	 */
	public String getSchemaLocation() {
		return schemaLocation;
	}
}
