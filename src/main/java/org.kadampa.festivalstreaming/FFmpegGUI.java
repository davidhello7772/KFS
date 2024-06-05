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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class FFmpegGUI extends Application {

    private final ComboBox<String>[] audioInputs;
    private final TextField[] pidFields;
    private final ComboBox<String> videoInput;
    private final TextField delayInput;
    private final ComboBox<String> pixFormatInput;
    private final TextField streamIdInput;
    private final ComboBox<String> srtDefInput;
    private final ComboBox<String> fileDefInput;
    private final ComboBox<String> encoderInput;
    private final TextField srtDestInput;
    private final TextField outputFileInput;
    private final TextFlow consoleOutputTextArea;
    private final Button startButton;
    private final Button stopButton;
    private final Button clearOutputButton;
    private Thread encodingThread;
    private final StreamRecorderRunnable streamRecorder = new StreamRecorderRunnable();
    private Settings settings;
    private ScrollPane consoleOutputScrollPane;
    private static int MAX_NUMBER_OF_LANGUAGES=12;

    public FFmpegGUI() {
        audioInputs = new ComboBox[MAX_NUMBER_OF_LANGUAGES];
        pidFields = new TextField[MAX_NUMBER_OF_LANGUAGES];
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i] = new ComboBox<>();
            pidFields[i] = new TextField();
        }
        videoInput = new ComboBox<>();
        delayInput = new TextField();
        pixFormatInput = new ComboBox<>();
        streamIdInput = new TextField();
        srtDefInput = new ComboBox<>();
        fileDefInput = new ComboBox<>();
        encoderInput = new ComboBox<>();
        srtDestInput = new TextField();
        outputFileInput = new TextField();
        consoleOutputTextArea = new TextFlow();
        startButton = new Button("Start");
        stopButton = new Button("Stop");
        clearOutputButton = new Button("Clear Output");
        // Load settings
        settings = SettingsUtil.loadSettings();
        applySettings();

        // Add action listeners
        startButton.setOnAction(event -> {
            try {
                startEncodingThread(event);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        stopButton.setOnAction(this::stopEncodingThread);
        clearOutputButton.setOnAction((ActionEvent e) ->consoleOutputTextArea.getChildren().clear());

        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (int i = 0; i < audioInputs.length; i++) {
            audioInputs[i].getItems().add("Not Used");
            for (Mixer.Info mixerInfo : mixerInfos) {
                if (!mixerInfo.getDescription().equals("Port Mixer")) {
                    if (!mixerInfo.getName().contains("OUT"))
                        audioInputs[i].getItems().add(mixerInfo.getName());
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
            pidFields[i].setText(settings.getPids().getOrDefault("pid" + i, ""));
        }
        videoInput.setValue(settings.getVideoInput());
        delayInput.setText(settings.getDelay());
        pixFormatInput.setValue(settings.getPixFormat());
        streamIdInput.setText(settings.getStreamId());
        srtDefInput.setValue(settings.getSrtDef());
        fileDefInput.setValue(settings.getFileDef());
        encoderInput.setValue(settings.getEncoder());
        srtDestInput.setText(settings.getSrtDest());
        outputFileInput.setText(settings.getOutputFile());
    }

    private void saveSettings() {
        for (int i = 0; i < audioInputs.length; i++) {
            settings.getAudioInputs().put("audio" + i, audioInputs[i].getValue());
            settings.getPids().put("pid" + i, pidFields[i].getText());
        }
        settings.setVideoInput(videoInput.getValue());
        settings.setDelay(delayInput.getText());
        settings.setPixFormat(pixFormatInput.getValue());
        settings.setStreamId(streamIdInput.getText());
        settings.setSrtDef(srtDefInput.getValue());
        settings.setFileDef(fileDefInput.getValue());
        settings.setEncoder(encoderInput.getValue());
        settings.setSrtDest(srtDestInput.getText());
        settings.setOutputFile(outputFileInput.getText());

        SettingsUtil.saveSettings(settings);
    }

    private ScrollPane buildUI() {
        BorderPane root = new BorderPane();

        ImageView logoView = new ImageView(new Image("https://kadampafestivals.org/wp-content/uploads/2024/01/New-NKT-IKBU-Logo-Kadampa-Blue.png"));
        logoView.setFitHeight(50);
        logoView.setFitWidth(50);

        // Create a label for the title
        Label titleLabel = new Label("International Kadampa Festival Streaming");
        titleLabel.setStyle("-fx-font-size: 18px;"); // Set the font size of the label

        // Create an HBox to hold the logo and the title label
        HBox titleBox = new HBox(10, logoView, titleLabel);
        titleBox.setAlignment(Pos.CENTER); // Center align the contents of the HBox

        TabPane tabPane = new TabPane();
        tabPane.setPadding(new Insets(50, 0, 0, 0));

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

        root.setTop(titleBox);
        root.setCenter(tabPane);
        return new ScrollPane(root);
    }

    private Node buildTabControlConsole() {
        consoleOutputScrollPane = new ScrollPane(consoleOutputTextArea);
        consoleOutputTextArea.setMouseTransparent(false);
        consoleOutputTextArea.setMinHeight(500);
        consoleOutputScrollPane.setMinHeight(500);
        consoleOutputScrollPane.setMaxHeight(500);
        consoleOutputScrollPane.setMouseTransparent(false);
        consoleOutputScrollPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        consoleOutputScrollPane.setFitToWidth(true); // Adjusts the width of the ScrollPane to fit its content
        VBox consoleBox = new VBox(10, new Label("Console Output"), consoleOutputScrollPane);
        startButton.styleProperty().bind(streamRecorder.isAliveProperty().map(r -> r ? "-fx-background-color: red;" : ""));
        streamRecorder.isAliveProperty().addListener(b->startButton.setDisable(streamRecorder.isAliveProperty().getValue()));
        HBox buttonBox = new HBox(10, startButton, stopButton,clearOutputButton);
        buttonBox.setPadding(new Insets(20, 0, 20, 0));
        return new VBox(10, buttonBox, consoleBox);
    }

    private Node buildTabSettings() {
        GridPane inputGrid = new GridPane();
        inputGrid.setHgap(10);
        inputGrid.setVgap(10);
        inputGrid.setPadding(new Insets(10));

        int row = 12;
        addLanguageRow(inputGrid, 0, "English:", audioInputs[0], pidFields[0]);
        addLanguageRow(inputGrid, 1, "Spanish:", audioInputs[1], pidFields[1]);
        addLanguageRow(inputGrid, 2, "French:", audioInputs[2], pidFields[2]);
        addLanguageRow(inputGrid, 3, "Portuguese:", audioInputs[3], pidFields[3]);
        addLanguageRow(inputGrid, 4, "German:", audioInputs[4], pidFields[4]);
        addLanguageRow(inputGrid, 5, "Cantonese:", audioInputs[5], pidFields[5]);
        addLanguageRow(inputGrid, 6, "Mandarin:", audioInputs[6], pidFields[6]);
        addLanguageRow(inputGrid, 7, "Vietnamese:", audioInputs[7], pidFields[7]);
        addLanguageRow(inputGrid, 8, "Italian:", audioInputs[8], pidFields[8]);
        addLanguageRow(inputGrid, 9, "Finnish:", audioInputs[9], pidFields[9]);
        addLanguageRow(inputGrid, 10, "Greek:", audioInputs[10], pidFields[10]);
        addLanguageRow(inputGrid, 11, "Extra Language 1:", audioInputs[11], pidFields[11]);

        inputGrid.add(new Label("Video Input:"), 0, row);
        inputGrid.add(videoInput, 1, row);
        row++;

        inputGrid.add(new Label("Delay in ms:"), 0, row);
        inputGrid.add(delayInput, 1, row);
        row++;

        inputGrid.add(new Label("Pixel format:"), 0, row);
        inputGrid.add(pixFormatInput, 1, row);
        row++;

        inputGrid.add(new Label("Stream Id:"), 0, row);
        inputGrid.add(streamIdInput, 1, row);
        row++;

        inputGrid.add(new Label("Srt def.:"), 0, row);
        inputGrid.add(srtDefInput, 1, row);
        row++;

        inputGrid.add(new Label("Srt url.:"), 0, row);
        inputGrid.add(srtDestInput, 1, row);
        row++;
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
        Scene scene = new Scene(new ScrollPane(pane), 800, 600);
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
        stopEncodingThread(null);
    }

    private void startEncodingThread(ActionEvent event) throws Exception {
        streamRecorder.setSrtUrl(srtDestInput.getText());
        streamRecorder.initialiseVideoDevice(videoInput.getValue());
        streamRecorder.initialiseAudioDevices(Arrays.stream(audioInputs).map(ComboBox::getValue).toArray(String[]::new));

        // Add a listener to the StringProperty
        streamRecorder.getOutputLineProperty().addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                appendToConsole(newValue, oldValue,false);
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
                // Handle exceptions
                e.printStackTrace();
            }
        });
        encodingThread.start();

        // Wait for the encoding thread to be fully constructed
        while (streamRecorder.getProcess() == null) {
            Thread.sleep(1000); // Adjust the sleep duration as needed
        }
    }

    private void stopEncodingThread(ActionEvent event) {
        if (encodingThread != null && encodingThread.isAlive()) {
            streamRecorder.stop();
            int exitCode = 0;
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
                    appendToConsole("FFmpeg process exited with error code " + exitCode,"",true);
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
}
