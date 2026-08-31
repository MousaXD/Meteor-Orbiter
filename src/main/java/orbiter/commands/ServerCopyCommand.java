package orbiter.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import orbiter.modules.misc.ServerMonitor;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ServerCopyCommand extends Command {
    public ServerCopyCommand() {
        super("servercopy", "Copies a compact server diagnostics snapshot to the clipboard.");
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

            String output = "Server: " + monitor.getServerAddress() + '\n'
                + "Remote IP: " + monitor.getRemoteAddress() + '\n'
                + "Brand: " + monitor.getServerBrand() + '\n'
                + "Version: " + monitor.getServerVersion() + '\n'
                + "Protocol: " + (monitor.getProtocolVersion() > 0 ? monitor.getProtocolVersion() : "Unknown") + '\n'
                + "Health: " + monitor.getHealth().name() + '\n'
                + "Stability: " + monitor.getStabilityScore() + "/100\n"
                + "Ping: " + monitor.getCurrentPing() + " ms (avg " + Math.round(monitor.getAveragePing())
                + ", jitter " + Math.round(monitor.getPingJitter()) + ", max " + monitor.getMaxPing() + ")\n"
                + "TPS: " + String.format("%.1f", monitor.getCurrentTps()) + " (avg "
                + String.format("%.1f", monitor.getAverageTps()) + ", min " + String.format("%.1f", monitor.getMinimumTps()) + ")\n"
                + "Players: " + monitor.getCurrentPlayers() + " (peak " + monitor.getPeakPlayers() + ")\n"
                + "Packets: in " + monitor.getInboundPps() + " pps / out " + monitor.getOutboundPps() + " pps\n"
                + "Session packets: in " + monitor.getTotalInboundPackets() + " / out " + monitor.getTotalOutboundPackets() + '\n'
                + "Lag spikes: " + monitor.getLagSpikes() + '\n'
                + "Detected plugins: " + monitor.getDetectedPlugins() + '\n'
                + "Advertised command roots: " + monitor.getCommandRootCount();

            mc.keyboardHandler.setClipboard(output);
            info("Copied server diagnostics to clipboard.");
            return SINGLE_SUCCESS;
        });
    }
}
