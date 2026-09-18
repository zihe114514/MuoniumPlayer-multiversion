package repackage.net.sourceforge.jaad.aac.ps;

import repackage.net.sourceforge.jaad.aac.AACException;
import repackage.net.sourceforge.jaad.aac.SampleFrequency;
import repackage.net.sourceforge.jaad.aac.syntax.BitStream;

public class PS implements PSConstants, PSTables, HuffmanTables {

	/* 比特流参数 */
	boolean enable_iid, enable_icc, enable_ext;
	int iid_mode;
	int icc_mode;
	int nr_iid_par;
	int nr_ipdopd_par;
	int nr_icc_par;
	int frame_class;
	int num_env;
	int[] border_position = new int[MAX_PS_ENVELOPES+1];
	boolean[] iid_dt = new boolean[MAX_PS_ENVELOPES];
	boolean[] icc_dt = new boolean[MAX_PS_ENVELOPES];
	boolean enable_ipdopd;
	int ipd_mode;
	boolean[] ipd_dt = new boolean[MAX_PS_ENVELOPES];
	boolean[] opd_dt = new boolean[MAX_PS_ENVELOPES];

	/* 索引 */
	int[] iid_index_prev = new int[34];
	int[] icc_index_prev = new int[34];
	int[] ipd_index_prev = new int[17];
	int[] opd_index_prev = new int[17];
	int[][] iid_index = new int[MAX_PS_ENVELOPES][34];
	int[][] icc_index = new int[MAX_PS_ENVELOPES][34];
	int[][] ipd_index = new int[MAX_PS_ENVELOPES][17];
	int[][] opd_index = new int[MAX_PS_ENVELOPES][17];

	int[] ipd_index_1 = new int[17];
	int[] opd_index_1 = new int[17];
	int[] ipd_index_2 = new int[17];
	int[] opd_index_2 = new int[17];
	/* ps 数据已正确读取 */
	int ps_data_available;
	/* 已读取一个头 */
	public boolean header_read;
	/* 混合滤波器组参数 */
	Filterbank hyb;
	boolean use34hybrid_bands;
	int numTimeSlotsRate;
	int num_groups;
	int num_hybrid_groups;
	int nr_par_bands;
	int nr_allpass_bands;
	int decay_cutoff;
	int[] group_border;
	int[] map_group2bk;
	/* 滤波器延迟处理 */
	int saved_delay;
	int[] delay_buf_index_ser = new int[NO_ALLPASS_LINKS];
	int[] num_sample_delay_ser = new int[NO_ALLPASS_LINKS];
	int[] delay_D = new int[64];
	int[] delay_buf_index_delay = new int[64];
	float[][][] delay_Qmf = new float[14][64][2]; /* 最大 14 个采样延迟，64 个 QMF 声道 */

	float[][][] delay_SubQmf = new float[2][32][2]; /* 最大 2 个采样延迟（SubQmf 始终经过全通滤波） */

	float[][][][] delay_Qmf_ser = new float[NO_ALLPASS_LINKS][5][64][2]; /* 最大 5 个采样延迟（表 8.34），64 个 QMF 声道 */

	float[][][][] delay_SubQmf_ser = new float[NO_ALLPASS_LINKS][5][32][2]; /* 最大 5 个采样延迟（表 8.34） */
	/* 瞬态 */

	float alpha_decay;
	float alpha_smooth;
	float[] P_PeakDecayNrg = new float[34];
	float[] P_prev = new float[34];
	float[] P_SmoothPeakDecayDiffNrg_prev = new float[34];
	/* 混合与相位 */
	float[][] h11_prev = new float[50][2];
	float[][] h12_prev = new float[50][2];
	float[][] h21_prev = new float[50][2];
	float[][] h22_prev = new float[50][2];
	int phase_hist;
	float[][][] ipd_prev = new float[20][2][2];
	float[][][] opd_prev = new float[20][2][2];

	public PS(SampleFrequency sr, int numTimeSlotsRate) {
		int i;
		int short_delay_band;

		hyb = new Filterbank(numTimeSlotsRate);
		this.numTimeSlotsRate = numTimeSlotsRate;

		this.ps_data_available = 0;

		/* 延迟相关 */
		this.saved_delay = 0;

		for(i = 0; i<64; i++) {
			this.delay_buf_index_delay[i] = 0;
		}

		for(i = 0; i<NO_ALLPASS_LINKS; i++) {
			this.delay_buf_index_ser[i] = 0;
			/* 以下现在是常量 */
			this.num_sample_delay_ser[i] = delay_length_d[i];
		}

		/* 以下现在是常量 */
		short_delay_band = 35;
		this.nr_allpass_bands = 22;
		this.alpha_decay = 0.76592833836465f;
		this.alpha_smooth = 0.25f;

		/* 如果 PS 与采样率无关，以下现在是常量 */
		for(i = 0; i<short_delay_band; i++) {
			this.delay_D[i] = 14;
		}
		for(i = short_delay_band; i<64; i++) {
			this.delay_D[i] = 1;
		}

		/* 混合与相位 */
		for(i = 0; i<50; i++) {
			this.h11_prev[i][0] = 1;
			this.h12_prev[i][1] = 1;
			this.h11_prev[i][0] = 1;
			this.h12_prev[i][1] = 1;
		}

		this.phase_hist = 0;

		for(i = 0; i<20; i++) {
			this.ipd_prev[i][0][0] = 0;
			this.ipd_prev[i][0][1] = 0;
			this.ipd_prev[i][1][0] = 0;
			this.ipd_prev[i][1][1] = 0;
			this.opd_prev[i][0][0] = 0;
			this.opd_prev[i][0][1] = 0;
			this.opd_prev[i][1][0] = 0;
			this.opd_prev[i][1][1] = 0;
		}
	}

