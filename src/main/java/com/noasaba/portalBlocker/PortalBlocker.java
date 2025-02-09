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

    // 多言語対応: メッセージを保管するMap
    private final Map<String, String> messages = new HashMap<>();

    // config.yml の設定
    private String language;            // "en" or "ja"
    private int globalBlockDistance;    // Overworld用
    private int globalNetherDistance;   // Nether用
    private static final int DEFAULT_NETHER_MULTIPLIER = 8;
    private static final int DEFAULT_NETHER_OFFSET = 2;

    // world.yml
    private File worldConfigFile;
    private FileConfiguration worldConfig;

    @Override
    public void onEnable() {
        // 1) デフォルトの config.yml を生成 (なければ)
        saveDefaultConfig();
        // 2) config.yml & langの読み込み
        loadConfigAndLanguage();

        // 3) world.yml がなければリソースからコピー → ロード
        setupWorldConfig();
        // 4) 足りないワールドを追記 (block-distance: default など)
        addMissingWorlds();
        // 5) world.yml を保存 (この時点でコメントは消える可能性あり)
        saveWorldConfig();

        // 6) イベント登録
        getServer().getPluginManager().registerEvents(this, this);

        // 7) コマンド登録
        if (getCommand("portalblocker") != null) {
            getCommand("portalblocker").setExecutor(this);
            getCommand("portalblocker").setTabCompleter(this);
        }

        // 8) 起動メッセージ (plugin.yml の version を自動取得)
        String version = getDescription().getVersion();
        getLogger().info("== === ==");
        getLogger().info("PortalBlocker v" + version + " - Developed by NOASABA (by nanosize)");
        getLogger().info("== === ==");
    }

    @Override
    public void onDisable() {
        saveWorldConfig();
    }

    // ---------------------------------------------
    //  config.yml & lang 読み込み
    // ---------------------------------------------
    private void loadConfigAndLanguage() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // 言語設定
        language = cfg.getString("language", "en");
        if (!language.equalsIgnoreCase("en") && !language.equalsIgnoreCase("ja")) {
            language = "en";
        }

        // block-distance (Overworld)
        globalBlockDistance = cfg.getInt("block-distance", 100);

        // nether-block-distance
        if (cfg.isInt("nether-block-distance")) {
            // 数値が書いてあればその値を適用
            globalNetherDistance = cfg.getInt("nether-block-distance");
        } else {
            // "default" or 他の場合 → block-distance / 8 + 2
            globalNetherDistance = (globalBlockDistance / DEFAULT_NETHER_MULTIPLIER) + DEFAULT_NETHER_OFFSET;
        }

        // 言語ファイルを読み込み
        loadLanguageFile(language);
    }

    /**
     * lang/en.yml / lang/ja.yml を読み込んで messages に格納
     */
    private void loadLanguageFile(String lang) {
        messages.clear();
        String resourcePath = "lang/" + lang + ".yml";

        InputStream in = getResource(resourcePath);
        if (in == null) {
            // ファイルが見つからない場合は英語にフォールバック
            getLogger().warning("Language file not found: " + resourcePath + " , fallback to en.yml");
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
            getLogger().severe("Failed to load language file: " + resourcePath);
            e.printStackTrace();
        }
    }

    /**
     * メッセージ取得 (String.format対応)
     */
    private String msg(String key, Object... args) {
        String text = messages.getOrDefault(key, key);
        if (args.length > 0) {
            return String.format(text, args);
        }
        return text;
    }

    // ---------------------------------------------
    // world.yml の読み込み・追記・保存
    // ---------------------------------------------
    private void setupWorldConfig() {
        worldConfigFile = new File(getDataFolder(), "world.yml");
        if (!worldConfigFile.exists()) {
            // 初回導入時のみコメント付きファイルをコピー
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
     * 未登録のワールドを "AUTO" / "block-distance: default" / "nether-block-distance: default" で追加
     */
    private void addMissingWorlds() {
        for (World w : Bukkit.getWorlds()) {
            String basePath = "worlds." + w.getName();
            if (!worldConfig.isConfigurationSection(basePath)) {
                worldConfig.createSection(basePath);
                // デフォルト記述
                worldConfig.set(basePath + ".type", "AUTO");
                worldConfig.set(basePath + ".block-distance", "default");
                worldConfig.set(basePath + ".nether-block-distance", "default");
            }
        }
    }

    // ---------------------------------------------
    // イベント (PortalCreate / PlayerPortal)
    // エンドワールドは処理しない
    // ---------------------------------------------
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        // エンドワールドはスキップ
        if (world.getEnvironment() == Environment.THE_END) {
            return;
        }

        int maxDist = getEffectiveDistance(world);
        Location spawnLoc = world.getSpawnLocation();

        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLoc = block.getLocation();
            if (isWithinRestricted(spawnLoc, portalLoc, maxDist)) {
                event.setCancelled(true);
                // 周囲のプレイヤーに通知
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
        // エンドワールドはスキップ
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
     * スポーン地点から四角形範囲 (±maxDist) をチェック
     */
    private boolean isWithinRestricted(Location spawn, Location check, int maxDist) {
        double dx = Math.abs(spawn.getX() - check.getX());
        double dz = Math.abs(spawn.getZ() - check.getZ());
        return (dx <= maxDist && dz <= maxDist);
    }

    /**
     * ワールドごとの距離を決定 (type, block-distance, nether-block-distance)
     */
    private int getEffectiveDistance(World w) {
        String path = "worlds." + w.getName();
        String type = worldConfig.getString(path + ".type", "AUTO").toUpperCase();

        // block-distance と nether-block-distance は "default" or 数値
        String bdistStr = worldConfig.getString(path + ".block-distance", "default");
        String ndistStr = worldConfig.getString(path + ".nether-block-distance", "default");

        // 実際の値に変換
        int localBlockDist = parseOrDefault(bdistStr, globalBlockDistance);
        int localNetherDist = parseOrDefault(ndistStr, globalNetherDistance);

        if (type.equals("OVERWORLD")) {
            return localBlockDist;
        } else if (type.equals("NETHER")) {
            return localNetherDist;
        } else if (type.equals("THE_END")) {
            // world.yml で THE_END と書かれていても実際にはイベントをスキップ
            // 一応返す値を決める (ここでは localBlockDist)
            return localBlockDist;
        } else {
            // AUTO -> Bukkit の Environment に従う
            if (w.getEnvironment() == Environment.NETHER) {
                return localNetherDist;
            } else {
                return localBlockDist;
            }
        }
    }

    /**
     * "default" と書かれていれば defaultVal を返し、数値ならパースして返す
     */
    private int parseOrDefault(String str, int defaultVal) {
        if ("default".equalsIgnoreCase(str)) {
            return defaultVal;
        }
        // それ以外は数値とみなす
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // ---------------------------------------------
    // コマンド実装 (/portalblocker)
    // ---------------------------------------------
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
                // config.yml & lang 再読込
                loadConfigAndLanguage();
                // world.yml 再読込
                reloadWorldConfig();
                // 未登録ワールドを "default" 付きで追加
                addMissingWorlds();
                // 保存
                saveWorldConfig();
                // メッセージ
                sender.sendMessage(msg("RELOAD_DONE"));
                return true;

            case "generateworldconfig":
                if (!sender.hasPermission("portalblocker.admin")) {
                    sender.sendMessage(msg("NO_PERMISSION"));
                    return true;
                }
                // world.yml に未登録ワールドを追加
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
