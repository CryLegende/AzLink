package com.azuriom.azlink.bukkit.network;

import com.azuriom.azlink.common.AzLinkPlugin;
import com.azuriom.azlink.common.data.PlayerData;
import com.azuriom.azlink.common.data.UserInfo;
import com.azuriom.azlink.common.network.NetworkBridge;
import com.azuriom.azlink.common.network.NetworkPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHNetwork.api.LeaderHandle;
import org.Cry_Legende.sHNetwork.api.NetMessage;
import org.Cry_Legende.sHNetwork.api.NetPlayer;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Network mode on top of SHNetwork. This class is only loaded when the SHNetwork plugin is enabled.
 *
 * <ul>
 *     <li>One server is elected leader ({@code azlink} task): it is the only one talking to the website.</li>
 *     <li>After each request, the leader shares the users returned by the website ({@code azlink.users}).</li>
 *     <li>The other servers ask the leader to fetch ({@code azlink.fetch}) instead of sending a request.</li>
 * </ul>
 */
public final class ShNetworkBridge implements NetworkBridge {

    private static final String LEADER_TASK = "azlink";
    private static final String USERS_CHANNEL = "azlink.users";
    private static final String FETCH_CHANNEL = "azlink.fetch";

    private static final long FETCH_TIMEOUT_SECONDS = 15;
    private static final Duration REQUEST_EXPIRATION = Duration.ofMinutes(1);

    private final AzLinkPlugin plugin;
    private final SHNetworkAPI api;
    private final Set<String> servers = new HashSet<>();

    // Requests of this server waiting for the leader, by id
    private final Map<String, CompletableFuture<Void>> pendingFetches = new ConcurrentHashMap<>();
    // Requests received by the leader, by id, with the time they were received
    private final Map<String, Instant> receivedRequests = new ConcurrentHashMap<>();

    private final LeaderHandle leader;

    private ShNetworkBridge(AzLinkPlugin plugin) {
        this.plugin = plugin;
        this.api = SHNetworkAPI.get();

        for (String server : plugin.getConfig().getNetworkServers()) {
            this.servers.add(server.toLowerCase(Locale.ROOT));
        }

        this.api.subscribe(USERS_CHANNEL, this::handleUsers, false);
        this.api.subscribe(FETCH_CHANNEL, this::handleFetchRequest, false);
        this.leader = this.api.leader(LEADER_TASK, this::onLeaderAcquired, this::onLeaderLost);
    }

    /**
     * Join the network. Only call it when SHNetwork is enabled.
     */
    public static NetworkBridge create(AzLinkPlugin plugin) {
        ShNetworkBridge bridge = new ShNetworkBridge(plugin);
        String servers = bridge.servers.isEmpty() ? "every server" : String.join(", ", bridge.servers);

        plugin.getLogger().info("Network mode enabled on server " + bridge.api.serverName()
                + ": only the leader talks to the website, for the players of " + servers + ".");

        return bridge;
    }

    @Override
    public boolean isLeader() {
        return this.leader.isLeader();
    }

    @Override
    public List<PlayerData> getPlayers(List<PlayerData> localPlayers) {
        List<PlayerData> players = new ArrayList<>(localPlayers);
        Set<UUID> listed = new HashSet<>();
        String localServer = this.api.serverName();

        for (PlayerData player : localPlayers) {
            listed.add(player.getUuid());
        }

        for (NetPlayer player : this.api.online()) {
            // Players of this server come from the platform, which may hide vanished players
            if (player.server().equalsIgnoreCase(localServer) || !isIncluded(player.server())) {
                continue;
            }

            if (listed.add(player.uuid())) {
                players.add(new PlayerData(player.name(), player.uuid()));
            }
        }

        return players;
    }

    @Override
    public boolean isOnline(String name) {
        Optional<NetPlayer> player = this.api.find(name);

        return player.isPresent()
                && (player.get().server().equalsIgnoreCase(this.api.serverName()) || isIncluded(player.get().server()));
    }

    @Override
    public Optional<NetworkPlayer> findRemotePlayer(String name) {
        Optional<NetPlayer> found = this.api.find(name);

        if (!found.isPresent()) {
            return Optional.empty();
        }

        NetPlayer player = found.get();

        if (player.server().equalsIgnoreCase(this.api.serverName()) || !isIncluded(player.server())) {
            return Optional.empty();
        }

        return Optional.of(new NetworkPlayer(player.name(), player.uuid(), player.server()));
    }

