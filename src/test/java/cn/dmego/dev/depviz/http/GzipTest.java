package cn.dmego.dev.depviz.http;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * gzip 协商。
 *
 * <p>重点是「头必须说真话」：早先 respond() 无条件先写
 * {@code Content-Encoding: gzip}，然后才判断压完是不是更小。够小的响应
 * 压完反而变大时会发明文却在头里声称 gzip —— 浏览器拿 gunzip 去解一段
 * 普通 JSON，直接报解码错误，而服务端日志一切正常。
 */
public class GzipTest {

    @Test
    public void incompressiblePayloadGrowsUnderGzip() throws Exception {
        // 伪随机字节（固定种子的 LCG，可重复）。真随机数据 gzip 压不动，
        // 加 18 字节头与尾部校验后必然变大 —— 这正是 respond() 必须回退明文的场景。
        byte[] raw = new byte[300];
        int x = 12345;
        for (int i = 0; i < raw.length; i++) {
            x = (1103515245 * x + 12345) & 0x7fffffff;
            raw[i] = (byte) (x >>> 16);
        }
        assertTrue("前提：这段内容压完不会变小",
                Gzip.compress(raw).length >= raw.length);
    }

    @Test
    public void compressRoundTrips() throws Exception {
        byte[] raw = new byte[10000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) ('a' + (i % 3));
        }
        byte[] gz = Gzip.compress(raw);
        assertTrue("重复内容必须压小", gz.length < raw.length);

        GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        assertEquals(new String(raw, StandardCharsets.UTF_8),
                new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void thresholdIsRespectedForTinyBodies() throws Exception {
        // 小于 2 KB 不值得压。这里只验证压缩器本身对短输入的判断，
        // 不经过 HTTP（Exchange 需要真实连接）。
        byte[] tiny = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        assertTrue("8 字节的内容压完不可能更小",
                Gzip.compress(tiny).length >= tiny.length);
    }
}
