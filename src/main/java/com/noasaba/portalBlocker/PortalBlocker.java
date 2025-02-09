package com.noasaba.portalBlocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PortalBlocker extends JavaPlugin implements Listener {

    private int blockDistance;

    @Override
    public void onEnable() {
        // デフォルトのconfigを保存
        saveDefaultConfig();
        // 設定をロード
        loadConfig();

        // イベントリスナー登録
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // プラグインが無効化されるときの処理（特になし）
    }

    public void loadConfig() {
        // config.yml から block-distance を取得
        FileConfiguration config = getConfig();
        blockDistance = config.getInt("block-distance", 100);
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        Location spawnLocation = world.getSpawnLocation();

        // ポータルのブロックのいずれかがスポーン地点からの距離制限内にあるかチェック
        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLocation = block.getLocation();
            double distance = spawnLocation.distance(portalLocation);

            if (distance <= blockDistance) {
                // イベントをキャンセル（ポータル生成を防ぐ）
                event.setCancelled(true);

                // 近くのプレイヤーに警告メッセージを送信
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld().equals(world) && player.getLocation().distance(portalLocation) <= 10) {
                        player.sendMessage("§c" + blockDistance + "マス以内ではネザーポータルを作成できません。");
                    }
                }
                return;
            }
        }
    }
}
