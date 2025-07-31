package org.kadampa.festivalstreaming;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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

import javax.sound.sampled.*;

public class LevelMeter {

    private final VBox view;
    private  Label dbLabel;
    private  Label audioInterfaceLabel;
    private  Circle statusIndicator;
    private DropShadow statusGlow;
    private Mixer.Info mixerInfo;
    private volatile boolean running = false;
    private String channel;
    private AudioCaptureManager.AudioDataListener audioDataListener;


    private Rectangle greenBar, yellowBar, redBar, peakHoldBar;
    private Rectangle greenBackground, yellowBackground, redBackground;
    private AnimationTimer animationTimer;
    private PauseTransition peakHoldTimer;

    private static final double MAX_DB = 12.0;
    private static final double MIN_DB = -40.0;
    private static final double YELLOW_THRESHOLD_DB = -6.0; // (−18 dBFS + 12)
    private static final double RED_THRESHOLD_DB = 6.0;      // (−6 dBFS + 12)
    private static final double METER_HEIGHT = 300;
    private static final double METER_WIDTH = 30;

    private volatile double currentDb = MIN_DB;
    private volatile double actualCurrentDb = MIN_DB;
    private double actualDisplayDb = MIN_DB;

    private double displayDb = MIN_DB;
    private double peakDb = MIN_DB;
    private final double DECAY_RATE_DB_PER_SEC = 20.0;

    private boolean peakFlashActive = false;
    private final PauseTransition peakFlashTimer = new PauseTransition(Duration.seconds(1));
    private Color backgroundColor;

    private long redPeakTimestamp = 0;
    private long yellowPeakTimestamp = 0;

    private Button monitorButton;
    private volatile boolean monitoringEnabled = false;

    public LevelMeter(String language, Mixer.Info mixerInfo, String channel, Color backgroundColor) {
        this.mixerInfo = mixerInfo;
        this.channel = channel;
        this.view = new VBox();
        this.view.setAlignment(Pos.CENTER);
        this.backgroundColor = backgroundColor;

        // Create header with language and status
        VBox headerBox = createHeader(language);

        // Create meter with graduations
        HBox meterContainer = createVerticalMeterWithGraduations();

        // Create info section
        VBox infoBox = createInfoSection();

        view.setSpacing(0);
        view.setPadding(new Insets(16));
        view.setAlignment(Pos.CENTER);
        view.setPrefWidth(280);
        view.setMinHeight(METER_HEIGHT + 120);
        view.getChildren().addAll(headerBox, meterContainer, infoBox);

        // Apply modern styling with gradient background
        String gradientStyle = createGradientStyle(backgroundColor);
        view.setStyle(gradientStyle +
            "-fx-background-radius: 16; " +
            "-fx-border-color: rgba(255, 255, 255, 0.2); " +
            "-fx-border-width: 2; " +
            "-fx-border-radius: 16;");

        // Add drop shadow effect
        DropShadow dropShadow = new DropShadow();
        dropShadow.setRadius(16);
        dropShadow.setOffsetX(0);
        dropShadow.setOffsetY(8);
        dropShadow.setColor(Color.rgb(0, 0, 0, 0.2));
        view.setEffect(dropShadow);

        setupAnimation();
        peakFlashTimer.setOnFinished(e -> peakFlashActive = false);
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
        languageLabel.setTextFill(Color.WHITE);
        languageLabel.setAlignment(Pos.CENTER);
        addTextShadow(languageLabel);

        audioInterfaceLabel = new Label();
        audioInterfaceLabel.setFont(Font.font("System", FontWeight.NORMAL, 13));
        audioInterfaceLabel.setTextFill(Color.rgb(255, 255, 255, 0.9));
        audioInterfaceLabel.setWrapText(true);
        audioInterfaceLabel.setAlignment(Pos.CENTER);
        audioInterfaceLabel.setTextAlignment(TextAlignment.CENTER);
        audioInterfaceLabel.setPrefWidth(200);
        audioInterfaceLabel.setMinHeight(32); // Reserve space for 2 lines
        addTextShadow(audioInterfaceLabel);

        updateAudioInterfaceLabel();

        statusIndicator = new Circle(7);
        statusGlow = new DropShadow(); // Initialize statusGlow here
        statusIndicator.setEffect(statusGlow); // Set effect here
        updateStatusIndicator();

        titleRow.getChildren().addAll(languageLabel, statusIndicator);
        titleBox.getChildren().addAll(titleRow, audioInterfaceLabel);

        headerBox.getChildren().addAll(titleBox);

        return headerBox;
    }

