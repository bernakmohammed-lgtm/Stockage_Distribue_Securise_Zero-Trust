package com.stockage.common;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class StreamUtils {
    private StreamUtils() {
    }

    public static String readUtf8Line(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (baos.size() == 0) {
                    return null;
                }
                throw new EOFException("Unexpected EOF while reading line");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                baos.write(b);
            }
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    public static void writeUtf8Line(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    public static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        if (buf.length != n) {
            throw new EOFException("Expected " + n + " bytes, got " + buf.length);
        }
        return buf;
    }
}
