package com.example.importsim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What there is to download, read from catalog/maps.json and catalog/kits.json in the repo.
 *
 * These used to be Drive folder listings. Serving them as static files keeps the mod off Google
 * Drive entirely, which matters because Drive caps how often a popular public file may be fetched.
 */
public class Catalog {

    /** A map, and the release zip holding it. */
    public record MapEntry(String name, String url, long size) {
    }

    /** A kit file, sized so an already-downloaded copy can be recognised. */
    public record KitEntry(String name, long size) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;

    public Catalog(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public List<MapEntry> maps() throws IOException, InterruptedException {
        List<MapEntry> out = new ArrayList<>();
        for (JsonElement el : fetch("maps.json")) {
            JsonObject o = el.getAsJsonObject();
            out.add(new MapEntry(o.get("name").getAsString(),
                    o.get("url").getAsString(),
                    o.has("size") ? o.get("size").getAsLong() : 0));
        }
        return out;
    }

    public List<KitEntry> kits() throws IOException, InterruptedException {
        List<KitEntry> out = new ArrayList<>();
        for (JsonElement el : fetch("kits.json")) {
            JsonObject o = el.getAsJsonObject();
            out.add(new KitEntry(o.get("name").getAsString(),
                    o.has("size") ? o.get("size").getAsLong() : 0));
        }
        return out;
    }

    private JsonArray fetch(String file) throws IOException, InterruptedException {
        String url = baseUrl + "/" + file;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "import-simulators")
                .header("Accept", "application/json")
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Could not read " + url + " (HTTP " + resp.statusCode() + ")");
        }
        return JsonParser.parseString(resp.body()).getAsJsonArray();
    }
}
