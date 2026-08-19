package com.curseforgesync.core;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Restarts the server after a sync installed something the loader had already looked for.
 *
 * <p>Coremods and ModLauncher services are discovered before CurseforgeSync gets to run, so a jar
 * that arrives during the sync is invisible until the next boot. Rather than leave the server in a
 * half-updated state, we bounce it. This is uncommon: it only happens when the pack gains or
 * changes one of those jars, not on an ordinary mod update.
 */
public final class Restarter {
    private Restarter() {
    }

    public static void restart(SyncConfig.RestartMode mode, int exitCode, String reason) {
        CfsLog.warn("A restart is needed: " + reason);
        switch (mode) {
            case NONE:
                CfsLog.warn("restartMode is 'none', so start-up continues. The new jars take effect "
                        + "after you restart the server yourself.");
                return;
            case RELAUNCH:
                if (relaunch()) {
                    return;
                }
                CfsLog.warn("Could not rebuild the command line, falling back to a plain exit.");
                // Fall through.
            case EXIT:
            default:
                CfsLog.warn("Stopping with exit code " + exitCode + ". Your panel or start script should "
                        + "bring the server back up; if it does not, start it again by hand.");
                CfsLog.close();
                Runtime.getRuntime().halt(exitCode);
        }
    }

    private static boolean relaunch() {
        List<String> command = buildCommandLine();
        if (command == null) {
            return false;
        }
        try {
            CfsLog.info("Relaunching: " + join(command));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir", ".")));
            builder.inheritIO();
            builder.start();
            CfsLog.close();
            Runtime.getRuntime().halt(0);
            return true;
        } catch (IOException e) {
            CfsLog.error("Relaunch failed", e);
            return false;
        }
    }

    /**
     * Rebuilds this JVM's command line from {@code sun.java.command} and the runtime MX bean.
     *
     * <p>{@code ProcessHandle} would be tidier but it is Java 9+, and the legacy ports run on 8.
     */
    private static List<String> buildCommandLine() {
        String mainCommand = System.getProperty("sun.java.command");
        if (mainCommand == null || mainCommand.trim().isEmpty()) {
            return null;
        }

        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return null;
        }
        File java = new File(new File(javaHome, "bin"), isWindows() ? "java.exe" : "java");
        if (!java.isFile()) {
            return null;
        }

        List<String> command = new ArrayList<String>();
        command.add(java.getAbsolutePath());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // A second agent attachment on relaunch would fail; everything else carries over.
            if (!argument.startsWith("-agentlib:jdwp") && !argument.startsWith("-javaagent:")) {
                command.add(argument);
            }
        }

        String classpath = System.getProperty("java.class.path");
        String[] parts = mainCommand.split(" ");
        if (mainCommand.endsWith(".jar") || parts[0].endsWith(".jar")) {
            command.add("-jar");
            command.add(parts[0]);
        } else {
            if (classpath != null && !classpath.isEmpty()) {
                command.add("-cp");
                command.add(classpath);
            }
            command.add(parts[0]);
        }
        for (int i = 1; i < parts.length; i++) {
            command.add(parts[i]);
        }
        return command;
    }

    private static boolean isWindows() {
        String name = System.getProperty("os.name", "");
        return name.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(parts.get(i));
        }
        return text.toString();
    }
}
