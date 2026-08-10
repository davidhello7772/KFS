package org.kadampa.festivalstreaming;

import org.kadampa.festivalstreaming.linux.PulseAudioDevices;
import org.kadampa.festivalstreaming.linux.V4l2Devices;
import org.kadampa.festivalstreaming.linux.WindowAttention;
import org.kadampa.festivalstreaming.macos.AvFoundationDevices;

import com.github.sarxos.webcam.Webcam;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamingGUI extends Application {
    // The start of every main-window title, and the handle WindowAttention finds the window
    // by - the GNOME .desktop entry matches on WM_CLASS instead, which is this class's name
    private static final String WINDOW_TITLE_PREFIX = "Kadampa Festival";
    private static final String WAITING_TO_LIVESTREAM_AND_RECORD = "WAITING TO LIVESTREAM AND RECORD ON LOCAL MACHINE";
    private static final String WAITING_TO_RECORD = "WAITING TO RECORD ON LOCAL MACHINE";
    private static final String WAITING_TO_LIVESTREAM = "WAITING TO LIVESTREAM";
    private static final String CURRENTLY_RECORDING = "RECORDING ON LOCAL MACHINE IN PROGRESS";
    private static final String CURRENTLY_LIVESTREAMING ="LIVESTREAM IN PROGRESS";
    private static final String CURRENTLY_LIVESTREAMING_AND_RECORDING = "LIVESTREAM AND RECORDING ON LOCAL MACHINE IN PROGRESS";
    private final ComboBox<String>[] inputAudioSources;
    private final ComboBox<String>[] inputAudioSourcesChannel;
    private final ComboBox<String>[] inputNoiseReductionValues;
    private final Map<String, ColorPicker> languageColorPickers = new HashMap<>();
    private final ComboBox<String> inputVideoSource;
    /** AVFoundation only opens a mode the device advertised, so macOS picks one explicitly. */
    private final ComboBox<String> inputVideoInputMode;
    private final ComboBox<String> inputVideoSourceBuffer;
    private final ComboBox<String> inputAudioSourceBuffer;
    private final ComboBox<String> inputVideoBitrate;
    private final TextField inputSoundDelay;
    private final ComboBox<String> inputPixelFormat;
    private final ComboBox<String> inputSrtResolution;
    private final ComboBox<String> inputEncoder;
    private final ComboBox<String> inputChooseBetweenUrlOrFile;
    private final TextField inputSrtURL;
    private final TextField inputOutputDirectory;
    private final ComboBox<String> inputAudioBitrate;
    /** The extra recording for the communication team: its own quality and destination. */
    private final CheckBox inputCommRecording;
    private final ComboBox<String> inputCommResolution;
    private final ComboBox<String> inputCommVideoBitrate;
    private final ComboBox<String> inputCommAudioBitrate;
    private final TextField inputCommDirectory;
    /** The rate the capture devices run at; the recording is encoded at the same rate. */
    private final ComboBox<String> inputAudioSampleRate;
    private final ComboBox<String> inputFramePerSecond;
    /**
     * The console: a read-only text area, monospace, on a plain light panel - a log the operator
     * can drag a selection across and copy out of, exactly as they would from a terminal.
     *
     * <p>It used to be styled text, one colour per severity, which read well but could not be
     * selected: a JavaFX Text node has no background to highlight, and setting its selection range
     * paints nothing outside a text control (measured, not assumed). Colouring the whole line only
     * bought line-at-a-time selection, which is not what anyone means by selectable. A text control
     * can only paint one colour for all of its text, so severity is said in words at the head of
     * the line instead - and the alarm, the orange status bar, the dock attention and the printed
     * advice all say it far louder than red text ever did.
     */
    private final VBox consoleOutputBox = new VBox();
    private ScrollPane consoleOutputScrollPane;
    /** The row holding ffmpeg's status line, which the next report overwrites. */
    private Node statusLineRow;
    /** The rows the operator has picked out, in the order they appear. */
    private final List<Node> selectedConsoleRows = new ArrayList<>();
    /** Where a drag or a shift-click measures its range from. */
    private Node consoleSelectionAnchor;
    private final Button startButton;
    private final Button stopButton;
    private final Button clearOutputButton;
    private final StringProperty currentInformationTextProperty = new SimpleStringProperty();
    private final TextField inputTimeNeededToOpenADevice;
    private Thread encodingThread;
    private final StreamRecorderRunnable streamRecorder = new StreamRecorderRunnable(this);
    private final Settings settings;
    private final SVGPath stopPath = new SVGPath();
    private final TextArea textAreaInfo = new TextArea();
    private static final int WINDOW_WIDTH = 900;
    private static final int WINDOW_HEIGHT = 950;
    /** Big enough for the pulsing words and most of an ffmpeg status line, and no bigger. */
    private static final int COMPACT_WINDOW_WIDTH = 620;
    private static final int COMPACT_WINDOW_HEIGHT = 130;
    private static final double TOOLTIP_DELAY = 0.2;
    private static final int TOOLTIP_DURATION=10;
    private static final double LABEL_PREF_WIDTH = 150;
    private final Timeline blinkingTimeLine;
    private final BooleanProperty followingConsoleTail = new SimpleBooleanProperty(true);
    /** How far down the console was when it last put itself at the bottom. */
    /** Set while the console scrolls itself, so its own move is not read as the operator's. */
    private boolean scrollingConsoleToTail;
    /** How close to the bottom still counts as the bottom - a scroll bar never lands exactly on 1. */
    private static final double CONSOLE_AT_BOTTOM_EPSILON = 0.001;
    private final Label videoPID = new Label("");
    private final List<Label> audioPidLabels = new ArrayList<>();
    private final Label nowPlayingLabel = new Label("");
    private final Circle liveDot = new Circle(9);
    private final DropShadow liveDotGlow = new DropShadow();
    private final HBox nowPlayingBox = new HBox(nowPlayingLabel);
    private final BooleanProperty isTheOutputAFile = new SimpleBooleanProperty();
    /** True while a device re-scan runs, so the refresh button cannot be pressed twice. */
    private final BooleanProperty devicesRefreshing = new SimpleBooleanProperty(false);
    private final BooleanProperty isTheOutputAURL = new SimpleBooleanProperty();
    private final BooleanProperty isTheOutputFileAndUrl = new SimpleBooleanProperty();
    /**
     * True while the languages are spread over more than one audio device. The time needed to open
     * a device only shifts the command from the second device onwards, so with everything on one
     * mixer - which is every Mac, and this festival's Linux rig - the field is not merely unused,
     * it invites a value that will do nothing. It is hidden until it applies.
     */
    private final BooleanProperty severalAudioDevices = new SimpleBooleanProperty(false);
    private LevelMeterPanel vuMeterPanel;
    private VolumeMonitor volumeMonitor;

    


    private final TextField playerURLTextField = new TextField();
    private final HBox outputFileHBox = new HBox();
    private final HBox outputUrlHBox = new HBox();
    /** Optional: replace the latency the pasted URL carries, without ever editing the URL itself. */
    private final CheckBox inputSrtLatencyOverride = new CheckBox("Override the latency in the URL:");
    private final TextField inputSrtLatencyMillis = new TextField();
    private final HBox srtLatencyHBox = new HBox();
    private Scene scene;
    private Tab controlConsoleTab;
    private Stage primaryStage;
    private LanguagesTab languagesTab;
    /** The scene root: the mode colour lives on it, so compact mode swaps what is inside it. */
    private BorderPane shell;
    /** The whole interface, kept alive while the compact view is up so the log survives it. */
    private Region fullContent;
    private Node footer;
    private VBox compactView;
    /** The row under the pulsing bar in the compact view. */
    private HBox compactStatusRow;
    /** The VBox holding the status bar and the tabs, so the bar can be lent to the compact view. */
    private VBox windowRoot;
    private Button compactButton;
    private final Label compactStatusLine = new Label();
    private boolean compactMode;
    /** The window as it was before it shrank, to put back exactly. */
    private double fullWidth;
    private double fullHeight;
    private boolean wasAlwaysOnTop;
    /** Whether the status bar is currently saying "in progress" rather than "waiting". */
    private boolean showingInProgressText;
    private final Image iconLiveStreamIdle = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/live-streaming.png")));
    private final Image iconLiveStreamPlaying = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/live-streaming-playing.jpg")));
    private final Image iconRecordingIdle = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/recording-idle.png")));
    private final Image iconRecordingPlaying = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/recording-playing.jpg")));
    /** The style class the scene root carries so the stylesheet knows which window this is. */
    private static final String WINDOW_SHELL = "window-shell";
    // The three output modes as the stylesheet knows them. Whole class names rather than a prefix
    // plus a suffix, so a colour can be traced from the Java to the CSS with a single grep.
    private static final String MODE_LIVESTREAM = "mode-livestream";
    private static final String MODE_RECORD = "mode-record";
    private static final String MODE_LIVESTREAM_AND_RECORD = "mode-livestream-and-record";
    private static final List<String> MODE_STYLE_CLASSES =
            List.of(MODE_LIVESTREAM, MODE_RECORD, MODE_LIVESTREAM_AND_RECORD);
    // Keep in sync with -green-color / -orange-color in javafx@main.css
    private static final Color LIVE_GREEN = Color.web("#22C55E");
    private static final Color ERROR_ORANGE = Color.web("#EE7130");
    private static final long ERROR_COLOUR_HOLD_MS = 3000;
    private static final int MAX_PLAUSIBLE_DEVICE_OPEN_MS = 10000;
    /** The believable range for the stream latency, in milliseconds. The festival runs at 2000, and
     *  the mistake this catches is pasting the URL's microsecond figure into a millisecond field. */
    private static final int MIN_PLAUSIBLE_SRT_LATENCY_MS = 20;
    private static final int MAX_PLAUSIBLE_SRT_LATENCY_MS = 10000;
    private final DoubleProperty barPulse = new SimpleDoubleProperty(1.0);
    /** The delayed return to the idle look after a stream ends; cancelled by a new start. */
    private PauseTransition pendingIdleReset;
    private Color barBaseColor = LIVE_GREEN;
    private long lastErrorMillis = 0;
    private long firstOpeningDeviceStartupTime = 0;
    private long secondOpeningDeviceStartupTime = 0;
    /** Read and written from the alarm thread as well as the FX one, so the check has to be atomic. */
    private final AtomicBoolean playingError = new AtomicBoolean();
    /** Reads the health of the stream out of ffmpeg's status line. */
    private final StreamHealth streamHealth = new StreamHealth();
    /** The live "30 fps · 1.00x" beside the status text, so a fault shows without opening a tab. */
    private final Label statusReadout = new Label();
    /**
     * A capture fault that will not clear on its own, so the window must not quietly return to a
     * healthy green three seconds later the way it does after a passing error.
     */
    private boolean videoFaultLatched;
    /** The nodes making up the status line currently at the foot of the console. */
    /** Set while a drain is already on its way to the FX thread, so a batch is asked for once. */
    private final AtomicBoolean consoleDrainScheduled = new AtomicBoolean();
    /** Set while a layout repair is already on its way; see repairWindowLayoutLater. */
    private final AtomicBoolean layoutRepairQueued = new AtomicBoolean();
    private static final List<String> SEVERITY_STYLE_CLASSES =
            List.of("secondary-text", "primary-text", "warning-text", "danger-text");
    /** The format the camera delivers frames in, discovered when the video source is chosen. */
    private String videoInputPixelFormat;
    private static final Logger logger = LoggerFactory.getLogger(StreamingGUI.class);

    public StreamingGUI() {
        // Load settings first: it installs the language list (possibly defined in the .ini file)
        // that determines the size of the input arrays below
        settings = SettingsUtil.loadSettings("settings");
        // Check for development mode system property
        if (Boolean.parseBoolean(System.getProperty("kfs.developmentMode"))) {
            settings.setDevelopmentMode(true);
        }
        int numberOfLanguages = Settings.LANGUAGES.length;
        inputAudioSources = new ComboBox[numberOfLanguages];
        inputAudioSourcesChannel = new ComboBox[numberOfLanguages];
        inputNoiseReductionValues = new ComboBox[numberOfLanguages];

        for (int i = 0; i < inputAudioSources.length; i++) {
            inputAudioSources[i] = new ComboBox<>();
            inputAudioSourcesChannel[i] = new ComboBox<>();
            inputNoiseReductionValues[i] = new ComboBox<>();
            // Any language moving to another device can change the answer, including the moves
            // applySettings makes for itself when the window opens
            inputAudioSources[i].valueProperty().addListener((observable, oldValue, newValue) ->
                    severalAudioDevices.set(selectedAudioDevices().size() > 1));
        }
        inputVideoSource = new ComboBox<>();
        inputSoundDelay = new TextField();
        inputSoundDelay.setText("0");
        inputAudioBitrate = new ComboBox<>();
        inputAudioBitrate.getItems().add("128k");
        inputAudioBitrate.getItems().add("256k");
        inputAudioSampleRate = new ComboBox<>();
        inputAudioSampleRate.getItems().addAll("44100", "48000", "88200", "96000");
        // The devices are opened at this rate, so a change has to reach the capture manager
        AudioCaptureManager.setPreferredSampleRate(parseIntOrDefault(settings.getAudioSampleRate(), 48000));
        // On Linux the capture manager resolves device names through ffmpeg's own device list
        AudioCaptureManager.setFfmpegPath(settings.getFfmpegPath());
        // A JVM killed from outside (an IDE rerun, a session logout) never reaches handleClose,
        // and an ffmpeg left behind keeps the camera busy and the stream ingest occupied
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process encoding = streamRecorder.getProcess();
            if (encoding != null && encoding.isAlive()) {
                streamRecorder.stop();
            }
        }));
        inputAudioSampleRate.valueProperty().addListener((observable, oldValue, newValue) ->
                AudioCaptureManager.setPreferredSampleRate(parseIntOrDefault(newValue, 48000)));
        inputFramePerSecond = new ComboBox<>();
        inputFramePerSecond.getItems().add("30");
        inputVideoBitrate = new ComboBox<>();
        inputVideoBitrate.getItems().add("1000k");
        inputVideoBitrate.getItems().add("1500k");
        inputVideoBitrate.getItems().add("2000k");
        inputVideoBitrate.getItems().add("2500k");
        inputVideoBitrate.getItems().add("3000k");
        inputVideoBitrate.getItems().add("3500k");
        inputVideoBitrate.getItems().add("4000k");
        inputVideoBitrate.getItems().add("4500k");
        inputVideoBitrate.getItems().add("5000k");
        inputVideoBitrate.getItems().add("5500k");
        inputVideoBitrate.getItems().add("6000k");
        inputVideoBitrate.getItems().add("6500k");
        inputVideoBitrate.getItems().add("7000k");
        inputVideoBitrate.getItems().add("7500k");
        inputVideoBitrate.getItems().add("8500k");

        inputVideoSourceBuffer = new ComboBox<>();
        inputVideoSourceBuffer.getItems().add("256M");
        inputVideoSourceBuffer.getItems().add("512M");
        inputVideoSourceBuffer.getItems().add("1024M");

        inputAudioSourceBuffer = new ComboBox<>();
        inputAudioSourceBuffer.getItems().add("64M");
        inputAudioSourceBuffer.getItems().add("128M");
        inputAudioSourceBuffer.getItems().add("256M");

        inputPixelFormat = new ComboBox<>();
        inputPixelFormat.getItems().add("yuv420p");

        inputSrtResolution = new ComboBox<>();
        inputSrtResolution.getItems().add("hd480");
        inputSrtResolution.getItems().add("hd720");
        inputSrtResolution.getItems().add("hd1080");

        // The extra recording for the communication team: its own quality and destination
        inputCommRecording = new CheckBox("Also record a separate file for the communication team");
        inputCommResolution = new ComboBox<>();
        inputCommResolution.getItems().addAll("hd480", "hd720", "hd1080");
        inputCommVideoBitrate = new ComboBox<>();
        inputCommVideoBitrate.getItems().addAll(inputVideoBitrate.getItems());
        inputCommAudioBitrate = new ComboBox<>();
        inputCommAudioBitrate.getItems().addAll(inputAudioBitrate.getItems());
        inputCommDirectory = new TextField();

        inputEncoder = new ComboBox<>();
        // The hardware encoder depends on the machine: NVENC on the Windows streaming PC,
        // VideoToolbox on a Mac. Only what this ffmpeg build actually has is offered.
        inputEncoder.getItems().addAll(Host.videoEncoders(settings.getFfmpegPath()));

        inputVideoInputMode = new ComboBox<>();

        inputChooseBetweenUrlOrFile = new ComboBox<>();
        inputChooseBetweenUrlOrFile.getItems().add("Srt URL (livestream)");
        inputChooseBetweenUrlOrFile.getItems().add("File");
        inputChooseBetweenUrlOrFile.getItems().add("Livestream And File");


        // The three flags are set one after the other, and each one fires its own listeners as it
        // goes. Restyling from those listeners therefore ran twice per change and read a state that
        // was still half updated - which is how switching from "Livestream And File" to "File" used
        // to leave the window in the livestream colours. The look is settled here instead, once,
        // after all three agree.
        inputChooseBetweenUrlOrFile.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            isTheOutputFileAndUrl.setValue(newValue.equals("Livestream And File"));
            isTheOutputAFile.setValue(newValue.equals("Livestream And File") || newValue.equals("File"));
            isTheOutputAURL.setValue(newValue.equals("Livestream And File") || newValue.equals("Srt URL (livestream)"));
            // The combo is built before the window is: the first settings load styles the scene itself
            if (scene != null) {
                applyStyleOnOutputTypeChange();
            }
            }
        );

        inputTimeNeededToOpenADevice = new TextField();
        inputSrtURL = new TextField();
        inputOutputDirectory = new TextField();
        outputFileHBox.visibleProperty().bind(isTheOutputAFile);
        outputUrlHBox.visibleProperty().bind(isTheOutputAURL);
        // Hidden and out of the layout, not merely invisible: on its own, visible leaves the row
        // holding its full height and the settings keep a blank gap where the other output used to be
        outputFileHBox.managedProperty().bind(outputFileHBox.visibleProperty());
        outputUrlHBox.managedProperty().bind(outputUrlHBox.visibleProperty());
        srtLatencyHBox.visibleProperty().bind(isTheOutputAURL);
        srtLatencyHBox.managedProperty().bind(srtLatencyHBox.visibleProperty());
        // The number only means anything while the override is asked for, exactly as the
        // communication recording's fields follow its checkbox
        inputSrtLatencyMillis.setMaxWidth(75);
        inputSrtLatencyMillis.disableProperty().bind(inputSrtLatencyOverride.selectedProperty().not());
        //consoleOutputTextArea = new TextArea();
        startButton = new Button("Start");
        startButton.getStyleClass().add("event-button");
        startButton.getStyleClass().add("success-button");
        liveDot.setFill(Color.WHITE);
        liveDotGlow.setColor(Color.WHITE);
        liveDotGlow.setSpread(0.4);
        liveDot.setEffect(liveDotGlow);

        // The whole bar breathes between a deep and a vivid shade of the state colour, so it is
        // impossible to miss from across the room but never blinks away to nothing
        barPulse.addListener(observable -> paintStatusBar());
        blinkingTimeLine = new Timeline(
                new KeyFrame(Duration.ZERO, e -> onPulse(true),
                        new KeyValue(barPulse, 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.opacityProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(liveDotGlow.radiusProperty(), 22.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(0.9), e -> onPulse(false),
                        new KeyValue(barPulse, 0.0, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.opacityProperty(), 0.35, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.scaleXProperty(), 0.7, Interpolator.EASE_BOTH),
                        new KeyValue(liveDot.scaleYProperty(), 0.7, Interpolator.EASE_BOTH),
                        new KeyValue(liveDotGlow.radiusProperty(), 4.0, Interpolator.EASE_BOTH))
        );

        blinkingTimeLine.setCycleCount(Timeline.INDEFINITE); // Repeat indefinitely
        blinkingTimeLine.setAutoReverse(true); // Breathe back down instead of jumping

        // The dot only exists while the animation runs, so the bar shows nothing extra when idle
        liveDot.visibleProperty().bind(blinkingTimeLine.statusProperty().isEqualTo(Animation.Status.RUNNING));
        liveDot.managedProperty().bind(liveDot.visibleProperty());

        SVGPath playPath = new SVGPath();
        playPath.setContent("M16.6582 9.28638C18.098 10.1862 18.8178 10.6361 19.0647 11.2122C19.2803 11.7152 19.2803 12.2847 19.0647 12.7878C18.8178 13.3638 18.098 13.8137 16.6582 14.7136L9.896 18.94C8.29805 19.9387 7.49907 20.4381 6.83973 20.385C6.26501 20.3388 5.73818 20.0469 5.3944 19.584C5 19.053 5 18.1108 5 16.2264V7.77357C5 5.88919 5 4.94701 5.3944 4.41598C5.73818 3.9531 6.26501 3.66111 6.83973 3.6149C7.49907 3.5619 8.29805 4.06126 9.896 5.05998L16.6582 9.28638Z");
        playPath.setScaleX(1);
        playPath.setScaleY(1);
        playPath.setFill(Color.WHITE);
        startButton.setGraphicTextGap(15);
        startButton.setGraphic(playPath);
        stopButton = new Button("Stop");
        stopButton.getStyleClass().add("event-button");
        stopButton.getStyleClass().add("danger-button");
        stopButton.setDisable(true);
        stopPath.setContent("M546,571 L522,571 C520.896,571 520,571.896 520,573 L520,597 C520,598.104 520.896,599 522,599 L546,599 C547.104,599 548,598.104 548,597 L548,573 C548,571.896 547.104,571 546,571");
        stopPath.setScaleX(0.5);
        stopPath.setScaleY(0.5);
        stopPath.setFill(Color.WHITE);
        stopButton.setGraphicTextGap(15);
        stopButton.setGraphic(stopPath);

        clearOutputButton = new Button("Clear Output");
        clearOutputButton.getStyleClass().add("event-button");
        clearOutputButton.getStyleClass().add("secondary-button");

        // Add action listeners
        startButton.setOnAction(event -> {
            try {
                clearConsole();
                startEncodingThread();
            } catch (Exception e) {
                if (Settings.DEVELOPMENT_MODE) {
                    throw new RuntimeException(e);
                } else {
                    Platform.runLater(()->appendToConsole(e.toString(), ConsoleSeverity.ERROR));
                }
            }
        });

        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(20,20);
        progressIndicator.setStyle("-fx-progress-color: white;");

        // Add an event filter to consume the space key event on the stop button
        // we want to prevent the stop when the space is pressed
        stopButton.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.SPACE) {
                event.consume();
            }
        });

        stopButton.setOnAction(event -> {
            if (!confirm("Stop now?", whatStopEnds(), "Stop")) {
                return;
            }
            stopButton.setGraphic(progressIndicator);
            // Create a Task to run the long-running method in the background
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() {
                    stopEncodingThread(); // Simulate long-running task
                    return null;
                }

                @Override
                protected void succeeded() {
                    super.succeeded();
                    // Reset the button graphic after the task is done
                    stopButton.setGraphic(stopPath);
                }

                @Override
                protected void failed() {
                    super.failed();
                    // Handle any errors if needed
                    stopButton.setGraphic(stopPath); // Reset the graphic
                }
            };

            // Start the task on a new thread
            new Thread(task).start();
        });

        //clearOutputButton.setOnAction((ActionEvent e) ->consoleOutputTextArea.getChildren().clear());
        clearOutputButton.setOnAction((ActionEvent e) -> clearConsole());


        List<String> audioDeviceNames = audioDeviceNames();
        for (ComboBox<String> audioInput : inputAudioSources) {
            audioInput.getItems().add("Not Used");
            audioInput.getItems().addAll(audioDeviceNames);
        }
        for (int i = 0; i < inputAudioSources.length; i++) {
            populateChannels(i, inputAudioSources[i].getValue());
        }
        for (ComboBox<String> noiseReductionInput : inputNoiseReductionValues) {
            noiseReductionInput.getItems().addAll("0","1","2","3");
        }

        populateVideoSources();
    }

    /**
     * The audio devices to offer for the languages. On Linux they come from the sound server,
     * because that is where ffmpeg reads them and where every device works whatever holds it:
     * see {@link PulseAudioDevices}. Elsewhere they are the Java Sound capture mixers, whose
     * names DirectShow accepts back.
     */
    private List<String> audioDeviceNames() {
        if (Host.isLinux()) {
            return PulseAudioDevices.descriptions(settings.getFfmpegPath());
        }
        List<String> names = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            // Check if this mixer supports any TargetDataLine (input line)
            for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                if (TargetDataLine.class.isAssignableFrom(lineInfo.getLineClass())) {
                    names.add(mixerInfo.getName());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * Lists the cameras. The webcam-capture library is the long-standing Windows path; it has no
     * working macOS driver, and on macOS and Linux ffmpeg reports the devices under exactly the
     * names it will accept back as an input.
     */
    private void populateVideoSources() {
        inputVideoSource.getItems().addAll(videoDeviceNames());
    }

    private List<String> videoDeviceNames() {
        if (Host.isMac()) {
            return AvFoundationDevices.videoDeviceNames(settings.getFfmpegPath());
        }
        if (Host.isLinux()) {
            return V4l2Devices.videoDeviceNames(settings.getFfmpegPath());
        }
        List<String> names = new ArrayList<>();
        for (Webcam webcam : Webcam.getWebcams()) {
            String name = webcam.getDevice().getName();
            names.add(name.substring(0, name.length() - 2));
        }
        return names;
    }

    /**
     * Re-reads the device lists without restarting the program: the sound card or the
     * capture card is often plugged in only after launch. The enumeration shells out to
     * ffmpeg with multi-second deadlines, so it runs off the FX thread.
     * <p>
     * The scan never changes a setting. Every selection survives untouched — a device
     * still missing stays selected the way a saved setting does, and a newly plugged one
     * simply appears in its list. Channel lists and video modes are only rebuilt for a
     * device that is present and answered the scan, so a configured channel cannot be
     * clamped away by a device that is absent or momentarily silent; the one exception is
     * a present device that genuinely no longer offers what was chosen.
     */
    private void refreshDevices() {
        devicesRefreshing.set(true);
        // The scan probes the video devices, and ffmpeg must not race it for them: starting
        // is held back for the scan's couple of seconds, then restored to whatever the
        // stream's own state says it should be
        startButton.setDisable(true);
        // Snapshot the selections on the FX thread; the scan must not read live controls
        String[] selectedAudio = new String[inputAudioSources.length];
        for (int i = 0; i < inputAudioSources.length; i++) {
            selectedAudio[i] = inputAudioSources[i].getValue();
        }
        Thread scan = new Thread(() -> {
            List<String> audioNames = audioDeviceNames();
            List<String> videoNames = videoDeviceNames();
            // The channel lists follow the selected devices, whose channel counts can be
            // asked now that the hardware may finally be there; zero means "leave alone"
            int[] channelCounts = new int[selectedAudio.length];
            for (int i = 0; i < selectedAudio.length; i++) {
                channelCounts[i] = rescanChannelCount(selectedAudio[i], audioNames);
            }
            Platform.runLater(() -> {
                for (int i = 0; i < inputAudioSources.length; i++) {
                    ComboBox<String> audioInput = inputAudioSources[i];
                    String selected = audioInput.getValue();
                    audioInput.getItems().setAll(SettingsUtil.AUDIO_SOURCE_NOT_USED);
                    audioInput.getItems().addAll(audioNames);
                    audioInput.setValue(selected);
                    if (channelCounts[i] > 0) {
                        populateChannels(i, channelCounts[i]);
                    }
                }
                String selectedVideo = inputVideoSource.getValue();
                inputVideoSource.getItems().setAll(videoNames);
                inputVideoSource.setValue(selectedVideo);
                // Restoring the same value fires no change event, so the modes - which a
                // freshly started virtual camera only now reports - are refreshed by hand.
                // Only for a present device: an absent one keeps its configured mode
                if (selectedVideo != null && videoNames.contains(selectedVideo)) {
                    populateVideoInputModes();
                }
                // Same silence for the level meters: their restart normally rides on a
                // selection change, so a meter waiting for this very device is poked here
                if (vuMeterPanel != null) {
                    vuMeterPanel.restartMetersAfterDeviceRescan();
                }
                startButton.setDisable(streamRecorder.isAliveProperty().get());
                devicesRefreshing.set(false);
            });
        }, "device-refresh");
        scan.setDaemon(true);
        scan.start();
    }

    /**
     * Fills the channel list for one language from the device it reads.
     * <p>
     * A stereo cable carries two languages, so it keeps the familiar Left/Right/Join. A mixer
     * presents all of its inputs as a single device instead, and there each language sits on its
     * own numbered channel.
     */
    private void populateChannels(int languageIndex, String deviceName) {
        populateChannels(languageIndex, channelCountOf(deviceName));
    }

    /**
     * The channel count to apply during a device re-scan, or zero for "leave the channel
     * list untouched": a device that is absent, unused, or momentarily unable to answer
     * must not reshape a configured channel selection — {@link #channelCountOf(String)}'s
     * stereo fallback would clamp a mixer channel down to Left/Right.
     */
    private int rescanChannelCount(String deviceName, List<String> presentDevices) {
        if (deviceName == null || SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(deviceName)
                || !presentDevices.contains(deviceName)) {
            return 0;
        }
        if (Host.isLinux()) {
            // Unlike channelCountOf, an unanswered query stays zero instead of becoming
            // a stereo guess
            return PulseAudioDevices.channelCount(settings.getFfmpegPath(), deviceName);
        }
        return channelCountOf(deviceName);
    }

    /**
     * The half of {@link #populateChannels(int, String)} that only touches the interface:
     * the channel count arrives precomputed, so a device re-scan can query it off the FX
     * thread and apply the answer here.
     */
    private void populateChannels(int languageIndex, int channelCount) {
        ComboBox<String> channelInput = inputAudioSourcesChannel[languageIndex];
        String previous = channelInput.getValue();
        List<String> channels = new ArrayList<>();
        if (channelCount > 2) {
            for (int channel = 1; channel <= channelCount; channel++) {
                channels.add(SettingsUtil.audioChannelName(channel));
            }
        } else {
            channels.add(SettingsUtil.AUDIO_CHANNEL_JOIN);
            channels.add(SettingsUtil.AUDIO_CHANNEL_LEFT);
            channels.add(SettingsUtil.AUDIO_CHANNEL_RIGHT);
        }
        if (channels.equals(channelInput.getItems())) {
            return;
        }
        channelInput.getItems().setAll(channels);
        if (previous != null && channels.contains(previous)) {
            channelInput.setValue(previous);
        } else if (previous != null && !previous.isBlank()) {
            // Moving between a stereo cable and a mixer: keep the same physical input
            channelInput.setValue(channels.get(Math.min(SettingsUtil.audioChannelIndex(previous), channels.size() - 1)));
        }
    }

    /**
     * Puts a saved channel onto a combo whose entries came from the device actually present.
     * A settings file written against stereo cables says Left or Right, which on a mixer means
     * its first and second inputs.
     */
    private void applyChannelValue(ComboBox<String> channelInput, String saved) {
        if (channelInput.getItems().isEmpty()) {
            channelInput.setValue(saved);
            return;
        }
        if (saved != null && !saved.isBlank() && channelInput.getItems().contains(saved)) {
            channelInput.setValue(saved);
            return;
        }
        int index = Math.min(SettingsUtil.audioChannelIndex(saved), channelInput.getItems().size() - 1);
        channelInput.setValue(channelInput.getItems().get(Math.max(index, 0)));
    }

    /** The number of inputs a capture device offers, or 2 when it cannot be determined. */
    private int channelCountOf(String deviceName) {
        if (deviceName == null || deviceName.isBlank() || SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(deviceName)) {
            return 2;
        }
        if (Host.isLinux()) {
            // Java Sound only sees the sound server's compatibility device, whose numbers are
            // what the server could convert to, not what the hardware has; PipeWire knows
            int channels = PulseAudioDevices.channelCount(settings.getFfmpegPath(), deviceName);
            return channels > 0 ? channels : 2;
        }
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            if (!mixerInfo.getName().equals(deviceName)) {
                continue;
            }
            int maximum = 0;
            for (Line.Info lineInfo : AudioSystem.getMixer(mixerInfo).getTargetLineInfo()) {
                if (!(lineInfo instanceof DataLine.Info dataLineInfo)) {
                    continue;
                }
                for (AudioFormat format : dataLineInfo.getFormats()) {
                    maximum = Math.max(maximum, format.getChannels());
                }
            }
            if (maximum > 0) {
                return maximum;
            }
        }
        return 2;
    }

    /**
     * Asks the selected camera what it can capture. AVFoundation refuses anything else;
     * Video4Linux2 silently converts, so asking keeps the choice honest there too.
     */
    private void populateVideoInputModes() {
        if (!Host.isMac() && !Host.isLinux()) {
            return;
        }
        String previous = inputVideoInputMode.getValue();
        List<String> modes = supportedVideoModes().stream()
                .map(AvFoundationDevices.CaptureMode::toString).toList();
        inputVideoInputMode.getItems().setAll(modes);
        if (previous != null && modes.contains(previous)) {
            inputVideoInputMode.setValue(previous);
        } else if (!modes.isEmpty()) {
            inputVideoInputMode.setValue(defaultVideoInputMode(modes));
        }
        // The camera also decides what the frames look like on the way in, and ffmpeg's own
        // default is one that cameras rarely offer, so the device is asked which it supports
        videoInputPixelFormat = Host.isLinux()
                ? V4l2Devices.bestPixelFormat(settings.getFfmpegPath(), inputVideoSource.getValue())
                : AvFoundationDevices.bestPixelFormat(settings.getFfmpegPath(),
                        inputVideoSource.getValue(),
                        AvFoundationDevices.CaptureMode.parse(inputVideoInputMode.getValue()));
    }

    private List<AvFoundationDevices.CaptureMode> supportedVideoModes() {
        if (Host.isLinux()) {
            return V4l2Devices.supportedModes(settings.getFfmpegPath(), inputVideoSource.getValue(),
                    parseIntOrDefault(inputFramePerSecond.getValue(), 30));
        }
        return AvFoundationDevices.supportedModes(settings.getFfmpegPath(), inputVideoSource.getValue());
    }

    private String defaultVideoInputMode(List<String> modes) {
        int frameRate = parseIntOrDefault(inputFramePerSecond.getValue(), 30);
        // Whatever the output type, everything is encoded at the one output resolution - the URL,
        // the recording and both together all come from setOutputResolution. This used to consult a
        // per-file resolution that had no items and no place in the window, so it was always null
        // and the switch below always fell through to 720.
        String resolution = inputSrtResolution.getValue();
        int height = switch (resolution == null ? "" : resolution) {
            case "hd480" -> 480;
            case "hd1080" -> 1080;
            default -> 720;
        };
        AvFoundationDevices.CaptureMode best = AvFoundationDevices.bestMode(
                modes.stream().map(AvFoundationDevices.CaptureMode::parse).filter(Objects::nonNull).toList(),
                frameRate, height * 16 / 9, height);
        return best != null ? best.toString() : modes.get(0);
    }

    private static int parseIntOrDefault(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /**
     * Checks the audio devices can actually be recorded from.
     * <p>
     * On macOS the application reads the device itself and sends the samples to ffmpeg, which
     * means the device has to be one Java Sound can open, and only one of them can be used: the
     * samples travel down ffmpeg's standard input and there is only one of those.
     */
    /**
     * The distinct audio devices the languages are reading from. Both platform checks below asked
     * this question in identical words, and so does the interface: the time needed to open a device
     * only means anything once there is more than one of them.
     */
    private Set<String> selectedAudioDevices() {
        Set<String> devices = new LinkedHashSet<>();
        for (ComboBox<String> audioInput : inputAudioSources) {
            String deviceName = audioInput.getValue();
            if (deviceName != null && !SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(deviceName) && !deviceName.isBlank()) {
                devices.add(deviceName);
            }
        }
        return devices;
    }

    private boolean checkAudioSampleRates() {
        if (Host.isLinux()) {
            return checkLinuxAudioSources();
        }
        if (!Host.isMac()) {
            return true;  // ffmpeg opens the devices itself through DirectShow
        }
        boolean result = true;
        Set<String> devices = selectedAudioDevices();
        for (String deviceName : devices) {
            if (AudioCaptureManager.findCaptureDevice(deviceName) == null) {
                appendToConsole("The audio device \"" + deviceName + "\" cannot be opened for recording.", ConsoleSeverity.ERROR);
                result = false;
            }
        }
        if (devices.size() > 1) {
            appendToConsole("All the languages have to come from a single audio device on this machine, but "
                    + devices.size() + " are selected: " + String.join(", ", devices)
                    + ". A mixer presents all of its inputs as one device, so choose the same one for every language"
                    + " and give each language its own channel.", ConsoleSeverity.ERROR);
            result = false;
        }
        return result;
    }

    /**
     * Checks the audio devices are still there before ffmpeg is asked to open them, which it
     * would only refuse later and more cryptically.
     */
    private boolean checkLinuxAudioSources() {
        boolean result = true;
        Set<String> devices = selectedAudioDevices();
        int multiChannelDevices = 0;
        for (String deviceName : devices) {
            if (!PulseAudioDevices.exists(settings.getFfmpegPath(), deviceName)) {
                appendToConsole("The audio device \"" + deviceName + "\" is not connected any more.", ConsoleSeverity.ERROR);
                result = false;
                continue;
            }
            if (PulseAudioDevices.channelCount(settings.getFfmpegPath(), deviceName) > 8) {
                multiChannelDevices++;
            }
        }
        // A device beyond eight channels is read natively and fed to ffmpeg over its one and
        // only standard input: see StreamRecorderRunnable
        if (multiChannelDevices > 1) {
            appendToConsole("Only one device with more than 8 channels can be used on this machine, but "
                    + multiChannelDevices + " are selected. A mixer presents all of its inputs as one device,"
                    + " so choose the same one for every language and give each language its own channel.", ConsoleSeverity.ERROR);
            result = false;
        }
        return result;
    }

    /** The stream start time in milliseconds, or {@link Long#MIN_VALUE} if it is not a number. */
    private static long parseStartMillis(String startTime) {
        try {
            // A long, not an int: on Linux the inputs are stamped with the time of day, and
            // those milliseconds have not fitted in an int since 1970
            return (long) (Double.parseDouble(startTime.trim()) * 1000);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * The whole look of the window follows the output type. It takes no arguments on purpose: the
     * mode is read from the same three flags {@link #waitingText()} and {@link #stateIcon} read, so
     * a caller cannot hand it a stale or half-updated answer - which is exactly what went wrong
     * while two listeners each passed their own new value in.
     */
    private void applyStyleOnOutputTypeChange() {
        if(isTheOutputFileAndUrl.get()) {
            updateSceneStyle(MODE_LIVESTREAM_AND_RECORD);
            primaryStage.setTitle(WINDOW_TITLE_PREFIX + " - Livestreaming and Recording the session");
        }
        else if(isTheOutputAFile.get()) {
            updateSceneStyle(MODE_RECORD);
            primaryStage.setTitle(WINDOW_TITLE_PREFIX + " - Recording the session");
        }
        else {
            updateSceneStyle(MODE_LIVESTREAM);
            primaryStage.setTitle(WINDOW_TITLE_PREFIX + " - Live stream the session");
        }
        setStatusText(false);
        primaryStage.getIcons().setAll(stateIcon(false));
    }

    /**
     * The message, the icon and the title all depend on the output type, and "Livestream And File"
     * also makes isTheOutputAFile true. Everything therefore reads the mode from here, so the three
     * modes cannot disagree between waiting, running and stopped.
     */
    /**
     * Puts the right words in the status bar for the state, in the length the window has room for.
     * One place, because the compact view says the same thing in three characters and two sources
     * of the same sentence drift apart.
     */
    private void setStatusText(boolean inProgress) {
        showingInProgressText = inProgress;
        refreshStatusText();
    }

    private void refreshStatusText() {
        currentInformationTextProperty.setValue(compactMode
                ? compactStatusText()
                : (showingInProgressText ? inProgressText() : waitingText()));
    }

    /** The same state in a handful of characters, for a bar a few hundred pixels wide. */
    private String compactStatusText() {
        String what = isTheOutputFileAndUrl.get() ? "LIVE + REC"
                : isTheOutputAFile.get() ? "REC" : "LIVE";
        return showingInProgressText ? what : "READY " + what;
    }

    private String waitingText() {
        if (isTheOutputFileAndUrl.get()) {
            return WAITING_TO_LIVESTREAM_AND_RECORD;
        }
        return isTheOutputAFile.get() ? WAITING_TO_RECORD : WAITING_TO_LIVESTREAM;
    }

    private String inProgressText() {
        if (isTheOutputFileAndUrl.get()) {
            return CURRENTLY_LIVESTREAMING_AND_RECORDING;
        }
        return isTheOutputAFile.get() ? CURRENTLY_RECORDING : CURRENTLY_LIVESTREAMING;
    }

    /** Writing to the local machine wins the icon, so recording and both share the recording one. */
    private Image stateIcon(boolean playing) {
        if (isTheOutputAFile.get()) {
            return playing ? iconRecordingPlaying : iconRecordingIdle;
        }
        return playing ? iconLiveStreamPlaying : iconLiveStreamIdle;
    }


    /**
     * The mode colours the whole window, so its class goes on the scene root: the status bar, the
     * three tab bodies, the advanced options and the footer are all descendants of it, and one
     * add/remove therefore repaints the lot. Everything between the root and the operator is made
     * see-through in the stylesheet, which is why nothing here reaches into the scroll pane's skin
     * any more - that loop only ever found the viewport and the scroll bars, so the colour stopped
     * wherever a control painted a background of its own, and the footer, a sibling of the scroll
     * pane, could never be reached at all.
     */
    private void updateSceneStyle(String modeStyleClass) {
        List<String> rootStyleClasses = scene.getRoot().getStyleClass();
        // All three, including the one being set: re-applying the current mode - every settings load
        // does - would otherwise add the same class again and again for the life of the session
        rootStyleClasses.removeAll(MODE_STYLE_CLASSES);
        rootStyleClasses.add(modeStyleClass);
    }

    // Updated applySettings() method in StreamingGUI.java
    private void applySettings() {
        Map<String, String> audioSettings = settings.getAudioSources();
        for (int i = 0; i < inputAudioSources.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            inputAudioSources[i].setValue(audioSettings.getOrDefault(languageName, "Not Used"));

            ColorPicker colorPicker = languageColorPickers.get(languageName);
            if (colorPicker != null) {
                String hexColor = settings.getLanguageColors().get(languageName);
                if (hexColor != null) {
                    colorPicker.setValue(Color.web(hexColor));
                } else {
                    // Set a default color if not found in settings
                    colorPicker.setValue(Color.GREY); // or Color.web("#808080") for a specific grey
                }
            }
        }

        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            applyChannelValue(inputAudioSourcesChannel[i], settings.getAudioSourcesChannel().getOrDefault(languageName, ""));
        }

        for (int i = 0; i < inputNoiseReductionValues.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            if(settings.getNoiseReductionLevel() != null) {
                inputNoiseReductionValues[i].setValue(settings.getNoiseReductionLevel().getOrDefault(languageName, "0"));
            }
        }

        for (int i = 0; i < inputAudioSources.length; i++) {
            if(Objects.equals(inputAudioSources[i].getValue(), "")) {
                inputAudioSources[i].setValue("Not Used");
                inputAudioSourcesChannel[i].setValue("Join");
            }
            if(Objects.equals(inputAudioSources[i].getValue(), "Not Used")) {
                inputAudioSourcesChannel[i].setValue("Join");
                inputAudioSourcesChannel[i].setDisable(true);
                inputNoiseReductionValues[i].setValue("0");
                inputNoiseReductionValues[i].setDisable(true);
            }
        }

        // Rest of the method remains unchanged...
        if (!settings.getVideoSource().isEmpty()) {
            inputVideoSource.setValue(settings.getVideoSource());
        }
        // Selecting the source has just refreshed the mode list, so restore the saved one on top
        if (!settings.getVideoInputMode().isEmpty()
                && inputVideoInputMode.getItems().contains(settings.getVideoInputMode())) {
            inputVideoInputMode.setValue(settings.getVideoInputMode());
        }
        inputVideoSourceBuffer.setValue(settings.getVideoBuffer());
        inputAudioSourceBuffer.setValue(settings.getAudioBuffer());
        inputVideoBitrate.setValue(settings.getVideoBitrate());
        inputSoundDelay.setText(settings.getDelay());
        inputPixelFormat.setValue(settings.getPixFormat());
        inputChooseBetweenUrlOrFile.setValue(settings.getOutputType());
        inputSrtResolution.setValue(settings.getSrtDef());
        // The saved encoder may belong to the other machine, NVENC needing a card no Mac has
        String savedEncoder = settings.getEncoder();
        // A file from before the preset choice says a bare "libx264": its first preset entry
        if (!inputEncoder.getItems().contains(savedEncoder)) {
            for (String option : inputEncoder.getItems()) {
                if (Host.encoderCodec(option).equals(savedEncoder)) {
                    savedEncoder = option;
                    break;
                }
            }
        }
        if (inputEncoder.getItems().contains(savedEncoder)) {
            inputEncoder.setValue(savedEncoder);
        } else if (!inputEncoder.getItems().isEmpty()) {
            inputEncoder.setValue(inputEncoder.getItems().get(0));
            if (!settings.getEncoder().isEmpty()) {
                appendToConsole("The encoder " + settings.getEncoder() + " is not available on this machine. Using "
                        + inputEncoder.getValue() + " instead.", ConsoleSeverity.WARNING);
            }
        }
        inputSrtURL.setText(settings.getSrtURL());
        inputSrtLatencyOverride.setSelected(settings.isSrtLatencyOverride());
        inputSrtLatencyMillis.setText(settings.getSrtLatencyMs());
        inputTimeNeededToOpenADevice.setText(settings.getTimeNeededToOpenADevice());
        inputOutputDirectory.setText(outputDirectoryForThisMachine());
        inputAudioBitrate.setValue(settings.getAudioBitrate());
        inputAudioSampleRate.setValue(settings.getAudioSampleRate());
        inputFramePerSecond.setValue(settings.getFps());
        inputCommRecording.setSelected(settings.isCommRecording());
        // Empty saved values keep the defaults the settings tab put in place
        if (!settings.getCommResolution().isEmpty()) inputCommResolution.setValue(settings.getCommResolution());
        if (!settings.getCommVideoBitrate().isEmpty()) inputCommVideoBitrate.setValue(settings.getCommVideoBitrate());
        if (!settings.getCommAudioBitrate().isEmpty()) inputCommAudioBitrate.setValue(settings.getCommAudioBitrate());
        inputCommDirectory.setText(settings.getCommDirectory());
    }

    /** The saved recording folder, or this machine's default when it points somewhere else. */
    private String outputDirectoryForThisMachine() {
        String saved = settings.getOutputDirectory();
        if (!saved.isEmpty() && new File(saved).isDirectory()) {
            return saved;
        }
        String fallback = Host.defaultOutputDirectory();
        return new File(fallback).isDirectory() ? fallback : saved;
    }

    // Updated saveSettings() method in StreamingGUI.java
    /**
     * The one writer of the settings file, whichever tab's Save was pressed. The order matters: the
     * sweep below keys the per-language maps by the names in Settings.LANGUAGES, which are still the
     * ones the window started with, so the Languages tab's renames have to be applied after it or
     * they would be written straight back.
     */
    private void saveSettings() {
        collectSettingsFromControls();
        if (languagesTab != null) {
            languagesTab.applyPendingLanguageEdits();
        }
        SettingsUtil.saveSettings(settings, "settings");
    }

    private void collectSettingsFromControls() {
        for (int i = 0; i < inputAudioSources.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            settings.getAudioSources().put(languageName, inputAudioSources[i].getValue());
        }

        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            settings.getAudioSourcesChannel().put(languageName, inputAudioSourcesChannel[i].getValue());
        }

        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            String languageName = Settings.LANGUAGES[i].name();
            settings.getNoiseReductionLevel().put(languageName, inputNoiseReductionValues[i].getValue());
        }

        // Rest of the method remains unchanged...
        settings.setVideoSource(inputVideoSource.getValue());
        settings.setVideoInputMode(inputVideoInputMode.getValue());
        settings.setVideoBitrate(inputVideoBitrate.getValue());
        settings.setAudioBuffer(inputAudioSourceBuffer.getValue());
        settings.setVideoBuffer(inputVideoSourceBuffer.getValue());
        settings.setDelay(inputSoundDelay.getText());
        settings.setPixFormat(inputPixelFormat.getValue());
        settings.setOutputType(inputChooseBetweenUrlOrFile.getValue());
        settings.setSrtDef(inputSrtResolution.getValue());
        settings.setEncoder(inputEncoder.getValue());
        settings.setSrtURL(inputSrtURL.getText());
        settings.setSrtLatencyOverride(inputSrtLatencyOverride.isSelected());
        settings.setSrtLatencyMs(inputSrtLatencyMillis.getText());
        settings.setTimeNeededToOpenADevice(inputTimeNeededToOpenADevice.getText());
        settings.setOutputDirectory(inputOutputDirectory.getText());
        settings.setAudioBitrate(inputAudioBitrate.getValue());
        settings.setAudioSampleRate(inputAudioSampleRate.getValue());
        settings.setFps(inputFramePerSecond.getValue());
        settings.setCommRecording(inputCommRecording.isSelected());
        settings.setCommResolution(inputCommResolution.getValue());
        settings.setCommVideoBitrate(inputCommVideoBitrate.getValue());
        settings.setCommAudioBitrate(inputCommAudioBitrate.getValue());
        settings.setCommDirectory(inputCommDirectory.getText());
        for (Map.Entry<String, ColorPicker> entry : languageColorPickers.entrySet()) {
            settings.getLanguageColors().put(entry.getKey(), entry.getValue().getValue().toString());
        }
    }

    /**
     * The strip under everything else: which build this is, when it was made, and a link
     * to the commit history that produced it — the operator's "what's new in this build".
     */
    private HBox buildFooter() {
        Label version = new Label(BuildInfo.versionLine());
        version.setStyle("-fx-font-size: 11px; -fx-text-fill: #777777;");
        Hyperlink whatsNew = new Hyperlink("What's new");
        whatsNew.setStyle("-fx-font-size: 11px;");
        whatsNew.setOnAction(e -> getHostServices().showDocument(BuildInfo.whatsNewUrl()));
        HBox footer = new HBox(8, version, whatsNew);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(2, 10, 4, 10));
        return footer;
    }

    private ScrollPane buildUI() {
        VBox root = new VBox();
        windowRoot = root;
        // The logo lives in the status bar: the title row it used to share with the application
        // name is gone, which gives its whole height back to the tabs
        ImageView logoView = new ImageView(new Image("https://kadampafestivals.org/wp-content/uploads/2024/01/New-NKT-IKBU-Logo-Kadampa-Blue.png"));
        logoView.setFitHeight(34);
        logoView.setFitWidth(34);
        logoView.setPreserveRatio(true);

        nowPlayingLabel.textProperty().bind(currentInformationTextProperty);
        // See repairWindowLayoutLater: anything that resizes inside this bar can strand the
        // window's layout, and every writer into it has to ask for the repair
        currentInformationTextProperty.addListener(
                (observable, oldText, newText) -> repairWindowLayoutLater());
        statusReadout.textProperty().addListener(
                (observable, oldText, newText) -> repairWindowLayoutLater());

        nowPlayingBox.getChildren().setAll(logoView, liveDot, nowPlayingLabel, statusReadout);
        nowPlayingBox.setSpacing(14);
        nowPlayingBox.setMinHeight(50);
        nowPlayingBox.setMaxHeight(50);
        nowPlayingBox.setMinWidth(WINDOW_WIDTH-5);
        nowPlayingBox.setAlignment(Pos.CENTER);

        // Subscribed once, for the life of the window: adding this on every Start left the previous
        // one behind, so after three sessions every ffmpeg line reached the console three times
        streamRecorder.setOutputListener(this::scheduleConsoleDrain);

        TabPane tabPane = new TabPane();
        tabPane.setPrefWidth(WINDOW_WIDTH-2);

        // Control and Console Tab
        controlConsoleTab = new Tab("Control and Console");
        controlConsoleTab.setClosable(false);
        controlConsoleTab.setContent(buildTabControlConsole());
        tabPane.getTabs().add(controlConsoleTab);

        // Language Settings Tab
        Tab settingTab = new Tab("Settings");
        settingTab.setClosable(false);
        // Its own scroll pane, like the console tab has: unfolding the advanced options makes this
        // tab taller than the window, and the tab would otherwise just clip what does not fit,
        // putting the last options and the Save button out of reach.
        ScrollPane settingsScrollPane = new ScrollPane(buildTabSettings());
        settingsScrollPane.setFitToWidth(true);
        settingsScrollPane.getStyleClass().add("settings-scroll-pane");
        settingTab.setContent(settingsScrollPane);
        tabPane.getTabs().add(settingTab);


        // Between Settings and Information on purpose: the settings are touched every session, the
        // language list once per festival, and the Information tab is reference material
        languagesTab = new LanguagesTab(settings, inputAudioSources, inputAudioSourcesChannel,
                streamRecorder.isAliveProperty(), this::saveSettings);
        Tab languageTab = new Tab("Languages");
        languageTab.setClosable(false);
        ScrollPane languagesScrollPane = new ScrollPane(languagesTab.buildContent());
        languagesScrollPane.setFitToWidth(true);
        languagesScrollPane.getStyleClass().add("settings-scroll-pane");
        languageTab.setContent(languagesScrollPane);
        tabPane.getTabs().add(languageTab);

        Tab infoTab = new Tab("Information");
        infoTab.setClosable(false);
        infoTab.setContent(buildTabInfo());
        tabPane.getTabs().add(infoTab);


        root.getChildren().addAll(nowPlayingBox,tabPane);
        // The tabs take whatever height the status bar leaves, instead of the window taking
        // whatever height the tabs ask for. That direction is what lets the settings tab's own
        // scroll pane do the scrolling when the advanced options are unfolded, rather than the
        // whole window growing taller than the screen underneath a stale scroll position.
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        ScrollPane mainScrollPane = new ScrollPane(root);
        // Let the content take the width of the viewport rather than its own preferred width:
        // the two are within a few pixels of each other, which was enough to raise a horizontal
        // scroll bar with nothing worth scrolling to
        mainScrollPane.setFitToWidth(true);
        // Same for the height, so this pane only takes over when the window is dragged smaller
        // than the console tab's fixed panel really needs
        mainScrollPane.setFitToHeight(true);
        return mainScrollPane;
    }


    public ComboBox<String>[] getInputAudioSources() {
        return inputAudioSources;
    }

    public Settings getSettings() {
        return settings;
    }
    private Node buildTabInfo() {
        VBox infoVBox = new VBox(5);
        infoVBox.setPadding(new Insets(20,10,10,10));
        Label introLabel = new Label("The following values will be generated and displayed upon pressing the Start Button\n\n");
        introLabel.setStyle("-fx-font-weight: bold;");
        infoVBox.getChildren().add(introLabel);

        infoVBox.getChildren().add(new Separator());

        Label ffmpegCommandLabel = new Label("Generated FFMpeg command:");
        ffmpegCommandLabel.setPadding(new Insets(20,0,0,0));
        ffmpegCommandLabel.setStyle("-fx-font-weight: bold;");
        infoVBox.getChildren().add(ffmpegCommandLabel);

        textAreaInfo.setWrapText(true);
        infoVBox.getChildren().add(textAreaInfo);

        Label castrPlayerURL = new Label("Caster player url (with languages):");
        castrPlayerURL.setPadding(new Insets(20,0,0,0));
        castrPlayerURL.setStyle("-fx-font-weight: bold;");
        infoVBox.getChildren().add(castrPlayerURL);
        playerURLTextField.setText("");
        infoVBox.getChildren().add(playerURLTextField);

        Label pidInfo = new Label("PID information:");
        pidInfo.setPadding(new Insets(20,0,0,0));
        pidInfo.setStyle("-fx-font-weight: bold;");
        infoVBox.getChildren().add(pidInfo);
        infoVBox.getChildren().add(videoPID);
        audioPidLabels.clear();
        for (int i = 2; i < Settings.LANGUAGES.length; i++) {
            Label audioPidLabel = new Label("");
            audioPidLabels.add(audioPidLabel);
            infoVBox.getChildren().add(audioPidLabel);
        }
        return infoVBox;
    }

    private void displayPIDInfo() {
        int pidVideo = StreamRecorderRunnable.VIDEO_PID;
        videoPID.setText("PID Video: " + pidVideo);
        for (int i = 2; i < Settings.LANGUAGES.length; i++) {
            String displayName = i == 2 ? Settings.LANGUAGES[i].name() : Settings.LANGUAGES[i].nativeName();
            // A language without a source becomes no stream, so it must not consume a PID here
            // either - it used to, and every language under it was listed one PID too high, which
            // only ever looked right because the unused ones happened to be last
            int track = SettingsUtil.audioTrackIndex(i, this::isLanguageUsed);
            audioPidLabels.get(i - 2).setText(track < 0
                    ? displayName + ": not streamed"
                    : "PID " + displayName + ": " + (pidVideo + 1 + track));
        }
    }

    /** Whether a language has an audio source, and so becomes a track of its own. */
    private boolean isLanguageUsed(int languageIndex) {
        return !SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(inputAudioSources[languageIndex].getValue());
    }

    /**
     * The compact view: the pulsing bar and the line ffmpeg is writing right now, and nothing else.
     *
     * <p>During a session the window shares the screen with OBS and a browser, and almost none of
     * it is being read - the operator wants to know that it is still live and still keeping up.
     * So this is the same status bar, moved rather than copied so the breathing animation, the live
     * dot and the health readout keep their one code path, with ffmpeg's self-refreshing status
     * line underneath it.
     *
     * <p>Deliberately no Stop button. One mistaken click on a floating window that sits above
     * everything else would end a festival stream; the whole interface is one click away.
     */
    private VBox buildCompactView() {
        compactStatusLine.getStyleClass().add("compact-status-line");
        // The two ends are the ones worth reading - frame= at the start, speed= at the end - so
        // what goes missing on a narrow window is taken out of the middle
        compactStatusLine.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        compactStatusLine.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compactStatusLine, Priority.ALWAYS);

        Button expand = new Button("⤢");
        expand.getStyleClass().add("preset-button");
        expand.setTooltip(new Tooltip("Back to the full window (Esc)"));
        expand.setOnAction(event -> setCompactMode(false));

        HBox statusLineRow = new HBox(6, compactStatusLine, expand);
        statusLineRow.setAlignment(Pos.CENTER_LEFT);
        statusLineRow.setPadding(new Insets(4, 6, 4, 6));

        compactStatusRow = statusLineRow;
        VBox compact = new VBox(statusLineRow);
        // Anywhere on it takes the window back, for an operator who has not found the button
        compact.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                setCompactMode(false);
            }
        });
        return compact;
    }

    /**
     * Shrinks the window to the compact view, or puts the whole interface back.
     *
     * <p>The scene root does not change: the mode colour and its style classes live on it, so only
     * what sits inside it is swapped. The full interface is detached rather than thrown away, and
     * the console goes on collecting every line ffmpeg writes while it is out of sight.
     */
    private void setCompactMode(boolean compact) {
        if (compact == compactMode) {
            return;
        }
        compactMode = compact;
        if (compact) {
            fullWidth = primaryStage.getWidth();
            fullHeight = primaryStage.getHeight();
            wasAlwaysOnTop = primaryStage.isAlwaysOnTop();

            windowRoot.getChildren().remove(nowPlayingBox);
            nowPlayingBox.setMinWidth(0);
            compactView.getChildren().setAll(nowPlayingBox, compactStatusRow);
            shell.setCenter(compactView);
            shell.setBottom(null);
            // The point of it: OBS and the browser are in front of the window otherwise
            primaryStage.setAlwaysOnTop(true);
            primaryStage.setWidth(COMPACT_WINDOW_WIDTH);
            primaryStage.setHeight(COMPACT_WINDOW_HEIGHT);
        } else {
            compactView.getChildren().remove(nowPlayingBox);
            nowPlayingBox.setMinWidth(WINDOW_WIDTH - 5);
            windowRoot.getChildren().add(0, nowPlayingBox);
            shell.setCenter(fullContent);
            shell.setBottom(footer);
            primaryStage.setAlwaysOnTop(wasAlwaysOnTop);
            primaryStage.setWidth(fullWidth);
            primaryStage.setHeight(fullHeight);
        }
        refreshStatusText();
        compactButton.setText(compact ? "Full window" : "Compact view");
    }

    /**
     * Asks, on the following frame, for the window to lay itself out.
     *
     * <p>A workaround, and named as one. Something in this window - the status bar resizing is the
     * one occasion pinned down - raises a layout request at a moment when an ancestor is already
     * laying itself out, and JavaFX drops it. The node is left marked NEEDS_LAYOUT and never
     * registered with the scene, and because the walk that registers a dirty branch stops at the
     * first ancestor already marked dirty, <em>every</em> branch underneath is stranded behind it -
     * for the life of the window, since nothing ever clears the flag. It has surfaced three times
     * now, each time somewhere unrelated to the cause: the advanced options unfolding onto nothing,
     * the console drawing all of its lines on top of the first, and the health readout never
     * appearing in the status bar. Asking again on a later frame gets the pass performed, because
     * by then nothing is mid-layout.
     *
     * <p>It is asked for on the outermost box rather than on whatever changed: that is the node
     * that gets stuck, and laying it out takes every stranded branch under it with it. Coalesced,
     * so a burst of console lines costs one repair rather than one each.
     */
    private void repairWindowLayoutLater() {
        if (windowRoot == null || !layoutRepairQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            layoutRepairQueued.set(false);
            windowRoot.requestLayout();
        });
    }

    private Node buildTabControlConsole() {
        consoleOutputBox.getStyleClass().add("console-lines");
        consoleOutputScrollPane = new ScrollPane(consoleOutputBox);
        consoleOutputScrollPane.setMinSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-300);
        consoleOutputScrollPane.setMaxSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-300);
        consoleOutputScrollPane.setFitToWidth(true);
        consoleOutputScrollPane.getStyleClass().add("console-scroll-pane");
        installConsoleSelection();

        // Any movement the console did not make itself is the operator reading back through the log,
        // so new lines stop dragging the view down until they scroll to the bottom again
        consoleOutputScrollPane.vvalueProperty().addListener((observable, oldValue, newValue) -> {
            if (!scrollingConsoleToTail) {
                followingConsoleTail.set(newValue.doubleValue() >= 1.0 - CONSOLE_AT_BOTTOM_EPSILON);
            }
        });

        // Scrolling up to read something stops the log dragging the view back down - and then this
        // says so, and offers the way back. Without it, an operator who has scrolled up has no sign
        // that the console is still filling underneath them.
        Button jumpToLatest = new Button("↓ Jump to latest");
        jumpToLatest.getStyleClass().add("jump-to-latest");
        jumpToLatest.setOnAction(event -> {
            followingConsoleTail.set(true);
            scrollConsoleToTail();
        });
        jumpToLatest.visibleProperty().bind(followingConsoleTail.not());
        jumpToLatest.managedProperty().bind(jumpToLatest.visibleProperty());
        StackPane consoleStack = new StackPane(consoleOutputScrollPane, jumpToLatest);
        StackPane.setAlignment(jumpToLatest, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(jumpToLatest, new Insets(0, 22, 10, 0));

        Label consoleLabel = new Label("Console Output");
        consoleLabel.setStyle("-fx-font-weight: bold;");
        VBox consoleBox = new VBox(10, consoleLabel, consoleStack);
        // The monitor publishes on the FX thread. The event's own value is what matters:
        // re-reading the property here used to mishandle fast stop/start sequences.
        streamRecorder.isAliveProperty().addListener((observable, wasAlive, isAlive) -> {
            if (isAlive) {
                // A reset scheduled by a previous stop must not fire on this new stream
                if (pendingIdleReset != null) {
                    pendingIdleReset.stop();
                    pendingIdleReset = null;
                }
                startButton.setDisable(true);
                setStatusText(true);
                blinkingTimeLine.play(); // Start the animation
                if (!primaryStage.isFocused()) {
                    WindowAttention.request(WINDOW_TITLE_PREFIX);
                }
            }
            else {
                if (streamRecorder.wasStopRequested()) {
                    WindowAttention.clear(WINDOW_TITLE_PREFIX);
                } else {
                    // ffmpeg died on its own - a lost SRT connection kills the whole process
                    // on purpose, recordings included, so the operator can relaunch at once.
                    // ERROR is what raises the alarm sound and the orange bar; the message used
                    // to have to contain the word "error" to be heard, which meant its wording
                    // was load-bearing. The attention flag stays up until the window is focused.
                    appendToConsole("Stream lost - ffmpeg exited with error code "
                            + crashExitCode() + ". All recording has stopped too;"
                            + " press Start to relaunch.", ConsoleSeverity.ERROR);
                }
                //We wait for 3 seconds to give the time to the thread to end properly
                //(in the streamRecorder, we also wait 3s for the process to end before destroying it forcefully
                pendingIdleReset = new PauseTransition(Duration.seconds(3));
                pendingIdleReset.setOnFinished(event -> {
                    pendingIdleReset = null;
                    reinitialiseGraphicElements();
                });
                pendingIdleReset.play();
            }
        });

        // Create the VU Meters button
        Button showVUMetersButton = new Button("Level Meters");
        showVUMetersButton.getStyleClass().add("event-button");
        showVUMetersButton.getStyleClass().add("primary-button"); // Light blue style
        showVUMetersButton.setOnAction(event -> {
            if (vuMeterPanel.isShowing()) {
                vuMeterPanel.toFront();
            } else {
                vuMeterPanel.show();
            }
        });

        compactButton = new Button("Compact view");
        compactButton.getStyleClass().addAll("event-button", "secondary-button");
        compactButton.setTooltip(new Tooltip("""
                Shrinks the window to the pulsing bar and the line ffmpeg is writing, and keeps it
                above the other windows - so it can sit in a corner while you work in OBS.

                Esc, the arrows button or a double click brings the whole window back, and anything
                that goes wrong brings it back by itself."""));
        compactButton.setOnAction(event -> setCompactMode(!compactMode));

        // Five buttons now, so they sit closer together than the four used to
        HBox buttonBox = new HBox(30, startButton, stopButton, clearOutputButton, showVUMetersButton, compactButton);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(20, 0, 20, 0));
        consoleBox.setPadding(new Insets(0,0,0,10));
        return new VBox(10, buttonBox,consoleBox);
    }

    /** Repaints the bar at the current point of the breathing cycle, in whichever colour the state
     *  is in. It never drops below 62% brightness, so the bar always reads as green or orange. */
    private void paintStatusBar() {
        Color shade = barBaseColor.deriveColor(0, 1, 0.62 + 0.38 * barPulse.get(), 1);
        nowPlayingBox.setStyle("-fx-background-color: " + toHexString(shade) + ";");
    }

    /**
     * Called at both ends of the breathing cycle. An ffmpeg error turns the bar orange, which is
     * held for a few seconds so that even a single one is seen pulsing before the bar breathes
     * green again. The taskbar icon keeps alternating, to stay noticeable when minimised.
     *
     * <p>A degraded picture is the exception: those faults last until the stream is restarted, so
     * the bar stays orange rather than announcing a recovery that has not happened.
     */
    private void onPulse(boolean atFullBrightness) {
        primaryStage.getIcons().setAll(stateIcon(atFullBrightness));
        boolean stillDegraded = videoFaultLatched || streamHealth.level() != StreamHealth.Level.OK;
        if (!stillDegraded && System.currentTimeMillis() - lastErrorMillis > ERROR_COLOUR_HOLD_MS) {
            barBaseColor = LIVE_GREEN;
        }
    }

    private static String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /** The exit code of the ffmpeg that just died, readable because the monitor saw it die. */
    private String crashExitCode() {
        try {
            return String.valueOf(streamRecorder.getProcess().exitValue());
        } catch (RuntimeException e) {
            // The process never started, or is somehow still winding down
            return "unknown";
        }
    }

    private void reinitialiseGraphicElements() {
        stopButton.setGraphic(stopPath);
        setStatusText(false);
        stopButton.setDisable(true);
        blinkingTimeLine.stop();
        // An error arriving as the stream died must not tint the next run orange
        barBaseColor = LIVE_GREEN;
        nowPlayingBox.setStyle("-fx-background-color: -red-color;");
        // Only reached from a stream-ended event (a new start cancels the pending reset)
        startButton.setDisable(false);
        primaryStage.getIcons().setAll(stateIcon(false));
    }

    private Node buildTabSettings() {
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.setPadding(new Insets(10));
        inputGrid.setMaxWidth(WINDOW_WIDTH-10);
        GridPane inputGrid2 = new GridPane();
        inputGrid2.setHgap(10);
        inputGrid2.setVgap(10);
        inputGrid2.setPadding(new Insets(10));

        ColumnConstraints col_1 = new ColumnConstraints();
        col_1.setPercentWidth(15);
        ColumnConstraints col_2 = new ColumnConstraints();
        col_2.setPercentWidth(57);
        // The device combo only needs 450px of the column above, so the 3% goes to the two columns
        // on the right, which have to fit the noise reduction presets on the video input row
        ColumnConstraints col_3 = new ColumnConstraints();
        col_3.setPercentWidth(18);
        ColumnConstraints col_4 = new ColumnConstraints();
        col_4.setPercentWidth(10);

        inputGrid.getColumnConstraints().addAll(col_1, col_2, col_3,col_4);

        int row = 0;

        Label videoInputinfoLabel = new Label("?");
        videoInputinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip = new Tooltip("Choose the video input device (Either OBS Virtual Camera or another camera).");
        Tooltip.install(videoInputinfoLabel, tooltip);
        tooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip.getStyleClass().add("tooltip");
        Label videoInputLabel = new Label("Video Input:");
        videoInputLabel.setPrefWidth(LABEL_PREF_WIDTH);
        // Create an HBox to hold both labels
        HBox videoInputLabelHBox = new HBox(1,videoInputLabel, videoInputinfoLabel);
        videoInputLabelHBox.setAlignment(Pos.CENTER_LEFT);
        inputGrid.add(videoInputLabelHBox, 0, row);
        // A quiet way to pick up a sound card or capture card plugged in after launch,
        // without restarting; locked while a stream runs, like the Start button is
        Button refreshDevicesButton = new Button("↻");
        Tooltip refreshTooltip = new Tooltip("Re-scan the audio and video devices.\n"
                + "Use it when a device was plugged in after the program started.");
        refreshTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
        refreshTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
        refreshTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        refreshTooltip.getStyleClass().add("tooltip");
        refreshDevicesButton.setTooltip(refreshTooltip);
        refreshDevicesButton.disableProperty().bind(startButton.disabledProperty().or(devicesRefreshing));
        refreshDevicesButton.setOnAction(e -> refreshDevices());
        if (Host.isMac() || Host.isLinux()) {
            // AVFoundation only opens a mode the camera advertised, so it has to be chosen here;
            // v4l2 has modes too and the same virtual-camera behaviour, so it gets the same combo
            inputVideoSource.setPrefWidth(320);
            inputVideoInputMode.setPrefWidth(125);
            Tooltip modeTooltip = new Tooltip("The capture mode of the video device (size and frames per second).\n" +
                    "Only the modes the device reports can be used. A virtual camera lists its modes\n" +
                    "only while the application providing it is running.");
            modeTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
            modeTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
            modeTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
            modeTooltip.getStyleClass().add("tooltip");
            inputVideoInputMode.setTooltip(modeTooltip);
            inputGrid.add(new HBox(5, inputVideoSource, inputVideoInputMode, refreshDevicesButton), 1, row);
        } else {
            inputGrid.add(new HBox(5, inputVideoSource, refreshDevicesButton), 1, row);
            inputVideoSource.setPrefWidth(450);
        }
        HBox noisePresetBox = buildNoiseReductionPresets();
        inputGrid.add(noisePresetBox, 2, row);
        GridPane.setColumnSpan(noisePresetBox, 2);

        row++;
        //If it's empty, we select the first element
        if((inputVideoSource.getValue()==null || inputVideoSource.getValue().isEmpty()) && !inputVideoSource.getItems().isEmpty()) inputVideoSource.setValue(inputVideoSource.getItems().get(0));
        populateVideoInputModes();
        inputVideoSource.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> populateVideoInputModes());

        addLanguageRow(inputGrid, row, Settings.LANGUAGES[0].name() + ":", inputAudioSources[0], inputAudioSourcesChannel[0],null,Settings.LANGUAGES[0].name());
        row++;
        addLanguageRow(inputGrid, row, Settings.LANGUAGES[1].name(), inputAudioSources[1], inputAudioSourcesChannel[1],null, Settings.LANGUAGES[1].name());
        row++;
        Separator separator = new Separator();
        separator.setPrefWidth(WINDOW_WIDTH-50);
        inputGrid.add(separator, 0, row);
        GridPane.setColumnSpan(separator,2);
        Label noiseReductionInfoLabel = new Label("?");
        noiseReductionInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip toolti = new Tooltip("The number of iteration of the noise reduction filter.\n" +
                "The models ship with the application and are used from " + NoiseModels.modelFile(NoiseModels.SPEECH_MODEL).getParent() + "\n" +
                "The original files are downloadable here https://github.com/GregorR/rnnoise-nu");
        Tooltip.install(noiseReductionInfoLabel, toolti);
        toolti.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        toolti.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        toolti.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        toolti.getStyleClass().add("tooltip");
        Label noiseReductionLabel = new Label("Noise reduction:");
        // Create an HBox to hold both labels
        HBox noiseReductionLabelHBox = new HBox(1,noiseReductionLabel,noiseReductionInfoLabel);  // 5 is the spacing between the labels
        inputGrid.add(noiseReductionLabelHBox, 3, row);
        row++;
        for (int i = 2; i < Settings.LANGUAGES.length; i++) {
            addLanguageRow(inputGrid, row, Settings.LANGUAGES[i].name() + ":", inputAudioSources[i], inputAudioSourcesChannel[i], inputNoiseReductionValues[i], Settings.LANGUAGES[i].name());
            row++;
        }

        int comboWith = 100;
        inputGrid2.getColumnConstraints().addAll(outputColumnConstraints());

        row = 0;
        Label chooseOutputTypeLabelInfo = new Label("?");
        Tooltip tooltipA = new Tooltip("Choose if you want to livestream or either record in a file on the computer.");
        Tooltip.install(chooseOutputTypeLabelInfo, tooltipA);
        tooltipA.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipA.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipA.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipA.getStyleClass().add("tooltip");
        Label chooseOutputTypeLabel = new Label("Output type:");
        // Create an HBox to hold both labels
        HBox outPutTypeLabelHBox = new HBox(5);  // 5 is the spacing between the labels
        // No spacer before the combo any more: it was aligning this row with fields that have since
        // left it, and it took enough of the two columns to truncate the label to "Outp..."
        outPutTypeLabelHBox.getChildren().addAll(chooseOutputTypeLabel,chooseOutputTypeLabelInfo, inputChooseBetweenUrlOrFile);
        inputGrid2.add(outPutTypeLabelHBox, 0, row);
        GridPane.setColumnSpan(outPutTypeLabelHBox, 2);

        Label timeNeededinfoLabel = new Label("?");
        timeNeededinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipTimeNeededOutput = new Tooltip("""
            The time needed to open an audio device. This is important because each audio device takes time to open so it it async the audios.
            This parameter is used to readjust the sync. To know this value, in the ffmeg output, look for the start value for device 1 and for device 2, and look for the difference.
             Usually, it's around 650ms""");
        Tooltip.install(timeNeededinfoLabel, tooltipTimeNeededOutput);
        tooltipTimeNeededOutput.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipTimeNeededOutput.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipTimeNeededOutput.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipTimeNeededOutput.getStyleClass().add("tooltip");
        Label timeNeededLabel = new Label("Time needed to open a device (in ms):");
        HBox outputTimeNeededLabelHBox = new HBox(5);  // 5 is the spacing between the labels
        // The Region that used to sit here was sized by two lines that both addressed the spacer of
        // the row above, so it was always zero wide; the row fits as it is and does not want the 54px
        inputTimeNeededToOpenADevice.setMaxWidth(50);
        outputTimeNeededLabelHBox.getChildren().addAll(timeNeededLabel,timeNeededinfoLabel, inputTimeNeededToOpenADevice);
        inputGrid2.add(outputTimeNeededLabelHBox, 2, row);
        GridPane.setColumnSpan(outputTimeNeededLabelHBox, 2);
        // Shown only from the second distinct device onwards - the j>1 test in the recorder is the
        // only place this value is read. Managed as well as visible, or the row keeps its height.
        outputTimeNeededLabelHBox.visibleProperty().bind(severalAudioDevices);
        outputTimeNeededLabelHBox.managedProperty().bind(outputTimeNeededLabelHBox.visibleProperty());

        row++;

        Label outputUrlinfoLabel = new Label("?");
        outputUrlinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipOutput = new Tooltip("Choose the output, either a srt stream copied and paste from the streaming platform, or a file. The SRT protocol support multiaudio track, but the RTMP protocol does not");
        Tooltip.install(outputUrlinfoLabel, tooltipOutput);
        tooltipOutput.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipOutput.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipOutput.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipOutput.getStyleClass().add("tooltip");
        Label outputUrlLabel = new Label("Streaming url:");
        // Create an HBox to hold both labels
        outputUrlHBox.setSpacing(1);
        Region space = new Region();
        space.setMinWidth(50);
        space.setMaxWidth(50);
        // Takes whatever room is left instead of a fixed width, so the row never forces the tab
        // wider than the window and no horizontal scroll bar is needed
        inputSrtURL.setMinWidth(400);
        inputSrtURL.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(inputSrtURL, Priority.ALWAYS);
        outputUrlHBox.getChildren().setAll(outputUrlLabel,outputUrlinfoLabel,space, inputSrtURL);

        inputGrid2.add(outputUrlHBox, 0, row);
        GridPane.setColumnSpan(outputUrlHBox, 6);
        if(inputSrtURL.getText()==null) inputSrtURL.setText("");
        row++;

        // The livestream's latency, on its own row under the URL it modifies: the row above has no
        // width left to give - see the note on inputSrtURL - and it is shown and hidden with it,
        // being meaningless when nothing is streamed.
        Label srtLatencyInfoLabel = new Label("?");
        srtLatencyInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip srtLatencyTooltip = new Tooltip("""
            Replaces the latency the streaming platform's URL asks for, without editing the URL.
            Left unticked, the URL is handed to ffmpeg exactly as it was pasted.

            This field is in MILLISECONDS. The latency= inside the URL is in microseconds, so 2000
            here is written into the command as latency=2000000 - which is what the festival's URL
            already carries. More latency gives SRT more room to re-send lost packets over a poor
            connection, and costs that much delay; less gets the picture out sooner and forgives less.

            The URL in the settings is never rewritten. Only the command is, and the Information tab
            shows that command in full.""");
        Tooltip.install(srtLatencyInfoLabel, srtLatencyTooltip);
        srtLatencyTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
        srtLatencyTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
        srtLatencyTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        srtLatencyTooltip.getStyleClass().add("tooltip");
        Region srtLatencySpace = new Region();
        srtLatencySpace.setMinWidth(50);
        srtLatencySpace.setMaxWidth(50);
        srtLatencyHBox.setSpacing(6);
        srtLatencyHBox.setAlignment(Pos.CENTER_LEFT);
        srtLatencyHBox.getChildren().setAll(new Label("Stream latency:"), srtLatencyInfoLabel,
                srtLatencySpace, inputSrtLatencyOverride, inputSrtLatencyMillis, new Label("ms"));
        inputGrid2.add(srtLatencyHBox, 0, row);
        GridPane.setColumnSpan(srtLatencyHBox, 6);
        row++;

        Label outputDirectoryinfoLabel = new Label("?");
        outputDirectoryinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipOutputDirectory = new Tooltip("Choose the output directory, the files will be automatically named based on the date time of the start");
        Tooltip.install(outputDirectoryinfoLabel, tooltipOutputDirectory);
        tooltipOutputDirectory.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipOutputDirectory.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipOutputDirectory.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipOutputDirectory.getStyleClass().add("tooltip");

        Label outputFileLabel = new Label("Output directory:");
        // Create an HBox to hold both labels
        outputFileHBox.setSpacing(1);
        Button pickDirectoryButton = new Button("Choose Directory");
        pickDirectoryButton.setOnAction(event -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Select Directory");

            // Show directory chooser dialog
            File selectedDirectory = directoryChooser.showDialog(scene.getWindow());
            if (selectedDirectory != null) {
                inputOutputDirectory.setText(selectedDirectory.getAbsolutePath());
            }
        });
        Region spacer = new Region();
        spacer.setMinWidth(32);
        spacer.setMaxWidth(32);
        inputOutputDirectory.setMinWidth(400);
        inputOutputDirectory.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(inputOutputDirectory, Priority.ALWAYS);
        Region spacer2 = new Region();
        spacer2.setMinWidth(20);
        spacer2.setMaxWidth(20);
        outputFileHBox.getChildren().setAll(outputFileLabel,outputDirectoryinfoLabel,spacer, inputOutputDirectory,spacer2,pickDirectoryButton);
        inputGrid2.add(outputFileHBox, 0, row);

        if(inputOutputDirectory.getText()==null) inputOutputDirectory.setText("");
        GridPane.setColumnSpan(outputFileHBox, 6);

        row++;
        Separator verticalSeparator = new Separator();
        inputGrid2.add(verticalSeparator,0,row);
        GridPane.setColumnSpan(verticalSeparator, 6);

        // These are set once for a given venue and only get in the way of the settings used every
        // session, so they get their own grid and fold away behind the header below
        GridPane advancedGrid = new GridPane();
        advancedGrid.setHgap(10);
        advancedGrid.setVgap(10);
        advancedGrid.setPadding(new Insets(10));
        advancedGrid.getColumnConstraints().addAll(outputColumnConstraints());
        row = 0;

        Label delayinfoLabel = new Label("?");
        delayinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipDelay = new Tooltip("The delay of the audio in ms to synchronise with the video");
        Tooltip.install(delayinfoLabel, tooltipDelay);
        tooltipDelay.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipDelay.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipDelay.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipDelay.getStyleClass().add("tooltip");
        Label delayLabel = new Label("Delay in ms:");
        // Create an HBox to hold both labels
        HBox delayHBox = new HBox(1,delayLabel,delayinfoLabel);
        advancedGrid.add(delayHBox, 0, row);
        if(inputSoundDelay.getText()==null || inputSoundDelay.getText().isEmpty()) inputSoundDelay.setText("0");
        advancedGrid.add(inputSoundDelay, 1, row);
        inputSoundDelay.setMaxWidth(comboWith);

        Label pixelFormatInfoLabel = new Label("?");
        pixelFormatInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip4 = new Tooltip("While encoding a video, selecting the correct pixel format is essential when streaming to a platform because it impacts the video stream's compatibility, quality, and performance.\n The widely supported format is yuv420p. Using an incorrect pixel format can prevent the streaming platform from playing the video properly.");
        Tooltip.install(pixelFormatInfoLabel, tooltip4);
        tooltip4.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip4.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip4.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip4.getStyleClass().add("tooltip");
        Label pixelFormatlLabel = new Label("Pixel format:");
        // Create an HBox to hold both labels
        HBox audioChannelLabelHBox2 = new HBox(1,pixelFormatlLabel,pixelFormatInfoLabel);
        advancedGrid.add(audioChannelLabelHBox2, 2, row);

        //If it's empty, we select the first element
        if(inputPixelFormat.getValue()==null || inputPixelFormat.getValue().isEmpty()) inputPixelFormat.setValue(inputPixelFormat.getItems().get(0));
        advancedGrid.add(inputPixelFormat, 3, row);
        inputPixelFormat.setPrefWidth(comboWith);

        Label outputResInfoLabel = new Label("?");
        outputResInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip5= new Tooltip("""
                The parameters hd480, hd720, and hd1080 are shorthand for setting standard high-definition (HD) resolutions during video encoding:

                hd480: Configures the video to 852x480 pixels, offering a resolution that is higher than standard definition but lower than HD, suitable for lower bandwidth or smaller screens.

                hd720: Sets the video resolution to 1280x720 pixels, providing HD quality, ideal for streaming and viewing on HD displays without consuming too much bandwidth.

                hd1080: Adjusts the video to 1920x1080 pixels, delivering Full HD resolution, perfect for high-quality streaming and playback on larger screens with a higher level of detail.

                IMPORTANT: Streaming in hd1080 uses twice the data compared to hd720. The cost of the streaming platform is directly related to the total bandwidth consumed by viewers.
                If you plan to stream in hd1080, please confirm with the treasurer to ensure it fits within the budget.""");
        Tooltip.install(outputResInfoLabel, tooltip5);
        tooltip5.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip5.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip5.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip5.getStyleClass().add("tooltip");
        Label outputReslLabel = new Label("Output resolution:");
        // Create an HBox to hold both labels
        HBox outputResLabelHBox = new HBox(1,outputReslLabel,outputResInfoLabel);
        advancedGrid.add(outputResLabelHBox, 4, row);
        if(inputSrtResolution.getValue()==null || inputSrtResolution.getValue().isEmpty()) inputSrtResolution.setValue(inputSrtResolution.getItems().get(0));
        advancedGrid.add(inputSrtResolution, 5, row);
        inputSrtResolution.setPrefWidth(comboWith);
        row++;

        Label audioBitrateInfoLabel = new Label("?");
        audioBitrateInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip7= new Tooltip("""
                128k Audio Bitrate: This setting configures the audio bitrate to 128 kilobits per second (kbps) during encoding.\s
                It offers a reasonable balance between audio quality and file size, suitable for most standard audio playback scenarios, such as online streaming or casual listening.

                256k Audio Bitrate: This option sets the audio bitrate to 256 kbps, providing higher audio quality compared to 128k.
                 It's ideal for scenarios where audio fidelity is crucial, such as professional music streaming, podcasts, or audio recordings, but it results in larger file sizes compared to lower bitrate options.

                These settings will apply to all audio tracks. If there are multiple tracks, it will significantly increase the data usage and bandwidth consumed by viewers on the streaming platform, which forms the basis for invoicing""");
        Tooltip.install(audioBitrateInfoLabel, tooltip7);
        tooltip7.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip7.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip7.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip7.getStyleClass().add("tooltip");
        Label audioBitrateLabel = new Label("Audio bitrate:");
        // Create an HBox to hold both labels
        HBox audioBitrateLabelHBox = new HBox(1,audioBitrateLabel,audioBitrateInfoLabel);
        advancedGrid.add(audioBitrateLabelHBox, 0, row);
        //If it's empty, we select the first element
        if(inputAudioBitrate.getValue()==null || inputAudioBitrate.getValue().isEmpty()) inputAudioBitrate.setValue(inputAudioBitrate.getItems().get(0));
        advancedGrid.add(inputAudioBitrate, 1, row);
        inputAudioBitrate.setPrefWidth(comboWith);


        Label codecInfoLabel = new Label("?");
        codecInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip6= new Tooltip("""
                libx264 is a popular software-based H.264 encoder known for high-quality encoding. It supports the yuv420p pixel format and relies more on CPU resources rather than GPU.

                On the other hand, h264_nvenc leverages NVIDIA's hardware acceleration through the NVENC API for accelerated H.264 encoding. It's ideal for users with NVIDIA GPUs, ensuring efficient encoding while also supporting the yuv420p pixel format.""");
        Tooltip.install(codecInfoLabel, tooltip6);
        tooltip6.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip6.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip6.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip6.getStyleClass().add("tooltip");
        Label codecLabel = new Label("Codec:");
        // Create an HBox to hold both labels
        HBox codecLabelHBox = new HBox(1,codecLabel,codecInfoLabel);
        if(inputEncoder.getValue()==null || inputEncoder.getValue().isEmpty()) inputEncoder.setValue(inputEncoder.getItems().get(0));
        advancedGrid.add(codecLabelHBox, 2, row);
        inputEncoder.setPrefWidth(comboWith);
        advancedGrid.add(inputEncoder, 3, row);

        Label fpsInfoLabel = new Label("?");
        fpsInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip8= new Tooltip("The \"frames per second\" (fps) parameter in FFmpeg specifies the number of individual frames displayed or processed per second in a video.\nIt determines the smoothness and speed of motion in the video. A higher fps value results in smoother motion but may require more processing power and bandwidth, \npotentially impacting streaming performance by increasing the computational load and data transmission requirements.\nTherefore, while higher fps can enhance visual quality, it may also necessitate more robust hardware and network resources to maintain smooth streaming.");
        Tooltip.install(fpsInfoLabel, tooltip8);
        tooltip8.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip8.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip8.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip8.getStyleClass().add("tooltip");
        Label fpsLabel = new Label("Frame per second:");
        // Create an HBox to hold both labels
        HBox fpsLabelHBox = new HBox(1,fpsLabel,fpsInfoLabel);
        advancedGrid.add(fpsLabelHBox, 4, row);
        advancedGrid.add(inputFramePerSecond, 5, row);
        //If it's empty, we select the first element
        if(inputFramePerSecond.getValue()==null || inputFramePerSecond.getValue().isEmpty()) inputFramePerSecond.setValue(inputFramePerSecond.getItems().get(0));
        inputFramePerSecond.setPrefWidth(comboWith);
        row++;

        Label videoBitrateInfoLabel = new Label("?");
        videoBitrateInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip toolti8= new Tooltip("""
                The video bitrate parameter determines the amount of data allocated to encode each second of video footage. It directly affects the quality and file size of the resulting video file.
                 Higher bitrate values generally result in better visual quality but also produce larger file sizes.

                Recommended Bitrate Values:
                1/ hd480p (852x480 pixels): Recommended Bitrate: 1000-2500 kbps
                This range provides a balance between video quality and file size suitable for streaming or playback on smaller screens.

                2/ hd720p (1280x720 pixels): Recommended Bitrate: 2500-5000 kbps
                Higher resolution necessitates a higher bitrate to maintain quality. This range is suitable for HD streaming and playback on various devices.

                3/ hd1080p (1920x1080 pixels): Recommended Bitrate: 5000-8000 kbps
                Full HD resolution demands a higher bitrate for optimal quality. This range is suitable for high-quality streaming and playback on larger screens.""");
        Tooltip.install(videoBitrateInfoLabel, toolti8);
        toolti8.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        toolti8.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        toolti8.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        toolti8.getStyleClass().add("tooltip");
        Label videoBitrateLabel = new Label("Video bitrate:");
        // Create an HBox to hold both labels
        HBox videoBitrateLabelHBox = new HBox(1,videoBitrateLabel,videoBitrateInfoLabel);
        advancedGrid.add(videoBitrateLabelHBox, 0, row);
        advancedGrid.add(inputVideoBitrate, 1, row);
        //If it's empty, we select the first element
        if(inputVideoBitrate.getValue()==null || inputVideoBitrate.getValue().isEmpty()) inputVideoBitrate.setValue(inputVideoBitrate.getItems().get(0));
        inputVideoBitrate.setPrefWidth(comboWith);

        Label videoBufferInfoLabel = new Label("?");
        videoBufferInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip0= new Tooltip("The Video Buffer Parameter specifies the size of the real-time buffer used during encoding or decoding.\n It's crucial for ensuring smooth and uninterrupted processing, especially in live streaming.\n For video devices, a suggested value is 1024MB.\n These values help balance performance and stability while accommodating the data flow requirements of the respective devices.");
        Tooltip.install(videoBufferInfoLabel, tooltip0);
        tooltip0.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip0.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip0.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip0.getStyleClass().add("tooltip");
        Label videoBufferLabel = new Label("Video buffer size:");
        // Create an HBox to hold both labels
        HBox videoBufferLabelHBox = new HBox(2,videoBufferLabel,videoBufferInfoLabel);
        inputVideoSourceBuffer.setPrefWidth(comboWith);
        if(inputVideoSourceBuffer.getValue()==null || inputVideoSourceBuffer.getValue().isEmpty()) inputVideoSourceBuffer.setValue(inputVideoSourceBuffer.getItems().get(0));
        advancedGrid.add(videoBufferLabelHBox, 2, row);
        advancedGrid.add(inputVideoSourceBuffer, 3, row);

        Label audioBufferInfoLabel = new Label("?");
        audioBufferInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip10= new Tooltip("The Audio Buffer Parameter specifies the size of the real-time buffer used during encoding or decoding.\n It's crucial for ensuring smooth and uninterrupted processing, especially in live streaming.\n For audio devices, a suggested value is 128MB.\n These values help balance performance and stability while accommodating the data flow requirements of the respective devices.");
        Tooltip.install(audioBufferInfoLabel, tooltip10);
        tooltip10.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip10.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip10.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip10.getStyleClass().add("tooltip");
        Label audioBufferLabel = new Label("Audio buffer size:");
        // Create an HBox to hold both labels
        HBox audioBufferLabelHBox = new HBox(2,audioBufferLabel,audioBufferInfoLabel);
        // Only the DirectShow input takes an -rtbufsize for audio: on macOS the samples arrive on
        // a pipe and on Linux from the sound server, and neither branch emits one. Left out of the
        // grid rather than hidden, the way the capture-mode combo is left out on Windows.
        if (Host.isWindows()) {
            advancedGrid.add(audioBufferLabelHBox, 4, row);
            advancedGrid.add(inputAudioSourceBuffer, 5, row);
        }
        //If it's empty, we select the first element
        if(inputAudioSourceBuffer.getValue()==null || inputAudioSourceBuffer.getValue().isEmpty()) inputAudioSourceBuffer.setValue(inputAudioSourceBuffer.getItems().get(0));
        inputAudioSourceBuffer.setPrefWidth(comboWith);
        row++;

        Label sampleRateInfoLabel = new Label("?");
        sampleRateInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip11 = new Tooltip("""
                The sample rate of the audio devices, which is also the rate the recording is encoded at.

                It must match the rate the capture device is actually set to. If it does not, the recording
                contains only a fraction of the audio spread over the whole running time and is unusable,
                so the application checks the device before starting and refuses to run on a mismatch.

                On macOS the device rate is shown in Audio MIDI Setup; on Windows it is in the sound
                control panel for the device. 48000 is the usual choice for streaming.""");
        Tooltip.install(sampleRateInfoLabel, tooltip11);
        tooltip11.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip11.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
        tooltip11.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip11.getStyleClass().add("tooltip");
        Label sampleRateLabel = new Label("Audio sample rate:");
        HBox sampleRateLabelHBox = new HBox(1, sampleRateLabel, sampleRateInfoLabel);
        advancedGrid.add(sampleRateLabelHBox, 0, row);
        if(inputAudioSampleRate.getValue()==null || inputAudioSampleRate.getValue().isEmpty()) inputAudioSampleRate.setValue("48000");
        advancedGrid.add(inputAudioSampleRate, 1, row);
        inputAudioSampleRate.setPrefWidth(comboWith);
        row++;

        Label commInfoLabel = new Label("?");
        commInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip commTooltip = new Tooltip("""
                Records one more file during the session, encoded separately from the livestream:
                typically hd1080 at 5000k for the communication team while the stream stays hd720.
                For a true 1080p file, OBS must output 1920x1080 and the video input mode match it.

                The file is named recorded-video-communication-<date>-1080p.mp4 and goes to its own
                directory (leave it empty to use the main output directory). If its disk fills or
                fails, only this file stops - the livestream and the normal recording continue.""");
        Tooltip.install(commInfoLabel, commTooltip);
        commTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
        commTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
        commTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        commTooltip.getStyleClass().add("tooltip");
        HBox commCheckHBox = new HBox(6, inputCommRecording, commInfoLabel);
        commCheckHBox.setAlignment(Pos.CENTER_LEFT);
        advancedGrid.add(commCheckHBox, 0, row);
        GridPane.setColumnSpan(commCheckHBox, 6);
        row++;

        advancedGrid.add(new Label("Comm. resolution:"), 0, row);
        if (inputCommResolution.getValue() == null || inputCommResolution.getValue().isEmpty()) inputCommResolution.setValue("hd1080");
        advancedGrid.add(inputCommResolution, 1, row);
        inputCommResolution.setPrefWidth(comboWith);
        advancedGrid.add(new Label("Comm. video bitrate:"), 2, row);
        if (inputCommVideoBitrate.getValue() == null || inputCommVideoBitrate.getValue().isEmpty()) inputCommVideoBitrate.setValue("5000k");
        advancedGrid.add(inputCommVideoBitrate, 3, row);
        inputCommVideoBitrate.setPrefWidth(comboWith);
        advancedGrid.add(new Label("Comm. audio bitrate:"), 4, row);
        if (inputCommAudioBitrate.getValue() == null || inputCommAudioBitrate.getValue().isEmpty()) inputCommAudioBitrate.setValue("256k");
        advancedGrid.add(inputCommAudioBitrate, 5, row);
        inputCommAudioBitrate.setPrefWidth(comboWith);
        row++;

        advancedGrid.add(new Label("Comm. output directory:"), 0, row);
        inputCommDirectory.setPromptText("Empty: the main output directory");
        Button pickCommDirectoryButton = new Button("Choose Directory");
        pickCommDirectoryButton.setOnAction(event -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Select the communication recording directory");
            // Open where the recording would currently go
            File current = new File(inputCommDirectory.getText().isBlank()
                    ? inputOutputDirectory.getText() : inputCommDirectory.getText());
            if (current.isDirectory()) {
                directoryChooser.setInitialDirectory(current);
            }
            File selectedDirectory = directoryChooser.showDialog(scene.getWindow());
            if (selectedDirectory != null) {
                inputCommDirectory.setText(selectedDirectory.getAbsolutePath());
            }
        });
        inputCommDirectory.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(inputCommDirectory, Priority.ALWAYS);
        HBox commDirectoryHBox = new HBox(8, inputCommDirectory, pickCommDirectoryButton);
        commDirectoryHBox.setAlignment(Pos.CENTER_LEFT);
        advancedGrid.add(commDirectoryHBox, 1, row);
        GridPane.setColumnSpan(commDirectoryHBox, 5);

        // The fields only matter while the extra recording is wanted
        inputCommResolution.disableProperty().bind(inputCommRecording.selectedProperty().not());
        inputCommVideoBitrate.disableProperty().bind(inputCommRecording.selectedProperty().not());
        inputCommAudioBitrate.disableProperty().bind(inputCommRecording.selectedProperty().not());
        inputCommDirectory.disableProperty().bind(inputCommRecording.selectedProperty().not());
        pickCommDirectoryButton.disableProperty().bind(inputCommRecording.selectedProperty().not());

        Button saveButton = new Button("Save settings");
        saveButton.getStyleClass().add("event-button");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setGraphicTextGap(15);

        SVGPath checkmark = new SVGPath();
        checkmark.setContent("M10 20 l5 5 l10 -10"); // Simplified checkmark path
        checkmark.setStroke(Color.WHITE);
        checkmark.setFill(Color.TRANSPARENT);
        saveButton.setGraphic(checkmark);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setStyle("-fx-progress-color: white;");
        progress.setMaxSize(16,16);
        // Action for the Save button
        saveButton.setOnAction(event -> {
            saveSettings();
            saveButton.setGraphic(progress);
            // Create a Timeline to hide the checkmark after 1 second
            Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> saveButton.setGraphic(checkmark)));
            timeline.play();
        });

        HBox saveHBox = new HBox(saveButton);
        saveHBox.setAlignment(Pos.CENTER);
        saveHBox.setPadding(new Insets(20,0,20,0));

        // A TitledPane gives the standard disclosure arrow and keyboard handling for free. It starts
        // folded: these values are set once for a venue, so hiding them keeps the settings that are
        // used every session on one screen.
        TitledPane advancedPane = new TitledPane("Advanced options", advancedGrid);
        advancedPane.getStyleClass().add("advanced-pane");
        advancedPane.setExpanded(false);
        // Unfolded in one step rather than over twenty layout passes with the content still hidden:
        // a single dropped pass during that animation left the pane grown and its rows undrawn
        advancedPane.setAnimated(false);
        // Percentage columns ask for whatever width makes their widest label fit, which for this
        // grid is far more than the window. Pin it to the width of the grids above, or the pane
        // stretches the tab and the overflow is clipped with no scroll bar to reach it.
        advancedGrid.setMaxWidth(WINDOW_WIDTH-10);
        advancedPane.setMaxWidth(WINDOW_WIDTH-10);
        advancedPane.setPrefWidth(WINDOW_WIDTH-10);

        return new VBox(inputGrid,inputGrid2,advancedPane,saveHBox);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        ScrollPane pane = buildUI();
        // The build footer sits under the scroll pane, not inside it, so it stays visible
        // whatever the tabs do; the mode colour is painted on this shell, which is the only node
        // that is an ancestor of both the tabs and the footer
        fullContent = pane;
        footer = buildFooter();
        compactView = buildCompactView();
        shell = new BorderPane(pane);
        // A mode is on from the first frame rather than resolving the colour against nothing;
        // applySettings() below corrects it as soon as the real output type is known
        shell.getStyleClass().addAll(WINDOW_SHELL, MODE_LIVESTREAM);
        shell.setBottom(footer);
        scene = new Scene(shell, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add("javafx@main.css");
        // Esc is the way out of any view that has taken the window over
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && compactMode) {
                setCompactMode(false);
                event.consume();
            }
        });
        primaryStage.setScene(scene);
        primaryStage.setTitle("FFmpeg GUI");
        primaryStage.setOnCloseRequest(e -> {
            if (!confirmClose()) {
                e.consume();
                return;
            }
            handleClose();
        });
        // GNOME drops the demands-attention state the moment the window is focused, so the
        // dock only stays lit "while streaming" by re-asserting it each time the operator
        // looks away. On Windows the pulsing taskbar icon covers this already.
        primaryStage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && streamRecorder.isAliveProperty().get()) {
                WindowAttention.request(WINDOW_TITLE_PREFIX);
            }
        });
        primaryStage.show();
        applySettings();
        applyStyleOnOutputTypeChange();
        vuMeterPanel = new LevelMeterPanel(inputAudioSources, inputAudioSourcesChannel, settings);
        volumeMonitor = new VolumeMonitor(vuMeterPanel.getVuMeters());

        // Start/stop VolumeMonitor based on LevelMeterPanel visibility
        vuMeterPanel.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                volumeMonitor.startMonitoring();
            } else {
                volumeMonitor.stopMonitoring();
            }
        });

    }

    /** The output settings and the advanced ones are two grids so that the advanced section can be
     *  folded away, but they share these columns so their rows keep lining up. */
    private List<ColumnConstraints> outputColumnConstraints() {
        List<ColumnConstraints> columns = new ArrayList<>();
        for (double percentWidth : new double[]{13, 20, 15, 22, 15, 20}) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(percentWidth);
            columns.add(column);
        }
        return columns;
    }

    /**
     * One-click presets applying a noise reduction level to every language except the reference
     * one (English). They sit on the video input row, which is the only line with room to spell
     * out what they do.
     */
    private HBox buildNoiseReductionPresets() {
        Label presetLabel = new Label("Noise reduction presets:");
        presetLabel.getStyleClass().add("preset-label");
        Label presetInfoLabel = new Label("?");
        presetInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip presetInfoTooltip = new Tooltip("Sets the noise reduction of every language at once, except "
                + Settings.ENGLISH_LANGUAGE + ", which keeps its own level.");
        Tooltip.install(presetInfoLabel, presetInfoTooltip);
        presetInfoTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
        presetInfoTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
        presetInfoTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        presetInfoTooltip.getStyleClass().add("tooltip");
        HBox presetBox = new HBox(6, presetLabel, presetInfoLabel);
        presetBox.setAlignment(Pos.CENTER_LEFT);
        for (String level : new String[]{"0", "1", "2"}) {
            Button presetButton = new Button(level);
            presetButton.getStyleClass().add("preset-button");
            Tooltip presetTooltip = new Tooltip("Set the noise reduction of all the languages except "
                    + Settings.ENGLISH_LANGUAGE + " to " + level);
            presetTooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY));
            presetTooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION));
            presetTooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
            presetTooltip.getStyleClass().add("tooltip");
            presetButton.setTooltip(presetTooltip);
            presetButton.setOnAction(e -> {
                for (int i = 3; i < Settings.LANGUAGES.length; i++) {
                    inputNoiseReductionValues[i].setValue(level);
                }
            });
            presetBox.getChildren().add(presetButton);
        }
        return presetBox;
    }

    private void addLanguageRow(GridPane gridPane, int rowIndex, String labelText, ComboBox<String> audioInput, ComboBox<String> audioInputChannel, ComboBox<String> noiseReductionValue, String languageKey) {

        ColorPicker colorPicker = new ColorPicker();
        colorPicker.getStyleClass().add("color-picker-no-arrow");
        colorPicker.setPrefWidth(30);
        colorPicker.setPrefHeight(25);
        languageColorPickers.put(languageKey, colorPicker);
        Label audioInputinfoLabel = new Label("?");
        audioInputinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip = new Tooltip("Choose the input device for this language.It can happen you need to choose a device that manage two language, depending of the audio driver offers mono or only stereos.\n If you have to share an input device between languages, you'll have to use the Channel audio parameter which will allow to divide the stereo if needed");
        Tooltip.install(audioInputinfoLabel, tooltip);
        tooltip.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip.getStyleClass().add("tooltip");
        Label audioInputLabel = new Label(labelText);
        // Create an HBox to hold both labels
        HBox audioInputLabelHBox = new HBox(5, colorPicker, audioInputLabel, audioInputinfoLabel);
        audioInputLabelHBox.setAlignment(Pos.CENTER_LEFT);
        audioInputLabelHBox.setPrefWidth(LABEL_PREF_WIDTH + 50 + 10); // ColorPicker width + Label width + spacing
        gridPane.add(audioInputLabelHBox, 0, rowIndex);
        gridPane.add(audioInput, 1, rowIndex);

        gridPane.add(audioInputChannel, 2, rowIndex);
        if(noiseReductionValue!=null) {
            gridPane.add(noiseReductionValue, 3, rowIndex);
        }

        int languageIndex = indexOfLanguage(languageKey);
        audioInput.valueProperty().addListener((observable, oldValue, newValue) -> {
            // A stereo cable offers Left/Right, a mixer offers one entry per input
            if (languageIndex >= 0) {
                populateChannels(languageIndex, newValue);
            }
            if (!newValue.equals("Not Used") && !newValue.isEmpty()) {
                audioInputChannel.setDisable(false);
                // audioInputChannel.setValue("Join");
                if(noiseReductionValue!=null) {
                    noiseReductionValue.setDisable(false);
                }
            } else {
                audioInputChannel.setDisable(true);
                if(noiseReductionValue!=null) {
                    noiseReductionValue.setDisable(true);
                }
            }
        });
    }

    private static int indexOfLanguage(String languageName) {
        for (int i = 0; i < Settings.LANGUAGES.length; i++) {
            if (Settings.LANGUAGES[i].name().equals(languageName)) {
                return i;
            }
        }
        return -1;
    }

    /** What pressing Stop, or closing the window, is about to end. */
    private String whatStopEnds() {
        if (isTheOutputFileAndUrl.get()) {
            return "The livestream and the recording both end.";
        }
        return isTheOutputAFile.get() ? "The recording ends." : "The livestream ends.";
    }

    /**
     * A question the operator has to answer before something that cannot be taken back.
     * <p>
     * Cancel is the default button, deliberately: these appear over a live broadcast, and a stray
     * Enter or Space - the space bar is already guarded on the Stop button for the same reason -
     * must not be what ends it.
     */
    private boolean confirm(String header, String detail, String confirmLabel) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(WINDOW_TITLE_PREFIX);
        alert.setHeaderText(header);
        alert.setContentText(detail);
        ButtonType go = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(go, ButtonType.CANCEL);
        ((Button) alert.getDialogPane().lookupButton(go)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == go;
    }

    /**
     * Everything that has to be settled before the window closes, in one question wherever it can
     * be: a stream that is running is the thing worth asking about, and unsaved language edits come
     * with their own Save/Discard/Cancel rather than a second yes/no on top of it.
     */
    private boolean confirmClose() {
        boolean streaming = streamRecorder.isAliveProperty().get();
        if (streaming && !confirm("A stream is running.",
                whatStopEnds() + " Closing the window ends it.", "Close and stop")) {
            return false;
        }
        if (languagesTab != null && languagesTab.hasUnsavedEdits()) {
            return confirmDiscardLanguageEdits();
        }
        return streaming || confirm("Close the application?",
                "Nothing is streaming or recording.", "Close");
    }

    /** Whether the operator is content to lose the language list they have been editing. */
    private boolean confirmDiscardLanguageEdits() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(WINDOW_TITLE_PREFIX);
        alert.setHeaderText("The language list has unsaved changes.");
        alert.setContentText("Save them before closing, or close and lose them?");
        ButtonType saveAndClose = new ButtonType("Save and close");
        ButtonType closeAnyway = new ButtonType("Close without saving");
        alert.getButtonTypes().setAll(saveAndClose, closeAnyway, ButtonType.CANCEL);
        ButtonType chosen = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (chosen == saveAndClose) {
            saveSettings();
            return true;
        }
        return chosen == closeAnyway;
    }

    private void handleClose() {
        if (vuMeterPanel != null) {
            vuMeterPanel.closeVUMeters();
        }
        if (volumeMonitor != null) {
            volumeMonitor.stopMonitoring();
        }
        stopEncodingThread();
    }

    private void startEncodingThread() {
        if(!checkParameters()) {
            return;
        }
        displayPIDInfo();
        if (languagesTab != null && languagesTab.hasUnsavedEdits()) {
            appendToConsole("The language list has unsaved changes. This stream uses the list loaded"
                    + " when the application started.", ConsoleSeverity.WARNING);
        }
        barBaseColor = LIVE_GREEN;
        playingError.set(false);
        followingConsoleTail.set(true);
        // A new run is judged on its own numbers, and gets the benefit of the doubt on a fault the
        // last one could not recover from
        streamHealth.reset();
        videoFaultLatched = false;
        statusLineRow = null;
        compactStatusLine.setText("");
        statusReadout.setText("");
        streamRecorder.setSrtUrl(inputSrtURL.getText());
        // Unticked hands the recorder null, and the URL then reaches ffmpeg byte for byte
        streamRecorder.setSrtLatencyMillis(inputSrtLatencyOverride.isSelected()
                ? Integer.valueOf(parseIntOrDefault(inputSrtLatencyMillis.getText(), 0)) : null);
        streamRecorder.setOutputDirectory(inputOutputDirectory.getText());
        streamRecorder.initialiseVideoDevice(inputVideoSource.getValue());
        streamRecorder.initialiseAudioDevices(Arrays.stream(inputAudioSources).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputAudioSourcesChannel).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputNoiseReductionValues).map(ComboBox::getValue).toArray(String[]::new));
        streamRecorder.setPixelFormat(inputPixelFormat.getValue());
        streamRecorder.setOutputResolution(inputSrtResolution.getValue());
        streamRecorder.setDelay(Integer.parseInt(inputSoundDelay.getText()));
        streamRecorder.setEncoder(inputEncoder.getValue());
        // Zero when the field is hidden and was never filled in: with one device it is not read
        streamRecorder.setTimeNeededToOpenADevice(parseIntOrDefault(inputTimeNeededToOpenADevice.getText(), 0));
        streamRecorder.setAudioBitrate(inputAudioBitrate.getValue());
        streamRecorder.setAudioBufferSize(inputAudioSourceBuffer.getValue());
        streamRecorder.setVideoBitrate(inputVideoBitrate.getValue());
        streamRecorder.setCommRecording(inputCommRecording.isSelected());
        streamRecorder.setCommResolution(inputCommResolution.getValue());
        streamRecorder.setCommVideoBitrate(inputCommVideoBitrate.getValue());
        streamRecorder.setCommAudioBitrate(inputCommAudioBitrate.getValue());
        streamRecorder.setCommDirectory(inputCommDirectory.getText());
        streamRecorder.setVideoBufferSize(inputVideoSourceBuffer.getValue());
        streamRecorder.setFfmpegPath(settings.getFfmpegPath());
        streamRecorder.setVideoInputMode(inputVideoInputMode.getValue());
        streamRecorder.setVideoInputPixelFormat(videoInputPixelFormat);
        streamRecorder.setAudioSampleRate(inputAudioSampleRate.getValue());

        if(isTheOutputFileAndUrl.get())  streamRecorder.setOutputType(StreamRecorderRunnable.FILE_AND_URL);
        else if(isTheOutputAFile.get())  streamRecorder.setOutputType(StreamRecorderRunnable.FILE);
        else if(isTheOutputAURL.get())   streamRecorder.setOutputType(StreamRecorderRunnable.URL);

        streamRecorder.setFps(Integer.parseInt(inputFramePerSecond.getValue()));
        textAreaInfo.setText(streamRecorder.getFFMpegCommand());
        playerURLTextField.setText(buildPlayerURL());
        stopButton.setDisable(false);
        encodingThread = new Thread(() -> {
            // Your existing code for streamRecorder.run() goes here
            streamRecorder.setSrtUrl(inputSrtURL.getText());
            streamRecorder.initialiseVideoDevice(inputVideoSource.getValue());
            streamRecorder.initialiseAudioDevices(Arrays.stream(inputAudioSources).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputAudioSourcesChannel).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputNoiseReductionValues).map(ComboBox::getValue).toArray(String[]::new));
            try {
                streamRecorder.run();
            } catch (Exception e) {
                if (settings.isDevelopmentMode()) {
                    throw new RuntimeException(e);
                } else {
                    Platform.runLater(()->appendToConsole(e.toString(), ConsoleSeverity.ERROR));
                }
            }
        });
        encodingThread.start();
    }

    /**
     * Castr's stream key, which is the {@code r=} field inside the streamid - SRT's standard
     * {@code #!::key=value,key=value} syntax. Anchored on {@code streamid=} and on the comma
     * boundaries rather than searching the whole URL for {@code r=}: {@code linger=} and
     * {@code smoother=} are real SRT options that also end in one, and either of them written
     * before the streamid handed the player a fragment of the wrong parameter. Empty when there is
     * no key to find, so a broken link is left out rather than shown as a working one.
     */
    private static String castrStreamKey(String srtUrl) {
        int streamId = srtUrl.indexOf("streamid=");
        if (streamId < 0) {
            return "";
        }
        String id = srtUrl.substring(streamId + "streamid=".length());
        // ffmpeg ends the value at the first &, so the player key has to be read the same way
        int ampersand = id.indexOf('&');
        if (ampersand >= 0) {
            id = id.substring(0, ampersand);
        }
        if (id.startsWith("#!::")) {
            id = id.substring(4);
        }
        for (String field : id.split(",")) {
            if (field.startsWith("r=")) {
                return field.substring(2);
            }
        }
        return "";
    }

    private String buildPlayerURL() {
        String rValue = castrStreamKey(inputSrtURL.getText());
        if (rValue.isEmpty()) {
            return "";
        }
        String baseURL = "https://player.castr.com/"+rValue;
        StringBuilder parameters = new StringBuilder("?tracks=");
        boolean firstParameter = true;
        for (int i = 2; i < Settings.LANGUAGES.length; i++) {
            if (!inputAudioSources[i].getValue().equals("Not Used")) {
                if (!firstParameter) {
                    parameters.append(",");
                }
                // Use nativeName if available, otherwise use name
                String languageDisplayName = Settings.LANGUAGES[i].nativeName() != null ? Settings.LANGUAGES[i].nativeName() : Settings.LANGUAGES[i].name();
                parameters.append(languageDisplayName.replace(" ", "%20")); // URL encode spaces
                firstParameter = false;
            }
        }
        return baseURL + parameters;
    }

    private boolean checkParameters() {
        boolean result = true;
        if(inputAudioSources[0].getValue().equals("Not Used")) {
            appendToConsole("Please select an audio source for the prayers", ConsoleSeverity.ERROR);
            result = false;
        }
        if(inputAudioSources[1].getValue().equals("Not Used")) {
            appendToConsole("Please select an audio source for the english to be mixed with the translation", ConsoleSeverity.ERROR);
            result = false;
        }
        // The models ship inside the application and are unpacked on first use, so this only
        // fails if the folder installed on the machine is unreadable and unpacking failed too
        for (String modelName : new String[]{NoiseModels.SPEECH_MODEL, NoiseModels.GENERAL_NOISE_MODEL}) {
            File modelFile = NoiseModels.modelFile(modelName);
            if (!modelFile.exists()) {
                appendToConsole("The model file for the noise reduction filter is not found. It should be at " + modelFile.getPath() +
                        ".\nYou can dowwload it from here : https://github.com/GregorR/rnnoise-models", ConsoleSeverity.ERROR);
                result = false;
            }
        }
        if(!Host.isFfmpegAvailable(settings.getFfmpegPath())) {
            appendToConsole("ffmpeg could not be started. Install it, or set ffmpegPath in settings.ini to its full path.", ConsoleSeverity.ERROR);
            result = false;
        }
        if(!checkAudioSampleRates()) {
            result = false;
        }
        if((Host.isMac() || Host.isLinux()) && (inputVideoInputMode.getValue()==null || inputVideoInputMode.getValue().isEmpty())) {
            appendToConsole("The video device did not report any capture mode. If it is the OBS Virtual Camera, click Start Virtual Camera in OBS first, then choose the device again.", ConsoleSeverity.ERROR);
            result = false;
        }
        if(Host.isLinux()) {
            String videoDevicePath = V4l2Devices.devicePath(inputVideoSource.getValue());
            if (videoDevicePath == null || !new File(videoDevicePath).exists()) {
                appendToConsole("The video device \"" + inputVideoSource.getValue() + "\" is not connected any more.", ConsoleSeverity.ERROR);
                result = false;
            }
        }
        try {
            Integer.parseInt(inputSoundDelay.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Delay must be an integer number (See Tooltip for help).", ConsoleSeverity.ERROR);
            result= false;
        }
        if(inputSoundDelay.getText().isEmpty()) {
            appendToConsole("Please Fill the Delay (See Tooltip for help)", ConsoleSeverity.ERROR);
            result= false;
        }
        // Only worth checking while the field is on screen: with one audio device it is hidden,
        // never read, and an old value left in it must not stop a stream
        if (severalAudioDevices.get()) {
            if (inputTimeNeededToOpenADevice.getText().isEmpty()) {
                appendToConsole("Please Fill the Time needed to open a device Field (See Tooltip for help)", ConsoleSeverity.ERROR);
                result = false;
            }
            else {
                try {
                    Integer.parseInt(inputTimeNeededToOpenADevice.getText());
                }
                catch (NumberFormatException e) {
                    appendToConsole("The Time needed to open a device Field must be an integer number (See Tooltip for help).", ConsoleSeverity.ERROR);
                    result = false;
                }
            }
        }
        String outputType = inputChooseBetweenUrlOrFile.getValue();

        if (outputType.equals("File") || outputType.equals("Livestream And File")) {
            String directory = inputOutputDirectory.getText();
            File file = new File(directory);
            if (!file.isDirectory()) {
                appendToConsole(directory + " is not a directory. Please enter a valid directory for the file output.", ConsoleSeverity.ERROR);
                result = false;
            }
            else {
                long usableSpace = file.getUsableSpace();
                int usableSpaceInGB = (int) (usableSpace / (1024.0 * 1024.0 * 1024.0));
                int minimumGBNecessary = 15;
                if(usableSpaceInGB <minimumGBNecessary) {
                    appendToConsole("There is less than " +minimumGBNecessary+"GB available on the disk (" + usableSpaceInGB + " GB available). Free some space before recording", ConsoleSeverity.ERROR);
                    // Convert bytes to a more readable format (e.g., megabytes, gigabytes)
                    result = false;
                }
            }
        }

        if (outputType.equals("Srt URL (livestream)") || outputType.equals("Livestream And File")) {
            String url = inputSrtURL.getText();
            if (!url.startsWith("srt://")) {
                appendToConsole(url + " is not a valid srt url. Please enter a valid srt url to stream.", ConsoleSeverity.ERROR);
                result = false;
            }
            // A tee slave list is separated by pipes, so one inside the URL would split the stream
            // sink into two nonsense slaves rather than fail cleanly
            if (url.indexOf('|') >= 0) {
                appendToConsole("The streaming url must not contain a | character.", ConsoleSeverity.ERROR);
                result = false;
            }
            if (inputSrtLatencyOverride.isSelected()) {
                // One test rather than the parse-then-range pair used elsewhere: empty, not a number
                // and out of range all deserve the same sentence, and the sentence is the useful part
                int latency = parseIntOrDefault(inputSrtLatencyMillis.getText(), -1);
                if (latency < MIN_PLAUSIBLE_SRT_LATENCY_MS || latency > MAX_PLAUSIBLE_SRT_LATENCY_MS) {
                    appendToConsole("The stream latency must be a whole number of milliseconds between "
                            + MIN_PLAUSIBLE_SRT_LATENCY_MS + " and " + MAX_PLAUSIBLE_SRT_LATENCY_MS
                            + "; the festival streams at 2000. This field is in milliseconds - the URL's own"
                            + " latency= is in microseconds, so 2000 here writes latency=2000000 there.",
                            ConsoleSeverity.ERROR);
                    result = false;
                }
            }
        }

        if (inputCommRecording.isSelected()) {
            String commDirectory = inputCommDirectory.getText();
            String directory = commDirectory == null || commDirectory.isBlank()
                    ? inputOutputDirectory.getText() : commDirectory;
            File file = new File(directory);
            if (!file.isDirectory()) {
                appendToConsole(directory + " is not a directory. Please enter a valid directory for the communication recording.", ConsoleSeverity.ERROR);
                result = false;
            }
            else {
                long usableSpace = file.getUsableSpace();
                int usableSpaceInGB = (int) (usableSpace / (1024.0 * 1024.0 * 1024.0));
                int minimumGBNecessary = 15;
                if(usableSpaceInGB <minimumGBNecessary) {
                    appendToConsole("There is less than " +minimumGBNecessary+"GB available on the communication recording disk (" + usableSpaceInGB + " GB available). Free some space before recording", ConsoleSeverity.ERROR);
                    result = false;
                }
            }
        }

        if(!result) {
            appendToConsole("--------------------------------------------", ConsoleSeverity.ERROR);

            appendToConsole("Please correct before starting the command.", ConsoleSeverity.ERROR);

        }
        return result;
    }

    private void stopEncodingThread() {
        if (encodingThread != null && encodingThread.isAlive()) {
            streamRecorder.stop();
            int exitCode;
            try {
                exitCode = streamRecorder.getProcess().waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            encodingThread.interrupt();
            try {
                encodingThread.join();
                if (exitCode != 0) {
                    // Exit 255 is the SIGTERM this very method just sent, which is how every
                    // normal session ends. Saying "error code 255" was enough to sound the alarm
                    // on the way out of every stream, so the ordinary case is now named as such.
                    boolean askedFor = streamRecorder.wasStopRequested();
                    appendToConsole(askedFor
                                    ? "ffmpeg stopped on request (exit code " + exitCode
                                      + " - 255 is the usual result of Stop)"
                                    : "ffmpeg exited with error code " + exitCode,
                            askedFor ? ConsoleSeverity.INFO : ConsoleSeverity.ERROR);
                }
            } catch (InterruptedException e) {
                appendToConsole(e.toString(), ConsoleSeverity.ERROR);
            }
        }
    }

    /**
     * Takes everything ffmpeg has said since the last pass. Draining a queue in batches rather
     * than reacting to each line one at a time is what keeps a long session responsive: the
     * console lays itself out and scrolls once per batch instead of once per line, and identical
     * consecutive lines - a storm of the same warning - all arrive instead of collapsing into one.
     */
    private void drainConsoleQueue() {
        consoleDrainScheduled.set(false);
        String line;
        while ((line = streamRecorder.getOutputLines().poll()) != null) {
            appendFfmpegLine(line);
        }
        scrollConsoleToTail();
        repairWindowLayoutLater();
    }

    /** Asks for one pass over the queue; called from the reader threads, so it must be cheap. */
    private void scheduleConsoleDrain() {
        if (consoleDrainScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::drainConsoleQueue);
        }
    }

    /**
     * One line straight from ffmpeg. The status line is a live readout rather than a log entry, so
     * it replaces itself in place and is read for the health of the stream; everything else is a
     * message, and {@link FfmpegMessages} says what it means.
     */
    private void appendFfmpegLine(String line) {
        if (StreamHealth.isStatusLine(line)) {
            showStatusLine(line);
            return;
        }
        // A message ends the run of status updates, so the next one starts a new node of its own
        // instead of overwriting a warning the operator has not read yet
        statusLineRow = null;
        readDeviceOpenTime(line);
        FfmpegMessages.Diagnosis diagnosis = FfmpegMessages.diagnose(line);
        appendToConsole(line, diagnosis.severity());
        if (diagnosis.hasAdvice()) {
            // The remedy on its own line under the raw ffmpeg text: the operator gets the fault
            // already diagnosed instead of having to open the platform notes mid-session
            appendToConsole("    -> " + diagnosis.advice(), diagnosis.severity());
        }
        if (FfmpegMessages.isUnrecoverableCaptureFault(line)) {
            // This one does not clear by itself, so neither does the colour it puts on the window
            videoFaultLatched = true;
        }
    }

    /**
     * ffmpeg rewrites its status line twice a second, so it lives as a single self-refreshing
     * entry at the foot of the console rather than as thousands of log lines. Its counters are
     * coloured field by field, because "which number went orange" is the whole diagnosis.
     */
    private void showStatusLine(String line) {
        StreamHealth.Alert alert = streamHealth.observe(line, targetFrameRate(), System.currentTimeMillis());
        statusReadout.setText(streamHealth.readout());
        applySeverityStyle(statusReadout, streamHealth.level().severity());

        // The status line rewrites itself twice a second, so it overwrites the tail of the log
        // rather than adding a thousand entries to it
        // The status line rewrites itself twice a second, so it replaces its own row rather than
        // adding a thousand of them. It takes the colour of the health it is reporting, so a stream
        // that is falling behind says so on the line carrying the numbers that prove it.
        Node row = consoleRow(gutter(ConsoleSeverity.PLAIN) + line, streamHealth.level().severity());
        List<Node> rows = consoleOutputBox.getChildren();
        if (statusLineRow != null && !rows.isEmpty() && rows.get(rows.size() - 1) == statusLineRow) {
            deselectConsoleRow(statusLineRow);
            rows.set(rows.size() - 1, row);
        } else {
            rows.add(row);
        }
        statusLineRow = row;
        scrollConsoleToTail();
        compactStatusLine.setText(line);

        if (alert != null) {
            statusLineRow = null; // the alert below is a message, and must not be overwritten
            // The all-clear is worth saying out loud rather than in the plain text of a log line:
            // an operator who saw the warning needs to know it went away
            appendToConsole(alert.message(), alert.level() == StreamHealth.Level.OK
                    ? ConsoleSeverity.INFO : alert.level().severity());
        }
    }

    /**
     * The gap between two devices opening, which is what the delay setting has to cover. ffmpeg
     * reports each input's start time once, so the pair is remembered as the lines go by.
     */
    private void readDeviceOpenTime(String line) {
        int startIndex = line.indexOf("start: ");
        if (startIndex == -1) {
            return;
        }
        int endIndex = line.indexOf(",", startIndex);
        if (endIndex == -1) {
            return;
        }
        long startMillis = parseStartMillis(line.substring(startIndex + "start: ".length(), endIndex));
        if (startMillis == Long.MIN_VALUE) {
            return;
        }
        if (firstOpeningDeviceStartupTime == 0) {
            firstOpeningDeviceStartupTime = startMillis;
        }
        if (secondOpeningDeviceStartupTime == 0) {
            secondOpeningDeviceStartupTime = startMillis;
        } else {
            firstOpeningDeviceStartupTime = secondOpeningDeviceStartupTime;
            secondOpeningDeviceStartupTime = startMillis;
        }
        long timeToOpen = secondOpeningDeviceStartupTime - firstOpeningDeviceStartupTime;
        // DirectShow counts from the start of the capture, AVFoundation from when the
        // device itself was powered up, so only a plausible gap says anything useful
        if (timeToOpen > 0 && timeToOpen <= MAX_PLAUSIBLE_DEVICE_OPEN_MS) {
            appendToConsole("Time to open the device: " + timeToOpen + " ms", ConsoleSeverity.INFO);
        }
    }

    /**
     * Writes one line to the console at the severity the caller asked for. The caller's word is
     * final: what a message means is known where it is raised, and guessing it from the wording is
     * how a pressed Stop button - "exited with error code 255", the normal end of every session -
     * used to sound the alarm.
     */
    private void appendToConsole(String line, ConsoleSeverity severity) {
        // Stop is pressed on the interface but carried out on a background task, so some of these
        // messages are raised off the FX thread. Marshalling here rather than at each call site
        // means a new one cannot forget and touch the scene graph from the wrong thread.
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> appendToConsole(line, severity));
            return;
        }
        consoleOutputBox.getChildren().add(consoleRow(gutter(severity) + line, severity));
        statusLineRow = null;
        repairWindowLayoutLater();
        if (severity == ConsoleSeverity.ERROR) {
            raiseErrorAlarm();
        }
        scrollConsoleToTail();
    }

    /**
     * The mark that opens a line, so the eye can find the lines that matter in a log the control
     * can only paint in one colour. Fixed width, so everything below it still lines up, and words
     * rather than symbols so the log stays readable once it has been pasted somewhere else.
     */
    private static String gutter(ConsoleSeverity severity) {
        return switch (severity) {
            case ERROR -> "[X] ";
            case WARNING -> "[!] ";
            case INFO -> "[i] ";
            default -> "    ";
        };
    }

    /**
     * One line of the log: its text, coloured and weighted for what it means, and the plain text
     * behind it so it can be copied out as it reads.
     *
     * <p>A row rather than a text control, because only styled text can pick the warnings and the
     * errors out of a wall of ffmpeg chatter - and a JavaFX text control paints all of its text in
     * one colour. The cost is that a selection is whole lines: a Text node has no background to
     * highlight, so the row carries it, and the row is the smallest thing that can.
     */
    private Node consoleRow(String text, ConsoleSeverity severity) {
        Text content = new Text(text);
        applySeverityStyle(content, severity);
        TextFlow row = new TextFlow(content);
        row.getStyleClass().add("console-line");
        row.setUserData(text);
        row.setOnMousePressed(event -> pressConsoleRow(row, event.isShiftDown(), event.isControlDown()));
        // JavaFX's own press-drag-release gesture, rather than working out which row is under the
        // pointer: the row the drag enters says so itself, which needs no arithmetic to get wrong
        // and no walk over a log that may be thousands of lines long
        row.setOnDragDetected(event -> row.startFullDrag());
        row.setOnMouseDragEntered(event -> {
            if (consoleSelectionAnchor != null) {
                selectConsoleRange(consoleSelectionAnchor, row);
            }
        });
        return row;
    }

    /**
     * Drag across the log to take a run of lines, shift-click to extend, ctrl-click to pick them
     * out one by one, then Ctrl+C or the right-click menu. The console stays read-only: this is
     * about getting an ffmpeg line out of the window and into a message to somebody.
     */
    private void installConsoleSelection() {
        consoleOutputScrollPane.setFocusTraversable(true);
        // A press on the empty space under the log lets go of whatever was picked out
        consoleOutputBox.setOnMousePressed(event -> {
            consoleOutputScrollPane.requestFocus();
            if (event.getTarget() == consoleOutputBox) {
                clearConsoleSelection();
            }
        });

        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(event -> copyConsoleSelection());
        MenuItem selectAll = new MenuItem("Select all");
        selectAll.setOnAction(event -> selectAllConsoleRows());
        MenuItem clear = new MenuItem("Clear output");
        clear.setOnAction(event -> clearConsole());
        ContextMenu menu = new ContextMenu(copy, selectAll, new SeparatorMenuItem(), clear);
        // Copy takes the whole console when nothing is picked out, which is what an operator
        // sending a log to somebody wants and saves them selecting a thousand lines first
        menu.setOnShowing(event -> copy.setText(selectedConsoleRows.isEmpty()
                ? "Copy everything" : "Copy " + selectedConsoleRows.size() + " selected line"
                        + (selectedConsoleRows.size() == 1 ? "" : "s")));
        consoleOutputScrollPane.setContextMenu(menu);

        consoleOutputScrollPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isControlDown()) {
                return;
            }
            if (event.getCode() == KeyCode.C) {
                copyConsoleSelection();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                selectAllConsoleRows();
                event.consume();
            }
        });
    }

    /** A press on a line: take it, extend to it, or add it to what is already picked out. */
    private void pressConsoleRow(Node row, boolean extend, boolean toggle) {
        consoleOutputScrollPane.requestFocus();   // so Ctrl+C and Ctrl+A reach the console
        if (extend && consoleSelectionAnchor != null) {
            selectConsoleRange(consoleSelectionAnchor, row);
            return;
        }
        if (toggle) {
            if (selectedConsoleRows.contains(row)) {
                deselectConsoleRow(row);
            } else {
                selectConsoleRow(row);
            }
        } else {
            clearConsoleSelection();
            selectConsoleRow(row);
        }
        consoleSelectionAnchor = row;
    }

    private void selectConsoleRow(Node row) {
        if (!selectedConsoleRows.contains(row)) {
            selectedConsoleRows.add(row);
            row.getStyleClass().add("console-line-selected");
        }
    }

    private void deselectConsoleRow(Node row) {
        selectedConsoleRows.remove(row);
        row.getStyleClass().remove("console-line-selected");
    }

    private void clearConsoleSelection() {
        for (Node row : List.copyOf(selectedConsoleRows)) {
            deselectConsoleRow(row);
        }
    }

    private void selectConsoleRange(Node from, Node to) {
        List<Node> rows = consoleOutputBox.getChildren();
        int start = rows.indexOf(from);
        int end = rows.indexOf(to);
        if (start < 0 || end < 0) {
            return;
        }
        clearConsoleSelection();
        for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
            selectConsoleRow(rows.get(i));
        }
    }

    private void selectAllConsoleRows() {
        clearConsoleSelection();
        consoleOutputBox.getChildren().forEach(this::selectConsoleRow);
        consoleSelectionAnchor = null;
    }

    /** Puts the selected lines on the clipboard, or the whole console when nothing is selected. */
    private void copyConsoleSelection() {
        List<Node> rows = selectedConsoleRows.isEmpty()
                ? consoleOutputBox.getChildren() : selectedConsoleRows;
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Node row : rows) {
            text.append(row.getUserData()).append(System.lineSeparator());
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** One severity vocabulary for the whole window: the classes live in javafx@main.css. */
    private void applySeverityStyle(Node node, ConsoleSeverity severity) {
        node.getStyleClass().removeAll(SEVERITY_STYLE_CLASSES);
        if (severity.styleClass() != null) {
            node.getStyleClass().add(severity.styleClass());
        }
    }

    /**
     * The red-alert response: the status bar turns, the dock asks for attention and the sound
     * plays. One sound at a time - a burst of failing lines used to start a thread and a media
     * player each, all of them talking over one another.
     */
    private void raiseErrorAlarm() {
        // Whatever went wrong, the console is where it is explained - so the compact view gets out
        // of the way by itself rather than leaving the operator with a red bar and nowhere to read
        if (compactMode) {
            setCompactMode(false);
        }
        barBaseColor = ERROR_ORANGE;
        lastErrorMillis = System.currentTimeMillis();
        if (!primaryStage.isFocused()) {
            WindowAttention.request(WINDOW_TITLE_PREFIX);
        }
        if (!playingError.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::playErrorSound, "error-alarm");
        thread.setDaemon(true); // The alarm must never keep the application alive on exit
        thread.start();
    }

    private void playErrorSound() {
        // However playback ends - or fails to start - the flag has to come back,
        // or every later alarm stays silent for the rest of the session
        try {
            String beepSound = Objects.requireNonNull(getClass().getResource("/error.wav")).toString();
            Media media = new Media(beepSound);
            MediaPlayer mediaPlayer = new MediaPlayer(media);
            CountDownLatch latch = new CountDownLatch(1);

            mediaPlayer.setOnEndOfMedia(latch::countDown);
            mediaPlayer.setOnError(() -> {
                logger.error("The alert sound failed to play", mediaPlayer.getError());
                latch.countDown();
            });
            mediaPlayer.play();
            if (!latch.await(10, TimeUnit.SECONDS)) {
                logger.warn("The alert sound never finished; giving up on it");
            }
            mediaPlayer.dispose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            logger.error("The alert sound could not be played", e);
        } finally {
            playingError.set(false);
        }
    }

    /** The frame rate the stream is configured for, which the health thresholds are measured against. */
    private int targetFrameRate() {
        try {
            return Integer.parseInt(inputFramePerSecond.getValue().trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    /** Empties the console, and forgets the status line that was standing at the foot of it. */
    private void clearConsole() {
        consoleOutputBox.getChildren().clear();
        selectedConsoleRows.clear();
        consoleSelectionAnchor = null;
        statusLineRow = null;
    }

    /**
     * Keeps the newest line in view, unless the operator has scrolled back through the log.
     */
    private void scrollConsoleToTail() {
        if (!followingConsoleTail.get()) {
            return;
        }
        // The listener has to be able to tell this move apart from the operator's own
        scrollingConsoleToTail = true;
        consoleOutputScrollPane.setVvalue(1.0);
        scrollingConsoleToTail = false;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
