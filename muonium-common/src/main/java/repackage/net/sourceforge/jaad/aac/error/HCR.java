package repackage.net.sourceforge.jaad.aac.error;

import repackage.net.sourceforge.jaad.aac.AACException;
import repackage.net.sourceforge.jaad.aac.huffman.HCB;
import repackage.net.sourceforge.jaad.aac.syntax.BitStream;
import repackage.net.sourceforge.jaad.aac.syntax.Constants;
import repackage.net.sourceforge.jaad.aac.syntax.ICSInfo;
import repackage.net.sourceforge.jaad.aac.syntax.ICStream;

/**
 * Huffman 码字重排序
 * 当 section 数据使用错误弹性时，为 ICStreams 解码频谱数据。
 */
//TODO: 需要 BitStream 中的 decodeSpectralDataER() 方法
public class HCR implements Constants {

	private static class Codeword {

		int cb, decoded, sp_offset;
		BitsBuffer bits;

		private void fill(int sp, int cb) {
			sp_offset = sp;
			this.cb = cb;
			decoded = 0;
			bits = new BitsBuffer();
		}
	}
	private static final int NUM_CB = 6;
	private static final int NUM_CB_ER = 22;
	private static final int MAX_CB = 32;
	private static final int VCB11_FIRST = 16;
	private static final int VCB11_LAST = 31;
	private static final int[] PRE_SORT_CB_STD = {11, 9, 7, 5, 3, 1};
	private static final int[] PRE_SORT_CB_ER = {11, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 9, 7, 5, 3, 1};
	private static final int[] MAX_CW_LEN = {0, 11, 9, 20, 16, 13, 11, 14, 12, 17, 14, 49,
		0, 0, 0, 0, 14, 17, 21, 21, 25, 25, 29, 29, 29, 29, 33, 33, 33, 37, 37, 41};
	//位操作辅助函数
	private static final int[] S = {1, 2, 4, 8, 16};
	private static final int[] B = {0x55555555, 0x33333333, 0x0F0F0F0F, 0x00FF00FF, 0x0000FFFF};

	//32 位回退并反转
	private static int rewindReverse(int v, int len) {
		v = ((v>>S[0])&B[0])|((v<<S[0])&~B[0]);
		v = ((v>>S[1])&B[1])|((v<<S[1])&~B[1]);
		v = ((v>>S[2])&B[2])|((v<<S[2])&~B[2]);
		v = ((v>>S[3])&B[3])|((v<<S[3])&~B[3]);
		v = ((v>>S[4])&B[4])|((v<<S[4])&~B[4]);

		//移出低位
		v >>= (32-len);

		return v;
	}

	//64 位回退并反转
	static int[] rewindReverse64(int hi, int lo, int len) {
		int[] i = new int[2];
		if(len<=32) {
			i[0] = 0;
			i[1] = rewindReverse(lo, len);
		}
		else {
			lo = ((lo>>S[0])&B[0])|((lo<<S[0])&~B[0]);
			hi = ((hi>>S[0])&B[0])|((hi<<S[0])&~B[0]);
			lo = ((lo>>S[1])&B[1])|((lo<<S[1])&~B[1]);
			hi = ((hi>>S[1])&B[1])|((hi<<S[1])&~B[1]);
			lo = ((lo>>S[2])&B[2])|((lo<<S[2])&~B[2]);
			hi = ((hi>>S[2])&B[2])|((hi<<S[2])&~B[2]);
			lo = ((lo>>S[3])&B[3])|((lo<<S[3])&~B[3]);
			hi = ((hi>>S[3])&B[3])|((hi<<S[3])&~B[3]);
			lo = ((lo>>S[4])&B[4])|((lo<<S[4])&~B[4]);
			hi = ((hi>>S[4])&B[4])|((hi<<S[4])&~B[4]);

			//移出低位
			i[1] = (hi>>(64-len))|(lo<<(len-32));
			i[1] = lo>>(64-len);
		}
		return i;
	}

	private static boolean isGoodCB(int cb, int sectCB) {
		boolean b = false;
		if((sectCB>HCB.ZERO_HCB&&sectCB<=HCB.ESCAPE_HCB)||(sectCB>=VCB11_FIRST&&sectCB<=VCB11_LAST)) {
			if(cb<HCB.ESCAPE_HCB) b = ((sectCB==cb)||(sectCB==cb+1));
			else b = (sectCB==cb);
		}
		return b;
	}

