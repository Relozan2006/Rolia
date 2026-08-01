package io.canvasmc.canvas.util.version;

import com.destroystokyo.paper.util.VersionFetcher;
import io.canvasmc.canvas.util.Util;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import oshi.SystemInfo;

import static net.kyori.adventure.text.Component.text;

/**
 * This is a replacement version fetcher that is intended to replace {@link com.destroystokyo.paper.PaperVersionFetcher}
 * for general simplicity purposes of the rebrand patch(I don't want to replace half of the paper version fetcher,
 * because I'm lazy)
 *
 * @author dueris
 */
@NullMarked
public class CanvasVersionFetcher implements VersionFetcher {

    private static final TextColor RED = TextColor.color(0xFF5300);
    private static final TextColor YELLOW = TextColor.color(0xFFCD00);
    private static final TextColor GREEN = TextColor.color(0x4DE54D);

    private static final TextColor HEADER = TextColor.color(240, 106, 72);
    private static final TextColor PRIMARY = TextColor.color(200, 69, 32);
    private static final TextColor SECONDARY = TextColor.color(242, 144, 110);
    private static final TextColor INFORMATION = TextColor.color(252, 221, 213);
    private static final TextColor LIST = TextColor.color(122, 32, 10);

    private static final AtomicBoolean USE_CACHE = new AtomicBoolean(true);

    @Override
    public long getCacheTime() {
        if (!USE_CACHE.get()) {
            return Long.MIN_VALUE;
        }
        return 720000;
    }

    @Override
    public Component getVersionMessage() {
        return Component.empty();
    }

