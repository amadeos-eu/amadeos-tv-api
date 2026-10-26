package eu.amadeos.tvapi.api;

import eu.amadeos.tvapi.DatabaseTest;
import eu.amadeos.tvapi.film.FilmStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static eu.amadeos.tvapi.Fixtures.film;
import static org.junit.jupiter.api.Assertions.*;

class FilmApiTest extends DatabaseTest {
    static final Instant T = Instant.parse("2026-10-25T19:15:00Z");
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;
    @Autowired
    FilmStore films;

    HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    void fill() {
        films.save(film("a", "Das Erste", "Tatort", "Tod am Deich", "Ein Frachter läuft ein.", T, 90), T);
        films.save(film("b", "WDR", "Lokalzeit", "Lokalzeit aus Köln", "", T.minusSeconds(86400 * 3), 30), T);
    }

    @Test
    void filmsSearchesAndCanBeCached() throws Exception {
        fill();
        HttpResponse<String> r = get("/tv/v1/films?q=" + URLEncoder.encode("tod deich", StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("max-age=300"));
        JsonNode body = json.readTree(r.body());
        assertEquals(1, body.path("total").asInt());
        JsonNode f = body.path("films").path(0);
        assertEquals("Tod am Deich", f.path("title").asString());
        assertEquals("Tatort", f.path("topic").asString());
        assertEquals(5400, f.path("durationSeconds").asInt());
        assertEquals("2026-10-25T19:15:00Z", f.path("publishedAt").asString());
        assertEquals("https://example.org/a.mp4", f.path("videoSd").asString());
        assertTrue(f.path("sourceId").isMissingNode(), "internal ids stay inside");

        String etag = r.headers().firstValue("ETag").orElseThrow();
        assertEquals(304, get("/tv/v1/films?q=" + URLEncoder.encode("tod deich", StandardCharsets.UTF_8),
                "If-None-Match", etag).statusCode());
    }

    @Test
    void filtersByChannelAndDay() throws Exception {
        fill();
        assertEquals(1, json.readTree(get("/tv/v1/films?channel=WDR").body()).path("total").asInt());
        assertEquals(1, json.readTree(get("/tv/v1/films?from=2026-10-25").body()).path("total").asInt());
        assertEquals(1, json.readTree(get("/tv/v1/films?to=2026-10-24T00:00:00Z").body()).path("total").asInt());
        assertEquals(1, json.readTree(get("/tv/v1/films?minMinutes=60").body()).path("total").asInt());
        assertEquals(400, get("/tv/v1/films?from=yesterday").statusCode());
    }

    @Test
    void oneFilmChannelsAndStatus() throws Exception {
        fill();
        String id = json.readTree(get("/tv/v1/films?channel=WDR").body()).path("films").path(0).path("id").asString();
        assertEquals("Lokalzeit aus Köln", json.readTree(get("/tv/v1/films/" + id).body()).path("title").asString());
        assertEquals(404, get("/tv/v1/films/00000000-0000-0000-0000-000000000000").statusCode());

        JsonNode channels = json.readTree(get("/tv/v1/channels").body());
        assertEquals(2, channels.size());
        assertEquals("Das Erste", channels.path(0).path("channel").asString());
        assertEquals(1, channels.path(0).path("films").asInt());

        JsonNode status = json.readTree(get("/tv/v1/status").body());
        assertEquals(2, status.path("films").asInt());
        assertTrue(status.path("sources").isArray());
    }
}
