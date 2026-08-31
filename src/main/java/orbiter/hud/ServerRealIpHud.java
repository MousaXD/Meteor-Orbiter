package orbiter.hud;

import orbiter.Orbiter;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import net.minecraft.client.Minecraft;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class ServerRealIpHud extends BaseServerInfoHud {
    public static final HudElementInfo<ServerRealIpHud> INFO = new HudElementInfo<>(
        Orbiter.HUD_GROUP, "server-real-ip",
        "Shows the resolved remote connection address. On proxied networks this may be the proxy, not the backend server.",
        ServerRealIpHud::new
    );

    public ServerRealIpHud() {
        super(INFO);
    }

    @Override
    protected String getText() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;

        SocketAddress address = mc.getConnection().getConnection().getRemoteAddress();
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
            return "Remote IP: " + host;
        }
        return "Remote IP: " + address;
    }
}