    @Nullable
    @Override
    public Component getFullOutMessage() {
        final TextComponent.Builder builder = text();
        final ServerBuildInfoImpl buildInfo = (ServerBuildInfoImpl) ServerBuildInfo.buildInfo();

        builder.append(text("[", LIST, TextDecoration.BOLD));

        final Component contributors =
            Arrays.stream(buildInfo.contributors()
                .replace("[", "")
                .replace("]", "")
                .split("\\s*,\\s*")
            ).map(name -> Component.text()
                .append(text(" - ", LIST))
                .append(text(name, INFORMATION))
                .build())
            .reduce((a, b) -> a.append(Component.newline()).append(b))
            .orElse(Component.empty());

        TextComponent brandHoverText = Component.textOfChildren(
            text(buildInfo.brandName(), HEADER, TextDecoration.BOLD),
            text(" made by ", PRIMARY),
            text(buildInfo.brandVendor().orElse("Unknown Vendor"), HEADER, TextDecoration.BOLD),
            text("\nOther Contributors:\n", PRIMARY),
            contributors
        );
        TextComponent brandComponent = text(buildInfo.brandName(), HEADER, TextDecoration.BOLD);
        if (buildInfo.brandWebsite().isPresent()) {
            brandHoverText = brandHoverText.append(
                text("\n"),
                text(buildInfo.brandWebsite().get(), INFORMATION, TextDecoration.UNDERLINED),
                text(" - Click to open", PRIMARY)
            );
            brandComponent = brandComponent.clickEvent(ClickEvent.openUrl(buildInfo.brandWebsite().get()));
        }
        brandComponent = brandComponent.hoverEvent(HoverEvent.showText(brandHoverText));

        builder.append(brandComponent);
        builder.append(text("] ", LIST, TextDecoration.BOLD));
        builder.append(text(buildInfo.minecraftVersionName(), HEADER));
        builder.append(text(" | ", HEADER, TextDecoration.BOLD));

        builder.append(text(buildInfo.gitBranch().orElse("(Unknown Git Branch)"), SECONDARY));

        if (buildInfo.buildNumber().isPresent()) {
            builder.append(text("#", HEADER));
            builder.append(text(buildInfo.buildNumber().getAsInt(), SECONDARY));
            builder.append(text(" [", HEADER));

            // Rolia - build 46: derive the repository from Brand-Website instead of hard-coding
            // CraftCanvasMC/Canvas. The commit hash printed here is Rolia's, so the old link resolved
            // to a commit that does not exist in Canvas - /version is the single most visible surface
            // on the server and it was pointing every reader at somebody else's repository.
            String url = buildInfo.brandWebsite().orElse("https://github.com/Relozan2006/Rolia");
            if (!url.endsWith("/")) {
                url = url + "/";
            }
            String commit = buildInfo.gitCommit().orElse("Unknown Commit");

            if (buildInfo.gitCommit().isPresent()) {
                url = url + "commit/" + commit;
            }

            builder.append(text(commit, INFORMATION)
                .hoverEvent(HoverEvent.showText(text("Click to view commit", SECONDARY)))
                .clickEvent(ClickEvent.openUrl(url))
            );
            builder.append(text("]", HEADER));
        }
        else {
            builder.append(text("-", HEADER));
            builder.append(text("(NULL-COMMIT)", SECONDARY));
        }

        builder.append(text(" | ", HEADER, TextDecoration.BOLD));

        final Status status = computeStatus();
        if (!status.isError()) {
            // if we passed once, we don't really need to do this again
            // because the data is consistent throughout the runtime
            USE_CACHE.set(false);
        }

        builder.append(status.getStatus());
        builder.append(text("\n"));

        builder.append(text(">> ", LIST, TextDecoration.BOLD));

        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");
        final List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();

        builder.append(text("Java ", PRIMARY));
        builder.append(text(javaSpecVersion, INFORMATION));
        builder.append(text(", (", PRIMARY));
        builder.append(text(javaVendor, INFORMATION));
        builder.append(text(") ", PRIMARY));
        builder.append(text("OS ", PRIMARY));
        builder.append(text(osName + " " + osArch, SECONDARY));
        builder.append(text(" [", PRIMARY));
        builder.append(
            text("hover", SECONDARY).hoverEvent(HoverEvent.showText(
                Component.textOfChildren(
                    text("Running Java ", PRIMARY),
                    text(javaSpecVersion, SECONDARY),
                    text(" (", PRIMARY),
                    text(javaVmName + " " + javaVmVersion, INFORMATION),
                    text("; ", PRIMARY),
                    text(javaVendor + " " + javaVendorVersion, INFORMATION),
                    text(") ", PRIMARY),
                    text("on ", PRIMARY),
                    text(osName + " " + osVersion, INFORMATION),
                    text(" (", PRIMARY),
                    text(osArch, INFORMATION),
                    text(")\n", PRIMARY),
                    text("FLAGS:", PRIMARY),
                    formatList(inputArguments),
                    text("\nClick to copy flags to clipboard", PRIMARY)
                )
            )).clickEvent(ClickEvent.copyToClipboard(String.join(" ", inputArguments)))
        );
        builder.append(text("]\n", PRIMARY));

        builder.append(text(">> ", LIST, TextDecoration.BOLD));
        builder.append(text("Mem ", PRIMARY));

        final long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem == Long.MAX_VALUE) {
            builder.append(text("MAX-UNDEFINED", INFORMATION));
        }
        else {
            builder.append(text(String.format("%.1f", maxMem / (1024.0 * 1024.0 * 1024.0)), INFORMATION));
            builder.append(text("GB ", PRIMARY));
        }

        builder.append(text("CPU ", PRIMARY));
        builder.append(text(new SystemInfo().getHardware().getProcessor().getProcessorIdentifier().getName(), SECONDARY));
        builder.append(text(" (", PRIMARY));
        builder.append(text(Runtime.getRuntime().availableProcessors(), INFORMATION));
        builder.append(text(")", PRIMARY));

