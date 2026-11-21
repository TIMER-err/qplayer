package dev.t1m3.qplayer.audio.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 内嵌清单的形状与内容。
 *
 * <p>这份测试的用处不在「代码能跑」，而在<b>钉住数字</b>：体积与 sha256 是
 * 「手上这份文件是不是那一份」的唯一判据，抄错一位就会让一个正确的文件被拒绝
 * （或者更糟：一个错的被采用）。所以它们在这里以字面量的形式再写一遍——
 * 这正是 folia 把哈希写进清单注释里的同一种做法：能让两个人独立核对。
 */
public class ModelManifestTest {

    @Test
    public void embeddedManifestPinsFoliaHashes() {
        ModelManifest manifest = ModelManifest.embedded();

        // **逐条列出**而不是数个数：加模型时必须在这里显式加一行，
        // 这样「清单里悄悄多了一份权重」不会溜过去（它的后果是安装包外的又一次下载）。
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (ModelManifestEntry e : manifest.models()) {
            names.add(e.name);
        }
        assertEquals(java.util.Arrays.asList("beat_this", "htdemucs"), names);
        // 2026-10-01：生成式（ACE-Step）整套删除，清单里那六条 ace_step_* 随之删掉。
        // 这一串是**逐条列出**的，删/加模型都必须在这里显式改一行——「清单里悄悄多了一份
        // 权重」不会溜过去（后果是安装包外又一次下载）。

        ModelManifestEntry beat = manifest.require("beat_this");
        assertEquals("beat_this.onnx", beat.file);
        assertEquals(83077778L, beat.bytes);
        assertEquals("c5c1466e08abdb03fdeb50668a06f244b787d564c212490482231a9cfbe9ccbd",
                beat.sha256);
        assertEquals("MIT", beat.license);
        assertEquals("https://github.com/mosynthkey/beat_this_cpp", beat.upstream);
        assertEquals("beatGrid", beat.enables);

        ModelManifestEntry demucs = manifest.require("htdemucs");
        assertEquals("htdemucs.onnx", demucs.file);
        assertEquals(108644650L, demucs.bytes);
        assertEquals("099b5be76c1f6922124d07f850250f39d1f33f254a0b8cc90f4ec0dfd0912329",
                demucs.sha256);
        assertEquals("MIT", demucs.license);
        assertEquals("https://huggingface.co/itamiArika/htdemucs-int8-memory", demucs.upstream);
        assertEquals("stems", demucs.enables);
    }

    /**
     * htdemucs 那一条必须说清它是<b>哪一版</b>：fp16 权重 + folia 把焊死的窗口砍到
     * 3.9 秒的那一版。同一份权重在不同窗口下峰值内存差 300MB，而界面上要显示的
     * 「来源」如果含糊其辞，出事时没人能判断用户手上是哪种。
     */
    @Test
    public void htdemucsNoteNamesTheVersionAndTheHalvedSegment() {
        String note = ModelManifest.embedded().require("htdemucs").note;
        assertTrue("note 应当写明 fp16 权重：" + note, note.contains("fp16"));
        assertTrue("note 应当写明被砍到 3.9 秒的窗口：" + note, note.contains("3.9"));
    }

    @Test
    public void mirrorsAreOrderedWithTheMainlandReachableOneFirst() {
        ModelManifest manifest = ModelManifest.embedded();
        List<String> mirrors = manifest.mirrors();

        assertEquals(3, mirrors.size());
        // hf-mirror 第一：它是中国大陆唯一稳定可达的一条，而对别人也同样能用
        assertTrue(mirrors.get(0), mirrors.get(0).startsWith("https://hf-mirror.com/"));
        assertTrue(mirrors.get(1), mirrors.get(1).startsWith("https://huggingface.co/"));
        assertTrue(mirrors.get(2), mirrors.get(2)
                .startsWith("https://github.com/AZURE-HUAI/folia-models/releases/download/"));
        for (String mirror : mirrors) {
            assertTrue("镜像必须是 https：" + mirror, mirror.startsWith("https://"));
            assertTrue("镜像模板必须有 " + ModelManifest.FILE_PLACEHOLDER + "：" + mirror,
                    mirror.indexOf(ModelManifest.FILE_PLACEHOLDER) >= 0);
        }

        ModelManifestEntry beat = manifest.require("beat_this");
        assertEquals("https://hf-mirror.com/HUAI4236/folia-models/resolve/main/beat_this.onnx",
                ModelManifest.expand(mirrors.get(0), beat));
    }

