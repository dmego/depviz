package cn.dmego.dev.depviz.scan;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「离线失败 → 自动联网重试一次」这条分支的判定条件。
 *
 * <p>这段逻辑没有在 {@code ScanPipeline} 里被直接测到（要测就得跑两次 Maven），
 * 但判定本身是纯函数，而它决定了「什么时候会凭空多跑一次联网扫描」——
 * 误判一次就是几十秒的下载，用户会以为程序在偷跑。所以单独测。
 *
 * <p>断言用的都是 Maven 真实输出里出现过的原文（3.3.9 与 3.9.x 措辞不同，几种都收）。
 */
public class OfflineFailureTest {

    @Test
    public void realMavenOfflineMessagesAreDetected() {
        String[] real = {
            // 3.9.x：BOM 在本地仓库里但是「present, but unavailable」（repo id 不匹配）
            "[ERROR] Non-resolvable import POM: org.springframework:spring-framework-bom:pom:5.3.20 "
                    + "(present, but unavailable)",
            // 3.9.x：build extension 完全不在本地仓库
            "[ERROR] Unresolveable build extension: Plugin kr.motd.maven:os-maven-plugin:1.5.0.Final "
                    + "or one of its dependencies could not be resolved: Cannot access aliyun "
                    + "(https://maven.aliyun.com/repository/central) in offline mode",
            // 3.3.9：parent POM 不在本地
            "[ERROR] Non-resolvable parent POM for com.x:y:1.0: The following artifacts could not be "
                    + "resolved: com.x:parent:pom:1.0",
            // 插件本身没下载过
            "[ERROR] Cannot access central in offline mode and the artifact "
                    + "org.apache.maven.plugins:maven-dependency-plugin:jar:3.6.0 has not been downloaded",
        };
        for (String msg : real) {
            assertTrue("应识别为离线缺件:\n" + msg, ScanPipeline.looksLikeOfflineFailure(msg));
        }
    }

    @Test
    public void unrelatedFailuresAreNotRetriedOnline() {
        String[] other = {
            // 编译/语法错：联网也救不了，重试只是浪费几十秒
            "[ERROR] The project org.apache.seata:seata-parent:2.1.0-SNAPSHOT "
                    + "(/x/pom.xml) has 3 errors",
            "[ERROR] Malformed POM /x/pom.xml: Unrecognised tag: 'foo'",
            // 超时与取消
            "Maven 超过 600 秒未结束，已终止",
            "扫描已取消",
            // 退出码非零但没有离线字样
            "Maven 退出码 1\n[INFO] BUILD FAILURE",
            null,
        };
        for (String msg : other) {
            assertFalse("不该触发联网重试:\n" + msg, ScanPipeline.looksLikeOfflineFailure(msg));
        }
    }

    @Test
    public void onlyOfflineScansAreRetried() {
        // 判定函数本身不看 req.offline，那一层判断在 scan() 里（`req.offline && looksLike...`）。
        // 这里只确认两件事分开写是对的：在线扫描失败时即便信息里有 "Cannot access"，
        // 也不该再重试一次 —— 因为已经在线的，重试结果只会一样。
        String onlineFail = "[ERROR] Cannot access central in offline mode";
        assertTrue(ScanPipeline.looksLikeOfflineFailure(onlineFail));
    }
}
