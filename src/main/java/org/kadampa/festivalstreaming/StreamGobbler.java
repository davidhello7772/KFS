package org.kadampa.festivalstreaming;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
/**
 * @author Bruno Salmon
 */
class StreamGobbler implements Runnable {
    private final InputStream inputStream;
    private final Consumer<String> outputLineConsumer;

    public StreamGobbler(InputStream inputStream, Consumer<String> outputLineConsumer) {
        this.inputStream = inputStream;
        this.outputLineConsumer = outputLineConsumer;
    }

    @Override
    public void run() {
        // ffmpeg writes UTF-8 whatever the platform's own charset is
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).lines()
                .forEach(outputLineConsumer);
    }

}