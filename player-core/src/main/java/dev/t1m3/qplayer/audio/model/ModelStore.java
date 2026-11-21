package dev.t1m3.qplayer.audio.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 模型权重在本地的那点事：<b>怎么知道手上这份是对的</b>、<b>残留怎么清</b>，
 * 以及把「下回来」这件事收在一个入口上。
 *
 * <h2>这个类不再是「又一份 store」</h2>
 *
 * <p>本轮之前这里自带一套清单（{@code models.json}）、一套 {@code ModelSpec} 与
 * 一套下载器，那是照 Sonalis 的 {@code store.rs} 移植的。现在<b>清单只有一份</b>
 * （folia 形状的 {@code /models/model-manifest.json}，由 {@link ModelManifest} 解析），
 * <b>下载也只有一份</b>（{@link ModelDownloads}），<b>目录也只有一处</b>
 * （{@link dev.t1m3.qplayer.store.AppDirs#modelsDir()}，用户在设置里能改）。
 * 本类只留下这几件不属于上面任意一份的事：
 *
 * <ul>
 *   <li>{@link #hashFile} / {@link #verify} —— 「读完整份文件算 sha256」，
 *       与 {@link ModelDownloads#present} 那次 {@code stat} 是<b>两个不同的问题</b>
 *       （有没有 vs 对不对），见下。</li>
 *   <li>{@link #cleanStaleParts} / {@link #spawnStalePartCleanup} ——
 *       清掉中断的下载留下的 {@code <file>.part} 残骸。</li>
 *   <li>{@link #install} —— 把「下回来」收成 store 上的一个入口，转发给
 *       {@link ModelDownloads}。镜像按序试、第一个哈希匹配的胜出、
 *       写 {@code <file>.part} 再改名、校验不过绝不采用、把用了哪个镜像记下来——
 *       <b>这五条都由那一个实现负责</b>，这里只是让拍点这条路与 htdemucs 那条路
 *       走同一个 store，不再各写一份。</li>
 * </ul>
 *
 * <h2>「有文件」与「文件是对的」必须分开</h2>
 *
 * <ul>
 *   <li>{@link #isPresent} 只做一次 {@code stat}（顺带比大小）。它回答
 *       「磁盘上有没有权重」——这是<b>唯一</b>该被拿去做功能开关的问题
 *       （{@link ModelAvailability} 问的就是它）。</li>
 *   <li>{@link #verify} 读完整份文件算 sha256。它回答「手上这份对不对」。</li>
 * </ul>
 *
 * <p>合成一件的后果很具体：要么每次判断「有没有」都读 83 MB，要么把「有文件」
 * 当成「文件是对的」去承诺一个功能。
 *
 * <h2>为什么「下载成功」不等于「能用」</h2>
 *
 * <p>这是 folia 清单里用代价换来的那句话，本类的注释里也留一份：
 * <b>The hash is the point, not the URLs. 'Downloaded successfully' and 'usable' are
 * different claims… A truncated or mirror-substituted ONNX does not fail loudly - it
 * loads and answers confidently and wrongly, which for a beat grid means every
 * transition lands off the beat with nothing in the log to say why.</b>
 *
 * <p>译：重点是哈希，不是 URL。「下载成功」与「能用」是两句不同的话。被截断或被
 * 镜像替换过的 ONNX 不会响亮地失败——它会加载、自信地回答、而答案是错的；
 * 对拍点模型来说，后果是每次过渡都落在拍外，而日志里什么都看不出来。
 */
public final class ModelStore {

    /**
     * {@code .part} 残骸的判定阈值（毫秒）。
     *
     * <p><b>24 小时是刻意保守的取值。</b> {@code .part} 是「正在下载」的中间态：
     * 正常一次下载几分钟就结束，跨过一天还没被改名的，只可能是被中断或被杀的
     * 进程留下的。反过来，阈值定小了（比如几分钟）会碰上「别人正在写」——
     * 清掉一个正在写的文件等于毁掉那次下载，而且失败信息只会出现在对方那里。
     * 清理是顺手做的事，宁可留着。
     *
     * <p>下载路径从不从 {@code .part} 续传（{@link ModelDownloads} 明确不做断点续传，
     * 下一次下载会先把它删掉），所以一个没被改名的 {@code .part} 永远不会被谁捡起来
     * 接着用，只是占地方——最多 108 MB 一个。
     */
    public static final long STALE_PART_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    private ModelStore() {
    }

    // ------------------------------------------------------------------
    // 目录与存在性：都转发给共享的那一份，绝不另立一套
    // ------------------------------------------------------------------

    /** 当前模型目录（用户在设置里能改，所以每次现问）。 */
    public static File modelsDir() {
        return ModelDownloads.shared().dir();
    }

    /** 一个条目应该落在哪里。 */
    public static File localPath(ModelManifestEntry entry) {
        return ModelDownloads.shared().fileFor(entry);
    }

    /**
     * 磁盘上有没有这个模型的权重（一次 {@code stat}，顺带比大小）。
     *
     * <p>{@link ModelDownloads#present} 的实现：截断必然改大小，所以「被截断的旧文件」
     * 在这里就已经被排除。它<b>不</b>证明内容是对的——那要 {@link #verify}。
     */
    public static boolean isPresent(ModelManifestEntry entry) {
        return ModelDownloads.shared().present(entry);
    }

    /**
     * 按<b>名字</b>问「现在能不能用它」。名字不在清单里返回 {@code false}。
     *
     * <p>这就是可用性那个布尔，语义与 {@link ModelAvailability#canRun} 相同但
     * <b>现问磁盘</b>：不做任何缓存、不记「上次失败过」。
     * 「本次会话内放弃」那种闩锁在这里是<b>错的</b>——推理返回空既可能是权重不在、
     * 也可能是进程重启掐断了在途请求，两者都会自愈，所以唯一该做的就是重新问磁盘。
     *
     * <p>调用方要拿它去做界面上的承诺时，用 {@link ModelAvailability#canRun}
     * （那份带一次 {@code refresh}，避免每帧都 stat）。
     */
    public static boolean available(String name) {
        ModelDownloads downloads = ModelDownloads.shared();
        ModelManifestEntry e = downloads.manifest().get(name);
        return e != null && downloads.present(e);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * 手上这份<b>是不是清单里那一份</b>：先比大小（一次 stat 就挡掉截断），
     * 再读完整份文件算 sha256。
     *
     * <p>文件不存在、大小不对、清单没给校验值——一律 {@code false}。
     * <b>绝不在拿不到校验值的时候返回 true</b>：那把「校验」这件事变成了一句空话。
     *
     * <p>调用时机：界面上的「校验」按钮、以及任何「这一份要当真」的地方。
     * {@link BeatModels#open} <b>不</b>在每次过渡时调它——83 MB 读一遍要一百多毫秒，
     * 而每次过渡都要问两三遍模型；挡住「截断」的是 {@link #isPresent} 那次大小比较，
     * 挡住「替换」的是下载落地前的那道哈希门槛。
     */
    public static boolean verify(ModelManifestEntry entry) {
        if (entry == null) {
            return false;
        }
        File path = localPath(entry);
        if (!path.isFile() || path.length() != entry.bytes) {
            return false;
        }
        String expected = entry.sha256 == null ? "" : entry.sha256.trim();
        if (expected.isEmpty()) {
            return false;
        }
        return hashFile(path).equalsIgnoreCase(expected);
    }

    /**
     * 算一个文件的 sha256（小写十六进制）。
     *
     * <p>分块读：108 MB 的文件不需要整个进内存，峰值内存只多一个 1 MiB 的缓冲。
     */
    public static String hashFile(File path) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw ModelException.io(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在 Java 8 / 安卓 API 26 上是必须支持的算法，走到这里说明运行时不完整
            throw ModelException.io("运行时不支持 SHA-256：" + e.getMessage(), e);
        } finally {
            closeQuietly(in);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 下回来：转发给共享的那一个下载器
    // ------------------------------------------------------------------

    /**
     * 把这个模型的权重下回来（阻塞；83 MB 在本机实测 31 秒）。
     *
     * <p><b>行为全部由 {@link ModelDownloads#download} 负责</b>，因为那五条要求
     * （镜像按顺序试、第一个字节匹配哈希的胜出、写 {@code <file>.part} 再改名、
     * 校验不过绝不采用、把用了哪个镜像记下来）只该有一份实现——写两份的话，
     * 拍点这条路与 htdemucs 那条路迟早会在「校验」上不一致，而两份实现不一致的
     * 表现方式是一个「自信地给出错误答案」的拍点。
     *
     * <p>本方法存在的意义只有一个：<b>让调用方不必知道「下载器在哪」</b>——
     * 拍点模型与将来的分轨模型走的是同一个入口。返回的 {@link ModelDownloads.Snapshot}
     * 里有状态、字节数、进度、成功的镜像主机与失败原因，界面要什么有什么。
     *
     * <p>调用方应当放在后台线程里；界面则订阅 {@code ModelDownloads.Listener}
     * 拿进度。下完之后 {@link ModelAvailability} 由那边自己刷新（可用性就此自愈）。
     */
    public static ModelDownloads.Snapshot install(ModelManifestEntry entry) {
        if (entry == null) {
            throw ModelException.unknown("null");
        }
        return ModelDownloads.shared().download(entry);
    }

    // ------------------------------------------------------------------
    // 残留清理
    // ------------------------------------------------------------------

    /** 启动时顺手扫一次 {@code .part} 残骸的清理动作。 */
    public interface PartCleaner {
        /** @return 删掉的个数；出错时抛异常（调用方会吞掉） */
        int clean(File dir);
    }

    /**
     * 清理 {@code dir} 下陈旧的 {@code .part} 残骸，返回删掉的个数。
     *
     * <p>只认 <b>{@code .part} 结尾的普通文件</b>，且只删修改时间超过
     * {@link #STALE_PART_AGE_MILLIS} 的。目录不存在、时间取不到、删不掉——
     * 一律留着：这是清理入口，不是校验入口，它的失败不该影响任何正事，
     * 它更不该误删正在被写的文件。
     */
    public static int cleanStaleParts(File dir) {
        return cleanPartsAsOf(dir, STALE_PART_AGE_MILLIS, System.currentTimeMillis());
    }

    /**
     * 清理的真正实现。
     *
     * <p>{@code now} 单独作为参数传进来是为了能在测试里把「现在」挪到未来：
     * 否则要造一个「足够旧」的文件就得去改文件的修改时间，
     * 而那要么引一个新依赖，要么依赖较新工具链才有的 API。
     */
    static int cleanPartsAsOf(File dir, long olderThanMillis, long now) {
        if (dir == null) {
            return 0;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            // 目录不存在（或不是一个目录）不算错：没下过模型自然也没有残留
            return 0;
        }

        int removed = 0;
        for (File path : entries) {
            // `isFile()` 会跟着符号链接走，那样一个指向别处同名文件的链接就能让
            // 删除删到别的地方去。Java 没有 symlink_metadata 的对应物，
            // 所以下面用 canonical path 再确认一次它落在 dir 之内。
            if (!path.isFile()) {
                continue;
            }
            if (!path.getName().endsWith(".part")) {
                continue;
            }
            if (!isInside(dir, path)) {
                continue;
            }
            long modified = path.lastModified();
            if (modified <= 0L) {
                continue;
            }
            // 时钟回拨会让「现在」早于修改时间，差值为负——那种情况下这个文件
            // 一点也谈不上「旧」，跳过。
            long age = now - modified;
            if (age < olderThanMillis) {
                continue;
            }
            // 删不掉（多半正被别的进程占着，也就是「有人正在下」）不算错误：
            // 留着下次再说，这正是「宁可留着」在代码里的样子。
            if (path.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /** {@code path} 解析后是否落在 {@code dir} 之内（防符号链接指到别处）。 */
    private static boolean isInside(File dir, File path) {
        try {
            String d = dir.getCanonicalPath();
            String p = path.getCanonicalPath();
            return p.startsWith(d.endsWith(File.separator) ? d : d + File.separator);
        } catch (IOException e) {
            // 解析不出来（路径里有环、权限不足）→ 不删
            return false;
        }
    }

    /**
     * 启动时顺手扫一次 {@code .part} 残骸：<b>丢到后台线程，不阻塞、不报错</b>。
     *
     * <h3>为什么放后台线程</h3>
     *
     * <p>清理要列出整个模型目录并给每个条目取一次元数据。目录大、盘慢
     * （模型目录可能是机械盘或外接盘）、或者目录正被别的程序占着的时候，
     * 这一趟可以拖到几秒。而启动路径上<b>没有任何一件事在等它的结果</b>——
     * 它删的是 24 小时没人碰过的废文件，早一分钟晚一分钟没有区别。
     * 为一个没人等的活让用户等，是纯粹的净损失。
     *
     * <h3>为什么返回 {@code Thread} 而不是结果</h3>
     *
     * <p>它<b>不允许失败</b>，也不允许把失败讲给调用方听：磁盘只读、权限不足、
     * 目录不存在、连线程都开不出来——每一种都必须让启动照常继续。
     * 返回的那个线程只是给测试留一个「等它跑完再断言」的把手，正常调用方直接丢弃
     * 即可（丢弃不是取消：线程自己跑完就结束，也不会把进程留住）。
     *
     * <h3>为什么错误就地吞掉</h3>
     *
     * <p>这是清理入口，不是校验入口：删不掉一个文件的唯一后果是它多留一天。
     * 把它变成启动时的一条错误日志，只会让人去追一个不影响任何功能的东西。
     * 真要看清结果，测的是 {@link #cleanStaleParts}。
     */
    public static Thread spawnStalePartCleanup(File dir) {
        return spawnStalePartCleanupWith(dir, new PartCleaner() {
            @Override
            public int clean(File d) {
                return cleanStaleParts(d);
            }
        });
    }

    /**
     * 同 {@link #spawnStalePartCleanup}，只是清理动作可以注入。
     *
     * <p>这一层单独存在<b>只为可测</b>：真正的清理要文件超过 24 小时才动手，
     * 而「启动时到底有没有人叫它」和「它出错时启动会不会被带倒」这两件事
     * 没法靠等一天来验。测试传一个记账的（或故意出错的）动作进来即可。
     */
    public static Thread spawnStalePartCleanupWith(File dir, final PartCleaner clean) {
        // 起名字是为了排查：一个匿名线程在「进程为什么多活了一瞬间」这类
        // 问题里几乎认不出来，而这个名字直接说明了它是谁。
        Thread t = new Thread(new Runnable() {
            private final File target = dir;
            private final PartCleaner action = clean;

            @Override
            public void run() {
                // 返回值故意丢掉：在这里成功与失败是同一件事
                try {
                    action.clean(target);
                } catch (Throwable ignored) {
                    // 同上：任何失败都不该冒到任何地方
                }
            }
        }, "qplayer-part-cleanup");
        t.setDaemon(true);
        try {
            t.start();
        } catch (RuntimeException e) {
            // 线程开不出来（句柄耗尽）也只是「这次没清成」，与启动无关。
            // 这里**不能**抛：清理是上一个连失败都不该冒泡的地方。
            return null;
        }
        return t;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 关一个只读的资源失败没有任何补救动作，也不该盖掉真正的错误
        }
    }
}
