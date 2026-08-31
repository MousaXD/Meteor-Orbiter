package orbiter.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import orbiter.modules.misc.ServerMonitor;
import orbiter.util.ServerCapabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ServerCapsCommand extends Command {
    public ServerCapsCommand() {
        super("servercaps", "Copies the command roots advertised by the connected server to the clipboard.");
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
            ServerCapabilities capabilities = monitor.getCapabilities();
            if (capabilities == null || !capabilities.isAuthoritative()) {
                error("The server command tree is not available yet.");
                return SINGLE_SUCCESS;
            }

            List<String> roots = new ArrayList<>(capabilities.roots());
            Collections.sort(roots);
            String output = String.join("\n", roots);
            mc.keyboardHandler.setClipboard(output);
            info("Copied " + roots.size() + " advertised server command roots to clipboard.");

            if (!roots.isEmpty()) {
                int preview = Math.min(12, roots.size());
                info("Preview: " + String.join(", ", roots.subList(0, preview)) + (roots.size() > preview ? ", ..." : ""));
            }

            return SINGLE_SUCCESS;
        });
    }
}
