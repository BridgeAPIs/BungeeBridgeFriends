package net.zyuiop.bungeebridgefriends.friends;

import com.google.gson.Gson;
import net.bridgesapis.bungeebridge.BungeeBridge;
import net.bridgesapis.bungeebridge.core.database.Publisher;
import net.bridgesapis.bungeebridge.i18n.I18n;
import net.bridgesapis.bungeebridge.services.IFriendsManagement;
import net.bridgesapis.bungeebridge.utils.SettingsManager;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.zyuiop.bungeebridgefriends.BungeeBridgeFriends;
import net.zyuiop.bungeebridgefriends.FriendsCommand;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

public class FriendsManagement implements IFriendsManagement {


    /**
     * KEYS :
     * friendrequests:<uuid sender>:<uuid receiver>
     * friends:<uuid> : liste d'uuids
     */
    protected BungeeBridgeFriends plugin;

    public FriendsManagement(BungeeBridgeFriends plugin) {
        this.plugin = plugin;
        plugin.getConnector().psubscribe("friends.*", new FriendsConsumer(this));
        plugin.getProxy().getPluginManager().registerCommand(plugin, new FriendsCommand(this, plugin));
    }

    @Override
    public String sendRequest(UUID from, UUID add) {

        if (from.equals(add))
            return I18n.getModuleTranslation("friends", "command.add.cannot_friend_yourself");

        String dbKey = "friendrequest:" + from + ":" + add;
        String checkKey = "friendrequest:" + add + ":" + from;

        if (isFriend(from, add)) {
            return I18n.getModuleTranslation("friends", "command.add.already_friend_with");
        }

        Jedis jedis = plugin.getConnector().getResource();
        String value = jedis.get(dbKey);
        if (jedis.get(checkKey) != null) {
            jedis.close();
            return grantRequest(add, from);
        }

        if (value != null) {
            jedis.close();
            return I18n.getModuleTranslation("friends", "command.add.already_sent_a_demand");
        }

        String allow = SettingsManager.getSetting(add, "friendsenabled");
        if (allow != null && allow.equals("false")) {
            jedis.close();
            return I18n.getModuleTranslation("friends", "command.add.demands_refused");
        }

        if (!BungeeBridge.getInstance().getNetworkBridge().isOnline(add)) {
            jedis.close();
            return I18n.getTranslation("commands.locate.offline");
        }

        if (add == null) {
            jedis.close();
            return I18n.getTranslation("commands.misc.error_occured");
        }

        FriendRequest request = new FriendRequest(from, add, new Date());
        jedis.set(dbKey, new Gson().toJson(request));
        jedis.close();

		BungeeBridge.getInstance().getPublisher().publish(new Publisher.PendingMessage("friends.request", from + " " + add + " " + System.currentTimeMillis()));

        return I18n.getModuleTranslation("friends", "command.add.demand_sent");
    }

    @Override
    public boolean isFriend(UUID from, UUID isFriend) {
        return UUIDFriendList(from).contains(isFriend);
    }

    @Override
    public String grantRequest(UUID from, UUID add) {

        if (from.equals(add))
            return I18n.getModuleTranslation("friends", "command.add.cannot_friend_yourself");

        if (isFriend(from, add))
            return I18n.getModuleTranslation("friends", "command.add.already_friend_with");


        String dbKey = "friendrequest:"+from+":"+add;
        Jedis jedis = plugin.getConnector().getResource();
        String value = jedis.get(dbKey);
        if (value == null) {
            jedis.close();
            return I18n.getModuleTranslation("friends", "command.accept.demand_not_found");
        }

        jedis.del(dbKey);

        if (add == null) {
            jedis.close();
            return I18n.getTranslation("commands.misc.error_occured");
        }

        jedis.rpush("friends:"+from, add.toString());
        jedis.rpush("friends:"+add, from.toString());

        jedis.close();

		BungeeBridge.getInstance().getPublisher().publish(new Publisher.PendingMessage("friends.response", from + " " + add + " " + String.valueOf(true)));

        String pseudo = BungeeBridge.getInstance().getUuidTranslator().getName(from, false);

        return I18n.getModuleTranslation("friends", "command.accept.now_friend_with").replace("%NAME%", (pseudo == null ? ChatColor.RED + "UnknownName" + ChatColor.RESET : pseudo));
    }

    @Override
    public String denyRequest(UUID from, UUID add) {
        String dbKey = "friendrequest:"+from+":"+add;
        Jedis jedis = plugin.getConnector().getResource();
        String value = jedis.get(dbKey);
        if (value == null) {
            jedis.close();
            return I18n.getModuleTranslation("friends", "command.accept.demand_not_found");
        }

        jedis.del(dbKey);
        jedis.close();

		BungeeBridge.getInstance().getPublisher().publish(new Publisher.PendingMessage("friends.response", from + " " + add + " " + String.valueOf(false)));

		String pseudo = BungeeBridge.getInstance().getUuidTranslator().getName(from, false);

        return I18n.getModuleTranslation("friends", "command.deny.demand_refused").replace("%NAME%", (pseudo == null ? ChatColor.RED + "UnknownName" + ChatColor.RESET : pseudo));
    }

