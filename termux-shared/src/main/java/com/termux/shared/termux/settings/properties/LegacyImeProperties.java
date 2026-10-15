package com.termux.shared.termux.settings.properties;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Pattern;

public final class LegacyImeProperties {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern EXAMPLE = Pattern.compile(
            "[ \\t\\f]*[#!][ \\t\\f]*(?:enforce-char-based-input|ctrl-space-workaround)(?:[ \\t\\f:=].*)?");

    private LegacyImeProperties() {}

    public static String removeKeys(String text) throws IOException {
        StringBuilder result = new StringBuilder(text.length());
        int start = 0;
        while (start < text.length()) {
            int end = start;
            boolean continued;
            do {
                int lineStart = end;
                while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
                int slash = end;
                while (slash > lineStart && text.charAt(slash - 1) == '\\') slash--;
                continued = (end - slash) % 2 != 0;
                String line = text.substring(lineStart, end).trim();
                if (lineStart == start && (line.startsWith("#") || line.startsWith("!"))) continued = false;
                if (end < text.length() && text.charAt(end++) == '\r'
                        && end < text.length() && text.charAt(end) == '\n') end++;
            } while (continued && end < text.length());
            String entry = text.substring(start, end);
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(entry));
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid properties escape", error);
            }
            boolean legacy = properties.containsKey("enforce-char-based-input")
                    || properties.containsKey("ctrl-space-workaround")
                    || EXAMPLE.matcher(entry.replaceFirst("[\\r\\n]+$", "")).matches();
            if (!legacy) result.append(entry);
            start = end;
        }
        return result.toString();
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_BYTES - count) throw new IOException("Properties file exceeds 1 MiB");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static void removeFromFile(File path) throws IOException {
        File file = path.getCanonicalFile();
        if (!file.exists()) return;
        File temporary = null;
        try {
            StructStat stat = Os.stat(file.getPath());
            if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("Properties path is not a regular file");
            byte[] original = read(file);
            byte[] updated = removeKeys(new String(original, StandardCharsets.ISO_8859_1))
                    .getBytes(StandardCharsets.ISO_8859_1);
            if (Arrays.equals(original, updated)) return;
            temporary = File.createTempFile(".termux-properties-", ".tmp", file.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                Os.chmod(temporary.getPath(), stat.st_mode & 0777);
                output.write(updated);
                output.getFD().sync();
            }
            if (!Arrays.equals(original, read(file))) throw new IOException("Properties changed during cleanup");
            if (!temporary.renameTo(file)) throw new IOException("Cannot replace properties file");
            temporary = null;
        } catch (ErrnoException error) {
            throw new IOException("Cannot update properties file", error);
        } finally {
            if (temporary != null && !temporary.delete()) {
                throw new IOException("Cannot remove temporary properties file");
            }
        }
    }
}