	public int decode(BitStream ld) throws AACException {
		int tmp, n;
		long bits = ld.getPosition();

		/* 检查是否有新的 PS 头 */
		if(ld.readBool()) {
			this.header_read = true;

			this.use34hybrid_bands = false;

			/* 已启用声道间强度差（IID）参数 */
			this.enable_iid = ld.readBool();

			if(this.enable_iid) {
				this.iid_mode = ld.readBits(3);

				this.nr_iid_par = nr_iid_par_tab[this.iid_mode];
				this.nr_ipdopd_par = nr_ipdopd_par_tab[this.iid_mode];

				if(this.iid_mode==2||this.iid_mode==5)
					this.use34hybrid_bands = true;

				/* IPD 频率分辨率等于 IID 频率分辨率 */
				this.ipd_mode = this.iid_mode;
			}

			/* 已启用声道间相干性（ICC）参数 */
			this.enable_icc = ld.readBool();

			if(this.enable_icc) {
				this.icc_mode = ld.readBits(3);

				this.nr_icc_par = nr_icc_par_tab[this.icc_mode];

				if(this.icc_mode==2||this.icc_mode==5)
					this.use34hybrid_bands = true;
			}

			/* 已启用 PS 扩展层 */
			this.enable_ext = ld.readBool();
		}

		/* 我们到了这里，但尚未读取任何头 */
		if(this.header_read==false) {
			this.ps_data_available = 0;
			return 1;
		}

		this.frame_class = ld.readBit();
		tmp = ld.readBits(2);

		this.num_env = num_env_tab[this.frame_class][tmp];

		if(this.frame_class!=0) {
			for(n = 1; n<this.num_env+1; n++) {
				this.border_position[n] = ld.readBits(5)+1;
			}
		}

		if(this.enable_iid) {
			for(n = 0; n<this.num_env; n++) {
				this.iid_dt[n] = ld.readBool();

				/* iid_data */
				if(this.iid_mode<3) {
					huff_data(ld, this.iid_dt[n], this.nr_iid_par, t_huff_iid_def,
						f_huff_iid_def, this.iid_index[n]);
				}
				else {
					huff_data(ld, this.iid_dt[n], this.nr_iid_par, t_huff_iid_fine,
						f_huff_iid_fine, this.iid_index[n]);
				}
			}
		}

		if(this.enable_icc) {
			for(n = 0; n<this.num_env; n++) {
				this.icc_dt[n] = ld.readBool();

				/* icc_data */
				huff_data(ld, this.icc_dt[n], this.nr_icc_par, t_huff_icc,
					f_huff_icc, this.icc_index[n]);
			}
		}

		if(this.enable_ext) {
			int num_bits_left;
			int cnt = ld.readBits(4);
			if(cnt==15) {
				cnt += ld.readBits(8);
			}

			num_bits_left = 8*cnt;
			while(num_bits_left>7) {
				int ps_extension_id = ld.readBits(2);

				num_bits_left -= 2;
				num_bits_left -= ps_extension(ld, ps_extension_id, num_bits_left);
			}

			ld.skipBits(num_bits_left);
		}

		int bits2 = (int) (ld.getPosition()-bits);

		this.ps_data_available = 1;

		return bits2;
	}

	private int ps_extension(BitStream ld,
		int ps_extension_id,
		int num_bits_left) throws AACException {
		int n;
		long bits = ld.getPosition();

		if(ps_extension_id==0) {
			this.enable_ipdopd = ld.readBool();

			if(this.enable_ipdopd) {
				for(n = 0; n<this.num_env; n++) {
					this.ipd_dt[n] = ld.readBool();

					/* ipd_data */
					huff_data(ld, this.ipd_dt[n], this.nr_ipdopd_par, t_huff_ipd,
						f_huff_ipd, this.ipd_index[n]);

					this.opd_dt[n] = ld.readBool();

					/* opd_data */
					huff_data(ld, this.opd_dt[n], this.nr_ipdopd_par, t_huff_opd,
						f_huff_opd, this.opd_index[n]);
				}
			}
			ld.readBit(); //保留
		}

		/* 返回已读取的位数 */
		int bits2 = (int) (ld.getPosition()-bits);

		return bits2;
	}

	/* 读取以频率方向或时间方向编码的 huffman 数据 */
	private void huff_data(BitStream ld, boolean dt, int nr_par,
		int[][] t_huff, int[][] f_huff, int[] par) throws AACException {
		int n;

		if(dt) {
			/* 以时间方向编码 */
			for(n = 0; n<nr_par; n++) {
				par[n] = ps_huff_dec(ld, t_huff);
			}
		}
		else {
			/* 以频率方向编码 */
			par[0] = ps_huff_dec(ld, f_huff);

			for(n = 1; n<nr_par; n++) {
				par[n] = ps_huff_dec(ld, f_huff);
			}
		}
	}

	/* 二分查找 huffman 解码 */
	private int ps_huff_dec(BitStream ld, int[][] t_huff) throws AACException {
		int bit;
		int index = 0;

		while(index>=0) {
			bit = ld.readBit();
			index = t_huff[index][bit];
		}

		return index+31;
	}

	/* 将值 i 限制在范围 [min,max] 内 */
	private int delta_clip(int i, int min, int max) {
		if(i<min) return min;
		else if(i>max) return max;
		else return i;
	}


	/* 增量解码数组 */
	private void delta_decode(boolean enable, int[] index, int[] index_prev,
		boolean dt_flag, int nr_par, int stride,
		int min_index, int max_index) {
		int i;

		if(enable) {
			if(!dt_flag) {
				/* 以频率方向进行增量编码 */
				index[0] = 0+index[0];
				index[0] = delta_clip(index[0], min_index, max_index);

				for(i = 1; i<nr_par; i++) {
					index[i] = index[i-1]+index[i];
					index[i] = delta_clip(index[i], min_index, max_index);
				}
			}
			else {
				/* 以时间方向进行增量编码 */
				for(i = 0; i<nr_par; i++) {
                //int8_t tmp2;
					//int8_t tmp = index[i];

					//printf("%d %d\n", index_prev[i*stride], index[i]);
					//printf("%d\n", index[i]);
					index[i] = index_prev[i*stride]+index[i];
					//tmp2 = index[i];
					index[i] = delta_clip(index[i], min_index, max_index);

					//if (iid)
					//{
					//    if (index[i] == 7)
					//    {
					//        printf("%d %d %d\n", index_prev[i*stride], tmp, tmp2);
					//    }
					//}
				}
			}
		}
		else {
			/* 将索引置零 */
			for(i = 0; i<nr_par; i++) {
				index[i] = 0;
			}
		}

		/* 粗略（coarse） */
		if(stride==2) {
			for(i = (nr_par<<1)-1; i>0; i--) {
				index[i] = index[i>>1];
			}
		}
	}

