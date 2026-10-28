package eu.amadeos.tvapi.model;

import java.util.List;

/** One page of a search, and how many films match in all. */
public record FilmPage(long total, int page, int size, List<Film> films) {
}
