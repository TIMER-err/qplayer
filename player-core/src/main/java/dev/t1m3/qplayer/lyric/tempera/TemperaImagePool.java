package dev.t1m3.qplayer.lyric.tempera;

import dev.t1m3.qplayer.store.AppDirs;
import io.github.humbleui.skija.Image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凝彩的插图素材池：扫描 {@code <数据目录>/tempera/images/} 下的图片，按种子挑一张摆进
 * 镜头。移植自 folia 的图层图片机制（{@code temperaImageLayer.ts}）——qplayer 没有放置器
 * UI，所以用「往这个文件夹丢图」代替「在画布上拖放」。开启「凝彩插图」后，每镜按种子选
 * 一张、避开上一张，按九宫格带状摆放，随构图一起入场漂移；关掉就回到无插图的纯凝彩。
 *
 * <p>线程模型：只被渲染线程触碰。目录扫描走 stat + 读字节，原生 Image 懒加载并缓存；
 * 目录 mtime 变了才重扫，于是开着凝彩时往文件夹里加图也能在下一镜用上。
 */
public final class TemperaImagePool {

    private Path dir;
    private long dirMtime = -1L;
    private final List<TemperaTypes.LayerImage> pool = new ArrayList<>();
    private final Map<String, Image> cache = new HashMap<>();

    public TemperaImagePool() {
        try {
            String base = AppDirs.base();
            if (base != null && !base.trim().isEmpty()) {
                this.dir = java.nio.file.Paths.get(base).resolve("tempera").resolve("images");
            }
        } catch (Throwable ignored) {
            this.dir = null;
        }
    }

    /** 当前素材列表；目录不存在或为空时返回空表（凝彩照常跑，只是没插图）。 */
    public List<TemperaTypes.LayerImage> list() {
        if (dir == null) return pool;
        try {
            long mtime = Files.getLastModifiedTime(dir).toMillis();
            if (mtime != dirMtime) {
                rescan();
                dirMtime = mtime;
            }
        } catch (Throwable ignored) {
            // 目录还没建：不报错，等用户建了再扫。
        }
        return pool;
    }

    /** 素材数量（不触发重扫）。 */
    public int size() {
        return pool.size();
    }

    /** 取某 id 对应的原生贴图，懒加载并缓存；失败返回 null。 */
    public Image imageFor(String id) {
        if (id == null) return null;
        Image cached = cache.get(id);
        if (cached != null) return cached;
        TemperaTypes.LayerImage match = null;
        for (TemperaTypes.LayerImage img : pool) {
            if (id.equals(img.id)) { match = img; break; }
        }
        if (match == null || match.path == null) return null;
        try {
            byte[] bytes = Files.readAllBytes(java.nio.file.Paths.get(match.path));
            Image image = Image.makeFromEncoded(bytes);
            if (image != null) cache.put(id, image);
            return image;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 素材目录路径（提示信息用）。 */
    public Path directory() {
        return dir;
    }

    private void rescan() {
        // 清掉消失的 id 的缓存项（已加载的原生对象也一并释放）。
        cache.values().forEach(image -> {
            try {
                image.close();
            } catch (Throwable ignored) {
                // 关不掉就随 GC 走，不该让一帧失败。
            }
        });
        cache.clear();
        pool.clear();
        try {
            if (!Files.isDirectory(dir)) return;
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString().toLowerCase();
                    if (!(name.endsWith(".png") || name.endsWith(".jpg")
                            || name.endsWith(".jpeg") || name.endsWith(".webp"))) continue;
                    TemperaTypes.LayerImage img = new TemperaTypes.LayerImage();
                    img.id = entry.getFileName().toString();
                    img.path = entry.toAbsolutePath().toString();
                    pool.add(img);
                }
            }
        } catch (Throwable ignored) {
            // 扫描失败就当成空池：凝彩继续跑。
        }
    }

    /** 渲染器销毁时释放全部缓存的原生贴图。 */
    public void dispose() {
        cache.values().forEach(image -> {
            try {
                image.close();
            } catch (Throwable ignored) {
                // 同上。
            }
        });
        cache.clear();
        pool.clear();
    }
}
