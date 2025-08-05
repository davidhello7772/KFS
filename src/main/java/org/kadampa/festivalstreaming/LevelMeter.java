package org.kadampa.festivalstreaming;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.util.LinkedList;

public class LevelMeter {

    //<editor-fold desc="Color Constants">
    // UI Colors
    private static final Color COLOR_TEXT_PRIMARY = Color.WHITE;
    private static final Color COLOR_TEXT_SECONDARY = Color.rgb(255, 255, 255, 0.9);
    private static final Color COLOR_BORDER = Color.rgb(255, 255, 255, 0.2);
    private static final Color COLOR_BACKGROUND_DARK_TRANSPARENT = Color.rgb(0, 0, 0, 0.2);
    private static final Color COLOR_BACKGROUND_METER = Color.rgb(0, 0, 0, 0.3);
    private static final Color COLOR_SHADOW = Color.rgb(0, 0, 0, 0.4);
    private static final Color COLOR_SHADOW_GLOW = Color.rgb(255, 255, 255, 0.2);

    // Meter Bar Colors
    private static final Color COLOR_METER_BLUE = Color.web("#C0C0C0");
    private static final Color COLOR_METER_GREEN = Color.LIMEGREEN;
    private static final Color COLOR_METER_YELLOW = Color.ORANGE;
    public static final Color COLOR_METER_RED = Color.RED;
    private static final Color COLOR_METER_PEAK = Color.rgb(255, 100, 100);
    public static final Color COLOR_WARNING_HIGH = Color.rgb(255, 140, 0); // Bright Orange

    // Meter Background Colors
    private static final Color COLOR_METER_BACKGROUND_BLUE = Color.rgb(192, 192, 192, 0.3); // Light grey with transparency
    private static final Color COLOR_METER_BACKGROUND_GREEN = Color.rgb(0, 80, 0);
    private static final Color COLOR_METER_BACKGROUND_YELLOW = Color.rgb(80, 80, 0);
    private static final Color COLOR_METER_BACKGROUND_RED = Color.rgb(80, 0, 0);

    // Status Indicator Colors
    private static final Color COLOR_STATUS_INACTIVE = Color.rgb(100, 100, 100);
    private static final Color COLOR_STATUS_LOW = Color.web("#C0C0C0");
    private static final Color COLOR_STATUS_OK = Color.LIMEGREEN;
    private static final Color COLOR_STATUS_WARN = Color.ORANGE;
    private static final Color COLOR_STATUS_PEAK = Color.RED;
    private static final Color COLOR_STATUS_OFF = Color.GRAY;
    private static final Color COLOR_STATUS_STROKE = Color.rgb(255, 255, 255, 0.3);

    // Status Glow Colors
    private static final Color COLOR_GLOW_LOW = Color.web("#C0C0C0", 0.8);
    private static final Color COLOR_GLOW_OK = Color.rgb(50, 205, 50, 0.8);
    private static final Color COLOR_GLOW_WARN = Color.rgb(255, 165, 0, 0.8);
    private static final Color COLOR_GLOW_PEAK = Color.rgb(255, 0, 0, 0.8);
    private static final Color COLOR_GLOW_OFF = Color.rgb(100, 100, 100, 0.8);
    private static final Color COLOR_SCALE_MARK = Color.rgb(255, 255, 255, 0.4);
    private static final Color COLOR_0DB_TICK = Color.rgb(255, 0, 0, 0.7);
    //</editor-fold>

    public interface MonitorToggleListener {
        void onMonitorRequest(LevelMeter source);
    }

    private final VBox view;
    private Label dbLabel;
    private Label audioInterfaceLabel;
    private Circle statusIndicator;
    private DropShadow statusGlow;
    private Mixer.Info mixerInfo;
    private volatile boolean running = false;
    private String channel;
    private final String language;
    private AudioCaptureManager.AudioDataListener audioDataListener;

    private Rectangle blueBar, greenBar, yellowBar, redBar, peakHoldBar;
    private Rectangle blueBackground, greenBackground, yellowBackground, redBackground;
    private AnimationTimer animationTimer;
    private PauseTransition peakHoldTimer;

