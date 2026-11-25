package repackage.com.jsyn.util.soundfile.streamed.buffered;

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.util.soundfile.CustomSampleLoader;

import java.io.BufferedInputStream;
import java.io.IOException;

/**
 */
public class BufferedSampleLoader extends CustomSampleLoader {

    public FloatSample loadFloatSample(BufferedInputStream is) throws IOException {
        BufferedIFFParser parser = new BufferedIFFParser(is);
        parser.readHead();
        if (parser.isRIFF()) {
            BufferedWAVEFileParser fileParser = new BufferedWAVEFileParser();
            return fileParser.load(parser);
        } else if (parser.isIFF()) {
            throw new UnsupportedOperationException("AIFF is not supported");
        } else {
            throw new IOException("Unsupported audio file type.");
        }
    }

}