    private void updateMonitorButtonStyle() {
        monitorButton.setFont(Font.font("System", FontWeight.BOLD, 10));

        // Style based on monitoring state
        if (monitoringEnabled) {
            // Red background for active monitoring
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
            // Background-like color for inactive monitoring with strike-through effect
            monitorButton.setStyle(
                "-fx-background-color: rgba(0, 0, 0, 0.2);" +
                    "-fx-background-radius: 8;" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(255, 255, 255, 0.2);" +
                    "-fx-border-width: 1;" +
                    "-fx-border-radius: 8;" +
                    "-fx-text-fill: white;" +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 4, 0, 0, 2);"
            );

            // Add strike-through line for disabled state
            if (!monitoringEnabled) {
                // Create a line overlay effect by adding a Rectangle as background
                // This is a workaround since JavaFX doesn't have built-in strike-through for buttons
                monitorButton.setText("\uD83C\uDFA7"); // Unicode combining long stroke overlay
            }
        }

        // Add hover effect
        monitorButton.setOnMouseEntered(e -> {
            if (monitoringEnabled) {
                monitorButton.setStyle(monitorButton.getStyle().replace("0.3", "0.4"));
            } else {
                monitorButton.setStyle(monitorButton.getStyle().replace("rgba(0, 0, 0, 0.2)", "rgba(0, 0, 0, 0.4)"));
            }
        });

