package dev.t1m3.qplayer.audio.model;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>防回归：权重绝不打包进成品。</b>
 *
 * <p>这不是一条风格要求，是产品要求：权重一共 191MB，而绝大多数用户根本不会打开
 * 依赖它们的功能；一旦它们进了安装包，卸载再装、CI 产物、每个平台的构建时长
 * 全都要为它们付账，而「按需下载」这件事也就做不成了（能下载的前提是本来没有）。
 *
 * <p>所以这里直接盯着<b>打包输入</b>，而不是盯着代码：
 * <ul>
 *   <li>两个宿主的资源目录（会被打进 jar / APK）里不许有权重文件；</li>
 *   <li>共享 QML 树（桌面端把它整个挂到 classpath 上，安卓端它进 assets）也不许；</li>
 *   <li>清单里每个条目声明的文件<b>在运行期 classpath 上必须查不到</b>——
 *       这一条比目录扫描更强：它连「用别的构建插件塞进 resources」也一并挡住；</li>
 *   <li>清单资源目录里只该有清单本身（JSON）；</li>
 *   <li>打包配置（pom / gradle）里不许出现权重文件名。</li>
 * </ul>
 *
 * <p>它同时钉住「开发机取权重的那条路」不会溢出到仓库里：{@code player-core}
 * 下不该出现 folia 那种 {@code models/} 目录。
 */
