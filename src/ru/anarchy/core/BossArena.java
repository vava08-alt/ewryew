package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/**
 * ☠ Босс «Страж Бездны» - отдельный ивент. В случайном месте карты RTP строится круглая арена, за её край Страж не выходит.
 * Кто зашёл на арену - не уйдёт, пока Страж жив (заходить могут все). HP зависит от онлайна сервера.
 * После победы миньоны исчезают, на месте смерти - сундук с «неизвестным» лутом (как у аирдропа и метеорита).
 * Когда сундук разграблен или истёк - арена исчезает, рельеф возвращается как был.
 */
public final class BossArena implements Listener {
    private static final class ChestHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final AnarchyCore pl;
    private final NamespacedKey BOSS, MINION;
    private World world;
    private Location center;
    private int radius, floorY, height;
    private Events.Tier tier = Events.Tier.RARE;
    private LivingEntity boss;
    private double hpMax, hpScale = 1;
    private BossBar bar;
    private long start, despawnAt;
    private boolean building, fighting, bypassTp, cancelBuild;
    private int ticks;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> locked = new HashSet<>();
    private final Set<UUID> minions = new HashSet<>();
    private final Set<UUID> shown = new HashSet<>();
    private final Map<UUID, Long> warnCd = new HashMap<>();
    /** Изменённые блоки арены с оригиналами (порядок важен для отката). */
    private final LinkedHashMap<Block, BlockData> changed = new LinkedHashMap<>();
    private Block chest; private Inventory chestInv; private TextDisplay chestHolo;
    private long chestUnlock, chestRemove; private boolean chestFilled;

