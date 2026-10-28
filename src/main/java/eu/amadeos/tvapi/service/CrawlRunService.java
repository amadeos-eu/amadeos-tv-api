package eu.amadeos.tvapi.service;

import eu.amadeos.tvapi.model.CrawlRun;
import eu.amadeos.tvapi.repository.CrawlRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** What each run of the crawler did, per source: for /status, and to see when a source broke. */
@Service
public class CrawlRunService {
    private final CrawlRunRepository runs;

    public CrawlRunService(CrawlRunRepository runs) {
        this.runs = runs;
    }

    /** Notes that a run of the source started; the id is for {@link #finish}. */
    public long start(String source, Instant now) {
        return runs.start(source, now);
    }

    /** {@code error} is null when the run went well. */
    public void finish(long id, Instant now, int filmsNew, int filmsUpdated, String error) {
        runs.finish(id, now, filmsNew, filmsUpdated, error);
    }

    /** The latest run of each source. */
    public List<CrawlRun> latest() {
        return runs.latest();
    }
}
