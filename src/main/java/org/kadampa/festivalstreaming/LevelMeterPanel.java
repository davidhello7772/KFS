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

public class LevelMeterPanel extends Stage {

    private final FlowPane flowPane;
    private final Map<String, LevelMeter> vuMeters = new HashMap<>();

    public LevelMeterPanel(ComboBox<String>[] inputAudioSources, ComboBox<String>[] inputAudioSourcesChannel, Map<String, String> languageColorsMap) {
        flowPane = new FlowPane();
        flowPane.setPadding(new Insets(10));
        flowPane.setHgap(10);
        flowPane.setVgap(10);
        flowPane.setStyle("-fx-background-color: #222;");
        // Set a preferred width to encourage horizontal layout
        flowPane.setPrefWrapLength(1800);


        Scene scene = new Scene(flowPane);
        this.setScene(scene);
        this.setTitle("Level Meters");

        for (int i = 0; i < inputAudioSources.length; i++) {
            final int index = i;
            String language = Settings.LANGUAGE_NAMES[i];
            String hexColor = languageColorsMap.getOrDefault(language, "#4E342E"); // Default color if not found
            LevelMeter vuMeter = new LevelMeter(language, getMixerInfo(inputAudioSources[i].getValue()), inputAudioSourcesChannel[i].getValue(), Color.web(hexColor));
            vuMeters.put(language, vuMeter);

            // Add all meters to the pane initially
            flowPane.getChildren().add(vuMeter.getView());

            // Control visibility and managed state
            boolean isUsed = !"Not Used".equals(inputAudioSources[i].getValue());
            vuMeter.getView().setVisible(isUsed);
            vuMeter.getView().setManaged(isUsed);
            if(isUsed) {
                vuMeter.start();
            }

            inputAudioSources[i].valueProperty().addListener((observable, oldValue, newValue) -> {
                LevelMeter currentVuMeter = vuMeters.get(language);
                boolean isNowUsed = !"Not Used".equals(newValue);

                currentVuMeter.getView().setVisible(isNowUsed);
                currentVuMeter.getView().setManaged(isNowUsed);

                if (isNowUsed) {
                    currentVuMeter.setMixerInfo(getMixerInfo(newValue));
                    currentVuMeter.start();
                } else {
                    currentVuMeter.stop();
                }
            });

            inputAudioSourcesChannel[i].valueProperty().addListener((observable, oldValue, newValue) -> {
                LevelMeter currentVuMeter = vuMeters.get(language);
                currentVuMeter.setChannel(newValue);
            });
        }
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
        for (LevelMeter vuMeter : vuMeters.values()) {
            vuMeter.stop();
        }
        this.close();
    }
}
