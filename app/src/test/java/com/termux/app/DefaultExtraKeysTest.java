package com.termux.app;

import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DefaultExtraKeysTest {
    @Test public void keyboardToggleReplacesDashWithoutChangingTheOtherDefaultKeys() throws Exception {
        ExtraKeyButton[][] keys = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
                TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
        assertEquals(2, keys.length);
        String[][] expected = {{"ESC", "/", "KEYBOARD", "HOME", "UP", "END", "PGUP"},
                {"TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"}};
        for (int row = 0; row < expected.length; row++) {
            assertEquals(7, keys[row].length);
            for (int column = 0; column < expected[row].length; column++) {
                assertEquals(expected[row][column], keys[row][column].getKey());
            }
        }
        assertEquals("⌨", keys[0][2].getDisplay());
        assertFalse(keys[0][2].isMacro());
        assertNull(keys[0][2].getPopup());
    }

    @Test public void customDashAndPopupRemainAvailable() throws Exception {
        ExtraKeyButton[][] keys = new ExtraKeysInfo("[[{key:'-',popup:'|'}]]",
                TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
        assertEquals("-", keys[0][0].getKey());
        assertNotNull(keys[0][0].getPopup());
        assertEquals("|", keys[0][0].getPopup().getKey());
    }
}
