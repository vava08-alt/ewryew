package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;

/** Захват территорий кланами: держи точку - клан получает доход в банк и опыт. Точки задаются в config (territories.points). */
public final class Territories implements Listener {
    private static final class Point {
        String id, name; int x, z;
        String owner, capturer; int progress; long ownedSince;
        boolean contested;
        final BossBar bar = BossBar.bossBar(mm(""), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        final Set<UUID> viewers = new HashSet<>();
    }

    private final AnarchyCore pl;
    private final Clans clans;
    private final File file;
    private final Map<String, Point> points = new LinkedHashMap<>();
    private long nextIncome;
    private int tick;

    public Territories(AnarchyCore pl, Clans clans) {
        this.pl = pl; this.clans = clans;
        file = new File(pl.getDataFolder(), "territories.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextIncome = y.getLong("_next-income", 0);
        for (String k : y.getKeys(false)) {
            if (k.startsWith("_")) continue;
            Point p = new Point(); p.id = k;
            p.owner = y.getString(k + ".owner"); p.ownedSince = y.getLong(k + ".since");
            points.put(k, p);
        }
        Bukkit.getScheduler().runTaskTimer(pl, this::second, 40, 20);
    }

    private boolean enabled() { return pl.getConfig().getBoolean("territories.enabled", true); }
    private World world() { return Bukkit.getWorld(pl.getConfig().getString("territories.world", "world")); }
    private int radius() { return pl.getConfig().getInt("territories.radius", 12); }
    private int captureSeconds() { return Math.max(10, pl.getConfig().getInt("territories.capture-seconds", 180)); }

    /** Синхронизация точек с конфигом (поддерживает /core reload). */
    private void syncConfig() {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("territories.points");
        Set<String> ids = new HashSet<>();
        if (s != null) for (String k : s.getKeys(false)) {
            ids.add(k);
            Point p = points.computeIfAbsent(k, x -> { Point n = new Point(); n.id = x; return n; });
            p.name = s.getString(k + ".name", k); p.x = s.getInt(k + ".x"); p.z = s.getInt(k + ".z");
        }
        for (Iterator<Point> it = points.values().iterator(); it.hasNext(); ) {
            Point p = it.next();
            if (!ids.contains(p.id)) { hideAll(p); it.remove(); }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("_next-income", nextIncome);
        for (Point p : points.values()) { y.set(p.id + ".owner", p.owner); y.set(p.id + ".since", p.ownedSince); }
        try { y.save(file); } catch (Exception e) { pl.getLogger().warning("territories.yml: " + e); }
    }

    public int count(Clans.Clan c) { int n = 0; for (Point p : points.values()) if (c.id().equals(p.owner)) n++; return n; }

    public void release(String clanId) {
        for (Point p : points.values()) {
            if (clanId.equals(p.owner)) { p.owner = null; p.ownedSince = 0; }
            if (clanId.equals(p.capturer)) { p.capturer = null; p.progress = 0; }
        }
        save();
    }

    private String clanName(String id) { Clans.Clan c = clans.get(id); return c == null ? "<gray>никто" : c.display(); }

    public void list(Player p) {
        syncConfig();
        if (!enabled() || points.isEmpty()) { pl.msg(p, "<red>Территории сейчас отключены"); return; }
        long mins = Math.max(0, (nextIncome - System.currentTimeMillis()) / 60_000);
        p.sendMessage(mm("<gradient:#00f0ff:#0080ff><bold>⚑ Территории</bold></gradient> <gray>(мир " + esc(world() == null ? "?" : world().getName()) + ", радиус " + radius() + ")"));
        for (Point t : points.values())
            p.sendMessage(mm("<aqua>◆ <white>" + esc(t.name) + " <dark_gray>[" + t.x + ", " + t.z + "] <gray>- " + clanName(t.owner)
                    + (t.contested ? " <red>(оспаривается)" : t.capturer != null && t.progress > 0 ? " <yellow>(захват " + clanName(t.capturer) + " <yellow>" + t.progress * 100 / captureSeconds() + "%)" : "")));
        p.sendMessage(mm("<gray>Встань на точку кланом и удерживай <white>" + (captureSeconds() >= 60 ? captureSeconds() / 60 + " мин" : captureSeconds() + " сек") + "<gray>, чтобы захватить. Доход: <gold>" + pl.getConfig().getLong("territories.income-coins", 1500)
                + " " + pl.cur() + " <gray>в банк и <green>" + pl.getConfig().getLong("territories.income-xp", 50) + " опыта <gray>каждые " + pl.getConfig().getLong("territories.income-minutes", 60) + " мин <gray>(след. через " + mins + " мин)."));
    }

    private void second() {
        if (++tick % 30 == 1) syncConfig();
        World w = world();
        if (!enabled() || w == null) { for (Point p : points.values()) hideAll(p); return; }
        int r = radius(), cap = captureSeconds();
        long r2 = (long) r * r;
        for (Point t : points.values()) {
            Map<String, Integer> present = new HashMap<>();
            int solo = 0;
            List<Player> inside = new ArrayList<>(), near = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                double dx = p.getLocation().getX() - (t.x + 0.5), dz = p.getLocation().getZ() - (t.z + 0.5);
                double d2 = dx * dx + dz * dz;
                if (d2 < 64 * 64) near.add(p);
                if (d2 > r2 || p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE || (pl.admin() != null && pl.admin().isVanished(p.getUniqueId()))) continue;
                inside.add(p);
                Clans.Clan c = clans.of(p.getUniqueId());
                if (c == null) solo++; else present.merge(c.id(), 1, Integer::sum);
            }
            t.contested = false;
            if (present.size() == 1 && solo == 0) {
                String c = present.keySet().iterator().next();
                if (c.equals(t.owner)) { t.progress = Math.max(0, t.progress - 2); if (t.progress == 0) t.capturer = null; }
                else {
                    if (!c.equals(t.capturer)) {
                        if (t.progress > 0) t.progress = Math.max(0, t.progress - 2);    // сначала сбить чужой прогресс
                        if (t.progress == 0) { t.capturer = c; if (t.owner != null) notifyOwner(t, "<red>⚠ Вашу территорию <white>" + esc(t.name) + " <red>захватывает " + clanName(c) + "<red>!"); }
                    } else if (++t.progress >= cap) capture(t, c);
                }
            } else if (present.isEmpty() && solo == 0) {
                if (t.progress > 0 && tick % 2 == 0) t.progress--;
                if (t.progress == 0) t.capturer = null;
            } else t.contested = true;
            // боссбар
            Set<UUID> now = new HashSet<>();
            for (Player p : inside) {
                now.add(p.getUniqueId());
                if (t.viewers.add(p.getUniqueId())) p.showBossBar(t.bar);
            }
            for (Iterator<UUID> it = t.viewers.iterator(); it.hasNext(); ) {
                UUID u = it.next();
                if (!now.contains(u)) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(t.bar); it.remove(); }
            }
            if (!t.viewers.isEmpty()) {
                String text = "<aqua>⚑ " + esc(t.name) + " <dark_gray>| <gray>владелец: " + clanName(t.owner)
                        + (t.contested ? " <dark_gray>| <red>ОСПАРИВАЕТСЯ" : t.capturer != null && t.progress > 0 ? " <dark_gray>| <yellow>захват: " + clanName(t.capturer) + " <yellow>" + t.progress * 100 / cap + "%" : "");
                t.bar.name(mm(text));
                t.bar.progress(Math.max(0f, Math.min(1f, t.progress / (float) cap)));
                t.bar.color(t.contested ? BossBar.Color.RED : t.capturer != null && t.progress > 0 ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
            }
            // частицы границы
            if (tick % 2 == 0 && !near.isEmpty()) {
                int y = w.getHighestBlockYAt(t.x, t.z) + 1;
                Color col = t.contested ? Color.RED : t.owner == null ? Color.WHITE : Color.AQUA;
                Particle.DustOptions dust = new Particle.DustOptions(col, 1.6f);
                for (int i = 0; i < 48; i++) {
                    double a = Math.PI * 2 * i / 48;
                    double px = t.x + 0.5 + Math.cos(a) * r, pz = t.z + 0.5 + Math.sin(a) * r;
                    int py = w.isChunkLoaded((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4) ? w.getHighestBlockYAt((int) Math.floor(px), (int) Math.floor(pz)) + 1 : y;
                    for (Player p : near) p.spawnParticle(Particle.DUST, px, py + 0.2, pz, 1, 0, 0, 0, 0, dust);
                }
                for (Player p : near) p.spawnParticle(Particle.END_ROD, t.x + 0.5, y + 1.5, t.z + 0.5, 3, 0.2, 1.2, 0.2, 0.01);
            }
        }
        // доход
        long now = System.currentTimeMillis(), period = Math.max(1, pl.getConfig().getLong("territories.income-minutes", 60)) * 60_000;
        if (nextIncome == 0) { nextIncome = now + period; save(); }
        if (now >= nextIncome) { nextIncome = now + period; payIncome(); save(); }
    }

    private void payIncome() {
        long coins = pl.getConfig().getLong("territories.income-coins", 1500), xp = pl.getConfig().getLong("territories.income-xp", 50);
        boolean needOnline = pl.getConfig().getBoolean("territories.income-needs-online", true);
        for (Point t : points.values()) {
            Clans.Clan c = clans.get(t.owner);
            if (c == null) continue;
            if (needOnline && c.members.stream().noneMatch(u -> Bukkit.getPlayer(u) != null)) continue;
            c.bank += coins;
            clans.addXp(c, xp, "территория");
            clans.broadcast(c, "<gold>⚑ Доход с территории <white>" + esc(t.name) + "<gold>: +" + coins + " " + pl.cur() + " в банк, <green>+" + xp + " опыта");
        }
        clans.save();
    }

    private void notifyOwner(Point t, String msg) { Clans.Clan o = clans.get(t.owner); if (o != null) clans.broadcast(o, msg); }

    private void capture(Point t, String clanId) {
        Clans.Clan c = clans.get(clanId);
        String old = t.owner;
        t.owner = clanId; t.capturer = null; t.progress = 0; t.ownedSince = System.currentTimeMillis();
        save();
        if (c == null) return;
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<aqua>⚑ Клан " + c.display() + " <aqua>захватил территорию <white>" + esc(t.name) + " <dark_gray>[" + t.x + ", " + t.z + "]"
                + (old != null ? " <gray>у клана " + clanName(old) : "")));
        clans.addXp(c, pl.getConfig().getLong("territories.capture-xp", 100), "захват");
        World w = world();
        if (w != null) for (Player p : w.getPlayers()) if (t.viewers.contains(p.getUniqueId())) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    private void hideAll(Point t) {
        for (UUID u : t.viewers) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(t.bar); }
        t.viewers.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { for (Point t : points.values()) t.viewers.remove(e.getPlayer().getUniqueId()); }
}
