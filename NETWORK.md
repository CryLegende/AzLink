# Network mode (SkyHand fork, Bukkit only)

Network mode lets several Bukkit servers share **one server entry** on the Azuriom website (same site URL
and server key everywhere) while players move between them behind a proxy. Each web shop or vote command is
then delivered **once, on the server where the player is**.

Without it, every server posts its own player list: the website hands the commands of a player to whichever
server asks first, and commands that do not need the player online (`need_online = false`) go to any of them.

## When it is active

All of these are required, otherwise AzLink behaves exactly like upstream AzLink:

- the SHNetwork plugin (SkyHand's network layer: presence, bus, leader election) is enabled. It is a
  `softdepend`, and no SHNetwork class is loaded without it;
- `networkMode` is not `false` in `plugins/AzLink/config.json` (enabled when the key is absent);
- AzLink is set up (valid site URL and key) when the server starts. After a first `/azlink setup`, restart the
  server to enable the network mode.

On startup the log shows `Network mode enabled on server <name>: only the leader talks to the website...`.

## What it does

- **One leader.** The servers elect a leader through SHNetwork (`leader("azlink")`). Only the leader sends
  requests to the website (every minute, and when asked). Leadership is checked again right before each
  request, so a server that just lost it does not post.
- **Network player list.** The leader sends the players of every server (its own, then those reported by
  SHNetwork, optionally limited to `networkServers`), so the website returns the commands and users of every
  player of the network.
- **Dispatch, per player:**
  - online on the leader: dispatched locally, as before;
  - online on another server: sent there with SHNetwork `runFor` (`{player}`, `{name}` and `{uuid}` are
    replaced on the leader, `{uuid}` with the UUID known by SHNetwork). If the player leaves in the meantime,
    SHNetwork runs it once on the server he joins next;
  - not online anywhere: dispatched locally on the leader, as before (rank grants and other `need_online = false`
    commands).
- **Shared users.** After each response the leader publishes the users on the `azlink.users` channel. Every
  server adds them to its cache, so `/azlink money` and `%azlink_money%` work on every server.
- **Other servers never post.** A fetch on a server that is not the leader (`/azlink fetch`, `/azlink money`
  for an unknown player, or the website's instant commands request) publishes a request on `azlink.fetch`.
  The leader fetches (with its usual 5 second rate limit) and its users broadcast answers the requests it
  received before sending. The caller continues then, or after 15 seconds without an answer.
- **Leader change.** When the leader stops or loses its lock, another server takes over within a few seconds
  and fetches right away.

The log lines of the leader tell which case applied:

```
Sending command for player Steve to server poc2: give Steve diamond 1
Dispatching command for player Alex (not online on the network): lp user Alex parent add vip
```

## Configuration (`plugins/AzLink/config.json`)

```json
{
  "siteKey": "<same server key on every server>",
  "siteUrl": "https://example.com",
  "instantCommands": true,
  "httpPort": 6015,
  "checkUpdates": true,
  "networkMode": true,
  "networkServers": ["survival1", "survival2"]
}
```

- `networkMode` (default `true`): set to `false` to keep a server on its own even with SHNetwork.
- `networkServers` (default: every server): SHNetwork server names whose players are sent to the website.
  Players on other servers are treated as offline.

Both keys are left out of the file when they are not set, so saving the configuration (`/azlink setup`,
`/azlink port`) does not add them.

**Instant commands HTTP server.** The website only pings the address and AzLink port of its server entry, and
the server receiving the ping forwards it to the leader. Each server running on the same machine therefore needs
**its own `httpPort`**, or `"instantCommands": false` on the servers the website does not ping.

**Test servers must point to the test website only.** A server copied from production keeps the production
`siteUrl` and server key: with AzLink enabled it would post to the production website and take (and delete)
production commands. Use a dedicated server entry on the test website, with its own key.

## Building

SHNetwork 1.0 must be installed in the local Maven repository (`mvn install` in the SHNetwork project). Its
classes target Java 25, so Gradle must run on a JDK 25 (AzLink classes still target Java 8):

```sh
./gradlew -Dorg.gradle.java.home=<path to JDK 25> :azlink-common:compileJava :azlink-bukkit:compileJava :azlink-bukkit:processResources
```

The universal jar does not build at 1.3.13 (the Hytale dependency is gone). The SkyHand jar is the official
1.3.13 jar with these entries replaced or added:

- `com/azuriom/azlink/common/AzLinkPlugin.class`, `tasks/FetcherTask.class`, `command/AzLinkCommand.class`,
  `config/PluginConfig.class`;
- `com/azuriom/azlink/common/network/NetworkBridge.class`, `network/NetworkPlayer.class` (new);
- `com/azuriom/azlink/bukkit/AzLinkBukkitPlugin.class`, `AzLinkBukkitPlugin$1.class`;
- `com/azuriom/azlink/bukkit/network/ShNetworkBridge.class` (new);
- `plugin.yml` (`SHNetwork` added to `softdepend`).
