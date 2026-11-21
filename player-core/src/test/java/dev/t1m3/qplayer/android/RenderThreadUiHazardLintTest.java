package dev.t1m3.qplayer.android;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>「渲染线程不许直接做 UI/Activity 的活」——源码级护栏</b>（2026-10-03）。
 *
 * <h3>为什么需要一条读源码的判据</h3>
 *
 * <p>这类 bug 有一个共同点：<b>只要不在真机上跑，测试就照不到</b>。它是安卓运行时的线程契约，
 * 桌面端（同一个 QML、同一个引擎）完全正常。而这个仓库里已经连着踩了两次：
 *
 * <ol>
 *   <li>{@code AfpFingerprint} 在**没有 Looper 的线程**上 {@code new WebView(...)} →
 *       WebView 内部 {@code new Handler()} 直接 NPE。用户手机上点「识别」看到的就是它：</li>
 * </ol>
 * <pre>
 * java.lang.NullPointerException: Attempt to read from field
 *   'android.os.MessageQueue android.os.Looper.mQueue' on a null object reference
 *   in method 'void android.os.Handler.&lt;init&gt;(android.os.Looper, ...)'
 * </pre>
 * <ol start="2">
 *   <li>{@code pickPlaylistCover} / {@code pickPluginPackage} 直接在渲染线程上调
 *       {@code startActivityForResult}（同类，还没炸但迟早）。</li>
 * </ol>
 *
 * <p>根因是同一个：<b>QML 跑在 qml4j 的 GL 渲染线程上</b>（这一点写在本仓库自己的注释里：
 * {@code QPlayerActivity} 的「Playback control runs on the main thread ... unlike the GL
 * render thread」），而 {@code PlayerController} 的那些 Launcher 钩子正是从 QML 被调的。
 * 于是「凡是碰 Activity / WebView / 系统对话框的活，都必须先回主线程」。
 *
 * <h3>这条判据查什么</h3>
 *
 * <p>只查<b>渲染线程可达</b>的那几处（不是扫全仓库，免得一堆误报要靠白名单养着）：
 *
 * <ul>
 *   <li>{@code QPlayerActivity} 里每一个 {@code controller.setXxx(this::方法)} 指向的实现；
 *       Lambda 形式的（{@code setClipboard(text -> ...)}）就地查；</li>
 *   <li>三个 Launcher 实现类的整份源码：{@code AndroidWatchmanProbe}、
 *       {@code AndroidWebAuthScript}、{@code AfpFingerprint}（都是 WebView 大户）。</li>
 * </ul>
 *
 * <p>规则一句话：<b>方法体里出现危险调用，就必须在它之前出现过主线程回投</b>
 * （{@code runOnUiThread} / {@code main.post} / {@code mainHandler.post} /
 * {@code Looper.myLooper()} 比较 / 本仓库自己的 {@code runOnMainAndWait}）。
 *
 * <p>没在危险名单上的东西（{@code ContentResolver.query}、纯 Java 计算）不受影响——
 * 它们本来就可以在渲染线程跑。
 */
public class RenderThreadUiHazardLintTest {

    /** 必须在主线程做的调用（Activity / WebView / 系统对话框）。 */
    private static final String[] HAZARDS = {
        "startActivityForResult(",
        "new WebView(",
        "Toast.makeText(",
        "requestPermissions(",
        "startActivity(",
    };

    /** 「已经回到主线程了」的标记。 */
    private static final String[] MARSHALS = {
        "runOnUiThread(",
        "main.post(",
        "main.postDelayed(",
        "mainHandler.post(",
        "runOnMainAndWait(",
        "Looper.myLooper()",
    };

    /** 整份源码都要查的 Launcher 实现（WebView 大户）。 */
    private static final String[] WHOLE_FILES = {
        "android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/AndroidWatchmanProbe.java",
        "android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/AndroidWebAuthScript.java",
        "android-shell/app/src/main/java/dev/t1m3/qplayer/android/recognize/AfpFingerprint.java",
    };

    private static final String ACTIVITY =
            "android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java";

