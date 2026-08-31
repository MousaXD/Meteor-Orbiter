package orbiter.modules.misc;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import orbiter.Orbiter;
import orbiter.util.ServerCapabilities;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Passive connection telemetry for multiplayer servers.
 *
 * This module only consumes state the vanilla client has already received. It does not send
 * probes, chat commands, plugin messages or extra keep-alives, which makes it safe to leave
 * enabled on normal servers.
 */
public class ServerMonitor extends Module {
    public enum Health {
        EXCELLENT,
        GOOD,
        DEGRADED,
        BAD,
        OFFLINE
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Integer> sampleInterval = sgGeneral.add(new IntSetting.Builder()
        .name("sample-interval")
        .description("Ticks between telemetry samples. 20 ticks is about one second.")
        .defaultValue(20)
        .min(5)
        .sliderRange(5, 200)
        .build());

    private final Setting<Boolean> highPingAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("high-ping-alerts")
        .description("Warn when latency stays above the configured threshold.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> highPingThreshold = sgAlerts.add(new IntSetting.Builder()
        .name("high-ping-threshold")
        .description("Latency in milliseconds considered high.")
        .defaultValue(250)
        .min(50)
        .sliderRange(50, 1000)
        .visible(highPingAlerts::get)
        .build());

    private final Setting<Boolean> lowTpsAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("low-tps-alerts")
        .description("Warn when measured server TPS stays below the configured threshold.")
        .defaultValue(true)
        .build());

    private final Setting<Double> lowTpsThreshold = sgAlerts.add(new DoubleSetting.Builder()
        .name("low-tps-threshold")
        .description("Measured TPS considered unhealthy.")
        .defaultValue(15.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .visible(lowTpsAlerts::get)
        .build());

    private final Setting<Integer> sustainedSamples = sgAlerts.add(new IntSetting.Builder()
        .name("sustained-samples")
        .description("How many bad samples are required before an alert is emitted.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> alertCooldown = sgAlerts.add(new IntSetting.Builder()
        .name("alert-cooldown-seconds")
        .description("Minimum time between repeated alerts of the same type.")
        .defaultValue(30)
        .min(5)
        .sliderRange(5, 300)
        .build());

    private final Setting<Boolean> playerChangeAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("player-change-alerts")
        .description("Announce tab-list joins and leaves. Disabled by default to avoid spam on large servers.")
        .defaultValue(false)
        .build());

    private int tickCounter;
    private int currentPing;
    private int maxPing;
    private double averagePing;
    private float currentTps;
    private float minimumTps;
    private double averageTps;
    private int currentPlayers;
    private int peakPlayers;
    private int samples;
    private int highPingStreak;
    private int lowTpsStreak;
    private long connectedAt;
    private long lastPingAlertAt;
    private long lastTpsAlertAt;
    private boolean online;

    private String serverAddress = "Unknown";
    private String remoteAddress = "Unknown";
    private String serverBrand = "Unknown";
    private String serverVersion = "Unknown";
    private int protocolVersion = -1;
    private int detectedPlugins;
    private ServerCapabilities capabilities;

    private final Set<UUID> knownPlayers = new HashSet<>();

    public ServerMonitor() {
        super(Orbiter.CATEGORY, "server-monitor",
            "Passive server health telemetry, connection stats and optional lag/player alerts.");
    }

    @Override
    public void onActivate() {
        reset();
        refreshNow();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reset();
        connectedAt = System.currentTimeMillis();
        refreshNow();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        online = false;
        knownPlayers.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++tickCounter < sampleInterval.get()) return;
        tickCounter = 0;
        sample(false);
    }

    public void refreshNow() {
        sample(true);
    }

