package llm.impl;

import static base.Api.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import llm.LLMClient;
import llm.LLMResponse;
import ui.chat.ChatMessage;
import util.CliEnvironmentUtil;
import util.Utf16Sanitizer;

/**
 * LLM client that runs prompts through a local Kiro CLI (kiro-cli) installation
 * via the Agent Client Protocol (ACP) - the JSON-RPC 2.0 over stdio interface
 * {@code kiro-cli acp} speaks, and the same interface Kiro Crew itself drives
 * kiro-cli through.
 * <p>
 * This is NOT the {@code kiro-cli chat --no-interactive} shell UX: ACP is a
 * stateful protocol with an {@code initialize} handshake, an explicit
 * {@code session/new}, a {@code session/prompt} call per turn, and streamed
 * {@code session/update} notifications carrying {@code agent_message_chunk}
 * text deltas. kiro-cli can also send requests back to the client - most
 * importantly {@code session/request_permission} before it writes a file or
 * runs a shell command. This client refuses every such request (see
 * {@link #handleServerRequest}) except "allow once" for MCP tools the user
 * ticked in Settings, for the same reason Claude Code is invoked with
 * {@code --tools ""}: prompts here are built from untrusted HTTP traffic
 * pulled out of Burp, so kiro-cli must never execute built-in tools (shell,
 * write...) on the strength of content a malicious server put in a body.
 */
public class KiroClient implements LLMClient {
    private static final long INITIALIZE_TIMEOUT_SECONDS = 30;
    private static final long SESSION_NEW_TIMEOUT_SECONDS = 30;
    private static final long PROMPT_TIMEOUT_SECONDS = 120;
    private static final int ACP_PROTOCOL_VERSION = 1;

    private static final long LIST_TOOLS_MAX_WAIT_MS = 20_000;
    private static final long LIST_TOOLS_POLL_MS = 2_000;
    // Allowed entries are MCP tool ids "server/tool"; built-in tools have no such id.
    private static final java.util.regex.Pattern MCP_TOOL_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final String kiroPath;
    private final String model;
    private final java.util.Set<String> allowedTools;

    public KiroClient(String kiroPath, String model) {
        this(kiroPath, model, "");
    }

    public KiroClient(String kiroPath, String model, String allowedTools) {
        this.kiroPath = kiroPath;
        this.model = model;
        this.allowedTools = parseAllowedTools(allowedTools);
    }

    /** Keeps only well-formed "server/tool" ids from a comma-separated list. */
    static java.util.Set<String> parseAllowedTools(String tools) {
        java.util.Set<String> kept = new java.util.HashSet<>();
        if (tools == null) return kept;
        for (String t : tools.split(",")) {
            String id = t.trim();
            if (MCP_TOOL_ID.matcher(id).matches()) kept.add(id);
        }
        return kept;
    }

    /**
     * Lists MCP tools as "server/tool" ids via Kiro's {@code /tools} command
     * ({@code _kiro.dev/commands/execute}); no prompt is sent, so no credits are
     * used. MCP servers connect in the background after session/new, so this
     * polls until the count stops changing.
     */
    public List<String> listMcpTools() throws Exception {
        try (AcpSession session = openSession()) {
            List<String> tools = List.of();
            long start = System.currentTimeMillis();
            while (true) {
                List<String> now = session.mcpToolIds();
                boolean settled = now.size() == tools.size()
                        && System.currentTimeMillis() - start >= 2 * LIST_TOOLS_POLL_MS;
                tools = now;
                if (settled || System.currentTimeMillis() - start >= LIST_TOOLS_MAX_WAIT_MS) {
                    return tools;
                }
                Thread.sleep(LIST_TOOLS_POLL_MS);
            }
        }
    }

