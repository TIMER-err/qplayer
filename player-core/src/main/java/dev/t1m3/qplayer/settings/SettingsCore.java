package dev.t1m3.qplayer.settings;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;

import dev.t1m3.qplayer.audio.model.ModelAvailability;
import dev.t1m3.qplayer.audio.model.ModelDownloads;
import dev.t1m3.qplayer.audio.model.ModelManifest;
import dev.t1m3.qplayer.audio.model.ModelManifestEntry;
import dev.t1m3.qplayer.audio.model.ModelRow;
import dev.t1m3.qplayer.audio.model.ModelStore;
import dev.t1m3.qplayer.audio.model.StemModels;
import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.i18n.I18n;
import dev.t1m3.qplayer.lyric.skia.Fonts;
import dev.t1m3.qplayer.lyric.skia.LyricCompositor;
import dev.t1m3.qplayer.lyric.skia.LyricConfig;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@code settings} context global: one value store, one set of side effects,
 * one UI, shared by both hosts.
 *
 * <p>The settings page reads {@link #categories()} and {@link #rows(String)} and
 * renders whatever comes back — there is no hand-written row per setting on
 * either side. QML reads a value with {@link #value(String)} inside a binding,
 * which registers the underlying {@link Property} as a dependency (Property.get
 * records the read into the evaluating binding), so generated rows are as
 * reactive as hand-written ones were.
 *
 * <p>What each host still owns: a {@link SettingsStore} (JSON file vs
 * SharedPreferences), the platform id (so desktop-only rows don't show up on
 * Android), defaults that depend on the environment ({@link #setDefault}),
 * actions and live text the page can invoke/show ({@link #registerAction},
 * {@link #registerInfo}), and any extra reaction to a change
 * ({@link #onChange}). Everything else — persistence, clamping, the lyric/font/
 * player-controller wiring — happens here.
 */
public final class SettingsCore extends QObject implements LyricCompositor.SettingsBridge {

    /** Resolved dark flag (mode + system state), read by Main.qml's StyleManager
     *  binding. Derived, never persisted. */
    public final Property<Boolean> resolvedDark = new Property<>(Boolean.FALSE);
    /** Safe-area insets in logical px; Android publishes system-bar/cutout values,
     *  desktop leaves the side and bottom values at 0. */
    public final Property<Double> topInset = new Property<>(0.0);
    public final Property<Double> bottomInset = new Property<>(0.0);
    public final Property<Double> leftInset = new Property<>(0.0);
    public final Property<Double> rightInset = new Property<>(0.0);
    /** Installed font families for the picker dialog. Not persisted. */
    public final Property<List<String>> availableFontFamilies =
            new Property<>(Collections.emptyList());
    /** Desktop sets this when a requested Vulkan backend failed and startup
     *  continued with OpenGL. Non-persistent; Main.qml presents the explanation. */
    public final Property<Boolean> graphicsFallbackNotice = new Property<>(Boolean.FALSE);

    /** Whether the model dialog is open. Like the font picker, the "manageModels"
     *  action only raises a flag and the settings page binds its dialog to it. */
    public final Property<Boolean> modelManagerOpen = new Property<>(Boolean.FALSE);

    /**
     * Bumped whenever a model's on-disk state may have changed (a download
     * progressed or finished, a folder was picked, the dialog was opened).
     *
     * <p>{@link #modelRows()} is a plain method rather than a stored list, so a
     * QML binding reads this revision first and then asks for the rows — the
     * same trick {@link #fontFamily()} uses. Without it a progress reading would
     * never re-evaluate, because nothing the binding read had changed.
     */
    public final Property<Long> modelRevision = new Property<>(0L);

    private final Map<String, SettingSpec> specsByKey = new LinkedHashMap<>();
    private final Map<String, Property<Object>> values = new HashMap<>();
    private final Map<String, List<Consumer<Object>>> hooks = new HashMap<>();
    private final Map<String, Object> defaultOverrides = new HashMap<>();
    private final Map<String, Runnable> actions = new HashMap<>();
    private final Map<String, Supplier<String>> infos = new HashMap<>();

    private List<SettingSpec> specs = Collections.emptyList();
    private SettingsStore store;
    private String platform = SettingSpec.ANY;
    private PlayerController controller;
    private volatile DirectoryPicker directoryPicker;
    /** Plain thread-safe mirror for non-QML consumers such as desktop lyrics. */
    private volatile boolean resolvedDarkSnapshot;
    private boolean systemDark;
    private boolean loaded;

    /**
     * 后台线程把一次 Property 写交回渲染线程的钩子。宿主没装时<b>直接执行</b>
     * （测试与没有界面的宿主）。
     *
     * <p>为什么需要它：下载进度是在工作线程上产生的，而 qml4j 的 {@code Property.set}
     * 会当场通知依赖它的绑定、就地重算绑定表达式——从工作线程上这么干等于在渲染线程
     * 正在遍历的场景上并发写。{@link #attach} 会把它接到宿主的渲染线程队列上
     * （桌面与安卓的 shell 本来每帧都在 {@code pump()}）。
     */
    private volatile Consumer<Runnable> uiPoster;

    /** 模型下载/校验的工作线程。<b>单线程</b>：一次只下一份，避免两条流抢同一个 {@code .part}。 */
    private final ExecutorService modelWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qplayer-models");
        t.setDaemon(true);
        return t;
    });

    // ---- host setup ---------------------------------------------------------

    /** Override a default that only the host knows (a home-relative music folder,
     *  the platform cache directory). Call before {@link #load}. */
    public void setDefault(String key, Object value) {
        defaultOverrides.put(key, value);
    }

    /** Wire the settings that drive playback. Call before {@link #load} so the
     *  controller sees the persisted values as they're seeded. */
    public void attach(PlayerController controller) {
        this.controller = controller;
        // 工作室桥要读写设置（段表进自定义 JSON、模块参数进设置行），把中枢交给控制器
        if (controller != null) {
            controller.attachStudioSettings(this);
            // 播放设备：这次改动是**结构性**的（要重开输出流），不能靠"下次用到时现读"，
            // 所以走 onChange 通知控制器去做。
            controller.attachOutputDeviceSettings(this);
            // **自动接法的风格白名单**（用户 2026-10-09 ✓）：每档一个开关 ✓
            // 汇总成逗号串推进引擎 ✓（没调用它 ⇒ 开关点了也没用 ✗ 这一行就是接通的 ✓）。
            controller.attachAutomixStyleWhitelist(this);
        }
        // 渲染线程队列就在控制器里（两个 shell 每帧都会 pump()），所以不必再让宿主
        // 多接一根线：模型下载的进度回调顺着它回到渲染线程。
        this.uiPoster = controller != null ? controller::runOnRenderThread : null;
    }

    /** Override the render-thread hook (tests, or a host with its own loop). */
    public void setUiPoster(Consumer<Runnable> poster) {
        this.uiPoster = poster;
    }

    /** A button row's handler ({@link SettingSpec#action}). */
    public void registerAction(String id, Runnable r) {
        actions.put(id, r);
    }

    /** Live text for an info row or an action row's subtitle
     *  ({@link SettingSpec#provider}). */
    public void registerInfo(String id, Supplier<String> s) {
        infos.put(id, s);
    }

    /** Extra reaction to a value change, on top of the built-in effects. Fires
     *  after the new value is stored and persisted. */
    public void onChange(String key, Consumer<Object> handler) {
        hooks.computeIfAbsent(key, k -> new ArrayList<>()).add(handler);
    }

    /** 摘掉一个 onChange 监听（2.1.1 的安卓浮窗在销毁时要摘钩子）。 */
    public void removeOnChange(String key, Consumer<Object> handler) {
        List<Consumer<Object>> handlers = hooks.get(key);
        if (handlers == null) return;
        handlers.remove(handler);
        if (handlers.isEmpty()) hooks.remove(key);
    }

    /** Host hook for a platform directory chooser. The host must invoke the
     *  supplied callback on the QML/render thread. Desktop installs this after
     *  its window has been initialized; Android currently has no directory rows. */
    @FunctionalInterface
    public interface DirectoryPicker {
        void pick(String initialPath, Consumer<String> onPicked);
    }

    public void setDirectoryPicker(DirectoryPicker picker) {
        this.directoryPicker = picker;
    }

    /**
     * Seed every value from {@code store} and start persisting writes.
     *
     * @param platform {@link SettingsCatalog#DESKTOP} or
     *                 {@link SettingsCatalog#ANDROID} — filters the row list.
     */
    public void load(SettingsStore store, String platform) {
        this.store = store;
        this.platform = platform;
        migrateLegacyKeys();
        this.specs = new ArrayList<>();
        for (SettingSpec s : SettingsCatalog.specs()) {
            if (!s.appliesTo(platform)) continue;
            specs.add(s);
            specsByKey.put(s.key, s);
            if (s.hasValue()) values.put(s.key, new Property<>(read(s)));
        }
        specs = Collections.unmodifiableList(specs);
        availableFontFamilies.set(sortedFontFamilies());
        registerFontProviders();
        registerModelProviders();
        // 先问一次磁盘：设置页第一帧就该显示真实状态，而不是「全部未下载」闪一下
        // 再纠正。这一趟只有两个 stat，见 ModelAvailability.refresh 的注释。
        ModelAvailability.refresh();
        auditModelsOnce();
        loaded = true;
        applyAll();
    }

    // ---- QML API ------------------------------------------------------------

    /**
     * Tab ids, in declaration order, minus any that this host has no rows for.
     *
     * <p>A category can be entirely platform-specific (桌面歌词 is desktop-only),
     * and a tab that opens onto nothing is worse than no tab. The plugins tab is
     * the one exception: its content is contributed by the page itself rather
     * than by catalog rows, so it stays even while the catalog has none.
     */
    public List<String> categories() {
        List<String> out = new ArrayList<>(SettingsCatalog.CATEGORIES.size());
        for (String category : SettingsCatalog.CATEGORIES) {
            if (SettingsCatalog.PLUGINS.equals(category) || !rows(category).isEmpty()) {
                out.add(category);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The rows of one category, in declaration order. */
    public List<SettingSpec> rows(String category) {
        List<SettingSpec> out = new ArrayList<>();
        for (SettingSpec s : specs) {
            if (!s.hidden && s.category.equals(category)) out.add(s);
        }
        return out;
    }

    /** The cards of one category: consecutive rows sharing a group id, in
     *  declaration order. The page renders one card per group. */
    public List<SettingGroup> groups(String category) {
        List<SettingGroup> out = new ArrayList<>();
        List<SettingSpec> current = null;
        String currentId = null;
        for (SettingSpec s : rows(category)) {
            if (current == null || !s.group.equals(currentId)) {
                current = new ArrayList<>();
                currentId = s.group;
                out.add(new SettingGroup(currentId, current));
            }
            current.add(s);
        }
        return out;
    }

    /** Current value, as a reactive read: called inside a QML binding this
     *  registers the backing Property, so the binding re-evaluates on change. */
    public Object value(String key) {
        Property<Object> p = values.get(key);
        return p != null ? p.get() : null;
    }

    /**
     * 数值设置的<b>活</b>读法：在 QML 绑定里读它，这条绑定就会跟着这个键变。
     *
     * <p>与 {@link #intOf} 只差一处：这里走 {@link #value}（登记绑定依赖），
     * {@code intOf} 走 {@code peek}（宿主侧读，不登记）。<b>活文案必须在绑定里用这一个</b>：
     * {@code SettingsCore.info} 的 provider 是在 QML 绑定的求值过程里被调用的，
     * 只有走 {@code get()} 的那一次读才会把依赖登记上去——用 {@code peek} 的后果是
     * 「用户把「融合期人声」从 DSP 切到 AI，那一行还写着 DSP 时的话」，
     * 而「随设置变」正是活文案存在的全部理由。
     */
    public int intValue(String key) {
        Object v = value(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** Whether this build has the setting at all (desktop-only rows on Android). */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Write from the UI: normalizes (QML hands integers over as Long), clamps a
     *  numeric control to its declared range, persists, then applies. */
    public void setValue(String key, Object raw) {
        SettingSpec spec = specsByKey.get(key);
        Property<Object> p = values.get(key);
        if (spec == null || p == null) return;
        Object v = normalize(spec, raw);
        if (v == null || v.equals(p.peek())) return;
        p.set(v);
        persist(spec, v);
        apply(spec, v);
    }

    /** Stepper -/+ , clamped. Keeps the arithmetic out of every generated row. */
    public void bump(String key, int direction) {
        SettingSpec spec = specsByKey.get(key);
        if (spec == null || !SettingSpec.STEPPER.equals(spec.type)) return;
        setValue(key, intOf(key) + direction * spec.step);
    }

    /** Text shown by an info row / an action row's subtitle. */
    public String info(String provider) {
        Supplier<String> s = infos.get(provider);
        if (s == null) return "";
        try {
            String v = s.get();
            return v != null ? v : "";
        } catch (RuntimeException | LinkageError e) {
            // **provider 抛异常不许把整行带下去**（2026-10-11 ✓ 实测：模型不在磁盘上时
            // `ModelAvailability.canRun()` 会抛 ⇒ QML 绑定崩 ⇒ **行根本创建不出来** ✗
            // ⇒ 设置页那一行直接消失 ✓ 而它正是"告诉用户去下载模型"的那一行 ✗）
            return "";
        }
    }

    /** Run an action row's handler. */
    public void invoke(String action) {
        Runnable r = actions.get(action);
        if (r != null) r.run();
    }

    /** Open the host directory chooser for a PATH setting. Cancelling leaves the
     *  current value untouched. */
    public void pickDirectory(String key) {
        SettingSpec spec = specsByKey.get(key);
        DirectoryPicker picker = directoryPicker;
        if (picker == null || spec == null || !SettingSpec.PATH.equals(spec.type)) return;
        picker.pick(str(key), selected -> {
            if (selected == null || selected.trim().isEmpty()) return;
            setValue(key, selected);
        });
    }

    // ---- Java API -----------------------------------------------------------

    public boolean bool(String key) {
        return Boolean.TRUE.equals(peek(key));
    }

    public int intOf(String key) {
        Object v = peek(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public String str(String key) {
        Object v = peek(key);
        return v instanceof String ? (String) v : "";
    }

    /** Host-side write (not from QML) — same path, so effects still run. */
    public void put(String key, Object value) {
        setValue(key, value);
    }

    private Object peek(String key) {
        Property<Object> p = values.get(key);
        return p != null ? p.peek() : null;
    }

    // ---- dark mode ----------------------------------------------------------

    /** Safe-area insets in logical px (render thread). */
    public void setInsets(double left, double top, double right, double bottom) {
        leftInset.set(left);
        topInset.set(top);
        rightInset.set(right);
        bottomInset.set(bottom);
    }

    /** Desktop only reserves a custom title bar at the top. */
    public void setInsets(double top, double bottom) {
        setInsets(0, top, 0, bottom);
    }

    /** The OS's current dark state; hosts that can observe it live call this on
     *  every change, the others once at startup. */
    public void setSystemDark(boolean dark) {
        if (systemDark == dark && loaded) return;
        systemDark = dark;
        recomputeDark();
    }

    public boolean resolvedDarkValue() {
        return resolvedDarkSnapshot;
    }

    private void recomputeDark() {
        int mode = intOf("darkMode");
        boolean dark = mode == SettingsCatalog.MODE_DARK
                || (mode == SettingsCatalog.MODE_SYSTEM && systemDark);
        resolvedDarkSnapshot = dark;
        resolvedDark.set(dark);
    }

    // ---- LyricCompositor.SettingsBridge -------------------------------------

    @Override public boolean temperaWholeLine() { return bool("temperaWholeLine"); }
    @Override public int temperaGlyphSettleStretch() { return intOf("temperaGlyphSettleStretch"); }
    @Override public boolean temperaImages() { return bool("temperaImages"); }
    @Override public int lyricFontSize() { return intOf("lyricFontSize"); }

    @Override
    public float topInset() {
        Double v = topInset.peek();
        return v != null ? v.floatValue() : 0f;
    }

    @Override
    public boolean lyricBgStatic() {
        return intOf(SettingsCatalog.BG_MODE_KEY) == 1;
    }

    @Override
    public int lyricBgStyle() {
        return intOf(SettingsCatalog.BG_STYLE_KEY);
    }

    // ---- value plumbing -----------------------------------------------------

    private Object read(SettingSpec spec) {
        Object def = defaultOverrides.containsKey(spec.key) ? defaultOverrides.get(spec.key) : spec.def;
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return store.getBool(spec.key, Boolean.TRUE.equals(def));
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                return store.getInt(spec.key, def instanceof Number ? ((Number) def).intValue() : 0);
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
            case SettingSpec.COLOR:
                return store.getString(spec.key, def instanceof String ? (String) def : "");
            default:
                return null;
        }
    }

    private void persist(SettingSpec spec, Object v) {
        switch (spec.type) {
            case SettingSpec.SWITCH:
                store.putBool(spec.key, Boolean.TRUE.equals(v));
                break;
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER:
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN:
                store.putInt(spec.key, v instanceof Number ? ((Number) v).intValue() : 0);
                break;
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
            case SettingSpec.COLOR:
                store.putString(spec.key, v instanceof String ? (String) v : "");
                break;
            default:
                break;
        }
    }

    /** QML writes arrive as Long/Double for numbers and can be anything for a
     *  text field; coerce to the spec's own type and clamp numeric controls. */
    private Object normalize(SettingSpec spec, Object raw) {
        switch (spec.type) {
            case SettingSpec.SWITCH:
                return Boolean.TRUE.equals(raw);
            case SettingSpec.STEPPER:
            case SettingSpec.SLIDER: {
                if (!(raw instanceof Number)) return null;
                int v = ((Number) raw).intValue();
                int clamped = Math.max(spec.min, Math.min(spec.max, v));
                if (SettingSpec.SLIDER.equals(spec.type) && spec.dots && spec.step > 0) {
                    int steps = Math.round((clamped - spec.min) / (float) spec.step);
                    clamped = Math.max(spec.min,
                            Math.min(spec.max, spec.min + steps * spec.step));
                }
                return clamped;
            }
            case SettingSpec.SEGMENTED:
            case SettingSpec.RADIO:
            case SettingSpec.DROPDOWN: {
                if (!(raw instanceof Number)) return null;
                int v = ((Number) raw).intValue();
                int last = Math.max(0, spec.options.size() - 1);
                return Math.max(0, Math.min(last, v));
            }
            case SettingSpec.TEXT:
            case SettingSpec.PATH:
                return raw != null ? raw.toString() : "";
            case SettingSpec.COLOR:
                return normalizeColor(raw);
            default:
                return null;
        }
    }

    /** "#rrggbb", lowercased; anything else (including QML's "#aarrggbb" form and
     *  the picker's reset) becomes empty, i.e. "use this row's automatic default". */
    private static String normalizeColor(Object raw) {
        if (raw == null) return "";
        String hex = raw.toString().trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 8) hex = hex.substring(2);
        if (hex.length() != 6) return "";
        for (int i = 0; i < 6; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return "";
        }
        return "#" + hex.toLowerCase(java.util.Locale.ROOT);
    }

    // ---- side effects -------------------------------------------------------

    /** Push every seeded value into whatever consumes it. Runs once at load, so
     *  a consumer sees the persisted state without the host replaying it. */
    private void applyAll() {
        // Before anything reads a string: every other consumer resolves keys
        // through whatever language is active at that moment.
        applyLanguage();
        recomputeDark();
        applyLyricConfig();
        applyModelsDir();
        Fonts.setSelection(fontSelection());
        applyDiagnosticLog();
        pushToController();
        for (SettingSpec s : specs) {
            if (!s.hasValue()) continue;
            fireHooks(s.key, peek(s.key));
        }
    }

    private void apply(SettingSpec spec, Object v) {
        if (SettingsCatalog.LANGUAGE_KEY.equals(spec.key)) {
            applyLanguage();
        } else if (SettingsCatalog.MODELS_DIR_KEY.equals(spec.key)) {
            applyModelsDir();
        } else if (SettingsCatalog.AUTOMIX_DIAGNOSTIC_LOG_KEY.equals(spec.key)) {
            applyDiagnosticLog();
        } else if (spec.key.startsWith("lyric") && !SettingsCatalog.BG_MODE_KEY.equals(spec.key)) {
            applyLyricConfig();
        } else if ("darkMode".equals(spec.key)) {
            recomputeDark();
        } else if (controller != null) {
            switch (spec.key) {
                case "monet": controller.setMonetEnabled(bool("monet")); break;
                case "mirror": controller.setUpdateMirror(bool("mirror")); break;
                case "highQuality": controller.setHighQualityEnabled(bool("highQuality")); break;
                // 总开关必须立刻生效，不能等下一次造编排器：关掉之后后端手上还留着
                // 上一首的预告，不撤掉的话「关掉之后这一曲的换曲点」照样会做过渡——
                // 那就是「关了还在跑」，比不做更糟。控制器那边负责撤预告。
                case SettingsCatalog.AUTOMIX_ENABLED_KEY:
                    controller.setAutomixEnabled(bool(SettingsCatalog.AUTOMIX_ENABLED_KEY));
        controller.setAutomixTieredSelection(bool(SettingsCatalog.AUTOMIX_TIERED_SELECTION_KEY));
                    pushAutomixTransitionRequested();
                    break;
                // 接法也决定「会不会真的做过渡」：无缝拼接（窗口 0）不会，
                // 而这件事决定控制器要不要替用户预解析/预取下一首（花带宽）。
                case SettingsCatalog.AUTOMIX_MODE_KEY:
                    pushAutomixTransitionRequested();
                    break;
                // ③ 分层取模（同层随机取点）：开关要立刻同步给选点层，
                // 否则设置页拨了没反应（要等重启才生效）
                case SettingsCatalog.AUTOMIX_TIERED_SELECTION_KEY:
                    controller.setAutomixTieredSelection(
                            bool(SettingsCatalog.AUTOMIX_TIERED_SELECTION_KEY));
                    break;
                case "maxCacheSizeMB": controller.setCacheMaxSizeMB(intOf("maxCacheSizeMB")); break;
                case SettingsCatalog.HOME_PLAYLIST_LIMIT_KEY:
                    controller.setHomePlaylistLimit(intOf(SettingsCatalog.HOME_PLAYLIST_LIMIT_KEY));
                    break;
                default: break;
            }
        }
        fireHooks(spec.key, v);
    }

    private void fireHooks(String key, Object v) {
        List<Consumer<Object>> list = hooks.get(key);
        if (list == null) return;
        for (Consumer<Object> h : list) h.accept(v);
    }

    /**
     * 过渡诊断日志开关 → {@link Logger#setVerbose}。
     *
     * <p>为什么这件事落在设置中枢而不是某个宿主：它是<b>全局</b>的日志开关，两端都要生效，
     * 而且要在 {@link #load} 那一刻就按盘上的值定下来（否则第一首的过渡还是老样子）。
     * 与 {@link #applyLanguage} / {@link #recomputeDark} 同类：一个键改变了一个进程级的全局量。
     */
    private void applyDiagnosticLog() {
        Logger.setVerbose(bool(SettingsCatalog.AUTOMIX_DIAGNOSTIC_LOG_KEY));
    }

    private void pushToController() {
        if (controller == null) return;
        controller.setMonetEnabled(bool("monet"));
        controller.setUpdateMirror(bool("mirror"));
        controller.setHighQualityEnabled(bool("highQuality"));
        controller.setAutomixEnabled(bool(SettingsCatalog.AUTOMIX_ENABLED_KEY));
        pushAutomixTransitionRequested();
        controller.setCacheMaxSizeMB(intOf("maxCacheSizeMB"));
        controller.setHomePlaylistLimit(intOf(SettingsCatalog.HOME_PLAYLIST_LIMIT_KEY));
    }

    /**
     * 把「这一次换曲真的会做过渡吗」推给控制器：总开关打开 <b>且</b> 接法不是无缝拼接。
     *
     * <p>控制器拿它来决定要不要为「下一首」预解析直链、预取音频——那是要花用户带宽的，
     * 所以判据必须与「真的会不会过渡」完全一致，不能只看开关。三处的判断
     * （这里、{@code AutomixSettings.factoryFor} 的窗口 0、控制器里的预取）都以
     * 「开关 + 接法」为输入，语法不同但语义是同一个。
     */
    private void pushAutomixTransitionRequested() {
        if (controller == null) return;
        boolean enabled = bool(SettingsCatalog.AUTOMIX_ENABLED_KEY);
        boolean gapless = intValue(SettingsCatalog.AUTOMIX_MODE_KEY)
                == SettingsCatalog.AUTOMIX_MODE_GAPLESS;
        controller.setAutomixTransitionRequested(enabled && !gapless);
    }

    private void applyLanguage() {
        int choice = intOf(SettingsCatalog.LANGUAGE_KEY);
        I18n.instance().setLanguage(
                choice == SettingsCatalog.LANGUAGE_ZH_CN ? "zh_CN"
                : choice == SettingsCatalog.LANGUAGE_EN_US ? "en_US"
                : I18n.systemLanguage());
    }

    private void applyLyricConfig() {
        LyricConfig c = LyricConfig.instance;
        c.lyricFontSize.setValue(intOf("lyricFontSize"));
        int w = Math.max(0, Math.min(LyricConfig.FontWeight.values().length - 1,
                intOf("lyricFontWeight")));
        c.fontWeight.setValue(LyricConfig.FontWeight.values()[w]);
        c.lineSpacing.setValue(intOf("lyricLineSpacing") / 100f);
        c.emphasisScale.setValue(intOf("lyricEmphasisScale") / 100f);
        c.autoLineSpacing.setValue(bool("lyricAutoLineSpacing"));
        c.springPhysics.setValue(bool("lyricSpring"));
        c.melodifyMotion.setValue(bool("lyricMelodifyMotion"));
        c.scaleEmphasis.setValue(bool("lyricScale"));
        c.liftStyle.setValue(Math.max(0, Math.min(1, intOf("lyricLiftStyle"))));
        c.glow.setValue(bool("lyricGlow"));
        c.dropShadow.setValue(bool("lyricShadow"));
        c.linearAnimForPlainLrc.setValue(bool("lyricLinearAnim"));
        c.edgeBlur.setValue(bool("lyricEdgeBlur"));
        Fonts.warmupFromConfig();
    }

    // ---- fonts --------------------------------------------------------------

    /** The font source is stored under its own key rather than as a catalog row:
     *  it's picked from a dialog (bundled / system default / any installed
     *  family), not from a row widget. */
    public static final String FONT_KEY = "fontFamily";

    /** Desktop lyrics' own font source, stored the same way but resolved through
     *  {@link Fonts#get(String, Fonts.Weight, float)} rather than the process-wide
     *  selection. Empty means "follow the main font", which is the default. */
    public static final String DESKTOP_LYRIC_FONT_KEY = "desktopLyricFont";

    public String fontSelection() {
        if (store == null) return "";
        return store.getString(FONT_KEY, migratedFontSelection());
    }

    /** Set from the font picker dialog. */
    public void setFontSelection(String family) {
        String v = family != null ? family : "";
        if (store != null) store.putString(FONT_KEY, v);
        Fonts.setSelection(v);
        Fonts.warmupFromConfig();
        fontFamilyChanged.set(fontFamilyChanged.peek() + 1);
    }

    /** The picker writes whichever font source it was opened for: empty key (or
     *  {@link #FONT_KEY}) is the app-wide selection, anything else a secondary one
     *  stored under its own key. */
    /** 宿主自己的字体选择对话框（2.1.1 的宿主用它打开系统字体选择）。 */
    public interface FontPicker {
        void pick();
    }

    private volatile FontPicker fontPicker;

    public void setFontPicker(FontPicker picker) {
        this.fontPicker = picker;
    }

    /** 界面缩放系数（1.0 = 100%）。2.1.1 的宿主用它调整个窗口的 scale。 */
    public float uiScaleFactor() {
        Object v = peek(SettingsCatalog.UI_SCALE_KEY);
        int percent = v instanceof Number ? ((Number) v).intValue() : SettingsCatalog.UI_SCALE_DEFAULT;
        return Math.max(SettingsCatalog.UI_SCALE_MIN,
                Math.min(SettingsCatalog.UI_SCALE_MAX, percent)) / 100f;
    }

    /** 把宿主选到的字体文件字节导入并选中（2.1.1 的"导入字体"入口走这里）。 */
    /** 重新扫一遍可选字族（导入字体后调用）。 */
    public void refreshFontFamilies() {
        try {
            dev.t1m3.qplayer.lyric.skia.Fonts.reloadFileIndex();
            dev.t1m3.qplayer.lyric.skia.ImportedFonts.instance().reload();
        } catch (Throwable ignored) {
        }
    }

    public void importFontBytes(byte[] bytes, String filename) {
        String family = dev.t1m3.qplayer.lyric.skia.ImportedFonts.instance()
                .importFont(bytes, filename);
        if (family == null || family.isEmpty()) {
            return;
        }
        setFontSelectionFor(fontPickerTarget.peek(), family);
    }

    public void setFontSelectionFor(String key, String family) {
        if (key == null || key.isEmpty() || FONT_KEY.equals(key)) {
            setFontSelection(family);
            return;
        }
        if (store != null) store.putString(key, family != null ? family : "");
        fontFamilyChanged.set(fontFamilyChanged.peek() + 1);
        fireHooks(key, family != null ? family : "");
    }

    /** Reactive current value of one font source; see {@link #fontFamily()}. */
    public String fontFamilyFor(String key) {
        fontFamilyChanged.get();
        if (key == null || key.isEmpty() || FONT_KEY.equals(key)) return fontSelection();
        return store != null ? store.getString(key, "") : "";
    }

    /** Non-reactive read for host code (the desktop-lyric window). */
    public String fontSelectionOf(String key) {
        if (key == null || key.isEmpty() || FONT_KEY.equals(key)) return fontSelection();
        return store != null ? store.getString(key, "") : "";
    }

    /** Bumped on every font change so QML rows showing the current font
     *  re-evaluate (the value itself lives in the store, not in a Property). */
    public final Property<Integer> fontFamilyChanged = new Property<>(0);
    /** The font picker is a dialog rather than a row widget, so the "pickFont"
     *  action just raises this flag and the settings page binds its dialog to it. */
    public final Property<Boolean> fontPickerOpen = new Property<>(Boolean.FALSE);
    /** Which font source {@link #fontPickerOpen} is currently editing: empty for
     *  the app-wide font, {@link #DESKTOP_LYRIC_FONT_KEY} for desktop lyrics. */
    public final Property<String> fontPickerTarget = new Property<>("");
    /** Key of the COLOR row whose picker dialog is open, empty when none is. One
     *  dialog serves every colour row, the same way one dialog serves both fonts. */
    public final Property<String> colorPickerKey = new Property<>("");

    /** Reactive current-font readout for the picker dialog and the 外观 row. */
    public String fontFamily() {
        fontFamilyChanged.get();
        return fontSelection();
    }

    /** The font row's action and live text: core-owned (the selection lives in
     *  the store, not in a catalog row), so neither host has to wire them. A host
     *  may still override either by registering the same id first. */
    private void registerFontProviders() {
        infos.putIfAbsent("fontName", () -> {
            String sel = fontFamily();
            if (sel.isEmpty()) return I18n.tr("settings.font.current",
                    I18n.tr("font.picker.bundled"));
            if (Fonts.SYSTEM.equals(sel)) return I18n.tr("settings.font.current",
                    I18n.tr("font.picker.system"));
            return I18n.tr("settings.font.current", sel);
        });
        actions.putIfAbsent("pickFont", () -> {
            fontPickerTarget.set("");
            fontPickerOpen.set(Boolean.TRUE);
        });
        infos.putIfAbsent("desktopLyricFontName", () -> {
            String sel = fontFamilyFor(DESKTOP_LYRIC_FONT_KEY);
            if (sel.isEmpty()) return I18n.tr("font.picker.followMain");
            if (Fonts.BUNDLED.equals(sel)) return I18n.tr("font.picker.bundled");
            if (Fonts.SYSTEM.equals(sel)) return I18n.tr("font.picker.system");
            return sel;
        });
        actions.putIfAbsent("pickDesktopLyricFont", () -> {
            fontPickerTarget.set(DESKTOP_LYRIC_FONT_KEY);
            fontPickerOpen.set(Boolean.TRUE);
        });
    }

    // ---- models (按需下载的权重) --------------------------------------------

    /**
     * 模型那几行的动作与实时文案。core 自己拥有它们（像字体那两行一样），
     * 所以两个宿主都不用各自接一遍线。
     */
    private void registerModelProviders() {
        // 「管理模型」行右边那串状态摘要。它是活文案：下载完成之后这一行必须跟着变，
        // 否则用户会面对一个「明明下好了、设置里还说未下载」的界面。
        infos.putIfAbsent("modelStatus", this::modelStatusSummary);
        // 自动混音那一行的说明（见下面的 automixModeNotice）。
        infos.putIfAbsent("automixModelNotice", this::automixModeNotice);
        // 融合期人声那一行的说明（见下面的 automixVocalModeNotice）。
        infos.putIfAbsent("automixVocalNotice", this::automixVocalModeNotice);
        // 自动混音**总开关**那一行的实时状态（见下面的 automixStatusNotice）：
        // 光有一个开关不够——「开着但接法是无缝拼接」「开着但下一首拿不到句柄」
        // 这两种情况都会让人以为在跑而其实没跑，所以这一行必须把「现在到底会不会做过渡」
        // 说出来，而不是只显示一个 ON。
        infos.putIfAbsent("automixStatus", this::automixStatusNotice);
        actions.putIfAbsent(SettingsCatalog.MANAGE_MODELS_ACTION, this::openModelManager);
        // 「打开音频工作室」：页面栈归 QML 管，这里只举手（控制器发一个版本号，QML 监听它）
        actions.putIfAbsent(SettingsCatalog.OPEN_STUDIO_ACTION, () -> {
            PlayerController c = controller;
            if (c != null) {
                c.requestOpenStudio();
            }
        });
    }

    /** 把设置里的模型目录落到 {@link AppDirs} 上，然后重新问一次磁盘。 */
    private void applyModelsDir() {
        if (specsByKey.get(SettingsCatalog.MODELS_DIR_KEY) == null) {
            return;
        }
        AppDirs.setModelsDir(str(SettingsCatalog.MODELS_DIR_KEY));
        ModelAvailability.refresh();
        bumpModels();
    }

    /**
     * 启动时对已经在磁盘上的权重做一次<b>廉价</b>检查（只比大小），
     * 有问题就记一笔日志——被截断的旧文件不会让任何东西崩，它只会让功能不生效，
     * 而「为什么没生效」必须能在日志里看到。
     */
    private void auditModelsOnce() {
        try {
            for (String problem : ModelDownloads.shared().cheapAudit()) {
                Logger.warn("模型文件不对：{}", problem);
            }
        } catch (RuntimeException e) {
            // 清单读不出来时 refresh() 已经记过一笔，这里不重复
        }
        // 顺手扫一次陈旧的 .part 残留（下载中断留下的半成品，最多能占 108MB）。
        // 后台线程、不阻塞启动、出错就地吞掉；**按 24 小时年龄判断**那条规矩在
        // ModelStore.cleanPartsAsOf 里——正在写的 .part 被删掉等于毁掉那次下载，
        // 而这个清理是个顺手做的事，宁可留着。
        try {
            ModelStore.spawnStalePartCleanup(AppDirs.modelsDir().toFile());
        } catch (RuntimeException e) {
            Logger.warn("模型目录的 .part 清理没跑起来（不影响启动）：{}", e.toString());
        }
    }

    /** 打开模型对话框。顺手重新问一次磁盘：用户可能刚从网盘把文件放进去。 */
    public void openModelManager() {
        ModelAvailability.refresh();
        bumpModels();
        modelManagerOpen.set(Boolean.TRUE);
    }

    public void closeModelManager() {
        modelManagerOpen.set(Boolean.FALSE);
    }

    /** 当前模型目录（界面显示用）。 */
    public String modelDir() {
        return AppDirs.modelsDir().toFile().getAbsolutePath();
    }

    /**
     * 某个模型现在能不能跑——<b>它的权重在不在磁盘上，就是问题的全部</b>。
     *
     * <p>QML 里读它会让绑定依赖 {@link #modelRevision}，所以下载完成之后
     * 依赖模型的功能会自动重新判断一次。
     */
    public boolean modelReady(String name) {
        modelRevision.get();
        return ModelAvailability.canRun(name);
    }

    /** 状态摘要（「管理模型」那一行的实时文案）。 */
    public String modelStatusSummary() {
        modelRevision.get();
        List<ModelManifestEntry> entries;
        try {
            entries = ModelDownloads.shared().manifest().models();
        } catch (RuntimeException e) {
            return I18n.tr("settings.models.status.unavailable");
        }
        int ready = 0;
        StringBuilder sb = new StringBuilder();
        for (ModelManifestEntry e : entries) {
            boolean present = ModelAvailability.canRun(e.name);
            if (present) {
                ready++;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(e.name).append(present ? " ✓" : " —");
        }
        if (ready == entries.size()) {
            return I18n.tr("settings.models.status.allReady");
        }
        return I18n.tr("settings.models.status.count", Integer.valueOf(ready),
                Integer.valueOf(entries.size())) + (sb.length() > 0 ? "（" + sb + "）" : "");
    }

    /**
     * 自动混音<b>总开关</b>那一行的实时状态，<b>活文案</b>。
     *
     * <p>为什么这一行非有不可：总开关开着<strong>不等于</strong>真的会发生过渡——
     * 接法选了「无缝拼接」时窗口是 0（硬接），而在线歌曲还要先落到本地缓存才拿得到
     * 句柄（见 {@code PlayerController.armNextSourceForAutomix}）。只画一个 ON
     * 就是在让用户靠耳朵猜，而他会得出「开了也没用」这个——在他那种曲库下
     * 完全正确的——结论。所以这里把「会不会做过渡」的三种状态说清楚：
     * 关 / 开着但接法是硬接 / 开着且真会交叠。
     *
     * <p>拼接用的是设置页自己的那套措辞（下拉的选项文案），不另写一份模式名——
     * 两份名字迟早对不上，而症状是「设置里写的和状态里写的不是一回事」。
     */
    private String automixStatusNotice() {
        // 三个都必须是**活**读法（value/intValue 走 Property.get()，会登记绑定依赖）：
        // provider 是在 QML 绑定的求值过程里被调用的，用 peek/intOf 的话
        // 「刚把开关拨开、这一行还写着「已关闭」」——而这一行存在的全部理由就是随设置变。
        boolean enabled = Boolean.TRUE.equals(value(SettingsCatalog.AUTOMIX_ENABLED_KEY));
        int mode = intValue(SettingsCatalog.AUTOMIX_MODE_KEY);
        SettingSpec spec = specsByKey.get(SettingsCatalog.AUTOMIX_MODE_KEY);
        String label = "";
        if (spec != null && mode >= 0 && mode < spec.options.size()) {
            label = I18n.tr(spec.options.get(mode));
        }
        int window = mode == SettingsCatalog.AUTOMIX_MODE_CROSSFADE
                ? intValue(SettingsCatalog.AUTOMIX_CROSSFADE_SECS_KEY)
                : intValue(SettingsCatalog.AUTOMIX_HARMONIC_SECS_KEY);
        // 自动点位也必须是活读法：它决定「窗口从哪儿开始算」，这一行要说出来
        boolean autoCue = Boolean.TRUE.equals(value(SettingsCatalog.AUTOMIX_AUTO_CUE_KEY));
        return automixStatusNotice(enabled, mode, label, window, autoCue);
    }

    /**
     * {@link #automixStatusNotice} 的<b>纯函数部分</b>——理由与
     * {@link #vocalModeNotice} 一样：{@link #load} 在 player-core 单测里跑不起来
     * （Skija 原生库只在宿主运行时里），所以「怎么说」这一半必须能单独断言。
     *
     * @param enabled   总开关
     * @param mode      {@link SettingsCatalog#AUTOMIX_MODE_KEY} 的取值
     * @param modeLabel 那一档的界面措辞（已经过 i18n）
     * @param windowSecs 该档的窗口时长（秒）；只有交叉淡化/声波重合用得上
     */
    static String automixStatusNotice(boolean enabled, int mode, String modeLabel, int windowSecs) {
        return automixStatusNotice(enabled, mode, modeLabel, windowSecs, false);
    }

    /**
     * 同 {@link #automixStatusNotice(boolean, int, String, int)}，但把<b>自动点位</b>也算进去。
     *
     * <p>为什么它属于这一行：打开自动点位之后，设置里那个「窗口 N 秒」说的只是
     * <strong>交叠多长</strong>，而<strong>从哪儿开始交叠</strong>改由整曲扫描决定。
     * 只说「窗口 6 s」是对的但不完整——用户会以为换曲点还在曲尾那几秒。
     */
    static String automixStatusNotice(boolean enabled, int mode, String modeLabel, int windowSecs,
                                      boolean autoCue) {
        if (!enabled) {
            return I18n.tr("settings.automixStatus.off");
        }
        if (mode == SettingsCatalog.AUTOMIX_MODE_GAPLESS) {
            // 无缝拼接压根没有可融合的片段，自动点位对它是不生效的（引擎那一层不套包装）
            return I18n.tr("settings.automixStatus.gapless");
        }
        if (mode == SettingsCatalog.AUTOMIX_MODE_AI) {
            String text = I18n.tr("settings.automixStatus.ai", modeLabel);
            return autoCue ? text + I18n.tr("settings.automixStatus.autoCue") : text;
        }
        String text = I18n.tr("settings.automixStatus.window", modeLabel, Integer.valueOf(windowSecs));
        return autoCue ? text + I18n.tr("settings.automixStatus.autoCue") : text;
    }

    /**
     * 「过渡接法」那一行的说明，**活文案**。
     *
     * <p>为什么要活：两条都会让人以为「在跑」而其实听不到任何东西。
     *
     * <ol>
     *   <li><b>默认那一档是「无缝拼接」</b>，而它就是窗口 0 的硬接——<strong>按设计就
     *       不会有任何可听的重叠</strong>。不写明的话，「开箱即用听不出自动混音」
     *       必然被读成「这个功能坏了」，而它其实只是没被选过。</li>
     *   <li>AI 模式的拍点靠 beat_this，而这个模型是用户自己下的。模型不在磁盘上时
     *       这一行必须如实说「需要下载、当前退回硬算」——显示成「AI 模式（已启用）」
     *       而实际在硬算，就是拿界面撒谎。</li>
     * </ol>
     *
     * <p>反过来，把某一档从下拉里藏掉也是撒谎（另一个方向的）：硬算那条路是真的能跑，
     * 只是质量不同。所以这里既不藏、也不假装，只是把话说明白。
     */
    private String automixModeNotice() {
        modelRevision.get();
        String base = I18n.tr("settings.automixMode.desc");
        int mode = intValue(SettingsCatalog.AUTOMIX_MODE_KEY);
        if (mode == SettingsCatalog.AUTOMIX_MODE_GAPLESS) {
            return base + "\n" + I18n.tr("settings.automixMode.gaplessNotice");
        }
        if (mode != SettingsCatalog.AUTOMIX_MODE_AI) {
            return base;
        }
        boolean present = ModelAvailability.canRun("beat_this");
        return base + "\n" + I18n.tr(present
                ? "settings.automixMode.model.ready"
                : "settings.automixMode.model.missing");
    }

    /**
     * 「融合期人声」那一行的说明，<b>活文案</b>——与 {@link #automixModeNotice} 同一条理由。
     *
     * <p>三档里只有 <b>AI（模型）</b>那一档有「现在到底能不能用」的问题，而那一档
     * <b>只在桌面出现</b>（移动端不装 {@code StemModels.Opener}，设置里那行也没有 AI 选项，
     * 所以移动端永远返回基础说明）：DSP 是 player-core 自己的纯 Java 实现（不需要下载，
     * 所以不该出现「请先下载模型」这种话挡住它），「不分离」本来就不碰人声。
     *
     * <p>为什么必须说：AI 那一档在模型不在磁盘上时<b>一点作用都没有</b>
     * （过渡照常做，人声一个都不动，而且<b>不会</b>拿 DSP 顶替）。一个选了却什么都不做的档，
     * 唯一的诚实出口就是这一行——否则用户唯一的判据是听感，他会得到
     * 「选了好像没用」的结论，然后永远不知道该去下载模型。
     *
     * <p>注意这里只<b>说</b>、不倒过来改用户的选择：模型一下完它就自愈
     * （{@link #modelReady} 依赖的 {@link #modelRevision} 会在下载落地后自增，
     * 这一行跟着重算）。
     */
    private String automixVocalModeNotice() {
        modelRevision.get();
        String base = I18n.tr("settings.automixVocalMode.desc");
        return vocalModeNotice(base,
                intValue(SettingsCatalog.AUTOMIX_VOCAL_MODE_KEY),
                ModelAvailability.canRun(StemModels.MODEL_NAME));
    }

    /**
     * {@link #automixVocalModeNotice} 的<b>纯函数部分</b>。
     *
     * <p>单独抽出来只为一件事：<b>可测</b>。拼文案要看「选了哪一档 + 模型在不在磁盘上」
     * 这两个条件的组合，而 {@link #load} 在这台机器的 player-core 单测里跑不起来
     * （它会走到 {@code Fonts.listFamilies()}，而 Skija 的原生库只在各宿主的运行时里，
     * 单测 JVM 上直接 {@code UnsatisfiedLinkError}）。所以把「怎么说」和「读哪个键」
     * 分开：这一层是纯的、可断言的，读设置那一层只有一行。
     *
     * <p><b>不许在这里返回任何「已启用」的说法</b>——选了 AI 而模型不在时，
     * 那句话必须明说「现在会正常过渡但不去人声」；选了 DSP / 不分离时<b>不加话</b>
     * （它们没有「缺东西」这回事，多一句话只会让人以为要去下载点什么）。
     */
    static String vocalModeNotice(String base, int mode, boolean modelPresent) {
        if (mode != SettingsCatalog.AUTOMIX_VOCAL_MODE_AI) {
            return base;
        }
        return base + "\n" + I18n.tr(modelPresent
                ? "settings.automixVocalMode.model.ready"
                : "settings.automixVocalMode.model.missing",
                StemModels.MODEL_NAME);
    }

    /**
     * 对话框的<b>结构</b>：清单里每个模型一行，只带与进度无关的那些字段
     * （名字、体积、许可证、来源、说明、手动兜底链接）。
     *
     * <p>刻意<b>不</b>读 {@link #modelRevision}：这一个列表是 Repeater 的 model，
     * 它一变整个列表的子项就会被重建。把进度也算进来的话，每 200ms 一次的回调
     * 会把窗口里的按钮和文字全部重建一遍（滚动位置、焦点、动画都会跟着抖）。
     * 会变的那部分走 {@link #modelRow(String)}，它读 revision，只重算一行里的绑定。
     */
    public List<ModelRow> modelRows() {
        List<ModelRow> out = new ArrayList<ModelRow>();
        try {
            ModelDownloads downloads = ModelDownloads.shared();
            ModelManifest manifest = downloads.manifest();
            // **按组渲染**：一个模型拆成好几个文件时（ONNX 的 .onnx + .onnx.data、
            // ACE-Step 那一整套）只在界面上占一行，一个按钮下全。
            for (List<ModelManifestEntry> group : bundles(manifest)) {
                if (!capabilitySupported(group.get(0).enables)) {
                    // 这个平台没有对应的实现（例如安卓没有「生成式」的推埋内核）：
                    // 列一个下不了的权重只会让用户下一份用不上的东西
                    continue;
                }
                ModelRow row = group.size() == 1
                        ? ModelRow.of(group.get(0), null)
                        : ModelRow.bundleOf(group);
                row.title = group.size() == 1
                        ? modelTitle(group.get(0).name)
                        : bundleTitle(group.get(0).bundleId(), group.get(0));
                row.stateText = stateText(ModelDownloads.STATE_ABSENT, 0, false);
                row.setManual(manifest.manualLinks(), manifest.manualNote());
                out.add(row);
            }
        } catch (RuntimeException e) {
            Logger.warn("模型清单读不出来：{}", e.toString());
        }
        return out;
    }

    /**
     * 这个能力在当前平台有没有实现。
     *
     * <p>只有「靠宿主装实现」的能力才需要问。{@code beatGrid} / {@code stems} 两端都有；
     * 原先还有一档 {@code bridgeGeneration}（生成式过渡，只有桌面装了推埋内核）——
     * <b>2026-10-01 生成式整套删除</b>，清单里不再有那个能力，这个判定目前对什么都说「有」，
     * 但保留着：它是「模型能力 → 宿主实现」的唯一入口，将来再出宿主独占的能力还用这里。
     */
    private static boolean capabilitySupported(String enables) {
        return true;
    }

    /** 清单里的条目按 {@code bundleId()} 分组（保持清单顺序）。 */
    private static List<List<ModelManifestEntry>> bundles(ModelManifest manifest) {
        List<List<ModelManifestEntry>> out = new ArrayList<List<ModelManifestEntry>>();
        java.util.LinkedHashMap<String, List<ModelManifestEntry>> byId =
                new java.util.LinkedHashMap<String, List<ModelManifestEntry>>();
        for (ModelManifestEntry entry : manifest.models()) {
            String id = entry.bundleId();
            List<ModelManifestEntry> group = byId.get(id);
            if (group == null) {
                group = new ArrayList<ModelManifestEntry>();
                byId.put(id, group);
                out.add(group);
            }
            group.add(entry);
        }
        return out;
    }

    /** 组里的条目（一行对应一组；名字就是组 id）。 */
    private List<ModelManifestEntry> groupOf(String id) {
        try {
            for (List<ModelManifestEntry> group : bundles(ModelDownloads.shared().manifest())) {
                if (group.get(0).bundleId().equals(id)) {
                    return group;
                }
            }
        } catch (RuntimeException e) {
            Logger.warn("模型清单读不出来：{}", e.toString());
        }
        return null;
    }

    /**
     * 一行<b>此刻</b>的样子。QML 里读它会让那条绑定依赖 {@link #modelRevision}，
     * 所以下载进度、校验结果、下载完成都会让这一行自己刷新，而不必重建列表。
     *
     * <p>每次调用都现算（不缓存）：缓存一份「几秒前的状态」正是进度条会卡住的原因。
     * 磁盘那一趟只有两个 stat；完整校验是用户按按钮才做的。
     */
    public ModelRow modelRow(String name) {
        modelRevision.get();
        try {
            ModelDownloads downloads = ModelDownloads.shared();
            List<ModelManifestEntry> group = groupOf(name);
            if (group == null) {
                return null;
            }
            if (group.size() == 1) {
                ModelManifestEntry entry = group.get(0);
                ModelRow row = ModelRow.of(entry, downloads.status(entry));
                row.title = modelTitle(entry.name);
                row.stateText = stateText(row);
                row.setManual(downloads.manifest().manualLinks(), downloads.manifest().manualNote());
                return row;
            }
            // 一组多文件：状态取「最差的那一个」——只要有一个没下完，这一组就没下完。
            ModelRow row = ModelRow.bundleOf(group);
            List<ModelDownloads.Snapshot> snaps = new ArrayList<ModelDownloads.Snapshot>();
            for (ModelManifestEntry entry : group) {
                snaps.add(downloads.status(entry));
            }
            row.applyBundle(snaps, group);
            row.title = bundleTitle(name, group.get(0));
            row.stateText = stateText(row);
            row.setManual(downloads.manifest().manualLinks(), downloads.manifest().manualNote());
            return row;
        } catch (RuntimeException e) {
            Logger.warn("模型 {} 的状态读不出来：{}", name, e.toString());
            return null;
        }
    }

    /** 组的显示名：优先 i18n 键，其次清单里第一条的 note，最后退回组 id。 */
    private static String bundleTitle(String id, ModelManifestEntry first) {
        String key = "settings.models.bundle." + id + ".title";
        String value = I18n.tr(key);
        if (!key.equals(value)) {
            return value;
        }
        return modelTitle(first.name);
    }

    /**
     * 下载一个模型（后台线程；界面靠 {@link #modelRevision} 跟进度）。
     *
     * <p>失败不抛给界面：一次下载失败是普通事件（没网、镜像挂、被墙），
     * 它应该变成这一行上的一句话说给人听，而不是一个异常。
     */
    public void downloadModel(String name) {
        final List<ModelManifestEntry> group = groupOf(name);
        if (group == null || group.isEmpty()) {
            return;
        }
        final ModelDownloads downloads = ModelDownloads.shared();
        // 先画一次「下载中」，免得用户点了按钮之后几百毫秒里什么都没变
        bumpModels();
        modelWorker.execute(() -> {
            try {
                // 一组多文件时**逐个下**：一个失败就停在这里，剩下的留到下次点
                // （失败原因是按条目记的，界面上那一行会显示最差的那一个）
                for (ModelManifestEntry entry : group) {
                    if (downloads.isBusy(entry.name)) {
                        continue;
                    }
                    downloads.download(entry, snapshot -> post(this::bumpModels));
                }
            } catch (RuntimeException e) {
                Logger.warn("下载 {} 时出错：{}", name, e.toString());
            } finally {
                ModelAvailability.refresh();
                post(this::bumpModels);
            }
        });
    }

    /** 取消一次正在进行的下载（已经写下的 {@code .part} 留着，但绝不会被当成可用文件）。 */
    public void cancelModel(String name) {
        List<ModelManifestEntry> group = groupOf(name);
        if (group == null) {
            return;
        }
        boolean canceled = false;
        for (ModelManifestEntry entry : group) {
            canceled = ModelDownloads.shared().cancel(entry.name) || canceled;
        }
        if (canceled) {
            bumpModels();
        }
    }

    /**
     * 完整校验一份已经在磁盘上的文件（读完整份算 sha256）。
     *
     * <p>手动兜底那条路（网盘下载之后放进模型目录）就是靠它确认的：
     * <b>人搬运过来的文件没有第二个、更宽松的信任级别</b>。
     */
    public void verifyModel(String name) {
        final List<ModelManifestEntry> group = groupOf(name);
        if (group == null || group.isEmpty()) {
            return;
        }
        final ModelDownloads downloads = ModelDownloads.shared();
        bumpModels();
        modelWorker.execute(() -> {
            try {
                for (ModelManifestEntry entry : group) {
                    downloads.verify(entry, snapshot -> post(this::bumpModels));
                }
            } catch (RuntimeException e) {
                Logger.warn("校验 {} 时出错：{}", name, e.toString());
            } finally {
                ModelAvailability.refresh();
                post(this::bumpModels);
            }
        });
    }

    /** 删掉本地的权重（连同可能存在的 {@code .part}）。 */
    public void removeModel(String name) {
        List<ModelManifestEntry> group = groupOf(name);
        if (group == null) {
            return;
        }
        for (ModelManifestEntry entry : group) {
            ModelDownloads.shared().remove(entry);
        }
        ModelAvailability.refresh();
        bumpModels();
    }

    /** 在渲染线程上把模型相关的绑定全部作废一次。 */
    private void bumpModels() {
        Long current = modelRevision.peek();
        modelRevision.set(Long.valueOf(current == null ? 1L : current.longValue() + 1L));
    }

    /** 后台线程 → 渲染线程。宿主没接钩子时直接执行（测试，以及没有循环的宿主）。 */
    private void post(Runnable r) {
        Consumer<Runnable> poster = uiPoster;
        if (poster != null) {
            poster.accept(r);
        } else {
            r.run();
        }
    }

    private ModelManifestEntry entryOrNull(String name) {
        try {
            return ModelDownloads.shared().manifest().get(name);
        } catch (RuntimeException e) {
            Logger.warn("模型清单读不出来：{}", e.toString());
            return null;
        }
    }

    /** 模型名的显示名：有 i18n 键就用它，没有就退回清单里的名字（可见，不静默）。 */
    private static String modelTitle(String name) {
        String key = "settings.models.name." + name;
        String value = I18n.tr(key);
        return key.equals(value) ? name : value;
    }

    /**
     * 「下载中」那一行到底写什么。
     *
     * <p><b>只写一个百分比是不够的</b>，而这不是审美问题：`0%` 有三种完全不同的处境——
     * 刚点下去（正常）、正在等镜像的第一个字节（可能要十几秒，见
     * {@link ModelDownloads#FIRST_BYTE_TIMEOUT_MS}）、以及这一条路已经死了。
     * 三者在进度条上是同一个画面，用户只能得出「卡住了」这一个结论，然后去点取消。
     * 所以这一行必须把「正在从哪个镜像下、已经拿到多少、已经等了多久」写出来，
     * 并且在超过 {@link ModelDownloads#STALL_NOTICE_MS} 没有新字节时**如实说卡住**，
     * 而不是继续显示一个不动的数字。
     */
    private static String stateText(ModelRow row) {
        if (row == null) {
            return I18n.tr("settings.models.state.absent");
        }
        if (ModelDownloads.STATE_DOWNLOADING.equals(row.state)) {
            String base = I18n.tr("settings.models.state.downloading",
                    Integer.valueOf(row.percent));
            if (row.mirror.isEmpty()) {
                // 还没轮到任何一个镜像（建目录、清 .part 的那一小会儿）
                return base;
            }
            String transfer = I18n.tr("settings.models.state.transfer", row.mirror,
                    row.receivedText, Long.valueOf(row.elapsedSecs));
            if (!row.stalled) {
                return base + "\n" + transfer;
            }
            // 卡住了就说卡住了。两种卡法的后果不一样，所以说法也不一样：
            // 一个字节都没来 → 首字节预算一到就换下一个（能给倒计时）；
            // 收到一半停了 → 只能等这一次读超时，不能替用户承诺一个时间点。
            String stall = row.received <= 0L
                    ? I18n.tr("settings.models.state.stalled.firstByte", row.mirror,
                              Long.valueOf(row.sinceLastByteSecs),
                              Long.valueOf(remainingFirstByteSecs(row.sinceLastByteSecs)))
                    : I18n.tr("settings.models.state.stalled.midway", row.mirror,
                              Long.valueOf(row.sinceLastByteSecs));
            return base + "\n" + transfer + "\n" + stall;
        }
        if (ModelDownloads.STATE_VERIFYING.equals(row.state)) {
            return I18n.tr("settings.models.state.verifying");
        }
        if (ModelDownloads.STATE_READY.equals(row.state)) {
            // 「有文件」与「文件是对的」不是同一句话：只比过大小的时候说「已下载，
            // 未校验」，不许说「已就绪」——那会变成一句界面上的假承诺。
            return I18n.tr(row.verified
                    ? "settings.models.state.ready" : "settings.models.state.downloaded");
        }
        if (ModelDownloads.STATE_CORRUPT.equals(row.state)) {
            return I18n.tr("settings.models.state.corrupt");
        }
        if (ModelDownloads.STATE_FAILED.equals(row.state)) {
            return I18n.tr("settings.models.state.failed");
        }
        if (ModelDownloads.STATE_CANCELED.equals(row.state)) {
            return I18n.tr("settings.models.state.canceled");
        }
        return I18n.tr("settings.models.state.absent");
    }

    /**
     * 首字节预算还剩几秒（不小于 0）——给界面上的倒计时用。
     *
     * <p>它读的是「距离上一个字节多久」，而在一个字节都没来的情况下那就是这一笔
     * 已经等了多久，与 {@link ModelDownloads} 里看门狗的判据同一个量。
     */
    private static long remainingFirstByteSecs(long sinceLastByteSecs) {
        long budget = ModelDownloads.FIRST_BYTE_TIMEOUT_MS / 1000L;
        long left = budget - sinceLastByteSecs;
        return left > 0L ? left : 0L;
    }

    /** 状态码 → 给人看的一句话（没有快照的那些调用点用这个）。 */
    private static String stateText(String state, int percent, boolean verified) {
        if (ModelDownloads.STATE_DOWNLOADING.equals(state)) {
            return I18n.tr("settings.models.state.downloading", Integer.valueOf(percent));
        }
        if (ModelDownloads.STATE_VERIFYING.equals(state)) {
            return I18n.tr("settings.models.state.verifying");
        }
        if (ModelDownloads.STATE_READY.equals(state)) {
            // 「有文件」与「文件是对的」不是同一句话：只比过大小的时候说「已下载，
            // 未校验」，不许说「已就绪」——那会变成一句界面上的假承诺。
            return I18n.tr(verified
                    ? "settings.models.state.ready" : "settings.models.state.downloaded");
        }
        if (ModelDownloads.STATE_CORRUPT.equals(state)) {
            return I18n.tr("settings.models.state.corrupt");
        }
        if (ModelDownloads.STATE_FAILED.equals(state)) {
            return I18n.tr("settings.models.state.failed");
        }
        if (ModelDownloads.STATE_CANCELED.equals(state)) {
            return I18n.tr("settings.models.state.canceled");
        }
        return I18n.tr("settings.models.state.absent");
    }

    /** Open the shared colour dialog for a COLOR row. */
    public void openColorPicker(String key) {
        SettingSpec spec = specsByKey.get(key);
        if (spec == null || !SettingSpec.COLOR.equals(spec.type)) return;
        colorPickerKey.set(key);
    }

    /** One-time key moves, run before anything is read: the store keeps whatever
     *  type it was first written with, so a setting that changed shape needs a new
     *  key seeded from the old one. */
    private void migrateLegacyKeys() {
        // "lyricBgStatic" (bool) -> "lyricBgMode" (0 dynamic / 1 static).
        if (!store.has(SettingsCatalog.BG_MODE_KEY) && store.getBool("lyricBgStatic", false)) {
            store.putInt(SettingsCatalog.BG_MODE_KEY, 1);
        }
    }

    /** Fold in the two settings the single font selection replaced. */
    private String migratedFontSelection() {
        String legacyFamily = store.getString("lyricFontFamily", "");
        if (!legacyFamily.isEmpty()) return legacyFamily;
        return store.getBool("useSystemFont", false) ? Fonts.SYSTEM : "";
    }

    private static List<String> sortedFontFamilies() {
        String[] names = Fonts.listFamilies();
        java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        return java.util.Arrays.asList(names);
    }
}
