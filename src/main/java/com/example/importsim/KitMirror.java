package com.example.importsim;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * Serves kit files as plain static files (the repo's kits/ folder on GitHub by default).
 *
 * Google caps how often a popular public Drive file may be downloaded, which is what makes the
 * Drive copy unreliable; static hosting has no such cap. The mirror does not have to be complete —
 * a kit it does not carry simply falls back to Drive.
 */
public class KitMirror {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;

    public KitMirror(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /**
     * @return true once the file is written; false when the mirror does not carry it, so the
     *         caller should fall back to Drive.
     */
    public boolean download(String name, Path dest, LongConsumer onBytes)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return false;
        }
        String url = baseUrl + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "import-simulators")
                .header("Accept", "*/*")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            return false;
        }
        Files.createDirectories(dest.getParent());
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                onBytes.accept(n);
            }
        }
        return true;
    }
}
