package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.webstore.PurchaseQueue;
import com.aureleconomy.webstore.WebSnapshotCache;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3 at the handler boundary: an oversized body is refused before it is parsed,
 * an oversized search query is refused before it scans the catalogue, and a
 * session that exceeds its burst gets 429 with a retry hint.
 */
class DashboardRequestLimitsTest {

    private WebSessionManager sessions;
    private WebSnapshotCache cache;
    private DashboardApiHandler handler;
    private UUID player;

    @BeforeEach
    void setUp() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        sessions = mock(WebSessionManager.class);
        cache = mock(WebSnapshotCache.class);
        when(cache.categoriesJson()).thenReturn("[]");
        handler = new DashboardApiHandler(plugin, sessions, cache,
                mock(PurchaseQueue.class), "test");
        player = UUID.randomUUID();
        when(sessions.validate("t")).thenReturn(player);
    }

    @Test
    @DisplayName("a body over 64 KB is refused instead of read in full")
    void oversizedBodyIsRefused() throws Exception {
        byte[] body = ("{\"item\":\"" + "a".repeat(70 * 1024) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        HttpExchange exchange = exchange("POST", "/api/test/buy?token=t", body);
        handler.handle(exchange);

        assertEquals(413, statusOf(exchange));
    }

    @Test
    @DisplayName("a search query longer than 64 characters is refused")
    void oversizedSearchQueryIsRefused() throws Exception {
        HttpExchange exchange = exchange("GET",
                "/api/test/search?token=t&q=" + "a".repeat(65), new byte[0]);
        handler.handle(exchange);

        assertEquals(400, statusOf(exchange));
    }

    @Test
    @DisplayName("requests past the burst are rejected with 429 and a retry hint")
    void burstIsThrottled() throws Exception {
        for (int i = 0; i < 20; i++) {
            HttpExchange allowed = exchange("GET", "/api/test/categories?token=t", new byte[0]);
            handler.handle(allowed);
            assertEquals(200, statusOf(allowed), "request " + i + " inside the burst was refused");
        }

        HttpExchange throttled = exchange("GET", "/api/test/categories?token=t", new byte[0]);
        handler.handle(throttled);

        assertEquals(429, statusOf(throttled));
        assertEquals("1", throttled.getResponseHeaders().getFirst("Retry-After"));
    }

    private static HttpExchange exchange(String method, String uri, byte[] body) {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestURI()).thenReturn(URI.create(uri));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private static int statusOf(HttpExchange exchange) throws Exception {
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return status.getValue();
    }
}
