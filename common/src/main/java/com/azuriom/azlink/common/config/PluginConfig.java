package com.azuriom.azlink.common.config;

import com.azuriom.azlink.common.http.server.HttpServer;

import java.util.Collections;
import java.util.List;

public class PluginConfig {

    private String siteKey;
    private String siteUrl;
    private boolean instantCommands = true;
    private int httpPort = HttpServer.DEFAULT_PORT;
    private boolean checkUpdates = true;
    // Left null when absent so that saving the config does not add them
    private Boolean networkMode;
    private List<String> networkServers;

    public PluginConfig() {
        this(null, null);
    }

    public PluginConfig(String siteKey, String siteUrl) {
        this.siteKey = siteKey;
        this.siteUrl = siteUrl;
    }

    public String getSiteKey() {
        return this.siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }

    public String getSiteUrl() {
        return this.siteUrl;
    }

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public boolean hasInstantCommands() {
        return this.instantCommands;
    }

    public int getHttpPort() {
        return this.httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public boolean hasUpdatesCheck() {
        return this.checkUpdates;
    }

    /**
     * Whether the network mode may be used when the platform supports it (enabled when absent).
     */
    public boolean hasNetworkMode() {
        return this.networkMode == null || this.networkMode;
    }

    /**
     * The servers whose players are sent to the website in network mode, empty for every server.
     */
    public List<String> getNetworkServers() {
        return this.networkServers != null ? this.networkServers : Collections.emptyList();
    }

    public boolean isValid() {
        return this.siteKey != null && !this.siteKey.isEmpty() && this.siteUrl != null && !this.siteUrl.isEmpty();
    }

    @Override
    public String toString() {
        return "PluginConfig{siteKey='" + this.siteKey +
                "', siteUrl='" + this.siteUrl +
                "', httpPort=" + this.httpPort + '}';
    }
}
