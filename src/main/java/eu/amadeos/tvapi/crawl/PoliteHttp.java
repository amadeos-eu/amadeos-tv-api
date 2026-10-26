package eu.amadeos.tvapi.crawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crawler's HTTP client, polite to the media libraries: one request at a time per host with a pause
 * between them, a User-Agent that says who asks, and one more try after a pause when a server is busy
 * (429, 5xx). Other errors are not tried again.
 */
@Component
public class PoliteHttp implements Http {
    private static final Logger LOG = LoggerFactory.getLogger(PoliteHttp.class);

    private final HttpClient client;
    private final String userAgent;
    private final Duration pause;
    private final Duration timeout;
    /** Per host: when the last request ended. Requests to one host wait for each other. */
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();

    @Autowired
    public PoliteHttp(CrawlProperties properties, ObjectProvider<BuildProperties> build) {
        this(properties.pause(), properties.timeout(),
                "amadeos-tv-api/" + build.stream().map(BuildProperties::getVersion).findFirst().orElse("dev")
                        + " (+https://amadeos.eu)");
    }

    PoliteHttp(Duration pause, Duration timeout, String userAgent) {
        this.pause = pause;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String get(String url) throws IOException {
        URI uri = URI.create(url);
        Object lock = hostLocks.computeIfAbsent(uri.getHost(), h -> new Object());
        synchronized (lock) {
            HttpResponse<String> response = send(uri);
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                LOG.info("{} answered {}, trying once more", uri.getHost(), response.statusCode());
                sleep(pause.multipliedBy(10));
                response = send(uri);
            }
            if (response.statusCode() != 200) {
                throw new IOException(url + ": HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    private HttpResponse<String> send(URI uri) throws IOException {
        Long last = lastRequest.get(uri.getHost());
        if (last != null) {
            long wait = pause.toNanos() - (System.nanoTime() - last);
            if (wait > 0) sleep(Duration.ofNanos(wait));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + uri, e);
        } finally {
            lastRequest.put(uri.getHost(), System.nanoTime());
        }
    }

    private static void sleep(Duration d) throws IOException {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
