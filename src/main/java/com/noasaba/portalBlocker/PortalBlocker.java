package com.noasaba.portalBlocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PortalBlocker extends JavaPlugin implements Listener, TabCompleter {

    // 言語ファイル読み込み用マップ
    private final Map<String, String> messages = new HashMap<>();

    // config.yml の基本設定
    private String language;            // "en" or "ja"
    private int globalBlockDistance;    // オーバーワールド用
    private int globalNetherDistance;   // ネザー用
    private static final int DEFAULT_NETHER_MULTIPLIER = 8;
    private static final int DEFAULT_NETHER_OFFSET = 2;

    // world.yml
    private File worldConfigFile;
    private FileConfiguration worldConfig;

    @Override
    public void onEnable() {
        // config.yml がなければ生成
        saveDefaultConfig();
        loadConfigAndMessages();

        // world.yml の用意
        setupWorldConfig();
        addMissingWorlds();
        saveWorldConfig();

        // イベント登録
        getServer().getPluginManager().registerEvents(this, this);

        // コマンド登録
        if (getCommand("portalblocker") != null) {
            getCommand("portalblocker").setExecutor(this);
            getCommand("portalblocker").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveWorldConfig();
    }

    /**
     * config.yml を読み込み、langファイルも読み込む
     */
    private void loadConfigAndMessages() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // 言語設定 ("en" / "ja" 以外なら en にフォールバック)
        this.language = cfg.getString("language", "en");
        if (!language.equalsIgnoreCase("en") && !language.equalsIgnoreCase("ja")) {
            language = "en";
        }

        // グローバル距離設定
        globalBlockDistance = cfg.getInt("block-distance", 100);

        if (cfg.isInt("nether-block-distance")) {
            globalNetherDistance = cfg.getInt("nether-block-distance");
        } else {
            // "default" 場合 -> block-distance / 8 + 2
            globalNetherDistance = (globalBlockDistance / DEFAULT_NETHER_MULTIPLIER) + DEFAULT_NETHER_OFFSET;
        }

        // 言語ファイル読み込み
        loadLanguageFile(language);
    }

    /**
     * lang/<language>.yml を読み込み
     */
    private void loadLanguageFile(String langCode) {
        messages.clear();
        String path = "lang/" + langCode + ".yml";
        InputStream in = getResource(path);
        if (in == null) {
            // ファイルが見つからない場合は en.yml にフォールバック
            getLogger().warning("Language file not found: " + path + ", fallback to en.yml");
            in = getResource("lang/en.yml");
            if (in == null) {
                getLogger().severe("lang/en.yml is also missing! Using minimal defaults.");
                messages.put("NO_PERMISSION", "You do not have permission.");
                return;
            }
        }

        try {
            YamlConfiguration langYaml = new YamlConfiguration();
            langYaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String key : langYaml.getKeys(false)) {
                String val = langYaml.getString(key);
                if (val != null) {
                    messages.put(key, val);
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load language file: " + path);
            e.printStackTrace();
        }
    }

    /**
     * メッセージを取得
     */
    private String msg(String key, Object... args) {
        String text = messages.getOrDefault(key, key);
        if (args.length > 0) {
            return String.format(text, args);
        }
        return text;
    }

    // ============================
    // world.yml 関連
    // ============================
    private void setupWorldConfig() {
        worldConfigFile = new File(getDataFolder(), "world.yml");

        // 初回導入時に world.yml がない場合、リソースをコピー
        if (!worldConfigFile.exists()) {
            saveResource("world.yml", false);
        }
        worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);
    }

    private void saveWorldConfig() {
        if (worldConfig != null) {
            try {
                worldConfig.save(worldConfigFile);
            } catch (IOException e) {
                getLogger().severe("Could not save world.yml: " + e.getMessage());
            }
        }
    }

    private void reloadWorldConfig() {
        worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);
    }

    /**
     * world.yml に存在しないワールドを自動追加
     */
    private void addMissingWorlds() {
        for (World w : Bukkit.getWorlds()) {
            String basePath = "worlds." + w.getName();
            if (!worldConfig.isConfigurationSection(basePath)) {
                worldConfig.set(basePath + ".type", "AUTO");
            }
        }
    }

    // ============================
    // イベントハンドラー
    // ============================
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        // ① エンドワールドなら何もしない
        if (world.getEnvironment() == Environment.THE_END) {
            return;
        }

        int maxDist = getEffectiveDistance(world);
        Location spawnLoc = world.getSpawnLocation();

        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLoc = block.getLocation();
            if (isWithinRestricted(spawnLoc, portalLoc, maxDist)) {
                event.setCancelled(true);
                // 周囲にメッセージ
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(world) && isWithinRestricted(p.getLocation(), portalLoc, 10)) {
                        p.sendMessage(msg("PORTAL_CREATE_DENY"));
                        p.sendMessage(String.format("§7" + msg("WITHIN_LIMIT"), maxDist, world.getName()));
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
        // ② エンドワールドなら何もしない
        if (world.getEnvironment() == Environment.THE_END) {
            return;
        }

        int maxDist = getEffectiveDistance(world);
        Location spawnLoc = world.getSpawnLocation();
        Location portalLoc = event.getFrom();

        if (isWithinRestricted(spawnLoc, portalLoc, maxDist)) {
            event.setCancelled(true);
            player.sendMessage(msg("PORTAL_USE_DENY"));
            player.sendMessage(String.format("§7" + msg("WITHIN_LIMIT"), maxDist, world.getName()));
        }
    }

    /**
     * スポーン地点からの四角形範囲内か確認
     */
    private boolean isWithinRestricted(Location spawn, Location check, int dist) {
        double dx = Math.abs(spawn.getX() - check.getX());
        double dz = Math.abs(spawn.getZ() - check.getZ());
        return (dx <= dist && dz <= dist);
    }

    /**
     * ワールドタイプを参照して距離を決定
     */
    private int getEffectiveDistance(World world) {
        String base = "worlds." + world.getName();
        String type = worldConfig.getString(base + ".type", "AUTO").toUpperCase();

        int localBlock = worldConfig.getInt(base + ".block-distance", globalBlockDistance);
        int localNether = worldConfig.getInt(base + ".nether-block-distance", globalNetherDistance);

        // AUTO or OVERWORLD or NETHER -> 距離を返す
        if (type.equals("OVERWORLD")) {
            return localBlock;
        } else if (type.equals("NETHER")) {
            return localNether;
        } else if (type.equals("THE_END")) {
            // world.yml 上で THE_END 指定された場合 → 実際には何もしない設計だが
            // ここでは localBlock を返す等、好きに設定可
            return localBlock;
        } else {
            // AUTO
            if (world.getEnvironment() == Environment.NETHER) {
                return localNether;
            } else {
                return localBlock;
            }
        }
    }

    // ============================
    // コマンド
    // ============================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("portalblocker")) {
            return false;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("portalblocker.admin")) {
                    sender.sendMessage(msg("NO_PERMISSION"));
                    return true;
                }
                // config.yml＆lang再読込
                loadConfigAndMessages();
                // world.yml 再読込
                reloadWorldConfig();
                addMissingWorlds();
                saveWorldConfig();
                sender.sendMessage(msg("RELOAD_DONE"));
                return true;

            case "generateworldconfig":
                if (!sender.hasPermission("portalblocker.admin")) {
                    sender.sendMessage(msg("NO_PERMISSION"));
                    return true;
                }
                addMissingWorlds();
                saveWorldConfig();
                sender.sendMessage("§aAll missing worlds have been added to world.yml");
                return true;

            default:
                sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§e" + msg("HELP_HEADER"));
        sender.sendMessage("§6" + msg("HELP_RELOAD"));
        sender.sendMessage("§6" + msg("HELP_GENERATE"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("portalblocker")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("reload", "generateworldconfig");
        }
        return Collections.emptyList();
    }
}
