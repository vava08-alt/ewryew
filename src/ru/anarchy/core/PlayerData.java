package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerData {
    public final UUID uuid;
    public String name;
    public String rank = "player";
    public long rankExpire = 0; // 0 = навсегда
    public long balance;
    public int kills, deaths, streak, bestStreak;
    public long playMinutes;
    public String passHash;
    public String lastIp;
    /** все IP, с которых заходил игрок (для /alts) */
    public final Set<String> ips = new LinkedHashSet<>();
    /** активированные промокоды */
    public final Set<String> usedCodes = new HashSet<>();
    /** код автора (ютубера), который ввёл игрок */
    public String creatorCode;
    public long lastLogin;
    public final Map<String, String> homes = new LinkedHashMap<>();
    public final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    public long ari;
    public String title = "newbie";
    public String customColor;               // /color (Overlord)
    public final Set<String> titles = new LinkedHashSet<>();     // купленные титулы
    public final Set<String> buffs = new LinkedHashSet<>();      // купленные бафы
    public final Set<String> buffsOff = new LinkedHashSet<>();   // выключенные бафы
    public final List<String> deathLog = new ArrayList<>();      // последние смерти
    public int mobKills, questsDone, airdrops;
    public long blocksMined;
    public String questDay = "";
    public final List<String> quests = new ArrayList<>();
    public final Map<String, Integer> questProgress = new HashMap<>();
    public final Set<String> questClaimed = new HashSet<>();
    public boolean questBonus;
    public String returnLoc;                                   // где игрок вышел (возврат после входа в хаб)
    public final Map<String, Integer> caseKeys = new LinkedHashMap<>();
    public int casesOpened;
    public String permRank;                                    // постоянный ранг под временным
    public int passXp; public boolean passPremium;
    /** Аквакоины - валюта магазина БП; passCoinLevel - за сколько уровней пропуска они уже начислены. */
    public long aquacoins; public int passCoinLevel;
    public final Set<String> passClaimed = new HashSet<>();
    public int warns;

    // runtime
    public transient volatile boolean authed;
    public transient long lastMove = System.currentTimeMillis();
    public transient int activeMinutes;
    public transient long combatUntil;
    public transient volatile long lastChat;
    public transient Location backLoc;
    public transient int failedAttempts;
    public transient boolean ghost;
    public transient boolean flying;
    public transient String lastRegion;

    public PlayerData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    public long cooldownLeft(String key) {
        Long until = cooldowns.get(key);
        if (until == null) return 0;
        long left = (until - System.currentTimeMillis()) / 1000;
        if (left <= 0) { cooldowns.remove(key); return 0; }
        return left;
    }

    public void setCooldown(String key, long seconds) {
        if (seconds > 0) cooldowns.put(key, System.currentTimeMillis() + seconds * 1000);
    }

    public boolean inCombat() { return combatUntil > System.currentTimeMillis(); }

    public static String locToString(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    public static Location locFromString(String s) {
        try {
            String[] p = s.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        } catch (Exception e) { return null; }
    }
}