    @Test
    public void everyRenderThreadReachableLauncherMarshalsToTheMainThread() throws IOException {
        File root = repoRoot();
        List<String> problems = new ArrayList<String>();

        // ① Activity 里注册的那些 Launcher（`controller.setXxx(this::方法)` 与 Lambda 两种写法）
        String activity = read(new File(root, ACTIVITY));
        int at = 0;
        while ((at = activity.indexOf("controller.set", at)) >= 0) {
            int end = activity.indexOf(';', at);
            if (end < 0) {
                break;
            }
            String registration = activity.substring(at, end + 1);
            at = end + 1;
            // this::method → 去同文件里找那个方法体
            int marker = registration.indexOf("this::");
            if (marker >= 0) {
                int nameAt = marker + "this::".length();
                int nameEnd = nameAt;
                while (nameEnd < registration.length()
                        && (Character.isLetterOrDigit(registration.charAt(nameEnd))
                            || registration.charAt(nameEnd) == '_')) {
                    nameEnd++;
                }
                String method = registration.substring(nameAt, nameEnd);
                String body = methodBody(activity, method);
                if (body == null) {
                    problems.add(ACTIVITY + " 里注册的 " + method + " 是 Launcher，但源码里找不到它的方法体（"
                            + method + "）——改名了就把这条判据一起改，别让它静默失效");
                    continue;
                }
                problems.addAll(check(ACTIVITY + "#" + method, body));
            } else {
                problems.addAll(check(ACTIVITY + " 的 " + registration.trim(), registration));
            }
        }

        // ② 三个 WebView 大户的整份源码
        for (String path : WHOLE_FILES) {
            problems.addAll(check(path, read(new File(root, path))));
        }

        assertTrue("渲染线程可达的代码里出现了「必须在主线程做的活」而没有先回主线程——\n"
                        + "这类 bug 在桌面端与单测里永远看不出来，只会在真机上炸（用户 2026-10-03 "
                        + "连着碰到两次：识曲 WebView 的 Handler NPE、选图/选插件包的 "
                        + "startActivityForResult）。\n\n"
                        + String.join("\n", problems)
                        + "\n\n修法：把那段活包进 runOnUiThread(...)（Activity 里）或 "
                        + "main.post(...)（自己持有主线程 Handler 时）。",
                problems.isEmpty());
    }

    /** 一段源码里：危险调用之前必须先出现过主线程回投。 */
    private static List<String> check(String where, String source) {
        List<String> problems = new ArrayList<String>();
        for (String hazard : HAZARDS) {
            int hazardAt = source.indexOf(hazard);
            if (hazardAt < 0) {
                continue;
            }
            int marshalAt = -1;
            for (String marshal : MARSHALS) {
                int found = source.indexOf(marshal);
                if (found >= 0 && (marshalAt < 0 || found < marshalAt)) {
                    marshalAt = found;
                }
            }
            if (marshalAt < 0 || marshalAt > hazardAt) {
                problems.add("  · " + where + "：`" + hazard + "` 前面没有主线程回投"
                        + "（" + Arrays.toString(MARSHALS) + " 一个都没有，或出现在它之后）");
            }
        }
        return problems;
    }

    /**
     * 从源码里抠出某个方法的方法体（按花括号配对；跳过字符串里的花括号足够用——
     * 这些方法里没有带花括号的字符串字面量）。
     */
    static String methodBody(String source, String name) {
        String needle = " " + name + "(";
        int at = source.indexOf(needle);
        while (at >= 0) {
            int brace = source.indexOf('{', at);
            if (brace < 0) {
                return null;
            }
            // 声明与 `{` 之间不该有分号（那是调用而不是定义）
            String between = source.substring(at + needle.length(), brace);
            if (between.indexOf(';') < 0) {
                int depth = 0;
                for (int i = brace; i < source.length(); i++) {
                    char c = source.charAt(i);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return source.substring(brace, i + 1);
                        }
                    }
                }
            }
            at = source.indexOf(needle, at + 1);
        }
        return null;
    }

    private static String read(File file) throws IOException {
        assertTrue("找不到源码：" + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 从 {@code user.dir} 往上找仓库根（与 {@code AndroidAutomixWiringTest} 同一套做法）。 */
    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "android-shell").isDirectory()
                    && new File(dir, "player-core").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        fail("找不到仓库根目录（从 " + System.getProperty("user.dir") + " 往上找过）");
        return null;
    }
}
