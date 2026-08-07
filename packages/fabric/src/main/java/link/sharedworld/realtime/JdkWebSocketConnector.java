package link.sharedworld.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Production transport for the realtime channel: the JDK's built-in
 * WebSocket client (Java 11+, identical across every version bucket, so
 * this stays in main source with zero versioned seams).
 */
public final class JdkWebSocketConnector implements SharedWorldPushChannel.TransportConnector {
    private static final long CONNECT_TIMEOUT_SECONDS = 15;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public SharedWorldPushChannel.Transport connect(
            URI uri,
            String bearerToken,
            SharedWorldPushChannel.TransportEvents events
    ) throws Exception {
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    events.onMessage(message);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                events.onDisconnect();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                events.onDisconnect();
            }
        };
        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .header("authorization", "Bearer " + bearerToken)
                .buildAsync(uri, listener)
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new SharedWorldPushChannel.Transport() {
            @Override
            public void sendText(String text) {
                // Failures surface through onError -> onDisconnect; sends are
                // fire-and-forget so the channel scheduler never blocks.
                webSocket.sendText(text, true);
            }

            @Override
            public void close() {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                } catch (RuntimeException ignored) {
                    // Socket already dying; abort below is the backstop.
                }
                webSocket.abort();
            }
        };
    }
}
