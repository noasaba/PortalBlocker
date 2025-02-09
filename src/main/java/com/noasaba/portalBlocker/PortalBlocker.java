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

    // 多言語メッセージ保管用
    private final Map<String, String> messages = new HashMap<>();

    // config.yml 由来の設定
    private String language;                       // "en" or "ja"
    private int globalBlockDistance;               // Overworld距離
    private int globalNetherDistance;              // Nether距離
    private static final int DEFAULT_NETHER_MULTIPLIER = 8;
    private static final int DEFAULT_NETHER_OFFSET = 2;

    // 範囲内でポータル使用を許可するか
    private boolean allowPortalTravelInRestricted;

    // world.yml
    private File worldConfigFile;
    private FileConfiguration worldConfig;

    @Override
    public void onEnable() {
        // config.yml がなければ生成
        saveDefaultConfig();
        // config.yml + lang読み込み
        loadConfigAndLanguage();

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

        // plugin.yml の version を取得して起動メッセージを出す
        String version = getDescription().getVersion();
        getLogger().info("== === ==");
        getLogger().info("PortalBlocker v" + version + " - Developed by NOASABA (by nanosize)");
        getLogger().info("== === ==");
    }

    @Override
    public void onDisable() {
        saveWorldConfig();
    }

    // --------------------------------------------------
    // config.yml & langファイル 読み込み
    // --------------------------------------------------
    private void loadConfigAndLanguage() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // 言語設定
        this.language = cfg.getString("language", "en");
        if (!language.equalsIgnoreCase("en") && !language.equalsIgnoreCase("ja")) {
            language = "en";
        }

        // Overworld の distance
        globalBlockDistance = cfg.getInt("block-distance", 100);

        // Nether の distance (int or default)
        if (cfg.isInt("nether-block-distance")) {
            globalNetherDistance = cfg.getInt("nether-block-distance");
        } else {
            globalNetherDistance = (globalBlockDistance / DEFAULT_NETHER_MULTIPLIER) + DEFAULT_NETHER_OFFSET;
        }

        // 範囲内でポータル使用を許可するか
        allowPortalTravelInRestricted = cfg.getBoolean("allow-portal-travel-in-restricted-zone", false);

        // langファイル読み込み
        loadLanguageFile(language);
    }

    /**
     * lang/ja.yml, lang/en.yml を読み込み、 messages に格納
     */
    private void loadLanguageFile(String lang) {
        messages.clear();
        String path = "lang/" + lang + ".yml";
        InputStream in = getResource(path);
        if (in == null) {
            getLogger().warning("Language file not found: " + path + " , fallback to en.yml");
            in = getResource("lang/en.yml");
            if (in == null) {
                getLogger().severe("lang/en.yml is missing! Using minimal defaults.");
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

    // --------------------------------------------------
    // world.yml 関連
    // --------------------------------------------------
    private void setupWorldConfig() {
        worldConfigFile = new File(getDataFolder(), "world.yml");
        if (!worldConfigFile.exists()) {
            // 初回のみコメント付きの world.yml をコピー
            saveResource("world.yml", false);
        }
        worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);
    }

    private void saveWorldConfig() {
        if (worldConfig == null) return;
        try {
            worldConfig.save(worldConfigFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save world.yml: " + e.getMessage());
        }
    }

    private void reloadWorldConfig() {
        worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);
    }

    /**
     * 未登録のワールドを "AUTO", "block-distance: default", "nether-block-distance: default" で追記
     */
    private void addMissingWorlds() {
        for (World w : Bukkit.getWorlds()) {
            String basePath = "worlds." + w.getName();
            if (!worldConfig.isConfigurationSection(basePath)) {
                worldConfig.createSection(basePath);
                worldConfig.set(basePath + ".type", "AUTO");
                worldConfig.set(basePath + ".block-distance", "default");
                worldConfig.set(basePath + ".nether-block-distance", "default");
            }
        }
    }

    // --------------------------------------------------
    // イベント: PortalCreateEvent
    // --------------------------------------------------
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();

        // エンドワールドは無視
        if (world.getEnvironment() == Environment.THE_END) {
            return;
        }

        // ネザー側の自動生成 (NETHER_PAIR) はキャンセルしない
        if (event.getReason() == PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        // それ以外 (手動作成など) の場合、距離＆権限チェック
        int maxDist = getEffectiveDistance(world);
        Location spawnLoc = world.getSpawnLocation();

        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLoc = block.getLocation();

            // スポーン地点から±maxDist 以内か？
            if (isWithinRestricted(spawnLoc, portalLoc, maxDist)) {
                // 制限ゾーン
                boolean allowed = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(world) && isWithinRestricted(p.getLocation(), portalLoc, 5)) {
                        // 5ブロック以内のプレイヤーが portalblocker.create 権限を持っていればOK
                        if (p.hasPermission("portalblocker.create")) {
                            allowed = true;
                            break;
                        }
                    }
                }

                if (!allowed) {
                    event.setCancelled(true);

                    // 周囲プレイヤーに2行メッセージを通知
                    for (Player near : Bukkit.getOnlinePlayers()) {
                        if (near.getWorld().equals(world)
                                && isWithinRestricted(near.getLocation(), portalLoc, 10)) {

                            // 1) 短いメッセージ
                            near.sendMessage(msg("PORTAL_CREATE_DENY"));
                            //  (例: "この場所ではネザーポータルを作成できません！")

                            // 2) 詳細メッセージ
                            near.sendMessage(String.format(
                                    "§7" + msg("WITHIN_LIMIT"),  // lang/ja.yml → "スポーン地点から %d ブロック以内..."
                                    maxDist,
                                    world.getName()
                            ));
                        }
                    }
                    return; // 最初に見つかったブロックでキャンセルしたら終了
                }
            }
        }
    }

    // --------------------------------------------------
    // イベント: PlayerPortalEvent
    // --------------------------------------------------
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        // THE_END はスキップ
        if (world.getEnvironment() == Environment.THE_END) {
            return;
        }

        // もし allowPortalTravelInRestricted が true なら使用を許可
        if (allowPortalTravelInRestricted) {
            return;
        }

        // false の場合は 距離チェック
        int maxDist = getEffectiveDistance(world);
        Location spawnLoc = world.getSpawnLocation();
        Location portalLoc = event.getFrom();

        if (isWithinRestricted(spawnLoc, portalLoc, maxDist)) {
            event.setCancelled(true);

            // 1) 短いメッセージ
            player.sendMessage(msg("PORTAL_USE_DENY"));
            //  (例: "このネザーポータルは使用できません！")

            // 2) 詳細メッセージ
            player.sendMessage(String.format(
                    "§7" + msg("WITHIN_LIMIT"),  // "スポーン地点から %d ブロック以内 (ワールド: %s) ..."
                    maxDist,
                    world.getName()
            ));
        }
    }

    // --------------------------------------------------
    // 距離計算
    // --------------------------------------------------
    private boolean isWithinRestricted(Location spawn, Location check, int dist) {
        double dx = Math.abs(spawn.getX() - check.getX());
        double dz = Math.abs(spawn.getZ() - check.getZ());
        return (dx <= dist && dz <= dist);
    }

    private int getEffectiveDistance(World w) {
        String basePath = "worlds." + w.getName();
        String type = worldConfig.getString(basePath + ".type", "AUTO").toUpperCase();

        String bdistStr = worldConfig.getString(basePath + ".block-distance", "default");
        String ndistStr = worldConfig.getString(basePath + ".nether-block-distance", "default");

        int localBlock = parseOrDefault(bdistStr, globalBlockDistance);
        int localNether = parseOrDefault(ndistStr, globalNetherDistance);

        if (type.equals("OVERWORLD")) {
            return localBlock;
        } else if (type.equals("NETHER")) {
            return localNether;
        } else if (type.equals("THE_END")) {
            // THE_END はスキップ対象だが一応返す
            return localBlock;
        } else {
            // AUTO → 実際の環境を参照
            if (w.getEnvironment() == Environment.NETHER) {
                return localNether;
            } else {
                return localBlock;
            }
        }
    }

    private int parseOrDefault(String str, int defaultVal) {
        if ("default".equalsIgnoreCase(str)) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // --------------------------------------------------
    // コマンド処理
    // --------------------------------------------------
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
                loadConfigAndLanguage();
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
