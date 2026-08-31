package orbiter.modules.misc;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Passive multiplayer telemetry using only state and packets already handled by the client. */
public class ServerMonitor extends Module {
    public enum Health { EXCELLENT, GOOD, DEGRADED, BAD, OFFLINE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");
    private final SettingGroup sgNetwork = settings.createGroup("Network");

    private final Setting<Integer> sampleInterval = sgGeneral.add(new IntSetting.Builder()
        .name("sample-interval").description("Ticks between telemetry samples. 20 ticks is about one second.")
        .defaultValue(20).min(5).sliderRange(5, 200).build());

    private final Setting<Boolean> highPingAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("high-ping-alerts").description("Warn when latency stays above the configured threshold.")
        .defaultValue(true).build());

    private final Setting<Integer> highPingThreshold = sgAlerts.add(new IntSetting.Builder()
        .name("high-ping-threshold").description("Latency in milliseconds considered high.")
        .defaultValue(250).min(50).sliderRange(50, 1000).visible(highPingAlerts::get).build());

    private final Setting<Boolean> lowTpsAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("low-tps-alerts").description("Warn when measured server TPS stays below the configured threshold.")
        .defaultValue(true).build());

    private final Setting<Double> lowTpsThreshold = sgAlerts.add(new DoubleSetting.Builder()
        .name("low-tps-threshold").description("Measured TPS considered unhealthy.")
        .defaultValue(15.0).min(1.0).max(20.0).sliderRange(1.0, 20.0).visible(lowTpsAlerts::get).build());

    private final Setting<Integer> sustainedSamples = sgAlerts.add(new IntSetting.Builder()
        .name("sustained-samples").description("How many bad samples are required before an alert is emitted.")
        .defaultValue(3).min(1).sliderRange(1, 10).build());

    private final Setting<Integer> alertCooldown = sgAlerts.add(new IntSetting.Builder()
        .name("alert-cooldown-seconds").description("Minimum time between repeated alerts of the same type.")
        .defaultValue(30).min(5).sliderRange(5, 300).build());

    private final Setting<Boolean> playerChangeAlerts = sgAlerts.add(new BoolSetting.Builder()
        .name("player-change-alerts").description("Announce tab-list joins and leaves.")
        .defaultValue(false).build());

    private final Setting<Boolean> lagSpikeAlerts = sgNetwork.add(new BoolSetting.Builder()
        .name("lag-spike-alerts").description("Warn on sudden ping jumps, useful for spotting unstable routes or overloaded servers.")
        .defaultValue(true).build());

    private final Setting<Integer> pingSpikeDelta = sgNetwork.add(new IntSetting.Builder()
        .name("ping-spike-delta").description("Ping increase in milliseconds counted as a lag spike.")
        .defaultValue(150).min(25).sliderRange(25, 1000).visible(lagSpikeAlerts::get).build());

    private int tickCounter;
    private int currentPing;
    private int previousPing;
    private int maxPing;
    private double averagePing;
    private double pingJitter;
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
    private long lastLagAlertAt;
    private boolean online;

    private long inboundWindow;
    private long outboundWindow;
    private long totalInboundPackets;
    private long totalOutboundPackets;
    private long packetWindowStarted;
    private int inboundPps;
    private int outboundPps;
    private int peakInboundPps;
    private int peakOutboundPps;
    private double averageInboundPps;
    private double averageOutboundPps;
    private int lagSpikes;
    private long lastLagSpikeAt;

    private String serverAddress = "Unknown";
    private String remoteAddress = "Unknown";
    private String serverBrand = "Unknown";
    private String serverVersion = "Unknown";
    private int protocolVersion = -1;
    private int detectedPlugins;
    private ServerCapabilities capabilities;

    private final Map<UUID, String> knownPlayers = new HashMap<>();

    public ServerMonitor() {
        super(Orbiter.CATEGORY, "server-monitor",
            "Passive server health, latency, packet-rate, stability and connection telemetry.");
    }

    @Override public void onActivate() { reset(); refreshNow(); }
    @Override public void onDeactivate() { reset(); }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reset();
        connectedAt = System.currentTimeMillis();
        packetWindowStarted = connectedAt;
        refreshNow();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        online = false;
        knownPlayers.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.getConnection() == null) return;
        inboundWindow++;
        totalInboundPackets++;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.getConnection() == null) return;
        outboundWindow++;
        totalOutboundPackets++;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++tickCounter < sampleInterval.get()) return;
        tickCounter = 0;
        sample(false);
    }

    /** Refreshes instantaneous metadata for commands without modifying rolling statistics. */
    public void refreshNow() { sample(true); }

    private void sample(boolean manualRefresh) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            online = false;
            return;
        }

        long now = System.currentTimeMillis();
        online = true;
        if (connectedAt == 0) connectedAt = now;
        if (packetWindowStarted == 0) packetWindowStarted = now;

        currentPing = Math.max(0, PlayerUtils.getPing());
        currentTps = Math.max(0.0f, Math.min(20.0f, TickRate.INSTANCE.getTickRate()));
        currentPlayers = connection.getOnlinePlayers().size();

        boolean firstSample = samples == 0;
        if (firstSample) {
            averagePing = currentPing;
            averageTps = currentTps;
            maxPing = currentPing;
            minimumTps = currentTps;
            peakPlayers = currentPlayers;
            previousPing = currentPing;
            samples = 1;
        } else if (!manualRefresh) {
            int delta = Math.abs(currentPing - previousPing);
            pingJitter = pingJitter * 0.8 + delta * 0.2;
            if (currentPing > previousPing && currentPing - previousPing >= pingSpikeDelta.get()) {
                lagSpikes++;
                lastLagSpikeAt = now;
                long cooldownMs = alertCooldown.get() * 1000L;
                if (lagSpikeAlerts.get() && now - lastLagAlertAt >= cooldownMs) {
                    lastLagAlertAt = now;
                    warning("Lag spike detected: " + previousPing + " -> " + currentPing + " ms.");
                }
            }

            averagePing = averagePing * 0.8 + currentPing * 0.2;
            averageTps = averageTps * 0.8 + currentTps * 0.2;
            maxPing = Math.max(maxPing, currentPing);
            if (currentTps > 0.0f) minimumTps = minimumTps <= 0.0f ? currentTps : Math.min(minimumTps, currentTps);
            peakPlayers = Math.max(peakPlayers, currentPlayers);
            previousPing = currentPing;
            samples++;
            updatePacketRates(now);
        }

        var serverData = connection.getServerData();
        if (serverData != null) {
            if (serverData.ip != null && !serverData.ip.isBlank()) serverAddress = serverData.ip;
            if (serverData.version != null && !serverData.version.getString().isBlank()) serverVersion = serverData.version.getString();
            else if (serverData.status != null && !serverData.status.getString().isBlank()) serverVersion = serverData.status.getString();
            if (serverData.protocol > 0) protocolVersion = serverData.protocol;
        }

        SocketAddress remote = connection.getConnection().getRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            remoteAddress = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        } else if (remote != null) remoteAddress = remote.toString();

        capabilities = ServerCapabilities.capture(connection);
        updateScannerMetadata();
        updatePlayerSnapshot(connection, manualRefresh || firstSample);
        if (!manualRefresh) updateAlerts();
    }

    private void updatePacketRates(long now) {
        double seconds = Math.max(0.001, (now - packetWindowStarted) / 1000.0);
        inboundPps = (int) Math.round(inboundWindow / seconds);
        outboundPps = (int) Math.round(outboundWindow / seconds);

        if (samples <= 2) {
            averageInboundPps = inboundPps;
            averageOutboundPps = outboundPps;
        } else {
            averageInboundPps = averageInboundPps * 0.8 + inboundPps * 0.2;
            averageOutboundPps = averageOutboundPps * 0.8 + outboundPps * 0.2;
        }

        peakInboundPps = Math.max(peakInboundPps, inboundPps);
        peakOutboundPps = Math.max(peakOutboundPps, outboundPps);
        inboundWindow = 0;
        outboundWindow = 0;
        packetWindowStarted = now;
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
        } catch (Exception ignored) {}
    }

    private void updatePlayerSnapshot(ClientPacketListener connection, boolean baseline) {
        Map<UUID, String> current = new HashMap<>();
        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            if (playerInfo.getProfile() == null || playerInfo.getProfile().id() == null) continue;
            UUID id = playerInfo.getProfile().id();
            String name = playerInfo.getProfile().name();
            String label = name == null || name.isBlank() ? id.toString() : name;
            current.put(id, label);
            if (!baseline && playerChangeAlerts.get() && !knownPlayers.containsKey(id)) info("Player joined: " + label);
        }
        if (!baseline && playerChangeAlerts.get()) {
            for (Map.Entry<UUID, String> previous : knownPlayers.entrySet()) {
                if (!current.containsKey(previous.getKey())) info("Player left: " + previous.getValue());
            }
        }
        knownPlayers.clear();
        knownPlayers.putAll(current);
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
        currentPing = previousPing = maxPing = 0;
        averagePing = pingJitter = 0;
        currentTps = minimumTps = 0;
        averageTps = 0;
        currentPlayers = peakPlayers = samples = 0;
        highPingStreak = lowTpsStreak = 0;
        connectedAt = lastPingAlertAt = lastTpsAlertAt = lastLagAlertAt = 0;
        online = false;
        inboundWindow = outboundWindow = totalInboundPackets = totalOutboundPackets = 0;
        packetWindowStarted = 0;
        inboundPps = outboundPps = peakInboundPps = peakOutboundPps = 0;
        averageInboundPps = averageOutboundPps = 0;
        lagSpikes = 0;
        lastLagSpikeAt = 0;
        serverAddress = remoteAddress = serverBrand = serverVersion = "Unknown";
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

    public int getStabilityScore() {
        if (!online) return 0;
        double score = 100.0;
        score -= Math.min(30.0, Math.max(0, currentPing - 50) / 10.0);
        score -= Math.min(25.0, pingJitter / 4.0);
        if (currentTps > 0.0f) score -= Math.min(35.0, Math.max(0.0, 20.0 - currentTps) * 5.0);
        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }

    public boolean isOnline() { return online; }
    public int getCurrentPing() { return currentPing; }
    public int getMaxPing() { return maxPing; }
    public double getAveragePing() { return averagePing; }
    public double getPingJitter() { return pingJitter; }
    public float getCurrentTps() { return currentTps; }
    public float getMinimumTps() { return minimumTps; }
    public double getAverageTps() { return averageTps; }
    public int getCurrentPlayers() { return currentPlayers; }
    public int getPeakPlayers() { return peakPlayers; }
    public int getInboundPps() { return inboundPps; }
    public int getOutboundPps() { return outboundPps; }
    public int getPeakInboundPps() { return peakInboundPps; }
    public int getPeakOutboundPps() { return peakOutboundPps; }
    public double getAverageInboundPps() { return averageInboundPps; }
    public double getAverageOutboundPps() { return averageOutboundPps; }
    public long getTotalInboundPackets() { return totalInboundPackets; }
    public long getTotalOutboundPackets() { return totalOutboundPackets; }
    public int getLagSpikes() { return lagSpikes; }
    public long getLastLagSpikeAt() { return lastLagSpikeAt; }
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

    public String networkSummary() {
        if (!online) return "Offline";
        return "Ping " + currentPing + " ms (jitter " + Math.round(pingJitter) + ") | In " + inboundPps
            + " pps | Out " + outboundPps + " pps | Stability " + getStabilityScore() + "/100";
    }
}
