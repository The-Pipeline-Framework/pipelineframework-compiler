package org.pipelineframework.processor.census;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Fixed public destination. Construct and use only on the census daemon, never on a compiler thread. */
public final class HttpsCensusTransport implements CensusTransport {
    private final URI endpoint;
    public HttpsCensusTransport() { this(URI.create("https://pipelineframework.org/api/census/v1/observations")); }
    // Package-private controlled endpoint for tests; no endpoint override is exposed to builds.
    HttpsCensusTransport(URI endpoint) {
        this.endpoint = endpoint;
    }
    /** Uses a fresh client for this attempt and releases it even if the request fails. */
    public boolean send(String json) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(1))
                .header("Content-Type", "application/json").header("User-Agent", "TPF-Build-Census/1")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
                }).build();
        try {
            return send(client, request);
        } finally {
            client.shutdownNow();
        }
    }
    /** Bounds the entire response, including its body, and acknowledges only HTTP 204. */
    private boolean send(HttpClient client, HttpRequest request) throws Exception {
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return response.get(1, TimeUnit.SECONDS).statusCode() == 204;
        } catch (TimeoutException deadline) {
            throw new HttpTimeoutException("Census request deadline exceeded");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw new java.io.IOException("Census request failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            response.cancel(true);
        }
    }
}
