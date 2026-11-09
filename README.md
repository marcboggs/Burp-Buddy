# Burp Buddy - Burp Suite Extension

Burp Buddy is a Burp Suite extension that integrates Large Language Models (LLMs) to assist security researchers with vulnerability analysis, attack vector generation, and HTTP traffic understanding.

**New to Burp Buddy?** The [User Guide](docs/user-guide.md) walks through every feature, when to use each one, example workflows, and exactly what is sent to the model.

## Features

### Multi-Provider LLM Support

Burp Buddy supports five LLM providers:

- **Ollama** - Run models locally with complete privacy (Llama, Mistral, DeepSeek, and more)
- **AWS Bedrock** - Enterprise-grade access to Claude models via AWS infrastructure
- **Claude Code CLI** - Run prompts through your local `claude` CLI
- **OpenAI Codex CLI** - Run prompts through your local `codex` CLI
- **Kiro CLI** - Run prompts through your local `kiro-cli` CLI

Switch between providers in the Settings tab with automatic credential or binary-path detection.

### MCP Tool Selection (Claude Code CLI and Kiro CLI)

The CLI agents can call tools from the MCP servers you have configured for them, such as PortSwigger's Burp MCP server or Playwright. Burp Buddy lets you choose exactly which ones:

- **Load Tools** lists the MCP tools each CLI currently has connected
- Tick individual tools, or use **Select All** / **Select None**; each change is saved immediately
- Nothing is allowed until you tick it, and the agents' **built-in tools (shell, file read/write, web fetch) are always off**
- Every Kiro CLI allow/refuse decision is written to Burp's extension output as an audit trail

