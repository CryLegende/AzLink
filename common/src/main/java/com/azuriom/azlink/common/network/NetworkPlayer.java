package com.azuriom.azlink.common.network;

import java.util.UUID;

/**
 * A player online on another server of the network.
 */
public class NetworkPlayer {

    private final String name;
    private final UUID uuid;
    private final String server;

    public NetworkPlayer(String name, UUID uuid, String server) {
        this.name = name;
        this.uuid = uuid;
        this.server = server;
    }

    public String getName() {
        return this.name;
    }

    public UUID getUuid() {
        return this.uuid;
    }

    public String getServer() {
        return this.server;
    }
}