    @Override
    public String removeFriend(UUID asking, UUID askTo) {
        String dbKey = "friends:"+asking;
        String dbKeyTo = "friends:"+askTo;

        Jedis jedis = plugin.getConnector().getResource();
        boolean failed = (jedis.lrem(dbKey, 0, askTo.toString()) == 0 || jedis.lrem(dbKeyTo, 0, asking.toString()) == 0);
        jedis.close();
        if (failed)
            return I18n.getModuleTranslation("friends", "command.remove.not_friend_with");
        String name = plugin.getUuidTranslator().getName(askTo, false);
        if (name == null)
            name = ChatColor.RED + "UnknownName";
        return I18n.getModuleTranslation("friends", "command.remove.friend_removed").replace("%NAME%", name);
    }

    @Override
    public ArrayList<String> friendList(UUID asking) {
        ArrayList<String> playerNames = new ArrayList<>();

        for (UUID id : UUIDFriendList(asking)) {
            String name = BungeeBridge.getInstance().getUuidTranslator().getName(id, false);
            if (name == null) {
                continue;
            }
            playerNames.add(name);
        }
        return playerNames;
    }

    @Override
    public ArrayList<UUID> UUIDFriendList(UUID asking) {
        ArrayList<UUID> playerIDs = new ArrayList<>();

        Jedis jedis = plugin.getConnector().getResource();
        for (String data : jedis.lrange("friends:"+asking, 0, -1)) {
            if (data == null || data.equals("")) {
                jedis.lrem("friends:"+asking, 0, data);
                continue;
            }

            try  {
                UUID id = UUID.fromString(data);
				playerIDs.add(id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        jedis.close();
        return playerIDs;
    }

    @Override
    public HashMap<UUID, String> associatedFriendsList(UUID asking) {
        HashMap<UUID, String> ret = new HashMap<>();

        for (UUID id : UUIDFriendList(asking)) {
            String name = plugin.getUuidTranslator().getName(id, false);
            if (name == null) {
                continue;
            }
            ret.put(id, name);
        }
        return ret;
    }

    @Override
    public HashMap<UUID, String> onlineAssociatedFriendsList(UUID asking) {
        HashMap<UUID, String> ret = new HashMap<>();
        HashMap<UUID, String> map = associatedFriendsList(asking);

		map.keySet().stream().filter(id -> BungeeBridge.getInstance().getNetworkBridge().isOnline(id)).forEach(id -> ret.put(id, map.get(id)));

        return ret;
    }

    @Override
    public ArrayList<String> requestsList(UUID asking) {
        String dbKey = "friendrequest:*:"+asking;
        ArrayList<String> playerNames = new ArrayList<>();

        Jedis jedis = plugin.getConnector().getResource();
        for (String data : jedis.keys(dbKey)) {
            String[] parts = data.split(":");
            try  {
                UUID id = UUID.fromString(parts[1]);
                playerNames.add(plugin.getUuidTranslator().getName(id, false));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        jedis.close();
        return playerNames;
    }

    @Override
    public ArrayList<String> sentRequestsList(UUID asking) {
        String dbKey = "friendrequest:"+asking+":";
        ArrayList<String> playerNames = new ArrayList<>();

        Jedis jedis = plugin.getConnector().getResource();
        for (String data : jedis.keys(dbKey)) {
            String[] parts = data.split(":");
            try  {
                UUID id = UUID.fromString(parts[1]);
				playerNames.add(plugin.getUuidTranslator().getName(id, false));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        jedis.close();
        return playerNames;
    }

	@Override
    public void request(UUID from, UUID to, Date date) {
		ProxiedPlayer pl = ProxyServer.getInstance().getPlayer(to);
		if (pl == null)
			return;

		String pseudo = BungeeBridge.getInstance().getUuidTranslator().getName(from, false);
		if (pseudo == null)
			return;

		TextComponent line = new TextComponent(I18n.getModuleTranslation("friends", "demand_receive.message").replace("%NAME%", pseudo));
        TextComponent accept = new TextComponent(I18n.getModuleTranslation("friends", "command.requests.demand_accept_button"));
        accept.setColor(ChatColor.GREEN);
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friends accept "+pseudo));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(I18n.getModuleTranslation("friends", "command.requests.demand_accept_hover")).create()));
        TextComponent refuse = new TextComponent(I18n.getModuleTranslation("friends", "command.requests.demand_refuse_button"));
        refuse.setColor(ChatColor.RED);
        refuse.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friends deny "+pseudo));
        refuse.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(I18n.getModuleTranslation("friends", "command.requests.demand_refuse_hover")).create()));
        line.addExtra(accept);
        line.addExtra(new ComponentBuilder(" " + I18n.getWord("or") + " ").color(ChatColor.GOLD).create()[0]);
        line.addExtra(refuse);

		pl.sendMessage(line);
	}

	@Override
    public void response(UUID from, UUID to, boolean accepted) {
		final ProxiedPlayer pl = ProxyServer.getInstance().getPlayer(from);
		if (pl == null)
			return;

		String pseudo = BungeeBridge.getInstance().getUuidTranslator().getName(to, false);
		if (pseudo == null)
			return;

		if (accepted) {
			pl.sendMessage(new ComponentBuilder(I18n.getModuleTranslation("friends", "demand_receive.notify_accept").replace("%NAME%", pseudo)).color(ChatColor.GREEN).create());
		} else {
			pl.sendMessage(new ComponentBuilder(I18n.getModuleTranslation("friends", "demand_receive.notify_refuse").replace("%NAME%", pseudo)).color(ChatColor.RED).create());
		}

	}
}