    //<editor-fold desc="Meter Constants">
    private static final double METER_CEILING_DB = 15.0;
    private static final double MIN_DB = -40.0;
    private final double greenThresholdDb;
    private final double yellowThresholdDb;
    private final double redThresholdDb;
    //</editor-fold>

    private static final double METER_HEIGHT = 300;
    private static final double METER_WIDTH = 30;

    private volatile double currentDb = MIN_DB;
    private volatile double actualCurrentDb = MIN_DB;
    private double actualDisplayDb = MIN_DB;

    private final LinkedList<Double> volumeHistory = new LinkedList<>();
    private static final int VOLUME_HISTORY_SIZE = 100; // Roughly 10 seconds at 10 updates/sec

    private double displayDb = MIN_DB;
    private double peakDb = MIN_DB;
    private final double DECAY_RATE_DB_PER_SEC = 20.0;

    private boolean peakFlashActive = false;
    private final PauseTransition peakFlashTimer = new PauseTransition(Duration.seconds(1));
    private final Color originalBackgroundColor;
    private final MonitorToggleListener monitorToggleListener;

    private long redPeakTimestamp = 0;
    private long yellowPeakTimestamp = 0;
    private long greenPeakTimestamp = 0;

    private Button monitorButton;
    private final BooleanProperty monitoringActive = new SimpleBooleanProperty(false);

    public LevelMeter(String language, Mixer.Info mixerInfo, String channel, Color backgroundColor, MonitorToggleListener listener) {
        this.language = language;
        this.mixerInfo = mixerInfo;
        this.channel = channel;
        this.originalBackgroundColor = backgroundColor;
        this.monitorToggleListener = listener;

        if ("English (for mix)".equals(language)) {
            greenThresholdDb = -28.0;
            yellowThresholdDb = -20.0;
            redThresholdDb = 12.0;
        } else {
            greenThresholdDb = -9.0;
            yellowThresholdDb = 6.0;
            redThresholdDb = 12.0;
        }

        this.view = new VBox();
        this.view.setAlignment(Pos.CENTER);

        VBox headerBox = createHeader(language);
        HBox meterContainer = createVerticalMeterWithGraduations();
        VBox infoBox = createInfoSection();

        view.setSpacing(0);
        view.setPadding(new Insets(16));
        view.setAlignment(Pos.CENTER);
        view.setPrefWidth(280);
        view.setMinHeight(METER_HEIGHT + 120);
        view.getChildren().addAll(headerBox, meterContainer, infoBox);

        setupBindings();

        DropShadow dropShadow = new DropShadow();
        dropShadow.setRadius(16);
        dropShadow.setOffsetX(0);
        dropShadow.setOffsetY(8);
        dropShadow.setColor(COLOR_BACKGROUND_DARK_TRANSPARENT);
        view.setEffect(dropShadow);

        setupAnimation();
        peakFlashTimer.setOnFinished(e -> peakFlashActive = false);
    }

    private void setupBindings() {
        monitoringActive.addListener((obs, oldVal, newVal) -> {
            updateBackgroundStyle(newVal ? originalBackgroundColor.brighter() : originalBackgroundColor);
            updateMonitorButtonStyle();
            if (newVal) {
                AudioCaptureManager.getInstance().startMonitoring(mixerInfo, channel);
            } else {
                AudioCaptureManager.getInstance().stopMonitoring(mixerInfo);
            }
        });

        // Initial state
        updateBackgroundStyle(originalBackgroundColor);
        updateMonitorButtonStyle();
    }

