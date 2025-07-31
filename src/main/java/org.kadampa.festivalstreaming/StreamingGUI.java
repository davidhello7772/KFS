package org.kadampa.festivalstreaming;

import com.github.sarxos.webcam.Webcam;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamingGUI extends Application {
    private static final String WAITING_TO_LIVESTREAM_AND_RECORD = "WAITING TO LIVESTREAM AND RECORD ON LOCAL MACHINE";
    private static final String WAITING_TO_RECORD = "WAITING TO RECORD ON LOCAL MACHINE";
    private static final String WAITING_TO_LIVESTREAM = "WAITING TO LIVESTREAM";
    private static final String CURRENTLY_RECORDING = "RECORDING ON LOCAL MACHINE IN PROGRESS";
    private static final String CURRENTLY_LIVESTREAMING ="LIVESTREAM IN PROGRESS";
    private final ComboBox<String>[] inputAudioSources;
    private final ComboBox<String>[] inputAudioSourcesChannel;
    private final ComboBox<String>[] inputNoiseReductionValues;
    private final Map<String, ColorPicker> languageColorPickers = new HashMap<>();
    private final TextField inputVideoPid;
    private final TextField inputenMixDelay;
    private final ComboBox<String> inputVideoSource;
    private final ComboBox<String> inputVideoSourceBuffer;
    private final ComboBox<String> inputAudioSourceBuffer;
    private final ComboBox<String> inputVideoBitrate;
    private final TextField inputSoundDelay;
    private final ComboBox<String> inputPixelFormat;
    private final ComboBox<String> inputSrtResolution;
    private final ComboBox<String> inputOutputFileResolution;
    private final ComboBox<String> inputEncoder;
    private final ComboBox<String> inputChooseBetweenUrlOrFile;
    private final TextField inputSrtURL;
    private final TextField inputOutputDirectory;
    private final ComboBox<String> inputAudioBitrate;
    private final ComboBox<String> inputFramePerSecond;
    private final TextFlow consoleOutputTextFlow;
    private final Button startButton;
    private final Button stopButton;
    private final Button clearOutputButton;
    private final StringProperty currentInformationTextProperty = new SimpleStringProperty();
    private final TextField inputTimeNeededToOpenADevice;
    private Thread encodingThread;
    private final StreamRecorderRunnable streamRecorder = new StreamRecorderRunnable(this);
    private final Settings settings;
    private ScrollPane consoleOutputScrollPane;
    private final SVGPath stopPath = new SVGPath();
    private final TextArea textAreaInfo = new TextArea();
    private static final int WINDOW_WIDTH = 900;
    private static final int WINDOW_HEIGHT = 950;
    private static final double TOOLTIP_DELAY = 0.2;
    private static final int TOOLTIP_DURATION=10;
    private static final double LABEL_PREF_WIDTH = 150;
    private final Timeline blinkingTimeLine;
    private boolean manualScroll = false;
    private boolean doWeAutomaticallyScrollAtBottom = true;
    private final Label videoPID = new Label("");
    private final Label englishPID = new Label("");
    private final Label spanishPID = new Label("");
    private final Label frenchPID = new Label("");
    private final Label portuguesePID = new Label("");
    private final Label germanPID = new Label("");
    private final Label cantonesePID = new Label("");
    private final Label mandarinPID = new Label("");
    private final Label vietnamesePID = new Label("");
    private final Label italianPID = new Label("");
    private final Label finishPID = new Label("");
    private final Label greekPID = new Label("");
    private final Label extraLanguagePID = new Label("");
    private final Label nowPlayingLabel = new Label("");
    private final HBox nowPlayingBox = new HBox(nowPlayingLabel);
    private final BooleanProperty isTheOutputAFile = new SimpleBooleanProperty();
    private final BooleanProperty isTheOutputAURL = new SimpleBooleanProperty();
    private final BooleanProperty isTheOutputFileAndUrl = new SimpleBooleanProperty();
    private LevelMeterPanel vuMeterPanel;
    private final String[] languageNames = {
            "Prayers (for mix)", "English (for mix)", "English", "Spanish", "French",
            "Portuguese", "German", "Cantonese", "Mandarin", "Vietnamese", "Italian", "Finnish"
    };


    private final TextField playerURLTextField = new TextField();
    private final HBox outputFileHBox = new HBox();
    private final HBox outputUrlHBox = new HBox();
    private Scene scene;
    private Tab controlConsoleTab;
    private ScrollPane mainScrollPane;
    private Stage primaryStage;
    private final Image iconLiveStreamIdle = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/live-streaming.png")));
    private final Image iconLiveStreamPlaying = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/live-streaming-playing.jpg")));
    private final Image iconRecordingIdle = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/recording-idle.png")));
    private final Image iconRecordingPlaying = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/recording-playing.jpg")));
    private String bgColor = "-green-color";
    private int firstOpeningDeviceStartupTime = 0;
    private int secondOpeningDeviceStartupTime = 0;
    private boolean playingError;

    public StreamingGUI() {
        int MAX_NUMBER_OF_LANGUAGES = 12;
        inputAudioSources = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        inputAudioSourcesChannel = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        inputNoiseReductionValues = new ComboBox[MAX_NUMBER_OF_LANGUAGES];

        for (int i = 0; i < inputAudioSources.length; i++) {
            inputAudioSources[i] = new ComboBox<>();
            inputAudioSourcesChannel[i] = new ComboBox<>();
            inputNoiseReductionValues[i] = new ComboBox<>();
        }
        playingError = false;
        inputVideoSource = new ComboBox<>();
        inputVideoPid = new TextField();
        inputVideoPid.setText("37");
        inputVideoPid.setMaxWidth(75);
        inputenMixDelay = new TextField();
        inputenMixDelay.setText("0");
        inputenMixDelay.setMaxWidth(75);
        inputSoundDelay = new TextField();
        inputSoundDelay.setText("0");
        inputAudioBitrate = new ComboBox<>();
        inputAudioBitrate.getItems().add("128k");
        inputAudioBitrate.getItems().add("256k");
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

        inputOutputFileResolution = new ComboBox<>();
        inputEncoder = new ComboBox<>();
        inputEncoder.getItems().add("libx264");
        inputEncoder.getItems().add("h264_nvenc");

        inputChooseBetweenUrlOrFile = new ComboBox<>();
        inputChooseBetweenUrlOrFile.getItems().add("Srt URL (livestream)");
        inputChooseBetweenUrlOrFile.getItems().add("File");
        inputChooseBetweenUrlOrFile.getItems().add("Livestream And File");


        inputChooseBetweenUrlOrFile.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            isTheOutputFileAndUrl.setValue(newValue.equals("Livestream And File"));
            isTheOutputAFile.setValue(newValue.equals("Livestream And File") || newValue.equals("File"));
            isTheOutputAURL.setValue(newValue.equals("Livestream And File") || newValue.equals("Srt URL (livestream)"));
            }
        );

        isTheOutputFileAndUrl.addListener((observable, oldValue, newValue) -> {
            if(controlConsoleTab!=null) {
                applyStyleOnOutputTypeChange(isTheOutputFileAndUrl.get(),newValue);}});

        isTheOutputAFile.addListener((observable, oldValue, newValue) -> {
            if(controlConsoleTab!=null) {
                applyStyleOnOutputTypeChange(isTheOutputFileAndUrl.get(),newValue);}});

        inputTimeNeededToOpenADevice = new TextField();
        inputSrtURL = new TextField();
        inputOutputDirectory = new TextField();
        outputFileHBox.visibleProperty().bind(isTheOutputAFile);
        outputUrlHBox.visibleProperty().bind(isTheOutputAURL);
        consoleOutputTextFlow = new TextFlow();
        //consoleOutputTextArea = new TextArea();
        startButton = new Button("Start");
        startButton.getStyleClass().add("event-button");
        startButton.getStyleClass().add("success-button");
        blinkingTimeLine = new Timeline(
                new KeyFrame(Duration.seconds(0.2), e -> {
                    nowPlayingBox.setStyle("");
                    bgColor = "-green-color";
                    if(isTheOutputAFile.get())
                        primaryStage.getIcons().setAll(iconRecordingIdle);
                    else
                        primaryStage.getIcons().setAll(iconLiveStreamIdle);
                }),
                new KeyFrame(Duration.seconds(1), e -> {
                    nowPlayingBox.setStyle("-fx-background-color: "+bgColor+";");
                    if(isTheOutputAFile.get())
                        primaryStage.getIcons().setAll(iconRecordingPlaying);
                    else
                        primaryStage.getIcons().setAll(iconLiveStreamPlaying);    }) // Blinking color (red)
        );

        blinkingTimeLine.setCycleCount(Timeline.INDEFINITE); // Repeat indefinitely
        blinkingTimeLine.setAutoReverse(true); // Auto-reverse to create the blinking effect

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
                consoleOutputTextFlow.getChildren().clear();
                startEncodingThread();
            } catch (Exception e) {
                throw new RuntimeException(e);
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
        clearOutputButton.setOnAction((ActionEvent e) -> consoleOutputTextFlow.getChildren().clear());


        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (ComboBox<String> audioInput : inputAudioSources) {
            audioInput.getItems().add("Not Used");
            for (Mixer.Info mixerInfo : mixerInfos) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                // Check if this mixer supports any TargetDataLine (input line)
                Line.Info[] targetLineInfos = mixer.getTargetLineInfo();
                boolean hasInput = false;
                for (Line.Info lineInfo : targetLineInfos) {
                    if (TargetDataLine.class.isAssignableFrom(lineInfo.getLineClass())) {
                        hasInput = true;
                        break;
                    }
                }
                if (hasInput) {
                    audioInput.getItems().add(mixerInfo.getName());
                }
            }
        }
        for (ComboBox<String> audioInputChannel : inputAudioSourcesChannel) {
            audioInputChannel.getItems().add("Join");
            audioInputChannel.getItems().add("Left");
            audioInputChannel.getItems().add("Right");
            }
        for (ComboBox<String> noiseReductionInput : inputNoiseReductionValues) {
            noiseReductionInput.getItems().addAll("0","1","2","3");
        }

        List<Webcam> webcams = Webcam.getWebcams();
        for (Webcam webcam : webcams) {
            this.inputVideoSource.getItems().add(webcam.getDevice().getName().substring(0, webcam.getDevice().getName().length() - 2));
        }
        // Load settings
        settings = SettingsUtil.loadSettings();
    }

    private void applyStyleOnOutputTypeChange(Boolean isOutputLiveStreamAndFile, Boolean isOutputAFile) {
        if(isOutputLiveStreamAndFile) {
            updateSceneStyle("livestreaming-and-record");
            currentInformationTextProperty.setValue(WAITING_TO_LIVESTREAM_AND_RECORD);
            primaryStage.getIcons().setAll(iconRecordingIdle);
            primaryStage.setTitle("Kadampa Festival - Livestreaming and Recording the session");
        }
        else if(isOutputAFile) {
            updateSceneStyle("light-blue");
            currentInformationTextProperty.setValue(WAITING_TO_RECORD);
            primaryStage.getIcons().setAll(iconRecordingIdle);
            primaryStage.setTitle("Kadampa Festival - Recording the session");
        }
        else {
            updateSceneStyle("livestream");
            currentInformationTextProperty.setValue(WAITING_TO_LIVESTREAM);
            primaryStage.getIcons().setAll(iconLiveStreamIdle);
            primaryStage.setTitle("Kadampa Festival - Live stream the session");
        }
    }


    private void updateSceneStyle(String color) {
        // Update root style
        //make sure the color style is define in the css
        mainScrollPane.getStyleClass().removeAll("bg-light-blue");
        mainScrollPane.getStyleClass().removeAll("bg-livestreaming-and-record");
        mainScrollPane.getChildrenUnmodifiable().forEach(child -> child.getStyleClass().removeAll("bg-light-blue"));
        mainScrollPane.getChildrenUnmodifiable().forEach(child -> child.getStyleClass().removeAll("bg-livestreaming-and-record"));

        if(!("".equals(color))) {
            mainScrollPane.getStyleClass().add("bg-" + color);
            // Update styles of the children
            mainScrollPane.getChildrenUnmodifiable().forEach(child -> child.getStyleClass().add("bg-" + color));
        }
    }
    private void applySettings() {
        Map<String, String> audioSettings = settings.getAudioSources();
        for (int i = 0; i < inputAudioSources.length; i++) {
            inputAudioSources[i].setValue(audioSettings.getOrDefault("audio" + i, "Not Used"));
            String languageKey = languageNames[i];
            ColorPicker colorPicker = languageColorPickers.get(languageKey);
            if (colorPicker != null) {
                String hexColor = settings.getLanguageColors().get(languageKey);
                if (hexColor != null) {
                    colorPicker.setValue(Color.web(hexColor));
                } else {
                    // Set a default color if not found in settings
                    colorPicker.setValue(Color.GREY); // or Color.web("#808080") for a specific grey
                }
            }
         }
        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            inputAudioSourcesChannel[i].setValue(settings.getAudioSourcesChannel().getOrDefault("audioChannel" + i, ""));
             }
        for (int i = 0; i < inputNoiseReductionValues.length; i++) {
            if(settings.getNoiseReductionLevel()!=null)
                inputNoiseReductionValues[i].setValue(settings.getNoiseReductionLevel().getOrDefault("noiseReductionLevel" + i, "0"));
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
        inputenMixDelay.setText(settings.getEnMixDelay());
        inputVideoSource.setValue(settings.getVideoSource());
        inputVideoSourceBuffer.setValue(settings.getVideoBuffer());
        inputAudioSourceBuffer.setValue(settings.getAudioBuffer());
        inputVideoBitrate.setValue(settings.getVideoBitrate());
        inputVideoPid.setText(settings.getVideoPID());
        inputSoundDelay.setText(settings.getDelay());
        inputPixelFormat.setValue(settings.getPixFormat());
        inputChooseBetweenUrlOrFile.setValue(settings.getOutputType());
        inputSrtResolution.setValue(settings.getSrtDef());
        inputOutputFileResolution.setValue(settings.getFileDef());
        inputEncoder.setValue(settings.getEncoder());
        inputSrtURL.setText(settings.getSrtURL());
        inputTimeNeededToOpenADevice.setText(settings.getTimeNeededToOpenADevice());
        inputOutputDirectory.setText(settings.getOutputDirectory());
        inputAudioBitrate.setValue(settings.getAudioBitrate());
        inputFramePerSecond.setValue(settings.getFps());
    }

    private void saveSettings() {
        for (int i = 0; i < inputAudioSources.length; i++) {
            settings.getAudioSources().put("audio" + i, inputAudioSources[i].getValue());
        }
        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            settings.getAudioSourcesChannel().put("audioChannel" + i, inputAudioSourcesChannel[i].getValue());
        }
        for (int i = 0; i < inputAudioSourcesChannel.length; i++) {
            settings.getNoiseReductionLevel().put("noiseReductionLevel" + i, inputNoiseReductionValues[i].getValue());
        }
        settings.setEnMixDelay((inputenMixDelay.getText()));
        settings.setVideoSource(inputVideoSource.getValue());
        settings.setVideoBitrate(inputVideoBitrate.getValue());
        settings.setAudioBuffer(inputAudioSourceBuffer.getValue());
        settings.setVideoBuffer(inputVideoSourceBuffer.getValue());
        settings.setVideoPID(inputVideoPid.getText());
        settings.setDelay(inputSoundDelay.getText());
        settings.setPixFormat(inputPixelFormat.getValue());
        settings.setOutputType(inputChooseBetweenUrlOrFile.getValue());
        settings.setSrtDef(inputSrtResolution.getValue());
        settings.setFileDef(inputOutputFileResolution.getValue());
        settings.setEncoder(inputEncoder.getValue());
        settings.setSrtURL(inputSrtURL.getText());
        settings.setTimeNeededToOpenADevice(inputTimeNeededToOpenADevice.getText());
        settings.setOutputDirectory(inputOutputDirectory.getText());
        settings.setAudioBitrate(inputAudioBitrate.getValue());
        settings.setFps(inputFramePerSecond.getValue());
        for (Map.Entry<String, ColorPicker> entry : languageColorPickers.entrySet()) {
            settings.getLanguageColors().put(entry.getKey(), entry.getValue().getValue().toString());
        }
        SettingsUtil.saveSettings(settings);
    }

    private ScrollPane buildUI() {
        VBox root = new VBox();
        ImageView logoView = new ImageView(new Image("https://kadampafestivals.org/wp-content/uploads/2024/01/New-NKT-IKBU-Logo-Kadampa-Blue.png"));
        logoView.setFitHeight(50);
        logoView.setFitWidth(50);
        // Create a label for the title
        Label titleLabel = new Label("International Kadampa Festival Streaming");
        titleLabel.getStyleClass().add("title");
        titleLabel.getStyleClass().add("primary-text");

        // Create an HBox to hold the logo and the title label
        HBox titleBox = new HBox(10, logoView, titleLabel);
        titleBox.setAlignment(Pos.CENTER); // Center align the contents of the HBox
        titleBox.setPadding(new Insets(20,0,20,0));

        nowPlayingLabel.textProperty().bind(currentInformationTextProperty);
        nowPlayingLabel.setStyle("-fx-font-size: 24px;");

        nowPlayingBox.setMinHeight(50);
        nowPlayingBox.setMaxHeight(50);
        nowPlayingBox.setMinWidth(WINDOW_WIDTH-5);
        nowPlayingBox.setAlignment(Pos.CENTER);

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
        settingTab.setContent(buildTabSettings());
        tabPane.getTabs().add(settingTab);


        Tab infoTab = new Tab("Information");
        infoTab.setClosable(false);
        infoTab.setContent(buildTabInfo());
        tabPane.getTabs().add(infoTab);

        root.getChildren().addAll(titleBox,nowPlayingBox,tabPane);

        mainScrollPane = new ScrollPane(root);
        return mainScrollPane;
    }


    public ComboBox<String>[] getInputAudioSources() {
        return inputAudioSources;
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
        infoVBox.getChildren().add(englishPID);
        infoVBox.getChildren().add(spanishPID);
        infoVBox.getChildren().add(frenchPID);
        infoVBox.getChildren().add(portuguesePID);
        infoVBox.getChildren().add(germanPID);
        infoVBox.getChildren().add(cantonesePID);
        infoVBox.getChildren().add(mandarinPID);
        infoVBox.getChildren().add(vietnamesePID);
        infoVBox.getChildren().add(italianPID);
        infoVBox.getChildren().add(finishPID);
        infoVBox.getChildren().add(greekPID);
        infoVBox.getChildren().add(extraLanguagePID);
        return infoVBox;
    }

    private void displayPIDInfo() {
        int pidVideo = Integer.parseInt(inputVideoPid.getText());
        videoPID.setText("PID Video: " + pidVideo);
        int currentAudioPID =  pidVideo+1;
        englishPID.setText("PID English: " + currentAudioPID);
        currentAudioPID ++;
        spanishPID.setText("PID Spanish: " + currentAudioPID);
        currentAudioPID ++;
        frenchPID.setText("PID French: " + currentAudioPID);
        currentAudioPID ++;
        portuguesePID.setText("PID Portuguese: " + currentAudioPID);
        currentAudioPID ++;
        germanPID.setText("PID German: " + currentAudioPID);
        currentAudioPID ++;
        cantonesePID.setText("PID Cantonese: " + currentAudioPID);
        currentAudioPID ++;
        mandarinPID.setText("PID Mandarin: " + currentAudioPID);
        currentAudioPID ++;
        vietnamesePID.setText("PID Vietnamese: " + currentAudioPID);
        currentAudioPID ++;
        italianPID.setText("PID Italian: " + currentAudioPID);
        currentAudioPID ++;
        finishPID.setText("PID Finish: " + currentAudioPID);
        currentAudioPID ++;
        greekPID.setText("PID Greek: " + currentAudioPID);
        currentAudioPID ++;
        extraLanguagePID.setText("PID extraLanguage: " + currentAudioPID);
    }

    private Node buildTabControlConsole() {
        consoleOutputScrollPane = new ScrollPane(consoleOutputTextFlow);
        consoleOutputScrollPane.setMinSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-300);
        consoleOutputScrollPane.setMaxSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-300);
        consoleOutputScrollPane.setFitToWidth(true); // Adjusts the width of the ScrollPane to fit its content
        consoleOutputScrollPane.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> manualScroll = true);

        consoleOutputScrollPane.vvalueProperty().addListener(v->{
            if (manualScroll) {
                this.doWeAutomaticallyScrollAtBottom = false;
                manualScroll = false;
            }
        });

        Label consoleLabel = new Label("Console Output");
        consoleLabel.setStyle("-fx-font-weight: bold;");
        VBox consoleBox = new VBox(10, consoleLabel, consoleOutputScrollPane);
        streamRecorder.isAliveProperty().addListener(b->{
            if(streamRecorder.isAliveProperty().getValue()) {
                Platform.runLater(()->{
                    startButton.setDisable(true);
                    if(isTheOutputAFile.getValue())  currentInformationTextProperty.setValue(CURRENTLY_RECORDING);
                    else currentInformationTextProperty.setValue(CURRENTLY_LIVESTREAMING);

                    blinkingTimeLine.play(); // Start the animation
                });
            }
            else {
                Platform.runLater(()->{
                    //We wait for 3 seconds to give the time to the thread to end properly
                    //(in the streamRecorder, we also wait 3s for the process to end before destroying it forcefully
                    PauseTransition delay = new PauseTransition(Duration.seconds(3)); // Pause for 3 seconds
                    delay.setOnFinished(event -> reinitialiseGraphicElements());
                    delay.play();
                });
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

        HBox buttonBox = new HBox(80, startButton, stopButton, clearOutputButton, showVUMetersButton);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(20, 0, 20, 0));
        consoleBox.setPadding(new Insets(0,0,0,10));
        return new VBox(10, buttonBox,consoleBox);
    }

    private void reinitialiseGraphicElements() {
        manualScroll = false;
        stopButton.setGraphic(stopPath);
        if(isTheOutputAFile.getValue())  currentInformationTextProperty.setValue(WAITING_TO_RECORD);
        else currentInformationTextProperty.setValue(WAITING_TO_LIVESTREAM);
        stopButton.setDisable(true);
        blinkingTimeLine.stop();
        nowPlayingBox.setStyle("-fx-background-color: -red-color;");
        startButton.setDisable(streamRecorder.isAliveProperty().getValue());
        if(isTheOutputFileAndUrl.get()) {
            primaryStage.getIcons().setAll(iconRecordingIdle);
        }
        else if(isTheOutputAFile.get())
            primaryStage.getIcons().setAll(iconRecordingIdle);
        else
            primaryStage.getIcons().setAll(iconLiveStreamIdle);
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
        col_2.setPercentWidth(60);
        ColumnConstraints col_3 = new ColumnConstraints();
        col_3.setPercentWidth(15);
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

        row++;
        //If it's empty, we select the first element
        if(inputVideoSource.getValue()==null || inputVideoSource.getValue().isEmpty()) inputVideoSource.setValue(inputVideoSource.getItems().get(0));
        addLanguageRow(inputGrid, row, "Prayers (for mix):", inputAudioSources[0], inputAudioSourcesChannel[0],null,null);
        Label EnMixDelayInfoLabel = new Label("?");
        EnMixDelayInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip toolt = new Tooltip("The delay we put on the englih mix so it synchronise the english coming  the speakers throw the microphone to avoid echo");
        Tooltip.install(EnMixDelayInfoLabel, toolt);
        toolt.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        toolt.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        toolt.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        toolt.getStyleClass().add("tooltip");
        Label EnMixDelayLabel = new Label("En MixDelay:");
        // Create an HBox to hold both labels
        HBox EnMixDelayLabelHBox = new HBox(1,EnMixDelayLabel,EnMixDelayInfoLabel);  // 5 is the spacing between the labels
        inputGrid.add(EnMixDelayLabelHBox, 3, row);
        row++;
        addLanguageRow(inputGrid, row, "English Mix:", inputAudioSources[1], inputAudioSourcesChannel[1],null, languageNames[1]);
        inputGrid.add(inputenMixDelay, 3, row);

        row++;
        Separator separator = new Separator();
        separator.setPrefWidth(WINDOW_WIDTH-50);
        inputGrid.add(separator, 0, row);
        GridPane.setColumnSpan(separator,2);
        Label noiseReductionInfoLabel = new Label("?");
        noiseReductionInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip toolti = new Tooltip("The number of iteration of the noise reduction filter. The noise reduction filter directory needs to be installed at the root of C:\n" +
                "The model are C:\\rnmodel\\sh.rnnn etc., and the files are downloadable here https://github.com/GregorR/rnnoise-nu");
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
        addLanguageRow(inputGrid, row, "English:", inputAudioSources[2], inputAudioSourcesChannel[2],inputNoiseReductionValues[2], languageNames[2]);
        row++;
        addLanguageRow(inputGrid, row, "Spanish:", inputAudioSources[3], inputAudioSourcesChannel[3],inputNoiseReductionValues[3], languageNames[3]);
        row++;
        addLanguageRow(inputGrid, row, "French:", inputAudioSources[4], inputAudioSourcesChannel[4],inputNoiseReductionValues[4], languageNames[4]);
        row++;
        addLanguageRow(inputGrid, row, "Portuguese:", inputAudioSources[5], inputAudioSourcesChannel[5],inputNoiseReductionValues[5], languageNames[5]);
        row++;
        addLanguageRow(inputGrid, row, "German:", inputAudioSources[6], inputAudioSourcesChannel[6],inputNoiseReductionValues[6], languageNames[6]);
        row++;
        addLanguageRow(inputGrid, row, "Cantonese:", inputAudioSources[7], inputAudioSourcesChannel[7],inputNoiseReductionValues[7], languageNames[7]);
        row++;
        addLanguageRow(inputGrid, row, "Mandarin:", inputAudioSources[8], inputAudioSourcesChannel[8],inputNoiseReductionValues[8], languageNames[8]);
        row++;
        addLanguageRow(inputGrid, row, "Vietnamese:", inputAudioSources[9], inputAudioSourcesChannel[9],inputNoiseReductionValues[9], languageNames[9]);
        row++;
        addLanguageRow(inputGrid, row, "Italian:", inputAudioSources[10], inputAudioSourcesChannel[10],inputNoiseReductionValues[10], languageNames[10]);
        row++;
        addLanguageRow(inputGrid, row, "Greek:", inputAudioSources[11], inputAudioSourcesChannel[11],inputNoiseReductionValues[11], languageNames[11]);

        int comboWith = 100;
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(13);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(20);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(15);
        ColumnConstraints col4 = new ColumnConstraints();
        col4.setPercentWidth(22);
        ColumnConstraints col5 = new ColumnConstraints();
        col5.setPercentWidth(15);
        ColumnConstraints col6 = new ColumnConstraints();
        col6.setPercentWidth(20);
        inputGrid2.getColumnConstraints().addAll(col1, col2, col3,col4,col5,col6);

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
        Region spac = new Region();
        spac.setMinWidth(54);
        spac.setMaxWidth(54);
        outPutTypeLabelHBox.getChildren().addAll(chooseOutputTypeLabel,chooseOutputTypeLabelInfo,spac, inputChooseBetweenUrlOrFile);
        inputGrid2.add(outPutTypeLabelHBox, 0, row);
        GridPane.setColumnSpan(outPutTypeLabelHBox, 2);

        Label timeNeededinfoLabel = new Label("?");
        timeNeededinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipTimeNeededOutput = new Tooltip("The time needed to open an audio device. This is important because each audio device takes time to open so it it async the audios.\n" +
                "This parameter is used to readjust the sync. To know this value, in the ffmeg output, look for the start value for device 1 and for device 2, and look for the difference.\n Usually, it's around 650ms");
        Tooltip.install(timeNeededinfoLabel, tooltipTimeNeededOutput);
        tooltipTimeNeededOutput.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipTimeNeededOutput.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipTimeNeededOutput.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipTimeNeededOutput.getStyleClass().add("tooltip");
        Label timeNeededLabel = new Label("Time needed to open a device (in ms):");
        HBox outputTimeNeededLabelHBox = new HBox(5);  // 5 is the spacing between the labels
        Region spac2 = new Region();
        spac.setMinWidth(54);
        spac.setMaxWidth(54);
        inputTimeNeededToOpenADevice.setMaxWidth(50);
        outputTimeNeededLabelHBox.getChildren().addAll(timeNeededLabel,timeNeededinfoLabel,spac2, inputTimeNeededToOpenADevice);
        inputGrid2.add(outputTimeNeededLabelHBox, 2, row);
        GridPane.setColumnSpan(outputTimeNeededLabelHBox, 2);

        Label videoPIDinfoLabel = new Label("?");
        videoPIDinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip3 = new Tooltip("Value between (32 and 240). Information needed on some streaming platform.\nThe PID (Packet Identifier) in a video stream is used in MPEG transport stream (TS) containers to identify packets within the stream.\n Each type of data (video, audio, subtitles, etc.) in the transport stream is assigned a unique PID, which allows demultiplexing devices to separate and correctly process different types of data.");
        Tooltip.install(videoPIDinfoLabel, tooltip3);
        tooltip3.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip3.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip3.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip3.getStyleClass().add("tooltip");
        Label videoPIDLabel = new Label("PID:");
        // Create an HBox to hold both labels
        HBox videoPIDLabelHBox = new HBox(1);  // 5 is the spacing between the labels
        videoPIDLabelHBox.getChildren().addAll(videoPIDLabel, videoPIDinfoLabel, inputVideoPid);
        inputGrid2.add(videoPIDLabelHBox, 4, row);
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
        outputUrlHBox.setMinWidth(WINDOW_WIDTH);
        Region space = new Region();
        space.setMinWidth(50);
        space.setMaxWidth(50);
        inputSrtURL.setMinWidth(670);
        inputSrtURL.setMaxWidth(670);
        outputUrlHBox.getChildren().setAll(outputUrlLabel,outputUrlinfoLabel,space, inputSrtURL);

        inputGrid2.add(outputUrlHBox, 0, row);
        GridPane.setColumnSpan(outputUrlHBox, 6);
        if(inputSrtURL.getText()==null) inputSrtURL.setText("");
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
        inputOutputDirectory.setMinWidth(600);
        inputOutputDirectory.setMaxWidth(600);
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
        row++;

        Label advancedOutputLabel = new Label("Advanced option");
        advancedOutputLabel.setStyle("-fx-font-weight: bold;");
        inputGrid2.add(advancedOutputLabel,0,row);
        GridPane.setColumnSpan(advancedOutputLabel, 6);
        row++;

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
        inputGrid2.add(delayHBox, 0, row);
        if(inputSoundDelay.getText()==null || inputSoundDelay.getText().isEmpty()) inputSoundDelay.setText("0");
        inputGrid2.add(inputSoundDelay, 1, row);
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
        inputGrid2.add(audioChannelLabelHBox2, 2, row);

        //If it's empty, we select the first element
        if(inputPixelFormat.getValue()==null || inputPixelFormat.getValue().isEmpty()) inputPixelFormat.setValue(inputPixelFormat.getItems().get(0));
        inputGrid2.add(inputPixelFormat, 3, row);
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
        inputGrid2.add(outputResLabelHBox, 4, row);
        if(inputSrtResolution.getValue()==null || inputSrtResolution.getValue().isEmpty()) inputSrtResolution.setValue(inputSrtResolution.getItems().get(0));
        inputGrid2.add(inputSrtResolution, 5, row);
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
        inputGrid2.add(audioBitrateLabelHBox, 0, row);
        //If it's empty, we select the first element
        if(inputAudioBitrate.getValue()==null || inputAudioBitrate.getValue().isEmpty()) inputAudioBitrate.setValue(inputAudioBitrate.getItems().get(0));
        inputGrid2.add(inputAudioBitrate, 1, row);
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
        inputGrid2.add(codecLabelHBox, 2, row);
        inputEncoder.setPrefWidth(comboWith);
        inputGrid2.add(inputEncoder, 3, row);

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
        inputGrid2.add(fpsLabelHBox, 4, row);
        inputGrid2.add(inputFramePerSecond, 5, row);
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
        inputGrid2.add(videoBitrateLabelHBox, 0, row);
        inputGrid2.add(inputVideoBitrate, 1, row);
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
        inputGrid2.add(videoBufferLabelHBox, 2, row);
        inputGrid2.add(inputVideoSourceBuffer, 3, row);

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
        inputGrid2.add(audioBufferLabelHBox, 4, row);
        inputGrid2.add(inputAudioSourceBuffer, 5, row);
        //If it's empty, we select the first element
        if(inputAudioSourceBuffer.getValue()==null || inputAudioSourceBuffer.getValue().isEmpty()) inputAudioSourceBuffer.setValue(inputAudioSourceBuffer.getItems().get(0));
        inputAudioSourceBuffer.setPrefWidth(comboWith);
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

        Button showVUMetersButton = new Button("Show VU Meters");
        showVUMetersButton.getStyleClass().add("event-button");
        showVUMetersButton.setOnAction(event -> {
            if (vuMeterPanel.isShowing()) {
                vuMeterPanel.toFront();
            } else {
                vuMeterPanel.show();
            }
        });
        HBox vuMeterHBox = new HBox(showVUMetersButton);
        vuMeterHBox.setAlignment(Pos.CENTER);
        vuMeterHBox.setPadding(new Insets(0,0,20,0));

        return new VBox(inputGrid,inputGrid2,saveHBox, vuMeterHBox);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        ScrollPane pane = buildUI();
        scene = new Scene(pane, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add("javafx@main.css");
        primaryStage.setScene(scene);
        primaryStage.setTitle("FFmpeg GUI");
        primaryStage.setOnCloseRequest(e -> {
            // Call your method here
            handleClose();
        });
        primaryStage.show();
        applySettings();
        applyStyleOnOutputTypeChange(isTheOutputFileAndUrl.get(),isTheOutputAFile.get());
        vuMeterPanel = new LevelMeterPanel(inputAudioSources, inputAudioSourcesChannel, settings.getLanguageColors());

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

        audioInput.valueProperty().addListener((observable, oldValue, newValue) -> {
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

    private void handleClose() {
        if (vuMeterPanel != null) {
            vuMeterPanel.closeVUMeters();
        }
        stopEncodingThread();
    }

    private void startEncodingThread() {
        if(!checkParameters()) {
            return;
        }
        displayPIDInfo();
        bgColor = "-green-color";
        playingError = false;
        doWeAutomaticallyScrollAtBottom = true;
        streamRecorder.setSrtUrl(inputSrtURL.getText());
        streamRecorder.setEnMixDelay(Integer.parseInt(inputenMixDelay.getText()));
        streamRecorder.setOutputDirectory(inputOutputDirectory.getText());
        streamRecorder.initialiseVideoDevice(inputVideoSource.getValue());
        streamRecorder.initialiseAudioDevices(Arrays.stream(inputAudioSources).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputAudioSourcesChannel).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputNoiseReductionValues).map(ComboBox::getValue).toArray(String[]::new));
        streamRecorder.setPixelFormat(inputPixelFormat.getValue());
        streamRecorder.setOutputResolution(inputSrtResolution.getValue());
        streamRecorder.setDelay(Integer.parseInt(inputSoundDelay.getText()));
        streamRecorder.setVideoPid(Integer.parseInt(inputVideoPid.getText()));
        streamRecorder.setEncoder(inputEncoder.getValue());
        streamRecorder.setTimeNeededToOpenADevice(Integer.parseInt(inputTimeNeededToOpenADevice.getText()));
        streamRecorder.setAudioBitrate(inputAudioBitrate.getValue());
        streamRecorder.setAudioBufferSize(inputAudioSourceBuffer.getValue());
        streamRecorder.setVideoBitrate(inputVideoBitrate.getValue());
        streamRecorder.setVideoBufferSize(inputVideoSourceBuffer.getValue());

        if(isTheOutputFileAndUrl.get())  streamRecorder.setOutputType(StreamRecorderRunnable.FILE_AND_URL);
        else if(isTheOutputAFile.get())  streamRecorder.setOutputType(StreamRecorderRunnable.FILE);
        else if(isTheOutputAURL.get())   streamRecorder.setOutputType(StreamRecorderRunnable.URL);

        streamRecorder.setFps(Integer.parseInt(inputFramePerSecond.getValue()));
        textAreaInfo.setText(streamRecorder.getFFMpegCommand());
        playerURLTextField.setText(buildPlayerURL());
        stopButton.setDisable(false);
        streamRecorder.getOutputLineProperty().addListener((observable, oldValue, newValue) -> Platform.runLater(() -> appendToConsole(newValue, oldValue,null)));
        encodingThread = new Thread(() -> {
            // Your existing code for streamRecorder.run() goes here
            streamRecorder.setSrtUrl(inputSrtURL.getText());
            streamRecorder.initialiseVideoDevice(inputVideoSource.getValue());
            streamRecorder.initialiseAudioDevices(Arrays.stream(inputAudioSources).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputAudioSourcesChannel).map(ComboBox::getValue).toArray(String[]::new),Arrays.stream(inputNoiseReductionValues).map(ComboBox::getValue).toArray(String[]::new));
            try {
                streamRecorder.run();
            } catch (Exception e) {
                appendToConsole(e.toString(),"",Color.RED);
            }
        });
        encodingThread.start();
    }

    private String buildPlayerURL() {
        String outputUrl = inputSrtURL.getText();
        String rValue = "";
        int startIndex = outputUrl.indexOf("r=") + 2; // Exclude "r="
        int commaIndex = outputUrl.indexOf(',', startIndex);
        if (commaIndex != -1) {
            rValue = outputUrl.substring(startIndex, commaIndex);
        }
        String baseURL = "https://player.castr.com/"+rValue;
        String parameters = "?tracks=";
        boolean firstParameter = true;
        if(!inputAudioSources[2].getValue().equals("Not Used")) {
            parameters +="English";
            firstParameter = false;
        }
        if(!inputAudioSources[3].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Español";
            firstParameter = false;
        }
        if(!inputAudioSources[4].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Français";
            firstParameter = false;
        }
        if(!inputAudioSources[5].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Português";
            firstParameter = false;
        }
        if(!inputAudioSources[6].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Deutsch";
            firstParameter = false;
        }
        if(!inputAudioSources[7].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="廣東話";
            firstParameter = false;
        }
        if(!inputAudioSources[8].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="普通话";
            firstParameter = false;
        }
        if(!inputAudioSources[9].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Tiếng%20Việt";
            firstParameter = false;
        }
        if(!inputAudioSources[10].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Italiano";
            firstParameter = false;
        }
        if(!inputAudioSources[11].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Suomi";
            firstParameter = false;
        }
        return baseURL+parameters;
    }

    private boolean checkParameters() {
        boolean result = true;
        if(inputAudioSources[0].getValue().equals("Not Used")) {
            appendToConsole("Please select an audio source for the prayers","",Color.RED);
            result = false;
        }
        if(inputAudioSources[1].getValue().equals("Not Used")) {
            appendToConsole("Please select an audio source for the english to be mixed with the translation","",Color.RED);
            result = false;
        }
        File firstRnnnFile = new File("C:/rnmodel/sh.rnnn");
        if(!firstRnnnFile.exists()) {
            appendToConsole("The model file for the noise reduction filter is not found. It should be at " +  firstRnnnFile.getPath() +
                    ".\nYou can dowwload it from here : https://github.com/GregorR/rnnoise-models","",Color.RED);
            result = false;
        }
        File secondRnnfile = new File("C:/rnmodel/bd.rnnn");
        if(!secondRnnfile.exists()) {
            appendToConsole("The model file for the noise reduction filter is not found. It should be at " + secondRnnfile.getPath() +
                    ".\nYou can dowwload it from here : https://github.com/GregorR/rnnoise-models","",Color.RED);
            result = false;
        }
        if(inputVideoPid.getText().isEmpty()) {
            appendToConsole("Please Fill the Video PID Field (see Tooltip for help)","",Color.RED);
            result = false;
        }
        try {
            Integer.parseInt(inputVideoPid.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Video PID must be an integer number (See Tooltip for help).","",Color.RED);
            result= false;
        }
        try {
            Integer.parseInt(inputSoundDelay.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Delay must be an integer number (See Tooltip for help).","",Color.RED);
            result= false;
        }
        if(inputSoundDelay.getText().isEmpty()) {
            appendToConsole("Please Fill the Delay (See Tooltip for help)","",Color.RED);
            result= false;
        }
        try {
            Integer.parseInt(inputTimeNeededToOpenADevice.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Time needed to open a device Field must be an integer number (See Tooltip for help).","",Color.RED);
            result= false;
        }
        if(inputSoundDelay.getText().isEmpty()) {
            appendToConsole("Please Fill the Time needed to open a device Field (See Tooltip for help)","",Color.RED);
            result= false;
        }
        if(inputChooseBetweenUrlOrFile.getValue().equals("File")) {
            String directory = inputOutputDirectory.getText();
            File file = new File(directory);
            if (!file.isDirectory()) {
                    appendToConsole(directory + " is not a directory. Please enter a valid directory for the file output.","",Color.RED);
                    result = false;
            }
            else {
                long usableSpace = file.getUsableSpace();
                int usableSpaceInGB = (int) (usableSpace / (1024.0 * 1024.0 * 1024.0));
                int minimumGBNecessary = 15;
                if(usableSpaceInGB <minimumGBNecessary) {
                    appendToConsole("There is less than " +minimumGBNecessary+"GB available on the disk (" + usableSpaceInGB + " GB available). Free some space before recording", "", Color.RED);
                    // Convert bytes to a more readable format (e.g., megabytes, gigabytes)
                    result = false;
                }
            }
        }
        else {
            String url = inputSrtURL.getText();
            if (!url.startsWith("srt://")) {
                appendToConsole(url + " is not a valid srt url. Please enter a valid srt url to stream.","",Color.RED);
                return false;
            }
        }

        if(!result) {
            appendToConsole("--------------------------------------------","",Color.RED);

            appendToConsole("Please correct before starting the command.","",Color.RED);

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
                    // FFmpeg process exited with an error
                    Platform.runLater(()->appendToConsole("FFmpeg process exited with error code " + exitCode,"",Color.BLUE));
                }
            } catch (InterruptedException e) {
                appendToConsole(e.toString(),"",Color.RED);
            }
        }
    }

    private void appendToConsole(String newLine, String oldLine, Color color) {
        Text text = new Text(newLine + "\n");
        if (color!=null) {
            text.setFill(color);
        }
        String[] infoTerms = {"start:"};
        String[] errorTerms = {"error", "fatal", "Failed", "Invalid", "Unable","dropped","failed","Incompatible"};
        String[] warningTerms = {"ignored","deprecated","unsupported", "Could not","Deprecated","incorrect","confused"};
        String infoRegex = String.join("|", infoTerms);
        String errorRegex = String.join("|", errorTerms);
        String warningRegex = String.join("|", warningTerms);
        Pattern infoPattern = Pattern.compile(infoRegex,Pattern.CASE_INSENSITIVE);
        Pattern errorPattern = Pattern.compile(errorRegex,Pattern.CASE_INSENSITIVE);
        Pattern warningPattern = Pattern.compile(warningRegex,Pattern.CASE_INSENSITIVE);
        Matcher info = infoPattern.matcher(newLine);
        Matcher warningMatcher = warningPattern.matcher(newLine);
        Matcher errorMatcher = errorPattern.matcher(newLine);

        if (info.find()) {
            text.setFill(Color.BLUE);
            int startIndex = text.toString().indexOf("start: ");
            int endIndex = text.toString().indexOf(",", startIndex);
            if (startIndex != -1 && endIndex != -1) {
                String startTime = text.toString().substring(startIndex+ "start: ".length(), endIndex);
                if (firstOpeningDeviceStartupTime == 0) {
                    firstOpeningDeviceStartupTime = (int) (Double.parseDouble(startTime) * 1000);
                }
                if (secondOpeningDeviceStartupTime == 0) {
                    secondOpeningDeviceStartupTime = (int) (Double.parseDouble(startTime) * 1000);
                     }
                else {
                    firstOpeningDeviceStartupTime = secondOpeningDeviceStartupTime;
                    secondOpeningDeviceStartupTime = (int) (Double.parseDouble(startTime) * 1000);
                }
                int timeToOpen = secondOpeningDeviceStartupTime-firstOpeningDeviceStartupTime;
                appendToConsole("Time to open the device: " + timeToOpen+" ms","",Color.RED );
            }
        }
        if (warningMatcher.find()) text.setFill(Color.ORANGE);
        if (errorMatcher.find()) {
            text.setFill(Color.RED);
            bgColor = "-orange-color";
            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() {
                    if (!playingError) {
                        playingError = true;
                        String beepSound = getClass().getResource("/error.wav").toString();
                        Media media = new Media(beepSound);
                        MediaPlayer mediaPlayer = new MediaPlayer(media);
                        CountDownLatch latch = new CountDownLatch(1);

                        mediaPlayer.play();
                        mediaPlayer.setOnEndOfMedia(() -> {
                            Platform.runLater(() -> {
                                playingError = false;
                                latch.countDown();
                            });
                        });

                        try {
                            latch.await();  // Wait for the media to finish
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    return null;
                }
            };

            Thread thread = new Thread(task);
            thread.setDaemon(false); // Ensure the thread does not stop when the application exits
            thread.start();
        }
        if(oldLine!=null && oldLine.startsWith("frame=") && newLine.startsWith("frame="))
        {
            int lastIndex = consoleOutputTextFlow.getChildren().size() - 1;
            if (lastIndex >= 0) {
                Node lastNode = consoleOutputTextFlow.getChildren().get(lastIndex);
                consoleOutputTextFlow.getChildren().remove(lastNode);
            }
        }
        consoleOutputTextFlow.getChildren().add(text);
        if(doWeAutomaticallyScrollAtBottom) {
            consoleOutputScrollPane.setVvalue(1.0);
        }
    }
    public static void main(String[] args) {
        launch(args);
    }
}
