package cn.dmego.dev.depviz.http;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 临时目录必须真的被删掉 —— 早先的守卫把普通文件短路了，一个文件都不删。 */
public class FileUtilTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void deletesNestedFilesAndDirectories() throws Exception {
        File root = tmp.newFolder("jobs");
        File sub = new File(root, "sub");
        File deep = new File(sub, "deep");
        assertTrue(deep.mkdirs());
        java.nio.file.Files.write(new File(deep, "tree.txt").toPath(),
                "15355 lines".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        FileUtil.deleteRecursively(root);

        assertFalse("目录必须被删掉", root.exists());
    }

    @Test
    public void deletesPlainFile() throws Exception {
        File f = tmp.newFile("tree.txt");
        FileUtil.deleteRecursively(f);
        assertFalse("普通文件必须被删掉", f.exists());
    }

    @Test
    public void deletingAScanDirectoryDoesNotFollowSymlinks() throws Exception {
        File outside = tmp.newFolder("outside");
        File keep = new File(outside, "keep.txt");
        java.nio.file.Files.write(keep.toPath(), new byte[]{1});
        File root = tmp.newFolder("scan");
        try {
            java.nio.file.Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.Assume.assumeNoException(e);
        }
        FileUtil.deleteRecursively(root);
        assertFalse(root.exists());
        assertTrue("只删除链接，不能删除外部文件", keep.isFile());
    }

    @Test
    public void toleratesMissingPath() {
        FileUtil.deleteRecursively(new File(tmp.getRoot(), "does-not-exist"));
        FileUtil.deleteRecursively(null);
    }
}
