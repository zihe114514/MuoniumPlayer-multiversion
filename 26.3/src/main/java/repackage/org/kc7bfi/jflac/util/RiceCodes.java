/*
 * 创建于2004年6月24日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac.util;


/**
 * 用于处理大米代码的实用程序类。
 *
 * @author kc7bfi
 */
public class RiceCodes {

    /**
     * 从大米代码生成int。
     *
     * 写入一条 WAV 帧记录。
     * @param parameter 大米参数
     * @return 返回解码后的缓冲区数据
     */
    public int riceBits(int val, int parameter) {
        // 已签名到未签名的折叠
        int uval;
        if (val < 0) {
            // 这可以更快，但目前我们不需要
            uval = ((-(++val)) << 1) + 1;
        } else {
            uval = val << 1;
        }
        int msbs = uval >> parameter;
        return 1 + parameter + msbs;
    }
}
