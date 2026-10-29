package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public final class AnarchyCore extends JavaPlugin {
    public static AnarchyCore INSTANCE;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final LinkedHashMap<String, Rank> ranks = new LinkedHashMap<>();
    private DataStore store;
    private Teleports teleports;
    private Regions regions;
    private CustomItems items;
    private Menus menus;
    private Board board;
    private Quests quests;
    private Events events;
    private Location spawn;
    private Worlds worlds;
    private Cases cases;
    private Holograms holograms;
    private Visuals visuals;
    private Aquarium aquarium;
    private SpecialItems special;
    private ItemCooldowns itemCd;
    private Mystery mystery;
    public Mystery mystery() { return mystery; }
    private Clans clans; private Auction auction; private Boosts boosts; private AntiBot antibot; private Moderation moderation; private Buyer buyer; private Backups backups;
    private Hold hold; private Mine mine;
    private BattlePass pass; private Enchants enchants; private PWarps pwarps; private MoreEvents moreEvents; private Limits limits;
    private Npcs npcs; private Admin admin; private Dungeon dungeon; private Zeus zeus; private BossArena bossArena; private BpShop bpShop; private Monuments monuments; private Crafts crafts;
    private ru.anarchy.core.ac.AntiCheat anticheat;
    private PvpArena pvpArena;
    private Warps warps;
    private int announceIndex;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();
        upgradeConfig();
        loadRanks();
        store = new DataStore(this);
        store.load();
        teleports = new Teleports(this);
        hold = new Hold(this);
        Bukkit.getPluginManager().registerEvents(hold, this);
        worlds = new Worlds(this);
        worlds.load();
        loadSpawn();
        regions = new Regions(this);
        items = new CustomItems(this);
        menus = new Menus(this);
        quests = new Quests(this);
        board = new Board(this);
        boosts = new Boosts(this);
        admin = new Admin(this);
        moderation = new Moderation(this);
        clans = new Clans(this);
        auction = new Auction(this);
        antibot = new AntiBot(this);
        antibot.captchaWorld();
        antibot.startTask();
        pass = new BattlePass(this);
        bpShop = new BpShop(this);
        enchants = new Enchants(this);
        pwarps = new PWarps(this);
        events = new Events(this);
        moreEvents = new MoreEvents(this);
        dungeon = new Dungeon(this);
        zeus = new Zeus(this);
        bossArena = new BossArena(this);
        monuments = new Monuments(this);
        limits = new Limits(this);
        npcs = new Npcs(this);
        cases = new Cases(this);
        holograms = new Holograms(this);
        visuals = new Visuals(this);
        aquarium = new Aquarium(this);
        special = new SpecialItems(this);
        Bukkit.getPluginManager().registerEvents(special, this);
        crafts = new Crafts(this);
        Bukkit.getPluginManager().registerEvents(crafts, this);
        crafts.load(false);
        itemCd = new ItemCooldowns(this);
        Bukkit.getPluginManager().registerEvents(itemCd, this);
        Bukkit.getPluginManager().registerEvents(worlds, this);
        Bukkit.getPluginManager().registerEvents(antibot, this);
        Bukkit.getPluginManager().registerEvents(admin, this);
        Bukkit.getPluginManager().registerEvents(moderation, this);
        Bukkit.getPluginManager().registerEvents(new Exploits(this), this);
        anticheat = new ru.anarchy.core.ac.AntiCheat(this);
        anticheat.start();
        buyer = new Buyer(this);
        Bukkit.getPluginManager().registerEvents(buyer, this);
        backups = new Backups(this);
        Bukkit.getPluginManager().registerEvents(new AuthListener(this), this);
        Bukkit.getPluginManager().registerEvents(clans, this);
        Bukkit.getPluginManager().registerEvents(enchants, this);
        Bukkit.getPluginManager().registerEvents(moreEvents, this);
        Bukkit.getPluginManager().registerEvents(dungeon, this);
        Bukkit.getPluginManager().registerEvents(zeus, this);
        Bukkit.getPluginManager().registerEvents(bossArena, this);
        Bukkit.getPluginManager().registerEvents(monuments, this);
        Bukkit.getPluginManager().registerEvents(limits, this);
        Bukkit.getPluginManager().registerEvents(npcs, this);
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        Bukkit.getPluginManager().registerEvents(regions, this);
        Bukkit.getPluginManager().registerEvents(items, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(events, this);
        Bukkit.getPluginManager().registerEvents(cases, this);
        Bukkit.getPluginManager().registerEvents(visuals, this);
        Bukkit.getPluginManager().registerEvents(new PotionStack(this), this);
        mystery = new Mystery(this);
        Bukkit.getPluginManager().registerEvents(mystery, this);
        Bukkit.getScheduler().runTask(this, () -> { cases.placeBlocks(); holograms.spawnAll(); npcs.spawnAll(); aquarium.spawnAll(); });
        pvpArena = new PvpArena(this);
        warps = new Warps(this);
        mine = new Mine(this);
        Bukkit.getPluginManager().registerEvents(pvpArena, this);
        Bukkit.getScheduler().runTask(this, pvpArena::init);
        Commands cmds = new Commands(this);
        for (String name : getDescription().getCommands().keySet()) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(cmds); c.setTabCompleter(cmds); }
        }
        Commands2 cmds2 = new Commands2(this);
        for (String name : Commands2.NAMES) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(cmds2); c.setTabCompleter(cmds2); }
        }
        // каждую секунду: телепорты, режим боя
        Bukkit.getScheduler().runTaskTimer(this, teleports::tick, 20, 20);
        Bukkit.getScheduler().runTaskTimer(this, this::combatTick, 20, 5);
        // каждую минуту: время игры, награды, срок рангов
        Bukkit.getScheduler().runTaskTimer(this, this::minuteTick, 1200, 1200);
        // автосохранение каждые 5 минут
        Bukkit.getScheduler().runTaskTimer(this, () -> { store.save(true); clans.save(); auction.save(); admin.save(); anticheat.save(); }, 6000, 6000);
        long ann = Math.max(1, getConfig().getLong("announcements.interval-minutes", 5)) * 1200;
        Bukkit.getScheduler().runTaskTimer(this, this::announce, ann, ann);
        for (Player p : Bukkit.getOnlinePlayers()) p.kick(mm("<yellow>Сервер перезагружается, зайди снова"));
        Bukkit.getScheduler().runTaskTimer(this, () -> regions.save(), 6000, 6000);
        getLogger().info("AnarchyCore включён. Рангов: " + ranks.size());
    }

    @Override
    public void onDisable() {
        if (hold != null) hold.releaseAll();
        if (mine != null) mine.removeHolo();
        if (anticheat != null) anticheat.shutdown();
        if (pvpArena != null) pvpArena.shutdown();
        if (dungeon != null) dungeon.shutdown();
        if (zeus != null) zeus.shutdown();
        if (bossArena != null) bossArena.shutdown();
        if (monuments != null) monuments.shutdown();
        if (crafts != null) crafts.shutdown();
        if (moreEvents != null) moreEvents.shutdown();
        if (store != null) store.save(false);
        if (clans != null) clans.save();
        if (buyer != null) buyer.save();
        if (auction != null) auction.save();
        if (pwarps != null) pwarps.save();
        if (admin != null) { admin.save(); admin.saveLog(); }
        if (npcs != null) npcs.removeAll();
        if (regions != null) regions.save();
        if (events != null) events.shutdown();
        if (cases != null) cases.shutdown();
        if (holograms != null) holograms.removeAll();
        if (aquarium != null) aquarium.removeAll();
        if (special != null) special.shutdown();
    }

    public void reload() {
        reloadConfig();
        loadRanks();
        loadSpawn();
        cases.load();
        cases.placeBlocks();
        holograms.spawnAll();
        npcs.spawnAll();
        aquarium.spawnAll();
        buyer.reload();
        mine.load();
        crafts.load(true);
        for (Player p : Bukkit.getOnlinePlayers()) applyTeam(p);
    }

    private void loadRanks() {
        ranks.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        List<Rank> list = new ArrayList<>();
        if (sec != null) for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) continue;
            list.add(new Rank(id, r.getString("display", id), r.getString("prefix", ""),
                    r.getString("name-color", "#FFFFFF"), r.getString("chat-color", "#FFFFFF"),
                    r.getInt("weight"), r.getInt("homes", 1), r.getInt("regions", 1), r.getInt("rtp-cooldown", 600),
                    r.getInt("warmup", 5), r.getBoolean("chat-cooldown", true), r.getDouble("kill-multiplier", 1.0),
                    r.getLong("price-ari", 0), r.getString("icon", "PAPER"), r.getBoolean("hidden", false),
                    r.getStringList("perks"), r.getStringList("description")));
        }
        list.sort(Comparator.comparingInt(Rank::weight));
        for (Rank r : list) ranks.put(r.id(), r);
        if (!ranks.containsKey("player"))
            ranks.put("player", new Rank("player", "Player", "<gray>", "#AAAAAA", "#DDDDDD", 0, 2, 1, 600, 5, true, 1, 0, "PAPER", false, List.of(), List.of()));
    }

    // ---------- геттеры ----------
    public DataStore store() { return store; }
    public Dungeon dungeon() { return dungeon; }
    public Zeus zeus() { return zeus; }
    public BossArena bossArena() { return bossArena; }
    public BpShop bpShop() { return bpShop; }
    public Monuments monuments() { return monuments; }
    public Crafts crafts() { return crafts; }
    public Teleports teleports() { return teleports; }
    public Regions regions() { return regions; }
    public CustomItems items() { return items; }
    public SpecialItems special() { return special; }
    public Menus menus() { return menus; }
    public Quests quests() { return quests; }
    public Events events() { return events; }
    public Board board() { return board; }
    public Worlds worlds() { return worlds; }
    public Cases cases() { return cases; }
    public Holograms holograms() { return holograms; }
    public Visuals visuals() { return visuals; }
    public Clans clans() { return clans; }
    public Auction auction() { return auction; }
    public Boosts boosts() { return boosts; }
    public AntiBot antibot() { return antibot; }
    public BattlePass pass() { return pass; }
    public Enchants enchants() { return enchants; }
    public PWarps pwarps() { return pwarps; }
    public MoreEvents moreEvents() { return moreEvents; }
    public Limits limits() { return limits; }
    public Npcs npcs() { return npcs; }
    public Admin admin() { return admin; }
    public Moderation moderation() { return moderation; }
    public ru.anarchy.core.ac.AntiCheat anticheat() { return anticheat; }
    public PvpArena pvp() { return pvpArena; }
    public Warps warps() { return warps; }
    public Buyer buyer() { return buyer; }
    public Hold hold() { return hold; }
    public Mine mine() { return mine; }
    public String ariName() { return getConfig().getString("ari-name", "Ari"); }

    /** Общий адрес (туннель bore/playit, localhost): по нему нельзя отличать игроков друг от друга. */
    public boolean sharedIp(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        if (getConfig().getStringList("antibot.exempt-ips").contains(ip)) return true;
        return ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1") || ip.equals("0.0.0.0");
    }
    public String ariSym() { return getConfig().getString("ari-symbol", "✦"); }

    // ---------- спавн ----------
    public Location spawn() { return spawn; }
    private void loadSpawn() {
        String s = getConfig().getString("spawn.location");
        spawn = s == null ? null : PlayerData.locFromString(s);
        if (spawn == null && s != null) Bukkit.getScheduler().runTask(this, () -> spawn = PlayerData.locFromString(s));
    }
    public void setSpawn(Location l) {
        spawn = l.clone();
        getConfig().set("spawn.location", PlayerData.locToString(l));
        saveConfig();
        l.getWorld().setSpawnLocation(l);
    }

    // ---------- титулы ----------
    public String titleDisplay(PlayerData d) {
        if (!titleUnlocked(d, d.title)) d.title = "newbie";
        return getConfig().getString("titles." + d.title + ".display", "<gray>-");
    }

    public boolean titleUnlocked(PlayerData d, String id) {
        ConfigurationSection t = getConfig().getConfigurationSection("titles." + id);
        if (t == null) return false;
        if (d.titles.contains(id) || "*".equals(rank(d).perks().isEmpty() ? "" : rank(d).perks().get(0))) return true;
        if (t.getBoolean("case")) return false;
        if (t.contains("ari")) return false;
        if (t.contains("rank")) { Rank r = rankById(t.getString("rank")); return r != null && rank(d).weight() >= r.weight(); }
        if (t.contains("kills") && d.kills < t.getInt("kills")) return false;
        if (t.contains("playtime") && d.playMinutes < t.getLong("playtime")) return false;
        if (t.contains("streak") && d.bestStreak < t.getInt("streak")) return false;
        if (t.contains("quests") && d.questsDone < t.getInt("quests")) return false;
        if (t.contains("balance") && d.balance < t.getLong("balance")) return false;
        return true;
    }

    public String titleCondition(ConfigurationSection t) {
        if (t.getBoolean("case")) return "Только из кейса титулов (/cases)";
        if (t.contains("ari")) return "Покупается за " + t.getLong("ari") + " " + ariName();
        if (t.contains("rank")) { Rank r = rankById(t.getString("rank")); return "Для привилегии " + (r == null ? "?" : r.display()); }
        if (t.contains("kills")) return "Убить " + t.getInt("kills") + " игроков";
        if (t.contains("playtime")) return "Отыграть " + (t.getLong("playtime") / 60) + " ч";
        if (t.contains("streak")) return "Серия из " + t.getInt("streak") + " убийств";
        if (t.contains("quests")) return "Выполнить " + t.getInt("quests") + " квестов";
        if (t.contains("balance")) return "Накопить " + t.getLong("balance") + " " + cur();
        return "Доступен всем";
    }

    public String nameColor(PlayerData d) { return d.customColor != null && hasPerk(d, "color") ? d.customColor : rank(d).nameColor(); }

    // ---------- плейсхолдеры для скорборда ----------
    public String placeholders(Player p, PlayerData d, String line) {
        if (!line.contains("{")) return line;
        Rank r = rank(d);
        return line.replace("{server}", getConfig().getString("server-name", ""))
                .replace("{name}", esc(p.getName()))
                .replace("{rank}", r.prefix().isBlank() || r.id().equals("player") ? "<gray>Player" : r.prefix().trim())
                .replace("{title}", titleDisplay(d))
                .replace("{coins}", fmt(d.balance)).replace("{ari}", fmt(d.ari))
                .replace("{kills}", String.valueOf(d.kills)).replace("{deaths}", String.valueOf(d.deaths))
                .replace("{streak}", String.valueOf(d.streak))
                .replace("{playtime}", d.playMinutes >= 60 ? (d.playMinutes / 60) + "ч " + (d.playMinutes % 60) + "м" : d.playMinutes + "м")
                .replace("{quests}", quests.doneToday(d) + "/" + d.quests.size())
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{keys}", String.valueOf(cases.totalKeys(d)))
                .replace("{coins_short}", shortNum(d.balance)).replace("{ari_short}", shortNum(d.ari))
                .replace("{ping}", String.valueOf(Math.max(0, p.getPing())))
                .replace("{clan}", clanText(p))
                .replace("{rank_badge}", rankBadge(r))
                .replace("{event}", line.contains("{event}") ? events.status() : "");
    }

    /** Клан для скорборда: [ТЕГ] цветом клана или «Нет». */
    private String clanText(Player p) {
        Clans.Clan c = clans == null ? null : clans.of(p.getUniqueId());
        return c == null ? getConfig().getString("scoreboard.no-clan", "Нет") : c.display();
    }

    /** Значок ранга для скорборда: символ + название капителью цветом ранга (ranks.<id>.symbol). */
    public String rankBadge(Rank r) {
        String sym = getConfig().getString("ranks." + r.id() + ".symbol", "⚡");
        String col = r.nameColor() == null || r.nameColor().isBlank() ? "#AAAAAA" : r.nameColor();
        return "<" + col + ">" + (sym.isEmpty() ? "" : sym + " ") + smallCaps(r.display()) + "</" + col + ">";
    }

    private static final String SC_FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String SC_TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    /** Латиница -> капитель (ᴘʟᴀʏᴇʀ). Остальные символы без изменений. */
    public static String smallCaps(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) { int i = SC_FROM.indexOf(ch); b.append(i < 0 ? ch : SC_TO.charAt(i)); }
        return b.toString();
    }

    /** Короткая запись числа: 950, 1.5k, 10k, 2.3M */
    public static String shortNum(long v) {
        long a = Math.abs(v);
        String sfx; double x;
        if (a < 1_000) return String.valueOf(v);
        else if (a < 1_000_000) { x = v / 1_000.0; sfx = "k"; }
        else if (a < 1_000_000_000) { x = v / 1_000_000.0; sfx = "M"; }
        else { x = v / 1_000_000_000.0; sfx = "B"; }
        x = Math.floor(x * 10) / 10;
        String t = String.format(Locale.US, "%.1f", x);
        if (t.endsWith(".0") || Math.abs(x) >= 100) t = String.valueOf((long) x);
        return t + sfx;
    }

    /** Версия config.yml. При обновлении плагина в старый конфиг дописываются недостающие настройки
     *  (новые разделы магазина, ивенты и т.п.), значения админа не трогаются. */
    private static final int CONFIG_VERSION = 23;

    private void upgradeConfig() {
        org.bukkit.configuration.file.FileConfiguration cfg = getConfig();
        int ver = cfg.getInt("config-version", 0);
        if (ver >= CONFIG_VERSION) return;
        org.bukkit.configuration.Configuration def = cfg.getDefaults();
        if (def == null) return;
        List<String> added = new ArrayList<>();
        if (ver < 9) {   // v9: новый скорборд INFO/ECONOMY - заменяем старый целиком
            cfg.set("scoreboard", null);
            for (String k : def.getConfigurationSection("scoreboard").getKeys(true))
                if (!def.isConfigurationSection("scoreboard." + k)) cfg.set("scoreboard." + k, def.get("scoreboard." + k));
            added.add("scoreboard");
        }
        if (ver < 10) migrateUnchanged(cfg, def, "defaults-v9.yml", false, added);    // v10: шрифт борда, блок кейса титулов, /don и /shop в подсказках
        if (ver < 16) migrateUnchanged(cfg, def, "defaults-v15.yml", true, added);    // v16: лут ивентов, босс-арена, метеорит, подземелье, магазин БП, монументы
        if (ver < 17) migrateUnchanged(cfg, def, "defaults-v16.yml", true, added);    // v17: сферы - головы морских существ вместо шаров
        if (ver < 18) migrateUnchanged(cfg, def, "defaults-v17.yml", true, added);    // v18: гарантированный лут ивентов, шансы сфер/зелий/спец-предметов, метеорит: 5 стадий остывания
        if (ver < 19) migrateUnchanged(cfg, def, "defaults-v18.yml", true, added);    // v19: сундуки ивентов 2 предмета/сек, эпический лут - до Акулы, Кракен 1% (jackpot)
        if (ver < 20) migrateUnchanged(cfg, def, "defaults-v19.yml", true, added);    // v20: донат-ключ только за Ari (убран из лута, элиты, Ari-кейса), ключ титулов за квесты
        // v21: только новые ключи (events.dungeon.poseidon.items-min/max) - их дописывает цикл ниже
        if (ver < 22) migrateUnchanged(cfg, def, "defaults-v21.yml", true, added);    // v22: дары Посейдона дешевле - эпический 35, божественный 60 сердец
        if (ver < 23) migrateUnchanged(cfg, def, "defaults-v22.yml", true, added);    // v23: особые крафты (раздел crafts дописывается сам), /crafts в справке
        for (String path : def.getKeys(true)) {
            if (def.isConfigurationSection(path) || cfg.isSet(path)) continue;
            boolean blocked = false;   // предок задан не секцией (админ заменил раздел значением) - не трогаем
            for (int i = path.indexOf('.'); i > 0; i = path.indexOf('.', i + 1)) {
                Object anc = cfg.get(path.substring(0, i), null);
                if (anc != null && !(anc instanceof ConfigurationSection)) { blocked = true; break; }
            }
            if (blocked) continue;
            cfg.set(path, def.get(path));
            String top = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : path;
            if (!added.contains(top)) added.add(top);
        }
        cfg.set("config-version", CONFIG_VERSION);
        try { java.io.File f = new java.io.File(getDataFolder(), "config.yml"); if (f.exists()) java.nio.file.Files.copy(f.toPath(), new java.io.File(getDataFolder(), "config-old-v" + ver + ".yml").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}   // резервная копия старого конфига
        saveConfig();
        if (!added.isEmpty()) getLogger().info("config.yml обновлён до версии " + CONFIG_VERSION + ", обновлены/добавлены настройки: "
                + String.join(", ", added.subList(0, Math.min(20, added.size()))) + (added.size() > 20 ? " и ещё " + (added.size() - 20) : ""));
    }

    /**
     * Что админ не менял (совпадает со старым стандартом oldRes) - обновляем на новый стандарт.
     * removeGone: настройки, которых больше нет в новом стандарте, удаляются (только если тоже не менялись).
     */
    private void migrateUnchanged(org.bukkit.configuration.file.FileConfiguration cfg, org.bukkit.configuration.Configuration def, String oldRes, boolean removeGone, List<String> added) {
        try (java.io.InputStream in = getResource(oldRes)) {
            if (in == null) return;
            org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String path : def.getKeys(true)) {
                if (def.isConfigurationSection(path) || path.equals("config-version")) continue;
                Object nv = def.get(path), ov = old.get(path, null), cv = cfg.get(path, null);
                if (ov == null || cv == null || java.util.Objects.equals(nv, ov) || !java.util.Objects.equals(cv, ov)) continue;
                cfg.set(path, nv);
                String top = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : path;
                if (!added.contains(top)) added.add(top);
            }
            if (!removeGone) return;
            List<String> gone = new ArrayList<>();
            for (String path : old.getKeys(true)) {
                if (old.isConfigurationSection(path) || def.contains(path, true)) continue;
                if (java.util.Objects.equals(cfg.get(path, null), old.get(path))) gone.add(path);
            }
            for (String path : gone) {
                cfg.set(path, null);
                for (String par = path; par.contains("."); ) {   // опустевшие разделы тоже убираем
                    par = par.substring(0, par.lastIndexOf('.'));
                    ConfigurationSection sec = cfg.getConfigurationSection(par);
                    if (sec == null || !sec.getKeys(false).isEmpty() || def.contains(par, true)) break;
                    cfg.set(par, null);
                }
            }
            if (!gone.isEmpty()) getLogger().info("config.yml: убраны устаревшие настройки: " + String.join(", ", gone.subList(0, Math.min(15, gone.size()))) + (gone.size() > 15 ? " и ещё " + (gone.size() - 15) : ""));
        } catch (Exception ex) { getLogger().warning("Обновление config.yml (" + oldRes + "): " + ex.getMessage()); }
    }

    public static String fmt(long v) {
        if (v >= 1_000_000) return String.format(Locale.US, "%.1fM", v / 1_000_000.0);
        if (v >= 10_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        return String.valueOf(v);
    }

    /** МАТЕРИАЛ:кол-во[:зачарования] | region:<tier> | custom:<id> */
    public org.bukkit.inventory.ItemStack parseItem(String line) {
        if (line == null) return null;
        String[] p = line.split(":");
        if (p[0].equalsIgnoreCase("region")) return regions.item(p[1]);
        if (p[0].equalsIgnoreCase("custom")) return items.create(p[1]);
        org.bukkit.Material m = org.bukkit.Material.matchMaterial(p[0]);
        if (m == null) return null;
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
        if (p.length > 2) for (String en : p[2].split(",")) {
            String[] kv = en.split("=");
            var e = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft(kv[0].toLowerCase(Locale.ROOT)));
            if (e != null) it.addUnsafeEnchantment(e, Integer.parseInt(kv[1]));
        }
        return it;
    }
    public Collection<Rank> ranks() { return ranks.values(); }
    public Rank rankById(String id) { return ranks.get(id.toLowerCase(Locale.ROOT)); }
    public PlayerData data(Player p) { return store.getOrCreate(p.getUniqueId(), p.getName()); }

    public Rank rank(PlayerData d) {
        Rank r = ranks.get(d.rank);
        return r != null ? r : ranks.get("player");
    }

    public boolean hasPerk(PlayerData d, String perk) {
        int w = rank(d).weight();
        for (Rank r : ranks.values())
            if (r.weight() <= w && (r.perks().contains(perk) || r.perks().contains("*"))) return true;
        return false;
    }

    /** Ранг, с которого доступна способность (для подсказок). */
    public Rank rankForPerk(String perk) {
        for (Rank r : ranks.values()) if (!r.hidden() && r.perks().contains(perk)) return r;
        return null;
    }

    // ---------- сообщения ----------
    public static Component mm(String s) { return MM.deserialize(s == null ? "" : s); }
    public static String esc(String s) { return MM.escapeTags(s); }

    public void msg(CommandSender s, String text) { s.sendMessage(mm(getConfig().getString("prefix", "") + text)); }
    public String cur() { return getConfig().getString("currency", "⛃"); }

    public static TextColor color(String hex) {
        TextColor c = TextColor.fromHexString(hex);
        return c == null ? NamedTextColor.WHITE : c;
    }

    public Component displayName(PlayerData d) {
        Rank r = rank(d);
        return mm(r.prefix()).append(Component.text(d.name, color(nameColor(d))));
    }

    public static String time(long sec) {
        if (sec < 60) return sec + " сек";
        if (sec < 3600) return (sec / 60) + " мин " + (sec % 60) + " сек";
        return (sec / 3600) + " ч " + (sec % 3600 / 60) + " мин";
    }

    // ---------- команды над табом и ником ----------
    public void applyTeam(Player p) {
        p.playerListName(displayName(data(p)));
        if (board != null) board.updateAll();
    }

    public void updateGlow(Player p) {
        PlayerData d = data(p);
        p.setGlowing(d.streak >= getConfig().getInt("streaks.glow-from", 5));
    }

    // ---------- авторизация ----------
    public static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 60000, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String newHash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return hash(password, salt);
    }

    public static boolean checkHash(String password, String stored) {
        if (stored == null || !stored.contains(":")) return false;
        byte[] salt = Base64.getDecoder().decode(stored.split(":")[0]);
        return MessageDigest.isEqual(hash(password, salt).getBytes(), stored.getBytes());
    }

    public void onAuthed(Player p, boolean firstTime) {
        PlayerData d = data(p);
        d.authed = true;
        d.failedAttempts = 0;
        d.lastLogin = System.currentTimeMillis();
        d.lastMove = System.currentTimeMillis();
        if (p.getAddress() != null) { d.lastIp = p.getAddress().getAddress().getHostAddress(); d.ips.remove(d.lastIp); d.ips.add(d.lastIp); while (d.ips.size() > 25) d.ips.remove(d.ips.iterator().next()); }
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        checkExpire(d, p);
        applyTeam(p);
        updateGlow(p);
        Rank r = rank(d);
        quests.ensure(d);
        pass.syncCoins(p);
        worlds.afterAuth(p, firstTime);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        if (hasPerk(d, "join-broadcast")) {
            Bukkit.broadcast(mm("<gradient:#ffd700:#ff8c00><bold>» </bold></gradient>").append(displayName(d))
                    .append(mm(" <gold>входит в свои владения!")));
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1f);
        } else if (!hasPerk(d, "silent-join")) {
            Bukkit.broadcast(mm("<dark_gray>[<green>+</green>]</dark_gray> ").append(displayName(d)));
        }
        if (firstTime) {
            msg(p, "<aqua>Добро пожаловать на <bold>AquaAnarchy</bold>! <gray>Стартовый кит выдан. Портал на спавне - в мир. Меню: <yellow>/menu</yellow>, кейсы: <light_purple>/cases");
            Commands.giveKit(this, p, "start");
            d.setCooldown("kit_start", getConfig().getLong("kits.start.cooldown", 86400));
        } else {
            msg(p, "<green>Вход выполнен. <gray>Ранг: </gray>" + "<white>" + r.display() + "</white>"
                    + " <gray>Баланс: <gold>" + d.balance + " " + cur() + " <light_purple>" + d.ari + " " + ariSym() + " <gray>| <yellow>/menu");
        }
    }

    public void checkExpire(PlayerData d, Player online) {
        if (d.rankExpire > 0 && d.rankExpire < System.currentTimeMillis()) {
            Rank old = rank(d);
            d.rank = d.permRank != null && rankById(d.permRank) != null ? d.permRank : "player";
            d.permRank = null;
            d.rankExpire = 0;
            if (online != null) {
                msg(online, "<red>Срок привилегии <white>" + old.display() + "</white> истёк. <gray>Продлить: /don");
                applyTeam(online);
            }
        }
    }

    // ---------- задачи ----------
    /** Режим боя показывается боссбаром: сколько секунд осталось и кулдауны предметов. */
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> combatBars = new HashMap<>();

    private void combatTick() {
        long now = System.currentTimeMillis();
        long total = Math.max(1, getConfig().getLong("combat.tag-seconds", 15)) * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = data(p);
            if (d.combatUntil == 0) { itemCd.leaveCombat(p); hideCombatBar(p); continue; }
            long left = (d.combatUntil - now + 999) / 1000;
            if (left > 0) {
                net.kyori.adventure.bossbar.BossBar bar = combatBars.computeIfAbsent(p.getUniqueId(),
                        k -> net.kyori.adventure.bossbar.BossBar.bossBar(Component.empty(), 1f, net.kyori.adventure.bossbar.BossBar.Color.RED, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS));
                bar.name(mm("<red>⚔ Режим боя: <white>" + left + " сек</white>" + itemCd.status(p)));
                bar.progress(Math.max(0f, Math.min(1f, (d.combatUntil - now) / (float) total)));
                p.showBossBar(bar);
            } else { d.combatUntil = 0; itemCd.leaveCombat(p); hideCombatBar(p); p.sendActionBar(mm("<green>Ты вышел из боя")); }
        }
        combatBars.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void hideCombatBar(Player p) {
        net.kyori.adventure.bossbar.BossBar b = combatBars.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
    }

    private void minuteTick() {
        long now = System.currentTimeMillis();
        int interval = Math.max(1, getConfig().getInt("economy.playtime-interval", 10));
        long reward = getConfig().getLong("economy.playtime-reward", 5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = data(p);
            if (!d.authed) continue;
            checkExpire(d, p);
            d.playMinutes++;
            if (now - d.lastMove < 5 * 60_000) {
                d.activeMinutes++;
                quests.progress(p, "PLAYTIME", null, 1);
                if (d.activeMinutes % interval == 0 && reward > 0) {
                    long rw = Math.round(reward * events.coinMultiplier());
                    d.balance += rw;
                    p.sendActionBar(mm("<gold>+" + rw + " " + cur() + " <gray>за активную игру"));
                }
            }
        }
        for (PlayerData d : store.all()) if (Bukkit.getPlayer(d.uuid) == null) checkExpire(d, null);
    }

    private void announce() {
        List<String> list = getConfig().getStringList("announcements.messages");
        if (list.isEmpty()) return;
        String m = list.get(announceIndex++ % list.size());
        for (Player p : Bukkit.getOnlinePlayers()) if (data(p).authed) msg(p, m);
    }
}
