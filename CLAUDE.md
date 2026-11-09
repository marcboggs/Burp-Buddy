# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Burp Buddy is a Burp Suite extension that integrates LLMs (Ollama, AWS Bedrock) for security analysis, vulnerability detection, and HTTP traffic understanding.

## Key Development Commands

```bash
./gradlew jar      # Build the extension JAR (output: build/libs/burp-buddy-ext-<version>.jar)
./gradlew build    # Build and test
./gradlew clean    # Clean build artifacts
```

**Quick reload in Burp:** Ctrl/Cmd + click the Loaded checkbox next to Burp Buddy

## Architecture

### Central API Access Pattern
All Montoya API access goes through `base.Api`:
```java
import static base.Api.api;
// Usage: api.logging(), api.http(), api.userInterface()
```

### Main Components

- **Extension.java** - Entry point, registers all UI components and handlers
- **base/Api.java** - Static holder for MontoyaApi, extensionName, version
- **config/** - Settings persistence (SettingsManager), provider enum (LLMProvider)
- **llm/** - LLM client interface and implementations
  - `LLMClient` - Interface with streaming support
  - `impl/OllamaClient` - Local Ollama with streaming via HttpURLConnection
  - `impl/BedrockClient` - AWS Bedrock via Montoya HTTP API
  - `impl/ClaudeCodeClient` - Wraps local `claude` CLI (stdin prompt, JSON/stream-json output, built-in tools disabled; MCP tools ticked in Settings passed via `--allowedTools`)
  - `impl/CodexClient` - Wraps local `codex` CLI (stdin prompt, JSONL output, read-only sandbox)
  - `impl/KiroClient` - Drives local `kiro-cli acp` over the Agent Client Protocol (JSON-RPC 2.0 stdio, via `AcpTransport`); answers `session/request_permission` with "allow once" only for MCP tools ticked in Settings (matched on `_meta.mcpToolIdentity`, stored as `server/tool`) and refuses everything else, so built-in tools (shell, write, read) never run
  - `impl/AcpTransport` - Minimal JSON-RPC 2.0 stdio transport shared by `KiroClient`: newline-delimited messages, matches requests/responses by id, dispatches notifications and server-to-client requests
- **ui/** - All Swing UI components
  - `AIPalSuiteTab` - Main tab container with Chat/Tasks/Settings sub-tabs
  - `chat/` - Chat interface with session management
  - `contextmenu/` - Right-click menu provider
  - `editor/` - Request/Response editor tabs
  - `settings/` - LLM provider configuration panel
  - `tasks/` - Task tracking dashboard

### Key Patterns

- **Static imports** for Api access: `import static base.Api.api`
- **Switch expressions** for enums: `String role = switch (msg.getRole()) { case USER -> "user"; ... }`
- **SwingWorker** for background operations in settings panel
- **StreamCallback** interface for real-time streaming responses
- **ThreadManager** for async LLM operations

### UI Font Standard
All text areas use: `new Font(Font.MONOSPACED, Font.PLAIN, 13)`

## Documentation

- `docs/bapp-store-requirements.md` - BApp Store submission criteria
- `docs/montoya-api-examples.md` - API patterns and examples
- `docs/development-best-practices.md` - AI and threading guidelines
- `docs/resources.md` - External documentation links

## Build Configuration

- Java 21, Montoya API 2025.10, Gson 2.10.1
- JAR bundles all runtime dependencies (Gson)
