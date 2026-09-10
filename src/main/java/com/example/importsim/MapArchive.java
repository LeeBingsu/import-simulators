package com.example.importsim;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads a map archive and unpacks it. Handles the .zip and .rar a map may be published as. */
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

        // Both formats are read from a file rather than the socket. Rar needs it because the
        // reader seeks. Zip needs it because ZipInputStream, reading forwards only, rejects a
        // stored entry carrying a data descriptor ("only DEFLATED entries can have EXT
        // descriptor") — which is how some tools write the empty region files in a world.
        boolean rar = url.toLowerCase(Locale.ROOT).endsWith(".rar");
        Path temp = Files.createTempFile("import-simulators-", rar ? ".rar" : ".zip");
        try {
            try (InputStream in = new CountingStream(resp.body(), onBytes)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (rar) {
                unpackRar(temp, root);
            } else {
                unpackZip(temp, root);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void unpackZip(Path archive, Path root) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.isEmpty()) {
                    continue;
                }
                Path out = resolve(root, name);
                if (entry.isDirectory() || name.endsWith("\\")) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void unpackRar(Path temp, Path root) throws IOException {
        try (Archive archive = new Archive(temp.toFile())) {
            if (archive.isEncrypted()) {
                throw new IOException("The archive is password protected");
            }
            FileHeader header;
            while ((header = archive.nextFileHeader()) != null) {
                Path out = resolve(root, header.getFileName());
                if (header.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    archive.extractFile(header, os);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the .rar archive: " + e.getMessage(), e);
        }
    }

    /** Entry paths are untrusted: normalise the separator and keep them inside the target folder. */
    private static Path resolve(Path root, String entryName) throws IOException {
        // Archives written on Windows use "\" separators, which neither ZipEntry nor FileHeader
        // treats as a path separator, so every nested file would land as one long flat name.
        String name = entryName.replace('\\', '/');
        Path out = root.resolve(name).normalize();
        if (!out.startsWith(root)) {
            throw new IOException("Archive entry escapes the target directory: " + entryName);
        }
        return out;
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
