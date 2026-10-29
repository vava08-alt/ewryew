package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/**
 * ⚡ Стрела Зевса: в небе (y >= 80) появляется облако радиусом до 25 блоков. Молнии бьют в облако и зажигают огонь
 * случайной редкости - потуши его нужное число раз (ЛКМ по огню) и забери выпавший лут этой редкости. PvP разрешено.
 * Подняться на облако - восходящий поток (столб частиц) у края. После ивента облако исчезает без следа.
 */
public final class Zeus implements Listener {
    private static final class Fire {
        Block fire, base; Events.Tier tier; int need, hits; TextDisplay holo; long born;
        final Map<UUID, Long> cd = new HashMap<>();
    }

    private final AnarchyCore pl;
    private World world;
    private Location center;
    private int cy, topY, radius;
    private long start, end, nextStrike;
    private boolean building;
    private final Set<Block> cloud = new LinkedHashSet<>();
    private final Set<Block> top = new LinkedHashSet<>();
    private final List<Fire> fires = new ArrayList<>();
    private Location column;
    private BossBar bar;
    /** Редкость самого ивента: ограничивает тир сфер и талисманов со всех огней. */
    private Events.Tier eventTier = Events.Tier.COMMON;
    private final Set<UUID> shown = new HashSet<>();
    private final Set<UUID> lifted = new HashSet<>();

