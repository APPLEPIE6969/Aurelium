package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.webstore.PurchaseQueue;
import com.aureleconomy.webstore.WebSnapshotCache;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
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
 * How the dashboard API authenticates and what it tells the browser (M13).
 *
 * <p>The API used to accept {@code ?token=} as well as the Authorization header.
 * A token in a URL ends up in access logs, proxy logs and browser history, and a
 * shared dashboard link put it in all three. The query parameter is refused now,
 * and every response, including the ones raised before authentication, carries
 * {@code Referrer-Policy: no-referrer} so a URL cannot leak through a referrer
 * either.
 */
class DashboardAuthTest {

    private WebSessionManager sessions;
    private DashboardApiHandler handler;
    private UUID player;

    @BeforeEach
    void setUp() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        sessions = mock(WebSessionManager.class);
        WebSnapshotCache cache = mock(WebSnapshotCache.class);
        when(cache.categoriesJson()).thenReturn("[]");
        handler = new DashboardApiHandler(plugin, sessions, cache,
                mock(PurchaseQueue.class), "test");
        player = UUID.randomUUID();
        when(sessions.validate("t")).thenReturn(player);
    }

    @Test
    @DisplayName("a token in the query string no longer authenticates")
    void queryTokenIsRefused() throws Exception {
        HttpExchange exchange = exchange("GET", "/api/test/categories?token=t", null);

        handler.handle(exchange);

        assertEquals(401, statusOf(exchange),
                "the query token still authenticated, so the same token keeps ending up in logs");
    }

    @Test
    @DisplayName("the same token in the Authorization header still authenticates")
    void headerTokenStillWorks() throws Exception {
        HttpExchange exchange = exchange("GET", "/api/test/categories", "t");

        handler.handle(exchange);

        assertEquals(200, statusOf(exchange), "header auth must keep working");
    }

    @Test
    @DisplayName("every API response carries Referrer-Policy, including errors")
    void everyResponseCarriesReferrerPolicy() throws Exception {
        HttpExchange ok = exchange("GET", "/api/test/categories", "t");
        handler.handle(ok);
        assertEquals(200, statusOf(ok));
        assertEquals("no-referrer", ok.getResponseHeaders().getFirst("Referrer-Policy"),
                "a successful response can be linked to, so it must not send a referrer");

        HttpExchange unauthorized = exchange("GET", "/api/test/categories", null);
        handler.handle(unauthorized);
        assertEquals(401, statusOf(unauthorized));
        assertEquals("no-referrer", unauthorized.getResponseHeaders().getFirst("Referrer-Policy"),
                "the response raised before authentication has to carry it too");

        HttpExchange notFound = exchange("GET", "/api/other-server/categories", "t");
        handler.handle(notFound);
        assertEquals(404, statusOf(notFound));
        assertEquals("no-referrer", notFound.getResponseHeaders().getFirst("Referrer-Policy"),
                "routing errors are responses as well");
    }

    private static HttpExchange exchange(String method, String uri, String bearerToken) {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers requestHeaders = new Headers();
        if (bearerToken != null) {
            requestHeaders.set("Authorization", "Bearer " + bearerToken);
        }
        Headers responseHeaders = new Headers();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        when(exchange.getRequestURI()).thenReturn(URI.create(uri));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private static int statusOf(HttpExchange exchange) throws Exception {
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return status.getValue();
    }
}
