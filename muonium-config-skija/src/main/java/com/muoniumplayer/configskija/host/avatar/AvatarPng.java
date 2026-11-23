package com.muoniumplayer.configskija.host.avatar;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Worker-only CPU PNG handling. No disk temporary file, native image, Skija context or GL object. */
public final class AvatarPng {
    private AvatarPng() {}

    public static byte[] encode(AvatarPixels pixels) throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        try {
            image.setRGB(0, 0, 32, 32, pixels.headArgb(), 0, 32);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
            if (!writers.hasNext()) throw new IOException("PNG encoder is unavailable");
            ImageWriter writer = writers.next();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(image);
                output.flush();
                return bytes.toByteArray();
            } finally {
                writer.dispose();
            }
        } finally {
            image.flush();
        }
    }

    public static byte[] validate(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 8 || bytes.length > 1_048_576)
            throw new IOException("Invalid avatar PNG size");
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < 8; i++)
            if (bytes[i] != signature[i]) throw new IOException("Avatar is not PNG");
        try (MemoryCacheImageInputStream input =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unreadable avatar PNG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != 32 || reader.getHeight(0) != 32)
                    throw new IOException("Expected a 32x32 avatar");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Unreadable avatar pixels");
                image.flush();
                return bytes.clone();
            } finally {
                reader.dispose();
            }
        }
    }
}
