/*
 * 版权所有1997 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.util.soundfile;

import java.io.IOException;

/**
 * 处理从 IFF 或 RIFF 文件解析出的 IFF 块。
 * 
 * @see IFFParser
 * @see AudioSampleAIFF
 * @author (C) 1997 Phil Burk, SoftSynth.com
 */
public interface ChunkHandler {
    /**
     * 当解析器遇到包含其他块的表单或列表块时，它将调用它。
     * 此处理程序可以读取表单的块，也可以让解析器找到它们并调用
     * handleChunk().
     * 
     * @param ID 一个 4 字节标识符，如 FORM_ID，用于标识 IFF 块类型。
     * @param numBytes FORM 中包含的字节数，不含 FORM 类型本身。
     * @param type 标识表单类型的4字节标识符，如AIFF_ID。
     */
    void handleForm(IFFParser parser, int ID, int numBytes, int type) throws IOException;

    /**
     * 当解析器遇到不是表单或列表的块时，它将调用它。此处理程序
     * 可以读取块，也可以忽略它。解析器将跳过任何未读数据。不要
     * 读到了块的末尾之外！
     * 
     * @param ID 标识IFF区块类型的4字节标识符，如SSND_ID。
     * @param numBytes 区块中包含的字节数，不包括ID和大小字段。
     */
    void handleChunk(IFFParser parser, int ID, int numBytes) throws IOException;
}