	/* 增量取模解码数组 */
	/* 入参：取模值的 log2 值，以便用 AND 代替 MOD */
	private void delta_modulo_decode(boolean enable, int[] index, int[] index_prev,
		boolean dt_flag, int nr_par, int stride,
		int and_modulo) {
		int i;

		if(enable) {
			if(!dt_flag) {
				/* 以频率方向进行增量编码 */
				index[0] = 0+index[0];
				index[0] &= and_modulo;

				for(i = 1; i<nr_par; i++) {
					index[i] = index[i-1]+index[i];
					index[i] &= and_modulo;
				}
			}
			else {
				/* 以时间方向进行增量编码 */
				for(i = 0; i<nr_par; i++) {
					index[i] = index_prev[i*stride]+index[i];
					index[i] &= and_modulo;
				}
			}
		}
		else {
			/* 将索引置零 */
			for(i = 0; i<nr_par; i++) {
				index[i] = 0;
			}
		}

		/* 粗略（coarse） */
		if(stride==2) {
			index[0] = 0;
			for(i = (nr_par<<1)-1; i>0; i--) {
				index[i] = index[i>>1];
			}
		}
	}

	private void map20indexto34(int[] index, int bins) {
		//index[0] = index[0];
		index[1] = (index[0]+index[1])/2;
		index[2] = index[1];
		index[3] = index[2];
		index[4] = (index[2]+index[3])/2;
		index[5] = index[3];
		index[6] = index[4];
		index[7] = index[4];
		index[8] = index[5];
		index[9] = index[5];
		index[10] = index[6];
		index[11] = index[7];
		index[12] = index[8];
		index[13] = index[8];
		index[14] = index[9];
		index[15] = index[9];
		index[16] = index[10];

		if(bins==34) {
			index[17] = index[11];
			index[18] = index[12];
			index[19] = index[13];
			index[20] = index[14];
			index[21] = index[14];
			index[22] = index[15];
			index[23] = index[15];
			index[24] = index[16];
			index[25] = index[16];
			index[26] = index[17];
			index[27] = index[17];
			index[28] = index[18];
			index[29] = index[18];
			index[30] = index[18];
			index[31] = index[18];
			index[32] = index[19];
			index[33] = index[19];
		}
	}

	/* 解析在 ps_data() 中解码的比特流数据 */
	private void ps_data_decode() {
		int env, bin;

		/* ps 数据不可用，使用上一帧的数据 */
		if(this.ps_data_available==0) {
			this.num_env = 0;
		}

		for(env = 0; env<this.num_env; env++) {
			int[] iid_index_prev;
			int[] icc_index_prev;
			int[] ipd_index_prev;
			int[] opd_index_prev;

			int num_iid_steps = (this.iid_mode<3) ? 7 : 15 /*精细量化*/;

			if(env==0) {
				/* 沿用上一帧的最后一个包络 */
				iid_index_prev = this.iid_index_prev;
				icc_index_prev = this.icc_index_prev;
				ipd_index_prev = this.ipd_index_prev;
				opd_index_prev = this.opd_index_prev;
			}
			else {
				/* 沿用上一个包络的索引值 */
				iid_index_prev = this.iid_index[env-1];
				icc_index_prev = this.icc_index[env-1];
				ipd_index_prev = this.ipd_index[env-1];
				opd_index_prev = this.opd_index[env-1];
			}

//        iid = 1;
        /* 对 iid 参数进行增量解码 */
			delta_decode(this.enable_iid, this.iid_index[env], iid_index_prev,
				this.iid_dt[env], this.nr_iid_par,
				(this.iid_mode==0||this.iid_mode==3) ? 2 : 1,
				-num_iid_steps, num_iid_steps);
//        iid = 0;

			/* 对 icc 参数进行增量解码 */
			delta_decode(this.enable_icc, this.icc_index[env], icc_index_prev,
				this.icc_dt[env], this.nr_icc_par,
				(this.icc_mode==0||this.icc_mode==3) ? 2 : 1,
				0, 7);

			/* 对 ipd 参数进行增量取模解码 */
			delta_modulo_decode(this.enable_ipdopd, this.ipd_index[env], ipd_index_prev,
				this.ipd_dt[env], this.nr_ipdopd_par, 1, 7);

			/* 对 opd 参数进行增量取模解码 */
			delta_modulo_decode(this.enable_ipdopd, this.opd_index[env], opd_index_prev,
				this.opd_dt[env], this.nr_ipdopd_par, 1, 7);
		}

		/* 处理错误情况 */
		if(this.num_env==0) {
			/* 强制设为 1 */
			this.num_env = 1;

			if(this.enable_iid) {
				for(bin = 0; bin<34; bin++) {
					this.iid_index[0][bin] = this.iid_index_prev[bin];
				}
			}
			else {
				for(bin = 0; bin<34; bin++) {
					this.iid_index[0][bin] = 0;
				}
			}

			if(this.enable_icc) {
				for(bin = 0; bin<34; bin++) {
					this.icc_index[0][bin] = this.icc_index_prev[bin];
				}
			}
			else {
				for(bin = 0; bin<34; bin++) {
					this.icc_index[0][bin] = 0;
				}
			}

			if(this.enable_ipdopd) {
				for(bin = 0; bin<17; bin++) {
					this.ipd_index[0][bin] = this.ipd_index_prev[bin];
					this.opd_index[0][bin] = this.opd_index_prev[bin];
				}
			}
			else {
				for(bin = 0; bin<17; bin++) {
					this.ipd_index[0][bin] = 0;
					this.opd_index[0][bin] = 0;
				}
			}
		}

		/* 更新先前的索引 */
		for(bin = 0; bin<34; bin++) {
			this.iid_index_prev[bin] = this.iid_index[this.num_env-1][bin];
		}
		for(bin = 0; bin<34; bin++) {
			this.icc_index_prev[bin] = this.icc_index[this.num_env-1][bin];
		}
		for(bin = 0; bin<17; bin++) {
			this.ipd_index_prev[bin] = this.ipd_index[this.num_env-1][bin];
			this.opd_index_prev[bin] = this.opd_index[this.num_env-1][bin];
		}

		this.ps_data_available = 0;

		if(this.frame_class==0) {
			this.border_position[0] = 0;
			for(env = 1; env<this.num_env; env++) {
				this.border_position[env] = (env*this.numTimeSlotsRate)/this.num_env;
			}
			this.border_position[this.num_env] = this.numTimeSlotsRate;
		}
		else {
			this.border_position[0] = 0;

			if(this.border_position[this.num_env]<this.numTimeSlotsRate) {
				for(bin = 0; bin<34; bin++) {
					this.iid_index[this.num_env][bin] = this.iid_index[this.num_env-1][bin];
					this.icc_index[this.num_env][bin] = this.icc_index[this.num_env-1][bin];
				}
				for(bin = 0; bin<17; bin++) {
					this.ipd_index[this.num_env][bin] = this.ipd_index[this.num_env-1][bin];
					this.opd_index[this.num_env][bin] = this.opd_index[this.num_env-1][bin];
				}
				this.num_env++;
				this.border_position[this.num_env] = this.numTimeSlotsRate;
			}

			for(env = 1; env<this.num_env; env++) {
				int thr = this.numTimeSlotsRate-(this.num_env-env);

				if(this.border_position[env]>thr) {
					this.border_position[env] = thr;
				}
				else {
					thr = this.border_position[env-1]+1;
					if(this.border_position[env]<thr) {
						this.border_position[env] = thr;
					}
				}
			}
		}

		/* 确保所有参数的索引都能映射
			 * 到同一个混合综合滤波器组
			 */
		if(this.use34hybrid_bands) {
			for(env = 0; env<this.num_env; env++) {
				if(this.iid_mode!=2&&this.iid_mode!=5)
					map20indexto34(this.iid_index[env], 34);
				if(this.icc_mode!=2&&this.icc_mode!=5)
					map20indexto34(this.icc_index[env], 34);
				if(this.ipd_mode!=2&&this.ipd_mode!=5) {
					map20indexto34(this.ipd_index[env], 17);
					map20indexto34(this.opd_index[env], 17);
				}
			}
		}
	}

