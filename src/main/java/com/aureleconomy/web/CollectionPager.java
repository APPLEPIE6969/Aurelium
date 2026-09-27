package com.aureleconomy.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Search, sort and page over a JSON collection.
 *
 * <p>The dashboard's auctions, orders and stocks endpoints all answer with the
 * same envelope, and the browser drives one paginator against all of them, so
 * the shaping lives here instead of in the request handler.
 *
 * <p>Kept package-private and static so it can be unit tested without standing
 * up an HTTP exchange.
 */
final class CollectionPager {

    private CollectionPager() {
    }

    /**
     * Describes one collection: which fields the client may search and sort on,
     * and which of the sortable ones order by magnitude rather than as text.
     */
    record Spec(List<String> search, List<String> sortable, List<String> numeric) {
        boolean canSort(String field) {
            return field != null && sortable.contains(field);
        }
    }

    /**
     * Applies search, then sort, then the page window.
     *
     * @param query    free text, matched case-insensitively as a substring
     * @param sort     field to order by, ignored when the spec does not allow it
     * @param ascending order direction for the sort
     */
    static JsonObject page(JsonArray rows, int pageNo, int pageSize, int maxPageSize,
                           String query, String sort, boolean ascending, Spec spec) {
        JsonArray working = rows == null ? new JsonArray() : rows;
        if (spec != null) {
            working = search(working, query, spec.search());
            working = sort(working, sort, ascending, spec);
        }

        int size = Math.min(Math.max(1, maxPageSize), Math.max(1, pageSize));
        int total = working.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        // A caller paging past the end (for example after a filter shrinks the
        // set) should see the last page rather than an empty one.
        int page = Math.min(Math.max(0, pageNo), totalPages - 1);
        int start = page * size;

        JsonArray slice = new JsonArray();
        for (int i = start; i < Math.min(start + size, total); i++) {
            slice.add(working.get(i));
        }

        JsonObject out = new JsonObject();
        out.addProperty("page", page);
        out.addProperty("totalPages", totalPages);
        out.addProperty("totalItems", total);
        out.addProperty("pageSize", size);
        out.add("items", slice);
        return out;
    }

    static JsonArray search(JsonArray rows, String query, List<String> fields) {
        if (query == null || query.isBlank() || fields == null || fields.isEmpty()) {
            return rows;
        }
        String q = query.trim().toLowerCase();
        JsonArray out = new JsonArray();
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            for (String field : fields) {
                if (o.has(field) && o.get(field).isJsonPrimitive()
                        && o.get(field).getAsString().toLowerCase().contains(q)) {
                    out.add(el);
                    break;
                }
            }
        }
        return out;
    }

    static JsonArray sort(JsonArray rows, String field, boolean ascending, Spec spec) {
        if (spec == null || !spec.canSort(field)) {
            return rows;
        }
        boolean numeric = spec.numeric().contains(field);
        List<JsonObject> list = new ArrayList<>(rows.size());
        for (JsonElement el : rows) {
            if (el.isJsonObject()) {
                list.add(el.getAsJsonObject());
            }
        }
        list.sort((a, b) -> {
            int cmp = numeric
                    ? Double.compare(num(a, field), num(b, field))
                    : str(a, field).compareToIgnoreCase(str(b, field));
            return ascending ? cmp : -cmp;
        });
        JsonArray out = new JsonArray();
        for (JsonObject o : list) {
            out.add(o);
        }
        return out;
    }

    private static double num(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsDouble() : 0d;
        } catch (Exception e) {
            return 0d;
        }
    }

    private static String str(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
