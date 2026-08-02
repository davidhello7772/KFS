package org.kadampa.festivalstreaming;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the RNNoise models the arnndn filter loads.
 * <p>
 * The Windows machine has them installed at the root of the current drive and the filter asks
 * for {@code /rnmodel/sh.rnnn}, which resolves there. Nothing else has that layout, so on any
 * other machine (and on a Windows machine without the folder) the copies shipped inside the jar
 * are unpacked once into a per-user directory and the filter is given their real path.
 */
public final class NoiseModels {

    private static final Logger logger = LoggerFactory.getLogger(NoiseModels.class);

    /** The models the filter graph asks for. The jar also carries cb, lq and mp, unused today. */
    public static final String GENERAL_NOISE_MODEL = "bd";
    public static final String SPEECH_MODEL = "sh";

    /** The historical location: the root of the current drive on Windows. */
    private static final String LEGACY_DIR = "/rnmodel";

    private NoiseModels() {
    }

    /**
     * The model file on disk, unpacking it from the jar if needed.
     *
     * @return the file, which may not exist if unpacking failed
     */
    public static File modelFile(String modelName) {
        File legacy = new File(LEGACY_DIR, modelName + ".rnnn").getAbsoluteFile();
        if (legacy.isFile()) {
            return legacy;
        }
        File unpacked = new File(new File(Host.userDataDir(), "rnmodel"), modelName + ".rnnn");
        if (!unpacked.isFile()) {
            unpack(modelName, unpacked);
        }
        return unpacked;
    }

    /**
     * The same path, escaped for use inside an ffmpeg filter description. A colon there separates
     * filter options, so a Windows drive letter has to be escaped or the path is cut in two.
     */
    public static String modelPathForFilter(String modelName) {
        return modelFile(modelName).getPath()
                .replace('\\', '/')
                .replace(":", "\\:");
    }

    private static void unpack(String modelName, File target) {
        String resource = LEGACY_DIR + "/" + modelName + ".rnnn";
        try (InputStream in = NoiseModels.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.error("Noise reduction model {} is missing from the application", resource);
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                logger.error("Could not create the noise reduction model directory {}", parent);
                return;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Unpacked the noise reduction model {} to {}", modelName, target);
        } catch (IOException e) {
            logger.error("Could not unpack the noise reduction model " + modelName, e);
        }
    }
}
