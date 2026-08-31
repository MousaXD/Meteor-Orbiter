package orbiter.hud;

import orbiter.Orbiter;
import orbiter.modules.misc.PeakPluginScanner;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import net.minecraft.client.Minecraft;

public class ServerIpHud extends BaseServerInfoHud {
    public static final HudElementInfo<ServerIpHud> INFO = new HudElementInfo<>(
        Orbiter.HUD_GROUP, "server-ip",
        "Shows the configured server address. Use Server Real IP for the resolved socket address.",
        ServerIpHud::new
    );

    public ServerIpHud() {
        super(INFO);
    }

    @Override
    protected String getText() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;

        PeakPluginScanner scanner = scanner();
        String address = scanner != null ? scanner.getServerIp() : null;
        if (address == null && mc.getConnection() != null && mc.getConnection().getServerData() != null) {
            address = mc.getConnection().getServerData().ip;
        }
        return "Server: " + (address != null ? address : "Unknown");
    }
}
