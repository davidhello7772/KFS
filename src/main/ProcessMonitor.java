package org.kadampa.festivalstreaming

import javafx.beans.property.BooleanProperty;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProcessMonitor {

    private final Process process;
    private final BooleanProperty isAliveProperty;
    private final ScheduledExecutorService scheduler;

    public ProcessMonitor(Process process, BooleanProperty isAliveProperty) {
        this.process = process;
        this.isAliveProperty = isAliveProperty;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        startMonitoring();
    }

    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            isAliveProperty.setValue(process.isAlive());
        }, 0, 100, TimeUnit.MILLISECONDS); // Check every 100ms
    }

    public void stopMonitoring() {
        scheduler.shutdown();
    }
}