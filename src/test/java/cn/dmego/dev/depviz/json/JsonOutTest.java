package cn.dmego.dev.depviz.json;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class JsonOutTest {

    @Test
    public void emptyContainers() {
        assertEquals("{}", new JsonOut().beginObject().endObject().toString());
        assertEquals("[]", new JsonOut().beginArray().endArray().toString());
    }

    @Test
    public void flatObject() {
        JsonOut o = new JsonOut().beginObject()
                .field("a", 1).field("b", "x").field("c", true).name("d").nullValue();
        assertEquals("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null}", o.endObject().toString());
    }

    @Test
    public void arrayOfObjects() {
        JsonOut o = new JsonOut().beginArray();
        o.beginObject().field("i", 0).endObject();
        o.beginObject().field("i", 1).endObject();
        assertEquals("[{\"i\":0},{\"i\":1}]", o.endArray().toString());
    }

    @Test
    public void nestedArrayInObject() {
        JsonOut o = new JsonOut().beginObject().name("v").beginArray()
                .value("1.0").value("2.0").endArray().endObject();
        assertEquals("{\"v\":[\"1.0\",\"2.0\"]}", o.toString());
    }

    @Test
    public void escapesControlCharsAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\\u0001\"", JsonOut.quote("a\"b\\c\nd\te\u0001"));
    }
}
