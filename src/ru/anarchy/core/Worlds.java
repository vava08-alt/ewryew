package ru.anarchy.core;

import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.generator.ChunkGenerator;

import java.time.Duration;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Отдельный мир-хаб со спавном: вход, без урона, без мобов, портал в мир анархии. */
public final class Worlds implements Listener {
    public static final class Void extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        /** фиксированная точка спавна - сервер не ищет землю в пустоте (иначе зависание при создании мира) */
        @Override public org.bukkit.Location getFixedSpawnLocation(org.bukkit.World world, java.util.Random random) { return new org.bukkit.Location(world, 0.5, 65, 0.5); }
    }

    private final AnarchyCore pl;
    private World hub;
    private final Map<UUID, Long> portalCd = new HashMap<>();

    public Worlds(AnarchyCore pl) { this.pl = pl; }

    public World hub() { return hub; }
    public boolean isHub(World w) { return w != null && w.equals(hub); }

    public void load() {
        String name = pl.getConfig().getString("spawn.world", "spawn");
        hub = Bukkit.getWorld(name);
        if (hub == null) hub = new WorldCreator(name).generator(new Void()).generateStructures(false).createWorld();
        if (hub == null) { pl.getLogger().severe("Не удалось загрузить мир спавна " + name); return; }
        hub.setDifficulty(Difficulty.PEACEFUL);
        hub.setPVP(false);
        hub.setStorm(false);
        hub.setThundering(false);
        hub.setTime(pl.getConfig().getLong("spawn.time", 6000));
        rule(GameRules.ADVANCE_TIME, false);
        rule(GameRules.ADVANCE_WEATHER, false);
        rule(GameRules.SPAWN_MOBS, false);
        rule(GameRules.SPAWN_MONSTERS, false);
        rule(GameRules.SPAWN_PATROLS, false);
        rule(GameRules.SPAWN_WANDERING_TRADERS, false);
        rule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        rule(GameRules.MOB_GRIEFING, false);
        rule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        rule(GameRules.PVP, false);
        rule(GameRules.FALL_DAMAGE, false);
        rule(GameRules.SPAWN_PHANTOMS, false);
        rule(GameRules.KEEP_INVENTORY, true);
        rule(GameRules.RANDOM_TICK_SPEED, 0);
        hub.setAutoSave(true);
        for (Entity e : hub.getEntities())
            if (e instanceof Monster || e instanceof Item || e instanceof ExperienceOrb) e.remove();
    }

    private <T> void rule(GameRule<T> r, T v) { try { hub.setGameRule(r, v); } catch (Exception ignored) {} }

    public Location hubPoint() { return pl.spawn() != null ? pl.spawn() : hub == null ? null : hub.getSpawnLocation(); }

    // ---------- вход: всегда в хаб ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = pl.data(p);
        Location hp = hubPoint();
        if (hp == null) return;
        if (!isHub(p.getWorld()) && p.hasPlayedBefore() && !(pl.dungeon() != null && pl.dungeon().isDungeon(p.getWorld()))) d.returnLoc = PlayerData.locToString(p.getLocation());
        p.teleport(hp);
        p.setFallDistance(0);
        p.setFireTicks(0);
    }

    /** После входа в аккаунт - возвращаем туда, где игрок вышел. */
    public void afterAuth(Player p, boolean firstTime) {
        PlayerData d = pl.data(p);
        if (firstTime || d.returnLoc == null) {
            p.showTitle(Title.title(mm(pl.getConfig().getString("visual.title", "AquaAnarchy")),
                    mm("<gray>Портал впереди - в мир анархии"), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            return;
        }
        p.showTitle(Title.title(mm(pl.getConfig().getString("visual.title", "AquaAnarchy")),
                mm("<gray>С возвращением! <aqua>Возвращаем на место выхода..."), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
        String saved = d.returnLoc;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || !isHub(p.getWorld())) return;
            Location l = PlayerData.locFromString(saved);
            d.returnLoc = null;
            if (l == null) return;
            p.teleportAsync(l).thenAccept(ok -> {
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.3f);
                pl.msg(p, "<gray>Ты на месте выхода. Спавн: <aqua>/spawn</aqua>, меню: <yellow>/menu");
            });
        }, pl.getConfig().getLong("spawn.return-delay-ticks", 50));
    }

    // ---------- без урона / мобов ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!isHub(e.getEntity().getWorld())) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player p) {
            p.setFireTicks(0);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) toSpawn(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent e) { if (isHub(e.getEntity().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (isHub(e.getEntity().getWorld()) && e.getFoodLevel() < e.getEntity().getFoodLevel()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!isHub(e.getLocation().getWorld())) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.CUSTOM && r != CreatureSpawnEvent.SpawnReason.COMMAND) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent e) { if (isHub(e.getEntity().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPotion(EntityPotionEffectEvent e) {
        if (isHub(e.getEntity().getWorld()) && e.getEntity() instanceof Player && e.getNewEffect() != null
                && e.getCause() != EntityPotionEffectEvent.Cause.PLUGIN && e.getCause() != EntityPotionEffectEvent.Cause.COMMAND
                && isBad(e.getNewEffect().getType())) e.setCancelled(true);
    }

    private static boolean isBad(org.bukkit.potion.PotionEffectType t) {
        return t.getEffectCategory() == org.bukkit.potion.PotionEffectType.Category.HARMFUL;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHanging(HangingBreakEvent e) { if (isHub(e.getEntity().getWorld()) && e.getCause() != HangingBreakEvent.RemoveCause.ENTITY) e.setCancelled(true); }

    @EventHandler public void onWeather(WeatherChangeEvent e) { if (isHub(e.getWorld()) && e.toWeatherState()) e.setCancelled(true); }
    @EventHandler public void onFade(BlockFadeEvent e) { if (isHub(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler public void onForm(BlockFormEvent e) { if (isHub(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler public void onSpread(BlockSpreadEvent e) { if (isHub(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler public void onDecay(LeavesDecayEvent e) { if (isHub(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler public void onEntityPortal(EntityPortalEvent e) { if (isHub(e.getFrom().getWorld())) e.setCancelled(true); }

    // ---------- портал и пустота ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent e) { if (isHub(e.getFrom().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock() || !isHub(e.getTo().getWorld())) return;
        Player p = e.getPlayer();
        if (e.getTo().getY() < pl.getConfig().getInt("spawn.void-y", -70)) { toSpawn(p); return; }
        boolean devil = pl.dungeon() != null && pl.dungeon().isSpawnPortal(e.getTo());
        if (!devil && !isPortal(e.getTo().getBlock().getType()) && !isPortal(e.getTo().getBlock().getRelative(0, 1, 0).getType())) return;
        PlayerData d = pl.data(p);
        if (!d.authed) return;
        if (devil) { pl.dungeon().portalEnter(p); return; }
        long now = System.currentTimeMillis();
        if (portalCd.getOrDefault(p.getUniqueId(), 0L) > now) return;
        portalCd.put(p.getUniqueId(), now + 5000);
        portalRtp(p);
    }

    private static boolean isPortal(Material m) { return m == Material.NETHER_PORTAL || m == Material.END_PORTAL || m == Material.END_GATEWAY; }

    private void toSpawn(Player p) {
        Location s = hubPoint();
        if (s == null) return;
        p.setFallDistance(0);
        p.teleportAsync(s);
    }

    /** Портал на спавне: бесплатный случайный телепорт в мир анархии. */
    public void portalRtp(Player p) {
        World w = Bukkit.getWorld(pl.getConfig().getString("rtp.world", "world"));
        if (w == null) return;
        p.showTitle(Title.title(mm("<gradient:#00f0ff:#0080ff><bold>В мир анархии</bold></gradient>"), mm("<gray>Ищу безопасное место..."),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(4), Duration.ofMillis(300))));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.4f, 1.6f);
        pl.teleports().findAround(new Location(w, 0, 64, 0), pl.getConfig().getInt("rtp.normal.min", 300), pl.getConfig().getInt("rtp.normal.max", 3000), 15, loc -> {
            if (!p.isOnline()) return;
            if (loc == null) { pl.msg(p, "<red>Не удалось найти место, зайди в портал ещё раз"); return; }
            pl.teleports().teleport(p, loc);
            p.showTitle(Title.title(mm("<red><bold>☠ Анархия"), mm("<gray>" + loc.getBlockX() + ", " + loc.getBlockZ() + " - выживай!"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(500))));
        });
    }
}