See [Allowing MCP tools](#allowing-mcp-tools) for how each CLI enforces this.

### Context Menu Actions

Right-click on any request/response in Burp to access AI-powered analysis:

| Action | Description |
|--------|-------------|
| **Analyze for Vulnerabilities** | Comprehensive security analysis covering OWASP Top 10, including SQLi, XSS, CSRF, SSRF, XXE, IDOR, and more |
| **Explain Request/Response** | Plain-English breakdown of what the HTTP traffic does |
| **Generate Attack Vectors** | Produces specific test payloads with bypass techniques |
| **Custom Prompt** | Send your own prompt with the HTTP content as context |
| **Chat** | Opens the request in the Chat tab for interactive analysis |

### Interactive Chat

A built-in chat interface for conversational security analysis:

- Multi-turn conversations with conversation history
- Streaming responses for real-time output (Ollama, Claude Code CLI, OpenAI Codex CLI, and Kiro CLI)
- Each message shows as a card with the sender, provider/model, and time; errors are marked in red
- Replies render Markdown: headings, bold/italic, lists, inline code, and code blocks (long lines such as HTTP requests scroll sideways instead of being cut off)
- Colours follow Burp's light or dark theme
- Send HTTP requests/responses directly to chat from context menu
- Ask follow-up questions about vulnerabilities
- Get help with payload crafting and testing strategies

### Editor Integration

Burp Buddy adds an "Burp Buddy" tab to the HTTP request and response editors in Repeater and other tools:

- Write custom prompts with attached request/response context
- Toggle which context to include (Request, Response, Notes)
- Track analysis tasks in the Tasks tab
- Results appear without leaving your workflow

### Tasks Dashboard

Track analyses started from the Burp Buddy editor tab:

- View status of running analyses (pending, running, completed, failed)
- Review past analysis results with full context
- See the prompt, result, and original request/response for each task
- Right-click actions show their results in a popup and Chat keeps its own history, so neither appears here
- Tasks last until Burp closes or the extension reloads

## Prerequisites

Before using Burp Buddy, you need to set up at least one LLM provider:

### Option 1: Ollama (Recommended for Privacy)

Ollama runs models locally on your machine - no data leaves your computer.

1. **Install Ollama** from [ollama.ai](https://ollama.ai/download)
2. **Pull a model**:
   ```bash
   ollama pull llama3.2
   ```
3. **Start the Ollama server** (usually starts automatically):
   ```bash
   ollama serve
   ```
4. **Verify it's running**:
   ```bash
   curl http://localhost:11434/api/tags
   ```

**Recommended models for security analysis:**
| Model | Size | Best For |
|-------|------|----------|
| `llama3.2` | 3B | Fast general analysis |
| `llama3.1:8b` | 8B | Better reasoning |
| `mistral` | 7B | Good balance of speed/quality |
| `deepseek-r1` | 7B+ | Strong reasoning with thinking output |
| `codellama` | 7B | Code-focused analysis |

### Option 2: AWS Bedrock (Enterprise)

AWS Bedrock provides access to Claude models with enterprise security and compliance.

**Requirements:**
- AWS account with Bedrock access enabled in your region
- IAM permissions for `bedrock:InvokeModel`
- Model access granted in the AWS Bedrock console

**Configure credentials using one of these methods (in priority order):**

1. **Environment variables**:
   ```bash
   export AWS_ACCESS_KEY_ID=your_access_key
   export AWS_SECRET_ACCESS_KEY=your_secret_key
   # Optional for temporary credentials:
   export AWS_SESSION_TOKEN=your_session_token
   ```

2. **AWS credentials file** (`~/.aws/credentials`):
   ```ini
   [default]
   aws_access_key_id = your_access_key
   aws_secret_access_key = your_secret_key

   # Or use a named profile:
   [my-profile]
   aws_access_key_id = your_access_key
   aws_secret_access_key = your_secret_key
   ```
   Set `AWS_DEFAULT_PROFILE=my-profile` to use a named profile.

3. **Manual entry** in Burp Buddy settings (credentials stored in Burp preferences)

**Available models (Global Inference Profiles):**
| Model | Best For |
|-------|----------|
| `claude-sonnet-4-5` | Complex security analysis |
| `claude-sonnet-4` | Balanced performance |
| `claude-haiku-4-5` | Fast, cost-effective |
| `claude-opus-4-5` | Most capable |

**Supported regions:** us-east-1, us-west-2, eu-west-1, eu-central-1, ap-southeast-1, ap-northeast-1

### Option 3: Claude Code CLI

Burp Buddy uses a local CLI integration for Claude Code. Users are responsible for complying with Anthropic's applicable terms and policies for their account and usage.

1. Install Claude Code CLI (recommended):
   ```bash
   curl -fsSL https://claude.ai/install.sh | bash
   ```
   npm fallback (deprecated by Claude docs):
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```
2. In Burp Buddy Settings, select **Use Claude Code**
3. Click **Detect** (or set the binary path manually)
4. Click **Test Connection** to run `claude --version` and verify the binary executes
5. Optional: click **Load Tools** under **Allowed MCP tools** and tick the MCP tools Claude may use (see [Allowing MCP tools](#allowing-mcp-tools))

### Option 4: OpenAI Codex CLI

Burp Buddy uses a local CLI integration for Codex. Users are responsible for complying with OpenAI's applicable terms and policies for their account and usage.

1. Install OpenAI Codex CLI:
   ```bash
   npm install -g @openai/codex
   ```
2. In Burp Buddy Settings, select **Use OpenAI Codex**
3. Click **Detect** (or set the binary path manually)
4. Click **Test Connection** to run `codex --version` and verify the binary executes

### Option 5: Kiro CLI

Burp Buddy uses a local CLI integration for Kiro CLI (`kiro-cli`). Users are responsible for complying with the applicable terms and policies for their account and usage. See [kiro.dev/docs/cli](https://kiro.dev/docs/cli/) for installation and authentication (interactive login, or headless auth via the `KIRO_API_KEY` environment variable).

1. Install Kiro CLI following the instructions at [kiro.dev/docs/cli](https://kiro.dev/docs/cli/)
2. Authenticate once (interactive login, or set `KIRO_API_KEY` for headless use)
3. In Burp Buddy Settings, select **Use Kiro CLI**
4. Click **Detect** (or set the binary path manually)
5. Pick a model: `auto` (Kiro's default), `claude-opus-5.5`, `claude-sonnet-5.5`, `claude-haiku-5.5`, `claude-sonnet-4.6`, or `claude-haiku-4.5`. Kiro model IDs use dots; run `kiro-cli chat --list-models` to see what your account offers
6. Click **Test Connection** to run `kiro-cli --version` and verify the binary executes
7. Optional: click **Load Tools** under **Allowed MCP tools** and tick the MCP tools Kiro may use (see [Allowing MCP tools](#allowing-mcp-tools))

Burp Buddy drives Kiro CLI via the **Agent Client Protocol (ACP)** - the same JSON-RPC 2.0 over stdio interface Kiro Crew itself uses to run `kiro-cli`, rather than the `chat --no-interactive` shell UX. For each request it spawns `kiro-cli acp --model <model>`, performs the `initialize` / `session/new` handshake, sends the prompt via `session/prompt`, and streams the reply back from `session/update` notifications. It advertises no file-system or terminal client capability. When kiro-cli sends a `session/request_permission` call, Burp Buddy answers **allow once** only for MCP tools you ticked, and refuses everything else, including every built-in tool (shell, write, read). If kiro-cli exits or returns an error, the message shown includes its exit code and the last lines it wrote to stderr.

### Allowing MCP tools

Prompts are built from HTTP traffic, which an attacker can control. Anything a tool can do, a malicious response could try to make the agent do, so Burp Buddy keeps built-in tools off and only allows MCP tools you tick.

| | Claude Code CLI | Kiro CLI |
|---|---|---|
| Where tools come from | MCP servers registered with `claude mcp add` (any scope) | MCP servers in Kiro's agent config, e.g. `~/.kiro/settings/mcp.json` |
| How Load Tools works | Starts `claude` and reads the tool list from its startup event, then stops it before the model answers | Runs Kiro's `/tools` command over ACP; no prompt is sent and no credits are used |
| Saved as | Tool names, e.g. `mcp__burp__url_encode` | `server/tool`, e.g. `burp/url_encode` |
| Enforcement | `--tools ""` removes built-in tools; ticked tools are passed to `--allowedTools`. Other MCP tools are denied because `-p` mode has no one to approve them | Each permission request is matched on its MCP tool identity. Built-in tools have none, so they can never match |
| Extra startup time | None | When any tool is ticked, waits up to 20 s for those tools' MCP servers to connect before prompting |

Both CLIs re-check the saved list when it is used, so an edited preference cannot switch on a built-in tool.

**Using Burp's own MCP server ([PortSwigger mcp-server](https://github.com/PortSwigger/mcp-server))**: it serves SSE at `http://127.0.0.1:9876`. Claude Code and Kiro should both start it through the bundled stdio proxy jar. Kiro only supports streamable-HTTP MCP servers, so a plain `"url": "http://127.0.0.1:9876"` entry is silently skipped. Example Kiro entry:

```json
"burp": {
  "command": "<path to java>",
  "args": ["-jar", "<path to>/mcp-proxy-all.jar", "--sse-url", "http://127.0.0.1:9876"]
}
```

Burp must be running with the MCP extension enabled for these tools to connect.

## Installation

### Building from Source

```bash
# Clone the repository
git clone https://github.com/mykalseceng/burpsuite-ai-pal-ext.git
cd burpsuite-ai-pal-ext

# Build the extension
./gradlew jar

# The JAR file is created in build/libs/ (named <project>-<version>.jar)
```

### Loading into Burp Suite

1. In Burp, go to **Extensions > Installed**
2. Click **Add**
3. Under **Extension details**, click **Select file**
4. Select the JAR from `build/libs/`
5. Click **Next**

## Configuration

1. After loading the extension, open the **Burp Buddy** tab in Burp
2. Navigate to the **Settings** sub-tab
3. Select your preferred LLM provider (Ollama, Claude Code CLI, AWS Bedrock, OpenAI Codex CLI, or Kiro CLI)
4. The settings panel shows your credential status:
   - For Ollama: Connection status and loaded model
   - For Bedrock: Which credential source is being used
   - For Claude/Codex/Kiro CLI: Detected local CLI path
5. Choose a model and region (for Bedrock)
6. Click **Test Connection** to verify your setup
   - For Claude/Codex/Kiro CLI this executes `<binary> --version` and requires a runnable executable
7. For Claude Code or Kiro CLI, optionally click **Load Tools** and tick the MCP tools the agent may use

## Usage

### Quick Analysis (Context Menu)

1. Capture traffic in Proxy or navigate to any request in Burp
2. Right-click on the request/response
3. Select **Burp Buddy > Analyze for Vulnerabilities** (or other options)
4. Review the analysis in the popup dialog

### Interactive Chat

1. Go to the **Burp Buddy > Chat** tab
2. Type your question and press Enter (or click Send)
3. For context, right-click a request and select **Burp Buddy > Chat**
4. Example questions:
   - "What parameters are most likely vulnerable?"
   - "Generate SQLi payloads for the id parameter"
   - "Explain the authentication flow in this request"

### Editor Integration (Burp Buddy Tab)

1. Open a request in Repeater or click on any request
2. In the request/response viewer, select the **Burp Buddy** tab
3. Write your prompt in the text area
4. Toggle context: **Request** | **Response** | **Notes**
5. Click **Analyze**
6. View results in the **Tasks** tab

### Tasks Tab

- Lists each analysis started from the editor tab, newest first, with status icons:
  - ⏳ Pending
  - ▶ Running
  - ✓ Completed
  - ! Failed
- Click a task to see full details: prompt, result, and original context

## Project Structure

```
src/main/java/
├── Extension.java           # Main entry point
├── config/                  # Settings and provider configuration
│   ├── LLMProvider.java
│   ├── LLMSettings.java
│   └── SettingsManager.java
├── llm/                     # LLM client implementations
│   ├── LLMClient.java
│   ├── LLMClientFactory.java
│   ├── LLMResponse.java
│   ├── impl/
│   │   ├── OllamaClient.java
│   │   ├── BedrockClient.java
│   │   ├── ClaudeCodeClient.java
│   │   ├── CodexClient.java
│   │   ├── KiroClient.java
│   │   └── AcpTransport.java
│   └── prompts/
│       └── PromptTemplates.java
├── ui/                      # User interface components
│   ├── AIPalSuiteTab.java
│   ├── chat/
│   ├── contextmenu/
│   ├── dialogs/
│   ├── editor/
│   ├── settings/
│   └── tasks/
└── util/                    # Utilities
    ├── AwsCredentialsUtil.java
    ├── CliEnvironmentUtil.java
    ├── HttpRequestFormatter.java
    ├── ThreadManager.java
    └── Utf16Sanitizer.java
```

## Development

### Build Commands

```bash
./gradlew build    # Build and run tests
./gradlew jar      # Create the extension JAR
./gradlew clean    # Clean build artifacts
```

### Quick Reload During Development

1. Make code changes
2. Run `./gradlew jar`
3. In Burp: Hold **Ctrl/Cmd** and click the **Loaded** checkbox next to Burp Buddy

## Security Considerations

- **Ollama**: All data stays local - no external API calls
- **AWS Bedrock**: Data is sent to AWS; follows your organization's AWS security policies
- AWS credentials from environment variables or credentials file are preferred over manual entry
- Manual credentials entered in settings are stored in Burp preferences (not encrypted)
- Bedrock requests go through Burp's HTTP stack, respecting proxy settings
- HTTP content sent to LLMs may contain sensitive data - review before sending
- **CLI agents and tools**: prompts contain HTTP traffic an attacker may control. Built-in agent tools are always disabled; only MCP tools you tick can run, and a malicious response could try to trigger any of them. Be careful with tools that send traffic or change state, such as Burp's `send_http1_request`, `send_http2_request`, and `set_*_options`, or Playwright's `browser_run_code_unsafe` and `browser_evaluate`
- **Chat rendering**: model output is fully HTML-escaped before Markdown formatting is applied, so markup in a reply (for example `<img>` or `<object>` tags) is shown as text and never loaded

## Requirements

- Burp Suite 2024.1+ (uses Montoya API 2025.10)
- Java 21
- **For Ollama**: Local Ollama installation with at least one model pulled
- **For AWS Bedrock**: AWS account with Bedrock model access enabled

## Troubleshooting

### Ollama not connecting
- Ensure Ollama is running: `ollama serve`
- Check the base URL (default: `http://localhost:11434`)
- Verify a model is pulled: `ollama list`

### Kiro CLI not connecting / Send opens the Kiro IDE
- The standalone `kiro-cli` agent binary ([kiro.dev/docs/cli](https://kiro.dev/docs/cli/)) is a **different binary** from the Kiro IDE's own bundled launcher, which is also named `kiro`/`kiro.cmd` and lives under the IDE's install folder (e.g. `%LOCALAPPDATA%\Programs\Kiro\bin\kiro.cmd` on Windows). That launcher is a VS Code-style CLI: it treats unrecognized arguments as file/folder paths and opens the desktop IDE window instead of returning a response. If pressing Send in Chat opens the IDE, the configured path points at the IDE launcher, not `kiro-cli`.
- Install `kiro-cli` separately and point Burp Buddy's Kiro CLI path at that binary. `--version` output distinguishes them: `kiro-cli` prints a single line; the IDE launcher prints three lines (semver, commit hash, architecture). Burp Buddy's Detect button and Test Connection both check for this and will refuse to use an IDE-launcher path.
- Verify authentication and the ACP subcommand work from a terminal: `kiro-cli --version` then `kiro-cli acp --help` (or sign in first with `kiro-cli login` if prompted)
- For headless/unattended use, set the `KIRO_API_KEY` environment variable before launching Burp
- If a request fails with a protocol/session error, check Burp's extension error log (Extensions > Errors) for the underlying ACP error - it includes the JSON-RPC error code, kiro-cli's exit code, and the last lines it wrote to stderr
- **"The model '...' is not available"**: the selected model isn't offered on your Kiro account. Pick another (`kiro-cli chat --list-models`). Older Burp Buddy builds offered dashed IDs such as `claude-sonnet-4-6`; these reset to `auto` on load

### MCP tools missing from Load Tools
- **Claude Code**: run `claude mcp list` and check the server shows as connected
- **Kiro CLI**: run `kiro-cli mcp list`. A server configured with a `url` must support streamable HTTP; SSE-only servers (including Burp's MCP server) need a stdio proxy entry, see [Allowing MCP tools](#allowing-mcp-tools)
- Burp's MCP tools only appear while Burp is running with the MCP extension enabled
- Slow servers (e.g. Playwright via `npx`) can take 10-20 seconds to connect; click **Load Tools** again if the list looks short

### AWS Bedrock signature errors
- Verify your credentials are valid: `aws sts get-caller-identity`
- Ensure you have Bedrock model access in the AWS console
- Check the region matches where you have access

### Extension not loading
- Ensure Java 21+ is installed
- Check Burp's Extensions > Errors tab for details

## License

MIT License

## Acknowledgments

- Built using the [Burp Montoya API](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/MontoyaApi.html)