    private void sample(boolean forceBaseline) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            online = false;
            return;
        }

        online = true;
        if (connectedAt == 0) connectedAt = System.currentTimeMillis();

        currentPing = Math.max(0, PlayerUtils.getPing());
        currentTps = Math.max(0.0f, Math.min(20.0f, TickRate.INSTANCE.getTickRate()));
        currentPlayers = connection.getOnlinePlayers().size();

        if (samples == 0) {
            averagePing = currentPing;
            averageTps = currentTps;
            maxPing = currentPing;
            minimumTps = currentTps;
            peakPlayers = currentPlayers;
        } else {
            // EWMA keeps the number useful during long sessions without storing an unbounded history.
            averagePing = averagePing * 0.8 + currentPing * 0.2;
            averageTps = averageTps * 0.8 + currentTps * 0.2;
            maxPing = Math.max(maxPing, currentPing);
            if (currentTps > 0.0f) minimumTps = minimumTps <= 0.0f ? currentTps : Math.min(minimumTps, currentTps);
            peakPlayers = Math.max(peakPlayers, currentPlayers);
        }
        samples++;

        if (connection.getServerData() != null && connection.getServerData().ip != null) {
            serverAddress = connection.getServerData().ip;
        }

        SocketAddress remote = connection.getConnection().getRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            remoteAddress = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        } else if (remote != null) {
            remoteAddress = remote.toString();
        }

        capabilities = ServerCapabilities.capture(connection);
        updateScannerMetadata();
        updatePlayerSnapshot(connection, forceBaseline || samples <= 1);
        updateAlerts();
    }

    private void updateScannerMetadata() {
        try {
            PeakPluginScanner scanner = Modules.get().get(PeakPluginScanner.class);
            if (scanner == null) return;

            String brand = scanner.getServerBrand();
            String version = scanner.getServerVersion();
            if (brand != null && !brand.isBlank()) serverBrand = brand;
            if (version != null && !version.isBlank()) serverVersion = version;
            if (scanner.getProtocolVersion() > 0) protocolVersion = scanner.getProtocolVersion();
            detectedPlugins = Math.max(0, scanner.getDetectedCount());
        } catch (Exception ignored) {
            // ServerMonitor must stay independent from the active state of the scanner.
        }
    }

    private void updatePlayerSnapshot(ClientPacketListener connection, boolean baseline) {
        Set<UUID> current = new HashSet<>();

        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile() == null || info.getProfile().id() == null) continue;
            UUID id = info.getProfile().id();
            current.add(id);

            if (!baseline && playerChangeAlerts.get() && !knownPlayers.contains(id)) {
                String name = info.getProfile().name();
                info("Player joined: " + (name == null || name.isBlank() ? id : name));
            }
        }

        if (!baseline && playerChangeAlerts.get()) {
            for (UUID id : knownPlayers) {
                if (!current.contains(id)) info("Player left: " + id);
            }
        }

        knownPlayers.clear();
        knownPlayers.addAll(current);
    }

    private void updateAlerts() {
        long now = System.currentTimeMillis();
        int required = sustainedSamples.get();
        long cooldownMs = alertCooldown.get() * 1000L;

        if (highPingAlerts.get() && currentPing >= highPingThreshold.get()) highPingStreak++;
        else highPingStreak = 0;

        if (lowTpsAlerts.get() && currentTps > 0.0f && currentTps <= lowTpsThreshold.get()) lowTpsStreak++;
        else lowTpsStreak = 0;

        if (highPingStreak >= required && now - lastPingAlertAt >= cooldownMs) {
            lastPingAlertAt = now;
            warning("Server ping is high: " + currentPing + " ms (avg " + Math.round(averagePing) + " ms).");
        }

        if (lowTpsStreak >= required && now - lastTpsAlertAt >= cooldownMs) {
            lastTpsAlertAt = now;
            warning("Server TPS is low: " + String.format("%.1f", currentTps)
                + " (avg " + String.format("%.1f", averageTps) + ").");
        }
    }

    private void reset() {
        tickCounter = 0;
        currentPing = 0;
        maxPing = 0;
        averagePing = 0;
        currentTps = 0;
        minimumTps = 0;
        averageTps = 0;
        currentPlayers = 0;
        peakPlayers = 0;
        samples = 0;
        highPingStreak = 0;
        lowTpsStreak = 0;
        connectedAt = 0;
        lastPingAlertAt = 0;
        lastTpsAlertAt = 0;
        online = false;
        serverAddress = "Unknown";
        remoteAddress = "Unknown";
        serverBrand = "Unknown";
        serverVersion = "Unknown";
        protocolVersion = -1;
        detectedPlugins = 0;
        capabilities = null;
        knownPlayers.clear();
    }

    public Health getHealth() {
        if (!online) return Health.OFFLINE;
        if (currentPing >= 400 || (currentTps > 0.0f && currentTps < 10.0f)) return Health.BAD;
        if (currentPing >= 200 || (currentTps > 0.0f && currentTps < 16.0f)) return Health.DEGRADED;
        if (currentPing >= 100 || (currentTps > 0.0f && currentTps < 19.0f)) return Health.GOOD;
        return Health.EXCELLENT;
    }

    public boolean isOnline() { return online; }
    public int getCurrentPing() { return currentPing; }
    public int getMaxPing() { return maxPing; }
    public double getAveragePing() { return averagePing; }
    public float getCurrentTps() { return currentTps; }
    public float getMinimumTps() { return minimumTps; }
    public double getAverageTps() { return averageTps; }
    public int getCurrentPlayers() { return currentPlayers; }
    public int getPeakPlayers() { return peakPlayers; }
    public String getServerAddress() { return serverAddress; }
    public String getRemoteAddress() { return remoteAddress; }
    public String getServerBrand() { return serverBrand; }
    public String getServerVersion() { return serverVersion; }
    public int getProtocolVersion() { return protocolVersion; }
    public int getDetectedPlugins() { return detectedPlugins; }
    public int getCommandRootCount() { return capabilities == null ? 0 : capabilities.roots().size(); }
    public ServerCapabilities getCapabilities() { return capabilities; }

    public long getSessionSeconds() {
        if (!online || connectedAt == 0) return 0;
        return Math.max(0, (System.currentTimeMillis() - connectedAt) / 1000L);
    }

    public String summary() {
        if (!online) return "Offline";
        return currentPing + " ms | " + String.format("%.1f", currentTps) + " TPS | "
            + currentPlayers + " players | " + getHealth().name();
    }
}
