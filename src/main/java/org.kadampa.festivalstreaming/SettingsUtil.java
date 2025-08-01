package org.kadampa.festivalstreaming;

import java.io.*;

public class SettingsUtil {
    public static final String AUDIO_CHANNEL_LEFT = "Left";
    public static final String AUDIO_CHANNEL_RIGHT = "Right";
    public static final String AUDIO_CHANNEL_JOIN = "Join";
    public static final String AUDIO_SOURCE_NOT_USED = "Not Used";
    public static final String AUDIO_SOURCE_NOT_SELECTED = "Not Selected";
    public static final String AUDIO_CHANNEL_STEREO = "Stereo";
    private static final String SETTINGS_FILE = "settings.ser";

    public static void saveSettings(Settings settings) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(SETTINGS_FILE))) {
            out.writeObject(settings);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Settings loadSettings() {
        File settingsFile = new File(SETTINGS_FILE);
        if (settingsFile.exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(settingsFile))) {
                return (Settings) in.readObject();
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return new Settings();
    }
}
