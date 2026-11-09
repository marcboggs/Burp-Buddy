package config;

import util.AwsCredentialsUtil;

public class LLMSettings {
    private LLMProvider activeProvider;

    // Ollama settings
    private String ollamaBaseUrl;
    private String ollamaModel;

    // Claude Code settings
    private String claudeCodePath;
    private String claudeCodeModel;
    private String claudeCodeAllowedTools; // comma-separated mcp__* tool names

    // Bedrock settings
    private String bedrockAccessKey;
    private String bedrockSecretKey;
    private String bedrockSessionToken;
    private String bedrockRegion;
    private String bedrockModel;

    // Claude Code models
    public static final String[] CLAUDE_CODE_MODELS = {
        "claude-sonnet-4-6",
        "claude-opus-4-6",
        "claude-haiku-4-5-20251001"
    };

    // Codex settings
    private String codexPath;
    private String codexModel;

    // Kiro CLI settings
    private String kiroPath;
    private String kiroModel;
    private String kiroAllowedTools; // comma-separated server/tool MCP ids

    // AWS Bedrock models - Anthropic Claude global inference profiles
    public static final String[] BEDROCK_MODELS = {
        "global.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "global.anthropic.claude-sonnet-4-20250514-v1:0",
        "global.anthropic.claude-haiku-4-5-20251001-v1:0",
        "global.anthropic.claude-opus-4-5-20251101-v1:0"
    };

    // OpenAI Codex CLI models
    public static final String[] CODEX_MODELS = {
        "gpt-5.3-codex",
        "gpt-5.2-codex",
        "gpt-5.2",
        "gpt-5.1-codex-max",
        "gpt-5-codex-mini"
    };

    // Kiro CLI models - Kiro's ids use dots (`kiro-cli chat --list-models`)
    public static final String[] KIRO_MODELS = {
        "auto",
        "claude-opus-5.5",
        "claude-sonnet-5.5",
        "claude-haiku-5.5",
        "claude-sonnet-4.6",
        "claude-haiku-4.5"
    };

    // AWS Bedrock regions that support Bedrock
    public static final String[] BEDROCK_REGIONS = {
        "us-east-1",
        "us-west-2",
        "eu-west-1",
        "eu-central-1",
        "ap-southeast-1",
        "ap-northeast-1"
    };

    public LLMSettings() {
        this.activeProvider = LLMProvider.OLLAMA;
        this.ollamaBaseUrl = "http://localhost:11434";
        this.ollamaModel = "llama3.2";
        this.claudeCodePath = "";
        this.claudeCodeModel = "claude-sonnet-4-6";
        this.claudeCodeAllowedTools = "";
        this.bedrockAccessKey = "";
        this.bedrockSecretKey = "";
        this.bedrockSessionToken = "";
        this.bedrockRegion = "us-east-1";
        this.bedrockModel = "global.anthropic.claude-sonnet-4-5-20250929-v1:0";
        this.codexPath = "";
        this.codexModel = "gpt-5.3-codex";
        this.kiroPath = "";
        this.kiroModel = "auto";
        this.kiroAllowedTools = "";
    }

    public LLMProvider getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(LLMProvider activeProvider) {
        this.activeProvider = activeProvider;
    }

    // Ollama getters/setters
    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public String getOllamaModel() {
        return ollamaModel;
    }

    public void setOllamaModel(String ollamaModel) {
        this.ollamaModel = ollamaModel;
    }

    // Claude Code getters/setters
    public String getClaudeCodePath() {
        return claudeCodePath;
    }

    public void setClaudeCodePath(String claudeCodePath) {
        this.claudeCodePath = claudeCodePath;
    }

    public String getClaudeCodeModel() {
        return claudeCodeModel;
    }

    public void setClaudeCodeModel(String claudeCodeModel) {
        this.claudeCodeModel = claudeCodeModel;
    }

    public String getClaudeCodeAllowedTools() {
        return claudeCodeAllowedTools;
    }

    public void setClaudeCodeAllowedTools(String claudeCodeAllowedTools) {
        this.claudeCodeAllowedTools = claudeCodeAllowedTools;
    }

    // Bedrock getters/setters
    public String getBedrockAccessKey() {
        return bedrockAccessKey;
    }

    public void setBedrockAccessKey(String bedrockAccessKey) {
        this.bedrockAccessKey = bedrockAccessKey;
    }

    public String getBedrockSecretKey() {
        return bedrockSecretKey;
    }

