package com.azuriom.azlink.common.network;

import com.azuriom.azlink.common.data.PlayerData;
import com.azuriom.azlink.common.data.UserInfo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Network mode: several servers share the same server entry on the website and players move between them.
 *
 * <p>Only the leader talks to the website. It sends the players of the whole network, so the website returns
 * the commands of every player, and runs each command on the server where the player is. The users returned
 * by the website are shared with the other servers.</p>
 *
 * <p>A bridge is set by the platform only when its network layer is available and enabled. Without it,
 * AzLink behaves exactly as a standalone server.</p>
 */
public interface NetworkBridge {

    /**
     * Whether this server is currently the one talking to the website for the whole network.
     */
    boolean isLeader();

    /**
     * The players to send to the website: the local ones followed by the players of the other servers.
     *
     * @param localPlayers the players online on this server
     */
    List<PlayerData> getPlayers(List<PlayerData> localPlayers);

    /**
     * Whether a player is online on this server or on another server of the network.
     */
    boolean isOnline(String name);

    /**
     * The player, if he is online on another server of the network.
     */
    Optional<NetworkPlayer> findRemotePlayer(String name);

    /**
     * Run a console command on the server where the player is.
     */
    void dispatchCommand(NetworkPlayer player, String command);

    /**
     * Called on the leader once a website response has been handled, to share its users.
     *
     * @param users       the users returned by the website
     * @param requestTime the time the request was sent to the website
     */
    void onFetched(List<UserInfo> users, Instant requestTime);

    /**
     * Called instead of a request to the website when this server is not the leader: asks the leader to fetch.
     *
     * @return a future completed once the leader shared the result of a request sent after this call,
     * or after a timeout
     */
    CompletableFuture<Void> requestFetch();

    /**
     * Leave the network, letting another server become the leader.
     */
    void close();
}
