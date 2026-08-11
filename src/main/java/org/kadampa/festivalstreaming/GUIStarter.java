package org.kadampa.festivalstreaming;

import org.kadampa.festivalstreaming.linux.NvidiaOffload;

/**
 * Everything that must happen before the JavaFX toolkit exists lives here. Prism builds
 * one rendering pipeline per process at toolkit init, and NVIDIA's PRIME offload is
 * chosen by environment variables the JVM must already carry - both decided by the
 * render device setting, so the settings are read first. That read is JavaFX-free
 * (SettingsUtil, Settings and Host import nothing of the toolkit), and the StreamingGUI
 * constructor reads the file again: the double read is idempotent, at the price of
 * repeating any malformed-language warning in the log.
 *
 * <p>The NVIDIA-offloaded child runs this same main() and NvidiaOffload's guard makes
 * relaunchIfNeeded a no-op there, so the decision logic exists in exactly one place.
 */
public class GUIStarter {
    public static void main(final String[] args) {
        Settings settings = SettingsUtil.loadSettings("settings");
        Settings.RenderDevice choice = Settings.RenderDevice.fromToken(settings.getRenderDevice());
        if (choice == Settings.RenderDevice.CPU) {
            // "sw" is Prism's software pipeline; it must be named before launch and works
            // the same on every platform, no relaunch involved
            System.setProperty("prism.order", "sw");
            System.setProperty("kfs.renderStatus", "cpu");
        } else if (Host.isLinux()) {
            boolean nvidiaWanted = choice != Settings.RenderDevice.DEFAULT_GPU; // AUTO prefers NVIDIA too
            int childExit = NvidiaOffload.relaunchIfNeeded(nvidiaWanted);
            if (childExit >= 0) {
                System.exit(childExit); // this process was only the supervisor
            }
            System.setProperty("kfs.renderStatus",
                    NvidiaOffload.statusForThisProcess(choice == Settings.RenderDevice.NVIDIA));
        }
        // Windows and macOS choose their GPU outside the app, so Auto there means the
        // hardware default and needs nothing from us
        StreamingGUI.main(args);
    }
}
