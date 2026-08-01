package io.rolia.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolia - renders the option registry as {@code rolia.yml}, and as a machine-readable key list.
 *
 * <p>Everything here is derived from {@link Opt#entries()}, so the generated file cannot drift from the
 * options the server actually reads. Adding an option to the registry adds it to the file, to
 * the startup line, and to the key list CI compares against the Russian documentation - with no
 * second edit anywhere.</p>
 */
public final class ConfigWriter {

    /** Rolia - where the Russian translation of every comment in this file lives. */
    public static final String DOCS_URL_RU =
        "https://github.com/Relozan2006/Rolia/blob/main/docs/rolia.yml.ru.md";

    private ConfigWriter() {
    }

    /**
     * Rolia - render the whole configuration file.
     *
     * <p>Values come from the CURRENT state of the registry, so writing the file after loading it
     * round-trips exactly, and writing it on a fresh server emits the compiled-in defaults plus the
     * freshly generated secret.</p>
     */
    public static String render(final String[] header) {
        final StringBuilder sb = new StringBuilder(64 * 1024);
        for (final String line : header) {
            sb.append(line.isEmpty() ? "" : "# " + line).append('\n');
        }
        sb.append('\n');

        // The path segments currently open, e.g. ["optimizations", "dab"].
        final List<String> open = new ArrayList<>();
        boolean atStart = true;

        for (final Object entry : Opt.entries()) {
            if (entry instanceof Opt.Note n) {
                if (!atStart) {
                    sb.append('\n');
                }
                final String pad = "  ".repeat(open.size());
                for (final String line : n.lines) {
                    sb.append(pad).append(line.isEmpty() ? "#" : "# " + line).append('\n');
                }
                atStart = false;
                continue;
            }

            final String path;
            final String[] comment;
            final boolean isSection;
            if (entry instanceof Opt.Section s) {
                path = s.path;
                comment = s.comment;
                isSection = true;
            } else {
                final Opt<?> o = (Opt<?>) entry;
                path = o.path;
                comment = o.comment;
                isSection = false;
            }

            final String[] segs = path.split("\\.");
            // For an option the last segment is the key itself; for a section every segment is a level.
            final int parentCount = isSection ? segs.length : segs.length - 1;

            // Close down to the common prefix with what is currently open.
            int common = 0;
            while (common < open.size() && common < parentCount && open.get(common).equals(segs[common])) {
                common++;
            }
            while (open.size() > common) {
                open.remove(open.size() - 1);
            }

            // Open any parents that are still missing. A parent opened implicitly (because an option
            // named it) gets no comment; a parent opened by a Section declaration gets the section's.
            for (int i = common; i < parentCount; i++) {
                final boolean last = i == parentCount - 1;
                final String pad = "  ".repeat(i);
                if (!atStart) {
                    sb.append('\n');
                }
                if (last && isSection) {
                    for (final String line : comment) {
                        sb.append(pad).append(line.isEmpty() ? "#" : "# " + line).append('\n');
                    }
                }
                sb.append(pad).append(segs[i]).append(":\n");
                open.add(segs[i]);
                atStart = false;
            }

            if (isSection) {
                continue;
            }

            final Opt<?> opt = (Opt<?>) entry;
            final String pad = "  ".repeat(parentCount);
            if (comment.length > 0) {
                if (!atStart) {
                    sb.append('\n');
                }
                for (final String line : comment) {
                    sb.append(pad).append(line.isEmpty() ? "#" : "# " + line).append('\n');
                }
            }
            if (opt.reload == Opt.Reload.RESTART) {
                // Rolia - build 46: this used to say "/rolia reload will not apply it". Build 46 removed
                // the /rolia command along with the options it existed to report, so the shipped file was
                // telling every operator to try a command that does not exist.
                sb.append(pad).append("# (takes effect on the next server restart)\n");
            }
            sb.append(pad).append(segs[segs.length - 1]).append(": ").append(opt.yamlValue()).append('\n');
            atStart = false;
        }
        return sb.toString();
    }

    /**
     * Rolia - one line per option: {@code path|type|default|reload}.
     *
     * <p>Written to disk when {@code -Drolia.dumpConfigKeys=<file>} is set, and used by CI to prove that
     * {@code docs/rolia.yml.ru.md} documents exactly the options that exist - no more, no less. That
     * check is what stops the Russian documentation from rotting the moment an option is added.</p>
     *
     * <p>Secret-valued options are listed by path and type only; their values never appear.</p>
     */
    public static String dumpKeys() {
        final StringBuilder sb = new StringBuilder(8 * 1024);
        for (final Opt<?> o : Opt.options()) {
            sb.append(o.path).append('|')
                .append(typeName(o)).append('|')
                .append(o.isSecret() ? "<secret>" : String.valueOf(o.defaultValue())).append('|')
                .append(o.reload)
                .append('\n');
        }
        return sb.toString();
    }

    public static String typeName(final Opt<?> o) {
        if (o instanceof Opt.BoolOpt) {
            return "bool";
        }
        if (o instanceof Opt.IntOpt) {
            return "int";
        }
        if (o instanceof Opt.DoubleOpt) {
            return "double";
        }
        if (o instanceof Opt.StringListOpt) {
            return "list";
        }
        return "string";
    }
}
