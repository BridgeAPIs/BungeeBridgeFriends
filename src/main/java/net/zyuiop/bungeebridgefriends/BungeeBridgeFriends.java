package net.zyuiop.bungeebridgefriends;

import net.bridgesapis.bungeebridge.BungeeBridge;
import net.bridgesapis.bungeebridge.commands.CommandHelp;
import net.bridgesapis.bungeebridge.core.database.DatabaseConnector;
import net.bridgesapis.bungeebridge.core.handlers.ApiExecutor;
import net.bridgesapis.bungeebridge.core.players.UUIDTranslator;
import net.bridgesapis.bungeebridge.core.proxies.NetworkBridge;
import net.bridgesapis.bungeebridge.listeners.PlayerJoinEvent;
import net.bridgesapis.bungeebridge.services.IFriendsManagement;
import net.bridgesapis.bungeebridge.services.ServiceManager;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.zyuiop.bungeebridgefriends.friends.FriendsManagement;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * @author zyuiop
 */
public class BungeeBridgeFriends extends Plugin {
    private FriendsManagement friendsManagement = new FriendsManagement(this);

    public void onEnable() {
        CommandHelp.addHelp("friends", "friends management main command");

        // TODO : Messages on join/leave
        ServiceManager.registerService(IFriendsManagement.class, friendsManagement);

        ApiExecutor.registerExecutor("friendrequest", (message) -> {
            UUID from = UUID.fromString(message[0]);
            UUID to = UUID.fromString(message[1]);

            friendsManagement.sendRequest(from, to);
        });

        PlayerJoinEvent.triggerOnJoin((player, throwable) -> {
            TextComponent message = new TextComponent("Amis en ligne : ");
            message.setColor(ChatColor.AQUA);

            ArrayList<String> onLine = new ArrayList<>();
            try {
                onLine.addAll(friendsManagement.onlineAssociatedFriendsList(player.getUniqueId()).values().stream().collect(Collectors.toList()));
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            String lines = StringUtils.join(onLine, ", ");
            TextComponent line;

            if (lines != null && !lines.equals("")) {
                line = new TextComponent(lines);
                line.setColor(ChatColor.GREEN);
            } else {
                line = new TextComponent("Aucun");
                line.setColor(ChatColor.RED);
            }

            message.addExtra(line);
            player.sendMessage(message);
        });
    }

    public DatabaseConnector getConnector() {
        return BungeeBridge.getInstance().getConnector();
    }

    public UUIDTranslator getUuidTranslator() {
        return BungeeBridge.getInstance().getUuidTranslator();
    }

    public NetworkBridge getNetworkBridge() {
        return BungeeBridge.getInstance().getNetworkBridge();
    }
}
