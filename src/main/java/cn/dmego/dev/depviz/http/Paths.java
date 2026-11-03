package cn.dmego.dev.depviz.http;

import java.io.File;
import java.io.IOException;

/**
 * 把客户端传进来的目录参数变成<b>唯一的规范形式</b>。
 *
 * <p>为什么必须做：{@code projectId} 就是绝对路径，而路径的写法有无数种等价形式
 * （{@code /a/b}、{@code /a/b/}、{@code /a/./b}、{@code /a/b/../b}）。不做归一化，
 * 同一个项目会因为写法不同被当成两个不同的 key —— 「最近打开」里出现两条、
 * 扫描两次、内存里存两份图。例如 {@code /workspace/project/.} 与
 * {@code /workspace/project} 应使用同一个 {@code projectId}。
 *
 * <p>同时拒绝相对路径：服务进程的 {@code user.dir} 对用户不可见，
 * {@code dir=.} 到底指向哪里全凭启动方式，属于不可预期的行为。
 */
final class Paths {

    private Paths() {
    }

    /**
     * @return 规范化的绝对路径（去掉末尾分隔符与 {@code .}、{@code ..}）
     * @throws IllegalArgumentException 路径为空或不是绝对路径
     */
    static String canonicalDir(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 dir 参数");
        }
        File f = new File(raw.trim());
        if (!f.isAbsolute()) {
            throw new IllegalArgumentException("dir 必须是绝对路径: " + raw);
        }
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            // 路径不存在时 getCanonicalPath 仍会尽量规范化；真失败就用绝对路径兜底
            return f.getAbsolutePath();
        }
    }
}
