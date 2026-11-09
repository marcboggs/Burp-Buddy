package llm.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal JSON-RPC 2.0 over stdio transport for the Agent Client Protocol (ACP),
 * which is what {@code kiro-cli acp} speaks. One line = one JSON-RPC message,
 * newline-delimited, no embedded newlines (matches the ACP transport spec and
 * the LSP-style framing kiro-cli uses).
 * <p>
 * This is a client-and-server-in-one transport: kiro-cli is itself free to send
 * JSON-RPC *requests* back to us (permission requests, fs/terminal capability
 * calls) that must be answered, in addition to the responses to our own
 * requests and the notifications it streams. A background reader thread
 * dispatches:
 * <ul>
 *   <li>responses (matched by id) to whichever caller is blocked in {@link #request};</li>
 *   <li>notifications (no id) to the registered {@link NotificationHandler};</li>
 *   <li>server-to-client requests (has id, method not a response) to the registered
 *       {@link ServerRequestHandler}, whose return value is sent back as our response.</li>
 * </ul>
 */
final class AcpTransport implements AutoCloseable {

    @FunctionalInterface
    interface NotificationHandler {
        void onNotification(String method, JsonObject params);
    }

    @FunctionalInterface
    interface ServerRequestHandler {
        /**
         * Handle a request kiro-cli sends to us (e.g. session/request_permission).
         * Return the JSON result object to send back, or throw to send a JSON-RPC error.
         */
        JsonObject onServerRequest(String method, JsonObject params) throws Exception;
    }

    private static final Gson GSON = new Gson();

    private final Process process;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, BlockingQueue<JsonObject>> pending = new ConcurrentHashMap<>();
    private volatile NotificationHandler notificationHandler = (m, p) -> { };
    private volatile ServerRequestHandler serverRequestHandler = (m, p) -> {
        throw new UnsupportedOperationException("No handler registered for " + m);
    };
    private volatile Exception readerFailure;
    private volatile boolean closed = false;
    // Wake-up marker for requesters when stdout closes (process exited).
    private static final JsonObject EXITED = new JsonObject();
    private static final int STDERR_TAIL_CHARS = 2000;
    private final StringBuilder stderrTail = new StringBuilder();

    AcpTransport(ProcessBuilder processBuilder) throws IOException {
        this.process = processBuilder.start();
        this.stdin = process.getOutputStream();
        this.readerThread = new Thread(this::readLoop, "kiro-acp-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        // Drain stderr: an unread pipe can fill and hang the child, and it holds
        // the only explanation when kiro-cli exits early.
        Thread stderrThread = new Thread(this::drainStderr, "kiro-acp-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    void setNotificationHandler(NotificationHandler handler) {
        this.notificationHandler = handler;
    }

    void setServerRequestHandler(ServerRequestHandler handler) {
        this.serverRequestHandler = handler;
    }

    Process getProcess() {
        return process;
    }

    /**
     * Send a JSON-RPC request and block for its matching response.
     */
    JsonObject request(String method, JsonObject params, long timeoutSeconds) throws Exception {
        int id = nextId.getAndIncrement();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.addProperty("id", id);
        envelope.addProperty("method", method);
        if (params != null) {
            envelope.add("params", params);
        }

        BlockingQueue<JsonObject> replyQueue = new LinkedBlockingQueue<>(1);
        pending.put(id, replyQueue);
        try {
            try {
                writeLine(envelope);
            } catch (IOException e) {
                // e.g. Windows "The pipe is being closed": the process already exited.
                throw new IOException(exitDescription("while sending " + method, e.getMessage()), e);
            }
            JsonObject reply = replyQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
            if (reply == EXITED || (reply == null && !process.isAlive())) {
                throw new IOException(exitDescription("before answering " + method,
                        readerFailure != null ? readerFailure.getMessage() : null), readerFailure);
            }
            if (reply == null) {
                throw new IOException("Timed out waiting for response to " + method + stderrSuffix());
            }
            if (reply.has("error")) {
                JsonObject error = reply.getAsJsonObject("error");
                String message = error.has("message") ? error.get("message").getAsString() : "unknown error";
                int code = error.has("code") ? error.get("code").getAsInt() : 0;
                String data = error.has("data") && !error.get("data").isJsonNull()
                        ? " (" + error.get("data") + ")" : "";
                throw new IOException("ACP error " + code + " from " + method + ": " + message + data + stderrSuffix());
            }
            return reply.has("result") && reply.get("result").isJsonObject()
                    ? reply.getAsJsonObject("result")
                    : new JsonObject();
        } finally {
            pending.remove(id);
        }
    }

    void notify(String method, JsonObject params) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.addProperty("method", method);
        if (params != null) {
            envelope.add("params", params);
        }
        writeLine(envelope);
    }

    private void respondToServerRequest(JsonElement id, JsonObject result) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.add("id", id);
        envelope.add("result", result != null ? result : new JsonObject());
        writeLine(envelope);
    }

    private void respondErrorToServerRequest(JsonElement id, int code, String message) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        envelope.add("error", error);
        writeLine(envelope);
    }

    private synchronized void writeLine(JsonObject envelope) throws IOException {
        if (closed) {
            throw new IOException("ACP transport is closed");
        }
        String line = GSON.toJson(envelope);
        stdin.write(line.getBytes(StandardCharsets.UTF_8));
        stdin.write('\n');
        stdin.flush();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                dispatch(line);
            }
        } catch (Exception e) {
            readerFailure = e;
        } finally {
            // Wake up any requester still waiting; they report the exit.
            for (BlockingQueue<JsonObject> queue : pending.values()) {
                queue.offer(EXITED);
            }
        }
    }

    private void dispatch(String line) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            return; // Not a JSON object line - ignore (e.g. stray log output).
        }

        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        boolean hasMethod = msg.has("method") && !msg.get("method").isJsonNull();

        if (hasId && !hasMethod) {
            // Response to one of our requests.
            int id;
            try {
                id = msg.get("id").getAsInt();
            } catch (Exception e) {
                return;
            }
            BlockingQueue<JsonObject> queue = pending.get(id);
            if (queue != null) {
                queue.offer(msg);
            }
            return;
        }

        String method = hasMethod ? msg.get("method").getAsString() : null;
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params") : new JsonObject();

        if (hasId && hasMethod) {
            // kiro-cli sending us a request (e.g. permission request, fs/terminal call).
            JsonElement id = msg.get("id");
            try {
                JsonObject result = serverRequestHandler.onServerRequest(method, params);
                respondToServerRequest(id, result);
            } catch (Exception e) {
                try {
                    respondErrorToServerRequest(id, -32000, e.getMessage() == null ? "handler error" : e.getMessage());
                } catch (IOException ignored) {
                    // Pipe is gone; nothing more we can do.
                }
            }
            return;
        }

        if (!hasId && hasMethod) {
            // Notification (session/update, etc).
            try {
                notificationHandler.onNotification(method, params);
            } catch (Exception ignored) {
                // Never let a handler bug take down the reader thread.
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.append(line).append(' ');
                    if (stderrTail.length() > STDERR_TAIL_CHARS) {
                        stderrTail.delete(0, stderrTail.length() - STDERR_TAIL_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
            // Process gone
        }
    }

    /** Last lines kiro-cli wrote to stderr, as " - stderr: ..." or "" if none. */
    String stderrSuffix() {
        synchronized (stderrTail) {
            String tail = stderrTail.toString().trim();
            return tail.isEmpty() ? "" : " - stderr: " + tail;
        }
    }

    private String exitDescription(String when, String cause) {
        try {
            process.waitFor(2, TimeUnit.SECONDS); // let it finish exiting so the code and stderr are in
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String status = process.isAlive() ? "closed its pipes" : "exited with code " + process.exitValue();
        return "kiro-cli " + status + " " + when
                + (cause != null ? " (" + cause + ")" : "") + stderrSuffix();
    }

    boolean isAlive() {
        return process.isAlive() && !closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        readerThread.interrupt();
    }

    static JsonArray emptyArray() {
        return new JsonArray();
    }
}
