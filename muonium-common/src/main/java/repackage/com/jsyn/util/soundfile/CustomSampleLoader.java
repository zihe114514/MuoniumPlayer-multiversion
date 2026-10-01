/*
 * Copyright 2009 Phil Burk, Mobileer Inc
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

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.util.AudioSampleLoader;

import java.io.*;
import java.net.URL;

public class CustomSampleLoader implements AudioSampleLoader {

    @Override
    public FloatSample loadFloatSample(File fileIn) throws IOException {
        FileInputStream fileStream = new FileInputStream(fileIn);
        BufferedInputStream inputStream = new BufferedInputStream(fileStream);
        return loadFloatSample(inputStream);
    }

    @Override
    public FloatSample loadFloatSample(URL url) throws IOException {
        InputStream rawStream = url.openStream();
        BufferedInputStream inputStream = new BufferedInputStream(rawStream);
        return loadFloatSample(inputStream);
    }

    @Override
    public FloatSample loadFloatSample(InputStream inputStream) throws IOException {
        AudioFileParser fileParser;
        IFFParser parser = new IFFParser(inputStream);
        parser.readHead();
        if (parser.isRIFF()) {
            fileParser = new WAVEFileParser();
        } else if (parser.isIFF()) {
            fileParser = new AIFFFileParser();
        } else {
            throw new IOException("Unsupported audio file type.");
        }
        return fileParser.load(parser);
    }

}
