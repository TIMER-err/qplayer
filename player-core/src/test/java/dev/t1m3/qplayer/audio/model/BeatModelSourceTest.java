package dev.t1m3.qplayer.audio.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>拍点模型的来源与「不进包」</b>：清单那一行必须与 folia 的权威清单逐字一致，
 * 镜像顺序必须照抄，而<b>权重绝不能出现在成品里</b>。
 *
 * <h2>为什么这几条要单独一个测试类</h2>
 *
 * <p>它们不是算法测试，是<b>产品要求</b>的回归闸门：
 *
 * <ul>
 *   <li>用户对「硬算的 BPM 不够好」的判断，落点就是清单里那一行；
 *       换错一个字节的哈希不会报错——只会在下载完之后安静地用上一份
 *       「能加载、自信地回答、而答案是错的」权重。</li>
 *   <li>权重必须由用户按需下载。<b>一旦它们进了安装包，「按需下载」这件事
 *       也就不成立了</b>（能下载的前提是本来没有），而且每个平台的构建时长、
 *       CI 产物、卸载再装全都要为 191 MB 付账。</li>
 * </ul>
 *
 * <p>目录级/打包级的扫描在 {@code ModelPackagingTest} 里（那一份扫得更全）；
 * 这里只钉<b>拍点这条路自己</b>的那几件：清单那一行、镜像顺序、
 * 以及「运行期 classpath 上拿不到那个权重」。
 */
public class BeatModelSourceTest {

    /**
     * 清单里拍点那一行必须与 folia 的 {@code shared/modelManifest.json} 逐字一致。
     *
     * <p>尤其盯住 {@code sha256} 与 {@code bytes}：QPlayer 早先照 Sonalis 的清单
     * 接过一次，那一批的 {@code beat_this.onnx} 是<b>另一个导出</b>
     * （83162650 字节 / {@code 5f810deb…}），与 folia 钉的这份不是同一个文件。
     */
    @Test
    public void theBeatRowMatchesFoliaManifest() {
        ModelManifest manifest = ModelManifest.embedded();
        ModelManifestEntry beat = manifest.require(BeatModels.PREFERRED_ID);

        assertEquals("beat_this.onnx", beat.file);
        assertEquals(83_077_778L, beat.bytes);
        assertEquals("c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd",
                beat.sha256);
        assertEquals("MIT", beat.license);
        assertEquals("https://github.com/mosynthkey/beat_this_cpp", beat.upstream);
        assertEquals("这条清单项打开的能力", "beatGrid", beat.enables);
        assertTrue("note 要写清是哪一份导出", beat.note.contains("Beat This!"));
    }

    /**
     * 镜像顺序必须照抄 folia：hf-mirror 第一（中国大陆唯一稳定可达的那条路），
     * 自家 GitHub release 最后兜底。
     *
     * <p>顺序在这里不是风格问题：{@link ModelDownloads} 是<b>按顺序试、
     * 第一个字节匹配哈希的胜出</b>，所以顺序直接决定哪条路先被走。
     */
    @Test
    public void mirrorsKeepFoliasOrder() {
        List<String> mirrors = ModelManifest.embedded().mirrors();
        assertEquals(3, mirrors.size());
        assertTrue(mirrors.get(0),
                mirrors.get(0).startsWith("https://hf-mirror.com/HUAI4236/folia-models/"));
        assertTrue(mirrors.get(1),
                mirrors.get(1).startsWith("https://huggingface.co/HUAI4236/folia-models/"));
        assertTrue(mirrors.get(2),
                mirrors.get(2).startsWith("https://github.com/AZURE-HUAI/folia-models/"));

        // 每条模板都要能被展开成具体文件的地址（没有 {file} 的模板会被解析期挡掉）
        ModelManifestEntry beat = ModelManifest.embedded().require(BeatModels.PREFERRED_ID);
        for (String template : mirrors) {
            String url = ModelManifest.expand(template, beat);
            assertTrue(url, url.endsWith("/beat_this.onnx"));
            assertFalse(url, url.contains(ModelManifest.FILE_PLACEHOLDER));
        }
    }

    /**
     * <b>权重不在运行期 classpath 上。</b>
     *
     * <p>这一条是「不许打包」里最要紧的那条：它不问「哪个目录里有没有」，
     * 而是问「运行的时候能不能拿到」——那正是「打包进去」的定义。
     * 换一个构建插件把权重塞进 resources，这条一样会红。
     */
    @Test
    public void theDeclaredWeightIsNotOnTheRuntimeClasspath() {
        ModelManifest manifest = ModelManifest.embedded();
        for (ModelManifestEntry entry : manifest.models()) {
            assertNull("清单里的 /models/" + entry.file + " 竟然在 classpath 上——权重被打包了",
                    ModelManifest.class.getResource("/models/" + entry.file));
        }
    }