	/* 使用全通滤波器对单声道信号去相关 */
	private void ps_decorrelate(float[][][] X_left, float[][][] X_right,
		float[][][] X_hybrid_left, float[][][] X_hybrid_right) {
		int gr, n, m, bk;
		int temp_delay = 0;
		int sb, maxsb;
		int[] temp_delay_ser = new int[NO_ALLPASS_LINKS];
		float P_SmoothPeakDecayDiffNrg, nrg;
		float[][] P = new float[32][34];
		float[][] G_TransientRatio = new float[32][34];
		float[] inputLeft = new float[2];


		/* 选择混合滤波器组：20 或 34 频带情形 */
		float[][] Phi_Fract_SubQmf;
		if(this.use34hybrid_bands) {
			Phi_Fract_SubQmf = Phi_Fract_SubQmf34;
		}
		else {
			Phi_Fract_SubQmf = Phi_Fract_SubQmf20;
		}

		/* 清空能量值 */
		for(n = 0; n<32; n++) {
			for(bk = 0; bk<34; bk++) {
				P[n][bk] = 0;
			}
		}

		/* 计算每个参数频带 b(k) 中的能量 */
		for(gr = 0; gr<this.num_groups; gr++) {
			/* 选择该组所属的参数索引 b(k) */
			bk = (~NEGATE_IPD_MASK)&this.map_group2bk[gr];

			/* 为该组选择上子带边界 */
			maxsb = (gr<this.num_hybrid_groups) ? this.group_border[gr]+1 : this.group_border[gr+1];

			for(sb = this.group_border[gr]; sb<maxsb; sb++) {
				for(n = this.border_position[0]; n<this.border_position[this.num_env]; n++) {

					/* 来自混合子带或 QMF 子带的输入 */
					if(gr<this.num_hybrid_groups) {
						inputLeft[0] = X_hybrid_left[n][sb][0];
						inputLeft[1] = X_hybrid_left[n][sb][1];
					}
					else {
						inputLeft[0] = X_left[n][sb][0];
						inputLeft[1] = X_left[n][sb][1];
					}

					/* 累加能量 */
					P[n][bk] += (inputLeft[0]*inputLeft[0])+(inputLeft[1]*inputLeft[1]);
				}
			}
		}

		/* 计算每个参数频带 b(k) 的瞬态衰减比 */
		for(bk = 0; bk<this.nr_par_bands; bk++) {
			for(n = this.border_position[0]; n<this.border_position[this.num_env]; n++) {
				float gamma = 1.5f;

				this.P_PeakDecayNrg[bk] = (this.P_PeakDecayNrg[bk]*this.alpha_decay);
				if(this.P_PeakDecayNrg[bk]<P[n][bk])
					this.P_PeakDecayNrg[bk] = P[n][bk];

				/* 对峰值衰减能量应用平滑滤波器 */
				P_SmoothPeakDecayDiffNrg = this.P_SmoothPeakDecayDiffNrg_prev[bk];
				P_SmoothPeakDecayDiffNrg += ((this.P_PeakDecayNrg[bk]-P[n][bk]-this.P_SmoothPeakDecayDiffNrg_prev[bk])*alpha_smooth);
				this.P_SmoothPeakDecayDiffNrg_prev[bk] = P_SmoothPeakDecayDiffNrg;

				/* 对能量应用平滑滤波器 */
				nrg = this.P_prev[bk];
				nrg += ((P[n][bk]-this.P_prev[bk])*this.alpha_smooth);
				this.P_prev[bk] = nrg;

				/* 计算瞬态比 */
				if((P_SmoothPeakDecayDiffNrg*gamma)<=nrg) {
					G_TransientRatio[n][bk] = 1.0f;
				}
				else {
					G_TransientRatio[n][bk] = (nrg/(P_SmoothPeakDecayDiffNrg*gamma));
				}
			}
		}

		/* 对信号应用立体声去相关滤波器 */
		for(gr = 0; gr<this.num_groups; gr++) {
			if(gr<this.num_hybrid_groups)
				maxsb = this.group_border[gr]+1;
			else
				maxsb = this.group_border[gr+1];

			/* QMF 声道 */
			for(sb = this.group_border[gr]; sb<maxsb; sb++) {
				float g_DecaySlope;
				float[] g_DecaySlope_filt = new float[NO_ALLPASS_LINKS];

				/* g_DecaySlope: [0..1] */
				if(gr<this.num_hybrid_groups||sb<=this.decay_cutoff) {
					g_DecaySlope = 1.0f;
				}
				else {
					int decay = this.decay_cutoff-sb;
					if(decay<=-20 /* -1/DECAY_SLOPE */) {
						g_DecaySlope = 0;
					}
					else {
						/* decay(int)*decay_slope(frac) = g_DecaySlope(frac) */
						g_DecaySlope = 1.0f+DECAY_SLOPE*decay;
					}
				}

				/* 对每个 m 计算 g_DecaySlope_filt，乘以 filter_a[m] */
				for(m = 0; m<NO_ALLPASS_LINKS; m++) {
					g_DecaySlope_filt[m] = g_DecaySlope*filter_a[m];
				}


				/* 设置延迟索引 */
				temp_delay = this.saved_delay;
				for(n = 0; n<NO_ALLPASS_LINKS; n++) {
					temp_delay_ser[n] = this.delay_buf_index_ser[n];
				}

				for(n = this.border_position[0]; n<this.border_position[this.num_env]; n++) {
					float[] tmp = new float[2], tmp0 = new float[2], R0 = new float[2];

					if(gr<this.num_hybrid_groups) {
						/* 混合滤波器组输入 */
						inputLeft[0] = X_hybrid_left[n][sb][0];
						inputLeft[1] = X_hybrid_left[n][sb][1];
					}
					else {
						/* QMF 滤波器组输入 */
						inputLeft[0] = X_left[n][sb][0];
						inputLeft[1] = X_left[n][sb][1];
					}

					if(sb>this.nr_allpass_bands&&gr>=this.num_hybrid_groups) {
						/* 延迟 */

						/* 此处绝不使用混合子带，始终为 QMF 子带 */
						tmp[0] = this.delay_Qmf[this.delay_buf_index_delay[sb]][sb][0];
						tmp[1] = this.delay_Qmf[this.delay_buf_index_delay[sb]][sb][1];
						R0[0] = tmp[0];
						R0[1] = tmp[1];
						this.delay_Qmf[this.delay_buf_index_delay[sb]][sb][0] = inputLeft[0];
						this.delay_Qmf[this.delay_buf_index_delay[sb]][sb][1] = inputLeft[1];
					}
					else {
						/* 全通滤波器 */
						/* 此处绝不使用混合子带，始终为 QMF 子带 */
						float[] Phi_Fract = new float[2];

						/* 获取参数 */
						if(gr<this.num_hybrid_groups) {
							/* 从混合子带选择数据 */
							tmp0[0] = this.delay_SubQmf[temp_delay][sb][0];
							tmp0[1] = this.delay_SubQmf[temp_delay][sb][1];

							this.delay_SubQmf[temp_delay][sb][0] = inputLeft[0];
							this.delay_SubQmf[temp_delay][sb][1] = inputLeft[1];

							Phi_Fract[0] = Phi_Fract_SubQmf[sb][0];
							Phi_Fract[1] = Phi_Fract_SubQmf[sb][1];
						}
						else {
							/* 从 QMF 子带选择数据 */
							tmp0[0] = this.delay_Qmf[temp_delay][sb][0];
							tmp0[1] = this.delay_Qmf[temp_delay][sb][1];

							this.delay_Qmf[temp_delay][sb][0] = inputLeft[0];
							this.delay_Qmf[temp_delay][sb][1] = inputLeft[1];

							Phi_Fract[0] = Phi_Fract_Qmf[sb][0];
							Phi_Fract[1] = Phi_Fract_Qmf[sb][1];
						}

						/* z^(-2) * Phi_Fract[k] */
						tmp[0] = (tmp[0]*Phi_Fract[0])+(tmp0[1]*Phi_Fract[1]);
						tmp[1] = (tmp0[1]*Phi_Fract[0])-(tmp0[0]*Phi_Fract[1]);

						R0[0] = tmp[0];
						R0[1] = tmp[1];
						for(m = 0; m<NO_ALLPASS_LINKS; m++) {
							float[] Q_Fract_allpass = new float[2], tmp2 = new float[2];

							/* 获取参数 */
							if(gr<this.num_hybrid_groups) {
								/* 从混合子带选择数据 */
								tmp0[0] = this.delay_SubQmf_ser[m][temp_delay_ser[m]][sb][0];
								tmp0[1] = this.delay_SubQmf_ser[m][temp_delay_ser[m]][sb][1];

								if(this.use34hybrid_bands) {
									Q_Fract_allpass[0] = Q_Fract_allpass_SubQmf34[sb][m][0];
									Q_Fract_allpass[1] = Q_Fract_allpass_SubQmf34[sb][m][1];
								}
								else {
									Q_Fract_allpass[0] = Q_Fract_allpass_SubQmf20[sb][m][0];
									Q_Fract_allpass[1] = Q_Fract_allpass_SubQmf20[sb][m][1];
								}
							}
							else {
								/* 从 QMF 子带选择数据 */
								tmp0[0] = this.delay_Qmf_ser[m][temp_delay_ser[m]][sb][0];
								tmp0[1] = this.delay_Qmf_ser[m][temp_delay_ser[m]][sb][1];

								Q_Fract_allpass[0] = Q_Fract_allpass_Qmf[sb][m][0];
								Q_Fract_allpass[1] = Q_Fract_allpass_Qmf[sb][m][1];
							}

							/* 延迟一个分数 */
							/* z^(-d(m)) * Q_Fract_allpass[k,m] */
							tmp[0] = (tmp0[0]*Q_Fract_allpass[0])+(tmp0[1]*Q_Fract_allpass[1]);
							tmp[1] = (tmp0[1]*Q_Fract_allpass[0])-(tmp0[0]*Q_Fract_allpass[1]);

							/* -a(m) * g_DecaySlope[k] */
							tmp[0] += -(g_DecaySlope_filt[m]*R0[0]);
							tmp[1] += -(g_DecaySlope_filt[m]*R0[1]);

							/* -a(m) * g_DecaySlope[k] * Q_Fract_allpass[k,m] * z^(-d(m)) */
							tmp2[0] = R0[0]+(g_DecaySlope_filt[m]*tmp[0]);
							tmp2[1] = R0[1]+(g_DecaySlope_filt[m]*tmp[1]);

							/* 存储采样 */
							if(gr<this.num_hybrid_groups) {
								this.delay_SubQmf_ser[m][temp_delay_ser[m]][sb][0] = tmp2[0];
								this.delay_SubQmf_ser[m][temp_delay_ser[m]][sb][1] = tmp2[1];
							}
							else {
								this.delay_Qmf_ser[m][temp_delay_ser[m]][sb][0] = tmp2[0];
								this.delay_Qmf_ser[m][temp_delay_ser[m]][sb][1] = tmp2[1];
							}

							/* 保存供下一次迭代使用（若是最后一次迭代则作为输出值） */
							R0[0] = tmp[0];
							R0[1] = tmp[1];
						}
					}

					/* 选择 b(k) 以读取瞬态比 */
					bk = (~NEGATE_IPD_MASK)&this.map_group2bk[gr];

					/* 若发现过去的瞬态则进行压低（duck） */
					R0[0] = (G_TransientRatio[n][bk]*R0[0]);
					R0[1] = (G_TransientRatio[n][bk]*R0[1]);

					if(gr<this.num_hybrid_groups) {
						/* 混合 */
						X_hybrid_right[n][sb][0] = R0[0];
						X_hybrid_right[n][sb][1] = R0[1];
					}
					else {
						/* QMF */
						X_right[n][sb][0] = R0[0];
						X_right[n][sb][1] = R0[1];
					}

					/* 更新延迟缓冲区索引 */
					if(++temp_delay>=2) {
						temp_delay = 0;
					}

					/* 更新延迟索引 */
					if(sb>this.nr_allpass_bands&&gr>=this.num_hybrid_groups) {
						/* delay_D 取决于采样率，可取值 14 和 1 */
						if(++this.delay_buf_index_delay[sb]>=this.delay_D[sb]) {
							this.delay_buf_index_delay[sb] = 0;
						}
					}

					for(m = 0; m<NO_ALLPASS_LINKS; m++) {
						if(++temp_delay_ser[m]>=this.num_sample_delay_ser[m]) {
							temp_delay_ser[m] = 0;
						}
					}
				}
			}
		}

		/* 更新延迟索引 */
		this.saved_delay = temp_delay;
		for(m = 0; m<NO_ALLPASS_LINKS; m++) {
			this.delay_buf_index_ser[m] = temp_delay_ser[m];
		}
	}

