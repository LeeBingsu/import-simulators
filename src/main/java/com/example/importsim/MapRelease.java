package com.example.importsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maps published as one zip per world on a GitHub release.
 *
 * A release asset has no per-file download cap, and pulls a whole world in a single request
 * instead of the several hundred a Drive folder walk needs.
 */
public class MapRelease {

    public record Asset(String name, String url, long size) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiUrl;
    private List<Asset> cached;

    public MapRelease(String apiUrl) {
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
    }

    public boolean isConfigured() {
        return !apiUrl.isEmpty();
    }

    /** Assets on the release, fetched once per import. Empty when the release is unreachable. */
    private List<Asset> assets() {
        if (cached != null) {
            return cached;
        }
        List<Asset> out = new ArrayList<>();
        try {
            HttpResponse<String> resp = http.send(request(apiUrl), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray arr = root.has("assets") ? root.getAsJsonArray("assets") : new JsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    out.add(new Asset(o.get("name").getAsString(),
                            o.get("browser_download_url").getAsString(),
                            o.has("size") ? o.get("size").getAsLong() : 0));
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // Unreachable release just means "fall back to Drive".
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        cached = out;
        return out;
    }

    /**
     * Finds the zip for a map. GitHub rewrites spaces in asset names to dots, so names are
     * compared with punctuation and case removed rather than literally.
     */
    public Asset find(String mapName) {
        if (!isConfigured()) {
            return null;
        }
        String want = key(mapName);
        for (Asset a : assets()) {
            String name = a.name();
            int dot = name.lastIndexOf('.');
            if (key(dot > 0 ? name.substring(0, dot) : name).equals(want)) {
                return a;
            }
        }
        return null;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Downloads the zip and unpacks it into {@code dir}, reporting downloaded bytes as it goes. */
    public void downloadInto(Asset asset, Path dir, LongConsumer onBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = http.send(request(asset.url()), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("Map download failed (HTTP " + resp.statusCode() + "): " + asset.name());
        }
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream in = resp.body();
             ZipInputStream zip = new ZipInputStream(new CountingStream(in, onBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // Windows PowerShell writes "\" separators, which are not path separators to
                // ZipEntry, so every nested file would land as one long flat name without this.
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Zip entry escapes the target directory: " + entry.getName());
                }
                if (name.endsWith("/")) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "import-simulators")
                .header("Accept", "*/*")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();
    }

    /** Reports raw bytes off the wire, so progress tracks the download rather than the unpacking. */
    private static final class CountingStream extends InputStream {
        private final InputStream in;
        private final LongConsumer onBytes;

        CountingStream(InputStream in, LongConsumer onBytes) {
            this.in = in;
            this.onBytes = onBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                onBytes.accept(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                onBytes.accept(n);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
