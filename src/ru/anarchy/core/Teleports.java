package ru.anarchy.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static ru.anarchy.core.AnarchyCore.mm;

public final class Teleports {
    private final AnarchyCore pl;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>(); // ключ - кому отправлен запрос

    private static final class Pending {
        Location start; int left; String label; Runnable action;
    }
    public record Request(UUID from, boolean here, long time) {}

    public Teleports(AnarchyCore pl) { this.pl = pl; }

    /** Телепорт с задержкой (по рангу). */
    public void start(Player p, String label, Runnable action) {
        PlayerData d = pl.data(p);
        if (d.inCombat()) { pl.msg(p, "<red>Нельзя телепортироваться в режиме боя!"); return; }
        if (pl.bossArena() != null && pl.bossArena().isLocked(p)) { pl.msg(p, "<red>☠ С арены Стража не уйти, пока он жив!"); return; }
        int warm = pl.rank(d).warmup();
        if (warm <= 0) { action.run(); return; }
        Pending pd = new Pending();
        pd.start = p.getLocation(); pd.left = warm; pd.label = label; pd.action = action;
        pending.put(p.getUniqueId(), pd);
        pl.msg(p, "<gray>Телепорт <white>" + label + "</white> через <yellow>" + warm + " сек</yellow>. Не двигайся.");
        show(p, pd);
    }

    private void show(Player p, Pending pd) {
        p.showTitle(Title.title(mm("<yellow>" + pd.left), mm("<gray>Телепорт: " + pd.label),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
    }

    public void cancel(Player p, String reason) {
        if (pending.remove(p.getUniqueId()) != null) {
            pl.msg(p, "<red>Телепорт отменён: " + reason);
            p.clearTitle();
        }
    }

    public void tick() {
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Pending pd = e.getValue();
            if (p == null) { it.remove(); continue; }
            if (!p.getWorld().equals(pd.start.getWorld()) || p.getLocation().distanceSquared(pd.start) > 0.36) {
                it.remove(); pl.msg(p, "<red>Телепорт отменён: ты сдвинулся"); p.clearTitle(); continue;
            }
            if (pl.data(p).inCombat()) { it.remove(); pl.msg(p, "<red>Телепорт отменён: режим боя"); continue; }
            if (pl.bossArena() != null && pl.bossArena().isLocked(p)) { it.remove(); pl.msg(p, "<red>Телепорт отменён: ты на арене Стража"); p.clearTitle(); continue; }
            pd.left--;
            if (pd.left <= 0) { it.remove(); p.clearTitle(); pd.action.run(); }
            else show(p, pd);
        }
        long timeout = pl.getConfig().getLong("teleport.tpa-timeout", 60) * 1000;
        requests.values().removeIf(r -> System.currentTimeMillis() - r.time() > timeout);
    }

    public void teleport(Player p, Location to) {
        p.teleportAsync(to).thenAccept(ok -> {
            if (ok) p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
        });
    }

    // ---------- TPA ----------
    public void request(Player from, Player to, boolean here) {
        requests.put(to.getUniqueId(), new Request(from.getUniqueId(), here, System.currentTimeMillis()));
    }
    public Request takeRequest(Player to) { return requests.remove(to.getUniqueId()); }

    // ---------- RTP ----------
    public void findRandom(World w, int tries, Consumer<Location> cb) {
        Location c = pl.spawn() != null && pl.spawn().getWorld().equals(w) ? pl.spawn() : new Location(w, 0, 64, 0);
        findAround(c, pl.getConfig().getInt("rtp.normal.min", 300), pl.getConfig().getInt("rtp.normal.max", 3000), tries, cb);
    }

    public void findAround(Location c, int min, int max0, int tries, Consumer<Location> cb) {
        if (tries <= 0) { cb.accept(null); return; }
        World w = c.getWorld();
        int max = Math.max(min + 1, max0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble(Math.PI * 2);
        int dist = r.nextInt(min, max);
        int x = c.getBlockX() + (int) (Math.cos(ang) * dist), z = c.getBlockZ() + (int) (Math.sin(ang) * dist);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z)) || pl.regions().at(new Location(w, x, 64, z)) != null) { findAround(c, min, max, tries - 1, cb); return; }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
            Block b = w.getHighestBlockAt(x, z);
            Material m = b.getType();
            if (b.isLiquid() || m.isAir() || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                    || m == Material.POWDER_SNOW || m.name().contains("LEAVES") || b.getY() <= w.getMinHeight() + 1) {
                findAround(c, min, max, tries - 1, cb);
            } else {
                cb.accept(b.getLocation().add(0.5, 1, 0.5));
            }
        });
    }
}