    /**
     * 全量模型那份 {@code .part} 也不例外：半成品同样不许进包。
     *
     * <p>写这条是因为「权重不进包」最容易漏掉的形式是<b>残留的半成品</b>——
     * 开发时下到一半的 {@code beat_this.onnx.part} 落在资源目录里，
     * 打包脚本不会觉得它有什么特别的。
     */
    @Test
    public void noPartFilesOnTheRuntimeClasspathEither() {
        ModelManifest manifest = ModelManifest.embedded();
        for (ModelManifestEntry entry : manifest.models()) {
            assertNull("/models/" + entry.file + ".part 竟然在 classpath 上",
                    ModelManifest.class.getResource("/models/" + entry.file + ".part"));
        }
    }

    /**
     * 拍点这条路<b>只有一个模型</b>：清单里 {@code enables == "beatGrid"} 的条目唯一。
     *
     * <p>它钉的是「没有自动的第二选择」在数据层面的那一半：
     * 多一个 enable 同名能力的条目，就会有人去做两级回退，
     * 而那等于在权威清单之外悄悄换模型。
     */
    @Test
    public void exactlyOneModelEnablesTheBeatGrid() {
        int count = 0;
        String name = null;
        for (ModelManifestEntry e : ModelManifest.embedded().models()) {
            if ("beatGrid".equals(e.enables)) {
                count++;
                name = e.name;
            }
        }
        assertEquals("拍点能力只该有一个模型来源", 1, count);
        assertEquals(BeatModels.PREFERRED_ID, name);
    }

    /** 上线前最后一道自检：清单本身读得出来，且本条目的文件是纯文件名（不是路径）。 */
    @Test
    public void theDeclaredFileIsAPlainFileName() {
        ModelManifestEntry beat = ModelManifest.embedded().require(BeatModels.PREFERRED_ID);
        assertNotNull(beat.file);
        assertTrue(beat.file, beat.file.indexOf('/') < 0 && beat.file.indexOf('\\') < 0);
        assertFalse(beat.file, beat.file.startsWith("."));
    }

    /**
     * 输入/输出张量名必须以<b>实测值</b>写进清单，不能只写一句「128 维 log-mel」。
     *
     * <p>这条是「不要靠猜」在数据层面的落点，盯的是一个真实踩点：<b>folia 那份导出的
     * 输入叫 {@code input_spectrogram}、形状是 {@code [1, -1, 128]}，而本地那份 Sonalis
     * 导出叫 {@code spectrogram}、形状是 {@code [-1, -1, 128]}</b>——同一个模型的两次导出
     * 连输入名都不一样，所以宿主实现<b>不能写死输入名</b>（桌面实现取会话元数据里的第一个
     * 输入名）。名字猜错不会安静地降级，它要么直接失败、要么自信地给出错答案。
     *
     * <p>本条的断言原来在 {@code ModelCatalogTest.tensorNamesAreRecordedInTheCatalog}
     * （以及 {@code riskNotesSurviveParsing} 里「行顺序要落在数据里」那半）。清单合并成
     * 一份之后，<b>取数方式</b>从 {@code ModelCatalog}/{@code ModelSpec} 改成
     * {@link ModelManifest}/{@link ModelManifestEntry}——<b>断言一条没减</b>。
     */
    @Test
    public void tensorNamesAreRecordedInTheManifest() {
        ModelManifest manifest = ModelManifest.embedded();

        ModelManifestEntry beat = manifest.require(BeatModels.PREFERRED_ID);
        assertTrue(beat.input, beat.input.contains("input_spectrogram"));
        assertTrue(beat.input, beat.input.contains("128"));
        assertTrue("必须写明输入名不能写死这件事", beat.input.contains("不能写死"));
        assertTrue(beat.output, beat.output.contains("beat"));
        assertTrue(beat.output, beat.output.contains("downbeat"));

        // 对照物：官方 log-mel 前端的两个张量名是 MelFrontend 参数标定的依据
        ModelManifestEntry mel = manifest.require("mel_spectrogram");
        assertTrue(mel.input, mel.input.contains("audio_pcm"));
        assertTrue(mel.output, mel.output.contains("mel_spectrogram"));

        // 四轨的行顺序：猜错不会报错，只会把鼓当人声往外送
        ModelManifestEntry demucs = manifest.require("htdemucs");
        assertTrue("htdemucs 的输出字段必须写清行顺序的出处",
                demucs.output.contains("drums") && demucs.output.contains("vocals"));
        assertTrue("体积风险要如实记下来（483 MB 的峰值）", demucs.note.contains("483"));
    }
}
