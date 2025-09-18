package org.kadampa.festivalstreaming;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.HashMap;
import java.util.Map;

public class LevelMeterPanel extends Stage implements LevelMeter.MonitorToggleListener {

    private final Map<String, LevelMeter> vuMeters = new HashMap<>();

    public LevelMeterPanel(ComboBox<String>[] inputAudioSources, ComboBox<String>[] inputAudioSourcesChannel, Map<String, String> languageColorsMap) {
        FlowPane flowPane = new FlowPane();
        flowPane.setPadding(new Insets(10));
        flowPane.setHgap(10);
        flowPane.setVgap(10);
        flowPane.setStyle("-fx-background-color: #222;");
        flowPane.setPrefWrapLength(1800);

        Scene scene = new Scene(flowPane);
        this.setScene(scene);
        this.setTitle("Level Meters");

        for (int i = 0; i < inputAudioSources.length; i++) {
            String language = Settings.LANGUAGES[i].name();
            String hexColor = languageColorsMap.getOrDefault(language, "#4E342E");
            LevelMeter vuMeter = new LevelMeter(language, getMixerInfo(inputAudioSources[i].getValue()), inputAudioSourcesChannel[i].getValue(), Color.web(hexColor), this);
            vuMeters.put(language, vuMeter);

            flowPane.getChildren().add(vuMeter.getView());

            boolean isUsed = !"Not Used".equals(inputAudioSources[i].getValue());
            vuMeter.getView().setVisible(isUsed);
            vuMeter.getView().setManaged(isUsed);

            inputAudioSources[i].valueProperty().addListener((observable, oldValue, newValue) -> {
                LevelMeter currentVuMeter = vuMeters.get(language);
                boolean isNowUsed = !"Not Used".equals(newValue);
                currentVuMeter.getView().setVisible(isNowUsed);
                currentVuMeter.getView().setManaged(isNowUsed);

                // Stop the meter, update its info, and then restart it only if the panel is showing.
                currentVuMeter.stop();
                currentVuMeter.setMixerInfo(getMixerInfo(newValue));
                if (isShowing() && isNowUsed) {
                    currentVuMeter.start();
                }
            });

            inputAudioSourcesChannel[i].valueProperty().addListener((observable, oldValue, newValue) -> {
                LevelMeter currentVuMeter = vuMeters.get(language);
                currentVuMeter.setChannel(newValue);
            });
        }

        this.setOnShowing(event -> startAllMeters());
        this.setOnHidden(event -> stopAllMeters());
        this.setOnCloseRequest(event -> stopAllMonitoring());
    }

    private void startAllMeters() {
        for (LevelMeter vuMeter : vuMeters.values()) {
            if (vuMeter.getView().isVisible()) {
                vuMeter.start();
            }
        }
    }

    private void stopAllMeters() {
        for (LevelMeter vuMeter : vuMeters.values()) {
            vuMeter.stop();
        }
    }

    @Override
    public void onMonitorRequest(LevelMeter source) {
        boolean wasMonitoring = source.isMonitoring();

        // First, turn off all other monitors
        for (LevelMeter meter : vuMeters.values()) {
            if (meter != source && meter.isMonitoring()) {
                meter.monitoringActiveProperty().set(false);
            }
        }

        // Then, toggle the source meter
        source.monitoringActiveProperty().set(!wasMonitoring);
    }

    private Mixer.Info getMixerInfo(String mixerName) {
        if (mixerName == null || "Not Used".equals(mixerName)) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (mixerName.equals(info.getName())) {
                return info;
            }
        }
        return null;
    }

    public void closeVUMeters() {
        stopAllMeters();
        this.close();
    }

    public void stopAllMonitoring() {
        for (LevelMeter vuMeter : vuMeters.values()) {
            vuMeter.stopAllMonitoring();
        }
    }

    public Map<String, LevelMeter> getVuMeters() {
        return vuMeters;
    }
}
