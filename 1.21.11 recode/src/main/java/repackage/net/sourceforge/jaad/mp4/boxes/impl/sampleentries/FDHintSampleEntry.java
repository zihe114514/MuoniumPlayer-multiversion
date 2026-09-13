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

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

public class FDHintSampleEntry extends SampleEntry {

	private int hintTrackVersion, highestCompatibleVersion, partitionEntryID;
	private double fecOverhead;

	public FDHintSampleEntry() {
		super("FD Hint Sample Entry");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		hintTrackVersion = (int) in.readBytes(2);
		highestCompatibleVersion = (int) in.readBytes(2);
		partitionEntryID = (int) in.readBytes(2);
		fecOverhead = in.readFixedPoint(8, 8);

		readChildren(in);
	}

	/**
	 * partition entry ID 指示 FD item information box 中的分区条目。
	 * 零值表示此采样条目未关联分区条目，例如用于 FDT。
	 * 若对应 FD 提示轨道只含开销数据，此值应指示其所讨论开销数据所在的分区条目。
	 *
	 * @return partition entry ID
	 */
	public int getPartitionEntryID() {
		return partitionEntryID;
	}

	/**
	 * FEC overhead 为浮点数，指示提示采样所用保护开销的百分比。
	 * 提供此值的目的是给出特征，帮助服务器选择会话组（及对应 FD 提示轨道）。
	 * 若对应 FD 提示轨道只含开销数据，此值应指示会话组中直到所讨论 FD 提示轨道、
	 * 使用所有 FD 提示轨道所达到的保护开销。
	 *
	 * @return FEC overhead
	 */
	public double getFECOverhead() {
		return fecOverhead;
	}
}
