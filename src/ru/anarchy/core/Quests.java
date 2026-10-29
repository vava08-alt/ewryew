package ru.anarchy.core;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

public final class Quests {
    private final AnarchyCore pl;

    public Quests(AnarchyCore pl) { this.pl = pl; }

    public ConfigurationSection pool() { return pl.getConfig().getConfigurationSection("quests.pool"); }

    public void ensure(PlayerData d) {
        String today = LocalDate.now().toString();
        if (today.equals(d.questDay) && !d.quests.isEmpty()) return;
        ConfigurationSection pool = pool();
        if (pool == null) return;
        List<String> ids = new ArrayList<>(pool.getKeys(false));
        Collections.shuffle(ids);
        d.quests.clear();
        d.questProgress.clear();
        d.questClaimed.clear();
        d.questBonus = false;
        d.quests.addAll(ids.subList(0, Math.min(pl.getConfig().getInt("quests.daily-count", 3), ids.size())));
        d.questDay = today;
    }

    private static boolean matches(List<String> mats, Material m) {
        if (mats.isEmpty()) return true;
        String n = m.name();
        for (String s : mats) {
            if (s.startsWith("*") && n.endsWith(s.substring(1))) return true;
            if (s.endsWith("*") && n.startsWith(s.substring(0, s.length() - 1))) return true;
            if (s.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    public void progress(Player p, String type, Material m, int amount) {
        PlayerData d = pl.data(p);
        if (!d.authed) return;
        pl.pass().track(p, type, m, amount);
        ensure(d);
        ConfigurationSection pool = pool();
        boolean changed = false;
        for (String id : d.quests) {
            ConfigurationSection q = pool.getConfigurationSection(id);
            if (q == null || d.questClaimed.contains(id) || !type.equals(q.getString("type"))) continue;
            if (m != null && !matches(q.getStringList("materials"), m)) continue;
            int need = q.getInt("amount", 1);
            int cur = Math.min(need, d.questProgress.getOrDefault(id, 0) + amount);
            d.questProgress.put(id, cur);
            changed = true;
            if (cur >= need) {
                d.questClaimed.add(id);
                d.questsDone++;
                pl.pass().addXp(p, 40);
                long reward = q.getLong("reward");
                d.balance += reward;
                pl.msg(p, "<aqua>✔ Квест выполнен: <white>" + q.getString("name") + "</white> <gold>+" + reward + " " + pl.cur());
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            } else if (need >= 10 && cur % Math.max(1, need / 4) == 0) {
                p.sendActionBar(mm("<aqua>Квест: <white>" + q.getString("name") + " <gray>" + cur + "/" + need));
            }
        }
        if (changed && !d.questBonus && d.questClaimed.containsAll(d.quests) && !d.quests.isEmpty()) {
            d.questBonus = true;
            long bonus = pl.getConfig().getLong("quests.all-done-bonus", 300);
            long ari = pl.getConfig().getLong("quests.all-done-ari", 1);
            d.balance += bonus;
            d.ari += ari;
            String qk = pl.getConfig().getString("quests.all-done-key", "title");
            if (pl.cases().get(qk) != null) { pl.cases().addKeys(d, qk, 1); pl.msg(p, "<aqua>+1 ключ: " + pl.cases().get(qk).display); }
            pl.msg(p, "<gradient:#00e5ff:#7a00ff><bold>Все квесты дня выполнены!</bold></gradient> <gold>+" + bonus + " " + pl.cur()
                    + (ari > 0 ? " <light_purple>+" + ari + " " + pl.ariSym() : ""));
        }
    }

    public int doneToday(PlayerData d) { ensure(d); return d.questClaimed.size(); }
}
