package orbiter.hud;

import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.Minecraft;
import orbiter.Orbiter;
import orbiter.modules.misc.ServerMonitor;

public class ServerHealthHud extends BaseServerInfoHud {
    public static final HudElementInfo<ServerHealthHud> INFO = new HudElementInfo<>(
        Orbiter.HUD_GROUP,
        "server-health",
        "Shows ping, TPS, player count and an overall connection-health label.",
        ServerHealthHud::new
    );

    public ServerHealthHud() {
        super(INFO);
    }

    @Override
    protected String getText() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return null;

        ServerMonitor monitor = Modules.get().get(ServerMonitor.class);
        if (monitor != null && monitor.isOnline()) {
            return "Server: " + monitor.summary();
        }

        int ping = Math.max(0, PlayerUtils.getPing());
        float tps = Math.max(0.0f, Math.min(20.0f, TickRate.INSTANCE.getTickRate()));
        int players = mc.getConnection().getOnlinePlayers().size();
        return "Server: " + ping + " ms | " + String.format("%.1f", tps) + " TPS | " + players + " players";
    }
}
