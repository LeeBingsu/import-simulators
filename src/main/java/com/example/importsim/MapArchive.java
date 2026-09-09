package com.example.importsim;

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
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads a map's zip and unpacks it, streaming so a multi-hundred-megabyte world stays cheap. */
public class MapArchive {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public void downloadInto(String url, Path dir, LongConsumer onBytes)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "import-simulators")
                .header("Accept", "*/*")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("Map download failed (HTTP " + resp.statusCode() + ")");
        }

        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream in = resp.body();
             ZipInputStream zip = new ZipInputStream(new CountingStream(in, onBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // Windows PowerShell writes "\" separators, which ZipEntry does not treat as path
                // separators, so every nested file would land as one long flat name without this.
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
