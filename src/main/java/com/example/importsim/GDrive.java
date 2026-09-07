package com.example.importsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Google Drive client for a publicly shared folder.
 *
 * Listing, in order of preference:
 *   1. user API key set  -> Drive API v3 (files.list).
 *   2. no key            -> clients6.google.com/drive/v2beta — the endpoint drive.google.com's own
 *                           web app uses, reachable with Google's public web key + an X-Origin
 *                           header. Paginated, so no ~50-item cap. No setup required.
 *   3. if (2) fails      -> scrape window['_DRIVE_ivd'] from the folder page (fragile, ~50 cap).
 *
 * File bytes are always pulled from drive.usercontent.google.com (no key), except when a user API
 * key is set, in which case alt=media is used.
 */
public class GDrive {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /** Public key embedded in the Drive web client; only usable with an X-Origin of drive.google.com. */
    private static final String WEB_KEY = "AIzaSyC1qbk75NzWBvSaDh6KnsjjA9pIrP4lYIE";

    private static final Pattern IVD = Pattern.compile("window\\['_DRIVE_ivd'] = '([^']+)'");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;

    public GDrive(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public record Entry(String id, String name, String mimeType) {
        public boolean isFolder() {
            return FOLDER_MIME.equals(mimeType);
        }
    }

    // ------------------------------------------------------------------ listing

    public List<Entry> listFolder(String folderId) throws IOException, InterruptedException {
        if (!apiKey.isEmpty()) {
            return listApiV3(folderId);
        }
        try {
            return listWeb(folderId);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[import-simulators] Drive web listing failed for {} ({}); falling back to page scrape",
                    folderId, e.toString());
            return listScrape(folderId);
        }
    }

