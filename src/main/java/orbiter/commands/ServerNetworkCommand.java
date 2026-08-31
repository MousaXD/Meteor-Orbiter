package orbiter.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import orbiter.modules.misc.ServerMonitor;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ServerNetworkCommand extends Command {
    public ServerNetworkCommand() {
        super("servernetwork", "Shows passive packet-rate, jitter and connection stability telemetry.");
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

            info("Network Stability: " + monitor.getStabilityScore() + "/100");
            info("Ping: " + monitor.getCurrentPing() + " ms | Avg: " + Math.round(monitor.getAveragePing())
                + " ms | Jitter: " + Math.round(monitor.getPingJitter()) + " ms | Max: " + monitor.getMaxPing() + " ms");
            info("Inbound: " + monitor.getInboundPps() + " pps | Avg: " + Math.round(monitor.getAverageInboundPps())
                + " | Peak: " + monitor.getPeakInboundPps() + " | Session packets: " + monitor.getTotalInboundPackets());
            info("Outbound: " + monitor.getOutboundPps() + " pps | Avg: " + Math.round(monitor.getAverageOutboundPps())
                + " | Peak: " + monitor.getPeakOutboundPps() + " | Session packets: " + monitor.getTotalOutboundPackets());
            info("Lag spikes: " + monitor.getLagSpikes() + " | TPS: " + String.format("%.1f", monitor.getCurrentTps())
                + " | Health: " + monitor.getHealth().name());

            return SINGLE_SUCCESS;
        });
    }
}
