package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DataStore {
    private final AnarchyCore plugin;
    private final File file;
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();

    public DataStore(AnarchyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            UUID u;
            try { u = UUID.fromString(key); } catch (Exception e) { continue; }
            PlayerData d = new PlayerData(u, s.getString("name", "?"));
            d.rank = s.getString("rank", "player");
            d.rankExpire = s.getLong("rank-expire");
            d.balance = s.getLong("balance");
            d.kills = s.getInt("kills");
            d.deaths = s.getInt("deaths");
            d.streak = s.getInt("streak");
            d.bestStreak = s.getInt("best-streak");
            d.playMinutes = s.getLong("play-minutes");
            d.passHash = s.getString("pass");
            d.lastIp = s.getString("ip");
            d.ips.addAll(s.getStringList("ips"));
            if (d.lastIp != null) d.ips.add(d.lastIp);
            d.usedCodes.addAll(s.getStringList("codes"));
            d.creatorCode = s.getString("creator");
            d.lastLogin = s.getLong("last-login");
            ConfigurationSection h = s.getConfigurationSection("homes");
            if (h != null) for (String k : h.getKeys(false)) d.homes.put(k, h.getString(k));
            ConfigurationSection c = s.getConfigurationSection("cooldowns");
            if (c != null) for (String k : c.getKeys(false)) d.cooldowns.put(k, c.getLong(k));
            d.ari = s.getLong("ari");
            d.title = s.getString("title", "newbie");
            d.customColor = s.getString("color");
            d.titles.addAll(s.getStringList("titles"));
            d.buffs.addAll(s.getStringList("buffs"));
            d.buffsOff.addAll(s.getStringList("buffs-off"));
            d.deathLog.addAll(s.getStringList("death-log"));
            d.mobKills = s.getInt("mob-kills");
            d.questsDone = s.getInt("quests-done");
            d.airdrops = s.getInt("airdrops");
            d.blocksMined = s.getLong("blocks-mined");
            d.questDay = s.getString("quest-day", "");
            d.quests.addAll(s.getStringList("quests"));
            d.questClaimed.addAll(s.getStringList("quest-claimed"));
            d.questBonus = s.getBoolean("quest-bonus");
            d.returnLoc = s.getString("return-loc");
            d.casesOpened = s.getInt("cases-opened");
            d.permRank = s.getString("perm-rank");
            d.passXp = s.getInt("pass-xp"); d.passPremium = s.getBoolean("pass-premium");
            d.aquacoins = s.getLong("aquacoins"); d.passCoinLevel = s.getInt("pass-coin-level");
            d.passClaimed.addAll(s.getStringList("pass-claimed")); d.warns = s.getInt("warns");
            ConfigurationSection ck = s.getConfigurationSection("case-keys");
            if (ck != null) for (String kk : ck.getKeys(false)) d.caseKeys.put(kk, ck.getInt(kk));
            ConfigurationSection qp = s.getConfigurationSection("quest-progress");
            if (qp != null) for (String k : qp.getKeys(false)) d.questProgress.put(k, qp.getInt(k));
            data.put(u, d);
            names.put(d.name.toLowerCase(Locale.ROOT), u);
        }
        plugin.getLogger().info("Загружено игроков: " + data.size());
    }

    private final Object saveLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong saveSeq = new java.util.concurrent.atomic.AtomicLong();
    private long lastWritten;

    public void save(boolean async) {
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (PlayerData d : data.values()) {
            String k = d.uuid.toString();
            y.set(k + ".name", d.name);
            y.set(k + ".rank", d.rank);
            y.set(k + ".rank-expire", d.rankExpire);
            y.set(k + ".balance", d.balance);
            y.set(k + ".kills", d.kills);
            y.set(k + ".deaths", d.deaths);
            y.set(k + ".streak", d.streak);
            y.set(k + ".best-streak", d.bestStreak);
            y.set(k + ".play-minutes", d.playMinutes);
            y.set(k + ".pass", d.passHash);
            y.set(k + ".ip", d.lastIp);
            y.set(k + ".ips", new ArrayList<>(d.ips));
            y.set(k + ".codes", new ArrayList<>(d.usedCodes));
            y.set(k + ".creator", d.creatorCode);
            y.set(k + ".last-login", d.lastLogin);
            y.set(k + ".ari", d.ari);
            y.set(k + ".title", d.title);
            y.set(k + ".color", d.customColor);
            y.set(k + ".titles", new ArrayList<>(d.titles));
            y.set(k + ".buffs", new ArrayList<>(d.buffs));
            y.set(k + ".buffs-off", new ArrayList<>(d.buffsOff));
            y.set(k + ".death-log", new ArrayList<>(d.deathLog));
            y.set(k + ".mob-kills", d.mobKills);
            y.set(k + ".quests-done", d.questsDone);
            y.set(k + ".airdrops", d.airdrops);
            y.set(k + ".blocks-mined", d.blocksMined);
            y.set(k + ".quest-day", d.questDay);
            y.set(k + ".quests", new ArrayList<>(d.quests));
            y.set(k + ".quest-claimed", new ArrayList<>(d.questClaimed));
            y.set(k + ".quest-bonus", d.questBonus);
            y.set(k + ".return-loc", d.returnLoc);
            y.set(k + ".cases-opened", d.casesOpened);
            y.set(k + ".perm-rank", d.permRank);
            y.set(k + ".pass-xp", d.passXp); y.set(k + ".pass-premium", d.passPremium);
            y.set(k + ".aquacoins", d.aquacoins); y.set(k + ".pass-coin-level", d.passCoinLevel);
            y.set(k + ".pass-claimed", new ArrayList<>(d.passClaimed)); y.set(k + ".warns", d.warns);
            y.set(k + ".case-keys", null);
            for (var en : d.caseKeys.entrySet()) y.set(k + ".case-keys." + en.getKey(), en.getValue());
            for (Map.Entry<String, Integer> e : d.questProgress.entrySet()) y.set(k + ".quest-progress." + e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : d.homes.entrySet()) y.set(k + ".homes." + e.getKey(), e.getValue());
            for (Map.Entry<String, Long> e : d.cooldowns.entrySet())
                if (e.getValue() > now) y.set(k + ".cooldowns." + e.getKey(), e.getValue());
        }
        String text = y.saveToString();
        long my = saveSeq.incrementAndGet();
        Runnable write = () -> {
            synchronized (saveLock) {
            if (my < lastWritten) return;
            lastWritten = my;
            try {
                File tmp = new File(file.getParentFile(), "players.yml.tmp");
                Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                plugin.getLogger().severe("Не удалось сохранить players.yml: " + e);
            }
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write); else write.run();
    }

    public PlayerData get(UUID u) { return data.get(u); }

    public PlayerData getOrCreate(UUID u, String name) {
        return data.computeIfAbsent(u, k -> {
            PlayerData d = new PlayerData(u, name);
            d.balance = plugin.getConfig().getLong("economy.start-balance", 100);
            names.put(name.toLowerCase(Locale.ROOT), u);
            return d;
        });
    }

    public PlayerData byName(String name) {
        UUID u = names.get(name.toLowerCase(Locale.ROOT));
        return u == null ? null : data.get(u);
    }

    public Collection<PlayerData> all() { return data.values(); }
}
