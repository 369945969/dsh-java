package com.deepseek.dsh.app.profile;

import java.nio.file.Path;

import com.deepseek.dsh.agent.Agent;
import com.deepseek.dsh.app.bundle.BaseBundle;
import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.util.PluginRunner;

/**
 * Profile —— 命名的插件树组合层（对应原 Harness 的 profile/bundle 组合）。
 *
 * <p>一个 profile 在启动时从有序层组合而成，{@code base} 是每个 profile 的第一层。
 * 切换 profile 即可改变 agent 的能力组合（如 headless vs web）。
 *
 * <p>设计模式：构建器 + 抽象工厂。
 */
public final class Profile {

    private final String name;
    private final BaseBundle base;

    public Profile(String name, BaseBundle base) {
        this.name = name;
        this.base = base;
    }

    public String name() {
        return name;
    }

    /**
     * 装配此 profile：挂载基础包插件并返回 agent。
     */
    public Agent assemble(Context ctx, PluginRunner runner) throws Exception {
        return base.assemble(ctx, runner);
    }

    /** 工厂：创建默认 web profile。 */
    public static Profile defaultWeb(String apiKey, String model, Path dataDir) {
        return new Profile("default", new BaseBundle(apiKey, model, dataDir));
    }
}
