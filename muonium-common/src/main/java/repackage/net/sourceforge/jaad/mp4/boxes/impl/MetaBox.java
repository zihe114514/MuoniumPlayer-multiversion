package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 可选的 iTunes/Apple 元数据容器。音频播放不消费
 * 其子级；仅需要外层 movie 的音频轨道表。
 */
public class MetaBox extends FullBox {

    public MetaBox() {
        super("Meta Box");
    }

    @Override
    public void decode(MP4InputStream in) throws IOException {
        /*
         * 某些有效的 M4A 文件含有旧版 JAAD 解析器无法安全遍历的
         * 厂商相关元数据子级。此前该可选 box 解析失败会使整个 M4A
         * 不可读，尽管 AAC 轨道表本身是有效的。
         * 以原子方式消费该元数据负载，使外层 movie 能继续解析其轨道 box。
         */
        in.skipBytes(getLeft(in));
    }
}