    public BossArena(AnarchyCore pl) {
        this.pl = pl;
        BOSS = new NamespacedKey(pl, "boss");
        MINION = new NamespacedKey(pl, "boss_minion");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20, 5);
        Bukkit.getScheduler().runTask(pl, () -> restoreJournal(0));
    }

    private String cs(String k, String d) { return pl.getConfig().getString("events.boss." + k, d); }
    private int ci(String k, int d) { return pl.getConfig().getInt("events.boss." + k, d); }
    private double cd(String k, double d) { return pl.getConfig().getDouble("events.boss." + k, d); }
    private String pre() { return pl.getConfig().getString("prefix", ""); }
    private String bossName() { return cs("name", "<gradient:#7000ff:#ff0040><bold>Страж Бездны</bold></gradient>"); }
    private File journal() { return new File(pl.getDataFolder(), "boss-arena-restore.yml"); }
    private static String xyz(Location l) { return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    public boolean running() { return center != null || building; }
    public boolean isLocked(Player p) { return fighting && locked.contains(p.getUniqueId()); }

    /** HP Стража от онлайна: меньше 50 - 500, меньше 100 - 750, дальше 1000 за каждые 100 игроков. */
    public double healthFor(int online) {
        if (online < ci("health-online-low", 50)) return cd("health-low", 500);
        if (online < ci("health-online-high", 100)) return cd("health-mid", 750);
        return cd("health-per-100", 1000) * Math.max(1, online / 100);
    }

    public String status() {
        if (fighting) return "<dark_purple>☠ Босс";
        if (chest != null) return "<dark_purple>☠ Сундук Стража";
        return "";
    }

    public List<MoreEvents.Active> active() {
        if (center == null) return List.of();
        String n = MoreEvents.name("boss") + " <dark_gray>[" + tier.tag() + "<dark_gray>]";
        long now = System.currentTimeMillis();
        if (fighting) return List.of(new MoreEvents.Active("boss", n, center, start, despawnAt, "уйдёт через", BossBar.Color.PURPLE));
        if (chest != null) return List.of(chestUnlock > now
                ? new MoreEvents.Active("boss", n, chest.getLocation(), chestUnlock - 3000, chestUnlock, "сундук откроется через", tier.bar)
                : new MoreEvents.Active("boss", n, chest.getLocation(), chestUnlock, chestRemove, "сундук исчезнет через", tier.bar));
        return List.of();
    }

    // ===================== запуск: место и арена =====================
    public boolean start() {
        if (running()) return false;
        building = true;
        cancelBuild = false;
        tier = pl.events().rollTier("boss");
        find(8, pl.events().forced != null);
        return true;
    }

    private void find(int tries, boolean forced) {
        pl.events().randomSurface("events.boss", loc -> {
            if (loc == null) { building = false; pl.getLogger().warning("Босс: не нашлось места для арены"); return; }
            int r = Math.max(8, Math.min(24, ci("arena-radius", 14)));
            World w = loc.getWorld();
            List<CompletableFuture<Chunk>> wait = new ArrayList<>();
            for (int x = (loc.getBlockX() - r - 3) >> 4; x <= (loc.getBlockX() + r + 3) >> 4; x++)
                for (int z = (loc.getBlockZ() - r - 3) >> 4; z <= (loc.getBlockZ() + r + 3) >> 4; z++) wait.add(w.getChunkAtAsync(x, z));
            CompletableFuture.allOf(wait.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(pl, () -> {
                try {
                    if (cancelBuild) { cancelBuild = false; building = false; return; }
                    if (!forced && !suitable(loc, r)) {
                        if (tries > 0) find(tries - 1, false);
                        else { building = false; pl.getLogger().warning("Босс: не нашлось ровного места для арены"); }
                        return;
                    }
                    begin(loc, r);
                } catch (Exception e) { building = false; pl.getLogger().warning("Босс: ошибка постройки арены - " + e); e.printStackTrace(); }
            }));
        });
    }

    /** Без приватов в круге, не на воде и без огромного перепада высот. */
    private boolean suitable(Location loc, int r) {
        World w = loc.getWorld();
        Block base = w.getBlockAt(loc.getBlockX(), Util.groundY(w, loc.getBlockX(), loc.getBlockZ()), loc.getBlockZ());
        if (base.isLiquid()) return false;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0, n = 0;
        for (double rr : new double[]{r, r * 0.55}) for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            int x = loc.getBlockX() + (int) Math.round(Math.cos(a) * rr), z = loc.getBlockZ() + (int) Math.round(Math.sin(a) * rr);
            Block top = w.getBlockAt(x, Util.groundY(w, x, z), z);
            if (pl.regions().at(top.getLocation()) != null) return false;
            if (top.isLiquid()) wet++;
            min = Math.min(min, top.getY()); max = Math.max(max, top.getY()); n++;
        }
        return wet * 4 <= n && max - min <= ci("max-height-diff", 14);
    }

    private void begin(Location loc, int r) {
        world = loc.getWorld();
        radius = r;
        height = Math.max(5, ci("arena-height", 9));
        floorY = Util.groundY(world, loc.getBlockX(), loc.getBlockZ());
        center = new Location(world, loc.getBlockX() + 0.5, floorY + 1, loc.getBlockZ() + 0.5);
        pl.events().hold("boss", center, r + 4);
        buildArena();
        saveJournal();
        spawnBoss();
        building = false;
        pl.events().announce("<gradient:#7000ff:#ff0040><bold>БОСС ПРОБУДИЛСЯ</bold></gradient>",
                tier.tag("Страж Бездны") + " <white>- <yellow>" + xyz(center) + "</yellow> <gray>(" + world.getName() + ", <red>❤ " + (int) hpMax + "<gray>)");
        Bukkit.broadcast(mm(pre() + "<gray>Страж ждёт на своей арене. <red>Кто зашёл - не выйдет, пока Страж жив.</red> Победа - сундук "
                + tier.tag() + " <gray>с лутом, лучшему бойцу - <gold>" + ci("reward-coins", 2000) + " " + pl.cur() + " <light_purple>+" + ci("reward-ari", 10) + " " + pl.ariSym()));
    }

    private boolean touchable(Block b) {
        Material m = b.getType();
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME || m == Material.END_PORTAL || m == Material.NETHER_PORTAL
                || m == Material.REINFORCED_DEEPSLATE || m == Material.SPAWNER || m == Material.TRIAL_SPAWNER || m == Material.VAULT) return false;
        if (b.getState(false) instanceof org.bukkit.block.TileState) return false;
        if (pl.monuments() != null && pl.monuments().isProtected(b)) return false;
        return pl.regions().at(b.getLocation()) == null;
    }

    private void put(Block b, BlockData d) { changed.putIfAbsent(b, b.getBlockData().clone()); b.setBlockData(d, false); }

    private Material floorMat(int dx, int dz, double d, ThreadLocalRandom r) {
        if (d >= radius - 0.5) return Math.floorMod(dx + dz, 2) == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN;
        if (d < 1.6) return Material.CRYING_OBSIDIAN;
        if (d < 2.7) return Material.GILDED_BLACKSTONE;
        double a = Math.atan2(dz, dx);
        if (Math.abs(Math.sin(a * 4)) < 0.55 / Math.max(1, d)) return Material.POLISHED_BLACKSTONE;
        if (Math.abs(d - radius * 0.6) < 0.5) return Material.POLISHED_DEEPSLATE;
        if (r.nextInt(10) == 0) return Material.CRACKED_DEEPSLATE_TILES;
        return Math.floorMod(dx, 2) == Math.floorMod(dz, 2) ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    /** Круглый пол на уровне рельефа, опора под ним, пустота над ним, по краю - колонны с огнём душ. */
    private void buildArena() {
        changed.clear();
        int cx = center.getBlockX(), cz = center.getBlockZ();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > radius + 0.5) continue;
            int x = cx + dx, z = cz + dz;
            Block f = world.getBlockAt(x, floorY, z);
            if (touchable(f)) put(f, floorMat(dx, dz, d, rnd).createBlockData());
            for (int y = floorY - 1; y >= Math.max(world.getMinHeight() + 1, floorY - 8); y--) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType().isSolid() || !touchable(b)) break;
                put(b, (y == floorY - 1 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.BLACKSTONE).createBlockData());
            }
            for (int y = floorY + 1; y <= floorY + height; y++) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType().isAir() || !touchable(b)) continue;
                put(b, Material.AIR.createBlockData());
            }
            // песок/гравий над вырезом не должен осыпаться внутрь
            Block cap = world.getBlockAt(x, floorY + height + 1, z);
            if (cap.getType().hasGravity() && touchable(cap)) put(cap, Material.BLACKSTONE.createBlockData());
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = cx + (int) Math.round(Math.cos(a) * (radius - 1)), z = cz + (int) Math.round(Math.sin(a) * (radius - 1));
            Block b1 = world.getBlockAt(x, floorY + 1, z), b2 = world.getBlockAt(x, floorY + 2, z), b3 = world.getBlockAt(x, floorY + 3, z);
            if (!touchable(b1) || !touchable(b2) || !touchable(b3)) continue;
            put(b1, Material.CHISELED_POLISHED_BLACKSTONE.createBlockData());
            put(b2, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
            put(b3, Material.SOUL_LANTERN.createBlockData());
            int lx = cx + (int) Math.round(Math.cos(a) * radius * 0.55), lz = cz + (int) Math.round(Math.sin(a) * radius * 0.55);
            Block light = world.getBlockAt(lx, floorY, lz);
            if (touchable(light)) put(light, Material.SHROOMLIGHT.createBlockData());
        }
    }

    private void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    private void spawnBoss() {
        hpMax = healthFor(Bukkit.getOnlinePlayers().size());
        double attr = Math.min(hpMax, 1000);
        hpScale = hpMax / attr;
        Component name = mm(bossName());
        boss = world.spawn(center.clone(), WitherSkeleton.class, b -> {
            b.customName(name);
            b.setCustomNameVisible(true);
            b.setRemoveWhenFarAway(false);
            b.setPersistent(false);
            set(b, Attribute.MAX_HEALTH, attr);
            b.setHealth(attr);
            set(b, Attribute.SCALE, cd("scale", 1.6));
            set(b, Attribute.ATTACK_DAMAGE, cd("damage", 14));
            set(b, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(b, Attribute.FOLLOW_RANGE, radius * 2 + 8);
            b.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            b.getEquipment().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            b.getEquipment().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            for (EquipmentSlot sl : EquipmentSlot.values()) try { b.getEquipment().setDropChance(sl, 0); } catch (Exception ignored) {}
            b.getPersistentDataContainer().set(BOSS, PersistentDataType.BYTE, (byte) 1);
            b.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0));
        });
        damage.clear(); locked.clear(); minions.clear();
        start = System.currentTimeMillis();
        despawnAt = start + Math.max(1, ci("minutes", 20)) * 60_000L;
        fighting = true;
        bar = BossBar.bossBar(name, 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        world.strikeLightningEffect(center);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 200, radius * 0.4, 1, radius * 0.4, 0.05);
    }

    // ===================== геометрия арены =====================
    private double dist2(Location l) {
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }
    /** Внутри цилиндра арены (для «захвата» игрока). */
    private boolean inside(Location l) {
        if (center == null || l == null || !world.equals(l.getWorld())) return false;
        return dist2(l) <= radius * radius && l.getY() >= floorY - 2 && l.getY() <= floorY + height + 3;
    }
    /** Где может находиться запертый игрок. */
    private boolean allowed(Location l) {
        if (center == null || l == null || !world.equals(l.getWorld())) return false;
        double r = radius + 0.3;
        return dist2(l) <= r * r && l.getY() >= floorY - 6 && l.getY() <= floorY + height + 8;
    }
    private boolean inVolume(Block b) {
        if (center == null || !b.getWorld().equals(world)) return false;
        if (changed.containsKey(b)) return true;
        double dx = b.getX() + 0.5 - center.getX(), dz = b.getZ() + 0.5 - center.getZ();
        double r = radius + 0.5;
        return dx * dx + dz * dz <= r * r && b.getY() >= floorY - 9 && b.getY() <= floorY + height + 1;
    }

    private void warn(Player p, String m) {
        long now = System.currentTimeMillis();
        if (now - warnCd.getOrDefault(p.getUniqueId(), 0L) < 2000) return;
        warnCd.put(p.getUniqueId(), now);
        p.sendActionBar(mm(m));
    }

    private Player shooter(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        return null;
    }

    /** Вылетел за край - вернуть на арену. */
    private void pullBack(Player p) {
        Vector v = p.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
        Location to = center.clone().add(v.normalize().multiply(Math.max(1, radius - 2)));
        to.setY(floorY + 1);
        to.setYaw(p.getLocation().getYaw()); to.setPitch(p.getLocation().getPitch());
        if (p.isInsideVehicle()) p.leaveVehicle();
        bypassTp = true;
        try { p.teleport(to); } finally { bypassTp = false; }
        p.setVelocity(new Vector());
        p.setFallDistance(0);
        warn(p, "<red>☠ С арены Стража не уйти, пока он жив!");
    }

    // ===================== бой =====================
    private void lockTick() {
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE || p.isDead()) continue;
            UUID u = p.getUniqueId();
            if (!locked.contains(u)) {
                if (!inside(p.getLocation())) continue;
                locked.add(u);
                p.showTitle(Title.title(mm("<gradient:#7000ff:#ff0040><bold>☠ Арена Стража</bold></gradient>"), mm("<gray>Выхода нет, пока Страж жив"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
                p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7f, 0.8f);
            } else if (!allowed(p.getLocation())) pullBack(p);
        }
    }

    private void confineBoss() {
        if (boss == null || !boss.isValid()) return;
        Location l = boss.getLocation();
        double r = radius - 1.5;
        if (dist2(l) > r * r || l.getY() < floorY - 1 || l.getY() > floorY + height + 4) {
            Location to = center.clone(); to.setYaw(l.getYaw());
            boss.teleport(to);
            world.spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.05);
        }
        if (boss instanceof Mob m && !(m.getTarget() instanceof Player t && locked.contains(t.getUniqueId()) && !t.isDead())) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (UUID u : locked) { Player p = Bukkit.getPlayer(u); if (p == null || p.isDead() || !p.getWorld().equals(world) || p.getGameMode() != GameMode.SURVIVAL) continue; double d = p.getLocation().distanceSquared(l); if (d < bd) { bd = d; best = p; } }
            if (best != null) m.setTarget(best);
        }
    }

    private List<Player> fighters() {
        List<Player> out = new ArrayList<>();
        for (UUID u : locked) { Player p = Bukkit.getPlayer(u); if (p != null && !p.isDead() && p.getGameMode() == GameMode.SURVIVAL && allowed(p.getLocation())) out.add(p); }
        return out;
    }

    private void skill() {
        if (boss == null || !boss.isValid()) return;
        List<Player> near = fighters();
        if (near.isEmpty()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location bl = boss.getLocation();
        switch (r.nextInt(3)) {
            case 0 -> {   // удар бездны: отбрасывание
                world.spawnParticle(Particle.EXPLOSION_EMITTER, bl, 1);
                world.playSound(bl, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.8f);
                for (Player p : near) if (p.getLocation().distanceSquared(bl) < 64) {
                    Vector v = p.getLocation().toVector().subtract(bl.toVector()).setY(0);
                    if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
                    p.setVelocity(v.normalize().multiply(1.3).setY(0.7));
                    p.damage(cd("blast-damage", 6), boss);
                }
            }
            case 1 -> {   // призыв вексов (не больше minions-max живых)
                minions.removeIf(u -> { Entity e = Bukkit.getEntity(u); return e == null || !e.isValid(); });
                int cap = ci("minions-max", 8);
                if (minions.size() >= cap) { soulStrike(near); return; }
                world.playSound(bl, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.7f);
                for (int i = 0; i < Math.min(3, cap - minions.size()); i++) {
                    Vex v = world.spawn(bl.clone().add(r.nextInt(-3, 4), 1, r.nextInt(-3, 4)), Vex.class, x -> {
                        x.setLimitedLifetime(true);
                        x.setLimitedLifetimeTicks(ci("minion-life-seconds", 25) * 20);
                        x.setPersistent(false);
                        x.getPersistentDataContainer().set(MINION, PersistentDataType.BYTE, (byte) 1);
                    });
                    minions.add(v.getUniqueId());
                }
            }
            default -> soulStrike(near);
        }
    }

    /** Молнии душ: по двум случайным бойцам - урон и замедление. */
    private void soulStrike(List<Player> near) {
        List<Player> t = new ArrayList<>(near);
        Collections.shuffle(t);
        for (Player p : t.subList(0, Math.min(2, t.size()))) {
            world.strikeLightningEffect(p.getLocation());
            world.spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.05);
            p.damage(cd("strike-damage", 4), boss);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        }
    }

    private void removeMinions() {
        for (UUID u : minions) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        minions.clear();
        if (world != null && center != null)
            for (Entity e : world.getNearbyEntities(center, radius + 40, 40, radius + 40))
                if (e.getPersistentDataContainer().has(MINION, PersistentDataType.BYTE)) e.remove();
    }

    private void borderFx() {
        boolean near = false;
        for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(center) < (radius + 40) * (radius + 40)) { near = true; break; }
        if (!near) return;
        Particle.DustOptions d1 = new Particle.DustOptions(Color.fromRGB(0x8A2CFF), 1.4f), d2 = new Particle.DustOptions(Color.fromRGB(0xFF0040), 1.2f);
        int pts = Math.max(32, radius * 5);
        double off = ticks * 0.05;
        for (int i = 0; i < pts; i++) {
            double a = off + i * Math.PI * 2 / pts;
            double x = center.getX() + Math.cos(a) * (radius + 0.3), z = center.getZ() + Math.sin(a) * (radius + 0.3);
            world.spawnParticle(Particle.DUST, x, floorY + 1.2, z, 1, 0, 0, 0, 0, i % 2 == 0 ? d1 : d2);
            if (i % 3 == 0) world.spawnParticle(Particle.DUST, x, floorY + 2.6, z, 1, 0, 0, 0, 0, d1);
        }
    }

    private void barTick(long now) {
        if (bar == null || boss == null) return;
        double hp = Math.max(0, boss.getHealth() * hpScale);
        bar.progress((float) Math.max(0, Math.min(1, hp / Math.max(1, hpMax))));
        bar.name(mm(bossName() + " <dark_gray>[" + tier.tag() + "<dark_gray>] <red>❤ " + (int) Math.ceil(hp) + "<gray>/" + (int) hpMax
                + " <dark_gray>| <gray>на арене: <white>" + fighters().size() + " <dark_gray>| <gray>уйдёт через <yellow>" + MoreEvents.mmss(despawnAt - now)));
        double vis = radius + 30;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = p.getWorld().equals(world) && p.getLocation().distanceSquared(center) <= vis * vis;
            if (in && shown.add(p.getUniqueId())) p.showBossBar(bar);
            else if (!in && shown.remove(p.getUniqueId())) p.hideBossBar(bar);
        }
    }

    private void hideBar() {
        if (bar != null) for (UUID u : shown) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(bar); }
        shown.clear();
        bar = null;
    }

    private void victory(Location at) {
        fighting = false;
        boss = null;
        hideBar();
        removeMinions();
        for (UUID u : locked) { Player p = Bukkit.getPlayer(u); if (p != null) p.showTitle(Title.title(mm("<gradient:#ffb000:#ff0040><bold>Страж повержен!</bold></gradient>"), mm("<gray>Арена открыта - забирай лут из сундука"))); }
        locked.clear();
        UUID top = damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (top != null) {
            PlayerData d = pl.store().get(top);
            if (d != null) {
                long coins = ci("reward-coins", 2000), ari = ci("reward-ari", 10);
                d.balance += coins; d.ari += ari;
                Bukkit.broadcast(mm(pre() + "<dark_purple>☠ Страж Бездны повержен! Больше всех урона: </dark_purple>").append(pl.displayName(d))
                        .append(mm(" <gold>+" + coins + " " + pl.cur() + " <light_purple>+" + ari + " " + pl.ariSym())));
            }
        }
        Bukkit.getScheduler().runTask(pl, () -> spawnChest(at));
    }

    private void timeout() {
        Bukkit.broadcast(mm(pre() + "<gray>☠ Страж Бездны никто не одолел - он ушёл обратно в Бездну."));
        finish();
    }

    // ===================== сундук =====================
    private void spawnChest(Location at) {
        if (center == null) return;
        Block b = world.getBlockAt(at.getBlockX(), floorY + 1, at.getBlockZ());
        double r = radius - 1.5;
        if (dist2(b.getLocation().add(0.5, 0, 0.5)) > r * r || !b.getType().isAir()) b = world.getBlockAt(center.getBlockX(), floorY + 1, center.getBlockZ());
        put(b, Material.CHEST.createBlockData());
        chest = b;
        saveJournal();
        ChestHolder h = new ChestHolder();
        chestInv = Bukkit.createInventory(h, 54, mm(tier.tag("сундук Стража")));
        h.inv = chestInv;
        chestFilled = false;
        long now = System.currentTimeMillis();
        chestUnlock = now + Math.max(0, ci("chest-unlock-seconds", 3)) * 1000L;
        chestRemove = chestUnlock + Math.max(1, ci("chest-minutes", 5)) * 60_000L;
        chestHolo = world.spawn(b.getLocation().add(0.5, 1.7, 0.5), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER); d.setPersistent(false); d.setAlignment(TextDisplay.TextAlignment.CENTER);
        });
        holoText(now);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, b.getLocation().add(0.5, 0.6, 0.5), 60, 0.6, 0.6, 0.6, 0.05);
        world.playSound(b.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.2f, 0.6f);
        Bukkit.broadcast(mm(pre() + "<dark_purple>☠ На месте гибели Стража появился </dark_purple>" + tier.tag("сундук")
                + " <gray>(" + b.getX() + " " + b.getY() + " " + b.getZ() + ") <dark_gray>- лут выдаётся постепенно, как у аирдропа"));
    }

    private void holoText(long now) {
        if (chestHolo == null || !chestHolo.isValid()) return;
        Mystery.Feeder f = pl.mystery().feeder("boss");
        String s = tier.tag("сундук Стража") + "\n" + (now < chestUnlock ? "<white>Откроется через <yellow>" + Math.max(1, (chestUnlock - now + 999) / 1000) + "с"
                : "<green><bold>ОТКРЫТ" + (f != null && f.left() > 0 ? "\n<gray>Ещё предметов: <white>" + f.left() + " <dark_gray>(+1 через " + (f.nextIn() / 1000 + 1) + "с)" : ""));
        chestHolo.text(mm(s));
    }

    private void unlockChest() {
        if (chest == null || chestFilled) return;
        chestFilled = true;
        pl.mystery().start("boss", chest, chestInv, pl.events().eventLoot(tier, "boss"), cd("drip-seconds", 0.5), tier);
        Location l = chest.getLocation().add(0.5, 1.2, 0.5);
        MoreEvents.firework(pl, l, tier.color);
        world.playSound(l, Sound.BLOCK_CHEST_OPEN, 1.5f, 0.8f);
        world.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1.2f, 0.7f);
    }

    private boolean chestEmpty() {
        Mystery.Feeder f = pl.mystery().feeder("boss");
        if (f != null && f.left() > 0) return false;
        return chestInv == null || !pl.mystery().hasLoot(chestInv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChestClick(PlayerInteractEvent e) {
        if (chest == null || e.getClickedBlock() == null || !e.getClickedBlock().equals(chest) || !e.getAction().isRightClick()) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        long left = chestUnlock - System.currentTimeMillis();
        if (left > 0 || !chestFilled) { pl.msg(p, "<red>Сундук Стража откроется через " + (Math.max(0, left) / 1000 + 1) + " сек"); return; }
        p.openInventory(chestInv);
        if (chest.getState() instanceof org.bukkit.block.Chest c) c.open();
        p.playSound(chest.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
    }

    @EventHandler
    public void onChestClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof ChestHolder)) return;
        Bukkit.getScheduler().runTask(pl, () -> {
            if (chest == null || chestInv == null) return;
            if (chestInv.getViewers().isEmpty() && chest.getState() instanceof org.bukkit.block.Chest c) c.close();
            if (!chestFilled || !chestEmpty() || !chestInv.getViewers().isEmpty()) return;
            world.spawnParticle(Particle.CLOUD, chest.getLocation().add(0.5, 0.5, 0.5), 30, 0.4, 0.4, 0.4, 0.05);
            Bukkit.broadcast(mm(pre() + "<gray>☠ Сундук Стража разграблен - арена рассыпается."));
            finish();
        });
    }

    // ===================== тик и завершение =====================
    private void tick() {
        if (center == null) return;
        ticks++;
        long now = System.currentTimeMillis();
        try {
            if (fighting) {
                if (now > despawnAt) { timeout(); return; }
                if (boss == null || !boss.isValid()) { spawnBossAgain(); return; }   // смерть уже обработана в onDeath (fighting = false)
                confineBoss();
                lockTick();
                if (ticks % 4 == 0) barTick(now);
                if (ticks % 2 == 0) borderFx();
                if (ticks % 32 == 0) skill();
            } else if (chest != null) {
                if (chest.getType() != Material.CHEST || now > chestRemove) { finish(); return; }
                if (!chestFilled && now >= chestUnlock) unlockChest();
                if (ticks % 4 == 0) holoText(now);
                world.spawnParticle(tier.particle, chest.getLocation().add(0.5, 1, 0.5), 4, 0.2, 1.2, 0.2, 0.01);
            }
        } catch (Exception ex) { pl.getLogger().warning("BossArena: " + ex); ex.printStackTrace(); }
    }

    /** Страж пропал (выгрузка/чужой плагин) - вернуть с тем же процентом здоровья. */
    private void spawnBossAgain() {
        double frac = 1;
        if (boss != null) { var a = boss.getAttribute(Attribute.MAX_HEALTH); if (a != null && a.getValue() > 0) frac = Math.max(0.05, boss.getHealth() / a.getValue()); }
        double keepMax = hpMax, keepScale = hpScale;
        long keepStart = start, keepEnd = despawnAt;
        Set<UUID> keepLocked = new HashSet<>(locked);
        Map<UUID, Double> keepDmg = new HashMap<>(damage);
        hideBar();
        spawnBoss();
        hpMax = keepMax; hpScale = keepScale; start = keepStart; despawnAt = keepEnd;
        locked.addAll(keepLocked); damage.putAll(keepDmg);
        var a = boss.getAttribute(Attribute.MAX_HEALTH);
        double attr = Math.min(hpMax, 1000);
        if (a != null) a.setBaseValue(attr);
        boss.setHealth(Math.max(1, attr * frac));
    }

    public boolean stop() {
        if (building && center == null) { cancelBuild = true; return true; }
        if (center == null) return false;
        finish();
        return true;
    }

    /** Убрать всё: Стража, миньонов, сундук, голограмму; вернуть рельеф. */
    private void finish() {
        fighting = false;
        if (boss != null && boss.isValid()) boss.remove();
        boss = null;
        hideBar();
        removeMinions();
        locked.clear();
        pl.mystery().stop("boss");
        if (chestInv != null) { for (org.bukkit.entity.HumanEntity v : new ArrayList<>(chestInv.getViewers())) v.closeInventory(); chestInv.clear(); }
        if (chest != null && chest.getState(false) instanceof org.bukkit.block.Container c) c.getInventory().clear();
        if (chestHolo != null) chestHolo.remove();
        chest = null; chestInv = null; chestHolo = null; chestFilled = false;
        restoreArena();
        if (center != null) pl.events().release("boss");
        center = null; world = null;
    }

    private void restoreArena() {
        if (changed.isEmpty()) { journal().delete(); return; }
        List<Player> inside = new ArrayList<>();
        if (world != null && center != null) for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            double r = radius + 2;
            if (dist2(l) <= r * r && l.getY() >= floorY - 10 && l.getY() <= floorY + height + 4) inside.add(p);
        }
        List<Map.Entry<Block, BlockData>> list = new ArrayList<>(changed.entrySet());
        Collections.reverse(list);
        for (var en : list) {
            try {
                if (en.getKey().getState(false) instanceof org.bukkit.block.Container c) c.getInventory().clear();
                en.getKey().setBlockData(en.getValue(), false);
            } catch (Exception ex) { pl.getLogger().warning("boss arena restore: " + ex.getMessage()); }
        }
        changed.clear();
        journal().delete();
        for (Player p : inside) Util.unstuck(p);
    }

    public void shutdown() { if (center != null) finish(); }

    // ===================== журнал на случай сбоя =====================
    private void saveJournal() {
        if (changed.isEmpty()) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", world.getName());
        List<String> l = new ArrayList<>(changed.size());
        for (var en : changed.entrySet()) { Block b = en.getKey(); l.add(b.getX() + "," + b.getY() + "," + b.getZ() + "|" + en.getValue().getAsString()); }
        y.set("blocks", l);
        try { y.save(journal()); } catch (Exception ex) { pl.getLogger().warning("boss-arena-restore: " + ex.getMessage()); }
    }

    private void restoreJournal(int tries) {
        File f = journal();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        World w = Bukkit.getWorld(y.getString("world", "world"));
        if (w == null) { if (tries < 12) Bukkit.getScheduler().runTaskLater(pl, () -> restoreJournal(tries + 1), 100); return; }
        List<String> l = new ArrayList<>(y.getStringList("blocks"));
        Collections.reverse(l);
        int n = 0;
        for (String s : l) {
            try {
                int bar = s.indexOf('|'); String[] c = s.substring(0, bar).split(",");
                Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                if (b.getState(false) instanceof org.bukkit.block.Container ct) ct.getInventory().clear();
                b.setBlockData(Bukkit.createBlockData(s.substring(bar + 1)), false);
                n++;
            } catch (Exception ignored) {}
        }
        f.delete();
        for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(BOSS, PersistentDataType.BYTE) || e.getPersistentDataContainer().has(MINION, PersistentDataType.BYTE)) e.remove();
        pl.getLogger().info("Босс: после перезапуска арена убрана, восстановлено блоков: " + n);
    }

    // ===================== события =====================
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBossHurt(EntityDamageEvent e) {
        if (boss == null || !e.getEntity().equals(boss)) return;
        switch (e.getCause()) {
            case FALL, SUFFOCATION, DROWNING, LAVA, FIRE, FIRE_TICK, HOT_FLOOR, CONTACT, CRAMMING, FREEZE, BLOCK_EXPLOSION, WORLD_BORDER -> { e.setCancelled(true); return; }
            default -> {}
        }
        if (e instanceof EntityDamageByEntityEvent be) {
            Player p = shooter(be.getDamager());
            if (p != null && !locked.contains(p.getUniqueId())) { e.setCancelled(true); warn(p, "<red>☠ Стража можно бить только на его арене"); return; }
            if (be.getDamager() instanceof Vex) { e.setCancelled(true); return; }
        }
        if (hpScale > 1) e.setDamage(e.getDamage() / hpScale);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent e) {
        if (boss == null || !e.getEntity().equals(boss)) return;
        Player p = shooter(e.getDamager());
        if (p != null) damage.merge(p.getUniqueId(), e.getFinalDamage() * hpScale, Double::sum);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        var pdc = en.getPersistentDataContainer();
        if (pdc.has(MINION, PersistentDataType.BYTE)) { e.getDrops().clear(); e.setDroppedExp(0); minions.remove(en.getUniqueId()); return; }
        if (!pdc.has(BOSS, PersistentDataType.BYTE)) return;
        e.getDrops().clear();
        e.setDroppedExp(ci("exp", 500));
        if (en.equals(boss) && fighting) victory(en.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) { locked.remove(e.getEntity().getUniqueId()); }

    /** Вышел из игры - больше не заперт (при входе плагин переносит на спавн и обратно; вернётся на арену - запрётся снова). */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) { UUID u = e.getPlayer().getUniqueId(); shown.remove(u); warnCd.remove(u); locked.remove(u); }

    /** Блок арены (для спец-предметов и других модулей, которые сами меняют блоки). */
    public boolean isProtected(Block b) { return center != null && inVolume(b); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!fighting || locked.isEmpty() || !e.hasChangedPosition()) return;
        Player p = e.getPlayer();
        if (!locked.contains(p.getUniqueId()) || allowed(e.getTo())) return;
        if (allowed(e.getFrom())) {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw()); back.setPitch(e.getTo().getPitch());
            e.setTo(back);
            warn(p, "<red>☠ С арены Стража не уйти, пока он жив!");
        } else Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline() && fighting && locked.contains(p.getUniqueId())) pullBack(p); });
    }

    /** Запертому игроку нельзя никуда за край: ни жемчугом, ни командами плагина (/pvp, /top, турнир...).
     *  Пропускаем только ванильный /tp (он есть лишь у админов) и перенос на проверку модератором. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTp(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!fighting || bypassTp || !locked.contains(p.getUniqueId()) || allowed(e.getTo())) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND || pl.admin().isFrozen(p)) { locked.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        warn(p, "<red>☠ С арены Стража не уйти, пока он жив!");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (!inVolume(e.getBlock())) return;
        e.setCancelled(true);
        warn(e.getPlayer(), e.getBlock().equals(chest) ? "<red>Сундук Стража нельзя сломать - открой его" : "<red>Арену Стража нельзя ломать - она исчезнет сама");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (inVolume(e.getBlockPlaced())) { e.setCancelled(true); warn(e.getPlayer(), "<red>На арене Стража нельзя ставить блоки"); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (inVolume(e.getBlockClicked().getRelative(e.getBlockFace()))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (center != null && inVolume(e.getToBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (center != null) e.blockList().removeIf(this::inVolume); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (center != null) e.blockList().removeIf(this::inVolume); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { if (center != null && e.getBlocks().stream().anyMatch(b -> inVolume(b) || inVolume(b.getRelative(e.getDirection())))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { if (center != null && e.getBlocks().stream().anyMatch(this::inVolume)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (inVolume(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (inVolume(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) { if (inVolume(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent e) {
        if (center == null || !e.getBlock().getWorld().equals(world)) return;
        double r = radius + 8;
        if (e.getBlock().getLocation().distanceSquared(center) < r * r) e.setCancelled(true);
    }
}
