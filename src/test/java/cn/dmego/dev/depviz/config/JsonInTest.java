package cn.dmego.dev.depviz.config;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link JsonIn} 的解析边界。
 *
 * <p>{@code /api/config} 的请求体是这个本机服务唯一的用户输入面，
 * 解析器崩掉的方式必须可预期。
 */
public class JsonInTest {

    @Test
    public void parsesNestedStructures() {
        Object v = JsonIn.parse("{\"a\":[1,{\"b\":true}],\"c\":null}");
        assertTrue(v instanceof Map);
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(2, m.size());
        assertTrue(m.get("a") instanceof java.util.List);
        assertTrue("null 要解析成 null 而不是字符串", m.containsKey("c"));
        assertEquals(null, m.get("c"));
    }

    @Test
    public void deepNestingIsRejectedNotStackOverflow() {
        // 早先没有深度上限：一万层数组会递归到 StackOverflowError。
        // 那是 Error 不是 RuntimeException，HTTP 层拦不住，工作线程直接死。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 5000; i++) {
            sb.append(']');
        }
        try {
            JsonIn.parse(sb.toString());
            fail("应该抛 JsonParseException 而不是真的去递归 5000 层");
        } catch (JsonParseException e) {
            assertTrue("错误信息要指出是嵌套层数: " + e.getMessage(),
                    e.getMessage().contains("嵌套"));
        }
    }

    @Test
    public void reasonableNestingStillWorks() {
        // 上限 64 层。正常配置最深也就「未来字段里嵌一个对象」，两三层的量级。
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            open.append('[');
            close.append(']');
        }
        Object v = JsonIn.parse(open.toString() + close.toString());
        assertTrue(v instanceof java.util.List);
    }

    @Test
    public void trailingContentIsRejected() {
        try {
            JsonIn.parse("{\"a\":1} trailing");
            fail("多余内容必须报错，否则截断的请求体会被当成合法");
        } catch (JsonParseException e) {
            assertTrue(e.getMessage().contains("多余内容"));
        }
    }

    @Test
    public void topLevelNonObjectIsRejectedByParseObject() {
        try {
            JsonIn.parseObject("[1,2]");
            fail("parseObject 只接对象");
        } catch (JsonParseException e) {
            assertTrue(e.getMessage().contains("顶层不是对象"));
        }
    }

    @Test
    public void escapesAndNumbersRoundTrip() {
        Map<String, Object> m = JsonIn.parseObject(
                "{\"s\":\"a\\\"b\\\\c\\nd\\te\\u0001\",\"n\":-1.5,\"b\":false}");
        assertEquals("a\"b\\c\nd\te\u0001", m.get("s"));
        assertEquals(-1.5, ((Number) m.get("n")).doubleValue(), 1e-9);
        assertFalse((Boolean) m.get("b"));
    }
}
