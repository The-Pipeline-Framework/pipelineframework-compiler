package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpsCensusTransportTest {
    @Test void fixedHeadersNoRedirectsAndDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/ok", exchange -> {
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("TPF-Build-Census/1", exchange.getRequestHeaders().getFirst("User-Agent"));
            for (String header : new String[] {"Authorization", "Cookie", "Referer", "traceparent", "baggage"})
                assertFalse(exchange.getRequestHeaders().containsKey(header));
            assertEquals("{}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/unexpected"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/unexpected", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        for (int status : new int[] {429, 503}) server.createContext("/status-" + status, exchange -> {
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        try {
            assertTrue(new HttpsCensusTransport(URI.create(base + "/ok")).send("{}"));
            assertFalse(new HttpsCensusTransport(URI.create(base + "/redirect")).send("{}"));
            assertEquals(0, redirected.get());
            assertFalse(new HttpsCensusTransport(URI.create(base + "/status-429")).send("{}"));
            assertFalse(new HttpsCensusTransport(URI.create(base + "/status-503")).send("{}"));
            assertTimeout(Duration.ofSeconds(2), () -> assertThrows(java.net.http.HttpTimeoutException.class,
                    () -> new HttpsCensusTransport(URI.create(base + "/slow")).send("{}")));
            assertTimeout(Duration.ofSeconds(2), () -> assertThrows(java.net.http.HttpTimeoutException.class,
                    () -> new HttpsCensusTransport(URI.create(base + "/slow-body")).send("{}")));
        } finally { server.stop(0); }
    }
}
