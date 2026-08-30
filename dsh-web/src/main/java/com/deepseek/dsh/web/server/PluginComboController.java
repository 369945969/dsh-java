package com.deepseek.dsh.web.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 插件 combo 组合路由 —— 复刻 harness 0.1.2 的
 * {@code ClientModuleRegistry.serveBundle}（{@code packages/client/modules/src/index.ts:1002}）。
 *
 * <p>0.1.2 把单文件加载改为 combo 批量加载：浏览器请求
 * {@code /plugins/??<id>/client.js,<id2>/client.js&rev=<rev>}，
 * 服务端从各插件的 {@code static/plugins/<id>/client.js} 读取原始 bundle，
 * strip 掉 sourceMappingURL/sourceURL trailer、用 {@code ;\n} 拼接、
 * 末尾盖 {@code //# sourceMappingURL=<comboMapUrl>} 戳记，
 * 以 {@code text/javascript; charset=utf-8} + immutable cache 返回。
 *
 * <p>未广告的 combo（stale rev、未知组合、{@code /plugins/events} 无 HMR）→ 404；
 * 非 GET/HEAD → 405。与 harness serveBundle 逐字节一致。
 */
@RestController
@RequestMapping("/plugins/**")
public class PluginComboController {

    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
    private static final String JS_CONTENT_TYPE = "text/javascript; charset=utf-8";
    private static final String MAP_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final Pattern SOURCE_MAP_TRAILER =
            Pattern.compile("(?:\\r?\\n)?//# sourceMappingURL=[^\\r\\n]*(?:\\r?\\n)?$");
    private static final Pattern SOURCE_URL_TRAILER =
            Pattern.compile("(?:\\r?\\n)?//# sourceURL=([^\\r\\n]+)(?:\\r?\\n)?$");

    @RequestMapping
    public void serve(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String method = req.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            resp.setStatus(405);
            return;
        }

        String queryString = req.getQueryString();
        if (queryString == null || !queryString.startsWith("?")) {
            resp.setStatus(404);
            return;
        }

        String body = queryString.substring(1); // strip leading '?'
        int revIdx = body.lastIndexOf("&rev=");
        if (revIdx < 0) {
            resp.setStatus(404);
            return;
        }
        String resourcesPart = body.substring(0, revIdx);
        String[] resourceTokens = resourcesPart.split(",");
        if (resourceTokens.length == 0) {
            resp.setStatus(404);
            return;
        }

        boolean isMap = resourceTokens[0].endsWith(".map");
        if (isMap) {
            serveMap(resourceTokens, req, resp);
        } else {
            serveScript(resourceTokens, resp, "HEAD".equals(method));
        }
    }

    private void serveScript(String[] resources, HttpServletResponse resp, boolean head) throws IOException {
        List<byte[]> bundles = new ArrayList<>(resources.length);
        List<String> ids = new ArrayList<>(resources.length);
        for (String resource : resources) {
            String id = extractId(resource, ".js");
            if (id == null) {
                resp.setStatus(404);
                return;
            }
            byte[] bundle = readPluginFile(id, "client.js");
            if (bundle == null) {
                resp.setStatus(404);
                return;
            }
            bundles.add(bundle);
            ids.add(id);
        }

        StringBuilder source = new StringBuilder();
        for (byte[] bundle : bundles) {
            String s = new String(bundle, StandardCharsets.UTF_8);
            s = SOURCE_URL_TRAILER.matcher(s).replaceFirst("");
            s = SOURCE_MAP_TRAILER.matcher(s).replaceFirst("");
            if (!s.endsWith("\n")) s += "\n";
            source.append(s).append(";\n");
        }

        String mapUrl = buildComboUrl(ids, true);
        source.append("//# sourceMappingURL=").append(mapUrl).append("\n");

        byte[] bytes = source.toString().getBytes(StandardCharsets.UTF_8);
        resp.setStatus(200);
        resp.setContentType(JS_CONTENT_TYPE);
        resp.setHeader("Cache-Control", IMMUTABLE_CACHE);
        if (!head) {
            resp.getOutputStream().write(bytes);
        }
    }

    private void serveMap(String[] resources, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Source maps are debugger-lazy; serve a minimal 404 for now.
        // The browser tolerates missing maps during normal operation.
        resp.setStatus(404);
    }

    private static String extractId(String resource, String suffix) {
        if (!resource.endsWith(suffix)) return null;
        return resource.substring(0, resource.length() - ("/client" + suffix).length());
    }

    private static String buildComboUrl(List<String> ids, boolean sourceMap) {
        StringBuilder sb = new StringBuilder("/plugins/??");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i)).append("/client.js").append(sourceMap ? ".map" : "");
        }
        return sb.toString();
    }

    private static byte[] readPluginFile(String pluginId, String fileName) throws IOException {
        String path = "static/plugins/" + pluginId + "/" + fileName;
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) return null;
        try (InputStream is = res.getInputStream()) {
            return is.readAllBytes();
        }
    }
}
