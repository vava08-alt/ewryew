package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/** Спец-предметы (Золотой купол, Снег правды, Заряд аквамарина) и донатные зелья. */
public final class SpecialItems implements Listener {
    public static final List<String> ITEMS = List.of("golden_dome", "truth_snow", "aqua_charge", "sea_aura", "temple_wall", "coral_net");
    public static final List<String> POTIONS = List.of("athena_kiss", "reaper_blood", "storm_potion", "fugu_potion", "rage_potion");
    private static final Material[] LIGHTS = {Material.GLOWSTONE, Material.SEA_LANTERN, Material.SHROOMLIGHT, Material.OCHRE_FROGLIGHT,
            Material.VERDANT_FROGLIGHT, Material.PEARLESCENT_FROGLIGHT, Material.JACK_O_LANTERN, Material.REDSTONE_LAMP};

    private final AnarchyCore pl;
    private final NamespacedKey PROJ;
    /** временные блоки купола (неломаемые) и борозды: блок -> исходное состояние */
    private final Map<Block, BlockData> dome = new HashMap<>();
    private final Map<Block, BlockData> trench = new HashMap<>();
    private final Map<String, Long> cds = new HashMap<>();
    private static final Set<String> USE = Set.of("golden_dome", "aqua_charge", "sea_aura", "temple_wall", "coral_net");
    /** опутанные сетью: игрок -> (точка, до какого времени) */
    private final Map<UUID, Map.Entry<Location, Long>> rooted = new HashMap<>();

    public SpecialItems(AnarchyCore pl) {
        this.pl = pl;
        PROJ = new NamespacedKey(pl, "special_proj");
    }

    private ConfigurationSection cfg(String id) {
        ConfigurationSection s = pl.getConfig().getConfigurationSection("special." + id);
        return s != null ? s : new org.bukkit.configuration.MemoryConfiguration();
    }

    private String id(ItemStack it) { return pl.items().id(it); }

