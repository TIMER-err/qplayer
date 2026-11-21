package dev.t1m3.qplayer.audio.mashup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * <b>{@link TrackProfile} 的磁盘缓存</b>（mashup 第二阶段）。
 *
 * <h2>纪律</h2>
 *
 * <ul>
 *   <li><b>键是内容</b>（本地文件 sha256、远端去 query 的 URL），不是文件名——
 *       改了标签、换了路径都不该让缓存失效，换了<b>内容</b>必须失效；</li>
 *   <li><b>读永不抛</b>：坏文件、半截文件、旧版本、少字段，一律当"没有缓存"返回
 *       {@code null}（{@code BeatGridSidecar} 同一条纪律，连 {@code LinkageError}
 *       都要接住——裁剪过的包里曾经因为少一个类把整条准备线程打死）；</li>
 *   <li><b>写是原子的</b>：先写同目录的临时文件再 rename，断电/崩溃只会留下一个临时文件，
 *       不会留下一个"看起来能读、实际半截"的档案。</li>
 * </ul>
 */
public final class ProfileStore {

    private final Path dir;

    /** 指定目录。目录不存在时会在第一次写时创建。 */
    public ProfileStore(Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("档案目录不能为空");
        }
        this.dir = dir;
    }

    /** 默认位置：{@code <cache>/mashup/profile}（可重建、可淘汰，不该占用户可见目录）。 */
    public static ProfileStore inCache() {
        return new ProfileStore(
                dev.t1m3.qplayer.store.AppDirs.cacheDir().resolve("mashup").resolve("profile"));
    }

    /** 档案目录。 */
    public Path dir() {
        return dir;
    }

    /** 某个键对应的文件（文件名是键的 sha256，避免 URL 里的字符进文件名）。 */
    public Path fileFor(String key) {
        return dir.resolve(sha256(key) + ".profile");
    }

    /** 读；没有或读不出来返回 {@code null}。 */
    public TrackProfile read(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            Path file = fileFor(key);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            TrackProfile profile = TrackProfile.parse(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (profile == null || !key.equals(profile.key)) {
                return null;                    // 内容对不上 ⇒ 当没有（不猜）
            }
            return profile;
        } catch (RuntimeException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写；失败返回 {@code false}（不抛——缓存写不进去不该让整个生成失败）。 */
    public boolean write(TrackProfile profile) {
        if (profile == null) {
            return false;
        }
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "profile-", ".tmp");
            Files.write(tmp, profile.toText().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, fileFor(profile.key),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            deleteQuietly(tmp);
            return false;
        }
    }

    /** 删掉某个键的档案（"清空缓存"与失效测试用）。 */
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(fileFor(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 缓存键。
     *
     * <p>本地路径按<b>文件内容</b>算 sha256（一小时内改过标签也不会误判）；
     * 远端 URL 只去掉 query（token 会过期，带着它做键等于每次都是新键）。
     * 文件读不出来时返回 {@code null}，调用方应当跳过缓存。
     */
    public static String keyOf(String source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int q = source.indexOf('?');
            return "url:" + (q >= 0 ? source.substring(0, q) : source);
        }
        try {
            Path path = java.nio.file.Paths.get(source);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (java.io.InputStream in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 拿不到 SHA-256 是不可能的；真出现了就用一个稳定的弱哈希顶上
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 临时文件清不掉不影响正确性
        }
    }

    @Override
    public String toString() {
        return "ProfileStore{" + dir + "}";
    }
}