    @Test
    public void manualFallbackCarriesItsOwnExtractionCode() {
        ModelManifest manifest = ModelManifest.embedded();
        List<ModelManifest.ManualLink> links = manifest.manualLinks();

        assertFalse("手动兜底不能是空的：空链接比没有链接更糟", links.isEmpty());
        for (ModelManifest.ManualLink link : links) {
            assertFalse("链接要有标签", link.label.isEmpty());
            assertTrue("链接要是 https：" + link.url, link.url.startsWith("https://"));
            assertFalse("提取码跟着链接走，不能是空的", link.code.isEmpty());
        }
    }

    /** 只有认得的名字能取到东西；取不到时 {@code require} 要抛而不是返回 null。 */
    @Test
    public void unknownNameIsRejectedLoudly() {
        ModelManifest manifest = ModelManifest.embedded();
        assertNull(manifest.get("nope"));
        try {
            manifest.require("nope");
            fail("清单里没有的名字必须抛");
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.UNKNOWN, e.kind());
        }
    }

    @Test
    public void sizeTextIsHumanReadable() {
        assertEquals("79.2 MB", ModelManifest.embedded().require("beat_this").sizeText());
        assertEquals("103.6 MB", ModelManifest.embedded().require("htdemucs").sizeText());
    }

    // ---- 坏清单必须在读取时就挡住 -------------------------------------------

    @Test
    public void sha256MustBeSixtyFourHexDigits() {
        assertCatalogRejects("{\"mirrors\":[\"https://x/{file}\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"a.onnx\",\"bytes\":1,\"sha256\":\"abc\","
                + "\"license\":\"MIT\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void bytesMustBePositive() {
        assertCatalogRejects("{\"mirrors\":[\"https://x/{file}\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"a.onnx\",\"bytes\":0,\"sha256\":\"" + hex64() + "\","
                + "\"license\":\"MIT\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void mirroredTemplateMustCarryTheFilePlaceholder() {
        // 没有占位符的模板会把同一个文件当成每个模型发过来。哈希那一关当然会挡住，
        // 但那是下完 108MB 之后的事——这种错在读取时就该报出来。
        assertCatalogRejects("{\"mirrors\":[\"https://x/one-file\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"a.onnx\",\"bytes\":1,\"sha256\":\"" + hex64() + "\","
                + "\"license\":\"MIT\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void plainHttpMirrorIsRefused() {
        assertCatalogRejects("{\"mirrors\":[\"http://x/{file}\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"a.onnx\",\"bytes\":1,\"sha256\":\"" + hex64() + "\","
                + "\"license\":\"MIT\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void duplicateNamesAreRefused() {
        String entry = "{\"name\":\"a\",\"file\":\"FILE\",\"bytes\":1,\"sha256\":\"" + hex64()
                + "\",\"license\":\"MIT\",\"upstream\":\"u\"}";
        assertCatalogRejects("{\"mirrors\":[\"https://x/{file}\"],\"models\":["
                + entry.replace("FILE", "a.onnx") + "," + entry.replace("FILE", "b.onnx") + "]}");
    }

    @Test
    public void fileMustBeAPlainName() {
        assertCatalogRejects("{\"mirrors\":[\"https://x/{file}\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"../a.onnx\",\"bytes\":1,\"sha256\":\"" + hex64()
                + "\",\"license\":\"MIT\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void emptyModelListIsRefused() {
        assertCatalogRejects("{\"mirrors\":[],\"models\":[]}");
    }

    @Test
    public void licenseAndUpstreamAreRequired() {
        // 没有 license 的条目不能进清单：能不能托管、能不能打包取决于它
        assertCatalogRejects("{\"mirrors\":[\"https://x/{file}\"],\"models\":["
                + "{\"name\":\"a\",\"file\":\"a.onnx\",\"bytes\":1,\"sha256\":\"" + hex64()
                + "\",\"upstream\":\"u\"}]}");
    }

    @Test
    public void hostOfKeepsOnlyTheHost() {
        assertEquals("hf-mirror.com",
                ModelManifest.hostOf("https://hf-mirror.com/HUAI4236/folia-models/resolve/main/a.onnx"));
        assertEquals("nonsense", ModelManifest.hostOf("nonsense"));
    }

    /** 假的取字节实现本身也要能被信任：它算的哈希必须与 {@link ModelDownloads#hashFile} 一致。 */
    @Test
    public void fakeBytesHashTheSameWayTheRealOneDoes() throws Exception {
        byte[] body = FakeModelFetcher.bytes(64, 7);
        java.io.File file = java.io.File.createTempFile("qplayer-model-test", ".bin");
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(body);
            out.close();
            assertEquals(FakeModelFetcher.sha256(body), ModelDownloads.hashFile(file));
        } finally {
            file.delete();
        }
    }

    /**
     * <b>对照物钉在这里，而且它不该混进「要下载给用户的权重」那一列。</b>
     *
     * <p>{@code mel_spectrogram} 是 QPlayer 自己留的（<b>不在 folia 的清单里</b>）：
     * 它是 Beat This! 官方 log-mel 前端的导出，纯 Java 的 {@code MelFrontend} 与
     * {@code MelParams.beatThis()} 的参数标定靠它做权威对照——前端参数与训练时不一致时
     * 推理不报错、只会安静地吐错拍点，而它是唯一能拿来做这次核对的参照物。
     *
     * <p>两条断言各钉一半：<b>数据在</b>（体积、哈希、来源），以及<b>位置对</b>
     * （它在 {@code reference} 里而不在 {@code models} 里——设置页那一列就是
     * {@code models()}，对照物不该让用户多看到一个「下载 264 KB 的对照文件」的行，
     * 它也不 gate 任何能力）。合并清单之前这份数据在 {@code models.json} 里，
     * 是**第二个条目数组**；现在它还在，只是没有第二份文件了。
     */
    @Test
    public void theReferenceEntryIsPinnedAndStaysOutOfTheDownloadList() {
        ModelManifest manifest = ModelManifest.embedded();
        ModelManifestEntry mel = manifest.require("mel_spectrogram");

        assertEquals("mel_spectrogram.onnx", mel.file);
        assertEquals(270742L, mel.bytes);
        assertEquals("fdd59e65c515331308e4c8841edf99972deca646bdf6197744c2a5b7755e3de9",
                mel.sha256);
        assertEquals("MIT", mel.license);
        assertEquals("https://github.com/danigb/beat-this-rs", mel.upstream);
        assertEquals("对照物不 gate 任何能力", "", mel.enables);
        assertTrue("note 要写明它不在 folia 的清单里：" + mel.note,
                mel.note.contains("不在 folia 的清单里"));

        // 它不在那三条镜像上，所以自带地址；按序试、第一个哈希匹配者胜的规矩对它照样成立
        assertEquals("自带两条地址", 2, mel.urls.size());
        assertTrue(mel.urls.get(0), mel.urls.get(0).startsWith("https://cdn.jsdelivr.net/"));
        assertTrue(mel.urls.get(1), mel.urls.get(1).startsWith("https://fastly.jsdelivr.net/"));
        assertTrue(mel.urls.get(0), mel.urls.get(0).endsWith("/mel_spectrogram.onnx"));

        // 位置：设置页那一列里没有它（对照物不该出现在「按需下载」那一列）
        for (ModelManifestEntry e : manifest.models()) {
            assertFalse("对照物混进了要按需下载的那一列：" + e.name,
                    "mel_spectrogram".equals(e.name));
        }
        assertFalse(manifest.references().isEmpty());
        assertEquals("allEntries 是两列之和", manifest.models().size() + 1,
                manifest.allEntries().size());
    }

    private static void assertCatalogRejects(String json) {
        try {
            ModelManifest parsed = ModelManifest.parse(json);
            fail("这份清单应当被拒绝，却解析出了：" + parsed);
        } catch (ModelException e) {
            assertEquals(ModelException.Kind.CATALOG, e.kind());
            assertNotNull(e.getMessage());
        }
    }

    private static String hex64() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            sb.append('a');
        }
        return sb.toString();
    }
}