    public void setBedrockSecretKey(String bedrockSecretKey) {
        this.bedrockSecretKey = bedrockSecretKey;
    }

    public String getBedrockSessionToken() {
        return bedrockSessionToken;
    }

    public void setBedrockSessionToken(String bedrockSessionToken) {
        this.bedrockSessionToken = bedrockSessionToken;
    }

    public String getBedrockRegion() {
        return bedrockRegion;
    }

    public void setBedrockRegion(String bedrockRegion) {
        this.bedrockRegion = bedrockRegion;
    }

    public String getBedrockModel() {
        return bedrockModel;
    }

    public void setBedrockModel(String bedrockModel) {
        this.bedrockModel = bedrockModel;
    }

    // Codex getters/setters
    public String getCodexPath() {
        return codexPath;
    }

    public void setCodexPath(String codexPath) {
        this.codexPath = codexPath;
    }

    public String getCodexModel() {
        return codexModel;
    }

    public void setCodexModel(String codexModel) {
        this.codexModel = codexModel;
    }

    // Kiro CLI getters/setters
    public String getKiroPath() {
        return kiroPath;
    }

    public void setKiroPath(String kiroPath) {
        this.kiroPath = kiroPath;
    }

    public String getKiroModel() {
        return kiroModel;
    }

    public void setKiroModel(String kiroModel) {
        this.kiroModel = kiroModel;
    }

    public String getKiroAllowedTools() {
        return kiroAllowedTools;
    }

    public void setKiroAllowedTools(String kiroAllowedTools) {
        this.kiroAllowedTools = kiroAllowedTools;
    }

    // Generic accessors for provider abstraction
    public String getModel(LLMProvider provider) {
        return switch (provider) {
            case OLLAMA -> ollamaModel;
            case BEDROCK -> bedrockModel;
            case CLAUDE_CODE -> claudeCodeModel;
            case CODEX -> codexModel;
            case KIRO -> kiroModel;
        };
    }

    public void setModel(LLMProvider provider, String model) {
        switch (provider) {
            case OLLAMA -> ollamaModel = model;
            case BEDROCK -> bedrockModel = model;
            case CLAUDE_CODE -> claudeCodeModel = model;
            case CODEX -> codexModel = model;
            case KIRO -> kiroModel = model;
        }
    }

    public static String[] getModelsForProvider(LLMProvider provider) {
        return switch (provider) {
            case OLLAMA -> new String[0]; // Ollama models are user-specified
            case BEDROCK -> BEDROCK_MODELS;
            case CLAUDE_CODE -> CLAUDE_CODE_MODELS;
            case CODEX -> CODEX_MODELS;
            case KIRO -> KIRO_MODELS;
        };
    }

    /**
     * Check if the provider has valid configuration.
     * For Ollama: needs base URL
     * For Bedrock: needs either env credentials or explicit keys
     */
    public boolean hasValidConfig(LLMProvider provider) {
        return switch (provider) {
            case OLLAMA -> ollamaBaseUrl != null && !ollamaBaseUrl.trim().isEmpty();
            case BEDROCK -> hasBedrockCredentials();
            case CLAUDE_CODE -> claudeCodePath != null && !claudeCodePath.trim().isEmpty()
                    && new java.io.File(claudeCodePath.trim()).canExecute();
            case CODEX -> codexPath != null && !codexPath.trim().isEmpty()
                    && new java.io.File(codexPath.trim()).canExecute();
            case KIRO -> kiroPath != null && !kiroPath.trim().isEmpty()
                    && new java.io.File(kiroPath.trim()).canExecute();
        };
    }

    private boolean hasBedrockCredentials() {
        // Check for explicit credentials in settings (using shared isBlank check)
        boolean hasExplicit = !AwsCredentialsUtil.isBlank(bedrockAccessKey)
                && !AwsCredentialsUtil.isBlank(bedrockSecretKey);

        // Check for environment credentials (using shared validation that checks for blank values)
        boolean hasEnv = AwsCredentialsUtil.hasValidEnvCredentials();

        // Check for shared credentials file with the selected profile (using shared parser)
        String profile = AwsCredentialsUtil.getEffectiveProfile();
        AwsCredentialsUtil.CredentialsResult fileCreds = AwsCredentialsUtil.loadFromFile(profile);
        boolean hasProfile = fileCreds != null && fileCreds.isValid();

        return hasExplicit || hasEnv || hasProfile;
    }
}
