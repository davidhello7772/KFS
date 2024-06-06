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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FFmpegGUI extends Application {
    private final ComboBox<String>[] audioInputs;
    private final TextField[] audioPidFields;
    private final TextField videoPidField;
    private final ComboBox<String> videoInput;
    private final TextField delayInput;
    private final ComboBox<String> pixFormatInput;
    private final ComboBox<String> srtDefInput;
    private final ComboBox<String> fileDefInput;
    private final ComboBox<String> encoderInput;
    private final TextField srtDestInput;
    private final TextField outputFileInput;
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

    private static final int WINDOW_WIDTH = 800;
    private static final int WINDOW_HEIGHT = 800;

    public FFmpegGUI() {
        int MAX_NUMBER_OF_LANGUAGES = 12;
        audioInputs = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        audioPidFields = new TextField[MAX_NUMBER_OF_LANGUAGES];
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i] = new ComboBox<>();
            audioPidFields[i] = new TextField();
        }
        videoInput = new ComboBox<>();
        videoPidField = new TextField();
        delayInput = new TextField();
        pixFormatInput = new ComboBox<String>();
        pixFormatInput.getItems().add("yuv420p");

        srtDefInput = new ComboBox<String>();
        srtDefInput.getItems().add("hd480");
        srtDefInput.getItems().add("hd720");
        srtDefInput.getItems().add("hd1080");

        fileDefInput = new ComboBox<>();
        encoderInput = new ComboBox<>();
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

        List<Webcam> webcams = Webcam.getWebcams();
        for (Webcam webcam : webcams) {
            this.videoInput.getItems().add(webcam.getDevice().getName().substring(0, webcam.getDevice().getName().length() - 2));
        }
    }

    private void applySettings() {
        Map<String, String> audioSettings = settings.getAudioInputs();
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i].setValue(audioSettings.getOrDefault("audio" + i, "Not Used"));
            audioPidFields[i].setText(settings.getPids().getOrDefault("pid" + i, ""));
        }
        videoInput.setValue(settings.getVideoInput());
        delayInput.setText(settings.getDelay());
        pixFormatInput.setValue(settings.getPixFormat());
        srtDefInput.setValue(settings.getSrtDef());
        fileDefInput.setValue(settings.getFileDef());
        encoderInput.setValue(settings.getEncoder());
        srtDestInput.setText(settings.getSrtDest());
        outputFileInput.setText(settings.getOutputFile());
    }

    private void saveSettings() {
        for (int i = 0; i < audioInputs.length; i++) {
            settings.getAudioInputs().put("audio" + i, audioInputs[i].getValue());
            settings.getPids().put("pid" + i, audioPidFields[i].getText());
        }
        settings.setVideoInput(videoInput.getValue());
        settings.setDelay(delayInput.getText());
        settings.setPixFormat(pixFormatInput.getValue());
        settings.setSrtDef(srtDefInput.getValue());
        settings.setFileDef(fileDefInput.getValue());
        settings.setEncoder(encoderInput.getValue());
        settings.setSrtDest(srtDestInput.getText());
        settings.setOutputFile(outputFileInput.getText());

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

        root.getChildren().addAll(titleBox,tabPane);
        return new ScrollPane(root);
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
        return new VBox(10, buttonBox, consoleBox);
    }

    private Node buildTabSettings() {
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.setPadding(new Insets(10));

        int row = 12;
        addLanguageRow(inputGrid, 0, "English:", audioInputs[0], audioPidFields[0]);
        addLanguageRow(inputGrid, 1, "Spanish:", audioInputs[1], audioPidFields[1]);
        addLanguageRow(inputGrid, 2, "French:", audioInputs[2], audioPidFields[2]);
        addLanguageRow(inputGrid, 3, "Portuguese:", audioInputs[3], audioPidFields[3]);
        addLanguageRow(inputGrid, 4, "German:", audioInputs[4], audioPidFields[4]);
        addLanguageRow(inputGrid, 5, "Cantonese:", audioInputs[5], audioPidFields[5]);
        addLanguageRow(inputGrid, 6, "Mandarin:", audioInputs[6], audioPidFields[6]);
        addLanguageRow(inputGrid, 7, "Vietnamese:", audioInputs[7], audioPidFields[7]);
        addLanguageRow(inputGrid, 8, "Italian:", audioInputs[8], audioPidFields[8]);
        addLanguageRow(inputGrid, 9, "Finnish:", audioInputs[9], audioPidFields[9]);
        addLanguageRow(inputGrid, 10, "Greek:", audioInputs[10], audioPidFields[10]);
        addLanguageRow(inputGrid, 11, "Extra Language 1:", audioInputs[11], audioPidFields[11]);

        inputGrid.add(new Label("Video Input:"), 0, row);
        inputGrid.add(videoInput, 1, row);
        inputGrid.add(new Text("PID"), 2, row);
        inputGrid.add(videoPidField, 3, row);
        row++;

        inputGrid.add(new Label("Pixel format:"), 0, row);
        inputGrid.add(pixFormatInput, 1, row);
        inputGrid.add(new Label("Output resolution:"), 2, row);
        inputGrid.add(srtDefInput, 3, row);
        row++;

        inputGrid.add(new Label("Delay in ms:"), 0, row);
        inputGrid.add(delayInput, 1, row);
        row++;

        inputGrid.add(new Label("Output url:"), 0, row);
        inputGrid.add(srtDestInput, 1, row);
        GridPane.setColumnSpan(srtDestInput, 4);
        return inputGrid;
    }

    private void addLanguageRow(GridPane gridPane, int rowIndex, String labelText, ComboBox<String> audioInput, TextField pidField) {
        gridPane.add(new Label(labelText), 0, rowIndex);
        gridPane.add(audioInput, 1, rowIndex);
        gridPane.add(new Label("PID:"), 2, rowIndex);
        gridPane.add(pidField, 3, rowIndex);
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
            saveSettings();
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

        ConsoleOutputStream consoleStream = new ConsoleOutputStream(consoleOutputTextArea);
        System.setOut(new PrintStream(consoleStream, true));
        System.setErr(new PrintStream(consoleStream, true));

        // Add a listener to the StringProperty
        streamRecorder.getOutputLineProperty().addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                System.out.println(newValue);
                        //appendToConsole(newValue, oldValue,false);
                consoleOutputScrollPane.setVvalue(1.0);
            });
            // Perform your action here
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

//    private void appendToConsole(String newLine, String oldLine, boolean isError) {
//        Text text = new Text(newLine + "\n");
//        if (isError) {
//            text.setFill(Color.RED);
//        }
//        if(oldLine!=null && oldLine.startsWith("frame=") && newLine.startsWith("frame="))
//        {
//            int lastIndex = consoleOutputTextArea.getChildren().size() - 1;
//            if (lastIndex >= 0) {
//                Node lastNode = consoleOutputTextArea.getChildren().get(lastIndex);
//                consoleOutputTextArea.getChildren().remove(lastNode);
//            }
//        }
//        consoleOutputTextArea.getChildren().add(text);
//    }

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
