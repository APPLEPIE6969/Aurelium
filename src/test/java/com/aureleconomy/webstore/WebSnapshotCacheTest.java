package com.aureleconomy.webstore;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3: /items and /search used to re-parse the whole item catalogue out of the
 * snapshot string on every request. The cache keeps one parsed copy per
 * publish, which is what the API rate limit then protects.
 */
class WebSnapshotCacheTest {

    @Test
    @DisplayName("the parsed catalogue is reused until the next publish")
    void itemsObjectIsParsedOncePerPublish() {
        WebSnapshotCache cache = new WebSnapshotCache();
        cache.publish(snapshot("{\"ore\":[{\"key\":\"DIAMOND\"}]}"));

        JsonObject first = cache.itemsObject();
        assertSame(first, cache.itemsObject(), "the catalogue was parsed again for the same publish");
        assertEquals("DIAMOND", first.getAsJsonArray("ore").get(0)
                .getAsJsonObject().get("key").getAsString());

        cache.publish(snapshot("{\"ore\":[{\"key\":\"GOLD_INGOT\"}]}"));
        JsonObject second = cache.itemsObject();
        assertNotSame(first, second, "the catalogue cache survived a publish");
        assertEquals("GOLD_INGOT", second.getAsJsonArray("ore").get(0)
                .getAsJsonObject().get("key").getAsString());
    }

    @Test
    @DisplayName("malformed catalogue JSON becomes an empty object, not a null")
    void malformedCatalogueIsEmpty() {
        WebSnapshotCache cache = new WebSnapshotCache();
        cache.publish(snapshot("not json"));

        assertTrue(cache.itemsObject().entrySet().isEmpty());
    }

    private static WebSnapshot.Snapshot snapshot(String itemsJson) {
        return new WebSnapshot.Snapshot("[]", itemsJson, "[]", "[]", "[]", "{}", "[]", false);
    }
}