	private float magnitude_c(float[] c) {
		return (float) Math.sqrt(c[0]*c[0]+c[1]*c[1]);
	}

	private void ps_mix_phase(float[][][] X_left, float[][][] X_right,
		float[][][] X_hybrid_left, float[][][] X_hybrid_right) {
		int n;
		int gr;
		int bk = 0;
		int sb, maxsb;
		int env;
		int nr_ipdopd_par;
		float[] h11 = new float[2], h12 = new float[2], h21 = new float[2], h22 = new float[2];
		float[] H11 = new float[2], H12 = new float[2], H21 = new float[2], H22 = new float[2];
		float[] deltaH11 = new float[2], deltaH12 = new float[2], deltaH21 = new float[2], deltaH22 = new float[2];
		float[] tempLeft = new float[2];
		float[] tempRight = new float[2];
		float[] phaseLeft = new float[2];
		float[] phaseRight = new float[2];
		float L;
		float[] sf_iid;
		int no_iid_steps;

		if(this.iid_mode>=3) {
			no_iid_steps = 15;
			sf_iid = sf_iid_fine;
		}
		else {
			no_iid_steps = 7;
			sf_iid = sf_iid_normal;
		}

		if(this.ipd_mode==0||this.ipd_mode==3) {
			nr_ipdopd_par = 11; /* 分辨率 */

		}
		else {
			nr_ipdopd_par = this.nr_ipdopd_par;
		}

		for(gr = 0; gr<this.num_groups; gr++) {
			bk = (~NEGATE_IPD_MASK)&this.map_group2bk[gr];

			/* 在 subqmf 域中每组使用一个声道 */
			maxsb = (gr<this.num_hybrid_groups) ? this.group_border[gr]+1 : this.group_border[gr+1];

			for(env = 0; env<this.num_env; env++) {
				if(this.icc_mode<3) {
					/* 8.6.4.6.2.1 中所述的 'A' 型混合 */
					float c_1, c_2;
					float cosa, sina;
					float cosb, sinb;
					float ab1, ab2;
					float ab3, ab4;

					/*
					 c_1 = sqrt(2.0 / (1.0 + pow(10.0, quant_iid[no_iid_steps + iid_index] / 10.0)));
					 c_2 = sqrt(2.0 / (1.0 + pow(10.0, quant_iid[no_iid_steps - iid_index] / 10.0)));
					 alpha = 0.5 * acos(quant_rho[icc_index]);
					 beta = alpha * ( c_1 - c_2 ) / sqrt(2.0);
					 */
					//printf("%d\n", ps.iid_index[env][bk]);

					/* 根据强度差计算比例因子 c_1 和 c_2 */
					c_1 = sf_iid[no_iid_steps+this.iid_index[env][bk]];
					c_2 = sf_iid[no_iid_steps-this.iid_index[env][bk]];

					/* 使用 ICC 参数计算 alpha 和 beta */
					cosa = cos_alphas[this.icc_index[env][bk]];
					sina = sin_alphas[this.icc_index[env][bk]];

					if(this.iid_mode>=3) {
						if(this.iid_index[env][bk]<0) {
							cosb = cos_betas_fine[-this.iid_index[env][bk]][this.icc_index[env][bk]];
							sinb = -sin_betas_fine[-this.iid_index[env][bk]][this.icc_index[env][bk]];
						}
						else {
							cosb = cos_betas_fine[this.iid_index[env][bk]][this.icc_index[env][bk]];
							sinb = sin_betas_fine[this.iid_index[env][bk]][this.icc_index[env][bk]];
						}
					}
					else {
						if(this.iid_index[env][bk]<0) {
							cosb = cos_betas_normal[-this.iid_index[env][bk]][this.icc_index[env][bk]];
							sinb = -sin_betas_normal[-this.iid_index[env][bk]][this.icc_index[env][bk]];
						}
						else {
							cosb = cos_betas_normal[this.iid_index[env][bk]][this.icc_index[env][bk]];
							sinb = sin_betas_normal[this.iid_index[env][bk]][this.icc_index[env][bk]];
						}
					}

					ab1 = (cosb*cosa);
					ab2 = (sinb*sina);
					ab3 = (sinb*cosa);
					ab4 = (cosb*sina);

					/* h_xy: COEF */
					h11[0] = (c_2*(ab1-ab2));
					h12[0] = (c_1*(ab1+ab2));
					h21[0] = (c_2*(ab3+ab4));
					h22[0] = (c_1*(ab3-ab4));
				}
				else {
					/* 8.6.4.6.2.2 中所述的 'B' 型混合 */
					float sina, cosa;
					float cosg, sing;

					if(this.iid_mode>=3) {
						int abs_iid = Math.abs(this.iid_index[env][bk]);

						cosa = sincos_alphas_B_fine[no_iid_steps+this.iid_index[env][bk]][this.icc_index[env][bk]];
						sina = sincos_alphas_B_fine[30-(no_iid_steps+this.iid_index[env][bk])][this.icc_index[env][bk]];
						cosg = cos_gammas_fine[abs_iid][this.icc_index[env][bk]];
						sing = sin_gammas_fine[abs_iid][this.icc_index[env][bk]];
					}
					else {
						int abs_iid = Math.abs(this.iid_index[env][bk]);

						cosa = sincos_alphas_B_normal[no_iid_steps+this.iid_index[env][bk]][this.icc_index[env][bk]];
						sina = sincos_alphas_B_normal[14-(no_iid_steps+this.iid_index[env][bk])][this.icc_index[env][bk]];
						cosg = cos_gammas_normal[abs_iid][this.icc_index[env][bk]];
						sing = sin_gammas_normal[abs_iid][this.icc_index[env][bk]];
					}

					h11[0] = (COEF_SQRT2*(cosa*cosg));
					h12[0] = (COEF_SQRT2*(sina*cosg));
					h21[0] = (COEF_SQRT2*(-cosa*sing));
					h22[0] = (COEF_SQRT2*(sina*sing));
				}

				/* 计算相位旋转参数 H_xy */
				/* 注意，这些参数的虚部仅在启用
				 * IPD 和 OPD 时才计算
				 */
				if((this.enable_ipdopd)&&(bk<nr_ipdopd_par)) {
					float xy, pq, xypq;

					/* 环形缓冲区索引 */
					int i = this.phase_hist;

					/* 先前值 */
					tempLeft[0] = (this.ipd_prev[bk][i][0]*0.25f);
					tempLeft[1] = (this.ipd_prev[bk][i][1]*0.25f);
					tempRight[0] = (this.opd_prev[bk][i][0]*0.25f);
					tempRight[1] = (this.opd_prev[bk][i][1]*0.25f);

					/* 保存当前值 */
					this.ipd_prev[bk][i][0] = ipdopd_cos_tab[Math.abs(this.ipd_index[env][bk])];
					this.ipd_prev[bk][i][1] = ipdopd_sin_tab[Math.abs(this.ipd_index[env][bk])];
					this.opd_prev[bk][i][0] = ipdopd_cos_tab[Math.abs(this.opd_index[env][bk])];
					this.opd_prev[bk][i][1] = ipdopd_sin_tab[Math.abs(this.opd_index[env][bk])];

					/* 加上当前值 */
					tempLeft[0] += this.ipd_prev[bk][i][0];
					tempLeft[1] += this.ipd_prev[bk][i][1];
					tempRight[0] += this.opd_prev[bk][i][0];
					tempRight[1] += this.opd_prev[bk][i][1];

					/* 环形缓冲区索引 */
					if(i==0) {
						i = 2;
					}
					i--;

					/* 获取前一个之前的值 */
					tempLeft[0] += (this.ipd_prev[bk][i][0]*0.5f);
					tempLeft[1] += (this.ipd_prev[bk][i][1]*0.5f);
					tempRight[0] += (this.opd_prev[bk][i][0]*0.5f);
					tempRight[1] += (this.opd_prev[bk][i][1]*0.5f);

					xy = magnitude_c(tempRight);
					pq = magnitude_c(tempLeft);

					if(xy!=0) {
						phaseLeft[0] = (tempRight[0]/xy);
						phaseLeft[1] = (tempRight[1]/xy);
					}
					else {
						phaseLeft[0] = 0;
						phaseLeft[1] = 0;
					}

					xypq = (xy*pq);

					if(xypq!=0) {
						float tmp1 = (tempRight[0]*tempLeft[0])+(tempRight[1]*tempLeft[1]);
						float tmp2 = (tempRight[1]*tempLeft[0])-(tempRight[0]*tempLeft[1]);

						phaseRight[0] = (tmp1/xypq);
						phaseRight[1] = (tmp2/xypq);
					}
					else {
						phaseRight[0] = 0;
						phaseRight[1] = 0;
					}

					/* MUL_F(COEF, REAL) = COEF */
					h11[1] = (h11[0]*phaseLeft[1]);
					h12[1] = (h12[0]*phaseRight[1]);
					h21[1] = (h21[0]*phaseLeft[1]);
					h22[1] = (h22[0]*phaseRight[1]);

					h11[0] = (h11[0]*phaseLeft[0]);
					h12[0] = (h12[0]*phaseRight[0]);
					h21[0] = (h21[0]*phaseLeft[0]);
					h22[0] = (h22[0]*phaseRight[0]);
				}

				/* 包络长度 n_e+1 - n_e（以时间采样计） */
				/* 0 < L <= 32：整数 */
				L = (float) (this.border_position[env+1]-this.border_position[env]);

				/* 通过线性插值获得最终的 H_xy */
				deltaH11[0] = (h11[0]-this.h11_prev[gr][0])/L;
				deltaH12[0] = (h12[0]-this.h12_prev[gr][0])/L;
				deltaH21[0] = (h21[0]-this.h21_prev[gr][0])/L;
				deltaH22[0] = (h22[0]-this.h22_prev[gr][0])/L;

				H11[0] = this.h11_prev[gr][0];
				H12[0] = this.h12_prev[gr][0];
				H21[0] = this.h21_prev[gr][0];
				H22[0] = this.h22_prev[gr][0];

				this.h11_prev[gr][0] = h11[0];
				this.h12_prev[gr][0] = h12[0];
				this.h21_prev[gr][0] = h21[0];
				this.h22_prev[gr][0] = h22[0];

				/* 仅在需要时计算虚部 */
				if((this.enable_ipdopd)&&(bk<nr_ipdopd_par)) {
					/* 通过线性插值获得最终的 H_xy */
					deltaH11[1] = (h11[1]-this.h11_prev[gr][1])/L;
					deltaH12[1] = (h12[1]-this.h12_prev[gr][1])/L;
					deltaH21[1] = (h21[1]-this.h21_prev[gr][1])/L;
					deltaH22[1] = (h22[1]-this.h22_prev[gr][1])/L;

					H11[1] = this.h11_prev[gr][1];
					H12[1] = this.h12_prev[gr][1];
					H21[1] = this.h21_prev[gr][1];
					H22[1] = this.h22_prev[gr][1];

					if((NEGATE_IPD_MASK&this.map_group2bk[gr])!=0) {
						deltaH11[1] = -deltaH11[1];
						deltaH12[1] = -deltaH12[1];
						deltaH21[1] = -deltaH21[1];
						deltaH22[1] = -deltaH22[1];

						H11[1] = -H11[1];
						H12[1] = -H12[1];
						H21[1] = -H21[1];
						H22[1] = -H22[1];
					}

					this.h11_prev[gr][1] = h11[1];
					this.h12_prev[gr][1] = h12[1];
					this.h21_prev[gr][1] = h21[1];
					this.h22_prev[gr][1] = h22[1];
				}

				/* 将 H_xy 应用于去相关子带的当前包络频带 */
				for(n = this.border_position[env]; n<this.border_position[env+1]; n++) {
					/* 该加法完成了对每个 n 的插值 */
					H11[0] += deltaH11[0];
					H12[0] += deltaH12[0];
					H21[0] += deltaH21[0];
					H22[0] += deltaH22[0];
					if((this.enable_ipdopd)&&(bk<nr_ipdopd_par)) {
						H11[1] += deltaH11[1];
						H12[1] += deltaH12[1];
						H21[1] += deltaH21[1];
						H22[1] += deltaH22[1];
					}

					/* channel 是该子带的别名 */
					for(sb = this.group_border[gr]; sb<maxsb; sb++) {
						float[] inLeft = new float[2], inRight = new float[2];

						/* 加载去相关后的采样 */
						if(gr<this.num_hybrid_groups) {
							inLeft[0] = X_hybrid_left[n][sb][0];
							inLeft[1] = X_hybrid_left[n][sb][1];
							inRight[0] = X_hybrid_right[n][sb][0];
							inRight[1] = X_hybrid_right[n][sb][1];
						}
						else {
							inLeft[0] = X_left[n][sb][0];
							inLeft[1] = X_left[n][sb][1];
							inRight[0] = X_right[n][sb][0];
							inRight[1] = X_right[n][sb][1];
						}

						/* 应用混合 */
						tempLeft[0] = (H11[0]*inLeft[0])+(H21[0]*inRight[0]);
						tempLeft[1] = (H11[0]*inLeft[1])+(H21[0]*inRight[1]);
						tempRight[0] = (H12[0]*inLeft[0])+(H22[0]*inRight[0]);
						tempRight[1] = (H12[0]*inLeft[1])+(H22[0]*inRight[1]);

						/* 仅在需要时执行虚部运算 */
						if((this.enable_ipdopd)&&(bk<nr_ipdopd_par)) {
							/* 应用旋转 */
							tempLeft[0] -= (H11[1]*inLeft[1])+(H21[1]*inRight[1]);
							tempLeft[1] += (H11[1]*inLeft[0])+(H21[1]*inRight[0]);
							tempRight[0] -= (H12[1]*inLeft[1])+(H22[1]*inRight[1]);
							tempRight[1] += (H12[1]*inLeft[0])+(H22[1]*inRight[0]);
						}

						/* 存储最终采样 */
						if(gr<this.num_hybrid_groups) {
							X_hybrid_left[n][sb][0] = tempLeft[0];
							X_hybrid_left[n][sb][1] = tempLeft[1];
							X_hybrid_right[n][sb][0] = tempRight[0];
							X_hybrid_right[n][sb][1] = tempRight[1];
						}
						else {
							X_left[n][sb][0] = tempLeft[0];
							X_left[n][sb][1] = tempLeft[1];
							X_right[n][sb][0] = tempRight[0];
							X_right[n][sb][1] = tempRight[1];
						}
					}
				}

				/* 移动相位平滑器的环形缓冲区索引 */
				this.phase_hist++;
				if(this.phase_hist==2) {
					this.phase_hist = 0;
				}
			}
		}
	}