    public Zeus(AnarchyCore pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40, 10);
        Bukkit.getScheduler().runTask(pl, () -> restore(0));
    }

    private int cfg(String k, int d) { return pl.getConfig().getInt("events.zeus." + k, d); }
    private File journal() { return new File(pl.getDataFolder(), "zeus-restore.yml"); }
    private static String xz(Location l) { return l.getBlockX() + " " + l.getBlockZ(); }

    public boolean running() { return center != null; }

    public List<MoreEvents.Active> active() {
        if (center == null) return List.of();
        return List.of(new MoreEvents.Active("zeus", MoreEvents.name("zeus") + " <dark_gray>[" + eventTier.tag() + "<dark_gray>]" + (fires.isEmpty() ? "" : " <dark_gray>[<yellow>огней: " + fires.size() + "<dark_gray>]"),
                center.clone().add(0, 2, 0), start, end, building ? "облако собирается" : "до конца", BossBar.Color.YELLOW));
    }

    // ===================== запуск =====================
    public boolean start() {
        if (center != null || building) return false;
        building = true;
        eventTier = pl.events().rollEventTier("zeus");
        pl.events().randomSurface("events.zeus", loc -> {
            if (loc == null) { building = false; return; }
            World w = loc.getWorld();
            radius = Math.max(8, Math.min(25, cfg("radius", 25)));
            List<CompletableFuture<Chunk>> wait = new ArrayList<>();
            for (int x = (loc.getBlockX() - radius) >> 4; x <= (loc.getBlockX() + radius) >> 4; x++)
                for (int z = (loc.getBlockZ() - radius) >> 4; z <= (loc.getBlockZ() + radius) >> 4; z++) wait.add(w.getChunkAtAsync(x, z));
            CompletableFuture.allOf(wait.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(pl, () -> begin(loc)));
        });
        return true;
    }

    private void begin(Location loc) {
        World w = loc.getWorld();
        int cx = loc.getBlockX(), cz = loc.getBlockZ();
        int maxTerrain = loc.getBlockY();
        for (int dx = -radius; dx <= radius; dx += 3) for (int dz = -radius; dz <= radius; dz += 3)
            if (dx * dx + dz * dz <= radius * radius) maxTerrain = Math.max(maxTerrain, w.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING));
        cy = Math.max(cfg("min-height", 80), maxTerrain + cfg("clearance", 10));
        cy = Math.min(cy, w.getMaxHeight() - 16);
        world = w;
        center = new Location(w, cx + 0.5, cy, cz + 0.5);
        pl.events().hold("zeus", center, radius + 3);
        start = System.currentTimeMillis();
        end = start + cfg("minutes", 10) * 60_000L;
        // восходящий поток - сквозная дыра в облаке ближе к краю
        double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        int colX = cx + (int) Math.round(Math.cos(a) * radius * 0.55), colZ = cz + (int) Math.round(Math.sin(a) * radius * 0.55);
        column = new Location(w, colX + 0.5, w.getHighestBlockYAt(colX, colZ, HeightMap.MOTION_BLOCKING) + 1, colZ + 0.5);
        buildCloud(cx, cz);
        nextStrike = System.currentTimeMillis() + 8_000;
        bar = BossBar.bossBar(mm(MoreEvents.name("zeus")), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        building = false;
        pl.events().announce(MoreEvents.name("zeus"), "<gray>Редкость: " + eventTier.tag() + " <yellow>- облако над <white>" + xz(center) + "<yellow> (y " + cy + "), туши огни и забирай лут! <red>PvP включено");
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>Подняться на облако: <aqua>восходящий поток</aqua> у <white>" + xz(column) + "<gray>. Огни редкостей: "
                + Events.Tier.COMMON.tag() + " <gray>" + need(Events.Tier.COMMON) + ", " + Events.Tier.RARE.tag() + " <gray>" + need(Events.Tier.RARE) + ", "
                + Events.Tier.EPIC.tag() + " <gray>" + need(Events.Tier.EPIC) + ", " + Events.Tier.DIVINE.tag() + " <gray>" + need(Events.Tier.DIVINE) + " ударов."));
    }

    private int need(Events.Tier t) {
        int[] def = {5, 7, 10, 14};
        return Math.max(1, pl.getConfig().getInt("events.zeus.extinguish." + t.id, def[t.ordinal()]));
    }

    /** Облако из «клубов»: плоское основание + шапки сверху. Ставится только в воздух. */
    private void buildCloud(int cx, int cz) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Set<Long> cells = new HashSet<>();
        int R = radius;
        double base = R * 0.78;
        addPuff(cells, cx, cy, cz, base, 2.2, base * (0.8 + r.nextDouble() * 0.2), R, cx, cz);
        int puffs = 6 + R / 3;
        for (int i = 0; i < puffs; i++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble() * R * 0.62;
            double pr = (4 + r.nextDouble() * 4.5) * R / 25.0 + 2;
            addPuff(cells, cx + Math.cos(a) * d, cy + 1 + r.nextDouble() * 1.5, cz + Math.sin(a) * d, pr, 2.2 + r.nextDouble() * 2.3, pr * (0.8 + r.nextDouble() * 0.4), R, cx, cz);
        }
        // дыра для восходящего потока
        int hx = column.getBlockX(), hz = column.getBlockZ();
        cells.removeIf(k -> { int x = kx(k), z = kz(k); return Math.abs(x - hx) <= 1 && Math.abs(z - hz) <= 1; });
        Set<Long> colReg = new HashSet<>();
        topY = cy;
        cloud.clear(); top.clear();
        for (long k : cells) {
            int x = kx(k), y = ky(k), z = kz(k);
            long col = ((long) x << 32) ^ (z & 0xffffffffL);
            if (colReg.contains(col)) continue;
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) continue;
            if (pl.regions().at(b.getLocation()) != null) { colReg.add(col); continue; }
            boolean up = !cells.contains(key(x, y + 1, z)), down = !cells.contains(key(x, y - 1, z));
            boolean edge = !cells.contains(key(x + 1, y, z)) || !cells.contains(key(x - 1, y, z)) || !cells.contains(key(x, y, z + 1)) || !cells.contains(key(x, y, z - 1));
            Material m = up ? Material.SNOW_BLOCK : down ? (r.nextInt(10) < 7 ? Material.LIGHT_GRAY_CONCRETE : Material.WHITE_CONCRETE)
                    : edge && r.nextInt(5) == 0 ? Material.WHITE_STAINED_GLASS : Material.WHITE_CONCRETE;
            if (up && edge && r.nextInt(6) == 0) m = Material.WHITE_STAINED_GLASS;
            b.setType(m, false);
            cloud.add(b);
            if (up && m == Material.SNOW_BLOCK) top.add(b);
            topY = Math.max(topY, y);
        }
        save();
    }

    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    private static int kx(long k) { return (int) (k >> 38) << 6 >> 6; }
    private static int kz(long k) { return (int) ((k >> 12) & 0x3FFFFFF) << 6 >> 6; }
    private static int ky(long k) { return (int) (k & 0xFFF) << 20 >> 20; }

    private void addPuff(Set<Long> cells, double px, double py, double pz, double rx, double ry, double rz, int R, int cx, int cz) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int x = (int) Math.floor(px - rx); x <= (int) Math.ceil(px + rx); x++)
            for (int z = (int) Math.floor(pz - rz); z <= (int) Math.ceil(pz + rz); z++) {
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > R * R) continue;
                for (int y = (int) Math.floor(py - ry); y <= (int) Math.ceil(py + ry); y++) {
                    double dx = (x + 0.5 - px) / rx, dy = (y + 0.5 - py) / (y < py ? ry * 0.6 : ry), dz = (z + 0.5 - pz) / rz;
                    if (dx * dx + dy * dy + dz * dz <= 1 + (r.nextDouble() - 0.5) * 0.12) cells.add(key(x, y, z));
                }
            }
    }

    // ===================== молнии и огни =====================
    private boolean strike() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Block> cand = new ArrayList<>();
        for (Block b : top) {
            if (b.getType() != Material.SNOW_BLOCK) continue;
            Block f = b.getRelative(BlockFace.UP);
            if (!f.getType().isAir() || !f.getRelative(BlockFace.UP).getType().isAir()) continue;
            if (column != null && Math.abs(b.getX() - column.getBlockX()) <= 3 && Math.abs(b.getZ() - column.getBlockZ()) <= 3) continue;
            boolean near = false;
            for (Fire o : fires) if (o.base.getLocation().distanceSquared(b.getLocation()) < 36) { near = true; break; }
            if (!near) cand.add(b);
        }
        if (cand.isEmpty()) return false;
        Block base = cand.get(r.nextInt(cand.size()));
        Fire f = new Fire();
        f.tier = pl.events().rollTier("zeus");
        f.need = need(f.tier);
        f.base = base;
        f.fire = base.getRelative(BlockFace.UP);
        f.born = System.currentTimeMillis();
        boolean soul = f.tier.ordinal() >= Events.Tier.EPIC.ordinal();
        base.setType(soul ? Material.SOUL_SOIL : Material.NETHERRACK, false);
        f.fire.setType(soul ? Material.SOUL_FIRE : Material.FIRE, false);
        Location l = f.fire.getLocation().add(0.5, 0, 0.5);
        world.strikeLightningEffect(l);
        world.spawnParticle(Particle.ELECTRIC_SPARK, l, 80, 1.5, 1, 1.5, 0.3);
        world.spawnParticle(f.tier.particle, l, 40, 1, 1, 1, 0.1);
        fires.add(f);
        holo(f);
        save();
        String msg = pl.getConfig().getString("prefix", "") + "<yellow>⚡ Молния Зевса зажгла " + f.tier.tag("огонь") + " <yellow>на облаке <white>" + xz(center) + " <gray>(потушить: " + f.need + " ударов)";
        if (f.tier.ordinal() >= Events.Tier.EPIC.ordinal()) Bukkit.broadcast(mm(msg));
        else for (Player p : world.getPlayers()) if (near(p, 200)) p.sendMessage(mm(msg));
        for (Player p : world.getPlayers()) if (near(p, 250)) p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.8f);
        return true;
    }

    /** Молния-декор по облаку (когда огней уже максимум), чтобы гроза не затихала. */
    private void ambientStrike() {
        if (top.isEmpty()) return;
        List<Block> tl = new ArrayList<>(top);
        Block b = tl.get(ThreadLocalRandom.current().nextInt(tl.size()));
        Location l = b.getLocation().add(0.5, 1, 0.5);
        world.strikeLightningEffect(l);
        world.spawnParticle(Particle.ELECTRIC_SPARK, l, 40, 1.2, 0.8, 1.2, 0.3);
        for (Player p : world.getPlayers()) if (near(p, 120)) p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.1f);
    }

    private void holo(Fire f) {
        if (f.holo == null || !f.holo.isValid()) {
            if (!world.isChunkLoaded(f.fire.getX() >> 4, f.fire.getZ() >> 4)) return;
            f.holo = world.spawn(f.fire.getLocation().add(0.5, 1.6, 0.5), TextDisplay.class, t -> { t.setPersistent(false); t.setBillboard(Display.Billboard.CENTER); t.setShadowed(true); });
        }
        int left = f.need - f.hits;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < f.need; i++) bar.append(i < f.hits ? "<dark_gray>|" : "<white>|");
        f.holo.text(mm(f.tier.tag("огонь") + "\n" + bar + "\n<gray>Потуши ещё <white>" + left + "<gray> раз <dark_gray>[ЛКМ]"));
    }

    private Fire fireAt(Block b) { for (Fire f : fires) if (f.fire.equals(b) || f.base.equals(b)) return f; return null; }

    private void hit(Player p, Fire f) {
        long now = System.currentTimeMillis();
        if (now - f.cd.getOrDefault(p.getUniqueId(), 0L) < Math.max(150, cfg("hit-cooldown-ms", 300))) return;
        f.cd.put(p.getUniqueId(), now);
        f.hits++;
        Location l = f.fire.getLocation().add(0.5, 0.3, 0.5);
        world.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.8f + 0.6f * f.hits / f.need);
        world.spawnParticle(Particle.LARGE_SMOKE, l, 6, 0.3, 0.3, 0.3, 0.02);
        if (f.hits < f.need) { holo(f); return; }
        // потушен!
        removeFire(f);
        fires.remove(f);
        save();
        // обычный/редкий огонь - 1..3 предмета из лута редкости; эпический/божественный - один, но ценный (пул zeus_single_<редкость>)
        List<ItemStack> drops;
        if (f.tier.ordinal() >= Events.Tier.EPIC.ordinal()) drops = single(f.tier);
        else {
            List<ItemStack> loot = new ArrayList<>(pl.events().tierLoot(f.tier, "zeus", eventTier));
            Collections.shuffle(loot);
            int lo = Math.max(1, cfg("loot-min", 1)), hi = Math.max(lo, cfg("loot-max", 3));
            drops = loot.subList(0, Math.min(loot.size(), ThreadLocalRandom.current().nextInt(lo, hi + 1)));
        }
        for (ItemStack it : drops) pl.events().dropLoot(l.clone().add(0, 0.4, 0), it);
        MoreEvents.firework(pl, l.clone().add(0, 1, 0), f.tier.color);
        world.spawnParticle(f.tier.particle, l, 60, 0.6, 0.8, 0.6, 0.15);
        world.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        String msg = pl.getConfig().getString("prefix", "") + "<white>" + p.getName() + " <yellow>потушил " + f.tier.tag("огонь") + " <yellow>Зевса - лут выпал на облаке!";
        if (f.tier.ordinal() >= Events.Tier.EPIC.ordinal()) Bukkit.broadcast(mm(msg));
        else for (Player o : world.getPlayers()) if (near(o, 200)) o.sendMessage(mm(msg));
        nextStrike = Math.min(nextStrike, now + 10_000);
    }

    /** Один ценный предмет с эпического/божественного огня, тир сфер ограничен редкостью ивента. */
    private List<ItemStack> single(Events.Tier t) {
        ItemStack one = pl.getConfig().isList("events.pools.zeus_single_" + t.id) ? pl.events().fromPool("zeus_single_" + t.id) : null;
        if (one == null) {
            List<ItemStack> loot = pl.events().tierLoot(t, "zeus", eventTier);
            for (ItemStack it : loot) if (pl.items().id(it) != null) { one = it; break; }
            if (one == null && !loot.isEmpty()) one = loot.get(0);
        }
        if (one == null) return List.of();
        return pl.events().capSea(new ArrayList<>(List.of(one)), pl.events().seaCap(eventTier));
    }

    private void removeFire(Fire f) {
        if (f.holo != null) f.holo.remove();
        if (f.fire.getType() == Material.FIRE || f.fire.getType() == Material.SOUL_FIRE) f.fire.setType(Material.AIR, false);
        if (cloud.contains(f.base)) f.base.setType(Material.SNOW_BLOCK, false);
    }

    private boolean near(Player p, double d) {
        if (center == null || !p.getWorld().equals(world)) return false;
        double dx = p.getLocation().getX() - center.getX(), dz = p.getLocation().getZ() - center.getZ();
        return dx * dx + dz * dz <= d * d;
    }
    private boolean inZone(Block b) {
        if (center == null || !b.getWorld().equals(world)) return false;
        double dx = b.getX() + 0.5 - center.getX(), dz = b.getZ() + 0.5 - center.getZ();
        return dx * dx + dz * dz <= (radius + 3) * (radius + 3) && b.getY() >= cy - 6 && b.getY() <= topY + 12;
    }

    // ===================== тик =====================
    private void tick() {
        if (center == null || building) return;
        long now = System.currentTimeMillis();
        try {
            if (now >= end) { finish(true); return; }
            if (now >= nextStrike) {
                if (!(fires.size() < cfg("max-fires", 8) && strike())) ambientStrike();
                int a = cfg("strike-seconds.min", 5), b = Math.max(a + 1, cfg("strike-seconds.max", 10));
                nextStrike = now + ThreadLocalRandom.current().nextInt(a, b + 1) * 1000L;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // огни: столб частиц-маяк и восстановление, если огонь кто-то убрал
            for (Fire f : fires) {
                boolean soul = f.tier.ordinal() >= Events.Tier.EPIC.ordinal();
                if (!world.isChunkLoaded(f.fire.getX() >> 4, f.fire.getZ() >> 4)) continue;
                if (f.fire.getType().isAir()) f.fire.setType(soul ? Material.SOUL_FIRE : Material.FIRE, false);
                if (f.holo == null || !f.holo.isValid()) holo(f);
                Location l = f.fire.getLocation().add(0.5, 0.5, 0.5);
                for (int y = 1; y < 14; y += 2) world.spawnParticle(f.tier.particle, l.clone().add(0, y, 0), 1, 0.08, 0.3, 0.08, 0);
                if (r.nextInt(4) == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, l, 6, 0.4, 0.4, 0.4, 0.05);
            }
            // облачная дымка снизу
            if (!cloud.isEmpty()) for (int i = 0; i < 6; i++) {
                double a = r.nextDouble(Math.PI * 2), d = r.nextDouble() * radius * 0.8;
                world.spawnParticle(Particle.CLOUD, center.clone().add(Math.cos(a) * d, -1.5, Math.sin(a) * d), 2, 1.5, 0.3, 1.5, 0.005);
            }
            updraft();
            barTick(now);
        } catch (Exception ex) { pl.getLogger().warning("Zeus: " + ex); ex.printStackTrace(); }
    }

    /** Восходящий поток: стоишь в столбе - поднимает к облаку, наверху сносит на облако. */
    private void updraft() {
        if (column == null) return;
        int colTop = topY + 2;
        Location base = column;
        for (double y = base.getY(); y < colTop + 1; y += 2.5)
            world.spawnParticle(Particle.CLOUD, base.getX(), y, base.getZ(), 1, 0.5, 0.6, 0.5, 0.02);
        world.spawnParticle(Particle.END_ROD, base.getX(), base.getY() + ThreadLocalRandom.current().nextDouble(colTop - base.getY()), base.getZ(), 2, 0.4, 0.5, 0.4, 0.02);
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - base.getX(), dz = l.getZ() - base.getZ();
            boolean in = dx * dx + dz * dz <= 2.3 && l.getY() >= base.getY() - 2 && l.getY() <= colTop + 1;
            if (in && l.getY() < colTop) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 25, 5, false, false, true));
                lifted.add(p.getUniqueId());
            } else if (lifted.remove(p.getUniqueId())) {
                p.removePotionEffect(PotionEffectType.LEVITATION);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0, false, false, true));
                if (in) {
                    Vector v = center.toVector().subtract(l.toVector()).setY(0);
                    if (v.lengthSquared() > 0.01) p.setVelocity(v.normalize().multiply(0.7).setY(0.25));
                }
            }
        }
    }

    private void barTick(long now) {
        if (bar == null) return;
        bar.progress(Math.max(0f, Math.min(1f, (end - now) / (float) Math.max(1, end - start))));
        Events.Tier best = null;
        for (Fire f : fires) if (best == null || f.tier.ordinal() > best.ordinal()) best = f.tier;
        bar.color(best == null ? BossBar.Color.YELLOW : best.bar);
        bar.name(mm(MoreEvents.name("zeus") + " <dark_gray>[" + eventTier.tag() + "<dark_gray>] | <yellow>огней: <white>" + fires.size() + (best == null ? "" : " <dark_gray>(" + best.tag() + "<dark_gray>)")
                + " <dark_gray>| <gray>до конца <white>" + MoreEvents.mmss(end - now)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = near(p, cfg("bar-radius", 70));
            if (in && shown.add(p.getUniqueId())) p.showBossBar(bar);
            else if (!in && shown.remove(p.getUniqueId())) p.hideBossBar(bar);
        }
    }

    // ===================== конец =====================
    public boolean stop() { if (center == null) return false; finish(false); return true; }

    private void finish(boolean natural) {
        for (Fire f : fires) removeFire(f);
        fires.clear();
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        shown.clear();
        // всех, кто на облаке или под ним, - мягко на землю
        if (world != null) for (Player p : world.getPlayers())
            if (near(p, radius + 12) && p.getLocation().getY() > cy - 30) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 40, 0, false, false, true));
                p.removePotionEffect(PotionEffectType.LEVITATION);
                pl.msg(p, "<gray>Облако Зевса рассеивается - медленное падение на 40 сек.");
            }
        for (Block b : cloud) b.setType(Material.AIR, false);
        if (natural && center != null) Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + MoreEvents.name("zeus") + " <gray>завершена - облако рассеялось."));
        cloud.clear(); top.clear(); lifted.clear();
        pl.events().release("zeus");
        center = null; column = null; bar = null; world = null;
        journal().delete();
    }

    public void shutdown() { if (center != null) finish(false); }

    // ===================== журнал на случай сбоя =====================
    private void save() {
        if (cloud.isEmpty()) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", world.getName());
        List<String> l = new ArrayList<>(cloud.size() + fires.size());
        for (Block b : cloud) l.add(b.getX() + "," + b.getY() + "," + b.getZ());
        for (Fire f : fires) l.add(f.fire.getX() + "," + f.fire.getY() + "," + f.fire.getZ());
        y.set("air", l);
        try { y.save(journal()); } catch (Exception ex) { pl.getLogger().warning("zeus-restore: " + ex.getMessage()); }
    }

    private void restore(int tries) {
        File f = journal();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        World w = Bukkit.getWorld(y.getString("world", "world"));
        if (w == null) { if (tries < 12) Bukkit.getScheduler().runTaskLater(pl, () -> restore(tries + 1), 100); return; }
        int n = 0;
        for (String s : y.getStringList("air")) {
            try {
                String[] c = s.split(",");
                Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                Material m = b.getType();
                if (m == Material.SNOW_BLOCK || m == Material.WHITE_CONCRETE || m == Material.LIGHT_GRAY_CONCRETE || m == Material.WHITE_STAINED_GLASS
                        || m == Material.NETHERRACK || m == Material.SOUL_SOIL || m == Material.FIRE || m == Material.SOUL_FIRE) { b.setType(Material.AIR, false); n++; }
            } catch (Exception ignored) {}
        }
        f.delete();
        pl.getLogger().info("Стрела Зевса: после перезапуска убрано блоков облака: " + n);
    }

    // ===================== защита облака =====================
    private final Map<UUID, Long> warn = new HashMap<>();
    private void warn(Player p, String m) {
        long now = System.currentTimeMillis();
        if (now - warn.getOrDefault(p.getUniqueId(), 0L) < 2000) return;
        warn.put(p.getUniqueId(), now);
        pl.msg(p, m);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPunch(org.bukkit.event.player.PlayerInteractEvent e) {
        if (center == null || e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Fire f = fireAt(b);
        if (f == null && e.getBlockFace() == BlockFace.UP) f = fireAt(b.getRelative(BlockFace.UP));
        if (f == null) return;
        e.setCancelled(true);
        hit(e.getPlayer(), f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (center == null) return;
        Block b = e.getBlock();
        Fire f = fireAt(b);
        if (f != null) { e.setCancelled(true); hit(e.getPlayer(), f); return; }
        if (cloud.contains(b)) { e.setCancelled(true); warn(e.getPlayer(), "<gray>Облако Зевса нельзя ломать - оно исчезнет само."); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!inZone(e.getBlockPlaced())) return;
        e.setCancelled(true);
        warn(e.getPlayer(), "<gray>У облака Зевса нельзя ставить блоки - поднимайся по восходящему потоку.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (inZone(e.getBlockClicked().getRelative(e.getBlockFace()))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (inZone(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (center != null && (fireAt(e.getBlock()) != null || cloud.contains(e.getBlock()))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (center != null && fireAt(e.getSource()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) { if (center != null && (fireAt(e.getBlock()) != null || cloud.contains(e.getBlock()))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (center != null) e.blockList().removeIf(b -> cloud.contains(b) || fireAt(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (center != null) e.blockList().removeIf(b -> cloud.contains(b) || fireAt(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { if (center != null && e.getBlocks().stream().anyMatch(b -> cloud.contains(b) || inZone(b.getRelative(e.getDirection())))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { if (center != null && e.getBlocks().stream().anyMatch(cloud::contains)) e.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        shown.remove(u); warn.remove(u);
        if (lifted.remove(u)) e.getPlayer().removePotionEffect(PotionEffectType.LEVITATION);
    }
}
