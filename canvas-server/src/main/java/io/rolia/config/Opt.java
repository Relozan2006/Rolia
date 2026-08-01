package io.rolia.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolia - one configuration option, declared once and used everywhere.
 *
 * <h2>Why a registry instead of plain fields</h2>
 *
 * <p>Before build 43 every option in {@code rolia.yml} existed in four places that had to be kept in
 * step by hand: a static field, a line in the parser, a line in the hand-written YAML template, and a
 * sentence of documentation. With four options that is tedious; with fifty it is a guarantee of drift -
 * a key that is written but never read, or read but never written, or documented with a default it does
 * not actually have. Those bugs are silent, which is the worst kind.</p>
 *
 * <p>So an option is declared exactly once, as an {@code Opt}, and everything else is derived from the
 * declaration: the parser, the generated file, the startup log line and the key list CI checks the
 * documentation against. The copies cannot disagree because there is only one.</p>
 *
 * <h2>Reading an option is a plain field read</h2>
 *
 * <p>{@link BoolOpt#get()} and friends are a single volatile read of a field on a final object, which
 * the JIT inlines to nothing. There is deliberately no map lookup, no string hashing and no lock on the
 * read path. Build 46 leaves only five options, none of them hot, but the shape is kept: the reason it
 * was written this way was {@code dab.enabled}, which was consulted per mob per tick from every region
 * thread, and a fork that adds an option like that again should not have to rediscover this.</p>
 */
public abstract class Opt<T> {

    // Rolia - build 46: the same logger name the rest of the fork uses, so an operator reading the
    // console sees one project. Added because coercions used to be silent: a value this class did not
    // recognise was quietly replaced and nothing said so, which is precisely the "silent bug" the class
    // javadoc above complains about.
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Rolia");

    /** Rolia - could this option be applied at runtime, or does it need a restart? */
    public enum Reload {
        /** Safe to change at runtime: the next read would simply see the new value. */
        LIVE,
        /**
         * Needs a restart. Either the value is captured once during startup, or changing it mid-run
         * would desynchronise a world that is already generating.
         *
         * <p>Build 46 removed the {@code /rolia} command, so nothing reloads anything today and every
         * option needs a restart in practice. The distinction is kept because it is what the generated
         * file's "(takes effect on the next server restart)" line is derived from, and because it is a
         * property of the option rather than of the command that used to read it.</p>
         */
        RESTART
    }

    /** Rolia - declaration-ordered registry. Order here is the order in the generated YAML. */
    private static final List<Object> ENTRIES = new ArrayList<>();
    private static final Set<String> PATHS = new LinkedHashSet<>();

    public final String path;
    public final String[] comment;
    public final Reload reload;
    /** Rolia - true once a value from the file (rather than the default) has been applied. */
    volatile boolean fromFile;
    /**
     * Rolia - holds a secret. Its value round-trips through the file (it has to - losing it loses the
     * world) but is never printed by the startup line, never written to a log, and never included
     * in the documentation key dump. The generated file is the ONLY place it appears.
     */
    private boolean secret;

    /** Rolia - fluent marker, used at the declaration site. */
    public Opt<T> markSecret() {
        this.secret = true;
        return this;
    }

    public boolean isSecret() {
        return this.secret;
    }

    /** Rolia - was this key present in the file that was loaded? */
    public boolean wasSetInFile() {
        return this.fromFile;
    }

    Opt(final String path, final Reload reload, final String... comment) {
        if (!PATHS.add(path)) {
            // A duplicate path means two options silently fight over one key. Fail at class-init time,
            // where it is a one-line stack trace, rather than at runtime where it is a mystery.
            throw new IllegalStateException("Rolia: duplicate config path " + path);
        }
        this.path = path;
        this.reload = reload;
        this.comment = comment;
        ENTRIES.add(this);
    }

    /** Rolia - a heading in the generated file. Carries no value; purely structure and prose. */
    public static final class Section {
        public final String path;
        public final String[] comment;

        private Section(final String path, final String... comment) {
            this.path = path;
            this.comment = comment;
        }
    }

    /** Rolia - declare a heading. Must be declared before the options it introduces. */
    public static Section section(final String path, final String... comment) {
        final Section s = new Section(path, comment);
        ENTRIES.add(s);
        return s;
    }

    /** Rolia - a free-standing comment block in the generated file, attached to no key. */
    public static final class Note {
        public final String[] lines;

        private Note(final String... lines) {
            this.lines = lines;
        }
    }

    public static Note note(final String... lines) {
        final Note n = new Note(lines);
        ENTRIES.add(n);
        return n;
    }

    /** Rolia - every registry entry in declaration order: {@link Opt}, {@link Section} or {@link Note}. */
    public static List<Object> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** Rolia - every option, in declaration order. Used by the startup line and the CI docs check. */
    public static List<Opt<?>> options() {
        final List<Opt<?>> out = new ArrayList<>();
        for (final Object e : ENTRIES) {
            if (e instanceof Opt<?> o) {
                out.add(o);
            }
        }
        return out;
    }

    /** The current value. */
    public abstract T get();

    /** The compiled-in default, i.e. what stock Rolia does with no file present. */
    public abstract T defaultValue();

    /** Is this option still at its default? Drives the "non-default settings" part of the startup line. */
    public boolean isDefault() {
        final T v = get();
        final T d = defaultValue();
        return v == null ? d == null : v.equals(d);
    }

    /** Render the current value as it would appear in the YAML file. */
    public abstract String yamlValue();

    /**
     * Apply a value read from the file.
     *
     * @param raw       the parsed YAML node for this path, or null when the key is absent
     * @param dryRun    when true, validate and report but do not assign. Build 46 removed the reload
     *                  command that used this, so every caller passes false today; the parameter is
     *                  kept because "would this change anything?" is the question any future reload
     *                  has to ask, and answering it by assigning first is how you get a half-applied
     *                  config
     * @return true when the effective value changed
     */
    public abstract boolean apply(Object raw, boolean dryRun);

    /** Reset to the compiled-in default. No caller since build 46 removed the reload command. */
    public abstract void reset();

    @Override
    public String toString() {
        // Rolia - build 44: a secret option must never render its value here. Nothing prints an Opt
        // today, but this is the one accessor a future LOGGER.warn("bad option {}", opt) would reach
        // for, and that single line would put the 1024-bit secret into latest.log and into every bug
        // report pasted from it. Every other secret-handling site in this codebase is defended; this
        // one was not.
        return path + "=" + (this.secret ? "<secret>" : yamlValue());
    }

    // ---------------------------------------------------------------------------------------------
    // Concrete types
    // ---------------------------------------------------------------------------------------------

    public static final class BoolOpt extends Opt<Boolean> {
        private final boolean def;
        private volatile boolean value;

        public BoolOpt(final String path, final boolean def, final Reload reload, final String... comment) {
            super(path, reload, comment);
            this.def = def;
            this.value = def;
        }

        /** Hot path: one volatile read, nothing else. */
        public boolean get0() {
            return this.value;
        }

        @Override
        public Boolean get() {
            return this.value;
        }

        @Override
        public Boolean defaultValue() {
            return this.def;
        }

        @Override
        public String yamlValue() {
            return Boolean.toString(this.value);
        }

        @Override
        public boolean apply(final Object raw, final boolean dryRun) {
            final boolean next;
            if (raw instanceof Boolean b) {
                next = b;
            } else if (raw != null) {
                // Accept the string forms a hand-edited file produces ("true", "yes", "on").
                final String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
                if (s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("1")) {
                    next = true;
                } else if (s.equals("false") || s.equals("no") || s.equals("off") || s.equals("0")) {
                    next = false;
                } else {
                    // Rolia - build 46: fall back to the DEFAULT, not to false.
                    //
                    // Anything unrecognised used to become false, and the only boolean in this file is
                    // secure-seed.enabled, whose default is the single true in the whole registry. So
                    // "enabled: ture" silently turned the entire fork off on a world that needs it -
                    // the most consequential possible outcome for a one-character typo, in the safest
                    // possible direction to get wrong.
                    // Rolia - the value is redacted for a secret option. No secret is a boolean today,
                    // but Opt.toString() was hardened for exactly this and a value-printing branch
                    // without the guard is how the next one leaks.
                    LOGGER.warn("Rolia: {} has an unrecognised value '{}'; keeping the default ({}).",
                        this.path, this.isSecret() ? "<secret>" : raw, this.def);
                    next = this.def;
                }
            } else {
                next = this.def;
            }
            final boolean changed = next != this.value;
            if (!dryRun) {
                this.value = next;
                this.fromFile = raw != null;
            }
            return changed;
        }

        @Override
        public void reset() {
            this.value = this.def;
            this.fromFile = false;
        }
    }

    public static final class IntOpt extends Opt<Integer> {
        private final int def;
        private final int min;
        private final int max;
        private volatile int value;

        public IntOpt(final String path, final int def, final int min, final int max,
                      final Reload reload, final String... comment) {
            super(path, reload, comment);
            if (def < min || def > max) {
                throw new IllegalStateException("Rolia: default out of range for " + path);
            }
            this.def = def;
            this.min = min;
            this.max = max;
            this.value = def;
        }

        /** Hot path: one volatile read. */
        public int get0() {
            return this.value;
        }

        @Override
        public Integer get() {
            return this.value;
        }

        @Override
        public Integer defaultValue() {
            return this.def;
        }

        @Override
        public String yamlValue() {
            return Integer.toString(this.value);
        }

        @Override
        public boolean apply(final Object raw, final boolean dryRun) {
            int next = this.def;
            if (raw instanceof Number n) {
                next = n.intValue();
            } else if (raw != null) {
                try {
                    next = Integer.parseInt(String.valueOf(raw).trim());
                } catch (final NumberFormatException ignored) {
                    // keep the default; the loader reports unparseable values
                }
            }
            // Clamp rather than reject: an out-of-range number is almost always a typo, and refusing to
            // start over a cosmetic value would be worse than quietly using the nearest legal one. The
            // loader logs the clamp so it is not silent.
            if (next < this.min) {
                next = this.min;
            } else if (next > this.max) {
                next = this.max;
            }
            final boolean changed = next != this.value;
            if (!dryRun) {
                this.value = next;
                this.fromFile = raw != null;
            }
            return changed;
        }

        @Override
        public void reset() {
            this.value = this.def;
            this.fromFile = false;
        }

        public int min() {
            return this.min;
        }

        public int max() {
            return this.max;
        }
    }

    public static final class DoubleOpt extends Opt<Double> {
        private final double def;
        private final double min;
        private final double max;
        private volatile double value;

        public DoubleOpt(final String path, final double def, final double min, final double max,
                         final Reload reload, final String... comment) {
            super(path, reload, comment);
            this.def = def;
            this.min = min;
            this.max = max;
            this.value = def;
        }

        public double get0() {
            return this.value;
        }

        @Override
        public Double get() {
            return this.value;
        }

        @Override
        public Double defaultValue() {
            return this.def;
        }

        @Override
        public String yamlValue() {
            return Double.toString(this.value);
        }

        @Override
        public boolean apply(final Object raw, final boolean dryRun) {
            double next = this.def;
            if (raw instanceof Number n) {
                next = n.doubleValue();
            } else if (raw != null) {
                try {
                    next = Double.parseDouble(String.valueOf(raw).trim());
                } catch (final NumberFormatException ignored) {
                    // keep the default
                }
            }
            if (Double.isNaN(next)) {
                next = this.def;
            }
            if (next < this.min) {
                next = this.min;
            } else if (next > this.max) {
                next = this.max;
            }
            final boolean changed = Double.compare(next, this.value) != 0;
            if (!dryRun) {
                this.value = next;
                this.fromFile = raw != null;
            }
            return changed;
        }

        @Override
        public void reset() {
            this.value = this.def;
            this.fromFile = false;
        }
    }

    public static final class StringOpt extends Opt<String> {
        private final String def;
        private final Set<String> allowed;
        private volatile String value;

        /** @param allowed when non-empty, the only accepted values; anything else falls back to the default. */
        public StringOpt(final String path, final String def, final Set<String> allowed,
                         final Reload reload, final String... comment) {
            super(path, reload, comment);
            this.def = def;
            this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
            this.value = def;
        }

        public String get0() {
            return this.value;
        }

        @Override
        public String get() {
            return this.value;
        }

        @Override
        public String defaultValue() {
            return this.def;
        }

        @Override
        public String yamlValue() {
            return "\"" + this.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        @Override
        public boolean apply(final Object raw, final boolean dryRun) {
            String next = raw == null ? this.def : String.valueOf(raw).trim();
            if (!this.allowed.isEmpty() && !this.allowed.contains(next)) {
                next = this.def;
            }
            final boolean changed = !next.equals(this.value);
            if (!dryRun) {
                this.value = next;
                this.fromFile = raw != null;
            }
            return changed;
        }

        @Override
        public void reset() {
            this.value = this.def;
            this.fromFile = false;
        }

        public Set<String> allowed() {
            return this.allowed;
        }
    }

    public static final class StringListOpt extends Opt<Set<String>> {
        private final Set<String> def;
        private volatile Set<String> value;

        public StringListOpt(final String path, final Set<String> def, final Reload reload, final String... comment) {
            super(path, reload, comment);
            this.def = Set.copyOf(def);
            this.value = this.def;
        }

        public Set<String> get0() {
            return this.value;
        }

        @Override
        public Set<String> get() {
            return this.value;
        }

        @Override
        public Set<String> defaultValue() {
            return this.def;
        }

        @Override
        public String yamlValue() {
            final Set<String> v = this.value;
            if (v.isEmpty()) {
                return "[]";
            }
            final List<String> sorted = new ArrayList<>(v);
            Collections.sort(sorted);
            final StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(sorted.get(i).replace("\"", "\\\"")).append('"');
            }
            return sb.append(']').toString();
        }

        @Override
        public boolean apply(final Object raw, final boolean dryRun) {
            Set<String> next = this.def;
            if (raw instanceof List<?> list) {
                final Set<String> parsed = new LinkedHashSet<>();
                for (final Object o : list) {
                    if (o != null) {
                        final String s = String.valueOf(o).trim();
                        if (!s.isEmpty()) {
                            parsed.add(s);
                        }
                    }
                }
                next = Set.copyOf(parsed);
            } else if (raw instanceof String s && !s.isBlank()) {
                // Tolerate a comma-separated string, which is what people write by hand.
                next = Set.copyOf(Arrays.stream(s.split(",")).map(String::trim)
                    .filter(x -> !x.isEmpty()).toList());
            }
            final boolean changed = !next.equals(this.value);
            if (!dryRun) {
                this.value = next;
                this.fromFile = raw != null;
            }
            return changed;
        }

        @Override
        public void reset() {
            this.value = this.def;
            this.fromFile = false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading a dotted path out of the parsed YAML tree
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - resolve {@code a.b.c} against the parsed document.
     *
     * <p>Returns null when any level is missing or is not a map, which the option types treat as "use
     * the default". Deliberately tolerant: a partially hand-edited file should lose one setting, not
     * fail to start.</p>
     */
    public static Object resolve(final Map<String, Object> root, final String path) {
        if (root == null) {
            return null;
        }
        Object cur = root;
        int from = 0;
        while (true) {
            final int dot = path.indexOf('.', from);
            final String key = dot < 0 ? path.substring(from) : path.substring(from, dot);
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(key);
            if (dot < 0) {
                return cur;
            }
            from = dot + 1;
        }
    }
}
