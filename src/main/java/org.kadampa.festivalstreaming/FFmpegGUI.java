package org.kadampa.festivalstreaming;

import com.github.sarxos.webcam.Webcam;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FFmpegGUI extends Application {
    private final ComboBox<String>[] audioInputs;
    private final ComboBox<String>[] audioInputsChannel;
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
    private final TextFlow consoleOutputTextFlow;
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

    private TextField playerURLTextField = new TextField();

    public FFmpegGUI() {
        int MAX_NUMBER_OF_LANGUAGES = 12;
        audioInputs = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        audioInputsChannel = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i] = new ComboBox<>();
            audioInputsChannel[i] = new ComboBox<>();
        }
        videoInput = new ComboBox<>();
        videoPidField = new TextField();
        videoPidField.setText("37");
        videoPidField.setMaxWidth(75);
        delayInput = new TextField();
        delayInput.setText("0");
        audioBitrateInput = new ComboBox<String>();
        audioBitrateInput.getItems().add("128k");
        audioBitrateInput.getItems().add("256k");
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
        srtDestInput = new TextField();
        outputFileInput = new TextField();
        consoleOutputTextFlow = new TextFlow();
        //consoleOutputTextArea = new TextArea();
        startButton = new Button("Start");
        startButton.getStyleClass().add("event-button");
        startButton.getStyleClass().add("success-button");
        blinkingTimeLine = new Timeline(
                new KeyFrame(Duration.seconds(0.5), e -> startButton.setStyle("-fx-background-color: -green-color;")), // Original color (green)
                new KeyFrame(Duration.seconds(1), e -> startButton.setStyle("-fx-background-color: -red-color;")) // Blinking color (red)
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

        stopButton.setOnAction(event -> {
            stopButton.setGraphic(progressIndicator);
            // Create a Task to run the long-running method in the background
            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
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
        // Load settings
        settings = SettingsUtil.loadSettings();
        applySettings();

    }
    private void applySettings() {
        Map<String, String> audioSettings = settings.getAudioInputs();
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i].setValue(audioSettings.getOrDefault("audio" + i, "Not Used"));
        }
        for (int i = 0; i < audioInputsChannel.length; i++) {
            audioInputsChannel[i].setValue(settings.getAudioInputsChannel().getOrDefault("audioChannel" + i, ""));
        }
        for (int i = 0; i < audioInputs.length; i++) {
            if(Objects.equals(audioInputs[i].getValue(), "")) {
                audioInputs[i].setValue("Not Used");
                audioInputsChannel[i].setValue("Join");
            }
            if(Objects.equals(audioInputs[i].getValue(), "Not Used")) {
                audioInputsChannel[i].setValue("Join");
                audioInputsChannel[i].setDisable(true);
            }
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

        // Control and Console Tab
        Tab controlConsoleTab = new Tab("Control and Console");
        controlConsoleTab.setClosable(false);
        controlConsoleTab.setContent(buildTabControlConsole());
        tabPane.getTabs().add(controlConsoleTab);

        // Language Settings Tab
        Tab settingTab = new Tab("Settings");
        settingTab.setClosable(false);
        settingTab.setContent(buildTabSettings());
        tabPane.getTabs().add(settingTab);


        Tab infoTab = new Tab("Informations");
        infoTab.setClosable(false);
        infoTab.setContent(buildTabInfo());
        tabPane.getTabs().add(infoTab);

        root.getChildren().addAll(titleBox,tabPane);
        return new ScrollPane(root);
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
        int pidVideo = Integer.parseInt(videoPidField.getText());
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
        consoleOutputScrollPane.setMinSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-250);
        consoleOutputScrollPane.setMaxSize(WINDOW_WIDTH-20, WINDOW_HEIGHT-250);
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
                    startButton.setText("Currently Playing");
                    blinkingTimeLine.play(); // Start the animation
                });
            }
            else {
                Platform.runLater(()->{
                    //We wait for 3 seconds to give the time to the thread to end properly
                    //(in the streamRecorder, we also wait 3s for the process to end before destroying it forcefully
                    PauseTransition delay = new PauseTransition(Duration.seconds(3)); // Pause for 3 seconds
                    delay.setOnFinished(event -> {
                        stopButton.setGraphic(stopPath);
                        stopButton.setDisable(true);
                        startButton.setText("Start");
                        blinkingTimeLine.stop();
                        startButton.setStyle("");
                        startButton.setDisable(streamRecorder.isAliveProperty().getValue());
                    });
                    delay.play();
                });
            }
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
        //If it's empty, we select the first element
        if(videoInput.getValue()==null || videoInput.getValue().isEmpty()) videoInput.setValue(videoInput.getItems().get(0));
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
        Label outputinfoLabel = new Label("?");
        outputinfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltipOutput = new Tooltip("Choose the output, either a srt stream copied and paste from the streaming platform, or a file. The SRT protocol support multiaudio track, but not the RTMP protool");
        Tooltip.install(outputinfoLabel, tooltipOutput);
        tooltipOutput.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltipOutput.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltipOutput.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltipOutput.getStyleClass().add("tooltip");
        Label outputLabel = new Label("Output url:");
        // Create an HBox to hold both labels
        HBox outputHBox = new HBox(1,outputLabel,outputinfoLabel);
        inputGrid2.add(outputHBox, 0, row);
        inputGrid2.add(srtDestInput, 1, row);
        srtDestInput.setMaxWidth(670);
        if(srtDestInput.getText()==null) srtDestInput.setText("");
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
        if(delayInput.getText()==null || delayInput.getText().isEmpty()) delayInput.setText("0");
        inputGrid2.add(delayInput, 1, row);
        delayInput.setMaxWidth(comboWith);

        Label pixelFormatInfoLabel = new Label("?");
        pixelFormatInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip3 = new Tooltip("While encoding a video, selecting the correct pixel format is essential when streaming to a platform because it impacts the video stream's compatibility, quality, and performance.\n The widely supported format is yuv420p. Using an incorrect pixel format can prevent the streaming platform from playing the video properly.");
        Tooltip.install(pixelFormatInfoLabel, tooltip3);
        tooltip3.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip3.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip3.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip3.getStyleClass().add("tooltip");
        Label pixelFormatlLabel = new Label("Pixel format:");
        // Create an HBox to hold both labels
        HBox audioChannelLabelHBox = new HBox(1,pixelFormatlLabel,pixelFormatInfoLabel);
        inputGrid2.add(audioChannelLabelHBox, 2, row);

        //If it's empty, we select the first element
        if(pixFormatInput.getValue()==null || pixFormatInput.getValue().isEmpty()) pixFormatInput.setValue(pixFormatInput.getItems().get(0));
        inputGrid2.add(pixFormatInput, 3, row);
        pixFormatInput.setPrefWidth(comboWith);

        Label outputResInfoLabel = new Label("?");
        outputResInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip4= new Tooltip("The parameters hd480, hd720, and hd1080 are shorthand for setting standard high-definition (HD) resolutions during video encoding:\n"+
                "\nhd480: Configures the video to 852x480 pixels, offering a resolution that is higher than standard definition but lower than HD, suitable for lower bandwidth or smaller screens.\n" +
                "\nhd720: Sets the video resolution to 1280x720 pixels, providing HD quality, ideal for streaming and viewing on HD displays without consuming too much bandwidth.\n" +
                "\nhd1080: Adjusts the video to 1920x1080 pixels, delivering Full HD resolution, perfect for high-quality streaming and playback on larger screens with a higher level of detail.\n"+
                "\nIMPORTANT: Streaming in hd1080 uses twice the data compared to hd720. The cost of the streaming platform is directly related to the total bandwidth consumed by viewers.\n" +
                "If you plan to stream in hd1080, please confirm with the treasurer to ensure it fits within the budget.");
        Tooltip.install(outputResInfoLabel, tooltip4);
        tooltip4.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip4.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip4.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip4.getStyleClass().add("tooltip");
        Label outputReslLabel = new Label("Output resolution:");
        // Create an HBox to hold both labels
        HBox outputResLabelHBox = new HBox(1,outputReslLabel,outputResInfoLabel);
        inputGrid2.add(outputResLabelHBox, 4, row);
        if(srtDefInput.getValue()==null ||srtDefInput.getValue().isEmpty()) srtDefInput.setValue(srtDefInput.getItems().get(0));
        inputGrid2.add(srtDefInput, 5, row);
        srtDefInput.setPrefWidth(comboWith);
        row++;

        Label audioBitrateInfoLabel = new Label("?");
        audioBitrateInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip5= new Tooltip("128k Audio Bitrate: This setting configures the audio bitrate to 128 kilobits per second (kbps) during encoding. \nIt offers a reasonable balance between audio quality and file size, suitable for most standard audio playback scenarios, such as online streaming or casual listening.\n" +
                "\n256k Audio Bitrate: This option sets the audio bitrate to 256 kbps, providing higher audio quality compared to 128k.\n It's ideal for scenarios where audio fidelity is crucial, such as professional music streaming, podcasts, or audio recordings, but it results in larger file sizes compared to lower bitrate options.\n"+
                "\nThese settings will apply to all audio tracks. If there are multiple tracks, it will significantly increase the data usage and bandwidth consumed by viewers on the streaming platform, which forms the basis for invoicing");
        Tooltip.install(audioBitrateInfoLabel, tooltip5);
        tooltip5.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip5.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip5.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip5.getStyleClass().add("tooltip");
        Label audioBitrateLabel = new Label("Audio bitrate:");
        // Create an HBox to hold both labels
        HBox audioBitrateLabelHBox = new HBox(1,audioBitrateLabel,audioBitrateInfoLabel);
        inputGrid2.add(audioBitrateLabelHBox, 0, row);
        //If it's empty, we select the first element
        if(audioBitrateInput.getValue()==null || audioBitrateInput.getValue().isEmpty()) audioBitrateInput.setValue(audioBitrateInput.getItems().get(0));
        inputGrid2.add(audioBitrateInput, 1, row);
        audioBitrateInput.setPrefWidth(comboWith);


        Label codecInfoLabel = new Label("?");
        codecInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip6= new Tooltip("libx264 is a popular software-based H.264 encoder known for high-quality encoding. It supports the yuv420p pixel format and relies more on CPU resources rather than GPU.\n"+
                "\nOn the other hand, h264_nvenc leverages NVIDIA's hardware acceleration through the NVENC API for accelerated H.264 encoding. It's ideal for users with NVIDIA GPUs, ensuring efficient encoding while also supporting the yuv420p pixel format.");
        Tooltip.install(codecInfoLabel, tooltip6);
        tooltip6.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip6.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip6.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip6.getStyleClass().add("tooltip");
        Label codecLabel = new Label("Codec:");
        // Create an HBox to hold both labels
        HBox codecLabelHBox = new HBox(1,codecLabel,codecInfoLabel);
        if(encoderInput.getValue()==null ||encoderInput.getValue().isEmpty()) encoderInput.setValue(encoderInput.getItems().get(0));
        inputGrid2.add(codecLabelHBox, 2, row);
        encoderInput.setPrefWidth(comboWith);
        inputGrid2.add(encoderInput, 3, row);

        Label fpsInfoLabel = new Label("?");
        fpsInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip7= new Tooltip("The \"frames per second\" (fps) parameter in FFmpeg specifies the number of individual frames displayed or processed per second in a video.\nIt determines the smoothness and speed of motion in the video. A higher fps value results in smoother motion but may require more processing power and bandwidth, \npotentially impacting streaming performance by increasing the computational load and data transmission requirements.\nTherefore, while higher fps can enhance visual quality, it may also necessitate more robust hardware and network resources to maintain smooth streaming.");
        Tooltip.install(fpsInfoLabel, tooltip7);
        tooltip7.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip7.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip7.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip7.getStyleClass().add("tooltip");
        Label fpsLabel = new Label("Frame per second:");
        // Create an HBox to hold both labels
        HBox fpsLabelHBox = new HBox(1,fpsLabel,fpsInfoLabel);
        inputGrid2.add(fpsLabelHBox, 4, row);
        inputGrid2.add(framePerSecond, 5, row);
        //If it's empty, we select the first element
        if(framePerSecond.getValue()==null || framePerSecond.getValue().isEmpty()) framePerSecond.setValue(framePerSecond.getItems().get(0));
        framePerSecond.setPrefWidth(comboWith);
        row++;

        Label videoBitrateInfoLabel = new Label("?");
        videoBitrateInfoLabel.getStyleClass().add("info-for-tooltip");
        Tooltip tooltip8= new Tooltip("The video bitrate parameter determines the amount of data allocated to encode each second of video footage. It directly affects the quality and file size of the resulting video file.\n Higher bitrate values generally result in better visual quality but also produce larger file sizes.\n" +
                "\nRecommended Bitrate Values:\n" +
                "1/ hd480p (852x480 pixels): Recommended Bitrate: 1000-2500 kbps\n" +
                "This range provides a balance between video quality and file size suitable for streaming or playback on smaller screens.\n" +
                "\n" +
                "2/ hd720p (1280x720 pixels): Recommended Bitrate: 2500-5000 kbps\n" +
                "Higher resolution necessitates a higher bitrate to maintain quality. This range is suitable for HD streaming and playback on various devices.\n" +
                "\n" +
                "3/ hd1080p (1920x1080 pixels): Recommended Bitrate: 5000-8000 kbps\n" +
                "Full HD resolution demands a higher bitrate for optimal quality. This range is suitable for high-quality streaming and playback on larger screens.");
        Tooltip.install(videoBitrateInfoLabel, tooltip8);
        tooltip8.setShowDelay(Duration.seconds(TOOLTIP_DELAY)); // Delay before showing (1 second)
        tooltip8.setShowDuration(Duration.seconds(TOOLTIP_DURATION)); // How long to show (10 seconds)
        tooltip8.setHideDelay(Duration.seconds(TOOLTIP_DELAY));
        tooltip8.getStyleClass().add("tooltip");
        Label videoBitrateLabel = new Label("Video bitrate:");
        // Create an HBox to hold both labels
        HBox videoBitrateLabelHBox = new HBox(1,videoBitrateLabel,videoBitrateInfoLabel);
        inputGrid2.add(videoBitrateLabelHBox, 0, row);
        inputGrid2.add(videoBitrate, 1, row);
        //If it's empty, we select the first element
        if(videoBitrate.getValue()==null || videoBitrate.getValue().isEmpty()) videoBitrate.setValue(videoBitrate.getItems().get(0));
        videoBitrate.setPrefWidth(comboWith);

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
        videoInputBuffer.setPrefWidth(comboWith);
        if(videoInputBuffer.getValue()==null || videoInputBuffer.getValue().isEmpty()) videoInputBuffer.setValue(videoInputBuffer.getItems().get(0));
        inputGrid2.add(videoBufferLabelHBox, 2, row);
        inputGrid2.add(videoInputBuffer, 3, row);

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
        inputGrid2.add(audioInputBuffer, 5, row);
        //If it's empty, we select the first element
        if(audioInputBuffer.getValue()==null || audioInputBuffer.getValue().isEmpty()) audioInputBuffer.setValue(audioInputBuffer.getItems().get(0));
        audioInputBuffer.setPrefWidth(comboWith);
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
        Tooltip tooltip2 = new Tooltip("If the input device manage only this language, choose 'Join'. Otherwise, choose 'Left' for the first language manage by the device, and 'Right' for the second language.\nWARNING: make sure the audio input support stereo if you choose left or right");
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

        audioInput.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.equals("Not Used") && !newValue.isEmpty()) {
                audioInputChannel.setDisable(false);
                audioInputChannel.setValue("Join");
            } else {
                audioInputChannel.setDisable(true);
            }
        });
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
        if(!checkParameters()) {
            return;
        }
        displayPIDInfo();
        doWeAutomaticallyScrollAtBottom = true;
        streamRecorder.setSrtUrl(srtDestInput.getText());
        streamRecorder.initialiseVideoDevice(videoInput.getValue());
        streamRecorder.initialiseAudioDevices(Arrays.stream(audioInputs).map(ComboBox::getValue).toArray(String[]::new));
        streamRecorder.initialiseAudioDevicesChannel(Arrays.stream(audioInputsChannel).map(ComboBox::getValue).toArray(String[]::new));
        streamRecorder.setPixelFormat(pixFormatInput.getValue());
        streamRecorder.setOutputResolution(srtDefInput.getValue());
        streamRecorder.setDelay(Integer.parseInt(delayInput.getText()));
        streamRecorder.setVideoPid(Integer.parseInt(videoPidField.getText()));
        streamRecorder.setEncoder(encoderInput.getValue());
        streamRecorder.setAudioBitrate(audioBitrateInput.getValue());
        streamRecorder.setAudioBufferSize(audioInputBuffer.getValue());
        streamRecorder.setVideoBitrate(videoBitrate.getValue());
        streamRecorder.setVideoBufferSize(videoInputBuffer.getValue());
        streamRecorder.setFps(Integer.parseInt(framePerSecond.getValue()));
        textAreaInfo.setText(streamRecorder.getFFMpegCommand());
        playerURLTextField.setText(buildPlayerURL());
        stopButton.setDisable(false);
        streamRecorder.getOutputLineProperty().addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                appendToConsole(newValue, oldValue,null);
            });
        });
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
    }

    private String buildPlayerURL() {
        String outputUrl = srtDestInput.getText();
        String rValue = "";
        int startIndex = outputUrl.indexOf("r=") + 2; // Exclude "r="
        int commaIndex = outputUrl.indexOf(',', startIndex);
        if (commaIndex != -1) {
            rValue = outputUrl.substring(startIndex, commaIndex);
        }
        String baseURL = "https://player.castr.com/"+rValue;
        String parameters = "?tracks=";
        boolean firstParameter = true;
        if(!audioInputs[0].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="English";
            firstParameter = false;
        }
        if(!audioInputs[1].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Español";
            firstParameter = false;
        }
        if(!audioInputs[2].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Français";
            firstParameter = false;
        }
        if(!audioInputs[3].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Português";
            firstParameter = false;
        }
        if(!audioInputs[4].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Deutsch";
            firstParameter = false;
        }
        if(!audioInputs[5].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="廣東話";
            firstParameter = false;
        }
        if(!audioInputs[6].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="普通话";
            firstParameter = false;
        }
        if(!audioInputs[7].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Tiếng%20Việt";
            firstParameter = false;
        }
        if(!audioInputs[8].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Italiano";
            firstParameter = false;
        }
        if(!audioInputs[9].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Suomi";
            firstParameter = false;
        }
        if(!audioInputs[10].getValue().equals("Not Used")) {
            if(!firstParameter)  parameters +=",";
            parameters +="Ελληνικά";
            firstParameter = false;
        }
        return baseURL+parameters;
    }

    private boolean checkParameters() {
        boolean result = true;
        if(videoPidField.getText().isEmpty()) {
            appendToConsole("Please Fill the Video PID Field (see Tooltip for help)","",Color.RED);
            result = false;
        }
        try {
            int i = Integer.parseInt(videoPidField.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Video PID must be an integer number (See Tooltip for help).","",Color.RED);
            result= false;
        }
        if(srtDestInput.getText().isEmpty()) {
            appendToConsole("Please Fill the Output URL (See Tooltip for help).","",Color.RED);
            result= false;
        }
        try {
            int i = Integer.parseInt(delayInput.getText());
        }
        catch (NumberFormatException e){
            appendToConsole("The Delay must be an integer number (See Tooltip for help).","",Color.RED);
            result= false;
        }
        if(delayInput.getText().isEmpty()) {
            appendToConsole("Please Fill the Delay (See Tooltip for help)","",Color.RED);
            result= false;
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
                e.printStackTrace();
            }
        }
    }

    private void appendToConsole(String newLine, String oldLine, Color color) {
        Text text = new Text(newLine + "\n");
        if (color!=null) {
            text.setFill(color);
        }
        String[] infoTerms = {"Info:", "Command:"};
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

        if (info.find()) text.setFill(Color.BLUE);
        if (warningMatcher.find()) text.setFill(Color.ORANGE);
        if (errorMatcher.find()) text.setFill(Color.RED);

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
