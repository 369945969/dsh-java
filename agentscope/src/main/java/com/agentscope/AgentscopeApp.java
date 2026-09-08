package com.agentscope;

import com.agentscope.config.Config;
import com.agentscope.web.WebServer;
import com.agentscope.ws.WsServer;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AgentscopeApp {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        String mode = args.length > 0 ? args[0] : "web";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : cfg.port;

        Path wsDir = Paths.get(cfg.workspaceDir);
        Files.createDirectories(wsDir);
        Path skillsAbs = Paths.get(cfg.skillsDir).isAbsolute()
                ? Paths.get(cfg.skillsDir)
                : Path.of(System.getProperty("user.dir"), cfg.skillsDir);

        System.out.println("[agentscope] mode=" + mode + " port=" + port);
        System.out.println("[agentscope] model=" + cfg.model + " workspace=" + wsDir.toAbsolutePath());
        System.out.println("[agentscope] skills=" + skillsAbs.toAbsolutePath());
        System.out.println("[agentscope] token=" + cfg.token.substring(0, Math.min(8, cfg.token.length())) + "...");
        System.out.println("[agentscope] URL: http://localhost:" + port + "/?token=" + cfg.token);

        HarnessAgent agent = HarnessAgent.builder()
                .name("agentscope-bot")
                .sysPrompt(cfg.sysPrompt)
                .model(cfg.model)
                .workspace(wsDir)
                .build();

        WebServer web = new WebServer(port, agent, cfg);
        web.start();

        if ("ws".equals(mode)) {
            WsServer ws = new WsServer(port, agent, cfg);
            ws.start();
            System.out.println("[agentscope] WebSocket started on ws://localhost:" + port + "/ws/agent");
        }
        System.out.println("[agentscope] ready (mode=" + mode + ")");
    }
}