    // ================= создание =================
    public ItemStack create(String id) {
        return switch (id) {
            case "golden_dome" -> item(id, Material.GOLD_BLOCK, "<gradient:#ffe259:#ffa751><bold>Золотой купол</bold></gradient>",
                    "<gray>Запирает тебя в неломаемой клетке 5x5", "<gray>из жёлтого стекла (внутри 3x3) со светом на макушке.",
                    "", "<gold>Действует: <white>" + cfg(id).getInt("seconds", 15) + " сек <dark_gray>| <gold>КД: <white>" + cfg(id).getInt("cooldown", 30) + " сек", "", "<yellow>ПКМ - поставить купол");
            case "truth_snow" -> item(id, Material.SNOWBALL, "<gradient:#e0f7ff:#7fd3ff><bold>Снег правды</bold></gradient>",
                    "<gray>Брось в противника - все невидимые", "<gray>игроки в радиусе <white>" + cfg(id).getInt("radius", 6) + "</white> блоков подсветятся",
                    "<gray>на <white>" + cfg(id).getInt("glow-seconds", 15) + " сек</white> и получат <white>Замедление I</white>.", "", "<yellow>ПКМ - бросить");
            case "aqua_charge" -> item(id, Material.PRISMARINE_SHARD, "<gradient:#7fffd4:#1e90ff><bold>Заряд аквамарина</bold></gradient>",
                    "<gray>Пробивает борозду-полумесяц из призмарина", "<gray>глубиной <white>4</white>, шириной <white>2</white> и длиной <white>6</white> блоков.", "<gray>Враги в борозде получают <white>слепоту</white> и <white>усталость</white>.",
                    "<gray>Игроков рядом с зарядом засасывает внутрь.", "<dark_gray>Земля восстанавливается через " + cfg(id).getInt("restore-seconds", 30) + " сек", "", "<yellow>ПКМ - метнуть");
            case "athena_kiss" -> potion(id, "<gradient:#fff6b7:#f6416c><bold>Поцелуй Афины</bold></gradient>", Color.fromRGB(0xFFD1DC),
                    "<gray>Благословение мудрой богини.",
                    new PotionEffect(PotionEffectType.HEALTH_BOOST, 3600, 0), new PotionEffect(PotionEffectType.RESISTANCE, 3600, 0));
            case "reaper_blood" -> potion(id, "<gradient:#8b0000:#ff1a1a><bold>Кровь жнеца</bold></gradient>", Color.fromRGB(0x7A0010),
                    "<gray>Сила смерти - за твою кровь.",
                    new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 1), new PotionEffect(PotionEffectType.STRENGTH, 1800, 2), new PotionEffect(PotionEffectType.SPEED, 1800, 2));
            case "storm_potion" -> potion(id, "<gradient:#a8c0ff:#3f2b96><bold>Зелье шторма</bold></gradient>", Color.fromRGB(0x5B6CFF),
                    "<gray>Мчись сквозь огонь, как буря.",
                    new PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 2400, 0), new PotionEffect(PotionEffectType.SPEED, 2400, 2));
            case "fugu_potion" -> potion(id, "<gradient:#f9d423:#7ac943><bold>Зелье фугу</bold></gradient>", Color.fromRGB(0x9ACD32),
                    "<gray>Яд рыбы-фугу. Брось во врага.",
                    new PotionEffect(PotionEffectType.POISON, 2400, 1), new PotionEffect(PotionEffectType.SLOWNESS, 2400, 1));
            case "rage_potion" -> potion(id, "<gradient:#ff4e00:#ec9f05><bold>Зелье ярости</bold></gradient>", Color.fromRGB(0xD7263D),
                    "<gray>Боль делает сильнее. <red>-" + fmt(cfg(id).getDouble("damage", 8) / 2) + " ❤ <gray>при попадании.",
                    new PotionEffect(PotionEffectType.STRENGTH, cfg(id).getInt("seconds", 90) * 20, 3), new PotionEffect(PotionEffectType.SLOWNESS, cfg(id).getInt("seconds", 90) * 20, 1));
            case "sea_aura" -> item(id, Material.HEART_OF_THE_SEA, "<gradient:#00c6ff:#0072ff><bold>Аура моря</bold></gradient>",
                    "<gray>Очищает от <white>всех</white> эффектов,", "<gray>взамен - <white>Сила I</white> и <white>Скорость I</white> на " + cfg(id).getInt("buff-seconds", 30) + " сек.",
                    "", "<gold>КД: <white>" + cfg(id).getInt("cooldown", 30) + " сек", "", "<yellow>ПКМ - использовать");
            case "temple_wall" -> item(id, Material.PRISMARINE_BRICKS, "<gradient:#4dd0e1:#00695c><bold>Стена храма</bold></gradient>",
                    "<gray>Возводит перед тобой нерушимую стену", "<gray><white>5x5</white> блоков толщиной <white>2</white> блока.",
                    "", "<gold>Стоит: <white>" + cfg(id).getInt("seconds", 15) + " сек <dark_gray>| <gold>КД: <white>" + cfg(id).getInt("cooldown", 30) + " сек", "", "<yellow>ПКМ - поставить стену");
            case "coral_net" -> item(id, Material.FIRE_CORAL_FAN, "<gradient:#ff7eb3:#ff4e50><bold>Коралловая сеть</bold></gradient>",
                    "<gray>Брось во врага - все в квадрате <white>3x3</white>", "<gray>не смогут двигаться <white>" + fmt(cfg(id).getDouble("seconds", 3)) + " сек</white>.", "", "<yellow>ПКМ - метнуть");
            default -> null;
        };
    }

    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v); }

    private ItemStack item(String id, Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm("<!italic>" + s));
        l.add(mm(" "));
        l.add(mm("<!italic><gradient:#00e5ff:#2d60ff>✦ СПЕЦ-ПРЕДМЕТ ✦</gradient>"));
        meta.lore(l);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(pl.items().KEY, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack potion(String id, String name, Color color, String desc, PotionEffect... effects) {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) it.getItemMeta();
        meta.displayName(mm("<!italic>" + name));
        for (PotionEffect e : effects) meta.addCustomEffect(e, true);
        meta.setColor(color);
        meta.lore(List.of(mm("<!italic>" + desc), mm(" "), mm("<!italic><gradient:#ff00aa:#aa00ff>✦ ДОНАТ-ЗЕЛЬЕ ✦</gradient> <dark_gray>| взрывное")));
        meta.getPersistentDataContainer().set(pl.items().KEY, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        PotionStack.fix(it, pl);
        return it;
    }

    // ================= использование =================
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (!e.getAction().isRightClick() || e.getItem() == null) return;
        String id = id(e.getItem());
        if (id == null || !USE.contains(id)) return;
        e.setCancelled(true);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        Player p = e.getPlayer();
        if (pl.regions().inSpawn(p.getLocation())) { p.sendActionBar(mm("<red>✘ На спавне нельзя")); return; }
        String key = id + ":" + p.getUniqueId();
        long cd = cfg(id).getLong("cooldown", id.equals("aqua_charge") || id.equals("coral_net") ? 3 : 30) * 1000L;
        long left = cds.getOrDefault(key, 0L) + cd - System.currentTimeMillis();
        if (left > 0) { p.sendActionBar(mm("<red>Перезарядка: " + (left / 1000 + 1) + " сек")); return; }
        switch (id) {
            case "golden_dome" -> { if (!buildDome(p)) return; }
            case "temple_wall" -> { if (!buildWall(p)) return; }
            case "sea_aura" -> aura(p);
            default -> {   // метательные: заряд аквамарина, коралловая сеть
                Snowball s = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().multiply(1.6));
                ItemStack disp = e.getItem().clone(); disp.setAmount(1);
                s.setItem(disp);
                s.getPersistentDataContainer().set(PROJ, PersistentDataType.STRING, id);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WIND_CHARGE_THROW, 1f, id.equals("coral_net") ? 1.3f : 0.7f);
            }
        }
        cds.put(key, System.currentTimeMillis());
        if (p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlaceDome(BlockPlaceEvent e) { if ("golden_dome".equals(id(e.getItemInHand()))) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Snowball s && "truth_snow".equals(id(s.getItem())))
            s.getPersistentDataContainer().set(PROJ, PersistentDataType.STRING, "truth_snow");
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        String t = e.getEntity().getPersistentDataContainer().get(PROJ, PersistentDataType.STRING);
        if (t == null) return;
        Player shooter = e.getEntity().getShooter() instanceof Player p ? p : null;
        Location at = e.getHitEntity() != null ? e.getHitEntity().getLocation() : e.getEntity().getLocation();
        if (t.equals("truth_snow")) truth(shooter, at, e.getHitEntity());
        else if (t.equals("coral_net")) net(shooter, at, e.getHitEntity());
        else charge(shooter, e, at);
    }

    // ---------- Коралловая сеть ----------
    private void net(Player shooter, Location at, Entity hitEnt) {
        ConfigurationSection c = cfg("coral_net");
        double half = c.getDouble("size", 3) / 2.0;
        long until = System.currentTimeMillis() + (long) (c.getDouble("seconds", 3) * 1000);
        World w = at.getWorld();
        w.playSound(at, Sound.BLOCK_CORAL_BLOCK_BREAK, 1.5f, 0.6f);
        w.playSound(at, Sound.ENTITY_PLAYER_SPLASH, 1f, 1.2f);
        int n = 0;
        for (Player v : w.getPlayers()) {
            if (v.equals(shooter) || v.getGameMode() == GameMode.SPECTATOR || v.getGameMode() == GameMode.CREATIVE) continue;
            Location l = v.getLocation();
            boolean in = v.equals(hitEnt) || (Math.abs(l.getX() - at.getX()) <= half && Math.abs(l.getZ() - at.getZ()) <= half && Math.abs(l.getY() - at.getY()) <= 2.5);
            if (!in) continue;
            rooted.put(v.getUniqueId(), Map.entry(l.clone(), until));
            v.setVelocity(new Vector());
            v.sendActionBar(mm("<#ff7eb3>🪸 Тебя опутала коралловая сеть!"));
            n++;
        }
        if (shooter != null) shooter.sendActionBar(mm(n > 0 ? "<#ff7eb3>🪸 Опутано: <white>" + n : "<gray>🪸 Сеть никого не задела"));
        // частицы сети
        Particle.DustOptions pink = new Particle.DustOptions(Color.fromRGB(0xFF5E7E), 1.2f), orange = new Particle.DustOptions(Color.fromRGB(0xFF9A3C), 1.2f);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (System.currentTimeMillis() > until) { cancel(); return; }
                for (double d = -half; d <= half; d += 0.5) {
                    w.spawnParticle(Particle.DUST, at.clone().add(d, 0.2, -half), 1, 0, 0, 0, 0, pink);
                    w.spawnParticle(Particle.DUST, at.clone().add(d, 0.2, half), 1, 0, 0, 0, 0, pink);
                    w.spawnParticle(Particle.DUST, at.clone().add(-half, 0.2, d), 1, 0, 0, 0, 0, orange);
                    w.spawnParticle(Particle.DUST, at.clone().add(half, 0.2, d), 1, 0, 0, 0, 0, orange);
                }
                for (UUID u : rooted.keySet()) { Player v = Bukkit.getPlayer(u); if (v != null && v.getWorld().equals(w)) w.spawnParticle(Particle.DUST, v.getLocation().add(0, 1, 0), 8, 0.35, 0.6, 0.35, 0, pink); }
            }
        }.runTaskTimer(pl, 0, 4);
    }

    private boolean isRooted(Player p) {
        Map.Entry<Location, Long> r = rooted.get(p.getUniqueId());
        if (r == null) return false;
        if (r.getValue() < System.currentTimeMillis() || p.isDead() || !p.getWorld().equals(r.getKey().getWorld())) { rooted.remove(p.getUniqueId()); return false; }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        if (!e.hasChangedPosition() || !isRooted(e.getPlayer())) return;
        Location a = rooted.get(e.getPlayer().getUniqueId()).getKey(), to = e.getTo().clone();
        to.setX(a.getX()); to.setY(Math.min(to.getY(), a.getY())); to.setZ(a.getZ());
        e.setTo(to);
        e.getPlayer().setVelocity(new Vector());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTp(org.bukkit.event.player.PlayerTeleportEvent e) {
        var c = e.getCause();
        if ((c == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) && isRooted(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(mm("<red>🪸 Сеть не отпускает!"));
        }
    }

    @EventHandler
    public void onGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) { if (e.isGliding() && e.getEntity() instanceof Player p && isRooted(p)) e.setCancelled(true); }

    // ---------- Стена храма ----------
    private boolean buildWall(Player p) {
        ConfigurationSection c = cfg("temple_wall");
        int wd = c.getInt("width", 5), ht = c.getInt("height", 5), th = c.getInt("thickness", 2), dist = c.getInt("distance", 2);
        BlockFace face = p.getFacing();   // направление взгляда (стороны света)
        int fx = face.getModX(), fz = face.getModZ(), sx = -fz, sz = fx;
        Block feet = p.getLocation().getBlock();
        Map<Block, BlockData> mine = new LinkedHashMap<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int t = 0; t < th; t++) for (int i = -(wd / 2); i < wd - wd / 2; i++) for (int y = 0; y < ht; y++) {
            Block b = feet.getRelative(fx * (dist + t) + sx * i, y, fz * (dist + t) + sz * i);
            if (dome.containsKey(b) || trench.containsKey(b) || zone(b)) continue;
            if (!b.getType().isAir() && !b.isLiquid() && !b.isReplaceable() && !diggable(p, b)) continue;
            if (!pl.regions().canBuild(p, b.getLocation())) continue;
            org.bukkit.util.BoundingBox bb = org.bukkit.util.BoundingBox.of(b);
            if (b.getWorld().getNearbyEntities(bb, en -> en instanceof LivingEntity).size() > 0) continue;   // не замуровываем живых
            boolean edge = i == -(wd / 2) || i == wd - wd / 2 - 1 || y == 0 || y == ht - 1;
            Material m = edge ? Material.DARK_PRISMARINE : (i == 0 && y == ht / 2 ? Material.SEA_LANTERN : r.nextDouble() < 0.25 ? Material.PRISMARINE : Material.PRISMARINE_BRICKS);
            mine.put(b, b.getBlockData());
            b.setType(m, false);
        }
        if (mine.isEmpty()) { p.sendActionBar(mm("<red>✘ Здесь не поставить стену")); return false; }
        dome.putAll(mine);
        Location cen = feet.getRelative(fx * dist, ht / 2, fz * dist).getLocation().add(0.5, 0.5, 0.5);
        p.getWorld().playSound(cen, Sound.BLOCK_CONDUIT_ACTIVATE, 1f, 0.8f);
        p.getWorld().playSound(cen, Sound.BLOCK_STONE_PLACE, 1f, 0.6f);
        p.getWorld().spawnParticle(Particle.NAUTILUS, cen, 80, wd / 2.0, ht / 2.0, wd / 2.0, 0.3);
        p.sendActionBar(mm("<aqua>✦ Стена храма стоит " + c.getInt("seconds", 15) + " сек"));
        Bukkit.getScheduler().runTaskLater(pl, () -> restoreDome(mine), c.getInt("seconds", 15) * 20L);
        return true;
    }

    // ---------- Аура моря ----------
    private void aura(Player p) {
        ConfigurationSection c = cfg("sea_aura");
        for (PotionEffect ef : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(ef.getType());
        p.setFireTicks(0);
        int t = c.getInt("buff-seconds", 30) * 20;
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, t, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, t, 0));
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.NAUTILUS, l, 80, 0.6, 1, 0.6, 0.6);
        p.getWorld().spawnParticle(Particle.BUBBLE_POP, l, 40, 0.5, 0.8, 0.5, 0.05);
        p.getWorld().playSound(l, Sound.BLOCK_CONDUIT_ACTIVATE, 1f, 1.4f);
        p.sendActionBar(mm("<aqua>🌊 Аура моря очистила тебя <gray>(+ Сила I и Скорость I)"));
    }

    /** Аура моря - не ингредиент (сердце моря не тратится на проводник). */
    @EventHandler
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (it != null && ITEMS.contains(id(it))) { e.getInventory().setResult(null); return; }
    }

    // ---------- Зелье ярости ----------
    @EventHandler(ignoreCancelled = true)
    public void onSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        if (!"rage_potion".equals(id(e.getPotion().getItem()))) return;
        double dmg = cfg("rage_potion").getDouble("damage", 8);
        for (LivingEntity le : e.getAffectedEntities()) {
            double k = e.getIntensity(le);
            if (k <= 0) continue;
            le.damage(dmg * k, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC).build());
            le.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, le.getLocation().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
        }
    }

    // ---------- Снег правды ----------
    private void truth(Player shooter, Location at, Entity hitEnt) {
        ConfigurationSection c = cfg("truth_snow");
        double r = c.getDouble("radius", 6);
        int glow = c.getInt("glow-seconds", 15);
        World w = at.getWorld();
        w.spawnParticle(Particle.SNOWFLAKE, at, 120, r / 2, 1.2, r / 2, 0.05);
        w.spawnParticle(Particle.END_ROD, at, 40, r / 2, 1, r / 2, 0.02);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
        int n = 0;
        for (Player v : w.getPlayers()) {
            if (v.equals(shooter) || v.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = v.getLocation();
            if (Math.abs(l.getX() - at.getX()) > r || Math.abs(l.getZ() - at.getZ()) > r || Math.abs(l.getY() - at.getY()) > r) continue;
            int slow = c.getInt("slow-seconds", 10);
            if (v.equals(hitEnt) && slow > 0) v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow * 20, 0));
            if (!v.hasPotionEffect(PotionEffectType.INVISIBILITY) && !v.isInvisible()) continue;
            v.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow * 20, 0, false, false, true));
            if (slow > 0) v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow * 20, 0));
            w.spawnParticle(Particle.END_ROD, v.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
            v.sendActionBar(mm("<aqua>❄ Снег правды раскрыл тебя!"));
            n++;
        }
        if (shooter != null) shooter.sendActionBar(mm(n > 0 ? "<aqua>❄ Подсвечено невидимок: <white>" + n : "<gray>❄ Невидимок рядом нет"));
    }

    // ---------- Заряд аквамарина ----------
    /** Борозда: вынутые блоки (air), облицовка призмарином (lining), хозяин. */
    private final class Trench {
        final UUID owner; final World w; int top; final Map<Block, BlockData> air = new LinkedHashMap<>(), lining = new LinkedHashMap<>();
        Trench(UUID owner, World w) { this.owner = owner; this.w = w; }
        boolean inside(Player p) {
            Block f = p.getLocation().getBlock();
            return air.containsKey(f) || air.containsKey(f.getRelative(BlockFace.UP));
        }
    }
    private final List<Trench> trenches = new ArrayList<>();
    private final Set<Block> lined = new HashSet<>();

    /** Блоки монументов, арены Стража и воронки метеорита спец-предметы не трогают (у тех свой откат). */
    private boolean zone(Block b) {
        return pl.monuments() != null && pl.monuments().isProtected(b) || pl.bossArena() != null && pl.bossArena().isProtected(b)
                || pl.moreEvents() != null && pl.moreEvents().isMeteorBlock(b);
    }

    private boolean diggable(Player owner, Block b) {
        if (dome.containsKey(b) || trench.containsKey(b) || zone(b)) return false;
        Material t = b.getType();
        if (t.getHardness() < 0 || b.getState() instanceof TileState || t == Material.OBSIDIAN || t == Material.CRYING_OBSIDIAN || t == Material.RESPAWN_ANCHOR) return false;
        if (b.getBlockData() instanceof org.bukkit.block.data.Bisected || b.getBlockData() instanceof org.bukkit.block.data.type.Bed) return false;
        return owner == null || pl.regions().canBuild(owner, b.getLocation());
    }

    private void charge(Player shooter, ProjectileHitEvent e, Location at) {
        ConfigurationSection c = cfg("aqua_charge");
        double len = c.getDouble("length", 6), wid = c.getDouble("width", 2);
        int depth = c.getInt("depth", 4);
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION, at, 2);
        w.spawnParticle(Particle.BUBBLE_POP, at, 80, 1.5, 1, 1.5, 0.1);
        w.spawnParticle(Particle.DRIPPING_WATER, at, 60, 2, 1, 2, 0);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.6f);
        w.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.6f);
        if (pl.regions().inSpawn(at)) return;
        Block base = e.getHitBlock() != null && e.getHitBlockFace() == BlockFace.UP ? e.getHitBlock() : null;
        if (base == null) {
            Block b = at.getBlock();
            for (int i = 0; i < 6 && base == null; i++, b = b.getRelative(BlockFace.DOWN)) if (b.getType().isSolid()) base = b;
        }
        if (base == null) return;
        // полумесяц: дуга окружности радиуса R, выпуклостью по направлению броска; в середине шире и глубже, к концам сужается
        Vector v = e.getEntity().getVelocity().setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
        Vector f = v.normalize(), sd = new Vector(-f.getZ(), 0, f.getX());
        // полумесяц = круг A минус такой же круг B, сдвинутый назад на s: выпуклый вперёд, рога загнуты назад
        double r0 = len / 2 + 0.3, sh = wid + 0.4, zmax = Math.sqrt(r0 * r0 - sh * sh / 4);
        double cx = base.getX() + 0.5 - f.getX() * (r0 - sh / 2), cz = base.getZ() + 0.5 - f.getZ() * (r0 - sh / 2);
        int top = base.getY();
        Trench tr = new Trench(shooter == null ? null : shooter.getUniqueId(), w);
        tr.top = top;
        int ext = (int) Math.ceil(r0 + 2);
        for (int x = (int) Math.floor(cx) - ext; x <= (int) Math.floor(cx) + ext; x++) for (int z = (int) Math.floor(cz) - ext; z <= (int) Math.floor(cz) + ext; z++) {
            double qx = x + 0.5 - cx, qz = z + 0.5 - cz;
            double fa = qx * f.getX() + qz * f.getZ(), sa = qx * sd.getX() + qz * sd.getZ();
            if (fa * fa + sa * sa > r0 * r0 || (fa + sh) * (fa + sh) + sa * sa <= r0 * r0) continue;
            double t = Math.min(1, Math.abs(sa) / zmax);
            int dep = Math.max(1, (int) Math.round(depth * (1 - 0.6 * t * t)));
            // поверхность колонки
            Block col = null;
            for (int y = top + 2; y >= top - 3; y--) { Block b = w.getBlockAt(x, y, z); if (b.getType().isSolid()) { col = b; break; } }
            if (col == null) continue;
            int ct = col.getY(), cb = Math.max(w.getMinHeight() + 1, ct - dep + 1);
            for (int y = ct + 2; y >= cb; y--) {   // +2 - сносим траву/цветы над бороздой
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isAir()) continue;
                if (y > ct && b.getType().isSolid()) continue;
                if (!diggable(shooter, b)) continue;
                tr.air.put(b, b.getBlockData());
            }
        }
        for (Map.Entry<Block, BlockData> m : tr.air.entrySet()) {
            w.spawnParticle(Particle.BLOCK, m.getKey().getLocation().add(0.5, 0.5, 0.5), 4, 0.3, 0.3, 0.3, m.getValue());
            m.getKey().setType(Material.AIR, false);
        }
        trench.putAll(tr.air);
        // облицовка призмарином: дно и стенки
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BlockFace[] around = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (Block b : tr.air.keySet()) for (BlockFace bf : around) {
            Block n = b.getRelative(bf);
            if (tr.air.containsKey(n) || tr.lining.containsKey(n) || !n.getType().isSolid() || !diggable(shooter, n)) continue;
            Material m;
            if (bf == BlockFace.DOWN && r.nextDouble() < c.getDouble("lantern-chance", 0.15)) m = Material.SEA_LANTERN;
            else { double x = r.nextDouble(); m = x < 0.5 ? Material.PRISMARINE : x < 0.8 ? Material.PRISMARINE_BRICKS : Material.DARK_PRISMARINE; }
            tr.lining.put(n, n.getBlockData());
            n.setType(m, false);
        }
        trench.putAll(tr.lining);
        lined.addAll(tr.lining.keySet());
        trenches.add(tr);
        // засасывание
        Location target = base.getLocation().add(0.5, 0, 0.5);
        target.setY(top - depth + 1);
        Block tb = target.getBlock();
        if (!tb.getType().isAir()) target.setY(top + 1);
        double pr = c.getDouble("pull-radius", 1.0);
        for (Player p : w.getPlayers()) {
            if (p.equals(shooter) || p.getGameMode() == GameMode.SPECTATOR) continue;
            boolean hit = p.equals(e.getHitEntity());
            if (!hit && p.getLocation().distance(at) > pr + 0.5 && p.getEyeLocation().distance(at) > pr + 0.5) continue;
            pull(p, target);
        }
        // слепота и усталость врагам внутри
        int restore = c.getInt("restore-seconds", 30);
        int fat = c.getInt("fatigue-level", 2) - 1;
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!trenches.contains(tr)) { cancel(); return; }
                for (Player p : w.getPlayers()) {
                    if (p.getUniqueId().equals(tr.owner) || p.getGameMode() == GameMode.SPECTATOR || !tr.inside(p)) continue;
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, Math.max(0, fat), false, false, true));
                    if (ticks % 40 == 0) p.sendActionBar(mm("<dark_aqua>🌊 Ты в борозде аквамарина: <gray>слепота и усталость"));
                }
                ticks += 10;
                if (restore > 0 && ticks >= restore * 20) { cancel(); restoreTrench(tr); }
            }
        }.runTaskTimer(pl, 5, 10);
    }

    private void pull(Player p, Location target) {
        p.sendActionBar(mm("<aqua>🌊 Тебя затянуло в борозду!"));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1f, 0.8f);
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                Vector d = target.clone().add(0, 0.1, 0).toVector().subtract(p.getLocation().toVector());
                if (d.length() < 0.7 || ++t > 12) {
                    if (d.length() > 1.6) { Location l = target.clone(); l.setYaw(p.getLocation().getYaw()); l.setPitch(p.getLocation().getPitch()); p.teleport(l); }
                    cancel(); return;
                }
                p.setVelocity(d.normalize().multiply(Math.min(1.2, d.length() * 0.5 + 0.35)));
                p.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, p.getLocation(), 6, 0.3, 0.5, 0.3, 0);
            }
        }.runTaskTimer(pl, 1, 1);
    }

    private void restoreTrench(Trench tr) {
        if (!trenches.remove(tr)) return;
        int top = tr.top;
        for (Player p : tr.w.getPlayers()) if (tr.inside(p)) {
            Location l = p.getLocation().clone();
            Block b = l.getBlock();
            int y = top + 1;
            while (y < top + 6 && b.getWorld().getBlockAt(b.getX(), y, b.getZ()).getType().isSolid()) y++;
            l.setY(y + 0.05); p.teleport(l);
        }
        for (Map.Entry<Block, BlockData> m : tr.lining.entrySet()) { trench.remove(m.getKey()); lined.remove(m.getKey()); m.getKey().setBlockData(m.getValue(), false); }
        for (Map.Entry<Block, BlockData> m : tr.air.entrySet()) {
            Block b = m.getKey();
            trench.remove(b);
            if (b.getType().isAir() || b.isLiquid()) b.setBlockData(m.getValue(), false);
        }
        if (!tr.air.isEmpty()) tr.w.playSound(tr.air.keySet().iterator().next().getLocation(), Sound.BLOCK_ROOTED_DIRT_PLACE, 1f, 0.8f);
    }

    // ---------- Золотой купол ----------
    /** Клетка 5x5x5: внутри пусто 3x3x3, пол, стены и крыша из стекла, на макушке - свет. */
    private boolean buildDome(Player p) {
        ConfigurationSection c = cfg("golden_dome");
        Block feet = p.getLocation().getBlock();
        if (!pl.regions().canBuild(p, feet.getLocation())) { p.sendActionBar(mm("<red>✘ Здесь нельзя ставить купол")); return false; }
        Map<Block, BlockData> mine = new LinkedHashMap<>();
        Material glass = Material.matchMaterial(c.getString("glass", "YELLOW_STAINED_GLASS"));
        if (glass == null || !glass.isBlock()) glass = Material.YELLOW_STAINED_GLASS;
        int in = Math.max(1, c.getInt("inner-size", 3)) / 2;            // 3 -> 1
        int o = in + 1, h = Math.max(2, c.getInt("inner-height", 3));   // стены на расстоянии o, внутри h блоков высоты
        Material light = LIGHTS[ThreadLocalRandom.current().nextInt(LIGHTS.length)];
        for (int dx = -o; dx <= o; dx++) for (int dz = -o; dz <= o; dz++) for (int dy = -1; dy <= h; dy++) {
            boolean shell = dy == -1 || dy == h || Math.abs(dx) == o || Math.abs(dz) == o;
            Block b = feet.getRelative(dx, dy, dz);
            if (dome.containsKey(b) || trench.containsKey(b) || zone(b)) continue;
            if (!shell) {   // внутри - пусто (убираем траву/снег/воду, твёрдое не трогаем)
                if (!b.getType().isAir() && (b.isReplaceable() || b.isLiquid()) && pl.regions().canBuild(p, b.getLocation())) { mine.put(b, b.getBlockData()); b.setType(Material.AIR, false); }
                continue;
            }
            if (!b.getType().isAir() && !b.isLiquid() && !b.isReplaceable() && !diggable(p, b)) continue;
            if (!pl.regions().canBuild(p, b.getLocation())) continue;
            Material m = (dx == 0 && dz == 0 && dy == h) ? light : glass;
            mine.put(b, b.getBlockData());
            BlockData nd = m.createBlockData();
            if (nd instanceof org.bukkit.block.data.Lightable lt) lt.setLit(true);
            b.setBlockData(nd, false);
        }
        for (Block b : mine.keySet()) if (!b.getType().isAir()) dome.put(b, mine.get(b));
        Map<Block, BlockData> placed = new LinkedHashMap<>(mine);
        Location cen = feet.getLocation().add(0.5, 1, 0.5);
        p.teleport(new Location(p.getWorld(), cen.getX(), feet.getY(), cen.getZ(), p.getLocation().getYaw(), p.getLocation().getPitch()));
        p.getWorld().playSound(cen, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        p.getWorld().spawnParticle(Particle.WAX_ON, cen, 80, 1.5, 1.5, 1.5, 0.5);
        p.sendActionBar(mm("<gold>✦ Золотой купол защищает тебя " + c.getInt("seconds", 15) + " сек"));
        Bukkit.getScheduler().runTaskLater(pl, () -> restoreDome(placed), c.getInt("seconds", 15) * 20L);
        return true;
    }

    private void restoreDome(Map<Block, BlockData> mine) {
        for (Map.Entry<Block, BlockData> m : mine.entrySet()) {
            Block b = m.getKey();
            if (dome.remove(b) != null || b.getType().isAir()) b.setBlockData(m.getValue(), false);   // стекло или вычищенная трава
        }
        if (!mine.isEmpty()) {
            Location l = mine.keySet().iterator().next().getLocation();
            l.getWorld().playSound(l, Sound.BLOCK_GLASS_BREAK, 1f, 1.2f);
        }
    }

    // ---------- защита купола ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) { if (dome.containsKey(e.getBlock()) || lined.contains(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(BlockDamageEvent e) { if (dome.containsKey(e.getBlock()) || lined.contains(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> dome.containsKey(b) || trench.containsKey(b)); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> dome.containsKey(b) || trench.containsKey(b)); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPiston(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(b -> dome.containsKey(b) || lined.contains(b))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPiston(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(b -> dome.containsKey(b) || lined.contains(b))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChange(EntityChangeBlockEvent e) { if (dome.containsKey(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(BlockBurnEvent e) { if (dome.containsKey(e.getBlock())) e.setCancelled(true); }

    public void shutdown() {
        for (Map.Entry<Block, BlockData> m : dome.entrySet()) m.getKey().setBlockData(m.getValue(), false);
        for (Trench t : new ArrayList<>(trenches)) restoreTrench(t);
        dome.clear(); trench.clear(); lined.clear();
    }
}
