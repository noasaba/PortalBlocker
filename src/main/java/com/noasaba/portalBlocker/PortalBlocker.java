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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class PortalBlocker extends JavaPlugin implements Listener, TabCompleter {

    // config.yml から読む言語コード (en / ja) ほか
    private String language;

    // 外部メッセージを保存するマップ
    private final Map<String, String> messages = new HashMap<>();

    // config.yml にある制限距離など
    private int globalBlockDistance;
    private int globalNetherDistance;
    private static final int DEFAULT_NETHER_MULTIPLIER = 8;
    private static final int DEFAULT_NETHER_OFFSET = 2;

    // world.yml
    private FileConfiguration worldConfig;
    private File worldConfigFile;

    @Override
    public void onEnable() {
        // config.yml が無ければ作る
        saveDefaultConfig();
        // config.yml を読み込む（language, block-distance など）
        loadMainConfig();

        // lang/<language>.yml を読み込んで messages に格納
        loadLanguageFile(language);

        // world.yml の読み込み
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
     * config.yml から言語や距離設定を読み込む
     */
    private void loadMainConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();
        language = cfg.getString("language", "en");
        if (!language.equalsIgnoreCase("ja") && !language.equalsIgnoreCase("en")) {
            language = "en"; // サポート外なら en にフォールバック
        }

        globalBlockDistance = cfg.getInt("block-distance", 100);
        if (cfg.isInt("nether-block-distance")) {
            globalNetherDistance = cfg.getInt("nether-block-distance");
        } else {
            globalNetherDistance = (globalBlockDistance / DEFAULT_NETHER_MULTIPLIER) + DEFAULT_NETHER_OFFSET;
        }
    }

    /**
     * 指定言語ファイル (lang/<language>.yml) を読み込み、messages マップに格納
     */
    private void loadLanguageFile(String langCode) {
        messages.clear(); // 一度クリア
        String resourcePath = "lang/" + langCode + ".yml";

        InputStream in = getResource(resourcePath); // jar内のリソースを取得
        if (in == null) {
            // リソースが存在しない場合: 英語ファイルで代用 or エラー扱い
            getLogger().warning("Language file not found: " + resourcePath + " (falling back to en.yml)");
            in = getResource("lang/en.yml");
            if (in == null) {
                getLogger().severe("lang/en.yml is also missing! Using hardcoded defaults.");
                // 最低限のメッセージを追加して終了
                messages.put("NO_PERMISSION", "You do not have permission.");
                return;
            }
        }

        // YamlConfiguration で読み取って、キーと値を Map に格納
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
     * 取得したメッセージを返す (String.format で置換可能)
     */
    private String msg(String key, Object... args) {
        String text = messages.getOrDefault(key, key); // ない場合はキー文字列を返す
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
        if (!worldConfigFile.exists()) {
            saveResource("world.yml", false); // コメント入りファイルがある場合はコピー
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

    private void addMissingWorlds() {
        for (World w : Bukkit.getWorlds()) {
            String basePath = "worlds." + w.getName();
            if (!worldConfig.isConfigurationSection(basePath)) {
                worldConfig.set(basePath + ".type", "AUTO");
            }
        }
    }

    // ============================
    // イベント（例）
    // ============================
    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        World world = event.getWorld();
        Location spawnLoc = world.getSpawnLocation();
        int maxDistance = getEffectiveDistance(world);

        for (org.bukkit.block.BlockState block : event.getBlocks()) {
            Location portalLoc = block.getLocation();
            if (isWithinRestricted(spawnLoc, portalLoc, maxDistance)) {
                event.setCancelled(true);
                // 周囲のプレイヤーへ通知
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(world) && isWithinRestricted(p.getLocation(), portalLoc, 10)) {
                        p.sendMessage(msg("PORTAL_CREATE_DENY"));
                        p.sendMessage(String.format("§7" + msg("WITHIN_LIMIT"), maxDistance, world.getName()));
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
        Location spawnLoc = world.getSpawnLocation();
        int maxDistance = getEffectiveDistance(world);

        if (isWithinRestricted(spawnLoc, event.getFrom(), maxDistance)) {
            event.setCancelled(true);
            player.sendMessage(msg("PORTAL_USE_DENY"));
            player.sendMessage(String.format("§7" + msg("WITHIN_LIMIT"), maxDistance, world.getName()));
        }
    }

    // スポーン地点から四角形範囲で判定
    private boolean isWithinRestricted(Location spawn, Location check, int max) {
        double dx = Math.abs(spawn.getX() - check.getX());
        double dz = Math.abs(spawn.getZ() - check.getZ());
        return (dx <= max && dz <= max);
    }

    // ワールドごとのタイプを判別し、適用すべき距離を返す
    private int getEffectiveDistance(World w) {
        String base = "worlds." + w.getName();
        String type = worldConfig.getString(base + ".type", "AUTO").toUpperCase();

        int localBlock = worldConfig.getInt(base + ".block-distance", globalBlockDistance);
        int localNether = worldConfig.getInt(base + ".nether-block-distance", globalNetherDistance);

        if (type.equals("OVERWORLD")) {
            return localBlock;
        } else if (type.equals("NETHER")) {
            return localNether;
        } else if (type.equals("THE_END")) {
            return localBlock; // 必要なら別途調整
        } else {
            // AUTO
            if (w.getEnvironment() == Environment.NETHER) {
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
                loadMainConfig();      // config.yml リロード
                loadLanguageFile(language); // langファイルを再ロード
                reloadWorldConfig();   // world.yml リロード
                addMissingWorlds();    // 追記
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
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("portalblocker")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("reload", "generateworldconfig");
        }
        return Collections.emptyList();
    }
}
