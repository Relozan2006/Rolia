package io.rolia.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.command.Command;
import io.rolia.RoliaConfig;
import io.rolia.config.ConfigWriter;
import io.rolia.config.Opt;
import io.rolia.secureseed.Globals;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

/**
 * Rolia - {@code /rolia status} and {@code /rolia reload}.
 *
 * <p>Registered as a subcommand of Canvas's command tree with {@code isAllowedSelfCommand()} true,
 * which means Brigadier also exposes it standalone as {@code /rolia} - so it reads as its own command
 * without a separate registration path to maintain.</p>
 */
@NullMarked
public class RoliaCommand implements Command {

    private static final int MAX_LISTED = 40;

    @Override
    public String getName() {
        return "rolia";
    }

    @Override
    public @Nullable String getDescription() {
        return "Shows what Rolia is actually doing, and reloads rolia.yml";
    }

    @Override
    public boolean isAllowedSelfCommand() {
        return true; // also available as /rolia and /canvas:rolia
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base) {
        return base
            .executes(ctx -> {
                status(ctx.getSource());
                return 1;
            })
            .then(literal("status").executes(ctx -> {
                status(ctx.getSource());
                return 1;
            }))
            .then(literal("reload").executes(ctx -> {
                reload(ctx.getSource());
                return 1;
            }));
    }

    /**
     * Rolia - report what is actually in effect.
     *
     * <p>Deliberately reports the LIVE values rather than re-reading the file, because the whole point
     * of the command is to answer "what is this server doing right now", which is not always what the
     * file on disk says - someone may have edited it since the last restart.</p>
     *
     * <p>The secret is never printed. The fingerprint is: it is a one-way function of the secret, and
     * being able to compare two servers' fingerprints without exchanging secrets is exactly the point
     * of having one.</p>
     */
    private static void status(final CommandSourceStack source) {
        final boolean seedOn = RoliaConfig.secureSeedEnabled();
        source.sendSystemMessage(Component.literal("Rolia configuration status")
            .withColor(0xFFFFFF));

        if (seedOn) {
            source.sendSystemMessage(Component.literal("  secure seed: "
                    + (Globals.isActive() ? "ACTIVE  fingerprint " + Globals.seedFingerprint() : "enabled, not yet initialised"))
                .withColor(0x7BE38B));
            source.sendSystemMessage(Component.literal("  terrain shape is public (level-seed); climate, caves, ores, structures are secret")
                .withColor(0xAAAAAA));
        } else {
            source.sendSystemMessage(Component.literal("  secure seed: DISABLED - worldgen is plain Vanilla from the level-seed")
                .withColor(0xFFD24A));
        }

        // Only non-default options, because on a stock server that list is empty and saying so is more
        // informative than printing fifty lines of "false".
        final List<Opt<?>> all = RoliaConfig.allOptions();
        int shown = 0;
        int nonDefault = 0;
        for (final Opt<?> o : all) {
            if (o.isSecret() || o.isDefault()) {
                continue;
            }
            nonDefault++;
            if (shown < MAX_LISTED) {
                shown++;
                source.sendSystemMessage(Component.literal("  " + o.path + " = " + o.yamlValue()
                        + (o.reload == Opt.Reload.RESTART ? "  (restart-only)" : ""))
                    .withColor(0x8FC8FF));
            }
        }
        if (nonDefault == 0) {
            source.sendSystemMessage(Component.literal("  all " + all.size()
                    + " options are at their defaults - this is stock Canvas behaviour plus the secure seed")
                .withColor(0xAAAAAA));
        } else {
            if (nonDefault > shown) {
                source.sendSystemMessage(Component.literal("  ... and " + (nonDefault - shown) + " more")
                    .withColor(0xAAAAAA));
            }
            source.sendSystemMessage(Component.literal("  " + nonDefault + " of " + all.size() + " options changed from default")
                .withColor(0xAAAAAA));
        }
        source.sendSystemMessage(Component.literal("  documentation: " + ConfigWriter.DOCS_URL_RU)
            .withColor(0xAAAAAA));
    }

    /**
     * Rolia - re-read rolia.yml and apply only what can safely change at runtime.
     *
     * <p>Options marked restart-only are compared but not applied, and named explicitly. Reporting
     * "reloaded" and then quietly not applying half of it is how an operator ends up debugging a
     * setting that was never in effect.</p>
     */
    private static void reload(final CommandSourceStack source) {
        final long start = System.nanoTime();
        final RoliaConfig.ReloadResult result = RoliaConfig.reload();
        final double ms = (System.nanoTime() - start) / 1e6;

        if (result.error() != null) {
            source.sendSystemMessage(Component.literal("Rolia: rolia.yml could not be read - nothing was changed.")
                .withColor(0xFF5555));
            source.sendSystemMessage(Component.literal("  " + result.error()).withColor(0xFF5555));
            return;
        }

        if (result.applied().isEmpty()) {
            source.sendSystemMessage(Component.literal(
                    String.format("Rolia: reloaded rolia.yml in %.1fms - no runtime option changed.", ms))
                .withColor(0xAAAAAA));
        } else {
            source.sendSystemMessage(Component.literal(
                    String.format("Rolia: reloaded rolia.yml in %.1fms, %d option(s) applied:", ms, result.applied().size()))
                .withColor(0x7BE38B));
            for (final String line : result.applied()) {
                source.sendSystemMessage(Component.literal("  " + line).withColor(0x8FC8FF));
            }
        }

        if (!result.needsRestart().isEmpty()) {
            source.sendSystemMessage(Component.literal(
                    "Rolia: " + result.needsRestart().size() + " option(s) changed in the file but need a RESTART; they were NOT applied:")
                .withColor(0xFFD24A));
            for (final String path : result.needsRestart()) {
                source.sendSystemMessage(Component.literal("  " + path).withColor(0xFFD24A));
            }
        }

        // The secret is deliberately not re-read. Changing it mid-run would mean chunks generated after
        // the reload disagree with chunks generated before it, in the same session, with no warning.
        source.sendSystemMessage(Component.literal("  (the secure seed is never reloaded at runtime - restart to change it)")
            .withColor(0xAAAAAA));
    }

}
