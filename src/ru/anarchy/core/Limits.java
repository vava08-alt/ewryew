package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Ограничения: спавнеры/воронки в чанке, анти-лаг-машины (редстоун), лимит мобов, очистка предметов с таймером. */
public final class Limits implements Listener {
    private final AnarchyCore pl;
    private final Map<Long, Integer> redstone = new HashMap<>();
    private final Map<Long, Integer> pistons = new HashMap<>();
    private long clearAt;
    private final Set<Integer> warned = new HashSet<>();

    public Limits(AnarchyCore pl) {
        this.pl = pl;
        clearAt = System.currentTimeMillis() + interval();
        Bukkit.getScheduler().runTaskTimer(pl, () -> { redstone.clear(); pistons.clear(); }, 20, 20);
        Bukkit.getScheduler().runTaskTimer(pl, this::clearTick, 20, 20);
    }

    private int cfg(String k, int d) { return pl.getConfig().getInt("limits." + k, d); }
    private long interval() { return Math.max(1, cfg("clear-items-minutes", 10)) * 60_000L; }
    private static long key(Chunk c) { return ((long) c.getX() << 32) ^ (c.getZ() & 0xffffffffL) ^ ((long) c.getWorld().getName().hashCode() << 16); }

    private int count(Chunk c, Material m) {
        int n = 0;
        for (BlockState st : c.getTileEntities()) if (st.getType() == m) n++;
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlock().getType();
        int max = switch (m) {
            case SPAWNER, TRIAL_SPAWNER -> cfg("spawners-per-chunk", 6);
            case HOPPER -> cfg("hoppers-per-chunk", 32);
            case OBSERVER -> cfg("observers-per-chunk", 48);
            case PISTON, STICKY_PISTON -> cfg("pistons-per-chunk", 64);
            default -> -1;
        };
        if (max < 0 || e.getPlayer().hasPermission("anarchy.admin")) return;
        int have;
        if (m == Material.OBSERVER || m == Material.PISTON || m == Material.STICKY_PISTON) {
            have = 0; Chunk c = e.getBlock().getChunk();
            int minY = c.getWorld().getMinHeight(), maxY = c.getWorld().getMaxHeight();
            var snap = c.getChunkSnapshot(false, false, false);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < maxY; y++) if (snap.getBlockType(x, y, z) == m) have++;
            have--; // уже поставленный
        } else have = count(e.getBlock().getChunk(), m) - 1;
        if (have >= max) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>Лимит в чанке: максимум " + max + " шт. (" + Util.matName(m) + ") - защита от лагов"); }
    }

    // ---------- анти-лаг-машины ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent e) {
        if (e.getOldCurrent() == e.getNewCurrent()) return;
        long k = key(e.getBlock().getChunk());
        int n = redstone.merge(k, 1, Integer::sum);
        int max = cfg("redstone-updates-per-second", 400);
        if (n > max) {
            e.setNewCurrent(0);
            if (n == max + 1 || n % 200 == 0) breakClock(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        long k = key(e.getBlock().getChunk());
        if (pistons.merge(k, 1, Integer::sum) > cfg("piston-moves-per-second", 120)) e.setCancelled(true);
    }

    /** Ломает источник частых сигналов (часы) - рядом стоящие репитеры/компараторы/наблюдатели. */
    private void breakClock(Block b) {
        int broken = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            Block x = b.getRelative(dx, dy, dz);
            Material m = x.getType();
            if (m == Material.REPEATER || m == Material.COMPARATOR || m == Material.OBSERVER || m == Material.REDSTONE_WIRE || m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH) { x.breakNaturally(); broken++; }
        }
        if (broken > 0) {
            pl.getLogger().info("Анти-лаг: сломана редстоун-машина " + b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ());
            for (Player p : b.getWorld().getPlayers()) if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) pl.msg(p, "<red>Анти-лаг: слишком быстрая редстоун-схема сломана рядом с <white>" + b.getX() + " " + b.getY() + " " + b.getZ());
        }
    }

    // ---------- лимит мобов ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        // фантомы отключены: естественный спавн (бессонница) не происходит ни в одном мире
        if (e.getEntityType() == org.bukkit.entity.EntityType.PHANTOM && pl.getConfig().getBoolean("limits.disable-phantoms", true)
                && r != CreatureSpawnEvent.SpawnReason.COMMAND && r != CreatureSpawnEvent.SpawnReason.CUSTOM && r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) { e.setCancelled(true); return; }
        if (r != CreatureSpawnEvent.SpawnReason.SPAWNER && r != CreatureSpawnEvent.SpawnReason.BREEDING && r != CreatureSpawnEvent.SpawnReason.EGG
                && r != CreatureSpawnEvent.SpawnReason.DISPENSE_EGG && r != CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM) return;
        int max = cfg("mobs-per-chunk", 40);
        int n = 0;
        for (Entity en : e.getLocation().getChunk().getEntities()) if (en instanceof LivingEntity && !(en instanceof Player)) n++;
        if (n >= max) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        int n = 0;
        for (Entity en : e.getEntity().getLocation().getChunk().getEntities()) if (en.getType() == e.getEntity().getType()) n++;
        if (n > cfg("same-animals-per-chunk", 24)) { e.setCancelled(true); if (e.getBreeder() instanceof Player p) pl.msg(p, "<red>Слишком много животных в этом чанке"); }
    }

    // ---------- очистка предметов ----------
    private void clearTick() {
        long left = (clearAt - System.currentTimeMillis()) / 1000;
        for (int w : new int[]{60, 30, 10}) if (left == w && warned.add(w))
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<yellow>⚠ Предметы на земле будут удалены через <white>" + w + " сек"));
        if (left > 0 && left <= 5) for (Player pp : Bukkit.getOnlinePlayers()) pp.sendActionBar(mm("<yellow>⚠ Очистка предметов через <white><bold>" + left + "</bold></white> сек"));
        if (left <= 0) {
            int n = 0;
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
                if (e instanceof Item it) {
                    if (it.getTicksLived() < 200) continue; // только что выпавшие не трогаем
                    Material m = it.getItemStack().getType();
                    if (m == Material.NETHER_STAR || m == Material.ELYTRA || m == Material.DRAGON_EGG || m.name().contains("SHULKER_BOX")) continue;
                    if (it.getPersistentDataContainer().has(pl.events().lootKey(), org.bukkit.persistence.PersistentDataType.BYTE)) continue; // лут ивента
                    if (pl.dungeon() != null && (pl.dungeon().isDungeon(w) || pl.dungeon().isDevilItem(it.getItemStack()))) continue;
                    e.remove(); n++;
                } else if (e instanceof Arrow a && a.isInBlock() || e instanceof ExperienceOrb) { e.remove(); }
            }
            clearAt = System.currentTimeMillis() + interval();
            warned.clear();
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<green>Очистка: удалено предметов на земле: <white>" + n));
        }
    }

    public long clearIn() { return Math.max(0, clearAt - System.currentTimeMillis()); }
}
