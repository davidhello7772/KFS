module org.kadampa.festivalstreaming {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires webcam.capture;
    requires com.google.gson;
    requires javafx.media;

    opens org.kadampa.festivalstreaming to com.google.gson;
    exports org.kadampa.festivalstreaming;
}