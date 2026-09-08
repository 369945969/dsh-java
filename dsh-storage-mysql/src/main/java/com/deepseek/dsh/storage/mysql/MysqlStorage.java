package com.deepseek.dsh.storage.mysql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * MySQL 存储后端 —— HikariCP 连接池单例，供 MysqlSessionStore / MysqlModelProfileBackend /
 * MysqlSysPromptSource 共享。
 *
 * <p>配置取自环境变量：
 * <ul>
 *   <li>{@code DSH_DB_HOST}（默认 localhost）</li>
 *   <li>{@code DSH_DB_PORT}（默认 3306）</li>
 *   <li>{@code DSH_DB_NAME}（默认 dsh-java）</li>
 *   <li>{@code DSH_DB_USER}（默认 root）</li>
 *   <li>{@code DSH_DB_PASSWORD}（必填）</li>
 * </ul>
 *
 * <p>设计模式：资源容器（连接池）+ 工厂（按 env 装配）。
 */
public final class MysqlStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MysqlStorage.class);

    private final HikariDataSource pool;

    private MysqlStorage(HikariDataSource pool) {
        this.pool = pool;
    }

    /** 从环境变量装配连接池。DSH_STORAGE=mysql 时由 BaseBundle/start 脚本调用。 */
    public static MysqlStorage fromEnv() {
        String host = System.getenv().getOrDefault("DSH_DB_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("DSH_DB_PORT", "3306"));
        String name = System.getenv().getOrDefault("DSH_DB_NAME", "dsh-java");
        String user = System.getenv().getOrDefault("DSH_DB_USER", "root");
        String pass = System.getenv().getOrDefault("DSH_DB_PASSWORD", "");
        return create(host, port, name, user, pass);
    }

    /** 显式装配连接池。 */
    public static MysqlStorage create(String host, int port, String name, String user, String pass) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setPoolName("dsh-mysql-pool");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        HikariDataSource ds = new HikariDataSource(cfg);
        log.info("MySQL pool ready: {}@{}/{} (maxPool={})", user, host + ":" + port, name, 10);
        return new MysqlStorage(ds);
    }

    /** 借一条连接（用完归还，try-with-resources）。 */
    public Connection borrow() throws java.sql.SQLException {
        return pool.getConnection();
    }

    public DataSource dataSource() {
        return pool;
    }

    /**
     * 引导 schema：把 schema.sql 内容按 ';' 分句执行（幂等：DROP IF EXISTS + CREATE IF NOT EXISTS）。
     * 由 start 脚本在 DSH_STORAGE=mysql 时启动前调用。
     */
    public void bootstrapSchema(Path schemaSql) {
        if (schemaSql == null || !Files.isReadable(schemaSql)) {
            log.warn("schema.sql 不可读，跳过 bootstrap: {}", schemaSql);
            return;
        }
        try {
            String sql = Files.readString(schemaSql);
            // 去注释行，按 ; 分句
            String cleaned = sql.lines()
                    .filter(l -> !l.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            try (Connection c = borrow(); Statement st = c.createStatement()) {
                for (String stmt : cleaned.split(";")) {
                    String s = stmt.strip();
                    if (s.isEmpty() || s.startsWith("--")) continue;
                    try {
                        st.execute(s);
                    } catch (java.sql.SQLException e) {
                        log.warn("schema stmt 跳过: {}", e.getMessage());
                    }
                }
            }
            log.info("schema bootstrap 完成: {}", schemaSql);
        } catch (Exception e) {
            log.error("schema bootstrap 失败: {}", e.toString());
        }
    }

    @Override
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("MySQL pool closed");
        }
    }
}
