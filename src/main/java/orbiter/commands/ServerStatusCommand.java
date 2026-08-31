package orbiter.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import orbiter.modules.misc.ServerMonitor;
import orbiter.util.ServerCapabilities;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ServerStatusCommand extends Command {
    public ServerStatusCommand() {
        super("serverstatus", "Shows passive server connection and health telemetry.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            ServerMonitor monitor = Modules.get().get(ServerMonitor.class);
            if (monitor == null) {
                error("Server Monitor is not registered.");
                return SINGLE_SUCCESS;
            }

            monitor.refreshNow();
            if (!monitor.isOnline()) {
                error("Not connected to a multiplayer server.");
                return SINGLE_SUCCESS;
            }

            info("Server Health: " + monitor.getHealth().name());
            info("Address: " + monitor.getServerAddress() + " | Remote IP: " + monitor.getRemoteAddress());
            info("Ping: " + monitor.getCurrentPing() + " ms | Avg: " + Math.round(monitor.getAveragePing())
                + " ms | Max: " + monitor.getMaxPing() + " ms");
            info("TPS: " + String.format("%.1f", monitor.getCurrentTps()) + " | Avg: "
                + String.format("%.1f", monitor.getAverageTps()) + " | Min: "
                + String.format("%.1f", monitor.getMinimumTps()));
            info("Players: " + monitor.getCurrentPlayers() + " | Session peak: " + monitor.getPeakPlayers());
            info("Brand: " + monitor.getServerBrand() + " | Version: " + monitor.getServerVersion()
                + " | Protocol: " + (monitor.getProtocolVersion() > 0 ? monitor.getProtocolVersion() : "Unknown"));
            info("Detected plugins: " + monitor.getDetectedPlugins() + " | Advertised command roots: "
                + monitor.getCommandRootCount());
            info("Session: " + formatDuration(monitor.getSessionSeconds()));

            ServerCapabilities capabilities = monitor.getCapabilities();
            if (capabilities != null) {
                info("Capabilities: WorldEdit=" + shortState(stateAny(capabilities, "worldedit", "worldedit:wand", "wand"))
                    + " /give=" + shortState(stateAny(capabilities, "give", "minecraft:give", "essentials:give"))
                    + " /home=" + shortState(stateAny(capabilities, "home", "essentials:home"))
                    + " /spawn=" + shortState(stateAny(capabilities, "spawn", "essentials:spawn")));
            }

            return SINGLE_SUCCESS;
        });
    }

    private static ServerCapabilities.State stateAny(ServerCapabilities capabilities, String... roots) {
        if (!capabilities.isAuthoritative()) return ServerCapabilities.State.UNKNOWN;
        for (String root : roots) {
            if (capabilities.has(root)) return ServerCapabilities.State.AVAILABLE;
        }
        return ServerCapabilities.State.UNAVAILABLE;
    }

    private static String shortState(ServerCapabilities.State state) {
        return switch (state) {
            case AVAILABLE -> "yes";
            case UNAVAILABLE -> "no";
            case UNKNOWN -> "unknown";
        };
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }
}
