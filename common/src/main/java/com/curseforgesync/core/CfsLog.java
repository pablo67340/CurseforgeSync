package com.curseforgesync.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Logging that works before the game's logging framework is up.
 *
 * <p>At the point CurseforgeSync runs, log4j is configured for the launcher but Forge has not
 * installed its own appenders, and on 1.7.10 there is no slf4j on the classpath at all. Writing
 * straight to stdout is the only thing guaranteed to reach the console on every supported version.
 * A copy goes to {@code logs/curseforgesync.log} so admins can read the full sync report after
 * the server's own logging has scrolled it away.
 */
public final class CfsLog {
    private static final String PREFIX = "[CurseforgeSync] ";
    private static final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    private static PrintStream console = System.out;
    private static OutputStream file;
    private static boolean verbose;

    private CfsLog() {
    }

    public static void openLogFile(Path gameDir) {
        try {
            Path logs = gameDir.resolve("logs");
            Files.createDirectories(logs);
            file = Files.newOutputStream(logs.resolve("curseforgesync.log"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            file = null;
        }
    }

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void info(String message) {
        emit("INFO ", message);
    }

    public static void warn(String message) {
        emit("WARN ", message);
    }

    public static void error(String message) {
        emit("ERROR", message);
    }

    public static void error(String message, Throwable error) {
        emit("ERROR", message);
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        for (String line : buffer.toString().split("\r?\n")) {
            emit("ERROR", "  " + line);
        }
    }

    public static void debug(String message) {
        if (verbose) {
            emit("DEBUG", message);
        }
    }

    public static void close() {
        if (file != null) {
            try {
                file.flush();
                file.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the server is about to start regardless.
            }
            file = null;
        }
    }

    private static void emit(String level, String message) {
        console.println(PREFIX + message);
        if (file != null) {
            try {
                String line = "[" + TIMESTAMP.format(new Date()) + "] [" + level + "] " + message + System.lineSeparator();
                file.write(line.getBytes("UTF-8"));
                file.flush();
            } catch (IOException ignored) {
                file = null;
            }
        }
    }
}