public class ModelPackagingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** 会被当成「权重」的扩展名。多列几个是因为这类文件最容易以另一个名字混进来。 */
    private static final List<String> WEIGHT_EXTENSIONS = Arrays.asList(
            ".onnx", ".pt", ".pth", ".safetensors", ".ckpt", ".tflite", ".mlmodel", ".gguf");

    /** 会被打包进成品的目录（桌面 jar 的资源 + 安卓 assets 的源）。 */
    private static final List<String> PACKAGING_INPUTS = Arrays.asList(
            "player-core/src/main/resources",
            "desktop-host/src/main/resources",
            "desktop-host/src/main/resources-filtered",
            "shared-qml",
            "android-shell/app/src/main");

    @Test
    public void noWeightFilesInAnyPackagingInput() {
        File root = repoRoot();
        List<String> offenders = new ArrayList<String>();
        for (String relative : PACKAGING_INPUTS) {
            File dir = new File(root, relative);
            if (!dir.isDirectory()) {
                continue;
            }
            collectWeights(dir, offenders);
        }
        assertTrue("这些文件会被打进成品，但它们必须由用户按需下载：\n"
                        + join(offenders),
                offenders.isEmpty());
    }

    /**
     * 运行期 classpath 上查不到清单里声明的那些文件。
     *
     * <p>这一条是这套防回归里最要紧的一条：它不问「哪个目录里有没有」，
     * 而是问「运行的时候能不能拿到」——那正是「打包进去」的定义。
     *
     * <p>遍历的是 {@link ModelManifest#allEntries()}（{@code models} + 对照物 {@code reference}），
     * 而不是只遍历 {@code models()}：清单里<b>声明过的每一个文件名</b>都在这一条的射程内。
     * 对照物（{@code mel_spectrogram.onnx}）是 264 KB 的小文件，正是最容易「顺手放进
     * resources 算了」的那一类，所以它尤其要在射程内。
     */
    @Test
    public void theDeclaredWeightsAreNotOnTheRuntimeClasspath() {
        ModelManifest manifest = ModelManifest.embedded();
        assertFalse(manifest.allEntries().isEmpty());
        for (ModelManifestEntry entry : manifest.allEntries()) {
            String resource = "/models/" + entry.file;
            assertTrue("清单里的 " + resource + " 竟然在 classpath 上——权重被打包了",
                    ModelManifest.class.getResource(resource) == null);
        }
    }

    @Test
    public void theOnlyThingUnderTheManifestResourceDirectoryIsTheManifest() {
        File dir = new File(repoRoot(), "player-core/src/main/resources/models");
        assertTrue("清单资源目录不存在：" + dir, dir.isDirectory());

        File[] files = dir.listFiles();
        assertNotNull(files);
        for (File file : files) {
            assertTrue("清单目录里只该有清单本身，却有：" + file.getName(),
                    file.getName().endsWith(".json"));
        }
        assertTrue("清单本身必须在那里",
                new File(dir, "model-manifest.json").isFile());
    }

    /** 开发机用的取权重目录不属于仓库（folia 把权重放在 models/，这里没有那一步）。 */
    @Test
    public void noDeveloperWeightFolderInTheCoreModule() {
        File stray = new File(repoRoot(), "player-core/models");
        assertFalse("player-core 下不该出现取权重用的 models/ 目录：" + stray, stray.exists());
    }

    /**
     * <b>第二份清单不许回来。</b>
     *
     * <p>本轮收敛掉的就是「两份描述同一批模型」：Sonalis 形状的
     * {@code audio/model/models.json} 已经删掉，拍点那条路也读 {@code model-manifest.json}。
     * 理由是 folia 那句话——两份描述迟早会不一致，而它们不一致时的表现方式是一个
     * 「自信但错误的拍点」。所以这条防回归直接盯着那个路径：它一旦回来，这里立刻红。
     */
    @Test
    public void thereIsNoSecondManifestInTheTree() {
        File resources = new File(repoRoot(), "player-core/src/main/resources");
        List<String> stray = new ArrayList<String>();
        collectNamed(resources, "models.json", stray);
        assertTrue("资源目录里出现了第二份模型清单（只该有 /models/model-manifest.json）：\n"
                + join(stray), stray.isEmpty());
    }

    /** 递归收集名叫 {@code name} 的文件（清单只有一份这件事的守卫用）。 */
    private static void collectNamed(File dir, String name, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectNamed(child, name, out);
            } else if (name.equals(child.getName())) {
                out.add(child.getAbsolutePath());
            }
        }
    }

    @Test
    public void packagingConfigsDoNotMentionAnyWeightFile() {
        ModelManifest manifest = ModelManifest.embedded();
        File root = repoRoot();
        for (String config : Arrays.asList("desktop-host/pom.xml",
                "android-shell/app/build.gradle.kts")) {
            File file = new File(root, config);
            if (!file.isFile()) {
                continue;
            }
            String text = read(file);
            for (ModelManifestEntry entry : manifest.allEntries()) {
                assertFalse(config + " 里提到了权重文件 " + entry.file
                                + "（打包配置不该认识它们）",
                        text.indexOf(entry.file) >= 0);
            }
            // 不查裸的 ".onnx" 子串：ONNX Runtime 的坐标是
            // com.microsoft.onnxruntime，它<b>该</b>在 pom 里（那是推理库，不是权重）。
            // 会被打进成品的写法只有两种：一个 *.onnx 通配，或者一个具体的文件名。
            assertFalse(config + " 里出现了 *.onnx 通配：权重会被一起打进去",
                    text.indexOf("*.onnx") >= 0);
        }
    }

    /**
     * 安卓的 assets 里没有权重。
     *
     * <p>单独再钉一次，是因为 APK 的打包路径与桌面 jar 完全不同
     * （{@code sourceSets.assets} 在构建脚本里，而不在资源目录约定里），
     * 而「APK 大了一圈」正是这条要求最先会被发现的地方。
     */
    @Test
    public void androidAssetsCarryNoWeights() {
        File root = repoRoot();
        List<String> offenders = new ArrayList<String>();
        for (String assets : Arrays.asList("android-shell/app/src/main/assets",
                "android-shell/app/src/main/jniLibs")) {
            File dir = new File(root, assets);
            if (dir.isDirectory()) {
                collectWeights(dir, offenders);
            }
        }
        // 顺带看一眼构建产物：如果上一次构建真的把权重打进去了，这里会立刻发现
        File merged = new File(root, "android-shell/app/build/intermediates/assets");
        if (merged.isDirectory()) {
            collectWeights(merged, offenders);
        }
        assertTrue("APK 的输入（含上一次构建的中间产物）里有权重：\n" + join(offenders),
                offenders.isEmpty());
    }

    // ---- 工具 ---------------------------------------------------------------

    private static void collectWeights(File dir, List<String> offenders) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                // build/ 之外的中间产物都在上面单独处理；这里不下钻构建目录
                collectWeights(child, offenders);
                continue;
            }
            String lower = child.getName().toLowerCase(java.util.Locale.ROOT);
            for (String ext : WEIGHT_EXTENSIONS) {
                if (lower.endsWith(ext) || lower.endsWith(ext + ".part")) {
                    offenders.add(child.getAbsolutePath());
                    break;
                }
            }
        }
    }

    /**
     * 仓库根目录。
     *
     * <p>Surefire 的工作目录是模块目录（{@code player-core}），但 IDE 里可能是根目录，
     * 所以往上找到「同时有 player-core 与 desktop-host 的那一层」为止——
     * 写死相对层级会让这条测试在换一个跑法时静默地测错地方。
     */
    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (new File(dir, "player-core").isDirectory()
                    && new File(dir, "desktop-host").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        fail("找不到仓库根目录（从 " + System.getProperty("user.dir") + " 往上找过）");
        return null;
    }

    private static String read(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            fail("读不了 " + file + "：" + e);
            return "";
        }
    }

    private static String join(List<String> items) {
        if (items.isEmpty()) {
            return "（没有）";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("  ").append(item).append('\n');
        }
        return sb.toString();
    }

    /**
     * 这条测试自己也要站得住：扫描<b>得真的认得出</b>一个权重文件。
     *
     * <p>没有这一条，上面那些「没找到」的断言在扫描写坏（比如扩展名列表被清空）时
     * 会一起变成永远通过——防回归测试最怕的就是这个。
     */
    @Test
    public void theScanItselfWouldNoticeAWeightFile() throws Exception {
        File dir = folder.newFolder("probe");
        File planted = new File(dir, "beat_this.onnx");
        java.io.FileOutputStream out = new java.io.FileOutputStream(planted);
        try {
            out.write(new byte[]{0});
        } finally {
            out.close();
        }
        File part = new File(dir, "htdemucs.onnx.part");
        assertTrue(part.createNewFile());

        List<String> found = new ArrayList<String>();
        collectWeights(dir, found);
        assertTrue("扫描必须认得出 .onnx：" + found, found.contains(planted.getAbsolutePath()));
        assertTrue("扫描必须认得出 .onnx.part：" + found, found.contains(part.getAbsolutePath()));
    }
}