    @Override
    public LLMResponse complete(String prompt, String systemPrompt) {
        String fullPrompt = buildSinglePrompt(prompt, systemPrompt);
        return runTurn(fullPrompt, null);
    }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, String newMessage) {
        String prompt = buildConversationPrompt(messages, newMessage);
        return runTurn(prompt, null);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public void chatStreaming(List<ChatMessage> messages, String newMessage, StreamCallback callback) {
        String prompt = buildConversationPrompt(messages, newMessage);
        LLMResponse response = runTurn(prompt, callback::onChunk);
        if (callback.isCancelled()) {
            return;
        }
        if (response.isSuccess()) {
            callback.onComplete(response.getTokensUsed());
        } else {
            callback.onError(response.getErrorMessage());
        }
    }

    @Override
    public boolean testConnection() {
        try (AcpSession session = openSession()) {
            return session != null;
        } catch (Exception e) {
            api.logging().logToError("Kiro CLI ACP connection test failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Kiro CLI";
    }

    @Override
    public String getModel() {
        return model;
    }

    /**
     * Returns `kiro-cli --version` (plain argv, no ACP) for a quick identity check
     * in Settings. A real kiro-cli prints a single-line version string; the Kiro
     * IDE's bundled launcher - a different binary that also happens to be named
     * "kiro"/"kiro.cmd" - prints a three-line VS Code-style block. That shape is
     * rejected here so a misconfigured path is reported as "not connected"
     * instead of a false positive.
     */
    public String getVersion() {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(kiroPath);
            command.add("--version");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.home")));
            CliEnvironmentUtil.ensureNodePath(pb);

            Process process = pb.start();
            String output = readStream(process.getInputStream()).trim();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            if (looksLikeIdeLauncherOutput(output)) {
                return null;
            }
            return output;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksLikeIdeLauncherOutput(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String[] lines = output.split("\\r?\\n");
        return lines.length > 1;
    }

    private String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // ACP session lifecycle
    // ------------------------------------------------------------------

    private LLMResponse runTurn(String prompt, java.util.function.Consumer<String> onChunk) {
        try (AcpSession session = openSession()) {
            if (!allowedTools.isEmpty()) {
                // MCP servers connect after session/new; without this wait the
                // first prompt sees none of the ticked tools.
                session.waitForTools(allowedTools);
            }
            return session.prompt(prompt, onChunk);
        } catch (Exception e) {
            api.logging().logToError("Kiro CLI ACP error: " + e.getMessage());
            return LLMResponse.error("Kiro CLI error: " + e.getMessage());
        }
    }

    private AcpSession openSession() throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(kiroPath);
        command.add("acp");
        // Always pass --model, even "auto" (kiro-cli's own default). A bare
        // `kiro-cli acp` launched with piped stdio from the JVM exits at once on
        // Windows with "Access is denied. (os error 5)" (kiro-cli 3.1.0); with
        // any option it starts fine.
        command.add("--model");
        command.add(model != null && !model.isBlank() ? model : "auto");
        // CRITICAL: do not pass --trust-all-tools / --trust-tools. We also reject
        // every session/request_permission call the agent sends us (see
        // AcpSession#handleServerRequest), so even if a future kiro-cli defaults
        // a tool to "no permission needed", a write/execute call still has no
        // path to actually run via this client. Prompts here are built from
        // untrusted HTTP traffic, so kiro-cli must never execute anything on the
        // strength of it.

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.directory(new java.io.File(System.getProperty("user.home")));
        CliEnvironmentUtil.ensureNodePath(pb);

        AcpTransport transport;
        try {
            transport = new AcpTransport(pb);
        } catch (java.io.IOException e) {
            throw new Exception("Failed to start kiro-cli acp: " + e.getMessage(), e);
        }

        AcpSession session = new AcpSession(transport);
        try {
            session.initializeAndCreateSession();
        } catch (Exception e) {
            session.close();
            throw e;
        }
        return session;
    }

    /**
     * One ACP session: a kiro-cli acp subprocess plus the sessionId created on it.
     * Closing it tears down the subprocess. Not reused across calls - each
     * complete()/chat() call gets a fresh subprocess and session, matching how
     * the Claude Code / Codex clients spawn a fresh process per call.
     */
    private final class AcpSession implements AutoCloseable {
        private static final long COMMAND_TIMEOUT_SECONDS = 30;
        private final AcpTransport transport;
        private volatile String sessionId;

        AcpSession(AcpTransport transport) {
            this.transport = transport;
            transport.setServerRequestHandler(this::handleServerRequest);
        }

        void initializeAndCreateSession() throws Exception {
            JsonObject initParams = new JsonObject();
            initParams.addProperty("protocolVersion", ACP_PROTOCOL_VERSION);
            JsonObject clientCapabilities = new JsonObject();
            // Deliberately advertise no fs/terminal capability so kiro-cli relies on
            // its own sandboxed tools (which we then refuse permission for) rather
            // than asking this client to read/write files or run commands on its behalf.
            initParams.add("clientCapabilities", clientCapabilities);
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "burp-buddy-ext");
            clientInfo.addProperty("version", "1.0.0");
            initParams.add("clientInfo", clientInfo);

            transport.request("initialize", initParams, INITIALIZE_TIMEOUT_SECONDS);

            JsonObject sessionParams = new JsonObject();
            sessionParams.addProperty("cwd", System.getProperty("user.home"));
            sessionParams.add("mcpServers", new JsonArray());
            JsonObject sessionResult = transport.request("session/new", sessionParams, SESSION_NEW_TIMEOUT_SECONDS);
            if (!sessionResult.has("sessionId")) {
                throw new java.io.IOException("kiro-cli acp session/new returned no sessionId");
            }
            this.sessionId = sessionResult.get("sessionId").getAsString();
        }

        /** Polls until every wanted tool is listed, or the max wait passes (then prompts anyway). */
        void waitForTools(java.util.Set<String> wanted) throws Exception {
            long start = System.currentTimeMillis();
            while (!mcpToolIds().containsAll(wanted)
                    && System.currentTimeMillis() - start < LIST_TOOLS_MAX_WAIT_MS) {
                Thread.sleep(LIST_TOOLS_POLL_MS / 4);
            }
        }

        /** "server/tool" ids of the MCP tools Kiro reports right now. */
        List<String> mcpToolIds() throws Exception {
            JsonObject command = new JsonObject();
            command.addProperty("command", "tools");
            command.add("args", new JsonObject());
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            params.add("command", command);
            JsonObject result = transport.request("_kiro.dev/commands/execute", params, COMMAND_TIMEOUT_SECONDS);

            List<String> ids = new java.util.ArrayList<>();
            if (result.has("data") && result.getAsJsonObject("data").has("tools")) {
                for (com.google.gson.JsonElement e : result.getAsJsonObject("data").getAsJsonArray("tools")) {
                    JsonObject tool = e.getAsJsonObject();
                    String source = tool.has("source") ? tool.get("source").getAsString() : "";
                    if (source.startsWith("mcp:") && tool.has("name")) {
                        String id = source.substring(4) + "/" + tool.get("name").getAsString();
                        if (MCP_TOOL_ID.matcher(id).matches()) ids.add(id);
                    }
                }
            }
            return ids;
        }

        LLMResponse prompt(String promptText, java.util.function.Consumer<String> onChunk) throws Exception {
            StringBuilder collected = new StringBuilder();
            AtomicReference<String> stopReason = new AtomicReference<>();

            transport.setNotificationHandler((method, params) -> {
                if (!"session/update".equals(method)) {
                    return;
                }
                if (!params.has("update") || !params.get("update").isJsonObject()) {
                    return;
                }
                JsonObject update = params.getAsJsonObject("update");
                String sessionUpdate = update.has("sessionUpdate") ? update.get("sessionUpdate").getAsString() : "";
                if ("agent_message_chunk".equals(sessionUpdate) && update.has("content")) {
                    JsonObject content = update.getAsJsonObject("content");
                    if (content.has("text")) {
                        String text = content.get("text").getAsString();
                        if (!text.isEmpty()) {
                            collected.append(text);
                            if (onChunk != null) {
                                onChunk.accept(text);
                            }
                        }
                    }
                }
            });

            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            JsonArray promptArray = new JsonArray();
            JsonObject promptBlock = new JsonObject();
            promptBlock.addProperty("type", "text");
            promptBlock.addProperty("text", Utf16Sanitizer.sanitize(promptText));
            promptArray.add(promptBlock);
            params.add("prompt", promptArray);

            JsonObject result;
            try {
                result = transport.request("session/prompt", params, PROMPT_TIMEOUT_SECONDS);
            } catch (Exception e) {
                if (collected.length() > 0) {
                    // Partial output was streamed before the failure (e.g. timeout);
                    // surface what we got rather than discarding it.
                    return LLMResponse.success(collected.toString());
                }
                throw e;
            }

            if (result.has("stopReason")) {
                stopReason.set(result.get("stopReason").getAsString());
            }

            String reason = stopReason.get();
            if (reason != null && !"end_turn".equals(reason)) {
                if (collected.length() > 0) {
                    return LLMResponse.success(collected.toString());
                }
                return LLMResponse.error("Kiro CLI turn ended with stopReason=" + reason);
            }

            if (collected.length() == 0) {
                return LLMResponse.error("No response received from Kiro CLI");
            }
            return LLMResponse.success(collected.toString());
        }

        /**
         * Answer every request kiro-cli sends us. The only ones observed in
         * practice are session/request_permission (v2) and its v3 equivalent
         * before a write/execute tool call, plus occasional fs/terminal capability
         * calls if we had advertised support for them (we do not). Permission
         * requests for ticked MCP tools get "allow once"; all others are
         * cancelled, and anything else
         * gets a JSON-RPC error so kiro-cli falls back to its own built-in tool
         * (which still needs a permission grant we will also reject) rather than
         * blocking forever on an answer we'd otherwise have to fabricate.
         */
        private JsonObject handleServerRequest(String method, JsonObject params) throws Exception {
            if ("session/request_permission".equals(method)) {
                String allowOption = allowOnceOptionFor(params);
                api.logging().logToOutput("Kiro CLI tool permission " + (allowOption != null ? "ALLOWED" : "refused")
                        + ": " + permissionTitle(params));
                if (allowOption != null) {
                    JsonObject outcome = new JsonObject();
                    outcome.addProperty("outcome", "selected");
                    outcome.addProperty("optionId", allowOption);
                    JsonObject result = new JsonObject();
                    result.add("outcome", outcome);
                    return result;
                }
                // Prefer the "reject once" option: it refuses this call and lets the
                // model carry on. "cancelled" (fallback) ends the whole turn.
                String rejectOption = optionOfKind(params, "reject_once");
                JsonObject outcome = new JsonObject();
                if (rejectOption != null) {
                    outcome.addProperty("outcome", "selected");
                    outcome.addProperty("optionId", rejectOption);
                } else {
                    outcome.addProperty("outcome", "cancelled");
                }
                JsonObject result = new JsonObject();
                result.add("outcome", outcome);
                return result;
            }
            // Any other server-to-client request (fs/*, terminal/*, auth/*, etc.)
            // is refused - we advertised no matching clientCapabilities, so kiro-cli
            // should not be sending these, but refuse defensively if it does.
            throw new UnsupportedOperationException(
                    "burp-buddy-ext does not support client request: " + method);
        }

        /**
         * The "allow once" option id if this permission request is for an MCP
         * tool the user ticked in Settings, else null. Kiro only sets
         * _meta.mcpToolIdentity for MCP tools, so built-ins (shell, write,
         * read...) can never match. Never "allow always": that would persist
         * trust in Kiro's own settings beyond this extension.
         */
        private String permissionTitle(JsonObject params) {
            if (params.has("toolCall") && params.get("toolCall").isJsonObject()
                    && params.getAsJsonObject("toolCall").has("title")) {
                return params.getAsJsonObject("toolCall").get("title").getAsString();
            }
            return "(unknown tool)";
        }

        private String allowOnceOptionFor(JsonObject params) {
            if (allowedTools.isEmpty() || !params.has("_meta") || !params.get("_meta").isJsonObject()) return null;
            JsonObject meta = params.getAsJsonObject("_meta");
            if (!meta.has("mcpToolIdentity") || !meta.get("mcpToolIdentity").isJsonObject()) return null;
            JsonObject identity = meta.getAsJsonObject("mcpToolIdentity");
            if (!identity.has("serverName") || !identity.has("toolName")) return null;
            String id = identity.get("serverName").getAsString() + "/" + identity.get("toolName").getAsString();
            return allowedTools.contains(id) ? optionOfKind(params, "allow_once") : null;
        }

        private String optionOfKind(JsonObject params, String kind) {
            if (!params.has("options") || !params.get("options").isJsonArray()) return null;
            for (com.google.gson.JsonElement e : params.getAsJsonArray("options")) {
                JsonObject option = e.getAsJsonObject();
                if (option.has("kind") && kind.equals(option.get("kind").getAsString()) && option.has("optionId")) {
                    return option.get("optionId").getAsString();
                }
            }
            return null;
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    private String buildSinglePrompt(String prompt, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            return systemPrompt + "\n\n" + prompt;
        }
        return prompt;
    }

    private String buildConversationPrompt(List<ChatMessage> messages, String newMessage) {
        StringBuilder sb = new StringBuilder();

        for (ChatMessage msg : messages) {
            String role = switch (msg.getRole()) {
                case SYSTEM -> "System";
                case USER -> "User";
                case ASSISTANT -> "Assistant";
            };
            sb.append(role).append(": ").append(msg.getFullContent()).append("\n\n");
        }

        if (newMessage != null && !newMessage.isEmpty()) {
            sb.append("User: ").append(newMessage);
        }

        return sb.toString();
    }
}
