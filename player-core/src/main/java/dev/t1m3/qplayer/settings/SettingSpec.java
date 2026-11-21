package dev.t1m3.qplayer.settings;

import java.util.Collections;
import java.util.List;

/**
 * One user-facing setting, declared once and rendered by whichever QML row
 * component matches {@link #type}. Every field is public and final because the
 * QML bridge reads Java members reflectively as public FIELDS (no getters), and
 * a spec is shared by every delegate that renders it.
 *
 * <p>Specs are data only: the value itself lives in {@link SettingsCore}, keyed
 * by {@link #key}, and side effects are registered there too. Adding a setting
 * means adding one entry to {@link SettingsCatalog} — no QML edit, no per-
 * platform Settings class edit.
 */
public final class SettingSpec {

    // Row component to render this with. QML switches on this string; anything
    // unknown renders as nothing rather than breaking the page.
    public static final String SWITCH = "switch";        // boolean toggle
    public static final String STEPPER = "stepper";      // int with -/+ buttons
    public static final String SLIDER = "slider";        // int selected on a slider
    public static final String SEGMENTED = "segmented";  // int index over `options`
    public static final String TEXT = "text";            // string + apply button
    public static final String PATH = "path";            // string chosen by the host directory picker
    public static final String RADIO = "radio";          // int index as radio buttons
    public static final String DROPDOWN = "dropdown";    // int index in a combo box
    public static final String COLOR = "color";          // "#rrggbb" string, empty = the row's automatic default
    public static final String ACTION = "action";        // button/icon, no stored value

    /** Every platform. The alternative values are the host ids passed to
     *  {@link SettingsCore#load}, so a spec can be desktop- or android-only. */
    public static final String ANY = "";

    public final String key;
    public final String type;
    public final String category;
    /** Card this row is drawn in: consecutive rows sharing a group id share one
     *  card, the way the hand-written page grouped e.g. every 歌词 control into
     *  a single surface. Defaults to the key, i.e. a card of its own. */
    public final String group;
    public final String title;
    /** Secondary line under the title; empty for none. */
    public final String desc;
    /** Initial value for {@link #SWITCH} (Boolean), {@link #STEPPER}/{@link #SLIDER}/
     *  {@link #SEGMENTED}/{@link #RADIO}/{@link #DROPDOWN} (Integer) and
     *  {@link #TEXT}/{@link #PATH} (String); null for the
     *  valueless row types. A null default here means "ask the host at load
     *  time" — see {@link SettingsCore#defaultOverride}. */
    public final Object def;

    // Numeric-control bounds. `scale` divides the stored value for display only, so a
    // line height persisted as 200 shows as 2.00 without QML doing the math.
    public final int min;
    public final int max;
    public final int step;
    public final int scale;
    /** Suffix appended to a stepper's displayed value, e.g. " px" or "×". */
    public final String unit;
    /** Draw one dot at every declared slider step. A slider without dots remains
     *  continuous while dragging and is rounded to an integer when committed. */
    public final boolean dots;

    /** Segmented labels, left to right; the stored value is the index. */
    public final List<String> options;
    /** Placeholder/hint for a text row. */
    public final String hint;
    /** Button label for an action row, or the apply button of a text row. */
    public final String button;
    /** Action id passed to {@link SettingsCore#invoke}; action rows only. */
    public final String action;
    /** Id of a live-text provider ({@link SettingsCore#registerInfo}) rendered
     *  in place of {@link #desc} when set — the version string, cache usage, the
     *  current font name. Empty for a static description. */
    public final String provider;
    /** Key of a boolean setting that gates this row's visibility; empty = always
     *  visible. Lets a block of rows hang off its own on/off switch. */
    public final String dependsOn;
    /** Host id this row is limited to, or {@link #ANY}. */
    public final String platform;
    /** Material Symbols name; an action row with an icon draws an IconButton
     *  instead of a labelled button. */
    public final String icon;
    /** md3 Button type for an action/text row's button: filledTonal, outlined, text. */
    public final String buttonType;
    /** Whether a provider's live text sits on the title row (cache usage, app
     *  version) rather than on its own line below it (the current font). */
    public final boolean inlineProvider;
    /** An extra widget on the title row; "swatch" draws the Monet seed colour. */
    public final String accessory;
    /** Stored and applied like any other setting, but never rendered — the value
     *  has its own UI elsewhere (the lyric page's offset control). */
    public final boolean hidden;

    private SettingSpec(Builder b) {
        this.key = b.key;
        this.type = b.type;
        this.category = b.category;
        this.title = b.title;
        this.desc = b.desc;
        this.def = b.def;
        this.min = b.min;
        this.max = b.max;
        this.step = b.step;
        this.scale = b.scale;
        this.unit = b.unit;
        this.dots = b.dots;
        this.options = b.options;
        this.hint = b.hint;
        this.button = b.button;
        this.action = b.action;
        this.provider = b.provider;
        this.dependsOn = b.dependsOn;
        this.platform = b.platform;
        this.group = b.group != null ? b.group : b.key;
        this.icon = b.icon;
        this.buttonType = b.buttonType;
        this.inlineProvider = b.inlineProvider;
        this.accessory = b.accessory;
        this.hidden = b.hidden;
    }

