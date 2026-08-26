package com.deepseek.dsh.core.event;

import org.junit.jupiter.api.Test;

import com.deepseek.dsh.core.context.Context;
import com.deepseek.dsh.core.context.Disposable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件总线 + 上下文作用域的基础测试。
 */
class EventBusTest {

    @Test
    void emit分发到所有监听器() {
        EventBus bus = new EventBus();
        int[] counter = {0};
        Disposable d = bus.on(String.class, (e, next) -> {
            counter[0]++;
            return next.invoke(e);
        });
        bus.emit("hello");
        assertEquals(1, counter[0]);
        d.dispose();
        bus.emit("again");
        assertEquals(1, counter[0]);
    }

    @Test
    void waterfall贯穿传递并可短路() {
        EventBus bus = new EventBus();
        bus.on(Integer.class, (e, next) -> next.invoke(e + 1));
        bus.on(Integer.class, (e, next) -> next.invoke(e + 1));
        bus.on(Integer.class, (e, next) -> e); // 短路，不再调用 next
        Integer result = bus.waterfall(Integer.class, 0);
        // 第三个监听器短路，结果为进入它的值（0 + 1 + 1 = 2）
        assertEquals(2, result);
    }

    @Test
    void 作用域注册不泄漏到父级() {
        Context root = Context.root();
        root.register("global", "G");
        assertEquals("G", root.get("global").orElseThrow());

        Context child = root.scoped(com.deepseek.dsh.core.brand.ScopeKey.random());
        // 子级可读到父级的全局服务
        assertEquals("G", child.get("global").orElseThrow());
        // 子级注册不泄漏
        child.register("scoped-only", "S");
        assertTrue(root.get("scoped-only").isEmpty());
        assertEquals("S", child.get("scoped-only").orElseThrow());

        // 子级遮蔽父级
        child.register("global", "shadow");
        assertEquals("shadow", child.get("global").orElseThrow());
        assertEquals("G", root.get("global").orElseThrow());
    }
}
