package org.groovymc.rootpackagetransformer.transform;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ConstantPoolRewriter {
    private final RootPackageTransformer transformer;

    public ConstantPoolRewriter(RootPackageTransformer transformer) {
        this.transformer = transformer;
    }

    private record Target(int offset, int length, byte[] newValue) {}

    @FunctionalInterface
    public interface Writer {
        OutputStream forClass(String name) throws IOException;
    }

    public void rewrite(InputStream is, Writer writer) throws IOException {
        byte[] bytes = is.readAllBytes();
        ClassReader reader = new ClassReader(bytes);
        List<Target> targets = new ArrayList<>();
        String name = transformer.apply(reader.getClassName());
        try (var os = writer.forClass(name)) {
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                byte type = bytes[offset - 1];
                if (type == 1) {
                    // CONSTANT_Utf8_info
                    int length = (bytes[offset] & 0xFF) << 8 | (bytes[offset + 1] & 0xFF);
                    String original = readUtf8(offset + 2, length, bytes);
                    String rewritten = transformer.apply(original);
                    if (!original.equals(rewritten)) {
                        byte[] newValue = writeUtf8(rewritten);
                        Target target = new Target(offset, length, newValue);
                        targets.add(target);
                    }
                }
            }
            int soFar = 0;
            for (Target target : targets) {
                os.write(bytes, soFar, target.offset - soFar);
                os.write(target.newValue.length >> 8);
                os.write(target.newValue.length & 0xFF);
                os.write(target.newValue);
                soFar = target.offset + target.length + 2;
            }
            os.write(bytes, soFar, bytes.length - soFar);
        }
    }

    private String readUtf8(int offset, int length, byte[] bytes) {
        int current = offset;
        int end = current + length;
        int totalLength = 0;
        char[] charBuffer = new char[length+2];
        while (current < end) {
            if (totalLength >= charBuffer.length) {
                char[] newBuffer = new char[charBuffer.length + charBuffer.length / 2];
                System.arraycopy(charBuffer, 0, newBuffer, 0, charBuffer.length);
                charBuffer = newBuffer;
            }
            int currentByte = bytes[current++];
            if ((currentByte & 0x80) == 0) {
                charBuffer[totalLength++] = (char) (currentByte & 0x7F);
            } else if ((currentByte & 0xE0) == 0xC0) {
                charBuffer[totalLength++] =
                        (char) (((currentByte & 0x1F) << 6) + (bytes[current++] & 0x3F));
            } else {
                charBuffer[totalLength++] =
                        (char) (((currentByte & 0xF) << 12)
                                + ((bytes[current++] & 0x3F) << 6)
                                + (bytes[current++] & 0x3F));
            }
        }
        return new String(charBuffer, 0, totalLength);
    }

    private byte[] writeUtf8(String value) {
        int length = value.length();
        byte[] bytes = new byte[length * 3];
        int current = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80 && c != 0) {
                bytes[current++] = (byte) c;
            } else if (c < 0x800) {
                bytes[current++] = (byte) (0b11000000 | (c >> 6));
                bytes[current++] = (byte) (0b10000000 | (c & 0b00111111));
            } else {
                bytes[current++] = (byte) (0b11100000 | (c >> 12));
                bytes[current++] = (byte) (0b10000000 | ((c >> 6) & 0b00111111));
                bytes[current++] = (byte) (0b10000000 | (c & 0b00111111));
            }
        }
        byte[] result = new byte[current];
        System.arraycopy(bytes, 0, result, 0, current);
        return result;
    }
}
