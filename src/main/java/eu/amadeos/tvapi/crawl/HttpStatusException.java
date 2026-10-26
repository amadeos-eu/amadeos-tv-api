package eu.amadeos.tvapi.crawl;

import java.io.IOException;

/** A server answered, but not with 200. */
public class HttpStatusException extends IOException {
    private final int status;

    public HttpStatusException(String url, int status) {
        super(url + ": HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
