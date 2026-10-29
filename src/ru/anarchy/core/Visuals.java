package ru.anarchy.core;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Таб, MOTD, боссбар на спавне и скрытие служебных команд. */
public final class Visuals implements Listener {
    private static final Set<String> HIDDEN = Set.of("pl", "plugins", "ver", "version", "about", "icanhasbukkit", "?", "help",
            "me", "trigger", "teammsg", "tm", "random", "paper", "spigot", "bukkit", "callback", "restart", "tps", "mspt", "timings",
            "reload", "rl", "grim", "grimac", "spark", "packetevents", "pe", "list", "seed", "datapack", "debug", "perf");
    private final AnarchyCore pl;
    private final BossBar bar = BossBar.bossBar(mm(""), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
    private int barIdx;

    public Visuals(AnarchyCore pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tab, 40, 40);
        Bukkit.getScheduler().runTaskTimer(pl, this::bossbar, 20, 100);
    }

    private boolean admin(Player p) { return p.hasPermission("anarchy.admin"); }

    // ---------- скрытие команд ----------
    @EventHandler
    public void onSend(PlayerCommandSendEvent e) {
        if (admin(e.getPlayer())) return;
        e.getCommands().removeIf(c -> c.contains(":") || HIDDEN.contains(c.toLowerCase(Locale.ROOT)));
    }

    @EventHandler
    public void onTab(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent e) {
        if (!(e.getSender() instanceof Player p) || admin(p)) return;
        String buf = e.getBuffer();
        if (!buf.startsWith("/") || buf.contains(" ")) return;
        e.completions(new ArrayList<>(e.completions().stream().filter(c -> {
            String t = c.suggestion().startsWith("/") ? c.suggestion().substring(1) : c.suggestion();
            return !t.contains(":") && !HIDDEN.contains(t.toLowerCase(Locale.ROOT));
        }).toList()));
    }

    @EventHandler
    public void onTabSync(org.bukkit.event.server.TabCompleteEvent e) {
        if (!(e.getSender() instanceof Player p) || admin(p)) return;
        String buf = e.getBuffer();
        if (!buf.startsWith("/") || buf.contains(" ")) return;
        e.setCompletions(new ArrayList<>(e.getCompletions().stream().filter(c -> {
            String t = c.startsWith("/") ? c.substring(1) : c;
            return !t.contains(":") && !HIDDEN.contains(t.toLowerCase(Locale.ROOT));
        }).toList()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (admin(p)) return;
        String cmd = e.getMessage().length() > 1 ? e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT) : "";
        if (cmd.equals("help") || cmd.equals("?")) { e.setCancelled(true); help(p); return; }
        if (cmd.contains(":") || HIDDEN.contains(cmd)) {
            e.setCancelled(true);
            pl.msg(p, "<red>Неизвестная команда. <gray>Все команды: <yellow>/help</yellow> или <yellow>/menu");
        }
    }

    public void help(Player p) {
        for (String l : pl.getConfig().getStringList("help")) p.sendMessage(mm(l));
    }

    // ---------- таб ----------
    private void tab() {
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        String h = String.join("\n", pl.getConfig().getStringList("visual.tab-header"));
        String f = String.join("\n", pl.getConfig().getStringList("visual.tab-footer"));
        String online = String.valueOf(Bukkit.getOnlinePlayers().size());
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = pl.data(p);
            String t = (tps >= 18 ? "<green>" : tps >= 15 ? "<yellow>" : "<red>") + String.format(Locale.US, "%.1f", tps);
            java.util.function.Function<String, String> r = s -> s.replace("{online}", online).replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                    .replace("{ping}", String.valueOf(p.getPing())).replace("{tps}", t).replace("{site}", pl.getConfig().getString("donate-site", ""))
                    .replace("{name}", AnarchyCore.esc(p.getName())).replace("{rank}", pl.rank(d).prefix().isBlank() ? "<gray>Player" : pl.rank(d).prefix().trim());
            p.sendPlayerListHeaderAndFooter(mm(r.apply(h)), mm(r.apply(f)));
        }
    }

    // ---------- боссбар на спавне ----------
    private void bossbar() {
        List<String> msgs = pl.getConfig().getStringList("visual.bossbar");
        if (msgs.isEmpty()) return;
        bar.name(mm(msgs.get(barIdx++ % msgs.size()).replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))));
        BossBar.Color[] cols = {BossBar.Color.BLUE, BossBar.Color.PURPLE, BossBar.Color.BLUE, BossBar.Color.PINK};
        bar.color(cols[barIdx % cols.length]);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pl.worlds().isHub(p.getWorld())) p.showBossBar(bar); else p.hideBossBar(bar);
        }
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { if (!pl.worlds().isHub(e.getPlayer().getWorld())) e.getPlayer().hideBossBar(bar); else e.getPlayer().showBossBar(bar); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { e.getPlayer().hideBossBar(bar); }

    // ---------- MOTD ----------
    @EventHandler
    public void onPing(PaperServerListPingEvent e) {
        List<String> m = pl.getConfig().getStringList("visual.motd");
        if (!m.isEmpty()) e.motd(mm(String.join("\n", m)));
    }
}
