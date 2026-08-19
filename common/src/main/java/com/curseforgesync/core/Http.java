package com.curseforgesync.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * A small {@link HttpURLConnection} wrapper with retries and redirect handling.
 *
 * <p>{@code java.net.http.HttpClient} would be nicer but it is Java 11+, and the 1.7.10 and 1.12.2
 * ports have to run on Java 8.
 */
public final class Http {
    private static final String USER_AGENT = "CurseforgeSync/1.0 (+https://github.com/pablo67340/CurseforgeSync)";
    private static final int MAX_REDIRECTS = 5;

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxAttempts;

    public Http(int connectTimeoutSeconds, int readTimeoutSeconds, int maxAttempts) {
        this.connectTimeoutMillis = Math.max(1, connectTimeoutSeconds) * 1000;
        this.readTimeoutMillis = Math.max(1, readTimeoutSeconds) * 1000;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public static final class HttpError extends IOException {
        private static final long serialVersionUID = 1L;
        public final int statusCode;

        HttpError(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    public byte[] get(String url, Map<String, String> headers) throws IOException {
        return retry(url, new Attempt<byte[]>() {
            @Override
            public byte[] run(HttpURLConnection connection) throws IOException {
                InputStream body = connection.getInputStream();
                try {
                    return readFully(body);
                } finally {
                    body.close();
                }
            }
        }, headers, null);
    }

    public byte[] post(String url, Map<String, String> headers, byte[] body) throws IOException {
        return retry(url, new Attempt<byte[]>() {
            @Override
            public byte[] run(HttpURLConnection connection) throws IOException {
                InputStream response = connection.getInputStream();
                try {
                    return readFully(response);
                } finally {
                    response.close();
                }
            }
        }, headers, body);
    }

    /**
     * Downloads to a sibling {@code .part} file and moves it into place only once the whole body
     * has arrived, so an interrupted start-up can never leave a truncated jar in the mods folder.
     */
    public long download(String url, Map<String, String> headers, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(partial);
        Long written = retry(url, new Attempt<Long>() {
            @Override
            public Long run(HttpURLConnection connection) throws IOException {
                InputStream body = connection.getInputStream();
                OutputStream out = Files.newOutputStream(partial);
                try {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    int read;
                    while ((read = body.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        total += read;
                    }
                    return Long.valueOf(total);
                } finally {
                    out.close();
                    body.close();
                }
            }
        }, headers, null);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return written.longValue();
    }

    private interface Attempt<T> {
        T run(HttpURLConnection connection) throws IOException;
    }

    private <T> T retry(String url, Attempt<T> attempt, Map<String, String> headers, byte[] body) throws IOException {
        IOException last = null;
        for (int tries = 1; tries <= maxAttempts; tries++) {
            try {
                return once(url, attempt, headers, body);
            } catch (HttpError e) {
                // 4xx responses are deterministic; retrying just delays the inevitable.
                if (e.statusCode >= 400 && e.statusCode < 500 && e.statusCode != 408 && e.statusCode != 429) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (tries < maxAttempts) {
                long backoff = 500L * (1L << (tries - 1));
                CfsLog.debug("Retrying " + url + " in " + backoff + "ms (" + last + ")");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private <T> T once(String url, Attempt<T> attempt, Map<String, String> headers, byte[] body) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            try {
                connection.setConnectTimeout(connectTimeoutMillis);
                connection.setReadTimeout(readTimeoutMillis);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                if (headers != null) {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                if (body != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    OutputStream out = connection.getOutputStream();
                    try {
                        out.write(body);
                    } finally {
                        out.close();
                    }
                }

                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new HttpError(status, "Redirect without a Location header from " + current);
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new HttpError(status, "HTTP " + status + " from " + current + describe(connection));
                }
                return attempt.run(connection);
            } finally {
                connection.disconnect();
            }
        }
        throw new HttpError(310, "Too many redirects starting at " + url);
    }

    private static String describe(HttpURLConnection connection) {
        InputStream errorBody = connection.getErrorStream();
        if (errorBody == null) {
            return "";
        }
        try {
            byte[] bytes = readFully(errorBody);
            String text = new String(bytes, "UTF-8").trim();
            if (text.isEmpty()) {
                return "";
            }
            return " -- " + (text.length() > 300 ? text.substring(0, 300) + "..." : text);
        } catch (IOException e) {
            return "";
        } finally {
            try {
                errorBody.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] chunk = new byte[16384];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
