package repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 此 box 含 AC-3 解码器参数。更多信息见 AC-3 规范 "<code>ETSI TS 102 366 V1.2.1 (2008-08)</code>"，网址
 * <a href="http://www.etsi.org/deliver/etsi_ts/102300_102399/102366/01.02.01_60/ts_102366v010201p.pdf>
 * http://www.etsi.org/deliver/etsi_ts/102300_102399/102366/01.02.01_60/ts_102366v010201p.pdf</a>。
 * 
 * @author in-somnia
 */
public class AC3SpecificBox extends CodecSpecificBox {

	private int fscod, bsid, bsmod, acmod, bitRateCode;
	private boolean lfeon;

	public AC3SpecificBox() {
		super("AC-3 Specific Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		final long l = in.readBytes(3);

		//2 位 fscod
		fscod = (int) ((l>>22)&0x3);
		//5 位 bsid
		bsid = (int) ((l>>17)&0x1F);
		//3 位 bsmod
		bsmod = (int) ((l>>14)&0x7);
		//3 位 acmod
		acmod = (int) ((l>>11)&0x7);
		//1 位 lfeon
		lfeon = ((l>>10)&0x1)==1;
		//5 位 bitRateCode
		bitRateCode = (int) ((l>>5)&0x1F);
		//5 位保留
	}

	/**
	 * 此字段含义相同，并设为与 AC-3 比特流中 fscod 字段相同的值。
	 * 
	 * @return 'fscod' 字段的值
	 */
	public int getFscod() {
		return fscod;
	}

	/**
	 * 此字段含义相同，并设为与 AC-3 比特流中 bsid 字段相同的值。
	 * 
	 * @return 'bsid' 字段的值
	 */
	public int getBsid() {
		return bsid;
	}

	/**
	 * 此字段含义相同，并设为与 AC-3 比特流中 bsmod 字段相同的值。
	 * 
	 * @return 'acmod' 字段的值
	 */
	public int getBsmod() {
		return bsmod;
	}

	/**
	 * 此字段含义相同，并设为与 AC-3 比特流中 acmod 字段相同的值。
	 * 
	 * @return 'acmod' 字段的值
	 */
	public int getAcmod() {
		return acmod;
	}

	/**
	 * 此字段含义相同，并设为与 AC-3 比特流中 lfeon 字段相同的值。
	 * 
	 * @return 'lfeon' 字段的值
	 */
	public boolean isLfeon() {
		return lfeon;
	}

	/**
	 * 此字段指示 AC-3 比特流的数据率（kbit/s），如下表：
	 * <table>
	 * <tr><th>比特率代码</th><th>比特率（kbit/s）</th></tr>
	 * <tr><td>0</td><td>32</td></tr>
	 * <tr><td>1</td><td>40</td></tr>
	 * <tr><td>2</td><td>48</td></tr>
	 * <tr><td>3</td><td>56</td></tr>
	 * <tr><td>4</td><td>64</td></tr>
	 * <tr><td>5</td><td>80</td></tr>
	 * <tr><td>6</td><td>96</td></tr>
	 * <tr><td>7</td><td>112</td></tr>
	 * <tr><td>8</td><td>128</td></tr>
	 * <tr><td>9</td><td>160</td></tr>
	 * <tr><td>10</td><td>192</td></tr>
	 * <tr><td>11</td><td>224</td></tr>
	 * <tr><td>12</td><td>256</td></tr>
	 * <tr><td>13</td><td>320</td></tr>
	 * <tr><td>14</td><td>384</td></tr>
	 * <tr><td>15</td><td>448</td></tr>
	 * <tr><td>16</td><td>512</td></tr>
	 * <tr><td>17</td><td>576</td></tr>
	 * <tr><td>18</td><td>640</td></tr>
	 * </table>
	 * 
	 * @return 比特率代码
	 */
	public int getBitRateCode() {
		return bitRateCode;
	}
}
