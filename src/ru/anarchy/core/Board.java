package ru.anarchy.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Красивый скорборд справа + префиксы над головой и в табе. */
public final class Board {
    private final AnarchyCore pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public Board(AnarchyCore pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::updateAll, 40, 40);
    }

    public void remove(Player p) { boards.remove(p.getUniqueId()); }

    private Scoreboard board(Player p) {
        return boards.computeIfAbsent(p.getUniqueId(), u -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
            return sb;
        });
    }

    public void updateAll() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            Scoreboard sb = board(viewer);
            if (viewer.getScoreboard() != sb) viewer.setScoreboard(sb);
            for (Player p : online) team(sb, p);
            sidebar(viewer, sb);
        }
    }

    private void team(Scoreboard sb, Player p) {
        PlayerData d = pl.data(p);
        Rank r = pl.rank(d);
        String name = String.format("%03d_%s", 999 - Math.min(r.weight(), 999), p.getName());
        if (name.length() > 64) name = name.substring(0, 64);
        Team cur = sb.getEntryTeam(p.getName());
        if (cur != null && !cur.getName().equals(name)) cur.unregister();
        Team t = sb.getTeam(name);
        if (t == null) { t = sb.registerNewTeam(name); t.addEntry(p.getName()); }
        t.prefix(mm(r.prefix()));
        t.color(NamedTextColor.nearestTo(AnarchyCore.color(pl.nameColor(d))));
        Clans.Clan cl = pl.clans() == null ? null : pl.clans().of(p.getUniqueId());
        Component suffix = (cl != null ? mm(" " + cl.display()) : Component.empty()).append(d.streak >= 3 ? mm(" <red>☄" + d.streak) : Component.empty());
        t.suffix(suffix);
    }

    private void sidebar(Player p, Scoreboard sb) {
        PlayerData d = pl.data(p);
        Objective o = sb.getObjective("side");
        if (o == null) {
            o = sb.registerNewObjective("side", Criteria.DUMMY, Component.empty());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        o.displayName(mm(pl.getConfig().getString("scoreboard.title", "").replace("{server}", pl.getConfig().getString("server-name", "Анархия"))));
        List<String> lines = pl.getConfig().getStringList("scoreboard.lines");
        int n = Math.min(15, lines.size());
        for (int i = 0; i < 15; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            if (i >= n) { sb.resetScores(entry); continue; }
            Score s = o.getScore(entry);
            s.setScore(n - i);
            s.customName(mm(pl.placeholders(p, d, lines.get(i))));
        }
    }
}
