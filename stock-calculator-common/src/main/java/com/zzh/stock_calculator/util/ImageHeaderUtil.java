package com.zzh.stock_calculator.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ImageHeaderUtil {

    public record Dimension(int width, int height) {}

    public static Dimension getImageDimension(byte[] bytes) throws IOException {
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            int b1 = is.read();
            int b2 = is.read();

            // 1. JPEG: FF D8
            if (b1 == 0xFF && b2 == 0xD8) {
                return getJpegDimension(is);
            }
            // 2. PNG: 89 50 4E 47
            if (b1 == 0x89 && b2 == 0x50 && is.read() == 0x4E && is.read() == 0x47) {
                is.skip(12); // 跳过到 IHDR 宽高数据区
                int width = readInt(is);
                int height = readInt(is);
                return new Dimension(width, height);
            }
            // 3. GIF: 47 49 46
            if (b1 == 0x47 && b2 == 0x49 && is.read() == 0x46) {
                is.skip(3); // 跳过版本号
                int width = readShortLE(is);
                int height = readShortLE(is);
                return new Dimension(width, height);
            }
            // 4. WebP: 52 49 46 46 ... 57 45 42 50
            if (b1 == 0x52 && b2 == 0x49 && is.read() == 0x46 && is.read() == 0x46) {
                is.skip(4); // 跳过 file size
                if (is.read() == 0x57 && is.read() == 0x45 && is.read() == 0x42 && is.read() == 0x50) {
                    return getWebPDimension(is);
                }
            }
        }
        return null;
    }

    private static Dimension getJpegDimension(InputStream is) throws IOException {
        while (true) {
            int marker = is.read();
            if (marker != 0xFF) return null;
            marker = is.read();
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) { // SOF0, SOF1, SOF2
                is.skip(3); // 跳过 length 和 precision
                int height = (is.read() << 8) | is.read();
                int width = (is.read() << 8) | is.read();
                return new Dimension(width, height);
            } else if (marker == 0xD9 || marker == -1) {
                break;
            } else {
                int length = (is.read() << 8) | is.read();
                is.skip(length - 2);
            }
        }
        return null;
    }

    private static Dimension getWebPDimension(InputStream is) throws IOException {
        byte[] chunkHeader = new byte[4];
        is.read(chunkHeader);
        String chunk = new String(chunkHeader);
        if ("VP8 ".equals(chunk)) {
            is.skip(10);
            int width = (readShortLE(is)) & 0x3FFF;
            int height = (readShortLE(is)) & 0x3FFF;
            return new Dimension(width, height);
        } else if ("VP8L".equals(chunk)) {
            is.skip(5);
            int b0 = is.read(), b1 = is.read(), b2 = is.read(), b3 = is.read();
            int width = 1 + (((b1 & 0x3F) << 8) | b0);
            int height = 1 + (((b3 & 0xF) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
            return new Dimension(width, height);
        } else if ("VP8X".equals(chunk)) {
            is.skip(8);
            int width = 1 + read24LE(is);
            int height = 1 + read24LE(is);
            return new Dimension(width, height);
        }
        return null;
    }

    private static int readInt(InputStream is) throws IOException {
        return (is.read() << 24) | (is.read() << 16) | (is.read() << 8) | is.read();
    }

    private static int readShortLE(InputStream is) throws IOException {
        return is.read() | (is.read() << 8);
    }

    private static int read24LE(InputStream is) throws IOException {
        return is.read() | (is.read() << 8) | (is.read() << 16);
    }
}
