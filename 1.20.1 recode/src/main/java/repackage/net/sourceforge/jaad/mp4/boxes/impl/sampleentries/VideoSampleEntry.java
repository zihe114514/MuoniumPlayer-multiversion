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

public class VideoSampleEntry extends SampleEntry {

	private int width, height;
	private double horizontalResolution, verticalResolution;
	private int frameCount, depth;
	private String compressorName;

	public VideoSampleEntry(String name) {
		super(name);
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		in.skipBytes(2); //预定义：0
		in.skipBytes(2); //保留
		//3x32 pre_defined
		in.skipBytes(4); //预定义：0
		in.skipBytes(4); //预定义：0
		in.skipBytes(4); //预定义：0

		width = (int) in.readBytes(2);
		height = (int) in.readBytes(2);
		horizontalResolution = in.readFixedPoint(16, 16);
		verticalResolution = in.readFixedPoint(16, 16);
		in.skipBytes(4); //保留
		frameCount = (int) in.readBytes(2);

		final int len = in.read();
		compressorName = in.readString(len);
		in.skipBytes(31-len);

		depth = (int) in.readBytes(2);
		in.skipBytes(2); //pre-defined: -1

		readChildren(in);
	}

	/**
	 * 宽度为此采样描述所描述流的最大视觉宽度（像素）。
	 */
	public int getWidth() {
		return width;
	}

	/**
	 * 高度为此采样描述所描述流的最大视觉高度（像素）。
	 */
	public int getHeight() {
		return height;
	}

	/**
	 * 图像水平分辨率（每英寸像素数），以浮点数表示。
	 */
	public double getHorizontalResolution() {
		return horizontalResolution;
	}

	/**
	 * 图像垂直分辨率（每英寸像素数），以浮点数表示。
	 */
	public double getVerticalResolution() {
		return verticalResolution;
	}

	/**
	 * 帧计数指示每个采样中存储了多少压缩视频帧。
	 */
	public int getFrameCount() {
		return frameCount;
	}

	/**
	 * 压缩器名称，仅供参考。
	 */
	public String getCompressorName() {
		return compressorName;
	}

	/**
	 * depth 取以下值之一
	 * DEFAULT_DEPTH（0x18）——图像为彩色、无 alpha
	 */
	public int getDepth() {
		return depth;
	}
}
