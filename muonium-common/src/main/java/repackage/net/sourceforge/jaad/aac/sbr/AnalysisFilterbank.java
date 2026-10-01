package repackage.net.sourceforge.jaad.aac.sbr;

import java.util.Arrays;

class AnalysisFilterbank implements FilterbankTable {

	private float[] x; //x 以双精度环形缓冲区实现
	private int x_index; //环形缓冲区索引
	private int channels;

	AnalysisFilterbank(int channels) {
		this.channels = channels;
		x = new float[2*channels*10];
		x_index = 0;
	}

	public void reset() {
		Arrays.fill(x, 0);
	}

	void sbr_qmf_analysis_32(SBR sbr, float[] input,
		float[][][] X, int offset, int kx) {
		float[] u = new float[64];
		float[] in_real = new float[32], in_imag = new float[32];
		float[] out_real = new float[32], out_imag = new float[32];
		int in = 0;
		int l;

		/* qmf 子采样 l */
		for(l = 0; l<sbr.numTimeSlotsRate; l++) {
			int n;

			/* 移动输入缓冲区 x */
			/* 输入缓冲区不再移动，x 以双精度环形缓冲区实现 */
			/* 输入缓冲区不再移动，x 以双精度环形缓冲区实现 */

			/* 向输入缓冲区 x 添加新采样 */
			for(n = 32-1; n>=0; n--) {
				this.x[this.x_index+n] = this.x[this.x_index+n+320] = input[in++];
			}

			/* 加窗并求和以生成数组 u */
			for(n = 0; n<64; n++) {
				u[n] = (this.x[this.x_index+n]*qmf_c[2*n])
					+(this.x[this.x_index+n+64]*qmf_c[2*(n+64)])
					+(this.x[this.x_index+n+128]*qmf_c[2*(n+128)])
					+(this.x[this.x_index+n+192]*qmf_c[2*(n+192)])
					+(this.x[this.x_index+n+256]*qmf_c[2*(n+256)]);
			}

			/* 更新环形缓冲区索引 */
			this.x_index -= 32;
			if(this.x_index<0)
				this.x_index = (320-32);

			/* 通过引入 X 计算 32 个子带采样 */
			// 数据重排已从 DCT_IV 移到此处
			in_imag[31] = u[1];
			in_real[0] = u[0];
			for(n = 1; n<31; n++) {
				in_imag[31-n] = u[n+1];
				in_real[n] = -u[64-n];
			}
			in_imag[0] = u[32];
			in_real[31] = -u[33];

			// dct4_kernel 是不带重排的 DCT_IV，重排在 FFT 前后进行
			DCT.dct4_kernel(in_real, in_imag, out_real, out_imag);

			// 数据重排已从 DCT_IV 移到此处
			for(n = 0; n<16; n++) {
				if(2*n+1<kx) {
					X[l+offset][2*n][0] = 2.0f*out_real[n];
					X[l+offset][2*n][1] = 2.0f*out_imag[n];
					X[l+offset][2*n+1][0] = -2.0f*out_imag[31-n];
					X[l+offset][2*n+1][1] = -2.0f*out_real[31-n];
				}
				else {
					if(2*n<kx) {
						X[l+offset][2*n][0] = 2.0f*out_real[n];
						X[l+offset][2*n][1] = 2.0f*out_imag[n];
					}
					else {
						X[l+offset][2*n][0] = 0;
						X[l+offset][2*n][1] = 0;
					}
					X[l+offset][2*n+1][0] = 0;
					X[l+offset][2*n+1][1] = 0;
				}
			}
		}
	}
}
