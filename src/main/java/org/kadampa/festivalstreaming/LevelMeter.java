package org.kadampa.festivalstreaming;

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
import java.util.ArrayDeque;
import java.util.ArrayList;

public class LevelMeter {

    //<editor-fold desc="Color Constants">
    // UI Colors
    private static final Color COLOR_TEXT_PRIMARY = Color.WHITE;
    private static final Color COLOR_TEXT_SECONDARY = Color.rgb(255, 255, 255, 0.9);
    private static final Color COLOR_BORDER = Color.rgb(255, 255, 255, 0.2);
    private static final Color COLOR_BACKGROUND_DARK_TRANSPARENT = Color.rgb(0, 0, 0, 0.2);
    private static final Color COLOR_BACKGROUND_METER = Color.rgb(0, 0, 0, 0.3);
    private static final Color COLOR_SHADOW = Color.rgb(0, 0, 0, 0.4);

    // Meter Bar Colors
    static final Color COLOR_METER_BLUE = Color.web("#C0C0C0");
    static final Color COLOR_METER_GREEN = Color.LIMEGREEN;
    static final Color COLOR_METER_YELLOW = Color.ORANGE;
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
    private static final Color COLOR_0DB_TICK = Color.rgb(255, 255, 0, 0.9);
    private static final Color COLOR_0DB_TICK_GLOW = Color.rgb(255, 255, 0, 0.4);
    //</editor-fold>


    public interface MonitorToggleListener {
        void onMonitorRequest(LevelMeter source);
    }

    private final VBox view;
    private final StackPane root;
    private Label dbLabel;
    private Label audioInterfaceLabel;
    private Circle statusIndicator;
    private DropShadow statusGlow;
    private Mixer.Info mixerInfo;
    private volatile boolean running = false;
    private String channel;
    private final String language;
    private AudioCaptureManager.AudioDataListener audioDataListener;

    private Rectangle peakHoldBar;
    private PauseTransition peakHoldTimer;

    //<editor-fold desc="Meter Constants">
    static final double METER_CEILING_DB = 12.0;
    static final double MIN_DB = -40.0;
    private double greenThresholdDb;
    private double yellowThresholdDb;
    private double redThresholdDb;
    //</editor-fold>

    private static final double METER_HEIGHT = 300;
    private static final double METER_WIDTH = 30;

    private volatile double currentDb = MIN_DB;
    private volatile double actualCurrentDb = MIN_DB;
    private double actualDisplayDb = MIN_DB;

    // Written on the audio capture thread, read on the VolumeMonitor scheduler thread:
    // every access must synchronize on the deque itself.
    private final ArrayDeque<Double> volumeHistory = new ArrayDeque<>();
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

    private ArrayList<Rectangle> meterBoxes;
    private ArrayList<Rectangle> meterBackgroundBoxes;
    // The lit-box "glow" is a translucent enlarged rectangle behind each box (crisp halo):
    // pure geometry, because per-box Gaussian DropShadows (40 x 12 meters) dominated GPU use.
    private ArrayList<Rectangle> haloBoxes;
    private Color[] boxColors;
    private DropShadow peakHoldGlow;
    // Last state pushed to the scene graph, so a frame only touches nodes that changed
    private int lastActiveBoxes = 0;
    private boolean lastPeakFlash = false;
    private int lastPeakBoxIndex = -1;
    private boolean lastPeakVisible = false;
    private int lastLabelTenths = Integer.MIN_VALUE;
    private long lastAnimationNanos = 0;
    private static final int NUM_BOXES = 40; // Total number of discrete boxes
    private static final double BOX_HEIGHT = (METER_HEIGHT - (NUM_BOXES - 1) * 2) / NUM_BOXES; // 2px gap between boxes
    private static final double BOX_GAP = 2.0; // Gap between boxes

