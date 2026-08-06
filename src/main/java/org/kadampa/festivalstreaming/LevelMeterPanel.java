package org.kadampa.festivalstreaming;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

public class LevelMeterPanel extends Stage implements LevelMeter.MonitorToggleListener {

    private static final int MIN_PERCENT = 50;
    private static final int MAX_PERCENT = 120;
    private static final int STEP_PERCENT = 5;

    private static final String STEPPER_BUTTON_STYLE =
            "-fx-background-color: #3a3a3a; -fx-text-fill: #ddd; -fx-background-radius: 4;"
            + " -fx-min-width: 26; -fx-min-height: 26; -fx-padding: 0 6 0 6; -fx-cursor: hand;";

    private final Map<String, LevelMeter> vuMeters = new HashMap<>();
    private final Settings settings;
    /** Kept for the device re-scan, which re-reads the selections without changing them. */
    private final ComboBox<String>[] inputAudioSources;

    // One shared timer drives every meter at ~30fps (every other JavaFX pulse):
    // twelve independent 60fps AnimationTimers doubled the GPU work for no visual gain
    private final AnimationTimer meterTicker = new AnimationTimer() {
        private long lastTick = 0;

        @Override
        public void handle(long now) {
            if (now - lastTick < 30_000_000L) {
                return;
            }
            lastTick = now;
            for (LevelMeter vuMeter : vuMeters.values()) {
                vuMeter.tick(now);
            }
        }
    };
    private final ScaleStepper widthStepper;
    private final ScaleStepper heightStepper;
    private final List<DbStepper> zoneSteppers = new ArrayList<>();
    private DbStepper greenStepper;
    private DbStepper yellowStepper;
    private DbStepper redStepper;
    private DbStepper enMixGreenStepper;
    private DbStepper enMixYellowStepper;
    private DbStepper enMixRedStepper;

    /** A discrete −/+ control stepping a scale factor between MIN_PERCENT and MAX_PERCENT. */
    private class ScaleStepper {
        final DoubleProperty factor = new SimpleDoubleProperty(1.0);
        private final Button minusButton = new Button("−");
        private final Button plusButton = new Button("+");
        private final Label percentLabel = new Label();
        private final HBox node;
        private int percent = 100;

        ScaleStepper(String captionText) {
            Label caption = new Label(captionText);
            caption.setStyle("-fx-text-fill: #888;");
            minusButton.setStyle(STEPPER_BUTTON_STYLE);
            plusButton.setStyle(STEPPER_BUTTON_STYLE);
            percentLabel.setMinWidth(42);
            percentLabel.setAlignment(Pos.CENTER);
            percentLabel.setStyle("-fx-text-fill: #ddd;");
            minusButton.setOnAction(e -> step(-STEP_PERCENT));
            plusButton.setOnAction(e -> step(STEP_PERCENT));
            node = new HBox(6, caption, minusButton, percentLabel, plusButton);
            node.setAlignment(Pos.CENTER_LEFT);
        }

        private void step(int deltaPercent) {
            apply(percent + deltaPercent);
            saveScales();
        }