        monitorButton.setOnMouseExited(e -> updateMonitorButtonStyle());
    }

    private VBox createInfoSection() {
        VBox infoBox = new VBox();
        infoBox.setAlignment(Pos.CENTER);
        infoBox.setSpacing(12);
        infoBox.setPadding(new Insets(20, 0, 0, 0));

        // dB level display with modern styling
        dbLabel = new Label(String.format("%.1f dB", MIN_DB));
        dbLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        dbLabel.setTextFill(Color.WHITE);
        dbLabel.setAlignment(Pos.CENTER);
        dbLabel.setMinWidth(150);
        dbLabel.setStyle("-fx-background-color: rgba(0, 0, 0, 0.2); " +
            "-fx-background-radius: 25; " +
            "-fx-padding: 8 40; " +
            "-fx-border-color: rgba(255, 255, 255, 0.2); " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 25;");
        addTextShadow(dbLabel);

        // Monitor toggle button - integrated design with headphones icon style
        monitorButton = new Button();
        monitorButton.setPrefSize(35, 35);
        updateMonitorButtonStyle();
        monitorButton.setOnAction(e -> toggleMonitoring());

        HBox bottomRow = new HBox();
        bottomRow.setAlignment(Pos.CENTER);
        bottomRow.setSpacing(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bottomRow.getChildren().addAll(dbLabel, spacer,monitorButton);

        infoBox.getChildren().addAll(bottomRow);

        return infoBox;
    }

    private void toggleMonitoring() {
        monitoringEnabled = !monitoringEnabled;
        updateMonitorButtonStyle();

        if (monitoringEnabled) {
            AudioCaptureManager.getInstance().startMonitoring(mixerInfo);
        } else {
            AudioCaptureManager.getInstance().stopMonitoring(mixerInfo);
        }
    }

    private void updateStatusIndicator() {
        // This will be updated based on audio levels in the animation
        statusIndicator.setFill(Color.rgb(100, 100, 100)); // Default grey
        statusIndicator.setStroke(Color.rgb(255, 255, 255, 0.3));
        statusIndicator.setStrokeWidth(2);

        statusGlow.setRadius(10);
        statusGlow.setColor(Color.rgb(39, 174, 96, 0.8));
    }

    private void addTextShadow(Label label) {
        DropShadow textShadow = new DropShadow();
        textShadow.setRadius(2);
        textShadow.setOffsetX(0);
        textShadow.setOffsetY(2);
        textShadow.setColor(Color.rgb(0, 0, 0, 0.4));
        label.setEffect(textShadow);
    }

    private String createGradientStyle(Color color) {
        // Create a subtle gradient variation of the background color
        Color darkerColor = color.darker();

        return String.format("-fx-background-color: linear-gradient(to bottom, #%s, #%s); ",
            color.toString().substring(2),
            darkerColor.toString().substring(2));
    }

    private HBox createVerticalMeterWithGraduations() {
        HBox container = new HBox(15);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(0, 0, 0, 30));

        Pane meterPane = createVerticalMeter();
        VBox graduationLabels = createGraduationLabels(backgroundColor);
        container.getChildren().addAll(meterPane, graduationLabels);
        return container;
    }

    private VBox createGraduationLabels(Color backgroundColor) {
        Pane graduationPane = new Pane();
        graduationPane.setPrefHeight(METER_HEIGHT);
        graduationPane.setMinWidth(45);

        double[] labelDbValues = {-40, -36, -30, -24, -18, -12, -6, 0, 6, 12};
        double totalRange = MAX_DB - MIN_DB;

        for (double dbValue : labelDbValues) {
            double y = METER_HEIGHT * (1 - (dbValue - MIN_DB) / totalRange);

            Label label = new Label((dbValue >= 0 ? "+" : "") + String.format("%.0f", dbValue));
            label.setFont(Font.font("System", FontWeight.BOLD, 12));
            label.setTextFill(Color.WHITE);
            label.setLayoutY(y - 8); // center label vertically
            addTextShadow(label);

            // Add scale mark line
            Rectangle scaleMark = new Rectangle(10, 2);
            scaleMark.setFill(Color.rgb(255, 255, 255, 0.8));
            scaleMark.setLayoutX(-12);
            scaleMark.setLayoutY(y - 1);
            scaleMark.setArcWidth(1);
            scaleMark.setArcHeight(1);

            graduationPane.getChildren().addAll(scaleMark, label);
        }

        VBox wrapper = new VBox(graduationPane);
        wrapper.setAlignment(Pos.TOP_LEFT);
        return wrapper;
    }

    private Pane createVerticalMeter() {
        Pane meterPane = new Pane();
        meterPane.setPrefSize(METER_WIDTH, METER_HEIGHT);

        // This rectangle will clip the contents of the pane to have rounded corners
        Rectangle clipRect = new Rectangle(METER_WIDTH, METER_HEIGHT);
        clipRect.setArcWidth(30);
        clipRect.setArcHeight(30);
        meterPane.setClip(clipRect);

        Rectangle meterBackground = new Rectangle(METER_WIDTH, METER_HEIGHT, Color.rgb(0, 0, 0, 0.3));
        meterBackground.setStroke(Color.rgb(255, 255, 255, 0.2));
        meterBackground.setStrokeWidth(1);
        // The background itself doesn't need rounded corners if the pane is clipped, but it looks better with them.
        meterBackground.setArcWidth(30);
        meterBackground.setArcHeight(30);


        // Add inner shadow effect to meter background
        DropShadow innerShadow = new DropShadow();
        innerShadow.setRadius(8);
        innerShadow.setOffsetX(0);
        innerShadow.setOffsetY(2);
        innerShadow.setColor(Color.rgb(0, 0, 0, 0.3));
        meterBackground.setEffect(innerShadow);

        greenBackground = new Rectangle(METER_WIDTH, 0, Color.rgb(0, 80, 0));

        yellowBackground = new Rectangle(METER_WIDTH, 0, Color.rgb(80, 80, 0));

        redBackground = new Rectangle(METER_WIDTH, 0, Color.rgb(80, 0, 0));

        greenBar = new Rectangle(METER_WIDTH, 0, Color.LIMEGREEN);

        yellowBar = new Rectangle(METER_WIDTH, 0, Color.YELLOW);

        redBar = new Rectangle(METER_WIDTH, 0, Color.RED);

        peakHoldBar = new Rectangle(METER_WIDTH, 2, Color.rgb(255, 100, 100));
        peakHoldBar.setVisible(false);

        // Add glow effect to active bars
        DropShadow glow = new DropShadow();
        glow.setRadius(20);
        glow.setColor(Color.rgb(255, 255, 255, 0.2));
        greenBar.setEffect(glow);
        yellowBar.setEffect(glow);
        redBar.setEffect(glow);

        meterPane.getChildren().addAll(meterBackground, greenBackground, yellowBackground, redBackground, greenBar, yellowBar, redBar, peakHoldBar);
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
            private final long updateIntervalNs = 1_000_000_000 / 10; // 10 FPS

            @Override
            public void handle(long now) {
                if (lastUpdate != 0 && now - lastUpdate < updateIntervalNs) {
                    return;
                }

                if (lastUpdate == 0) {
                    lastUpdate = now;
                    return;
                }
                double elapsedSeconds = (now - lastUpdate) / 1_000_000_000.0;
                lastUpdate = now;

                // Smooth the visual meter display
                if (currentDb < displayDb) {
                    displayDb -= DECAY_RATE_DB_PER_SEC * elapsedSeconds;
                    displayDb = Math.max(displayDb, currentDb);
                } else {
                    displayDb = currentDb;
                }

                // Smooth the actual dB display (for the label)
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

                // Update peak timestamps
                if (displayDb > RED_THRESHOLD_DB) {
                    redPeakTimestamp = now;
                } else if (displayDb > YELLOW_THRESHOLD_DB) {
                    yellowPeakTimestamp = now;
                }

                updateStatusIndicator(now);
                drawMeter();
            }
        };
    }

    private void updateStatusIndicator(long now) {
        Color statusColor;
        Color glowColor = Color.rgb(100, 100, 100, 0.8);
        // If peak flash is active, indicator is RED. This is the highest priority.
        if (peakFlashActive) {
            statusColor = Color.RED;
        }
        // Check for red hold first (second highest priority)
        else if (now - redPeakTimestamp < 1_000_000_000L) { // 1 second in nanoseconds
            statusColor = Color.RED;
        }
        // Then check for yellow hold
        else if (now - yellowPeakTimestamp < 1_000_000_000L) {
            statusColor = Color.YELLOW;
        }
        // If not holding, determine color from current level
        else {
            if (displayDb > RED_THRESHOLD_DB) {
                statusColor = Color.RED;
                glowColor = Color.rgb(255, 0, 0, 0.8);
            } else if (displayDb > YELLOW_THRESHOLD_DB) {
                statusColor = Color.YELLOW;
                glowColor = Color.rgb(255, 255, 0, 0.8);
            } else {
                statusColor = Color.LIMEGREEN;
                glowColor = Color.rgb(50, 205, 50, 0.8);
                if(displayDb<=-40) {
                    statusColor = Color.GRAY;
                    glowColor = Color.rgb(100, 100, 100, 0.8);
                }
            }
        }

        statusIndicator.setFill(statusColor);
        statusGlow.setColor(glowColor);
    }

    // Keep the existing drawMeter, calculateContrastColor and other methods unchanged
    private void drawMeter() {
        double totalRange = MAX_DB - MIN_DB;
        double dbClamped = Math.max(MIN_DB, Math.min(MAX_DB, displayDb));

        double greenHeight, yellowHeight = 0, redHeight = 0;

        double yellowThresholdY = METER_HEIGHT * (1 - (YELLOW_THRESHOLD_DB - MIN_DB) / totalRange);
        double redThresholdY = METER_HEIGHT * (1 - (RED_THRESHOLD_DB - MIN_DB) / totalRange);
        double currentLevelY = METER_HEIGHT * (1 - (dbClamped - MIN_DB) / totalRange);

        redBackground.setY(0);
        redBackground.setHeight(redThresholdY);

        yellowBackground.setY(redThresholdY);
        yellowBackground.setHeight(yellowThresholdY - redThresholdY);

        greenBackground.setY(yellowThresholdY);
        greenBackground.setHeight(METER_HEIGHT - yellowThresholdY);

        if (dbClamped > YELLOW_THRESHOLD_DB) {
            greenHeight = METER_HEIGHT - yellowThresholdY;
        } else {
            greenHeight = METER_HEIGHT - currentLevelY;
        }

        if (dbClamped > RED_THRESHOLD_DB) {
            yellowHeight = yellowThresholdY - redThresholdY;
        } else if (dbClamped > YELLOW_THRESHOLD_DB) {
            yellowHeight = yellowThresholdY - currentLevelY;
        }

        if (dbClamped > RED_THRESHOLD_DB) {
            redHeight = redThresholdY - currentLevelY;
        }

        if (peakFlashActive) {
            greenBar.setY(0);
            greenBar.setHeight(0);
            yellowBar.setY(0);
            yellowBar.setHeight(0);
            redBar.setY(0);
            redBar.setHeight(METER_HEIGHT);
        } else {
            greenBar.setY(METER_HEIGHT - greenHeight);
            greenBar.setHeight(greenHeight);

            yellowBar.setY(METER_HEIGHT - greenHeight - yellowHeight);
            yellowBar.setHeight(yellowHeight);

            redBar.setY(METER_HEIGHT - greenHeight - yellowHeight - redHeight);
            redBar.setHeight(redHeight);
        }

        if (peakDb > MIN_DB) {
            double peakY = METER_HEIGHT * (1 - (peakDb - MIN_DB) / totalRange);
            peakHoldBar.setY(peakY - 1);
            if (peakDb > RED_THRESHOLD_DB) {
                peakHoldBar.setFill(Color.RED);
            } else if (peakDb > YELLOW_THRESHOLD_DB) {
                peakHoldBar.setFill(Color.YELLOW);
            } else {
                peakHoldBar.setFill(Color.LIMEGREEN);
            }
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
        String text = mixerInfo != null ? mixerInfo.getName() : "Not Selected";
        String channelDisplay = channel;
        if (channelDisplay == null || channelDisplay.isEmpty()) {
            channelDisplay = "Join";
        }

        if ("Join".equalsIgnoreCase(channelDisplay)) {
            text += " - Stereo";
        } else {
            text += " - " + channelDisplay;
        }
        audioInterfaceLabel.setText(text);
    }

    public void setMixerInfo(Mixer.Info mixerInfo) {
        stop();
        this.mixerInfo = mixerInfo;
        updateAudioInterfaceLabel();
        if (mixerInfo != null) {
            start();
        } else {
            currentDb = MIN_DB;
        }
    }

    public void setChannel(String channel) {
        this.channel = channel;
        updateAudioInterfaceLabel();
    }

    public void stopMonitoring() {
        if (monitoringEnabled) {
            toggleMonitoring();
        }
    }

    void start() {
        if (mixerInfo == null) return;
        running = true;
        animationTimer.start();

        // Create the audio data listener
        audioDataListener = (buffer, bytesRead, format) -> {
            if (running) {
                currentDb = calculateLevelImproved(buffer, bytesRead, format);
            }
        };

        // Register with the improved capture manager
        AudioCaptureManager.getInstance().registerListener(mixerInfo, audioDataListener);
    }

    public void stop() {
        running = false;
        animationTimer.stop();

        // Unregister from the capture manager
        if (audioDataListener != null && mixerInfo != null) {
            AudioCaptureManager.getInstance().unregisterListener(mixerInfo, audioDataListener);
            audioDataListener = null;
        }
    }

    private double calculateLevelImproved(byte[] buffer, int bytesRead, AudioFormat format) {
        double maxSample = 0.0;
        int channels = format.getChannels();
        int sampleSizeInBits = format.getSampleSizeInBits();
        int frameSize = format.getFrameSize();

        // Process every 4th frame for performance, but ensure we don't skip too much data
        int step = Math.max(frameSize, frameSize * 2);

        for (int i = 0; i < bytesRead - frameSize + 1; i += step) {
            if (sampleSizeInBits == 16) {
                // 16-bit samples
                if (channels == 1) {
                    // Mono - treat as both left and right
                    short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    double abs = Math.abs(sample / 32768.0);
                    maxSample = Math.max(maxSample, abs);
                } else if (channels == 2) {
                    // Stereo
                    short leftSample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    short rightSample = (short) ((buffer[i + 3] << 8) | (buffer[i + 2] & 0xFF));

                    double leftAbs = Math.abs(leftSample / 32768.0);
                    double rightAbs = Math.abs(rightSample / 32768.0);

                    if ("Left".equalsIgnoreCase(channel)) {
                        maxSample = Math.max(maxSample, leftAbs);
                    } else if ("Right".equalsIgnoreCase(channel)) {
                        maxSample = Math.max(maxSample, rightAbs);
                    } else { // "Join" or default
                        maxSample = Math.max(maxSample, Math.max(leftAbs, rightAbs));
                    }
                }
            } else if (sampleSizeInBits == 24) {
                // 24-bit samples (3 bytes per sample)
                if (channels == 1) {
                    // Mono 24-bit
                    int sample = ((buffer[i + 2] << 16) | ((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
                    // Sign extend 24-bit to 32-bit
                    if ((sample & 0x800000) != 0) {
                        sample |= 0xFF000000;
                    }
                    double abs = Math.abs(sample / 8388608.0); // 2^23
                    maxSample = Math.max(maxSample, abs);
                } else if (channels == 2) {
                    // Stereo 24-bit
                    int leftSample = ((buffer[i + 2] << 16) | ((buffer[i + 1] & 0xFF) << 8) | (buffer[i] & 0xFF));
                    int rightSample = ((buffer[i + 5] << 16) | ((buffer[i + 4] & 0xFF) << 8) | (buffer[i + 3] & 0xFF));

                    // Sign extend 24-bit to 32-bit
                    if ((leftSample & 0x800000) != 0) leftSample |= 0xFF000000;
                    if ((rightSample & 0x800000) != 0) rightSample |= 0xFF000000;

                    double leftAbs = Math.abs(leftSample / 8388608.0);
                    double rightAbs = Math.abs(rightSample / 8388608.0);

                    if ("Left".equalsIgnoreCase(channel)) {
                        maxSample = Math.max(maxSample, leftAbs);
                    } else if ("Right".equalsIgnoreCase(channel)) {
                        maxSample = Math.max(maxSample, rightAbs);
                    } else { // "Join" or default
                        maxSample = Math.max(maxSample, Math.max(leftAbs, rightAbs));
                    }
                }
            }
        }

        if (maxSample == 0.0) {
            actualCurrentDb = -80.0;
            return MIN_DB;
        }

        double db = 20 * Math.log10(maxSample) + MAX_DB;
        actualCurrentDb = db;

        if (db >= MAX_DB - 0.5 && !peakFlashActive) {
            peakFlashActive = true;
            Platform.runLater(() -> peakFlashTimer.playFromStart());
        }

        return Math.max(db, MIN_DB);
    }
}
