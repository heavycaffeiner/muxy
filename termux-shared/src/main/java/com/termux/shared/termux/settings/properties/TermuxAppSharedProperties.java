package com.termux.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TermuxAppSharedProperties extends TermuxSharedProperties {

    private static TermuxAppSharedProperties properties;


    private TermuxAppSharedProperties(@NonNull Context context) {
        super(context, TermuxConstants.TERMUX_APP_NAME,
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST,
            new TermuxSharedProperties.SharedPropertiesParserClient());
    }

    @Override
    public synchronized void loadTermuxPropertiesFromDisk() {
        List<String> paths = new ArrayList<>(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST);
        paths.add(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/examples/termux/termux.properties");
        for (String path : paths) {
            try {
                LegacyImeProperties.removeFromFile(new File(path));
            } catch (IOException error) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Cannot remove obsolete IME settings from " + path, error);
                Logger.logErrorAndShowToast(mContext, LOG_TAG, "Could not update termux.properties");
            }
        }
        super.loadTermuxPropertiesFromDisk();
    }

    /**
     * Initialize the {@link #properties} and load properties from disk.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties init(@NonNull Context context) {
        if (properties == null)
            properties = new TermuxAppSharedProperties(context);

        return properties;
    }

    /**
     * Get the {@link #properties}.
     *
     * @return Returns the {@link TermuxAppSharedProperties}.
     */
    public static TermuxAppSharedProperties getProperties() {
        return properties;
    }

}
