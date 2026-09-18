package repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 此 box 含 Extended AC-3 解码器参数。更多信息见 AC-3 规范 "<code>ETSI TS 102 366 V1.2.1 (2008-08)</code>"，网址
 * <a href="http://www.etsi.org/deliver/etsi_ts/102300_102399/102366/01.02.01_60/ts_102366v010201p.pdf>
 * http://www.etsi.org/deliver/etsi_ts/102300_102399/102366/01.02.01_60/ts_102366v010201p.pdf</a>。
 * 
 * @author in-somnia
 */
public class EAC3SpecificBox extends CodecSpecificBox {

	private int dataRate, independentSubstreamCount;
	private int[] fscods, bsids, bsmods, acmods, dependentSubstreamCount, dependentSubstreamLocation;
	private boolean[] lfeons;

	public EAC3SpecificBox() {
		super("EAC-3 Specific Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		long l = in.readBytes(2);
		//13 位 dataRate
		dataRate = (int) ((l>>3)&0x1FFF);
		//3 位独立子流数量
		independentSubstreamCount = (int) (l&0x7);

		for(int i = 0; i<independentSubstreamCount; i++) {
			l = in.readBytes(3);
			//2 位 fscod
			fscods[i] = (int) ((l>>22)&0x3);
			//5 位 bsid
			bsids[i] = (int) ((l>>17)&0x1F);
			//5 位 bsmod
			bsmods[i] = (int) ((l>>12)&0x1F);
			//3 位 acmod
			acmods[i] = (int) ((l>>9)&0x7);
			//3 位保留
			//1 位 lfeon
			lfeons[i] = ((l>>5)&0x1)==1;
			//4 位依赖子流数量
			dependentSubstreamCount[i] = (int) ((l>>1)&0xF);
			if(dependentSubstreamCount[i]>0) {
				//9 位依赖子流位置
				l = (l<<8)|in.read();
				dependentSubstreamLocation[i] = (int) (l&0x1FF);
			}
			//else：1 位保留
		}
	}

	/**
	 * 此值指示 Enhanced AC-3 比特流的数据率（kbit/s）。
	 * 若 Enhanced AC-3 流为可变比特率，则此值指示流的最大数据率。
	 * 
	 * @return 数据率
	 */
	public int getDataRate() {
		return dataRate;
	}

	/**
	 * 此字段指示 Enhanced AC-3 比特流中存在的独立子流数量。
	 * 
	 * @return 独立子流数量
	 */
	public int getIndependentSubstreamCount() {
		return independentSubstreamCount;
	}

	/**
	 * 此字段含义相同，并设为与独立子流中 fscod 字段相同的值。
	 * 
	 * @return 所有独立子流的 'fscod' 值
	 */
	public int[] getFscods() {
		return fscods;
	}

	/**
	 * 此字段含义相同，并设为与独立子流中 bsid 字段相同的值。
	 * 
	 * @return 所有独立子流的 'bsid' 值
	 */
	public int[] getBsids() {
		return bsids;
	}

	/**
	 * 此字段含义相同，并设为与独立子流中 bsmod 字段相同的值。
	 * 若独立子流中不存在 bsmod 字段，此字段应设为 0。
	 * 
	 * @return 所有独立子流的 'bsmod' 值
	 */
	public int[] getBsmods() {
		return bsmods;
	}

	/**
	 * 此字段含义相同，并设为与独立子流中 acmod 字段相同的值。
	 * 
	 * @return 所有独立子流的 'acmod' 值
	 */
	public int[] getAcmods() {
		return acmods;
	}

	/**
	 * 此字段含义相同，并设为与独立子流中 lfeon 字段相同的值。
	 * 
	 * @return 所有独立子流的 'lfeon' 值
	 */
	public boolean[] getLfeons() {
		return lfeons;
	}

	/**
	 * 此字段指示与某个独立子流关联的依赖子流数量。
	 * 
	 * @return 所有独立子流的依赖子流数量
	 */
	public int[] getDependentSubstreamCount() {
		return dependentSubstreamCount;
	}

	/**
	 * 若某个独立子流关联有一个或多个依赖子流，此位字段用于标识比特流中存在的、
	 * 超出 'acmod' 字段所标识之外的声道位置。返回整数的低 9 位为标志，
	 * 指示某个声道位置是否存在。标志按下表使用，其中 index 0 为所用 9 位中最高位：
	 * <table>
	 * <tr><th>位</th><th>位置</th></tr>
	 * <tr><td>0</td><td>Lc/Rc 对</td></tr>
	 * <tr><td>1</td><td>Lrs/Rrs 对</td></tr>
	 * <tr><td>2</td><td>Cs</td></tr>
	 * <tr><td>3</td><td>Ts</td></tr>
	 * <tr><td>4</td><td>Lsd/Rsd 对</td></tr>
	 * <tr><td>5</td><td>Lw/Rw 对</td></tr>
	 * <tr><td>6</td><td>Lvh/Rvh 对</td></tr>
	 * <tr><td>7</td><td>Cvh</td></tr>
	 * <tr><td>8</td><td>LFE2</td></tr>
	 * </table>
	 * 
	 * @return 所有独立子流的依赖子流位置
	 */
	public int[] getDependentSubstreamLocation() {
		return dependentSubstreamLocation;
	}
}