    private VBox createHeader(String language) {
        VBox headerBox = new VBox();
        headerBox.setAlignment(Pos.CENTER);
        headerBox.setSpacing(8);
        headerBox.setPadding(new Insets(0, 0, 20, 0));

        HBox titleRow = new HBox();
        titleRow.setAlignment(Pos.CENTER);
        titleRow.setSpacing(15);

        VBox titleBox = new VBox();
        titleBox.setAlignment(Pos.CENTER);
        titleBox.setSpacing(2);

        Label languageLabel = new Label(language.toUpperCase());
        languageLabel.setFont(Font.font("System", FontWeight.BOLD, 20));
        languageLabel.setTextFill(COLOR_TEXT_PRIMARY);
        languageLabel.setAlignment(Pos.CENTER);
        addTextShadow(languageLabel);

        audioInterfaceLabel = new Label();
        audioInterfaceLabel.setFont(Font.font("System", FontWeight.NORMAL, 13));
        audioInterfaceLabel.setTextFill(COLOR_TEXT_SECONDARY);
        audioInterfaceLabel.setWrapText(true);
        audioInterfaceLabel.setAlignment(Pos.CENTER);
        audioInterfaceLabel.setTextAlignment(TextAlignment.CENTER);
        audioInterfaceLabel.setPrefWidth(200);
        audioInterfaceLabel.setMinHeight(32);
        addTextShadow(audioInterfaceLabel);

        updateAudioInterfaceLabel();

        statusIndicator = new Circle(7);
        statusGlow = new DropShadow();
        statusIndicator.setEffect(statusGlow);
        updateStatusIndicator();

        titleRow.getChildren().addAll(languageLabel, statusIndicator);
        titleBox.getChildren().addAll(titleRow, audioInterfaceLabel);

        headerBox.getChildren().addAll(titleBox);

        return headerBox;
    }