    @Override
    public void dispatchCommand(NetworkPlayer player, String command) {
        // Runs where the player is, or once on the server he joins next if he left in the meantime
        this.api.runFor(player.getUuid().toString(), command);
    }

    @Override
    public void onFetched(List<UserInfo> users, Instant requestTime) {
        JsonArray answered = new JsonArray();
        Instant expiration = Instant.now().minus(REQUEST_EXPIRATION);
        Iterator<Map.Entry<String, Instant>> iterator = this.receivedRequests.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Instant> request = iterator.next();

            if (!request.getValue().isAfter(requestTime)) {
                // Received before this request was sent, so it is answered by it
                answered.add(request.getKey());
                iterator.remove();
            } else if (request.getValue().isBefore(expiration)) {
                iterator.remove();
            }
        }

        JsonArray usersJson = new JsonArray();

        for (UserInfo user : users) {
            JsonObject userJson = new JsonObject();
            userJson.addProperty("id", user.getId());
            userJson.addProperty("name", user.getName());
            userJson.addProperty("money", user.getMoney());
            usersJson.add(userJson);
        }

        JsonObject message = new JsonObject();
        message.add("users", usersJson);
        message.add("answered", answered);

        this.api.publish(USERS_CHANNEL, message.toString());
    }

    @Override
    public CompletableFuture<Void> requestFetch() {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Void> future = new CompletableFuture<>();
        JsonObject message = new JsonObject();
        message.addProperty("id", id);

        this.pendingFetches.put(id, future);
        this.api.publish(FETCH_CHANNEL, message.toString());

        this.plugin.getScheduler().scheduleAsyncLater(() -> {
            CompletableFuture<Void> pending = this.pendingFetches.remove(id);

            if (pending != null) {
                // No answer from a leader in time, let the caller go on with the data it has
                complete(pending);
            }
        }, FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return future;
    }

    @Override
    public void close() {
        this.leader.release();

        for (CompletableFuture<Void> future : this.pendingFetches.values()) {
            future.complete(null);
        }

        this.pendingFetches.clear();
        this.receivedRequests.clear();
    }

    private void onLeaderAcquired() {
        this.plugin.getLogger().info("This server is now the network leader: it sends the data of every server to the website.");

        // Deliver what is waiting on the website without waiting for the next scheduled request
        this.plugin.getScheduler().executeAsync(this::fetch);
    }

    private void onLeaderLost() {
        this.plugin.getLogger().info("This server is no longer the network leader, another server talks to the website.");
    }

    // Bus thread: never block here
    private void handleFetchRequest(NetMessage message) {
        if (!isLeader()) {
            return;
        }

        String id;

        try {
            id = message.object().get("id").getAsString();
        } catch (RuntimeException e) {
            return;
        }

        this.receivedRequests.put(id, Instant.now());

        this.plugin.getScheduler().executeAsync(this::fetch);
    }

    // Bus thread: never block here
    private void handleUsers(NetMessage message) {
        JsonObject json;

        try {
            json = message.object();
        } catch (RuntimeException e) {
            return;
        }

        try {
            for (JsonElement element : json.getAsJsonArray("users")) {
                JsonObject user = element.getAsJsonObject();

                this.plugin.getUserManager().addUser(new UserInfo(
                        user.get("id").getAsInt(),
                        user.get("name").getAsString(),
                        user.get("money").getAsDouble()
                ));
            }

            for (JsonElement id : json.getAsJsonArray("answered")) {
                CompletableFuture<Void> future = this.pendingFetches.remove(id.getAsString());

                if (future != null) {
                    complete(future);
                }
            }
        } catch (RuntimeException e) {
            this.plugin.getLogger().warn("Invalid users received from server " + message.origin() + ": " + e.getMessage());
        }
    }

    private void fetch() {
        this.plugin.fetch().exceptionally(ex -> {
            this.plugin.getLogger().error("Unable to send data to the website: " + ex.getMessage());

            return null;
        });
    }

    private void complete(CompletableFuture<Void> future) {
        // Same thread as the answer of a request sent by this server
        try {
            this.plugin.getScheduler().executeSync(() -> future.complete(null));
        } catch (RuntimeException e) {
            future.complete(null); // plugin disabled
        }
    }

    private boolean isIncluded(String server) {
        return this.servers.isEmpty() || this.servers.contains(server.toLowerCase(Locale.ROOT));
    }
}