    public LevelMeter(String language, Mixer.Info mixerInfo, String channel, Color backgroundColor, MonitorToggleListener listener, Settings settings) {
        this.language = language;
        this.mixerInfo = mixerInfo;
        this.channel = channel;
        this.originalBackgroundColor = backgroundColor;
        this.monitorToggleListener = listener;

        if ("English (for mix)".equals(language)) {
            greenThresholdDb = settings.getEnMixMeterGreenThresholdDb();
            yellowThresholdDb = settings.getEnMixMeterYellowThresholdDb();
            redThresholdDb = settings.getEnMixMeterRedThresholdDb();
        } else {
            greenThresholdDb = settings.getMeterGreenThresholdDb();
            yellowThresholdDb = settings.getMeterYellowThresholdDb();
            redThresholdDb = settings.getMeterRedThresholdDb();
        }

        this.view = new VBox();
        this.view.setAlignment(Pos.CENTER);

        VBox headerBox = createHeader(language);
        HBox meterContainer = createVerticalMeterWithGraduations();
        VBox infoBox = createInfoSection();

        view.setSpacing(0);
        view.setPadding(new Insets(12, 16, 12, 16));
        view.setAlignment(Pos.CENTER);
        view.setPrefWidth(280);
        view.setMinHeight(METER_HEIGHT + 120);
        view.getChildren().addAll(headerBox, meterContainer, infoBox);

        setupBindings();

        // The card's outer shadow lives on a static sibling node, NOT on the card itself:
        // an effect on the card would force a full-card Gaussian re-blur on every animated
        // frame. The static rectangle never changes, so its blur is rendered once and cached.
        DropShadow dropShadow = new DropShadow();
        dropShadow.setRadius(16);
        dropShadow.setOffsetX(0);
        dropShadow.setOffsetY(8);
        dropShadow.setColor(COLOR_BACKGROUND_DARK_TRANSPARENT);
        Rectangle shadowRect = new Rectangle();
        shadowRect.setManaged(false);
        shadowRect.setArcWidth(32);
        shadowRect.setArcHeight(32);
        shadowRect.setFill(Color.BLACK);
        shadowRect.widthProperty().bind(view.widthProperty());
        shadowRect.heightProperty().bind(view.heightProperty());
        shadowRect.setEffect(dropShadow);
        root = new StackPane(shadowRect, view);

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
        headerBox.setPadding(new Insets(0, 0, 10, 0));

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
        infoBox.setPadding(new Insets(12, 0, 0, 0));

        dbLabel = new Label(String.format("%.1f dB", MIN_DB));
        dbLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        dbLabel.setTextFill(COLOR_TEXT_PRIMARY);
        dbLabel.setAlignment(Pos.CENTER);
        dbLabel.setMinWidth(150);
        dbLabel.setStyle("-fx-background-color: " + toRgbaString(COLOR_BACKGROUND_DARK_TRANSPARENT) + "; -fx-background-radius: 25; -fx-padding: 6 40; -fx-border-color: " + toRgbaString(COLOR_BORDER) + "; -fx-border-width: 1; -fx-border-radius: 25;");
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

        double[] labelDbValues = {-37, -30, -20, -10, -3, 0, 6, 9};
        if ("English (for mix)".equals(language)) {
            labelDbValues = new double[]{-37, -30, -24, -20, -10, -3, 0, 6, 9};
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

        meterPane.getChildren().add(meterBackground);

        // Initialize the discrete boxes
        meterBoxes = new ArrayList<>();
        meterBackgroundBoxes = new ArrayList<>();
        haloBoxes = new ArrayList<>();
        rebuildBoxColors();

        for (int i = 0; i < NUM_BOXES; i++) {
            // Calculate position from bottom to top
            double y = METER_HEIGHT - (i + 1) * (BOX_HEIGHT + BOX_GAP) + BOX_GAP;

            // Background box (always visible, darker)
            Rectangle bgBox = new Rectangle(METER_WIDTH - 4, BOX_HEIGHT);
            bgBox.setX(2);
            bgBox.setY(y);
            bgBox.setArcWidth(4);
            bgBox.setArcHeight(4);
            bgBox.setFill(getBackgroundColorForBox(i));
            bgBox.setOpacity(0.3);
            meterBackgroundBoxes.add(bgBox);
            meterPane.getChildren().add(bgBox);

            // Crisp halo behind the lit box, clipped by the meter's rounded clip so it
            // bleeds into the side margins and gaps like the old Gaussian glow did
            Rectangle halo = new Rectangle(METER_WIDTH + 2, BOX_HEIGHT + 6);
            halo.setX(-1);
            halo.setY(y - 3);
            halo.setArcWidth(8);
            halo.setArcHeight(8);
            halo.setOpacity(0.35);
            halo.setFill(boxColors[i]);
            halo.setVisible(false);
            haloBoxes.add(halo);
            meterPane.getChildren().add(halo);

            // Active box (lit up when signal reaches this level)
            Rectangle activeBox = new Rectangle(METER_WIDTH - 4, BOX_HEIGHT);
            activeBox.setX(2);
            activeBox.setY(y);
            activeBox.setArcWidth(4);
            activeBox.setArcHeight(4);
            activeBox.setFill(boxColors[i]);
            activeBox.setVisible(false);

            meterBoxes.add(activeBox);
            meterPane.getChildren().add(activeBox);
        }

        // Add graduation ticks inside the meter
        double[] labelDbValues = {-37, -30, -20, -10, -3, 0, 3, 6, 9}; // Changed max from 12 to 9
        if ("English (for mix)".equals(language)) {
            labelDbValues = new double[]{-37, -30, -24, -20, -10, -3, 0, 3, 6, 9}; // Keep -24dB for English mix
        }
        double totalRange = METER_CEILING_DB - MIN_DB;

        for (double dbValue : labelDbValues) {
            double y = METER_HEIGHT * (1 - (dbValue - MIN_DB) / totalRange);
            Rectangle tick;
            if (dbValue == 0 || ("English (for mix)".equals(language) && dbValue == -24)) { // Keep -24dB for English mix
                tick = new Rectangle(METER_WIDTH, 2); // Full width for 0dB and English mix -24dB
                tick.setFill(COLOR_0DB_TICK);
                tick.setLayoutX(0);

                // Add a glow effect to the 0dB tick
                DropShadow tickGlow = new DropShadow();
                tickGlow.setColor(COLOR_0DB_TICK_GLOW);
                tickGlow.setRadius(10);
                tickGlow.setSpread(0.5);
                tick.setEffect(tickGlow);
            } else {
                tick = new Rectangle(METER_WIDTH / 4, 2);
                tick.setFill(COLOR_SCALE_MARK);
                tick.setLayoutX(METER_WIDTH - (METER_WIDTH / 4));
            }
            tick.setLayoutY(y - 1);
            meterPane.getChildren().add(tick);
        }

        // Add peak hold indicator (single box that moves)
        peakHoldBar = new Rectangle(METER_WIDTH - 4, BOX_HEIGHT);
        peakHoldBar.setX(2);
        peakHoldBar.setArcWidth(4);
        peakHoldBar.setArcHeight(4);
        peakHoldBar.setFill(COLOR_METER_PEAK);
        peakHoldBar.setVisible(false);

        // Enhanced glow for peak hold (single reusable instance)
        peakHoldGlow = new DropShadow();
        peakHoldGlow.setRadius(12);
        peakHoldGlow.setColor(COLOR_METER_PEAK);
        peakHoldGlow.setSpread(0.5);
        peakHoldBar.setEffect(peakHoldGlow);

        meterPane.getChildren().add(peakHoldBar);

        return meterPane;
    }

    /**
     * Changes the zone thresholds at runtime. The active boxes and status indicator pick up
     * the new values on the next animation frame; only the static background tint needs a refresh.
     */
    public void setThresholds(double greenDb, double yellowDb, double redDb) {
        this.greenThresholdDb = greenDb;
        this.yellowThresholdDb = yellowDb;
        this.redThresholdDb = redDb;
        rebuildBoxColors();
        for (int i = 0; i < meterBackgroundBoxes.size(); i++) {
            meterBackgroundBoxes.get(i).setFill(getBackgroundColorForBox(i));
        }
        // Push the new zone colors to the cached box/halo fills (unless a peak flash
        // currently paints everything red; the flash-exit restore uses boxColors anyway)
        if (!lastPeakFlash) {
            for (int i = 0; i < NUM_BOXES; i++) {
                meterBoxes.get(i).setFill(boxColors[i]);
                haloBoxes.get(i).setFill(boxColors[i]);
            }
        }
        lastPeakBoxIndex = -1; // force the peak-hold bar to refresh its color
    }

    private void rebuildBoxColors() {
        if (boxColors == null) {
            boxColors = new Color[NUM_BOXES];
        }
        for (int i = 0; i < NUM_BOXES; i++) {
            boxColors[i] = getColorForBox(i);
        }
    }

    // Helper method to determine color for each box based on its position
    private Color getColorForBox(int boxIndex) {
        double totalRange = METER_CEILING_DB - MIN_DB;
        double boxDbValue = MIN_DB + (boxIndex / (double)(NUM_BOXES - 1)) * totalRange;

        if (boxDbValue > redThresholdDb) {
            return COLOR_METER_RED;
        } else if (boxDbValue > yellowThresholdDb) {
            return COLOR_METER_YELLOW;
        } else if (boxDbValue > greenThresholdDb) {
            return COLOR_METER_GREEN;
        } else {
            return COLOR_METER_BLUE;
        }
    }

    // Helper method to determine background color for each box
    private Color getBackgroundColorForBox(int boxIndex) {
        double totalRange = METER_CEILING_DB - MIN_DB;
        double boxDbValue = MIN_DB + (boxIndex / (double)(NUM_BOXES - 1)) * totalRange;

        if (boxDbValue > redThresholdDb) {
            return COLOR_METER_BACKGROUND_RED;
        } else if (boxDbValue > yellowThresholdDb) {
            return COLOR_METER_BACKGROUND_YELLOW;
        } else if (boxDbValue > greenThresholdDb) {
            return COLOR_METER_BACKGROUND_GREEN;
        } else {
            return COLOR_METER_BACKGROUND_BLUE;
        }
    }

    private void drawMeter() {
        double totalRange = METER_CEILING_DB - MIN_DB;
        double dbClamped = Math.max(MIN_DB, Math.min(METER_CEILING_DB, displayDb));

        // Calculate which boxes should be lit up
        int activeBoxes = 0;
        if (dbClamped > MIN_DB) {
            double normalizedLevel = (dbClamped - MIN_DB) / totalRange;
            activeBoxes = (int) Math.round(normalizedLevel * NUM_BOXES);
            activeBoxes = Math.max(0, Math.min(NUM_BOXES, activeBoxes));
        }

        // Handle peak flash effect: only the enter/exit transitions touch the boxes;
        // during steady flash (all red) and steady normal frames nothing is rewritten
        if (peakFlashActive != lastPeakFlash) {
            if (peakFlashActive) {
                for (int i = 0; i < NUM_BOXES; i++) {
                    meterBoxes.get(i).setFill(COLOR_METER_RED);
                    meterBoxes.get(i).setVisible(true);
                    haloBoxes.get(i).setFill(COLOR_METER_RED);
                    haloBoxes.get(i).setVisible(true);
                }
            } else {
                for (int i = 0; i < NUM_BOXES; i++) {
                    meterBoxes.get(i).setFill(boxColors[i]);
                    meterBoxes.get(i).setVisible(i < activeBoxes);
                    haloBoxes.get(i).setFill(boxColors[i]);
                    haloBoxes.get(i).setVisible(i < activeBoxes);
                }
                lastActiveBoxes = activeBoxes;
            }
            lastPeakFlash = peakFlashActive;
        } else if (!peakFlashActive && activeBoxes != lastActiveBoxes) {
            // Normal operation - only toggle the boxes between the old and new level
            int from = Math.min(activeBoxes, lastActiveBoxes);
            int to = Math.max(activeBoxes, lastActiveBoxes);
            for (int i = from; i < to; i++) {
                boolean lit = i < activeBoxes;
                meterBoxes.get(i).setVisible(lit);
                haloBoxes.get(i).setVisible(lit);
            }
            lastActiveBoxes = activeBoxes;
        }

        // Handle peak hold indicator
        boolean peakVisible = peakDb > MIN_DB;
        if (peakVisible) {
            double peakNormalized = (peakDb - MIN_DB) / totalRange;
            int peakBoxIndex = (int) Math.round(peakNormalized * NUM_BOXES) - 1;
            peakBoxIndex = Math.max(0, Math.min(NUM_BOXES - 1, peakBoxIndex));

            if (peakBoxIndex != lastPeakBoxIndex) {
                peakHoldBar.setY(METER_HEIGHT - (peakBoxIndex + 1) * (BOX_HEIGHT + BOX_GAP) + BOX_GAP);
                Color peakColor = boxColors[peakBoxIndex];
                peakHoldBar.setFill(peakColor);
                peakHoldGlow.setColor(peakColor);
                lastPeakBoxIndex = peakBoxIndex;
            }
        }
        if (peakVisible != lastPeakVisible) {
            peakHoldBar.setVisible(peakVisible);
            lastPeakVisible = peakVisible;
        }

        // Update the dB label only when the displayed tenth of a dB actually changes
        int labelTenths = (int) Math.round(actualDisplayDb * 10);
        if (labelTenths != lastLabelTenths) {
            dbLabel.setText(String.format("%.1f dB", labelTenths / 10.0));
            lastLabelTenths = labelTenths;
        }
    }


    private void setupAnimation() {
        peakHoldTimer = new PauseTransition(Duration.seconds(1.5));
        peakHoldTimer.setOnFinished(e -> {
            peakDb = MIN_DB;
            peakHoldBar.setVisible(false);
        });
    }

    /**
     * One animation step. Driven by the panel's single shared timer (~30fps) instead of
     * a per-meter 60fps AnimationTimer, halving render work across all visible meters.
     */
    void tick(long now) {
        if (!running) {
            return;
        }
        if (lastAnimationNanos == 0) {
            lastAnimationNanos = now;
            return;
        }
        double elapsedSeconds = (now - lastAnimationNanos) / 1_000_000_000.0;
        lastAnimationNanos = now;

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
        if (displayDb >= METER_CEILING_DB - 0.1) {
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

    /** Forgets the last animation timestamp so the next tick doesn't apply a huge decay step. */
    void resetAnimationClock() {
        lastAnimationNanos = 0;
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

    public Pane getView() {
        return root;
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
        // Guard against double-start: a second registration would orphan the previous
        // listener in the capture manager and keep the device line open forever
        if (running || mixerInfo == null) return;
        running = true;
        setWarningDisplay(false, "", null);
        audioDataListener = new AudioCaptureManager.AudioDataListener() {
            @Override
            public void onAudioData(byte[] buffer, int bytesRead, AudioFormat format) {
                if (running) {
                    currentDb = calculateLevelImproved(buffer, bytesRead, format);
                }
            }

            @Override
            public void onCaptureError(String message) {
                if (running) {
                    setWarningDisplay(true, "Device unavailable", COLOR_WARNING_HIGH);
                }
            }
        };
        AudioCaptureManager.getInstance().registerListener(mixerInfo, audioDataListener);
    }

    public void stop() {
        running = false;
        lastAnimationNanos = 0;
        if (audioDataListener != null && mixerInfo != null) {
            AudioCaptureManager.getInstance().unregisterListener(mixerInfo, audioDataListener);
            audioDataListener = null;
        }
    }

    private double calculateLevelImproved(byte[] buffer, int bytesRead, AudioFormat format) {
        double maxSample = 0.0;
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();
        int bitDepth = format.getSampleSizeInBits();
        if (bitDepth != 16 && bitDepth != 24) {
            return MIN_DB;
        }
        double fullScale = AudioSamples.fullScale(bitDepth) + 1;

        // Left and Right are the two halves of a stereo cable, "Ch n" is one input of a mixer,
        // and anything else (Join, Stereo, unset) watches whichever side is loudest
        boolean bothChannels = channels > 1
                && !SettingsUtil.AUDIO_CHANNEL_LEFT.equalsIgnoreCase(channel)
                && !SettingsUtil.AUDIO_CHANNEL_RIGHT.equalsIgnoreCase(channel)
                && !(channel != null && channel.startsWith(SettingsUtil.AUDIO_CHANNEL_PREFIX));
        int selectedChannel = channels == 1 ? 0
                : Math.min(SettingsUtil.audioChannelIndex(channel), channels - 1);

        for (int i = 0; i < bytesRead - frameSize + 1; i += frameSize) {
            if (bothChannels) {
                double left = Math.abs(AudioSamples.readSample(buffer, i, 0, bitDepth) / fullScale);
                double right = Math.abs(AudioSamples.readSample(buffer, i, 1, bitDepth) / fullScale);
                maxSample = Math.max(maxSample, Math.max(left, right));
            } else {
                double sample = Math.abs(AudioSamples.readSample(buffer, i, selectedChannel, bitDepth) / fullScale);
                maxSample = Math.max(maxSample, sample);
            }
        }

        if (maxSample == 0.0) {
            actualCurrentDb = -80.0;
            return MIN_DB;
        }

        double db = 20 * Math.log10(maxSample) + METER_CEILING_DB;
        actualCurrentDb = db;

        // Add to history and maintain size
        synchronized (volumeHistory) {
            volumeHistory.addLast(actualCurrentDb);
            if (volumeHistory.size() > VOLUME_HISTORY_SIZE) {
                volumeHistory.removeFirst();
            }
        }
        return Math.max(db, MIN_DB);
    }

    public double getAverageActualDb() {
        synchronized (volumeHistory) {
            if (volumeHistory.isEmpty()) {
                return MIN_DB; // Or some other default/indicator
            }
            double sum = 0;
            for (Double val : volumeHistory) {
                sum += val;
            }
            return sum / volumeHistory.size();
        }
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



