package org.kadampa.festivalstreaming;

import com.github.sarxos.webcam.Webcam;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FFmpegGUI extends Application {
    private final ComboBox<String>[] audioInputs;
    private final ComboBox<String>[] audioInputsChannel;

    private final TextField[] audioPidFields;
    private final TextField videoPidField;
    private final ComboBox<String> videoInput;
    private final ComboBox<String> videoInputBuffer;
    private final ComboBox<String> audioInputBuffer;
    private final ComboBox<String> videoBitrate;
    private final TextField delayInput;
    private final ComboBox<String> pixFormatInput;
    private final ComboBox<String> srtDefInput;
    private final ComboBox<String> fileDefInput;
    private final ComboBox<String> encoderInput;
    private final TextField srtDestInput;
    private final TextField outputFileInput;
    private final ComboBox<String> audioBitrateInput;
    private final ComboBox<String> framePerSecond;
    //private final TextArea consoleOutputTextArea;
    private final TextFlow consoleOutputTextArea;
    private final Button startButton;
    private final Button stopButton;
    private final Button clearOutputButton;
    private Thread encodingThread;
    private final StreamRecorderRunnable streamRecorder = new StreamRecorderRunnable();
    private final Settings settings;
    private ScrollPane consoleOutputScrollPane;
    private final SVGPath stopPath = new SVGPath();
    private final TextArea textAreaInfo = new TextArea();

    private static final int WINDOW_WIDTH = 900;
    private static final int WINDOW_HEIGHT = 900;

    private static final double TOOLTIP_DELAY = 0.2;
    private static final int TOOLTIP_DURATION=10;

    public FFmpegGUI() {
        int MAX_NUMBER_OF_LANGUAGES = 12;
        audioInputs = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        audioInputsChannel = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        audioPidFields = new TextField[MAX_NUMBER_OF_LANGUAGES];
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i] = new ComboBox<>();
            audioInputsChannel[i] = new ComboBox<>();
        }
        videoInput = new ComboBox<>();
        videoPidField = new TextField();
        videoPidField.setMaxWidth(75);
        delayInput = new TextField();
        audioBitrateInput = new ComboBox<String>();
        audioBitrateInput.getItems().add("128k");
        audioBitrateInput.getItems().add("256k");
        audioBitrateInput.getItems().add("512k");
        framePerSecond = new ComboBox<String>();
        framePerSecond.getItems().add("30");
        videoBitrate= new ComboBox();
        videoBitrate.getItems().add("1000k");
        videoBitrate.getItems().add("1500k");
        videoBitrate.getItems().add("2000k");
        videoBitrate.getItems().add("2500k");
        videoBitrate.getItems().add("3000k");
        videoBitrate.getItems().add("3500k");
        videoBitrate.getItems().add("4000k");
        videoBitrate.getItems().add("4500k");
        videoBitrate.getItems().add("5000k");
        videoBitrate.getItems().add("5500k");
        videoBitrate.getItems().add("6000k");
        videoBitrate.getItems().add("6500k");
        videoBitrate.getItems().add("7000k");
        videoBitrate.getItems().add("7500k");
        videoBitrate.getItems().add("8500k");

        videoInputBuffer = new ComboBox();
        videoInputBuffer.getItems().add("256M");
        videoInputBuffer.getItems().add("512M");
        videoInputBuffer.getItems().add("1024M");

        audioInputBuffer = new ComboBox();
        audioInputBuffer.getItems().add("64M");
        audioInputBuffer.getItems().add("128M");
        audioInputBuffer.getItems().add("256M");

        pixFormatInput = new ComboBox<String>();
        pixFormatInput.getItems().add("yuv420p");

        srtDefInput = new ComboBox<String>();
        srtDefInput.getItems().add("hd480");
        srtDefInput.getItems().add("hd720");
        srtDefInput.getItems().add("hd1080");

        fileDefInput = new ComboBox<>();
        encoderInput = new ComboBox<>();
        encoderInput.getItems().add("libx264");
        encoderInput.getItems().add("h264_nvenc");
        encoderInput.getItems().add("h264_qsv");
        encoderInput.getItems().add("h264_amf");
        srtDestInput = new TextField();
        outputFileInput = new TextField();
        consoleOutputTextArea = new TextFlow();
        //consoleOutputTextArea = new TextArea();
        startButton = new Button("Start");
        startButton.getStyleClass().add("event-button");
        startButton.getStyleClass().add("success-button");
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
        // Load settings
        settings = SettingsUtil.loadSettings();
        applySettings();

        // Add action listeners
        startButton.setOnAction(event -> {
            try {
                consoleOutputTextArea.getChildren().clear();
                startEncodingThread();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setMaxSize(20,20);
        progressIndicator.setStyle("-fx-progress-color: white;"+
                "-fx-stroke: white;");

        stopButton.setOnAction((event -> {
            stopButton.setGraphic(progressIndicator);
            stopEncodingThread();
        }));

        //clearOutputButton.setOnAction((ActionEvent e) ->consoleOutputTextArea.getChildren().clear());
        clearOutputButton.setOnAction((ActionEvent e) ->consoleOutputTextArea.getChildren().clear());


        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (ComboBox<String> audioInput : audioInputs) {
            audioInput.getItems().add("Not Used");
            for (Mixer.Info mixerInfo : mixerInfos) {
                if (!mixerInfo.getDescription().equals("Port Mixer")) {
                    if (!mixerInfo.getName().contains("OUT"))
                        audioInput.getItems().add(mixerInfo.getName());
                }
            }
        }
        for (ComboBox<String> audioInputChannel : audioInputsChannel) {
            audioInputChannel.getItems().add("Join");
            audioInputChannel.getItems().add("Left");
            audioInputChannel.getItems().add("Right");
            }

        List<Webcam> webcams = Webcam.getWebcams();
        for (Webcam webcam : webcams) {
            this.videoInput.getItems().add(webcam.getDevice().getName().substring(0, webcam.getDevice().getName().length() - 2));
        }
    }
    private void applySettings() {
        Map<String, String> audioSettings = settings.getAudioInputs();
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i].setValue(audioSettings.getOrDefault("audio" + i, "Not Used"));
        }
        for (int i = 0; i < audioInputsChannel.length; i++) {
            audioInputsChannel[i].setValue(settings.getAudioInputsChannel().getOrDefault("audioChannel" + i, ""));
        }

        videoInput.setValue(settings.getVideoInput());
        videoInputBuffer.setValue(settings.getVideoBufferInput());
        audioInputBuffer.setValue(settings.getAudioBufferInput());
        videoBitrate.setValue(settings.getVideoBitrateInput());
        videoPidField.setText(settings.getVideoPID());
        delayInput.setText(settings.getDelay());
        pixFormatInput.setValue(settings.getPixFormat());
        srtDefInput.setValue(settings.getSrtDef());
        fileDefInput.setValue(settings.getFileDef());
        encoderInput.setValue(settings.getEncoder());
        srtDestInput.setText(settings.getSrtDest());
        outputFileInput.setText(settings.getOutputFile());
        audioBitrateInput.setValue(settings.getAudioBitrate());
        framePerSecond.setValue(settings.getFps());
    }

    private void saveSettings() {
        for (int i = 0; i < audioInputs.length; i++) {
            settings.getAudioInputs().put("audio" + i, audioInputs[i].getValue());
        }
        for (int i = 0; i < audioInputsChannel.length; i++) {
            settings.getAudioInputsChannel().put("audioChannel" + i, audioInputsChannel[i].getValue());
        }
        settings.setVideoInput(videoInput.getValue());
        settings.setVideoBitrateInput(videoBitrate.getValue());
        settings.setAudioBufferInput(audioInputBuffer.getValue());
        settings.setVideoBufferInput(videoInputBuffer.getValue());
        settings.setVideoPID(videoPidField.getText());
        settings.setDelay(delayInput.getText());
        settings.setPixFormat(pixFormatInput.getValue());
        settings.setSrtDef(srtDefInput.getValue());
        settings.setFileDef(fileDefInput.getValue());
        settings.setEncoder(encoderInput.getValue());
        settings.setSrtDest(srtDestInput.getText());
        settings.setOutputFile(outputFileInput.getText());
        settings.setAudioBitrate(audioBitrateInput.getValue());
        settings.setFps(framePerSecond.getValue());
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
        TabPane tabPane = new TabPane();
        tabPane.setPrefWidth(WINDOW_WIDTH-2);

        // Language Settings Tab
        Tab languageTab = new Tab("Settings");
        languageTab.setClosable(false);
        languageTab.setContent(buildTabSettings());
        tabPane.getTabs().add(languageTab);

        // Control and Console Tab
        Tab controlConsoleTab = new Tab("Control and Console");
        controlConsoleTab.setClosable(false);
        controlConsoleTab.setContent(buildTabControlConsole());
        tabPane.getTabs().add(controlConsoleTab);

        Tab infoTab = new Tab("Informations");
        infoTab.setClosable(false);
        infoTab.setContent(buildTabInfo());
        tabPane.getTabs().add(infoTab);


        root.getChildren().addAll(titleBox,tabPane);
        return new ScrollPane(root);
    }

    private Node buildTabInfo() {
        return new VBox(10, textAreaInfo);
    }

    private Node buildTabControlConsole() {
        consoleOutputScrollPane = new ScrollPane(consoleOutputTextArea);
        consoleOutputScrollPane.setMouseTransparent(false);
        consoleOutputScrollPane.setMinSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-250);
        consoleOutputScrollPane.setMaxSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-250);
        consoleOutputScrollPane.setFitToWidth(true); // Adjusts the width of the ScrollPane to fit its content
        VBox consoleBox = new VBox(10, new Label("Console Output"), consoleOutputScrollPane);
        streamRecorder.isAliveProperty().addListener(b->startButton.setDisable(streamRecorder.isAliveProperty().getValue()));
        streamRecorder.isAliveProperty().addListener(b-> {
            stopButton.setDisable(streamRecorder.isAliveProperty().not().getValue());
            stopButton.setGraphic(stopPath);
        });

        HBox buttonBox = new HBox(80, startButton, stopButton,clearOutputButton);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(20, 0, 20, 0));
        consoleBox.setPadding(new Insets(0,0,0,10));
        return new VBox(10, buttonBox, consoleBox);
    }

    private Node buildTabSettings() {
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.setPadding(new Insets(10));
        GridPane inputGrid2 = new GridPane();
        inputGrid2.setHgap(10);
        inputGrid2.setVgap(10);
        inputGrid2.setPadding(new Insets(10));

        ColumnConstraints col_1 = new ColumnConstraints();
        col_1.setPercentWidth(15); // Définit la largeur de la première colonne à 30%
        ColumnConstraints col_2 = new ColumnConstraints();
        col_2.setPercentWidth(60); // Définit la largeur de la deuxième colonne à 50%
        ColumnConstraints col_3 = new ColumnConstraints();
        col_3.setPercentWidth(15); // Définit la largeur de la troisième colonne à 20%
        ColumnConstraints col_4 = new ColumnConstraints();
        col_4.setPercentWidth(10); // Définit la largeur de la première colonne à 30%

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
        // Create an HBox to hold both labels
        HBox videoInputLabelHBox = new HBox(1);  // 5 is the spacing between the labels
        videoInputLabelHBox.getChildren().addAll(videoInputLabel, videoInputinfoLabel);

        inputGrid.add(videoInputLabelHBox, 0, row);
        inputGrid.add(videoInput, 1, row);
        videoInput.setPrefWidth(450);

        Label videoPIDinfoLabel = new Label("?");
        videoPIDinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip2 = new Tooltip("Value between (32 and 240). Information needed on some streaming platform.\nThe PID (Packet Identifier) in a video stream is used in MPEG transport stream (TS) containers to identify packets within the stream.\n Each type of data (video, audio, subtitles, etc.) in the transport stream is assigned a unique PID, which allows demultiplexing devices to separate and correctly process different types of data.");
        Tooltip.install(videoPIDinfoLabel, tooltip2);
        tooltip2.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip2.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip2.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip2.getStyleClass().add("tooltip");
        Label videoPIDLabel = new Label("PID:");
        // Create an HBox to hold both labels
        HBox videoPIDLabelHBox = new HBox(1);  // 5 is the spacing between the labels
        videoPIDLabelHBox.getChildren().addAll(videoPIDLabel, videoPIDinfoLabel);
        inputGrid.add(videoPIDLabelHBox, 2, row);
        inputGrid.add(videoPidField, 3, row);
        row++;

        addLanguageRow(inputGrid, row, "English:", audioInputs[0], audioInputsChannel[0]);
        row++;
        addLanguageRow(inputGrid, row, "Spanish:", audioInputs[1], audioInputsChannel[1]);
        row++;
        addLanguageRow(inputGrid, row, "French:", audioInputs[2], audioInputsChannel[2]);
        row++;
        addLanguageRow(inputGrid, row, "Portuguese:", audioInputs[3], audioInputsChannel[3]);
        row++;
        addLanguageRow(inputGrid, row, "German:", audioInputs[4], audioInputsChannel[4]);
        row++;
        addLanguageRow(inputGrid, row, "Cantonese:", audioInputs[5], audioInputsChannel[5]);
        row++;
        addLanguageRow(inputGrid, row, "Mandarin:", audioInputs[6], audioInputsChannel[6]);
        row++;
        addLanguageRow(inputGrid, row, "Vietnamese:", audioInputs[7], audioInputsChannel[7]);
        row++;
        addLanguageRow(inputGrid, row, "Italian:", audioInputs[8], audioInputsChannel[8]);
        row++;
        addLanguageRow(inputGrid, row, "Finnish:", audioInputs[9], audioInputsChannel[9]);
        row++;
        addLanguageRow(inputGrid, row, "Greek:", audioInputs[10], audioInputsChannel[10]);
        row++;
        addLanguageRow(inputGrid, row, "Extra Language 1:", audioInputs[11], audioInputsChannel[11]);
        row++;

        int comboWith = 100;
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(13); // Définit la largeur de la première colonne à 30%
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(20); // Déf20it la largeur de la deuxième colonne à 50%
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(15); // Définit la largeur de la troisième colonne à 20%
        ColumnConstraints col4 = new ColumnConstraints();
        col4.setPercentWidth(22); // Définit la largeur de la première colonne à 30%
        ColumnConstraints col5 = new ColumnConstraints();
        col5.setPercentWidth(15); // Définit la largeur de la deuxième colonne à 50%
        ColumnConstraints col6 = new ColumnConstraints();
        col6.setPercentWidth(20); // Définit la largeur de la troisième colonne à 20%
        inputGrid2.getColumnConstraints().addAll(col1, col2, col3,col4,col5,col6);

        row = 0;
        inputGrid2.add(new Label("Output url:"), 0, row);
        inputGrid2.add(srtDestInput, 1, row);
        srtDestInput.setMaxWidth(670);
        GridPane.setColumnSpan(srtDestInput, 5);
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

        inputGrid2.add(new Label("Delay in ms:"), 0, row);
        inputGrid2.add(delayInput, 1, row);
        delayInput.setMaxWidth(comboWith);

        inputGrid2.add(new Label("Pixel format: "), 2, row);
        inputGrid2.add(pixFormatInput, 3, row);
        pixFormatInput.setPrefWidth(comboWith);
        inputGrid2.add(new Label("Output resolution:"), 4, row);
        inputGrid2.add(srtDefInput, 5, row);
        srtDefInput.setPrefWidth(comboWith);
        row++;
        inputGrid2.add(new Label("Audio bitrate:"), 0, row);
        inputGrid2.add(audioBitrateInput, 1, row);
        audioBitrateInput.setPrefWidth(comboWith);
        inputGrid2.add(new Label("Codec:"), 2, row);
        encoderInput.setPrefWidth(comboWith);
        inputGrid2.add(encoderInput, 3, row);
        inputGrid2.add(new Label("Frame per second:"), 4, row);
        inputGrid2.add(framePerSecond, 5, row);
        framePerSecond.setPrefWidth(comboWith);
        row++;
        inputGrid2.add(new Label("Video bitrate:"), 0, row);
        inputGrid2.add(videoBitrate, 1, row);
        videoBitrate.setPrefWidth(comboWith);
        inputGrid2.add(new Label("Video buffer size:"), 2, row);
        videoInputBuffer.setPrefWidth(comboWith);
        inputGrid2.add(videoInputBuffer, 3, row);
        inputGrid2.add(new Label("Audio buffer size:"), 4, row);
        inputGrid2.add(audioInputBuffer, 5, row);
        audioInputBuffer.setPrefWidth(comboWith);
        row++;


        Button saveButton = new Button("Save settings");
        saveButton.getStyleClass().add("event-button");
        saveButton.getStyleClass().add("primary-button");
        saveButton.setOnAction((event -> {saveSettings();}));
        HBox saveHBox = new HBox(saveButton);
        saveHBox.setAlignment(Pos.CENTER);
        saveHBox.setPadding(new Insets(20,0,20,0));
        return new VBox(inputGrid,inputGrid2,saveHBox);
    }

    private void addLanguageRow(GridPane gridPane, int rowIndex, String labelText, ComboBox<String> audioInput, ComboBox<String> audioInputChannel) {
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
        HBox audioInputLabelHBox = new HBox(1,audioInputLabel,audioInputinfoLabel);
        gridPane.add(audioInputLabelHBox, 0, rowIndex);
        gridPane.add(audioInput, 1, rowIndex);

        Label audioChannelinfoLabel2 = new Label("?");
        audioChannelinfoLabel2.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip2 = new Tooltip("If the input device manage only this language, choose 'Join'. Otherwise, choose 'Left' for the first language manage by the device, and 'Right' for the second language.");
        Tooltip.install(audioChannelinfoLabel2, tooltip2);
        tooltip2.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip2.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip2.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip2.getStyleClass().add("tooltip");
        Label audioChannelLabel = new Label("Audio Channel:");
        // Create an HBox to hold both labels
        HBox audioChannelLabelHBox = new HBox(1,audioChannelLabel,audioChannelinfoLabel2);  // 5 is the spacing between the labels
        gridPane.add(audioChannelLabelHBox, 2, rowIndex);
        gridPane.add(audioInputChannel, 3, rowIndex);
    }

    @Override
    public void start(Stage primaryStage) {
        ScrollPane pane = buildUI();
        Scene scene = new Scene(pane, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add("javafx@main.css");
        primaryStage.setScene(scene);
        primaryStage.setTitle("FFmpeg GUI");
        primaryStage.setOnCloseRequest(e -> {
            // Call your method here
            handleClose();
        });
        primaryStage.show();
    }

    private void handleClose() {
        stopEncodingThread();
    }

    private void startEncodingThread() throws Exception {
        streamRecorder.setSrtUrl(srtDestInput.getText());
        streamRecorder.initialiseVideoDevice(videoInput.getValue());
        streamRecorder.initialiseAudioDevices(Arrays.stream(audioInputs).map(ComboBox::getValue).toArray(String[]::new));
        streamRecorder.setPixelFormat(pixFormatInput.getValue());
        streamRecorder.setOutputResolution(srtDefInput.getValue());
        streamRecorder.setDelay(Integer.parseInt(delayInput.getText()));
        streamRecorder.setVideoPid(Integer.parseInt(videoPidField.getText()));
        streamRecorder.setEncoder(encoderInput.getValue());
        streamRecorder.setAudioBitrate(audioBitrateInput.getValue());
        streamRecorder.setAudioBufferSize(audioInputBuffer.getValue());
        streamRecorder.setVideoBitrate(videoBitrate.getValue());
        streamRecorder.setVideoBufferSize(videoInputBuffer.getValue());

        // Add a listener to the StringProperty
        streamRecorder.getOutputLineProperty().addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                appendToConsole(newValue, oldValue,false);
                consoleOutputScrollPane.setVvalue(1.0);
            });
        });
        textAreaInfo.setText(streamRecorder.getFFMpegCommand());
        encodingThread = new Thread(() -> {
            // Your existing code for streamRecorder.run() goes here
            streamRecorder.setSrtUrl(srtDestInput.getText());
            streamRecorder.initialiseVideoDevice(videoInput.getValue());
            streamRecorder.initialiseAudioDevices(Arrays.stream(audioInputs).map(ComboBox::getValue).toArray(String[]::new));
            try {
                streamRecorder.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        encodingThread.start();
//       //  Wait for the encoding thread to be fully constructed
//        while (streamRecorder.getProcess() == null) {
//            Thread.sleep(1000); // Adjust the sleep duration as needed
//        }
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
                    System.out.println("FFmpeg process exited with error code " + exitCode);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void appendToConsole(String newLine, String oldLine, boolean isError) {
        Text text = new Text(newLine + "\n");
        if (isError) {
            text.setFill(Color.RED);
        }
        if(newLine.contains("Error"))  text.setFill(Color.RED);
        if(oldLine!=null && oldLine.startsWith("frame=") && newLine.startsWith("frame="))
        {
            int lastIndex = consoleOutputTextArea.getChildren().size() - 1;
            if (lastIndex >= 0) {
                Node lastNode = consoleOutputTextArea.getChildren().get(lastIndex);
                consoleOutputTextArea.getChildren().remove(lastNode);
            }
        }
        consoleOutputTextArea.getChildren().add(text);
    }

    public static void main(String[] args) {
        launch(args);
    }

    // Custom OutputStream to write to the TextFlow
    public static class ConsoleOutputStream extends OutputStream {
        private final TextFlow console;
        private final StringBuilder buffer = new StringBuilder();
        private static final Map<String, String> ANSI_COLOR_MAP = new HashMap<>();
        static {
            ANSI_COLOR_MAP.put("\u001B[30m", "-fx-fill: black;");
            ANSI_COLOR_MAP.put("\u001B[31m", "-fx-fill: red;");
            ANSI_COLOR_MAP.put("\u001B[32m", "-fx-fill: green;");
            ANSI_COLOR_MAP.put("\u001B[33m", "-fx-fill: yellow;");
            ANSI_COLOR_MAP.put("\u001B[34m", "-fx-fill: blue;");
            ANSI_COLOR_MAP.put("\u001B[35m", "-fx-fill: magenta;");
            ANSI_COLOR_MAP.put("\u001B[36m", "-fx-fill: cyan;");
            ANSI_COLOR_MAP.put("\u001B[37m", "-fx-fill: white;");
            ANSI_COLOR_MAP.put("\u001B[0m", null);  // Reset
        }

        private String currentStyle = null;

        public ConsoleOutputStream(TextFlow console) {
            this.console = console;
        }
        @Override
        public void write(int b) throws IOException {
            char c = (char) b;
            buffer.append(c);

            if (c == '\n') {
                flushBuffer();
            }
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            String text = new String(b, off, len);
            buffer.append(text);
            if (text.contains("\n")) {
                flushBuffer();
            }
        }

        private void flushBuffer() {
            String text = buffer.toString();
            buffer.setLength(0);

            String[] parts = text.split("(\u001B\\[[0-9;]*m)");
            Platform.runLater(() -> {
                for (String part : parts) {
                    String style = ANSI_COLOR_MAP.get(part);
                    if (style != null) {
                        currentStyle = style;
                    } else {
                        Text styledText = new Text(part);
                        if (currentStyle != null) {
                            styledText.setStyle(currentStyle);
                        }
                        console.getChildren().add(styledText);
                    }
                }
            });
        }
    }
}
