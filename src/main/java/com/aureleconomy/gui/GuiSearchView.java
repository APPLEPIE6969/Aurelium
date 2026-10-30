package com.aureleconomy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Immutable search state for the menu screens: the text being filtered on and
 * the page within the filtered list.
 *
 * <p>Search used to be a mutable field on each GUI, which made every rebuild
 * path a place to forget it: page navigation constructed a fresh GUI without
 * the query, the orders prompt set the field on an instance it then threw away,
 * and the shop set it after the constructor had already rendered the page.
 * Carrying both values through the constructor means a rebuild cannot lose
 * either one: {@link #next()} returns a view with the same query on the next
 * page, and {@link #withQuery(String)} returns a view with the new query on the
 * first page.
 */
public final class GuiSearchView {

    private static final GuiSearchView EMPTY = new GuiSearchView(null, 0);

    private final String query;
    private final int page;

    private GuiSearchView(String query, int page) {
        this.query = query;
        this.page = page;
    }

    /** No filter, first page. */
    public static GuiSearchView none() {
        return EMPTY;
    }

    /** A filter on the first page. */
    public static GuiSearchView of(String query) {
        return EMPTY.withQuery(query);
    }

    /** A filter on a specific page, used by callers that hold a page number. */
    public static GuiSearchView of(String query, int page) {
        return EMPTY.withQuery(query).withPage(page);
    }

    /** The active query, or null when nothing is being filtered. */
    public String query() {
        return query;
    }

    public boolean active() {
        return query != null;
    }

    public int page() {
        return page;
    }

    /**
     * A view for {@code raw}. Null and blank both mean "no filter", and either
     * way the result starts on the first page: keeping the old page number
     * would show an empty page whenever the new query matches fewer items than
     * the old one did.
     */
    public GuiSearchView withQuery(String raw) {
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return page == 0 && query == null ? this : EMPTY;
        }
        return new GuiSearchView(trimmed, 0);
    }

    public GuiSearchView withPage(int newPage) {
        int clamped = Math.max(0, newPage);
        return clamped == page ? this : new GuiSearchView(query, clamped);
    }

    public GuiSearchView next() {
        return withPage(page + 1);
    }

    public GuiSearchView previous() {
        return withPage(page - 1);
    }

    /** Case-insensitive substring match; an inactive view matches everything. */
    public boolean matches(String candidate) {
        if (query == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    /** The items whose name matches this view, in their original order. */
    public <T> List<T> filter(List<T> items, Function<T, String> name) {
        if (query == null) {
            return items;
        }
        List<T> matches = new ArrayList<>();
        for (T item : items) {
            if (matches(name.apply(item))) {
                matches.add(item);
            }
        }
        return matches;
    }

    /** Number of pages, never less than one so "page 1 of 1" is displayable. */
    public int pageCount(int total, int perPage) {
        return Math.max(1, (total + perPage - 1) / perPage);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext(int total, int perPage) {
        return page < pageCount(total, perPage) - 1;
    }

    /** The slice of {@code items} that belongs on the current page. */
    public <T> List<T> pageOf(List<T> items, int perPage) {
        int start = page * perPage;
        if (start >= items.size()) {
            return List.of();
        }
        return items.subList(start, Math.min(start + perPage, items.size()));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GuiSearchView view)) {
            return false;
        }
        return page == view.page && java.util.Objects.equals(query, view.query);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(query, page);
    }

    @Override
    public String toString() {
        return "GuiSearchView[query=" + query + ", page=" + page + "]";
    }
}
