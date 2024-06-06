package org.kadampa.festivalstreaming;

import java.io.*;

public class SettingsUtil {
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
