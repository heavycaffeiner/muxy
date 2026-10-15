package com.termux.app;

import com.termux.shared.termux.settings.properties.LegacyImeProperties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class LegacyImePropertiesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void removesActiveAndCommentedKeysWithoutChangingOtherBytes() throws Exception {
        String preserved = "# Custom settings\r\nextra-keys = [['ESC','한']]\r\n";
        String text = preserved + "enforce-char-based-input = true\r\n"
                + "  # ctrl-space-workaround = false\r\n"
                + "! enforce-char-based-input=true\r\n"
                + "ctrl-space-workaround:false\r\n";
        assertEquals(preserved, LegacyImeProperties.removeKeys(text));
    }

    @Test public void removesEscapedKeysAndTheirContinuedValues() throws Exception {
        assertEquals("keep=value\n", LegacyImeProperties.removeKeys(
                "enforce\\u002dchar-based-input=tr\\\n  ue\n"
                        + "ctrl-space-workaround\\\n  =true\nkeep=value\n"));
    }

    @Test public void preservesSimilarKeysCommentsAndContinuedCustomValues() throws Exception {
        String text = "# Keep enforce-char-based-input in this note\n"
                + "enforce-char-based-input-extra=true\n"
                + "custom=one\\\n  ctrl-space-workaround=true\n"
                + "# ctrl-space-workaround-extra=true\nlast=value";
        assertEquals(text, LegacyImeProperties.removeKeys(text));
    }

    @Test public void preservesLineEndingsAndIsIdempotent() throws Exception {
        String text = "first=1\rsecond=2\nthird=3\r\nlast=4";
        assertEquals(text, LegacyImeProperties.removeKeys(text));
        assertEquals("", LegacyImeProperties.removeKeys("# enforce-char-based-input = true"));
        assertEquals("", LegacyImeProperties.removeKeys(""));
    }

    @Test public void atomicCleanupKeepsUnrelatedContent() throws Exception {
        File file = temporary.newFile("termux.properties");
        byte[] expected = "custom=\u00e9\r\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file.toPath(), ("enforce-char-based-input=true\r\ncustom=\u00e9\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        LegacyImeProperties.removeFromFile(file);
        assertArrayEquals(expected, Files.readAllBytes(file.toPath()));
        LegacyImeProperties.removeFromFile(file);
        assertArrayEquals(expected, Files.readAllBytes(file.toPath()));
    }

    @Test public void malformedPropertiesAreNotOverwritten() throws Exception {
        File file = temporary.newFile("invalid.properties");
        byte[] original = "enforce-char-based-input=true\nother=\\uZZZZ\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file.toPath(), original);
        try {
            LegacyImeProperties.removeFromFile(file);
            fail("Expected invalid properties to be rejected");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
    }
}