    /** Whether this row belongs to a host identified as {@code hostPlatform}. */
    public boolean appliesTo(String hostPlatform) {
        return platform.isEmpty() || platform.equals(hostPlatform);
    }

    /** Whether the row carries a stored value (as opposed to being a button or
     *  a read-only line). */
    public boolean hasValue() {
        return SWITCH.equals(type) || STEPPER.equals(type) || SLIDER.equals(type)
                || SEGMENTED.equals(type) || TEXT.equals(type) || PATH.equals(type)
                || RADIO.equals(type) || COLOR.equals(type)
                || DROPDOWN.equals(type);
    }

    public static Builder toggle(String key, String category, String title, boolean def) {
        return new Builder(key, SWITCH, category, title).def(def);
    }

    public static Builder stepper(String key, String category, String title,
                                  int def, int min, int max, int step) {
        return new Builder(key, STEPPER, category, title).def(def).range(min, max, step);
    }

    public static Builder slider(String key, String category, String title,
                                 int def, int min, int max, int step) {
        return new Builder(key, SLIDER, category, title).def(def).range(min, max, step);
    }

    public static Builder segmented(String key, String category, String title,
                                    int def, String... options) {
        return new Builder(key, SEGMENTED, category, title).def(def).options(options);
    }

    public static Builder radio(String key, String category, String title,
                                int def, String... options) {
        return new Builder(key, RADIO, category, title).def(def).options(options);
    }

    public static Builder dropdown(String key, String category, String title,
                                   int def, String... options) {
        return new Builder(key, DROPDOWN, category, title).def(def).options(options);
    }

    /** A value that is stored and applied but has no row of its own. */
    public static Builder hidden(String key, String type, Object def) {
        return new Builder(key, type, "", "").def(def).hide();
    }

    public static Builder text(String key, String category, String title, String def) {
        return new Builder(key, TEXT, category, title).def(def);
    }

    public static Builder path(String key, String category, String title, String def) {
        return new Builder(key, PATH, category, title).def(def);
    }

    /** A colour chosen from the picker dialog. The stored value is "#rrggbb", or
     *  empty for "whatever this row's automatic default is" (a Monet role, a fixed
     *  neutral) — which is what the picker's reset button writes back. */
    public static Builder color(String key, String category, String title, String def) {
        return new Builder(key, COLOR, category, title).def(def);
    }

    public static Builder action(String action, String category, String title, String button) {
        return new Builder("action:" + action, ACTION, category, title).action(action).button(button);
    }



    public static final class Builder {
        private final String key;
        private final String type;
        private final String category;
        private final String title;
        private String desc = "";
        private Object def;
        private int min;
        private int max;
        private int step = 1;
        private int scale = 1;
        private String unit = "";
        private boolean dots;
        private List<String> options = Collections.emptyList();
        private String hint = "";
        private String button = "";
        private String action = "";
        private String provider = "";
        private String dependsOn = "";
        private String platform = ANY;
        private String group;
        private String icon = "";
        private String buttonType = "filledTonal";
        private boolean inlineProvider;
        private String accessory = "";
        private boolean hidden;

        private Builder(String key, String type, String category, String title) {
            this.key = key;
            this.type = type;
            this.category = category;
            this.title = title;
        }

        public Builder desc(String v) { this.desc = v; return this; }
        public Builder def(Object v) { this.def = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder dots() { this.dots = true; return this; }
        public Builder scale(int v) { this.scale = v; return this; }
        public Builder hint(String v) { this.hint = v; return this; }
        public Builder button(String v) { this.button = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder dependsOn(String v) { this.dependsOn = v; return this; }
        public Builder onlyOn(String hostPlatform) { this.platform = hostPlatform; return this; }
        public Builder group(String v) { this.group = v; return this; }
        public Builder icon(String v) { this.icon = v; return this; }
        public Builder buttonType(String v) { this.buttonType = v; return this; }
        public Builder inlineProvider() { this.inlineProvider = true; return this; }
        public Builder accessory(String v) { this.accessory = v; return this; }
        private Builder hide() { this.hidden = true; return this; }

        public Builder range(int min, int max, int step) {
            this.min = min;
            this.max = max;
            this.step = step;
            return this;
        }

        public Builder options(String... labels) {
            this.options = Collections.unmodifiableList(java.util.Arrays.asList(labels));
            return this;
        }

        private Builder action(String v) { this.action = v; return this; }

        public SettingSpec build() {
            return new SettingSpec(this);
        }
    }
}
