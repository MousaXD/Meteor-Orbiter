package orbiter.hud;

import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import orbiter.Orbiter;
import orbiter.modules.misc.ServerMonitor;

public class ServerNetworkHud extends BaseServerInfoHud {
    public static final HudElementInfo<ServerNetworkHud> INFO = new HudElementInfo<>(
        Orbiter.HUD_GROUP,
        "server-network",
        "Shows ping jitter, packet rates and the current connection stability score.",
        ServerNetworkHud::new
    );

    public ServerNetworkHud() {
        super(INFO);
    }

    @Override
    protected String getText() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return null;

        ServerMonitor monitor = Modules.get().get(ServerMonitor.class);
        if (monitor == null || !monitor.isOnline()) return "Network: waiting for Server Monitor";
        return "Network: " + monitor.networkSummary();
    }
}
