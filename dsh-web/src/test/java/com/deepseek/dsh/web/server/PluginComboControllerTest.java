package com.deepseek.dsh.web.server;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginComboControllerTest {

    @Test
    void extractIdStripsClientJsSuffix() throws Exception {
        var method = PluginComboController.class.getDeclaredMethod("extractId", String.class, String.class);
        method.setAccessible(true);
        String id = (String) method.invoke(null, "@deepseek-ai/dsh-test/client.js", ".js");
        assertEquals("@deepseek-ai/dsh-test", id);
    }

    @Test
    void extractIdReturnsNullForNonClientJs() throws Exception {
        var method = PluginComboController.class.getDeclaredMethod("extractId", String.class, String.class);
        method.setAccessible(true);
        assertNull(method.invoke(null, "wrong.txt", ".js"));
    }

    @Test
    void buildComboUrlSingleResource() throws Exception {
        var method = PluginComboController.class.getDeclaredMethod("buildComboUrl", List.class, boolean.class);
        method.setAccessible(true);
        String url = (String) method.invoke(null, List.of("@deepseek-ai/dsh-test"), false);
        assertEquals("/plugins/??@deepseek-ai/dsh-test/client.js", url);
    }

    @Test
    void buildComboUrlMultiResource() throws Exception {
        var method = PluginComboController.class.getDeclaredMethod("buildComboUrl", List.class, boolean.class);
        method.setAccessible(true);
        String url = (String) method.invoke(null, List.of("@deepseek-ai/dsh-a", "@deepseek-ai/dsh-b"), false);
        assertEquals("/plugins/??@deepseek-ai/dsh-a/client.js,@deepseek-ai/dsh-b/client.js", url);
    }

    @Test
    void buildComboUrlSourceMap() throws Exception {
        var method = PluginComboController.class.getDeclaredMethod("buildComboUrl", List.class, boolean.class);
        method.setAccessible(true);
        String url = (String) method.invoke(null, List.of("@deepseek-ai/dsh-a"), true);
        assertEquals("/plugins/??@deepseek-ai/dsh-a/client.js.map", url);
    }
}
