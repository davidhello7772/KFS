package org.kadampa.festivalstreaming;

import javafx.application.Platform;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VolumeMonitor {

    private final Map<String, LevelMeter> vuMeters;
    private ScheduledExecutorService scheduler;
    private static final double WARNING_THRESHOLD_DB = 10.0; // Hardcoded threshold
    private final Map<String, Long> lowVolumeStartTime = new HashMap<>(); // Tracks when low volume started
    private final Map<String, Long> highVolumeStartTime = new HashMap<>(); // Tracks when high volume started

    // Constants for language names to exclude
    private static final String PRAYERS_LANGUAGE = "Prayers";
    private static final String ENGLISH_FOR_MIX_LANGUAGE = "English (for mix)";
    private static final String ENGLISH_LANGUAGE = "English"; // The reference language

    public VolumeMonitor(Map<String, LevelMeter> vuMeters) {
        this.vuMeters = vuMeters;
    }

    public void startMonitoring() {
        if (scheduler != null && !scheduler.isShutdown()) {
            stopMonitoring();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::checkTranslationVolumes, 0, 1, TimeUnit.SECONDS); // Check every 1 second
    }

    public void stopMonitoring() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    private void checkTranslationVolumes() {
        LevelMeter englishMixMeter = vuMeters.get(ENGLISH_LANGUAGE); // Assuming "English" is the reference
        if (englishMixMeter == null) {
            // English mix meter not found, cannot perform comparison
            return;
        }

        double englishMixAverageDb = englishMixMeter.getAverageActualDb();

        for (Map.Entry<String, LevelMeter> entry : vuMeters.entrySet()) {
            String language = entry.getKey();
            LevelMeter meter = entry.getValue();

            // Skip Prayers, English (for mix), and the English reference itself
            if (PRAYERS_LANGUAGE.equals(language) || ENGLISH_FOR_MIX_LANGUAGE.equals(language) || ENGLISH_LANGUAGE.equals(language)) {
                continue;
            }

            double translationAverageDb = meter.getAverageActualDb();
            long currentTime = System.currentTimeMillis();

            if (translationAverageDb - englishMixAverageDb > WARNING_THRESHOLD_DB) {
                // High volume condition met
                if (!highVolumeStartTime.containsKey(language)) {
                    // First time this condition is met, record start time
                    highVolumeStartTime.put(language, currentTime);
                }

                // Check if condition has persisted for 6 seconds
                if (currentTime - highVolumeStartTime.get(language) >= 6_000) { // 6 seconds
                    meter.setWarningDisplay(true, "NOT MUTED?");
                }
            } else if (englishMixAverageDb - translationAverageDb > WARNING_THRESHOLD_DB) {
                // Low volume condition met
                if (!lowVolumeStartTime.containsKey(language)) {
                    // First time this condition is met, record start time
                    lowVolumeStartTime.put(language, currentTime);
                }

                // Check if condition has persisted for 6 seconds
                if (currentTime - lowVolumeStartTime.get(language) >= 6_000) { // 6 seconds
                    meter.setWarningDisplay(true, "LOW VOL!");
                }
            } else {
                // Volume is okay, clear any active warning and reset timer
                if (lowVolumeStartTime.containsKey(language)) {
                    lowVolumeStartTime.remove(language);
                    meter.setWarningDisplay(false, ""); // Clear warning immediately
                }
                if (highVolumeStartTime.containsKey(language)) {
                    highVolumeStartTime.remove(language);
                    meter.setWarningDisplay(false, ""); // Clear warning immediately
                }
            }
        }
    }
}
