package eu.amadeos.tvapi.crawl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PoliteHttpTest {
    HttpServer server;
    final List<String> agents = new CopyOnWriteArrayList<>();
    final List<Long> times = new CopyOnWriteArrayList<>();
    final AtomicInteger busy = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            agents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            times.add(System.nanoTime());
            String path = exchange.getRequestURI().getPath();
            int status = path.equals("/busy") && busy.getAndIncrement() == 0 ? 503
                    : path.equals("/missing") ? 404 : 200;
            byte[] body = ("{\"path\":\"" + path + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void saysWhoAsksAndPausesBetweenRequests() throws IOException {
        PoliteHttp http = new PoliteHttp(Duration.ofMillis(200), Duration.ofSeconds(5), "amadeos-tv-api/test");
        assertEquals("{\"path\":\"/a\"}", http.get(url("/a")));
        http.get(url("/b"));
        assertEquals(List.of("amadeos-tv-api/test", "amadeos-tv-api/test"), agents);
        assertTrue(times.get(1) - times.get(0) >= Duration.ofMillis(190).toNanos(),
                "pause " + (times.get(1) - times.get(0)) / 1_000_000 + " ms");
    }

    @Test
    void aBusyServerIsAskedOnceMore() throws IOException {
        PoliteHttp http = new PoliteHttp(Duration.ofMillis(10), Duration.ofSeconds(5), "t");
        assertEquals("{\"path\":\"/busy\"}", http.get(url("/busy")));
        assertEquals(2, agents.size());
    }

    @Test
    void otherErrorsAreNotTriedAgain() {
        PoliteHttp http = new PoliteHttp(Duration.ofMillis(10), Duration.ofSeconds(5), "t");
        IOException e = assertThrows(IOException.class, () -> http.get(url("/missing")));
        assertTrue(e.getMessage().endsWith("HTTP 404"), e.getMessage());
        assertEquals(1, agents.size());
    }
}
