package org.pipelineframework.processor.census;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.parallel.Isolated("Exercises real request deadlines without concurrent compiler fixture contention")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
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
    @Test void localTlsHandshakeFailureIsRejected() throws Exception {
        // Reject the ClientHello with a TLS fatal handshake_failure alert. No keys,
        // DNS lookups or server-side crypto provider startup are involved.
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (var server = new ServerSocket(0, 1, loopback)) {
            var clientHelloSeen = new java.util.concurrent.CountDownLatch(1);
            var peer = Thread.ofPlatform().daemon().start(() -> {
                try (var connection = server.accept()) {
                    connection.setSoTimeout(1500);
                    byte[] header = connection.getInputStream().readNBytes(5);
                    if (header.length != 5 || header[0] != 0x16) return;
                    int length = ((header[3] & 0xff) << 8) | (header[4] & 0xff);
                    if (length == 0 || length > 16384) return;
                    byte[] hello = connection.getInputStream().readNBytes(length);
                    if (hello.length != length || hello[0] != 1) return;
                    clientHelloSeen.countDown();
                    connection.getOutputStream().write(new byte[] {0x15, 0x03, 0x03, 0, 2, 2, 0x28});
                    connection.getOutputStream().flush();
                } catch (java.io.IOException unavailable) { }
            });
            try {
                URI endpoint = URI.create("https://127.0.0.1:" + server.getLocalPort() + "/observations");
                assertThrows(javax.net.ssl.SSLException.class, () -> new HttpsCensusTransport(endpoint).send("{}"));
                assertEquals(0, clientHelloSeen.getCount(), "The local peer rejected a real TLS ClientHello");
            } finally { server.close(); peer.join(Duration.ofSeconds(2)); }
        }
    }

}
