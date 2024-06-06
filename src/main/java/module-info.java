module org.kadampa.festivalstreaming {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires webcam.capture;
    requires ffmpeg;
    requires com.google.gson;
    requires org.fusesource.jansi;

    opens org.kadampa.festivalstreaming to com.google.gson;
    exports org.kadampa.festivalstreaming;
}