        void apply(int newPercent) {
            percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, newPercent));
            factor.set(percent / 100.0);
            percentLabel.setText(percent + "%");
            minusButton.setDisable(percent <= MIN_PERCENT);
            plusButton.setDisable(percent >= MAX_PERCENT);
        }

        double factorValue() {
            return percent / 100.0;
        }
    }

    /**
     * A discrete −/+ control for a zone boundary in dB. Its allowed range is supplied
     * dynamically so neighbouring boundaries can never cross each other.
     */
    private class DbStepper {
        private final Button minusButton = new Button("−");
        private final Button plusButton = new Button("+");
        private final Label valueLabel = new Label();
        private IntSupplier minDb = () -> (int) LevelMeter.MIN_DB;
        private IntSupplier maxDb = () -> (int) LevelMeter.METER_CEILING_DB;
        final HBox node;
        private int valueDb;

        DbStepper(int initialDb, String tooltipText) {
            valueDb = initialDb;
            minusButton.setStyle(STEPPER_BUTTON_STYLE);
            plusButton.setStyle(STEPPER_BUTTON_STYLE);
            valueLabel.setMinWidth(34);
            valueLabel.setAlignment(Pos.CENTER);
            valueLabel.setStyle("-fx-text-fill: #ddd;");
            Tooltip tooltip = new Tooltip(tooltipText);
            Tooltip.install(valueLabel, tooltip);
            minusButton.setTooltip(tooltip);
            plusButton.setTooltip(tooltip);
            minusButton.setOnAction(e -> step(-1));
            plusButton.setOnAction(e -> step(1));
            node = new HBox(4, minusButton, valueLabel, plusButton);
            node.setAlignment(Pos.CENTER_LEFT);
            zoneSteppers.add(this);
        }

        void setRange(IntSupplier minDb, IntSupplier maxDb) {
            this.minDb = minDb;
            this.maxDb = maxDb;
        }

        private void step(int deltaDb) {
            valueDb = Math.max(minDb.getAsInt(), Math.min(maxDb.getAsInt(), valueDb + deltaDb));
            refreshAllZoneSteppers();
            saveAndApplyThresholds();
        }

        void sanitize() {
            valueDb = Math.max(minDb.getAsInt(), Math.min(maxDb.getAsInt(), valueDb));
        }

        void refresh() {
            valueLabel.setText(String.valueOf(valueDb));
            minusButton.setDisable(valueDb <= minDb.getAsInt());
            plusButton.setDisable(valueDb >= maxDb.getAsInt());
        }

        int value() {
            return valueDb;
        }
    }

    public LevelMeterPanel(ComboBox<String>[] inputAudioSources, ComboBox<String>[] inputAudioSourcesChannel, Settings settings) {
        this.settings = settings;
        this.inputAudioSources = inputAudioSources;
        this.widthStepper = new ScaleStepper("Width:");
        this.heightStepper = new ScaleStepper("Height:");

        FlowPane flowPane = new FlowPane();
        flowPane.setPadding(new Insets(10));
        flowPane.setHgap(10);
        flowPane.setVgap(10);
        flowPane.setStyle("-fx-background-color: #222;");
        flowPane.setPrefWrapLength(1800);
        // Let the pane shrink below its content height: if the meters don't fit the window,
        // the overflow must be clipped at the bottom rather than pushing the controls line off the top
        flowPane.setMinHeight(0);

        BorderPane root = new BorderPane(flowPane);
        HBox toolbar = buildToolbar();
        root.setTop(toolbar);

        // Floating chevron: collapses the controls line so the meters get the full window height
        Button toolbarToggle = new Button("▴");
        toolbarToggle.setStyle("-fx-background-color: rgba(58,58,58,0.85); -fx-text-fill: #aaa; -fx-background-radius: 4;"
                + " -fx-font-size: 10; -fx-min-height: 16; -fx-padding: 0 8 0 8; -fx-cursor: hand;");
        toolbarToggle.setTooltip(new Tooltip("Show / hide the controls line"));
        toolbarToggle.setOnAction(e -> {
            boolean show = !toolbar.isVisible();
            toolbar.setVisible(show);
            toolbar.setManaged(show);
            toolbarToggle.setText(show ? "▴" : "▾");
        });
        StackPane stack = new StackPane(root, toolbarToggle);
        StackPane.setAlignment(root, Pos.TOP_LEFT);
        StackPane.setAlignment(toolbarToggle, Pos.TOP_RIGHT);
        StackPane.setMargin(toolbarToggle, new Insets(4, 6, 0, 0));

        Scene scene = new Scene(stack);
        this.setScene(scene);
        this.setTitle("Level Meters");

        Map<String, String> languageColorsMap = settings.getLanguageColors();
        for (int i = 0; i < inputAudioSources.length; i++) {
            String language = Settings.LANGUAGES[i].name();
            String hexColor = languageColorsMap.getOrDefault(language, "#4E342E");
            LevelMeter vuMeter = new LevelMeter(language, getMixerInfo(inputAudioSources[i].getValue()), inputAudioSourcesChannel[i].getValue(), Color.web(hexColor), this, settings);
            vuMeters.put(language, vuMeter);

            // The Scale transform resizes the card; the Group wrapper makes the FlowPane
            // lay out using the transformed bounds (a node's own transform is ignored by layout)
            Scale scale = new Scale(1, 1, 0, 0);
            scale.xProperty().bind(widthStepper.factor);
            scale.yProperty().bind(heightStepper.factor);
            vuMeter.getView().getTransforms().add(scale);
            Group wrapper = new Group(vuMeter.getView());
            flowPane.getChildren().add(wrapper);

            boolean isUsed = !"Not Used".equals(inputAudioSources[i].getValue());
            wrapper.setVisible(isUsed);
            wrapper.setManaged(isUsed);
            vuMeter.getView().setVisible(isUsed);
            vuMeter.getView().setManaged(isUsed);

            inputAudioSources[i].valueProperty().addListener((observable, oldValue, newValue) -> {
                LevelMeter currentVuMeter = vuMeters.get(language);
                boolean isNowUsed = !"Not Used".equals(newValue);
                wrapper.setVisible(isNowUsed);
                wrapper.setManaged(isNowUsed);
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

        widthStepper.apply(snapToStep(settings.getLevelMeterWidthScale()));
        heightStepper.apply(snapToStep(settings.getLevelMeterHeightScale()));
        // Sanitize possibly hand-edited values (enforce green < yellow < red) and push them to the meters
        for (DbStepper stepper : zoneSteppers) {
            stepper.sanitize();
        }
        refreshAllZoneSteppers();
        applyThresholdsToMeters();

        this.setOnShowing(event -> {
            startAllMeters();
            meterTicker.start();
        });
        this.setOnHidden(event -> {
            meterTicker.stop();
            stopAllMeters();
        });
        this.setOnCloseRequest(event -> stopAllMonitoring());
        // Don't burn GPU on meter animation while the window is minimized;
        // audio listeners stay registered so volume warnings keep working
        this.iconifiedProperty().addListener((obs, wasIconified, iconified) -> {
            if (iconified) {
                meterTicker.stop();
                for (LevelMeter vuMeter : vuMeters.values()) {
                    vuMeter.resetAnimationClock();
                }
            } else {
                meterTicker.start();
            }
        });
    }

    private HBox buildToolbar() {
        greenStepper = new DbStepper((int) Math.round(settings.getMeterGreenThresholdDb()), "dB level where the green zone starts (grey below)");
        yellowStepper = new DbStepper((int) Math.round(settings.getMeterYellowThresholdDb()), "dB level where the orange zone starts");
        redStepper = new DbStepper((int) Math.round(settings.getMeterRedThresholdDb()), "dB level where the red zone starts");
        enMixGreenStepper = new DbStepper((int) Math.round(settings.getEnMixMeterGreenThresholdDb()), "dB level where the green zone starts (grey below)");
        enMixYellowStepper = new DbStepper((int) Math.round(settings.getEnMixMeterYellowThresholdDb()), "dB level where the orange zone starts");
        enMixRedStepper = new DbStepper((int) Math.round(settings.getEnMixMeterRedThresholdDb()), "dB level where the red zone starts");
        chainRanges(greenStepper, yellowStepper, redStepper);
        chainRanges(enMixGreenStepper, enMixYellowStepper, enMixRedStepper);

        HBox toolbar = new HBox(16,
                widthStepper.node, heightStepper.node,
                zoneRowSeparator(),
                buildZoneRow("All meters:", greenStepper, yellowStepper, redStepper),
                zoneRowSeparator(),
                buildZoneRow("English (for mix):", enMixGreenStepper, enMixYellowStepper, enMixRedStepper));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #222;");
        return toolbar;
    }

    private void chainRanges(DbStepper green, DbStepper yellow, DbStepper red) {
        green.setRange(() -> (int) LevelMeter.MIN_DB, () -> yellow.value() - 1);
        yellow.setRange(() -> green.value() + 1, () -> red.value() - 1);
        red.setRange(() -> yellow.value() + 1, () -> (int) LevelMeter.METER_CEILING_DB);
    }

    private Rectangle zoneRowSeparator() {
        Rectangle separator = new Rectangle(1, 22, Color.web("#4a4a4a"));
        return separator;
    }

    private HBox buildZoneRow(String captionText, DbStepper green, DbStepper yellow, DbStepper red) {
        Label caption = new Label(captionText);
        caption.setStyle("-fx-text-fill: #888;");
        HBox row = new HBox(8, caption,
                zoneChip(LevelMeter.COLOR_METER_BLUE), green.node,
                zoneChip(LevelMeter.COLOR_METER_GREEN), yellow.node,
                zoneChip(LevelMeter.COLOR_METER_YELLOW), red.node,
                zoneChip(LevelMeter.COLOR_METER_RED));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Rectangle zoneChip(Color color) {
        Rectangle chip = new Rectangle(18, 10, color);
        chip.setArcWidth(6);
        chip.setArcHeight(6);
        chip.setOpacity(0.85);
        return chip;
    }

    private void refreshAllZoneSteppers() {
        for (DbStepper stepper : zoneSteppers) {
            stepper.refresh();
        }
    }

    private void saveAndApplyThresholds() {
        settings.setMeterGreenThresholdDb(greenStepper.value());
        settings.setMeterYellowThresholdDb(yellowStepper.value());
        settings.setMeterRedThresholdDb(redStepper.value());
        settings.setEnMixMeterGreenThresholdDb(enMixGreenStepper.value());
        settings.setEnMixMeterYellowThresholdDb(enMixYellowStepper.value());
        settings.setEnMixMeterRedThresholdDb(enMixRedStepper.value());
        SettingsUtil.saveSettings(settings, "settings");
        applyThresholdsToMeters();
    }

    private void applyThresholdsToMeters() {
        for (Map.Entry<String, LevelMeter> entry : vuMeters.entrySet()) {
            if (Settings.ENGLISH_FOR_MIX_LANGUAGE.equals(entry.getKey())) {
                entry.getValue().setThresholds(enMixGreenStepper.value(), enMixYellowStepper.value(), enMixRedStepper.value());
            } else {
                entry.getValue().setThresholds(greenStepper.value(), yellowStepper.value(), redStepper.value());
            }
        }
    }

    private int snapToStep(double scale) {
        return (int) Math.round(scale * 100 / STEP_PERCENT) * STEP_PERCENT;
    }

    private void saveScales() {
        settings.setLevelMeterWidthScale(widthStepper.factorValue());
        settings.setLevelMeterHeightScale(heightStepper.factorValue());
        SettingsUtil.saveSettings(settings, "settings");
    }

    /**
     * Re-resolves every meter's device and restarts the ones in use: the settings tab's
     * device re-scan calls this after new hardware appears. The selections themselves have
     * not changed, so the value listeners stay silent — this does for every meter what a
     * selection change does for one.
     */
    public void restartMetersAfterDeviceRescan() {
        for (int i = 0; i < inputAudioSources.length; i++) {
            LevelMeter vuMeter = vuMeters.get(Settings.LANGUAGES[i].name());
            if (vuMeter == null) {
                continue;
            }
            String deviceName = inputAudioSources[i].getValue();
            vuMeter.stop();
            vuMeter.setMixerInfo(getMixerInfo(deviceName));
            if (isShowing() && !"Not Used".equals(deviceName)) {
                vuMeter.start();
            }
        }
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
            try {
                vuMeter.stop();
            } catch (RuntimeException e) {
                // One failing meter must not prevent the remaining device lines from closing
                System.err.println("Failed to stop a level meter: " + e.getMessage());
            }
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
        // One resolver for the whole application: it also insists on a capture line, so a
        // playback device sharing the name can no longer be picked up by mistake
        return AudioCaptureManager.findCaptureDevice(mixerName);
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