    /** clients6 v2beta — paginated, no user key. */
    private List<Entry> listWeb(String folderId) throws IOException, InterruptedException {
        List<Entry> out = new ArrayList<>();
        String pageToken = "";
        int pages = 0;
        do {
            String q = enc("trashed = false and '" + folderId + "' in parents");
            String url = "https://clients6.google.com/drive/v2beta/files?"
                    + "openDrive=false&reason=102&syncType=0&errorRecovery=false"
                    + "&q=" + q
                    + "&fields=" + enc("nextPageToken,items(id,title,mimeType)")
                    + "&appDataFilter=NO_APP_DATA&spaces=drive&maxResults=1000"
                    + "&supportsTeamDrives=true&includeItemsFromAllDrives=true"
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + enc(pageToken))
                    + "&key=" + WEB_KEY;

            HttpResponse<String> resp = sendWithRetry(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .header("X-Origin", "https://drive.google.com")
                    .header("Referer", "https://drive.google.com/")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMinutes(2))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new IOException("clients6 HTTP " + resp.statusCode() + ": " + trim(resp.body(), 300));
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
            for (JsonElement el : items) {
                JsonObject o = el.getAsJsonObject();
                out.add(new Entry(
                        o.get("id").getAsString(),
                        o.has("title") ? o.get("title").getAsString() : o.get("id").getAsString(),
                        o.has("mimeType") ? o.get("mimeType").getAsString() : ""));
            }
            pageToken = root.has("nextPageToken") && !root.get("nextPageToken").isJsonNull()
                    ? root.get("nextPageToken").getAsString() : "";
        } while (!pageToken.isEmpty() && ++pages < 50);
        return out;
    }

    /** Official Drive API v3 with the user's own key. */
    private List<Entry> listApiV3(String folderId) throws IOException, InterruptedException {
        List<Entry> out = new ArrayList<>();
        String pageToken = null;
        do {
            String q = enc("'" + folderId + "' in parents and trashed = false");
            StringBuilder url = new StringBuilder("https://www.googleapis.com/drive/v3/files?q=").append(q)
                    .append("&key=").append(enc(apiKey))
                    .append("&fields=nextPageToken,files(id,name,mimeType)")
                    .append("&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true");
            if (pageToken != null) {
                url.append("&pageToken=").append(enc(pageToken));
            }
            HttpResponse<String> resp = sendWithRetry(get(url.toString()), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Drive API list failed (HTTP " + resp.statusCode() + "): " + trim(resp.body(), 400));
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray files = root.has("files") ? root.getAsJsonArray("files") : new JsonArray();
            for (JsonElement el : files) {
                JsonObject o = el.getAsJsonObject();
                out.add(new Entry(o.get("id").getAsString(), o.get("name").getAsString(), o.get("mimeType").getAsString()));
            }
            pageToken = root.has("nextPageToken") ? root.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);
        return out;
    }

    /** Last-ditch fallback: parse the folder page. Capped at ~50 items per folder by Google. */
    private List<Entry> listScrape(String folderId) throws IOException, InterruptedException {
        HttpResponse<String> resp = sendWithRetry(get("https://drive.google.com/drive/folders/" + folderId + "?hl=en"),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Folder page HTTP " + resp.statusCode()
                    + " — is the folder shared as 'Anyone with the link'?");
        }
        Matcher m = IVD.matcher(resp.body());
        if (!m.find()) {
            throw new IOException("Could not read the folder listing (not public, rate-limited, or page format changed).");
        }
        List<Entry> out = new ArrayList<>();
        JsonArray root = JsonParser.parseString(jsUnescape(m.group(1))).getAsJsonArray();
        if (root.isEmpty() || root.get(0).isJsonNull()) {
            return out;
        }
        JsonArray items = root.get(0).getAsJsonArray();
        for (JsonElement el : items) {
            JsonArray it = el.getAsJsonArray();
            if (it.size() < 4) {
                continue;
            }
            out.add(new Entry(it.get(0).getAsString(), it.get(2).getAsString(), it.get(3).getAsString()));
        }
        if (items.size() >= 50) {
            LOG.warn("[import-simulators] Scrape fallback hit the ~50-item cap for folder {}; that map will be incomplete.",
                    folderId);
        }
        return out;
    }

    // ------------------------------------------------------------------ file download

    public void downloadFile(String fileId, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());

        if (!apiKey.isEmpty()) {
            fetchToFile("https://www.googleapis.com/drive/v3/files/" + fileId
                    + "?alt=media&supportsAllDrives=true&key=" + enc(apiKey), dest);
            return;
        }

        String url = "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
        HttpResponse<InputStream> resp = sendWithRetry(get(url), HttpResponse.BodyHandlers.ofInputStream());
        String ct = resp.headers().firstValue("content-type").orElse("");
        if (resp.statusCode() == 200 && !ct.startsWith("text/html")) {
            try (InputStream in = resp.body()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        // Large-file virus-scan interstitial: pull the confirm form and retry once.
        String html = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
        String action = firstGroup(Pattern.compile("action=\"([^\"]+)\""), html);
        String confirm = firstGroup(Pattern.compile("name=\"confirm\"\\s+value=\"([^\"]*)\""), html);
        String uuid = firstGroup(Pattern.compile("name=\"uuid\"\\s+value=\"([^\"]*)\""), html);
        if (action == null) {
            throw new IOException("Google blocked the download for file " + fileId + " and no confirm form was found.");
        }
        String retry = action.replace("&amp;", "&") + "?id=" + fileId + "&export=download"
                + "&confirm=" + (confirm != null ? confirm : "t")
                + (uuid != null ? "&uuid=" + uuid : "");
        fetchToFile(retry, dest);
    }

    private void fetchToFile(String url, Path dest) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = sendWithRetry(get(url), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Download HTTP " + resp.statusCode() + " for " + url + " :: " + trim(body, 300));
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> HttpResponse<T> sendWithRetry(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpResponse<T> resp = http.send(req, handler);
                int sc = resp.statusCode();
                if ((sc == 429 || sc == 403 || sc / 100 == 5) && attempt < 4) {
                    Thread.sleep(1000L * attempt);
                    continue;
                }
                return resp;
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000L * attempt);
            }
        }
        throw last != null ? last : new IOException("request failed");
    }

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String trim(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }

    /** Decodes the JS string escapes used inside window['_DRIVE_ivd']. */
    static String jsUnescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'x' -> { out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; }
                case 'u' -> { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '/' -> out.append('/');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case '\\' -> out.append('\\');
                default -> out.append(n);
            }
        }
        return out.toString();
    }
}
