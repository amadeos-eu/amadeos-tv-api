package eu.amadeos.tvapi.service;

import eu.amadeos.tvapi.model.ChannelCount;
import eu.amadeos.tvapi.model.Film;
import eu.amadeos.tvapi.model.FilmPage;
import eu.amadeos.tvapi.model.FilmQuery;
import eu.amadeos.tvapi.model.Saved;
import eu.amadeos.tvapi.repository.FilmRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The films: what the API shows and the crawler saves. */
@Service
public class FilmService {
    private final FilmRepository films;

    public FilmService(FilmRepository films) {
        this.films = films;
    }

    /** Adds the film, or updates the one with the same source and source id; either way it was seen now. */
    public Saved save(Film film, Instant now) {
        return films.save(film, now);
    }

    /** Of these ids of a source, the ones seen after {@code since}: the crawler need not ask for them again. */
    public Set<String> seenSince(String source, Collection<String> sourceIds, Instant since) {
        return films.seenSince(source, sourceIds, since);
    }

    public Optional<Film> get(UUID id) {
        return films.get(id);
    }

    /** The films that match, the best hits first when there are words, otherwise the newest first. */
    public FilmPage search(FilmQuery query) {
        return films.search(query);
    }

    public List<ChannelCount> channels() {
        return films.channels();
    }

    /** The films that are available. */
    public long count() {
        return films.count();
    }
}