        return builder.build();
    }

    /**
     * Rolia - build 46: report the local build instead of asking CanvasMC how out of date we are.
     *
     * <p>This used to call {@code Util.CANVAS_CLIENT.getLatestBuild(...)}, which queries CanvasMC's
     * build API. Rolia's build numbers do not exist there, so on a Rolia server the lookup either threw
     * or returned a Canvas build number and subtracted Rolia's from it - meaning {@code /version}
     * permanently rendered an error, or a meaningless "behind by N", and wrote a stack trace to the log
     * under the Rolia logger name every time somebody ran it.</p>
     *
     * <p>It also sent a request to a third party on a server they have nothing to do with, which is not
     * something a fork should do quietly on the operator's behalf. Rolia has no update endpoint of its
     * own, so the honest answer is the local one.</p>
     */
    private Status computeStatus() {
        // A jar with a build number came out of CI and is a release; only a local ./gradlew build has
        // none. Reporting a released build as "DEV" would be as wrong as the old lookup was.
        final OptionalInt buildNumber = ServerBuildInfo.buildInfo().buildNumber();
        return buildNumber.isPresent() ? new ReleaseStatus(buildNumber.getAsInt()) : new LocalStatus();
    }

    private static TextComponent formatList(final List<String> inputArguments) {
        final TextComponent.Builder builder = text();

        builder.append(text("[", LIST));
        for (int i = 0; i < inputArguments.size(); i++) {
            final String arg = inputArguments.get(i);
            builder.append(text(arg, INFORMATION));
            if (i != (inputArguments.size() - 1)) {
                builder.append(text(", ", SECONDARY));
            }
        }
        builder.append(text("]", LIST));

        return builder.build();
    }

    private interface Status {
        Component getStatus();

        boolean isError();
    }

    private static class ErrorStatus implements Status {
        @Contract(value = " -> new", pure = true)
        @Override
        public Component getStatus() {
            return text("ERROR", RED, TextDecoration.BOLD);
        }

        @Override
        public boolean isError() {
            return true;
        }
    }

    private static class LocalStatus implements Status {
        @Contract(value = " -> new", pure = true)
        @Override
        public Component getStatus() {
            return text("DEV", RED, TextDecoration.BOLD);
        }

        @Override
        public boolean isError() {
            return false;
        }
    }

    /**
     * Rolia - build 46: what a released build reports.
     *
     * <p>Rolia has no update endpoint, so there is no honest way to say "you are N builds behind". It
     * says which build this is and leaves it there. {@code BetaStatus} and {@code StableStatus} above
     * are unreachable now - they exist to render that distance - and are kept only because they belong
     * to Canvas's file and deleting them would widen the next rebase for no benefit.</p>
     */
    private record ReleaseStatus(int build) implements Status {
        @Contract(value = " -> new", pure = true)
        @Override
        public Component getStatus() {
            return text("BUILD " + build, GREEN, TextDecoration.BOLD);
        }

        @Override
        public boolean isError() {
            return false;
        }
    }

    private record BetaStatus(int distance) implements Status {
        @Contract(value = " -> new", pure = true)
        @Override
        public Component getStatus() {
            TextComponent base = text("BETA", YELLOW, TextDecoration.BOLD);
            if (distance > 0) {
                base = base.hoverEvent(HoverEvent.showText(text("You are " + distance + " builds out of date, please update ASAP!", YELLOW)));
            }
            else {
                base = base.hoverEvent(HoverEvent.showText(text("You are on the latest version! :)", GREEN)));
            }
            return base;
        }

        @Override
        public boolean isError() {
            return false;
        }
    }

    private record StableStatus(int distance) implements Status {
        @Contract(value = " -> new", pure = true)
        @Override
        public Component getStatus() {
            TextComponent base = text("STABLE", GREEN, TextDecoration.BOLD);
            if (distance > 0) {
                base = base.hoverEvent(HoverEvent.showText(text("You are " + distance + " builds out of date, please update ASAP!", YELLOW)));
            }
            else {
                base = base.hoverEvent(HoverEvent.showText(text("You are on the latest version! :)", GREEN)));
            }
            return base;
        }

        @Override
        public boolean isError() {
            return false;
        }
    }
}
