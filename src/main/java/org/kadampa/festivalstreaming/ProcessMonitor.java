package org.kadampa.festivalstreaming;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes whether the encoder process is still running into {@code isAliveProperty}.
 * <p>
 * The GUI's whole live/idle display hangs off that property, so the one thing this class
 * must guarantee is that the property always ends on false: a death the GUI never hears
 * about leaves it showing a live stream forever. Values are published on the FX thread,
 * both because that is where the listeners want to run and because a listener throwing
 * inside the polling task would silently cancel all further polls.
 */
public class ProcessMonitor {

    private final Process process;
    private final BooleanProperty isAliveProperty;
    private final ScheduledExecutorService scheduler;

    public ProcessMonitor(Process process, BooleanProperty isAliveProperty) {
        this.process = process;
        this.isAliveProperty = isAliveProperty;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ProcessMonitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        boolean alive = process.isAlive();
        Platform.runLater(() -> isAliveProperty.setValue(alive));
        if (!alive) {
            scheduler.shutdown();
        }
    }

    /**
     * Stops polling. The property is forced to false first: every caller of this method is
     * tearing the stream down, and some reach it before a poll has seen the process die.
     */
    public void stopMonitoring() {
        Platform.runLater(() -> isAliveProperty.setValue(false));
        scheduler.shutdown();
    }
}
