package org.kadampa.festivalstreaming;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SettingsUtil {
    private static final String SETTINGS_FILE = "settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Settings loadSettings() {
        try {
            if (Files.exists(Paths.get(SETTINGS_FILE))) {
                String json = new String(Files.readAllBytes(Paths.get(SETTINGS_FILE)));
                return GSON.fromJson(json, Settings.class);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new Settings();  // return default settings if file does not exist or an error occurred
    }

    public static void saveSettings(Settings settings) {
        try {
            String json = GSON.toJson(settings);
            Files.write(Paths.get(SETTINGS_FILE), json.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