	//sectionDataResilience = hDecoder->aacSectionDataResilienceFlag
	public static void decodeReorderedSpectralData(ICStream ics, BitStream in, short[] spectralData, boolean sectionDataResilience) throws AACException {
		final ICSInfo info = ics.getInfo();
		final int windowGroupCount = info.getWindowGroupCount();
		final int maxSFB = info.getMaxSFB();
		final int[] swbOffsets = info.getSWBOffsets();
		final int swbOffsetMax = info.getSWBOffsetMax();
		//TODO:
		//final SectionData sectData = ics.getSectionData();
		final int[][] sectStart = null; //sectData.getSectStart();
		final int[][] sectEnd = null; //sectData.getSectEnd();
		final int[] numSec = null; //sectData.getNumSec();
		final int[][] sectCB = null; //sectData.getSectCB();
		final int[][] sectSFBOffsets = null; //info.getSectSFBOffsets();

		//检查参数
		final int spDataLen = ics.getReorderedSpectralDataLength();
		if(spDataLen==0) return;

		final int longestLen = ics.getLongestCodewordLength();
		if(longestLen==0||longestLen>=spDataLen) throw new AACException("length of longest HCR codeword out of range");

		//创建 spOffsets
		final int[] spOffsets = new int[8];
		final int shortFrameLen = spectralData.length/8;
		spOffsets[0] = 0;
		int g;
		for(g = 1; g<windowGroupCount; g++) {
			spOffsets[g] = spOffsets[g-1]+shortFrameLen*info.getWindowGroupLength(g-1);
		}

		final Codeword[] codeword = new Codeword[512];
		final BitsBuffer[] segment = new BitsBuffer[512];

		int lastCB;
		int[] preSortCB;
		if(sectionDataResilience) {
			preSortCB = PRE_SORT_CB_ER;
			lastCB = NUM_CB_ER;
		}
		else {
			preSortCB = PRE_SORT_CB_STD;
			lastCB = NUM_CB;
		}

		int PCWs_done = 0;
		int segmentsCount = 0;
		int numberOfCodewords = 0;
		int bitsread = 0;

		int sfb, w_idx, i, thisCB, thisSectCB, cws;
		//第 1 步：解码 PCW（set 0），并将数据整理为更易使用的格式
		for(int sortloop = 0; sortloop<lastCB; sortloop++) {
			//选择本轮要处理的码本
			thisCB = preSortCB[sortloop];

			for(sfb = 0; sfb<maxSFB; sfb++) {
				for(w_idx = 0; 4*w_idx<(Math.min(swbOffsets[sfb+1], swbOffsetMax)-swbOffsets[sfb]); w_idx++) {
					for(g = 0; g<windowGroupCount; g++) {
						for(i = 0; i<numSec[g]; i++) {
							if((sectStart[g][i]<=sfb)&&(sectEnd[g][i]>sfb)) {
								/* 检查此处使用的码本是否为我们要处理的码本 */
								thisSectCB = sectCB[g][i];

								if(isGoodCB(thisCB, thisSectCB)) {
									//预计算
									int sect_sfb_size = sectSFBOffsets[g][sfb+1]-sectSFBOffsets[g][sfb];
									int inc = (thisSectCB<HCB.FIRST_PAIR_HCB) ? 4 : 2;
									int group_cws_count = (4*info.getWindowGroupLength(g))/inc;
									int segwidth = Math.min(MAX_CW_LEN[thisSectCB], longestLen);

									//读取码字，直到到达 sfb 末尾或窗口组末尾
									for(cws = 0; (cws<group_cws_count)&&((cws+w_idx*group_cws_count)<sect_sfb_size); cws++) {
										int sp = spOffsets[g]+sectSFBOffsets[g][sfb]+inc*(cws+w_idx*group_cws_count);

										//读取并解码 PCW
										if(PCWs_done==0) {
											//按常规段读取
											if(bitsread+segwidth<=spDataLen) {
												segment[segmentsCount].readSegment(segwidth, in);
												bitsread += segwidth;

												//Huffman.decodeSpectralDataER(segment[segmentsCount], thisSectCB, spectralData, sp);

												//保留剩余位
												segment[segmentsCount].rewindReverse();

												segmentsCount++;
											}
											else {
												//最后一段之后的剩余部分
												if(bitsread<spDataLen) {
													final int additional_bits = spDataLen-bitsread;

													segment[segmentsCount].readSegment(additional_bits, in);
													segment[segmentsCount].len += segment[segmentsCount-1].len;
													segment[segmentsCount].rewindReverse();

													if(segment[segmentsCount-1].len>32) {
														segment[segmentsCount-1].bufb = segment[segmentsCount].bufb
																+segment[segmentsCount-1].showBits(segment[segmentsCount-1].len-32);
														segment[segmentsCount-1].bufa = segment[segmentsCount].bufa
																+segment[segmentsCount-1].showBits(32);
													}
													else {
														segment[segmentsCount-1].bufa = segment[segmentsCount].bufa
																+segment[segmentsCount-1].showBits(segment[segmentsCount-1].len);
														segment[segmentsCount-1].bufb = segment[segmentsCount].bufb;
													}
													segment[segmentsCount-1].len += additional_bits;
												}
												bitsread = spDataLen;
												PCWs_done = 1;

												codeword[0].fill(sp, thisSectCB);
											}
										}
										else {
											codeword[numberOfCodewords-segmentsCount].fill(sp, thisSectCB);
										}
										numberOfCodewords++;
									}
								}
							}
						}
					}
				}
			}
		}

		if(segmentsCount==0) throw new AACException("no segments in HCR");

		final int numberOfSets = numberOfCodewords/segmentsCount;

		//第 2 步：解码 nonPCW
		int trial, codewordBase, segmentID, codewordID;
		for(int set = 1; set<=numberOfSets; set++) {
			for(trial = 0; trial<segmentsCount; trial++) {
				for(codewordBase = 0; codewordBase<segmentsCount; codewordBase++) {
					segmentID = (trial+codewordBase)%segmentsCount;
					codewordID = codewordBase+set*segmentsCount-segmentsCount;

					//数据向上
					if(codewordID>=numberOfCodewords-segmentsCount) break;

					if((codeword[codewordID].decoded==0)&&(segment[segmentID].len>0)) {
						if(codeword[codewordID].bits.len!=0) segment[segmentID].concatBits(codeword[codewordID].bits);

						int tmplen = segment[segmentID].len;
						/*int ret = Huffman.decodeSpectralDataER(segment[segmentID], codeword[codewordID].cb,
								spectralData, codeword[codewordID].sp_offset);

						if(ret>=0) codeword[codewordID].decoded = 1;
						else {
							codeword[codewordID].bits = segment[segmentID];
							codeword[codewordID].bits.len = tmplen;
						}*/

					}
				}
			}
			for(i = 0; i<segmentsCount; i++) {
				segment[i].rewindReverse();
			}
		}
	}
}
