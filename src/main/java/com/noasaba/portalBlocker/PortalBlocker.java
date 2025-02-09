package com.noasaba.portalBlocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;

public class PortalBlocker extends JavaPlugin implements Listener, TabCompleter {

    private int blockDistance;
    private int netherBlockDistance;
    private static final int DEFAULT_NETHER_MULTIPLIER = 8;
    private static final int DEFAULT_NETHER_OFFSET = 2;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // デフォルトの config.yml を作成
        loadConfig(); // 設定を読み込む
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("portalblocker").setExecutor(this);
        getCommand("portalblocker").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        // プラグイン無効化時の処理（特になし）
    }

    public void loadConfig() {
        reloadConfig(); // 設定ファイルをリロード
        FileConfiguration config = getConfig();
        blockDistance = config.getInt("block-distance", 100);

        // nether-block-distance の処理
        if (config.isInt("nether-block-distance")) {
            netherBlockDistance = config.getInt("nether-block-distance");
        } else {
            // デフォルト（1/8 + 2）を適用
            netherBlockDistance = (blockDistance / DEFAULT_NETHER_MULTIPLIER) + DEFAULT_NETHER_OFFSET;
        }
    }

    /**
     * X, Z 座標のみで四角形範囲の距離を測定（±blockDistance）
     */
    private boolean isWithinRestrictedZone(Location loc1, Location loc2, int maxDistance) {
        double dx = Math.abs(loc1.getX() - loc2.getX());
        double dz = Math.abs(loc1.getZ() - loc2.getZ());
        return (dx <= maxDistance && dz <= maxDistance);
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        Location spawnLocation = world.getSpawnLocation();
        int maxDistance = (world.getEnvironment() == Environment.NETHER) ? netherBlockDistance : blockDistance;

        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLocation = block.getLocation();

            if (isWithinRestrictedZone(spawnLocation, portalLocation, maxDistance)) {
                event.setCancelled(true);

                // 近くのプレイヤーに警告メッセージを送信
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld().equals(world) && isWithinRestrictedZone(player.getLocation(), portalLocation, 10)) {
                        player.sendMessage("§cこの場所ではネザーポータルを作成できません！");
                        player.sendMessage("§7スポーン地点から §e" + maxDistance + " ブロック§7 以内では作成不可です。（現在のワールド: §b" + world.getName() + "§7）");
                    }
                }
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        Location spawnLocation = world.getSpawnLocation();
        int maxDistance = (world.getEnvironment() == Environment.NETHER) ? netherBlockDistance : blockDistance;

        Location portalLocation = event.getFrom();

        if (isWithinRestrictedZone(spawnLocation, portalLocation, maxDistance)) {
            event.setCancelled(true);
            player.sendMessage("§cこのネザーポータルは使用できません！");
            player.sendMessage("§7スポーン地点から §e" + maxDistance + " ブロック§7 以内では使用不可です。（現在のワールド: §b" + world.getName() + "§7）");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("portalblocker")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("portalblocker.reload")) {
                    sender.sendMessage("§cこのコマンドを実行する権限がありません！");
                    return true;
                }
                loadConfig();
                sender.sendMessage("§aPortalBlocker の設定をリロードしました！");
                return true;
            }
        }
        sender.sendMessage("§e使用可能なコマンド: /portalblocker reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("portalblocker")) {
            if (args.length == 1) {
                return Arrays.asList("reload");
            }
        }
        return null;
    }
}