    private void updateMonitorButtonStyle() {
        monitorButton.setFont(Font.font("System", FontWeight.BOLD, 14)); // Increased size for better icon visibility
        monitorButton.setText("🎧"); // Headphone icon for both states

        if (monitoringActive.get()) {
            // Style for active monitoring (red)
            monitorButton.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #ef4444, #dc2626);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: #fca5a5;" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 8;" +
                "-fx-text-fill: white;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 4, 0, 0, 2);"
            );
        } else {
            // Style for inactive monitoring (dark)
            monitorButton.setStyle(
                "-fx-background-color: rgba(0, 0, 0, 0.2);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255, 255, 255, 0.2);" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 8;" +
                "-fx-text-fill: white;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 4, 0, 0, 2);"
            );
        }
    }

    private VBox createInfoSection() {
        VBox infoBox = new VBox();
        infoBox.setAlignment(Pos.CENTER);
        infoBox.setSpacing(12);
        infoBox.setPadding(new Insets(20, 0, 0, 0));

        dbLabel = new Label(String.format("%.1f dB", MIN_DB));
        dbLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        dbLabel.setTextFill(COLOR_TEXT_PRIMARY);
        dbLabel.setAlignment(Pos.CENTER);
        dbLabel.setMinWidth(150);
        dbLabel.setStyle("-fx-background-color: " + toRgbaString(COLOR_BACKGROUND_DARK_TRANSPARENT) + "; -fx-background-radius: 25; -fx-padding: 8 40; -fx-border-color: " + toRgbaString(COLOR_BORDER) + "; -fx-border-width: 1; -fx-border-radius: 25;");
        addTextShadow(dbLabel);

        monitorButton = new Button();
        monitorButton.setPrefSize(35, 35);
        monitorButton.setOnAction(e -> {
            if (monitorToggleListener != null) {
                monitorToggleListener.onMonitorRequest(this);
            }
        });

        HBox bottomRow = new HBox();
        bottomRow.setAlignment(Pos.CENTER);
        bottomRow.setSpacing(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bottomRow.getChildren().addAll(dbLabel, spacer, monitorButton);

        infoBox.getChildren().addAll(bottomRow);

        return infoBox;
    }

    public BooleanProperty monitoringActiveProperty() {
        return monitoringActive;
    }

    public boolean isMonitoring() {
        return monitoringActive.get();
    }

    private void updateBackgroundStyle(Color color) {
        String gradientStyle = createGradientStyle(color);
        view.setStyle(gradientStyle +
            "-fx-background-radius: 16; " +
            "-fx-border-color: " + toRgbaString(COLOR_BORDER) + "; " +
            "-fx-border-width: 2; " +
            "-fx-border-radius: 16;");
    }

    private void updateStatusIndicator() {
        statusIndicator.setFill(COLOR_STATUS_INACTIVE);
        statusIndicator.setStroke(COLOR_STATUS_STROKE);
        statusIndicator.setStrokeWidth(2);
        statusGlow.setRadius(10);
        statusGlow.setColor(COLOR_GLOW_OK);
    }

    private void addTextShadow(Label label) {
        DropShadow textShadow = new DropShadow();
        textShadow.setRadius(2);
        textShadow.setOffsetX(0);
        textShadow.setOffsetY(2);
        textShadow.setColor(COLOR_SHADOW);
        label.setEffect(textShadow);
    }

    private String createGradientStyle(Color color) {
        Color darkerColor = color.darker();
        return String.format("-fx-background-color: linear-gradient(to bottom, #%s, #%s); ",
            darkerColor.toString().substring(2),
            color.toString().substring(2));
    }

    private HBox createVerticalMeterWithGraduations() {
        HBox container = new HBox(5);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(0, 0, 0, 30));
        Pane meterPane = createVerticalMeter();
        VBox graduationLabels = createGraduationLabels();
        container.getChildren().addAll(meterPane, graduationLabels);
        return container;
    }

    private VBox createGraduationLabels() {
        Pane graduationPane = new Pane();
        graduationPane.setPrefHeight(METER_HEIGHT);
        graduationPane.setMinWidth(35);

        double[] labelDbValues = {-37, -30, -20, -10, -3, 0, 6, 12};
        if ("English (for mix)".equals(language)) {
            labelDbValues = new double[]{-37, -30, -24, -20, -10, -3, 0, 6, 12};
        }
        double totalRange = METER_CEILING_DB - MIN_DB;

        for (double dbValue : labelDbValues) {
            double y = METER_HEIGHT * (1 - (dbValue - MIN_DB) / totalRange);
            Label label = new Label((dbValue >= 0 ? "+" : "") + String.format("%.0f", dbValue));
            if (dbValue == 0 || ("English (for mix)".equals(language) && dbValue == -24)) {
                label.setFont(Font.font("Verdana", FontWeight.BOLD, 12));
            } else {
                label.setFont(Font.font("Verdana", FontWeight.NORMAL, 12));
            }
            label.setTextFill(COLOR_TEXT_PRIMARY);
            label.setLayoutY(y - 8);
            addTextShadow(label);

            graduationPane.getChildren().add(label);
        }

        VBox wrapper = new VBox(graduationPane);
        wrapper.setAlignment(Pos.TOP_LEFT);
        return wrapper;
    }

    private Pane createVerticalMeter() {
        Pane meterPane = new Pane();
        meterPane.setPrefSize(METER_WIDTH, METER_HEIGHT);

        Rectangle clipRect = new Rectangle(METER_WIDTH, METER_HEIGHT);
        clipRect.setArcWidth(30);
        clipRect.setArcHeight(30);
        meterPane.setClip(clipRect);

        Rectangle meterBackground = new Rectangle(METER_WIDTH, METER_HEIGHT, COLOR_BACKGROUND_METER);
        meterBackground.setStroke(COLOR_BORDER);
        meterBackground.setStrokeWidth(1);
        meterBackground.setArcWidth(30);
        meterBackground.setArcHeight(30);

        DropShadow innerShadow = new DropShadow();
        innerShadow.setRadius(8);
        innerShadow.setOffsetX(0);
        innerShadow.setOffsetY(2);
        innerShadow.setColor(COLOR_BACKGROUND_METER);
        meterBackground.setEffect(innerShadow);

        blueBackground = new Rectangle(METER_WIDTH, 0, COLOR_METER_BACKGROUND_BLUE);
        greenBackground = new Rectangle(METER_WIDTH, 0, COLOR_METER_BACKGROUND_GREEN);
        yellowBackground = new Rectangle(METER_WIDTH, 0, COLOR_METER_BACKGROUND_YELLOW);
        redBackground = new Rectangle(METER_WIDTH, 0, COLOR_METER_BACKGROUND_RED);
        blueBar = new Rectangle(METER_WIDTH, 0, COLOR_METER_BLUE);
        greenBar = new Rectangle(METER_WIDTH, 0, COLOR_METER_GREEN);
        yellowBar = new Rectangle(METER_WIDTH, 0, COLOR_METER_YELLOW);
        redBar = new Rectangle(METER_WIDTH, 0, COLOR_METER_RED);
        peakHoldBar = new Rectangle(METER_WIDTH, 2, COLOR_METER_PEAK);
        peakHoldBar.setVisible(false);

        DropShadow glow = new DropShadow();
        glow.setRadius(20);
        glow.setColor(COLOR_SHADOW_GLOW);
        blueBar.setEffect(glow);
        greenBar.setEffect(glow);
        yellowBar.setEffect(glow);
        redBar.setEffect(glow);

        meterPane.getChildren().addAll(meterBackground, blueBackground, greenBackground, yellowBackground, redBackground, blueBar, greenBar, yellowBar, redBar);

        // Add graduation ticks inside the meter
        double[] labelDbValues = {-37, -30, -20, -10, -3, 0, 6, 12};
        if ("English (for mix)".equals(language)) {
            labelDbValues = new double[]{-37, -30, -24, -20, -10, -3, 0, 6, 12};
        }
        double totalRange = METER_CEILING_DB - MIN_DB;

        for (double dbValue : labelDbValues) {
            double y = METER_HEIGHT * (1 - (dbValue - MIN_DB) / totalRange);
            Rectangle tick;
            if (dbValue == 0 || ("English (for mix)".equals(language) && dbValue == -24)) {
                tick = new Rectangle(METER_WIDTH, 2); // Full width for 0dB and English mix -24dB
                tick.setFill(COLOR_0DB_TICK);
                tick.setLayoutX(0); // Position at the start of the meter
            } else {
                tick = new Rectangle(METER_WIDTH / 4, 2); // Original width for others
                tick.setFill(COLOR_SCALE_MARK);
                tick.setLayoutX(METER_WIDTH - (METER_WIDTH / 4)); // Original position for others
            }
            tick.setLayoutY(y - 1);
            meterPane.getChildren().add(tick);
        }

        meterPane.getChildren().add(peakHoldBar);
        return meterPane;
    }

    private void setupAnimation() {
        peakHoldTimer = new PauseTransition(Duration.seconds(1.5));
        peakHoldTimer.setOnFinished(e -> {
            peakDb = MIN_DB;
            peakHoldBar.setVisible(false);
        });

        animationTimer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                if (lastUpdate == 0) {
                    lastUpdate = now;
                    return;
                }
                double elapsedSeconds = (now - lastUpdate) / 1_000_000_000.0;
                lastUpdate = now;

                if (currentDb < displayDb) {
                    displayDb -= DECAY_RATE_DB_PER_SEC * elapsedSeconds;
                    displayDb = Math.max(displayDb, currentDb);
                } else {
                    displayDb = currentDb;
                }

                if (actualCurrentDb < actualDisplayDb) {
                    actualDisplayDb -= DECAY_RATE_DB_PER_SEC * elapsedSeconds;
                    actualDisplayDb = Math.max(actualDisplayDb, actualCurrentDb);
                } else {
                    actualDisplayDb = actualCurrentDb;
                }

                if (displayDb > peakDb) {
                    peakDb = displayDb;
                    peakHoldTimer.playFromStart();
                }

                // Trigger peak flash if level is close to ceiling
                if (displayDb >= METER_CEILING_DB - 0.5) {
                    if (!peakFlashActive) {
                        peakFlashActive = true;
                        peakFlashTimer.playFromStart();
                    }
                }

                if (displayDb > redThresholdDb) {
                    redPeakTimestamp = now;
                } else if (displayDb > yellowThresholdDb) {
                    yellowPeakTimestamp = now;
                } else if (displayDb > greenThresholdDb) {
                    greenPeakTimestamp = now;
                }

                updateStatusIndicator(now);
                drawMeter();
            }
        };
    }

    private void updateStatusIndicator(long now) {
        Color statusColor;
        Color glowColor;
        if (peakFlashActive) {
            statusColor = COLOR_STATUS_PEAK;
            glowColor = COLOR_GLOW_PEAK;
        } else if (now - redPeakTimestamp < 1_000_000_000L) {
            statusColor = COLOR_STATUS_PEAK;
            glowColor = COLOR_GLOW_PEAK;
        } else if (now - yellowPeakTimestamp < 1_000_000_000L) {
            statusColor = COLOR_STATUS_WARN;
            glowColor = COLOR_GLOW_WARN;
        } else if (now - greenPeakTimestamp < 1_000_000_000L) {
            statusColor = COLOR_STATUS_OK;
            glowColor = COLOR_GLOW_OK;
        } else {
            if (displayDb > redThresholdDb) {
                statusColor = COLOR_STATUS_PEAK;
                glowColor = COLOR_GLOW_PEAK;
            } else if (displayDb > yellowThresholdDb) {
                statusColor = COLOR_STATUS_WARN;
                glowColor = COLOR_GLOW_WARN;
            } else if (displayDb > greenThresholdDb) {
                statusColor = COLOR_STATUS_OK;
                glowColor = COLOR_GLOW_OK;
            } else {
                statusColor = COLOR_STATUS_LOW;
                glowColor = COLOR_GLOW_LOW;
                if (displayDb <= MIN_DB) {
                    statusColor = COLOR_STATUS_OFF;
                    glowColor = COLOR_GLOW_OFF;
                }
            }
        }
        statusIndicator.setFill(statusColor);
        statusGlow.setColor(glowColor);
    }

    private void drawMeter() {
        double totalRange = METER_CEILING_DB - MIN_DB;
        double dbClamped = Math.max(MIN_DB, Math.min(METER_CEILING_DB, displayDb));

        double greenThresholdY = METER_HEIGHT * (1 - (greenThresholdDb - MIN_DB) / totalRange);
        double yellowThresholdY = METER_HEIGHT * (1 - (yellowThresholdDb - MIN_DB) / totalRange);
        double redThresholdY = METER_HEIGHT * (1 - (redThresholdDb - MIN_DB) / totalRange);
        double currentLevelY = METER_HEIGHT * (1 - (dbClamped - MIN_DB) / totalRange);

        // Backgrounds
        redBackground.setY(0);
        redBackground.setHeight(redThresholdY);
        yellowBackground.setY(redThresholdY);
        yellowBackground.setHeight(yellowThresholdY - redThresholdY);
        greenBackground.setY(yellowThresholdY);
        greenBackground.setHeight(greenThresholdY - yellowThresholdY);
        blueBackground.setY(greenThresholdY);
        blueBackground.setHeight(METER_HEIGHT - greenThresholdY);

        // Bars
        double redHeight = (dbClamped > redThresholdDb) ? redThresholdY - currentLevelY : 0;
        double yellowHeight = (dbClamped > yellowThresholdDb) ? (dbClamped > redThresholdDb ? yellowThresholdY - redThresholdY : yellowThresholdY - currentLevelY) : 0;
        double greenHeight = (dbClamped > greenThresholdDb) ? (dbClamped > yellowThresholdDb ? greenThresholdY - yellowThresholdY : greenThresholdY - currentLevelY) : 0;
        double blueHeight = (dbClamped > greenThresholdDb) ? METER_HEIGHT - greenThresholdY : METER_HEIGHT - currentLevelY;

        if (peakFlashActive) {
            redBar.setY(0);
            redBar.setHeight(METER_HEIGHT);
            greenBar.setHeight(0);
            yellowBar.setHeight(0);
            blueBar.setHeight(0);
        } else {
            blueBar.setY(METER_HEIGHT - blueHeight);
            blueBar.setHeight(blueHeight);
            greenBar.setY(METER_HEIGHT - blueHeight - greenHeight);
            greenBar.setHeight(greenHeight);
            yellowBar.setY(METER_HEIGHT - blueHeight - greenHeight - yellowHeight);
            yellowBar.setHeight(yellowHeight);
            redBar.setY(METER_HEIGHT - blueHeight - greenHeight - yellowHeight - redHeight);
            redBar.setHeight(redHeight);
        }

        if (peakDb > MIN_DB) {
            double peakY = METER_HEIGHT * (1 - (peakDb - MIN_DB) / totalRange);
            peakHoldBar.setY(peakY - 1);
            peakHoldBar.setFill(peakDb > redThresholdDb ? COLOR_METER_RED : (peakDb > yellowThresholdDb ? COLOR_METER_YELLOW : (peakDb > greenThresholdDb ? COLOR_METER_GREEN : COLOR_METER_BLUE)));
            peakHoldBar.setVisible(true);
        } else {
            peakHoldBar.setVisible(false);
        }

        dbLabel.setText(String.format("%.1f dB", actualDisplayDb));
    }

    public VBox getView() {
        return view;
    }

    private void updateAudioInterfaceLabel() {
        String text = mixerInfo != null ? mixerInfo.getName() : SettingsUtil.AUDIO_SOURCE_NOT_SELECTED;
        String channelDisplay = (channel == null || channel.isEmpty()) ? SettingsUtil.AUDIO_CHANNEL_JOIN : channel;
        text += SettingsUtil.AUDIO_CHANNEL_JOIN.equalsIgnoreCase(channelDisplay) ? " - " + SettingsUtil.AUDIO_CHANNEL_STEREO : " - " + channelDisplay;
        audioInterfaceLabel.setText(text);
    }

    public void setWarningDisplay(boolean showWarning, String message, Color warningColor) {
        Platform.runLater(() -> {
            // First, stop any existing animations on the label to prevent conflicts
            Object existingAnimation = audioInterfaceLabel.getProperties().get("warningAnimation");
            if (existingAnimation instanceof javafx.animation.FadeTransition) {
                ((javafx.animation.FadeTransition) existingAnimation).stop();
                audioInterfaceLabel.getProperties().remove("warningAnimation");
            }
            // Reset opacity and other properties to a clean state
            audioInterfaceLabel.setOpacity(1.0);

            if (showWarning) {
                // Create modern warning badge styling
                String warningMessage = message.toUpperCase();
                audioInterfaceLabel.setText(warningMessage);

                // Use provided color or default to bright warning red
                Color color = (warningColor != null) ? warningColor : Color.web("#FF4444");

                // Create modern pill/badge background with gradient
                Color gradientStart = color.brighter().brighter();

                // Apply modern warning badge styling
                audioInterfaceLabel.setStyle(
                    String.format("-fx-background-color: linear-gradient(to bottom, %s, %s);",
                        toRgbaString(gradientStart),
                        toRgbaString(color)) +
                        "-fx-border-color: " + toRgbaString(color.brighter()) + ";" +
                        "-fx-border-width: 1.5;" +
                        "-fx-effect: dropshadow(gaussian, " + toRgbaString(Color.rgb(0, 0, 0, 0.4)) + ", 4, 0, 0, 2);"
                );

                // Make text white and bold for maximum visibility
                audioInterfaceLabel.setTextFill(Color.WHITE);
                audioInterfaceLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

                // Create a subtle pulsing effect for the new warning
                javafx.animation.FadeTransition pulseAnimation = new javafx.animation.FadeTransition(Duration.seconds(0.8), audioInterfaceLabel);
                pulseAnimation.setFromValue(1.0);
                pulseAnimation.setToValue(0.6);
                pulseAnimation.setCycleCount(javafx.animation.FadeTransition.INDEFINITE);
                pulseAnimation.setAutoReverse(true);
                pulseAnimation.play();

                // Store the new animation reference
                audioInterfaceLabel.getProperties().put("warningAnimation", pulseAnimation);

                // Update the overall card styling for the warning state
                view.setStyle(createGradientStyle(color) +
                    "-fx-border-color: " + toRgbaString(color.brighter()) + "; " +
                    "-fx-border-width: 2; ");

            } else {
                // Revert to original label and styling
                updateAudioInterfaceLabel(); // Revert to original label
                updateBackgroundStyle(originalBackgroundColor); // Revert background

                // Reset label styling to original
                audioInterfaceLabel.setStyle(""); // Clear custom styling
                audioInterfaceLabel.setTextFill(COLOR_TEXT_SECONDARY); // Revert text color
                audioInterfaceLabel.setFont(Font.font("System", FontWeight.NORMAL, 13)); // Revert font
            }
        });
    }

    public void setMixerInfo(Mixer.Info mixerInfo) {
        stop();
        this.mixerInfo = mixerInfo;
        updateAudioInterfaceLabel();
        if (mixerInfo == null) {
            currentDb = MIN_DB;
        }
    }

    public void setChannel(String channel) {
        this.channel = channel;
        updateAudioInterfaceLabel();
    }

    public void stopAllMonitoring() {
        monitoringActive.set(false);
    }

    void start() {
        if (mixerInfo == null) return;
        running = true;
        animationTimer.start();
        audioDataListener = (buffer, bytesRead, format) -> {
            if (running) {
                currentDb = calculateLevelImproved(buffer, bytesRead, format);
            }
        };
        AudioCaptureManager.getInstance().registerListener(mixerInfo, audioDataListener);
    }

    public void stop() {
        running = false;
        animationTimer.stop();
        if (audioDataListener != null && mixerInfo != null) {
            AudioCaptureManager.getInstance().unregisterListener(mixerInfo, audioDataListener);
            audioDataListener = null;
        }
    }

    private double calculateLevelImproved(byte[] buffer, int bytesRead, AudioFormat format) {
        double maxSample = 0.0;
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();

        for (int i = 0; i < bytesRead - frameSize + 1; i += frameSize) {
            if (format.getSampleSizeInBits() == 16) {
                short leftSample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                double leftAbs = Math.abs(leftSample / 32768.0);
                if (channels == 1) {
                    maxSample = Math.max(maxSample, leftAbs);
                    continue;
                }
                short rightSample = (short) ((buffer[i + 3] << 8) | (buffer[i + 2] & 0xFF));
                double rightAbs = Math.abs(rightSample / 32768.0);

                if (SettingsUtil.AUDIO_CHANNEL_LEFT.equalsIgnoreCase(channel)) maxSample = Math.max(maxSample, leftAbs);
                else if (SettingsUtil.AUDIO_CHANNEL_RIGHT.equalsIgnoreCase(channel)) maxSample = Math.max(maxSample, rightAbs);
                else maxSample = Math.max(maxSample, Math.max(leftAbs, rightAbs));

            } else if (format.getSampleSizeInBits() == 24) {
                int leftSample = ((buffer[i + 2] << 16) | ((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
                if ((leftSample & 0x800000) != 0) leftSample |= 0xFF000000;
                double leftAbs = Math.abs(leftSample / 8388608.0);
                if (channels == 1) {
                    maxSample = Math.max(maxSample, leftAbs);
                    continue;
                }
                int rightSample = ((buffer[i + 5] << 16) | ((buffer[i + 4] & 0xFF) << 8) | (buffer[i + 3] & 0xFF));
                if ((rightSample & 0x800000) != 0) rightSample |= 0xFF000000;
                double rightAbs = Math.abs(rightSample / 8388608.0);

                if (SettingsUtil.AUDIO_CHANNEL_LEFT.equalsIgnoreCase(channel)) maxSample = Math.max(maxSample, leftAbs);
                else if (SettingsUtil.AUDIO_CHANNEL_RIGHT.equalsIgnoreCase(channel)) maxSample = Math.max(maxSample, rightAbs);
                else maxSample = Math.max(maxSample, Math.max(leftAbs, rightAbs));
            }
        }

        if (maxSample == 0.0) {
            actualCurrentDb = -80.0;
            return MIN_DB;
        }

        double db = 20 * Math.log10(maxSample) + METER_CEILING_DB;
        actualCurrentDb = db;

        // Add to history and maintain size
        volumeHistory.add(actualCurrentDb);
        if (volumeHistory.size() > VOLUME_HISTORY_SIZE) {
            volumeHistory.removeFirst();
        }
        return Math.max(db, MIN_DB);
    }

    public double getAverageActualDb() {
        if (volumeHistory.isEmpty()) {
            return MIN_DB; // Or some other default/indicator
        }
        double sum = 0;
        for (Double val : volumeHistory) {
            sum += val;
        }
        return sum / volumeHistory.size();
    }

    /**
     * Converts a JavaFX Color object to an RGBA string for use in CSS.
     * @param color The color to convert.
     * @return The CSS RGBA string.
     */
    private String toRgbaString(Color color) {
        return String.format("rgba(%d, %d, %d, %f)",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255),
                color.getOpacity());
    }
}