	/* 参数立体声（Parametric Stereo）主解码函数 */
	public int process(float[][][] X_left, float[][][] X_right) {
		float[][][] X_hybrid_left = new float[32][32][2];
		float[][][] X_hybrid_right = new float[32][32][2];

		/* 对比特流数据进行增量解码 */
		ps_data_decode();

		/* 根据滤波器组类型设置若干参数 */
		if(this.use34hybrid_bands) {
			this.group_border = group_border34;
			this.map_group2bk = map_group2bk34;
			this.num_groups = 32+18;
			this.num_hybrid_groups = 32;
			this.nr_par_bands = 34;
			this.decay_cutoff = 5;
		}
		else {
			this.group_border = group_border20;
			this.map_group2bk = map_group2bk20;
			this.num_groups = 10+12;
			this.num_hybrid_groups = 10;
			this.nr_par_bands = 20;
			this.decay_cutoff = 3;
		}

		/* 对最低子带做进一步分析，以获得更高的
		 * 频率分辨率
		 */
		hyb.hybrid_analysis(X_left, X_hybrid_left,
			this.use34hybrid_bands, this.numTimeSlotsRate);

		/* 对单声道信号去相关 */
		ps_decorrelate(X_left, X_right, X_hybrid_left, X_hybrid_right);

		/* 应用混合与相位参数 */
		ps_mix_phase(X_left, X_right, X_hybrid_left, X_hybrid_right);

		/* 混合综合，用于重建 SBR QMF 矩阵 */
		hyb.hybrid_synthesis(X_left, X_hybrid_left,
			this.use34hybrid_bands, this.numTimeSlotsRate);

		hyb.hybrid_synthesis(X_right, X_hybrid_right,
			this.use34hybrid_bands, this.numTimeSlotsRate);

		return 0;
	}

}
