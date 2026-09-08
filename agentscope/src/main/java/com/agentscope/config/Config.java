package com.agentscope.config;

public class Config {
    public final String token;
    public final int port;
    public final String model;
    public final String workspaceDir;
    public final String sysPrompt;
    public final String skillsDir;
    public final String mcpServers; // JSON, optional

    public Config() {
        this.token = env("DSH_TOKEN", "agentscope-default-token");
        this.port = Integer.parseInt(env("DSH_PORT", "8766"));
        this.model = env("DSH_MODEL", "dashscope:qwen3.7-max");
        this.workspaceDir = env("DSH_WORKSPACE", ".agentscope/workspace");
        this.sysPrompt = env("DSH_SYS_PROMPT", "You are a helpful AI assistant powered by AgentScope.");
        this.skillsDir = env("DSH_SKILLS_DIR", "src/main/resources/skills");
        this.mcpServers = env("DSH_MCP_SERVERS", "");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }
}
