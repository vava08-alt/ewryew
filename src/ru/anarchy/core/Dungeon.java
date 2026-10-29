package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;

import static ru.anarchy.core.AnarchyCore.mm;

/**
 * ☠ Подземелье дьявола: портал на спавне открыт первые 5 минут. 3 уровня по 5 минут:
 * 1 - зомби в золотой броне, 2 - скелеты-лучники в зачарованной железной броне (с мобов падают только «Сердца моря»),
 * 3 - босс «Дьявол»: после его смерти все в подземелье получают ключ и выходят через портал с сердцами.
 * Не успели - вещи остаются, но сердца этого забега сгорают. PvP выключено. Сердца меняются у Посейдона на лут.
 */
public final class Dungeon implements Listener {
    public static final String WORLD = "dungeon";

    private record Lvl(int n, Location origin, Location spawn, BoundingBox bounds, List<Location> mobs, double spread,
                       Location boss, BoundingBox exit, Location center, double radius) {}

    private final AnarchyCore pl;
    private final NamespacedKey HEART, KEY, MOB, VER;
    private World world;
    private final Map<Integer, Lvl> lv = new HashMap<>();
    private int version;
    private boolean ready, buildingWorld;

    // состояние забега: 0 - нет, 1 - уровень 1 (портал открыт), 2 - уровень 2, 3 - босс, 4 - босс убит (выход открыт)
    private int state;
    private String run = "";
    private long start, levelEnd, end, levelStart;
    /** Квота мобов текущего уровня (сколько всего можно убить) и сколько уже убито. */
    private int quota, killed;
    /** Погибшие в текущем забеге: выбыли, повторный вход закрыт до следующего подземелья. */
    private final Set<UUID> fallen = new HashSet<>();
    private boolean barred(Player p) { return fallen.contains(p.getUniqueId()) && !bypass(p); }
    private static final String BARRED = "<red>Ты погиб в этом подземелье - вход закрыт до следующего забега.";
    /** Редкость забега: ограничивает тир сфер/талисманов в награде за Дьявола. */
    private Events.Tier runTier = Events.Tier.COMMON;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> exiting = new HashSet<>();
    private final Map<UUID, Integer> earned = new HashMap<>();
    private final List<LivingEntity> mobs = new ArrayList<>();
    private LivingEntity boss;
    private double bossMax;
    private boolean enraged;
    private long nextSkill, nextSpawn;
    private BossBar bar;
    private TextDisplay portalHolo;
    private final Map<UUID, Long> warnCd = new HashMap<>();
    private final Set<Long> tickets = new HashSet<>();

    public Dungeon(AnarchyCore pl) {
        this.pl = pl;
        HEART = new NamespacedKey(pl, "devil_heart");
        KEY = new NamespacedKey(pl, "devil_key");
        MOB = new NamespacedKey(pl, "devil_mob");
        VER = new NamespacedKey(pl, "dungeon_version");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40, 20);
        Bukkit.getScheduler().runTaskTimer(pl, this::fastTick, 45, 5);
        Bukkit.getScheduler().runTaskLater(pl, this::load, 20);
    }

    private int cfg(String k, int d) { return pl.getConfig().getInt("events.dungeon." + k, d); }
    private double cfgD(String k, double d) { return pl.getConfig().getDouble("events.dungeon." + k, d); }
    private String pre() { return pl.getConfig().getString("prefix", ""); }

    // ===================== мир и постройка =====================
    static final class Biomes extends BiomeProvider {
        @Override public Biome getBiome(WorldInfo w, int x, int y, int z) { return x < 150 ? Biome.NETHER_WASTES : x < 450 ? Biome.SOUL_SAND_VALLEY : Biome.CRIMSON_FOREST; }
        @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.NETHER_WASTES, Biome.SOUL_SAND_VALLEY, Biome.CRIMSON_FOREST); }
    }

    private <T> void rule(GameRule<T> r, T v) { try { world.setGameRule(r, v); } catch (Exception ignored) {} }

    private void load() {
        try {
            YamlConfiguration meta;
            try (InputStream in = pl.getResource("dungeon.yml")) {
                if (in == null) { pl.getLogger().warning("Подземелье: нет dungeon.yml в плагине"); return; }
                meta = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            version = meta.getInt("version", 1);
            world = Bukkit.getWorld(WORLD);
            if (world == null) world = new WorldCreator(WORLD).environment(World.Environment.NETHER).generator(new Worlds.Void())
                    .biomeProvider(new Biomes()).generateStructures(false).createWorld();
            if (world == null) { pl.getLogger().severe("Подземелье: не удалось создать мир " + WORLD); return; }
            world.setDifficulty(Difficulty.HARD);
            world.setPVP(false);
            world.setAutoSave(true);
            rule(GameRules.SPAWN_MOBS, false); rule(GameRules.SPAWN_MONSTERS, false); rule(GameRules.SPAWN_PATROLS, false);
            rule(GameRules.SPAWN_WANDERING_TRADERS, false); rule(GameRules.SPAWN_PHANTOMS, false); rule(GameRules.SPAWN_WARDENS, false);
            rule(GameRules.KEEP_INVENTORY, true); rule(GameRules.MOB_GRIEFING, false); rule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            rule(GameRules.PVP, false); rule(GameRules.ADVANCE_TIME, false); rule(GameRules.ADVANCE_WEATHER, false);
            rule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false); rule(GameRules.IMMEDIATE_RESPAWN, true); rule(GameRules.RANDOM_TICK_SPEED, 0);
            rule(GameRules.MOB_DROPS, false); rule(GameRules.BLOCK_DROPS, false); rule(GameRules.TNT_EXPLODES, false);
            ConfigurationSection ls = meta.getConfigurationSection("levels");
            if (ls != null) for (String k : ls.getKeys(false)) {
                ConfigurationSection c = ls.getConfigurationSection(k);
                int n = Integer.parseInt(k);
                double[] o = arr(c, "origin");
                Location org = new Location(world, o[0], o[1], o[2]);
                double[] s = arr(c, "spawn");
                Location sp = org.clone().add(s[0], s[1], s[2]); sp.setYaw(s.length > 3 ? (float) s[3] : 0);
                double[] b = arr(c, "bounds");
                BoundingBox bb = new BoundingBox(o[0] + b[0], o[1] + b[1], o[2] + b[2], o[0] + b[3] + 1, o[1] + b[4] + 1, o[2] + b[5] + 1);
                List<Location> ms = new ArrayList<>();
                for (Object e : c.getList("mobs", List.of())) if (e instanceof List<?> li && li.size() >= 3)
                    ms.add(org.clone().add(((Number) li.get(0)).doubleValue(), ((Number) li.get(1)).doubleValue(), ((Number) li.get(2)).doubleValue()));
                Location bo = null, ce = null; BoundingBox ex = null;
                if (c.contains("boss")) { double[] q = arr(c, "boss"); bo = org.clone().add(q[0], q[1], q[2]); bo.setYaw(q.length > 3 ? (float) q[3] : 0); }
                if (c.contains("center")) { double[] q = arr(c, "center"); ce = org.clone().add(q[0], q[1], q[2]); }
                if (c.contains("exit")) { double[] q = arr(c, "exit"); ex = new BoundingBox(o[0] + q[0], o[1] + q[1], o[2] + q[2], o[0] + q[3] + 1, o[1] + q[4] + 1, o[2] + q[5] + 1); }
                lv.put(n, new Lvl(n, org, sp, bb, ms, c.getDouble("spread", 1), bo, ex, ce == null ? sp : ce, c.getDouble("radius", 19)));
            }
            Lvl l1 = lv.get(1);
            if (l1 != null) world.setSpawnLocation(l1.spawn());
            Integer have = world.getPersistentDataContainer().get(VER, PersistentDataType.INTEGER);
            if (have != null && have == version && !pl.getConfig().getBoolean("events.dungeon.rebuild", false)) { ready = true; cleanWorld(); return; }
            build();
        } catch (Exception ex) { pl.getLogger().severe("Подземелье: ошибка загрузки - " + ex); ex.printStackTrace(); }
    }

    private static double[] arr(ConfigurationSection c, String k) {
        List<?> l = c.getList(k, List.of());
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = ((Number) l.get(i)).doubleValue();
        return r;
    }

    /** Строит подземелье из dungeon.bin порциями (~30 000 блоков за тик). */
    private void build() throws Exception {
        buildingWorld = true;
        List<int[]> boxes = new ArrayList<>();
        List<int[]> runsList = new ArrayList<>();
        BlockData[] pal;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(pl.getResource("dungeon.bin")))) {
            byte[] mg = new byte[4]; in.readFully(mg);
            if (!new String(mg, StandardCharsets.US_ASCII).equals("AQD1")) throw new IllegalStateException("bad dungeon.bin");
            int pc = in.readInt();
            pal = new BlockData[pc];
            for (int i = 0; i < pc; i++) {
                byte[] s = new byte[in.readUnsignedShort()]; in.readFully(s);
                String st = new String(s, StandardCharsets.UTF_8);
                try { pal[i] = Bukkit.createBlockData(st); } catch (Exception e) { pl.getLogger().warning("Подземелье: блок " + st + " - " + e.getMessage()); pal[i] = Material.AIR.createBlockData(); }
            }
            int bc = in.readInt();
            for (int b = 0; b < bc; b++) {
                int[] box = new int[6]; for (int i = 0; i < 6; i++) box[i] = in.readInt();
                int rc = in.readInt();
                int[] runs = new int[rc * 2];
                for (int i = 0; i < rc; i++) { runs[i * 2] = in.readUnsignedShort(); runs[i * 2 + 1] = in.readUnsignedShort(); }
                boxes.add(box); runsList.add(runs);
            }
        }
        long total = 0; for (int[] b : boxes) total += (long) b[3] * b[4] * b[5];
        pl.getLogger().info("Подземелье: строю " + boxes.size() + " уровня (" + total + " блоков)...");
        for (int[] b : boxes) for (int cx = b[0] >> 4; cx <= (b[0] + b[3] - 1) >> 4; cx++) for (int cz = b[2] >> 4; cz <= (b[2] + b[5] - 1) >> 4; cz++) world.addPluginChunkTicket(cx, cz, pl);
        final BlockData[] P = pal;
        new org.bukkit.scheduler.BukkitRunnable() {
            int bi = 0, ri = 0, left = -1, x, y, z;
            @Override public void run() {
                int budget = 30_000;
                while (budget > 0 && bi < boxes.size()) {
                    int[] b = boxes.get(bi), runs = runsList.get(bi);
                    if (left < 0) { x = 0; y = 0; z = 0; ri = 0; left = runs.length > 0 ? runs[1] : 0; }
                    while (budget > 0 && ri < runs.length / 2) {
                        BlockData d = P[runs[ri * 2]];
                        while (left > 0 && budget > 0) {
                            Block bl = world.getBlockAt(b[0] + x, b[1] + y, b[2] + z);
                            if (!bl.getBlockData().equals(d)) bl.setBlockData(d, false);
                            left--; budget--;
                            if (++x >= b[3]) { x = 0; if (++z >= b[5]) { z = 0; y++; } }
                        }
                        if (left == 0) { ri++; if (ri < runs.length / 2) left = runs[ri * 2 + 1]; }
                    }
                    if (ri >= runs.length / 2) { bi++; left = -1; }
                }
                if (bi >= boxes.size()) {
                    cancel();
                    if (state == 0) world.removePluginChunkTickets(pl);
                    world.getPersistentDataContainer().set(VER, PersistentDataType.INTEGER, version);
                    world.save();
                    buildingWorld = false; ready = true;
                    cleanWorld();
                    pl.getLogger().info("Подземелье дьявола построено (версия " + version + ").");
                }
            }
        }.runTaskTimer(pl, 1, 1);
    }

    private void cleanWorld() {
        if (world == null) return;
        for (Entity e : world.getEntities()) if (!(e instanceof Player)) e.remove();
        removeExit();
    }

    public boolean isDungeon(World w) { return w != null && w.getName().equals(WORLD); }
    public boolean inDungeon(Player p) { return players.contains(p.getUniqueId()); }
    public boolean isOpen() { return state == 1; }
    public boolean running() { return state != 0; }

    // ===================== предметы =====================
    public ItemStack heart(String tag, int amount) {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(mm("<!i><gradient:#5ef2ff:#1e7bff><bold>Сердце моря</bold></gradient>"));
        m.lore(tag.equals("ok")
                ? List.of(mm("<!i><gray>Добыто в Подземелье дьявола"), mm("<!i><aqua>Обменяй у Посейдона на спавне на лут"))
                : List.of(mm("<!i><gray>Добыто в Подземелье дьявола"), mm("<!i><red>Не подтверждено - выйди из подземелья с ключом,"), mm("<!i><red>иначе сердце сгорит")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(HEART, PersistentDataType.STRING, tag);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack key() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        try { m.setItemModel(NamespacedKey.minecraft("ominous_trial_key")); } catch (Throwable ignored) {}
        m.displayName(mm("<!i><gradient:#ff4a3a:#ffb000><bold>Ключ дьявола</bold></gradient>"));
        m.lore(List.of(mm("<!i><gray>Войди в портал за троном Дьявола -"), mm("<!i><gray>выйдешь из подземелья с сердцами")));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, run);
        it.setItemMeta(m);
        return it;
    }

    public String heartTag(ItemStack it) {
        if (it == null || it.getType() != Material.HEART_OF_THE_SEA || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(HEART, PersistentDataType.STRING);
    }
    private boolean isKey(ItemStack it) { return it != null && it.getType() == Material.PAPER && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.STRING); }
    /** Предметы подземелья (для очистки лимитов: их не трогать) */
    public boolean isDevilItem(ItemStack it) { return heartTag(it) != null || isKey(it); }

    /** mode 0 - сжечь сердца забега и ключи; 1 - подтвердить сердца и забрать ключ. Возвращает число сердец забега. */
    private int process(Player p, int mode) {
        p.closeInventory();
        var inv = p.getInventory();
        int n = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType().isAir()) continue;
            if (isKey(it)) { inv.setItem(i, null); continue; }
            String t = heartTag(it);
            if (t != null && !t.equals("ok")) { n += it.getAmount(); inv.setItem(i, mode == 1 ? heart("ok", it.getAmount()) : null); continue; }
            if (it.getItemMeta() instanceof BundleMeta bm && bm.hasItems()) {
                List<ItemStack> keep = new ArrayList<>(); boolean ch = false;
                for (ItemStack in : bm.getItems()) {
                    if (isKey(in)) { ch = true; continue; }
                    String tt = heartTag(in);
                    if (tt != null && !tt.equals("ok")) { ch = true; n += in.getAmount(); if (mode == 1) keep.add(heart("ok", in.getAmount())); continue; }
                    keep.add(in);
                }
                if (ch) { bm.setItems(keep); it.setItemMeta(bm); inv.setItem(i, it); }
            }
        }
        return n;
    }

    private void give(Player p, ItemStack it) { for (ItemStack o : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), o); }

    // ===================== портал на спавне =====================
    private int[] portalBox() {
        try {
            String[] s = pl.getConfig().getString("events.dungeon.portal.box", "329,6,300,335,15,300").split(",");
            int[] r = new int[6]; for (int i = 0; i < 6; i++) r[i] = Integer.parseInt(s[i].trim());
            return new int[]{Math.min(r[0], r[3]), Math.min(r[1], r[4]), Math.min(r[2], r[5]), Math.max(r[0], r[3]), Math.max(r[1], r[4]), Math.max(r[2], r[5])};
        } catch (Exception e) { return null; }
    }
    private BlockData sealData() {
        try { return Bukkit.createBlockData(pl.getConfig().getString("events.dungeon.portal.closed-block", "minecraft:blue_stained_glass")); }
        catch (Exception e) { return Bukkit.createBlockData(Material.AIR); }
    }
    private void setPortal(boolean open) {
        World h = pl.worlds().hub(); int[] b = portalBox();
        if (h == null || b == null) return;
        BlockData d = Bukkit.createBlockData("minecraft:nether_portal[axis=" + (b[0] == b[3] ? "z" : "x") + "]");
        BlockData seal = sealData(); Material sm = seal.getMaterial();
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            Block bl = h.getBlockAt(x, y, z); Material t = bl.getType();
            boolean ours = t.isAir() || t == Material.NETHER_PORTAL || t == sm;
            if (!ours) continue;
            if (open) { if (t != Material.NETHER_PORTAL) bl.setBlockData(d, false); }
            else if (t != sm) bl.setBlockData(seal, false);
        }
    }
    private boolean sealedInit;
    private double portalPhase;
    /** Визуал портала: открыт - водоворот частиц, закрыт - редкие "пузырьки" за печатью */
    private void portalFx() {
        int[] b = portalBox(); World h = pl.worlds().hub();
        if (b == null || h == null || !h.isChunkLoaded(b[0] >> 4, b[2] >> 4)) return;
        double cx = (b[0] + b[3] + 1) / 2.0, cy = (b[1] + b[4] + 1) / 2.0, cz = (b[2] + b[5] + 1) / 2.0;
        Location c = new Location(h, cx, cy, cz);
        boolean near = false;
        for (Player p : h.getPlayers()) if (p.getLocation().distanceSquared(c) < 64 * 64) { near = true; break; }
        if (!near) return;
        boolean alongX = b[0] != b[3];
        double rw = (b[3] - b[0] + 1) / 2.0 + 0.4, rh = (b[4] - b[1] + 1) / 2.0 + 0.4;
        portalPhase += state == 1 ? 0.35 : 0.08;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = state == 1 ? 10 : 3;
        for (int i = 0; i < n; i++) {
            double a = portalPhase + i * (Math.PI * 2 / n);
            double ox = Math.cos(a) * rw, oy = Math.sin(a) * rh;
            Location p = alongX ? c.clone().add(ox, oy, 0.5) : c.clone().add(0.5, oy, ox);
            Location q = alongX ? c.clone().add(ox, oy, -0.5) : c.clone().add(-0.5, oy, ox);
            Particle pt = state == 1 ? Particle.SOUL_FIRE_FLAME : Particle.NAUTILUS;
            h.spawnParticle(pt, p, 1, 0, 0, 0, 0); h.spawnParticle(pt, q, 1, 0, 0, 0, 0);
        }
        if (state == 1) {
            h.spawnParticle(Particle.PORTAL, c, 25, rw * 0.5, rh * 0.5, 0.3, 0.6);
            h.spawnParticle(Particle.GLOW, c, 4, rw * 0.6, rh * 0.6, 0.6, 0);
            if (r.nextInt(8) == 0) h.playSound(c, Sound.BLOCK_CONDUIT_AMBIENT, 1.2f, 0.7f);
        } else {
            h.spawnParticle(Particle.DRIPPING_WATER, c.clone().add(0, rh - 0.5, 0), 2, rw * 0.6, 0.1, 0.6, 0);
            h.spawnParticle(Particle.GLOW, c, 1, rw * 0.5, rh * 0.5, 0.6, 0);
        }
    }
    public boolean isSpawnPortal(Location l) {
        int[] b = portalBox();
        if (b == null || l == null || !pl.worlds().isHub(l.getWorld())) return false;
        return l.getX() >= b[0] - 0.3 && l.getX() <= b[3] + 1.3 && l.getY() >= b[1] - 1 && l.getY() <= b[4] + 1 && l.getZ() >= b[2] - 0.3 && l.getZ() <= b[5] + 1.3;
    }
    private Location portalCenter() {
        int[] b = portalBox(); World h = pl.worlds().hub();
        if (b == null || h == null) return pl.worlds().hubPoint();
        return new Location(h, (b[0] + b[3] + 1) / 2.0, b[1], (b[2] + b[5] + 1) / 2.0);
    }
    /** Куда выходят из подземелья (перед порталом на спавне) */
    private Location outLoc() {
        Location l = PlayerData.locFromString(pl.getConfig().getString("events.dungeon.portal.exit", ""));
        return l != null ? l : pl.worlds().hubPoint();
    }
    public void portalEnter(Player p) {
        boolean no = barred(p);
        if (state == 1 && !no) { join(p); return; }
        warn(p, no ? BARRED : state == 0 ? "<gray>Портал запечатан. Подземелье откроется во время ивента <white>/event delay" : "<red>Портал закрыт - забег уже идёт. Дождись следующего ивента.");
        Location c = portalCenter();
        Vector v = p.getLocation().toVector().subtract(c.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(0, 0, 1);
        p.setVelocity(v.normalize().multiply(0.6).setY(0.3));
    }
    private void warn(Player p, String m) {
        long now = System.currentTimeMillis();
        if (now - warnCd.getOrDefault(p.getUniqueId(), 0L) < 2500) return;
        warnCd.put(p.getUniqueId(), now);
        pl.msg(p, m);
    }

    private void holo() {
        Location c = PlayerData.locFromString(pl.getConfig().getString("events.dungeon.portal.holo", ""));
        if (c == null) { c = portalCenter(); int[] b = portalBox(); if (b != null) c.setY(b[4] + 2.6); }
        if (c == null || !c.isChunkLoaded()) return;
        if (portalHolo != null && portalHolo.isValid() && (portalHolo.getWorld() != c.getWorld() || portalHolo.getLocation().distanceSquared(c) > 0.25)) { portalHolo.remove(); portalHolo = null; }
        if (portalHolo == null || !portalHolo.isValid()) {
            for (Entity e : c.getWorld().getNearbyEntities(c, 2, 3, 2)) if (e instanceof TextDisplay && e.getScoreboardTags().contains("devil_portal")) e.remove();
            portalHolo = c.getWorld().spawn(c, TextDisplay.class, t -> {
                t.setPersistent(false); t.setBillboard(Display.Billboard.CENTER); t.setShadowed(true);
                t.setDefaultBackground(false); t.setBackgroundColor(Color.fromARGB(120, 30, 0, 0)); t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.addScoreboardTag("devil_portal");
            });
        }
        long now = System.currentTimeMillis();
        String s = MoreEvents.name("dungeon") + "\n" + switch (state) {
            case 1 -> "<green><bold>Портал открыт!</bold> <white>" + MoreEvents.mmss(levelEnd - now) + "\n<gray>Войди в портал или <yellow>/event join</yellow>\n<gray>Внутри: <white>" + players.size() + " <gray>игр.";
            case 0 -> "<gray>Портал запечатан\n<dark_gray>Откроется во время ивента: <white>/event delay";
            default -> "<red>Идёт забег <gray>(уровень <white>" + Math.min(3, state) + "<gray>, игроков <white>" + players.size() + "<gray>)\n<gray>До конца: <white>" + MoreEvents.mmss(end - now);
        } + "\n<dark_gray>Сердца моря меняй у <aqua>Посейдона";
        portalHolo.text(mm(s));
    }

    // ===================== ход ивента =====================
    private long lvlMs() { return Math.max(1, cfg("level-minutes", 3)) * 60_000L; }

    public boolean start() {
        if (state != 0 || !ready || world == null || lv.size() < 3) return false;
        long now = System.currentTimeMillis();
        run = Long.toString(now, 36);
        state = 1; start = now; levelStart = now; levelEnd = now + lvlMs(); end = now + lvlMs() * 3;
        runTier = pl.events().rollEventTier("dungeon");
        killed = 0; quota = levelQuota(1, 1);
        players.clear(); exiting.clear(); earned.clear(); fallen.clear(); enraged = false; boss = null;
        clearMobs(); removeExit(); clearItems(false);
        tickets(true);
        setPortal(true);
        bar = BossBar.bossBar(mm(MoreEvents.name("dungeon")), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        pl.events().announce(MoreEvents.name("dungeon"), runTier.tag("забег") + " <red>- портал на спавне открыт " + cfg("level-minutes", 3) + " мин! <gray>Войди в него или <yellow>/event join");
        Bukkit.broadcast(mm(pre() + "<gray>3 уровня по " + cfg("level-minutes", 3) + " мин: на каждом уровне <white>квота мобов</white> - добейте её, иначе всех вернёт на спавн и <red>сердца сгорят</red>. С мобов - <aqua>Сердца моря</aqua>, на 3-м - <red>Дьявол</red>. <dark_gray>PvP выключено.")
                .append(mm(" <red>[Войти]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/event join"))));
        holo();
        return true;
    }

    public boolean stop() {
        if (state == 0) return false;
        for (UUID u : new ArrayList<>(players)) { Player p = Bukkit.getPlayer(u); if (p != null) { int n = process(p, 0); out(p); pl.msg(p, "<red>Подземелье закрыто администрацией." + (n > 0 ? " <gray>Сердца забега сгорели: <white>" + n : "")); } }
        finish(null);
        return true;
    }

    public List<MoreEvents.Active> active() {
        if (state == 0) return List.of();
        String ph = switch (state) { case 1 -> "мобы " + killed + "/" + quota + ", портал закроется через"; case 2 -> "мобы " + killed + "/" + quota + ", до конца уровня"; case 3 -> "босс, до конца"; default -> "выход открыт, до конца"; };
        long e = state <= 2 ? levelEnd : end;
        return List.of(new MoreEvents.Active("dungeon", MoreEvents.name("dungeon") + " <dark_gray>[" + runTier.tag() + "<dark_gray>] [<gray>ур. " + Math.min(3, state) + "<dark_gray>]", portalCenter(), start, e, ph, BossBar.Color.RED));
    }

    /** /event join во время открытого портала */
    public void joinCommand(Player p) {
        if (!pl.worlds().isHub(p.getWorld()) && !p.isOp()) { pl.msg(p, "<gray>Портал в Подземелье дьявола - на спавне: <yellow>/spawn</yellow>, затем войди в портал (или снова /event join)."); return; }
        join(p);
    }

    private void join(Player p) {
        if (state != 1 || players.contains(p.getUniqueId())) return;
        if (barred(p)) { warn(p, BARRED); return; }
        PlayerData d = pl.data(p);
        if (!d.authed) return;
        if (d.inCombat()) { warn(p, "<red>Нельзя войти в подземелье в режиме боя."); return; }
        if (pl.moreEvents().inTournament(p)) { warn(p, "<red>Ты на турнире."); return; }
        int max = cfg("max-players", 0);
        if (max > 0 && players.size() >= max) { warn(p, "<red>Подземелье заполнено (" + max + " игроков)."); return; }
        process(p, 0);
        players.add(p.getUniqueId());
        earned.putIfAbsent(p.getUniqueId(), 0);
        Lvl l = lv.get(1);
        p.teleport(l.spawn());
        p.setFallDistance(0); p.setFireTicks(0);
        p.showTitle(Title.title(mm("<gradient:#ff2a2a:#7a0000><bold>Подземелье дьявола</bold></gradient>"), mm("<gray>Уровень 1 - <gold>золотые стражи"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.35f, 0.8f);
        p.playSound(p.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1f, 0.8f);
        pl.msg(p, "<gray>Убивай мобов - падают <aqua>Сердца моря</aqua>. На уровне <white>квота мобов</white> (" + killed + "/" + quota + ") и <white>" + cfg("level-minutes", 3) + " мин</white>: "
                + "не добьёте - всех вернёт на спавн, <red>сердца сгорят</red>. На 3-м уровне - <red>Дьявол</red>: убьёшь - получишь ключ и выйдешь с сердцами. "
                + "<red>Погибнешь - выбываешь</red> (вещи останутся, сердца забега сгорят, снова войти нельзя). Выйти: <yellow>/event leave");
        if (bar != null) p.showBossBar(bar);
    }

    /** Добровольный выход: сердца забега сгорают. */
    public void leave(Player p, boolean msg) {
        if (!players.contains(p.getUniqueId())) return;
        int n = process(p, 0);
        out(p);
        if (msg) pl.msg(p, "<gray>Ты покинул подземелье." + (n > 0 ? " <red>Сердца забега сгорели: <white>" + n : ""));
        checkEmpty();
    }

    /** Убрать игрока из забега и вернуть к порталу на спавне. */
    private void out(Player p) {
        players.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        if (p.isDead()) return;
        if (isDungeon(p.getWorld())) {
            exiting.add(p.getUniqueId());
            p.teleport(outLoc());
            exiting.remove(p.getUniqueId());
        }
        p.setFallDistance(0); p.setFireTicks(0);
        p.removePotionEffect(PotionEffectType.WITHER);
    }

    private void exit(Player p) {
        if (!players.contains(p.getUniqueId())) return;
        int n = process(p, 1);
        out(p);
        p.showTitle(Title.title(mm("<gradient:#5ef2ff:#1e7bff><bold>Ты выбрался!</bold></gradient>"), mm("<gray>Сердец моря: <aqua>" + n + " <gray>- обменяй у Посейдона"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        pl.msg(p, "<green>Ты вышел из подземелья с ключом. <gray>Подтверждено сердец: <aqua>" + n + "<gray>. Посейдон стоит у портала: <yellow>/event poseidon");
        checkEmpty();
    }

    private void checkEmpty() {
        if (state >= 2 && players.isEmpty()) finish(state == 4 ? "<green>Все выбрались с добычей!" : "<gray>В подземелье никого не осталось.");
    }

    private void goLevel(int n) {
        collectGround();
        clearMobs();
        state = n;
        long now = System.currentTimeMillis();
        levelStart = now;
        if (n == 3) end = Math.max(end, now + lvlMs());
        levelEnd = n == 3 ? end : now + lvlMs();
        killed = 0;
        quota = n <= 2 ? levelQuota(n, online().size()) : 0;
        Lvl l = lv.get(n);
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || p.isDead()) continue;
            p.teleport(l.spawn());
            p.setFallDistance(0);
            p.showTitle(Title.title(mm("<red><bold>Уровень " + n + "</bold>"), mm(n == 2 ? "<gray>Скелеты-лучники в зачарованной броне" : "<dark_red>Логово Дьявола"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
            p.playSound(p.getLocation(), n == 3 ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 0.7f);
        }
        if (n == 3) Bukkit.getScheduler().runTaskLater(pl, () -> { if (state == 3 && boss == null) spawnBoss(); }, 60);
        nextSpawn = now + 2500;
    }

    /** Перед сменой уровня: сердца с пола - владельцам, выброшенные вещи - обратно бросившему. */
    private void collectGround() {
        if (world == null) return;
        for (Item it : world.getEntitiesByClass(Item.class)) {
            ItemStack st = it.getItemStack();
            String t = heartTag(st);
            UUID to = t != null ? it.getOwner() : it.getThrower();
            Player p = to == null ? null : Bukkit.getPlayer(to);
            if (p != null && players.contains(to) && (t == null || t.equals(run))) give(p, st);
            it.remove();
        }
    }

    /** Квота мобов уровня: base + per-player на игрока, но не больше доли общей квоты забега (зависит от онлайна сервера). */
    private int levelQuota(int level, int players) {
        int online = Bukkit.getOnlinePlayers().size(), cap = 250;
        ConfigurationSection caps = pl.getConfig().getConfigurationSection("events.dungeon.quota.run-cap");
        if (caps != null) {
            int bestFrom = -1;
            for (String k : caps.getKeys(false)) {
                try { int from = Integer.parseInt(k.trim()); if (from <= online && from > bestFrom) { bestFrom = from; cap = caps.getInt(k); } } catch (NumberFormatException ignored) {}
            }
        }
        double share = pl.getConfig().getDouble("events.dungeon.quota.level" + level + "-share", 0.5);
        int want = cfg("quota.base", 15) + cfg("quota.per-player", 15) * Math.max(1, players);
        return Math.max(1, Math.min((int) Math.round(cap * share), want));
    }

    /** Квота уровня не добита за отведённое время - всех на спавн, сердца забега сгорают. */
    private void failQuota() {
        int burned = 0;
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { players.remove(u); continue; }
            int n = pl.getConfig().getBoolean("events.dungeon.quota.fail-burns-hearts", true) ? process(p, 0) : process(p, 1);
            out(p); burned += n;
            p.showTitle(Title.title(mm("<red><bold>Квота не выполнена</bold>"), mm("<gray>Убито " + killed + " из " + quota + " - тебя вернуло на спавн"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
            pl.msg(p, "<red>Не успели убить всех мобов уровня (" + killed + "/" + quota + ") - вас вернуло на спавн." + (n > 0 ? " <gray>Сердца забега сгорели: <white>" + n : ""));
        }
        finish("<red>квота мобов уровня " + state + " не выполнена (" + killed + "/" + quota + "), игроки вернулись на спавн" + (burned > 0 ? ", сгорело сердец: " + burned : ""));
    }

    private void timeout() {
        int done = 0, burned = 0;
        for (UUID u : new ArrayList<>(players)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { players.remove(u); continue; }
            if (state == 4) { int n = process(p, 1); out(p); pl.msg(p, "<green>Время вышло, но Дьявол повержен - ключ вывел тебя. <gray>Сердец: <aqua>" + n); done++; }
            else { int n = process(p, 0); out(p); burned += n; pl.msg(p, "<red>Время вышло! Дьявол жив - сердца этого забега сгорели (<white>" + n + "<red>). <gray>Вещи остались при тебе."); }
            p.showTitle(Title.title(mm(state == 4 ? "<green><bold>Выход</bold>" : "<red><bold>Время вышло</bold>"), mm(state == 4 ? "<gray>Сердца сохранены" : "<gray>Сердца забега сгорели"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        }
        finish(state == 4 ? "<green>Забег завершён: выбралось " + done + " игр." : "<red>Дьявол устоял - время вышло, сгорело сердец: " + burned);
    }

    private void finish(String msg) {
        int was = state;
        state = 0;
        for (UUID u : new ArrayList<>(players)) { Player p = Bukkit.getPlayer(u); if (p != null) { process(p, 0); out(p); } }
        players.clear();
        clearMobs();
        if (boss != null) { boss.remove(); boss = null; }
        removeExit();
        setPortal(false);
        clearItems(true);
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bar = null;
        tickets(false);
        if (msg != null && was != 0) Bukkit.broadcast(mm(pre() + MoreEvents.name("dungeon") + " <dark_gray>- " + msg));
        holo();
    }

    public void shutdown() {
        if (state != 0) {
            for (UUID u : new ArrayList<>(players)) { Player p = Bukkit.getPlayer(u); if (p != null) { process(p, 0); out(p); pl.msg(p, "<red>Перезагрузка сервера - подземелье закрыто, сердца забега сгорели."); } }
            finish(null);
        }
        if (portalHolo != null) portalHolo.remove();
    }

    private void tickets(boolean add) {
        if (world == null) return;
        if (!add) { world.removePluginChunkTickets(pl); tickets.clear(); return; }
        for (Lvl l : lv.values()) {
            BoundingBox b = l.bounds();
            for (int cx = ((int) Math.floor(b.getMinX()) - 8) >> 4; cx <= ((int) Math.floor(b.getMaxX()) + 8) >> 4; cx++)
                for (int cz = ((int) Math.floor(b.getMinZ()) - 8) >> 4; cz <= ((int) Math.floor(b.getMaxZ()) + 8) >> 4; cz++)
                    if (tickets.add(((long) cx << 32) ^ (cz & 0xffffffffL))) world.addPluginChunkTicket(cx, cz, pl);
        }
    }

    private void clearMobs() { for (LivingEntity e : mobs) e.remove(); mobs.clear(); if (world != null) for (Entity e : world.getEntities()) if (e.getPersistentDataContainer().has(MOB, PersistentDataType.INTEGER)) e.remove(); }
    private void clearItems(boolean all) { if (world != null) for (Entity e : world.getEntities()) if (e instanceof Item || e instanceof Projectile || e instanceof ExperienceOrb) e.remove(); }

    private void openExit() { exitBlocks(true); }
    private void removeExit() { exitBlocks(false); }
    private void exitBlocks(boolean open) {
        Lvl l = lv.get(3);
        if (l == null || l.exit() == null || world == null) return;
        BoundingBox b = l.exit();
        String axis = (b.getWidthX() <= 1.01) ? "z" : "x";
        BlockData d = Bukkit.createBlockData("minecraft:nether_portal[axis=" + axis + "]");
        for (int x = (int) b.getMinX(); x < (int) b.getMaxX(); x++) for (int y = (int) b.getMinY(); y < (int) b.getMaxY(); y++) for (int z = (int) b.getMinZ(); z < (int) b.getMaxZ(); z++) {
            Block bl = world.getBlockAt(x, y, z);
            if (open) { if (bl.getType().isAir()) bl.setBlockData(d, false); }
            else if (bl.getType() == Material.NETHER_PORTAL) bl.setType(Material.AIR, false);
        }
    }

    // ===================== мобы =====================
    private List<Player> online() { List<Player> l = new ArrayList<>(); for (UUID u : players) { Player p = Bukkit.getPlayer(u); if (p != null && !p.isDead() && isDungeon(p.getWorld())) l.add(p); } return l; }

    private void maintainMobs(long now) {
        mobs.removeIf(m -> !m.isValid() || m.isDead());
        if (state != 1 && state != 2) return;
        List<Player> ps = online();
        if (ps.isEmpty() || now < nextSpawn) return;
        String k = "mobs.level" + state + ".";
        int want = Math.min(cfg(k + "max", 18), cfg(k + "base", state == 1 ? 6 : 5) + cfg(k + "per-player", 2) * ps.size());
        want = Math.min(want, quota - killed);   // живых не больше, чем осталось по квоте
        Lvl l = lv.get(state);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 2 && mobs.size() < want && !l.mobs().isEmpty(); i++) {
            Location at = null;
            for (int t = 0; t < 6 && at == null; t++) {
                Location c = l.mobs().get(r.nextInt(l.mobs().size())).clone();
                int s = (int) Math.round(l.spread());
                if (s > 0) c.add(r.nextInt(-s, s + 1), 0, r.nextInt(-s, s + 1));
                if (!c.getBlock().isPassable() || !c.clone().add(0, 1, 0).getBlock().isPassable()) continue;
                boolean close = false;
                for (Player p : ps) if (p.getLocation().distanceSquared(c) < 16) { close = true; break; }
                if (!close) at = c;
            }
            if (at == null) break;
            at.setYaw(r.nextFloat() * 360);
            LivingEntity e = state == 1 ? zombie(at) : skeleton(at);
            mobs.add(e);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.03);
            world.playSound(at, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.7f);
        }
        nextSpawn = now + cfg("respawn-ms", 1500);
    }

    private void base(LivingEntity e, int level, double hp) {
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        e.getPersistentDataContainer().set(MOB, PersistentDataType.INTEGER, level);
        var a = e.getAttribute(Attribute.MAX_HEALTH); if (a != null) { a.setBaseValue(hp); e.setHealth(hp); }
        var fr = e.getAttribute(Attribute.FOLLOW_RANGE); if (fr != null) fr.setBaseValue(40);
        var sr = e.getAttribute(Attribute.SPAWN_REINFORCEMENTS); if (sr != null) sr.setBaseValue(0);
        var eq = e.getEquipment();
        if (eq != null) { eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setBootsDropChance(0); eq.setItemInMainHandDropChance(0); eq.setItemInOffHandDropChance(0); }
    }

    private ItemStack gear(Material m, Enchantment en, int lvl) {
        ItemStack it = new ItemStack(m);
        if (en != null && lvl > 0) it.addUnsafeEnchantment(en, lvl);
        ItemMeta im = it.getItemMeta();
        if (im instanceof org.bukkit.inventory.meta.ArmorMeta am) {
            try { am.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(org.bukkit.inventory.meta.trim.TrimMaterial.REDSTONE, org.bukkit.inventory.meta.trim.TrimPattern.RIB)); } catch (Throwable ignored) {}
            it.setItemMeta(am);
        }
        return it;
    }

    private Zombie zombie(Location at) {
        return world.spawn(at, Zombie.class, z -> {
            z.setAdult();
            base(z, 1, cfgD("mobs.level1.health", 26));
            var eq = z.getEquipment();
            eq.setHelmet(gear(Material.GOLDEN_HELMET, null, 0)); eq.setChestplate(gear(Material.GOLDEN_CHESTPLATE, null, 0));
            eq.setLeggings(gear(Material.GOLDEN_LEGGINGS, null, 0)); eq.setBoots(gear(Material.GOLDEN_BOOTS, null, 0));
            eq.setItemInMainHand(gear(Material.GOLDEN_SWORD, Enchantment.SHARPNESS, 1));
            z.customName(mm("<gold>Золотой страж")); z.setCustomNameVisible(false);
        });
    }

    private Skeleton skeleton(Location at) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return world.spawn(at, Skeleton.class, s -> {
            base(s, 2, cfgD("mobs.level2.health", 24));
            var eq = s.getEquipment();
            eq.setHelmet(gear(Material.IRON_HELMET, Enchantment.PROTECTION, r.nextInt(1, 3))); eq.setChestplate(gear(Material.IRON_CHESTPLATE, Enchantment.PROTECTION, r.nextInt(1, 3)));
            eq.setLeggings(gear(Material.IRON_LEGGINGS, Enchantment.PROTECTION, r.nextInt(1, 3))); eq.setBoots(gear(Material.IRON_BOOTS, Enchantment.PROTECTION, r.nextInt(1, 3)));
            eq.setItemInMainHand(gear(Material.BOW, Enchantment.POWER, r.nextInt(1, 3)));
            s.customName(mm("<gray>Костяной лучник")); s.setCustomNameVisible(false);
        });
    }

    // ===================== босс =====================
    private void spawnBoss() {
        Lvl l = lv.get(3);
        int n = Math.max(1, online().size());
        double hp = Math.min(1024, cfgD("boss.health", 300) + cfgD("boss.health-per-player", 120) * (n - 1));
        bossMax = hp;
        enraged = false;
        world.strikeLightningEffect(l.boss());
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l.boss(), 3, 1, 1, 1);
        boss = world.spawn(l.boss(), PiglinBrute.class, b -> {
            b.setImmuneToZombification(true);
            base(b, 3, hp);
            set(b, Attribute.SCALE, cfgD("boss.scale", 1.7));
            set(b, Attribute.ATTACK_DAMAGE, cfgD("boss.damage", 10));
            set(b, Attribute.KNOCKBACK_RESISTANCE, 0.85);
            set(b, Attribute.ARMOR, cfgD("boss.armor", 12));
            set(b, Attribute.MOVEMENT_SPEED, 0.33);
            set(b, Attribute.FOLLOW_RANGE, 48);
            var eq = b.getEquipment();
            eq.setHelmet(gear(Material.NETHERITE_HELMET, Enchantment.PROTECTION, 3));
            eq.setChestplate(gear(Material.GOLDEN_CHESTPLATE, Enchantment.PROTECTION, 3));
            eq.setItemInMainHand(gear(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 2));
            b.customName(mm("<gradient:#ff2a2a:#ffb000><bold>Дьявол</bold></gradient>"));
            b.setCustomNameVisible(true);
        });
        nextSkill = System.currentTimeMillis() + 6000;
        for (Player p : online()) {
            p.showTitle(Title.title(mm("<dark_red><bold>☠ ДЬЯВОЛ ПРОБУДИЛСЯ</bold>"), mm("<gray>Убей его - получишь ключ к выходу"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.6f);
        }
    }

    private void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    private void bossTick(long now) {
        if (boss == null) return;
        if (!boss.isValid()) { if (state == 3 && !boss.isDead()) { boss = null; spawnBoss(); } return; }
        Lvl l = lv.get(3);
        if (boss.getLocation().distanceSquared(l.center()) > (l.radius() + 2) * (l.radius() + 2) || boss.getLocation().getY() < l.center().getY() - 3) boss.teleport(l.boss());
        List<Player> ps = online();
        if (boss instanceof Mob m && (!(m.getTarget() instanceof Player t) || !players.contains(t.getUniqueId()) || t.isDead())) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : ps) { double d = p.getLocation().distanceSquared(boss.getLocation()); if (d < bd) { bd = d; best = p; } }
            if (best != null) m.setTarget(best);
        }
        if (!enraged && boss.getHealth() < bossMax * 0.35) {
            enraged = true;
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
            world.spawnParticle(Particle.FLAME, boss.getLocation().add(0, 1.5, 0), 120, 1, 1.5, 1, 0.15);
            for (Player p : ps) { p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.6f); p.sendMessage(mm("<dark_red><bold>Дьявол в ярости!</bold> <gray>Он стал быстрее и сильнее.")); }
        }
        if (now >= nextSkill && !ps.isEmpty()) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            switch (r.nextInt(enraged ? 4 : 3)) {
                case 0 -> fireballs(ps);
                case 1 -> slam();
                case 2 -> minions();
                default -> flameRing();
            }
            nextSkill = now + (enraged ? r.nextInt(4000, 6500) : r.nextInt(6000, 9500));
        }
    }

    private void fireballs(List<Player> ps) {
        List<Player> t = new ArrayList<>(ps); Collections.shuffle(t);
        world.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        for (int i = 0; i < Math.min(3, t.size()); i++) {
            Player p = t.get(i);
            for (int k = 0; k < 3; k++) {
                int d = k * 6;
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (boss == null || !boss.isValid() || !p.isOnline() || !p.getWorld().equals(world)) return;
                    Location eye = boss.getEyeLocation();
                    Vector v = p.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
                    SmallFireball f = world.spawn(eye.clone().add(v.clone().multiply(1.2)), SmallFireball.class, fb -> { fb.setShooter(boss); fb.setIsIncendiary(false); fb.setDirection(v); fb.setVelocity(v.clone().multiply(1.1)); });
                    f.setPersistent(false);
                }, d);
            }
        }
    }

    private void slam() {
        boss.setVelocity(new Vector(0, 1.05, 0));
        world.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.4f, 0.7f);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (boss == null || !boss.isValid()) return;
            Location c = boss.getLocation();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2, 1, 0.2, 1);
            world.spawnParticle(Particle.LAVA, c, 40, 3, 0.3, 3, 0);
            world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.6f);
            double dmg = cfgD("boss.slam-damage", 7);
            for (Player p : online()) {
                if (p.getLocation().distanceSquared(c) > 36) continue;
                p.damage(dmg, boss);
                Vector v = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (v.lengthSquared() < 0.01) v = new Vector(1, 0, 0);
                p.setVelocity(v.normalize().multiply(1.1).setY(0.55));
            }
        }, 20);
    }

    private void minions() {
        long alive = mobs.stream().filter(m -> m.isValid() && !m.isDead()).count();
        int cap = cfg("boss.minions-max", 6);
        if (alive >= cap) { flameRing(); return; }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        world.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.4f, 0.6f);
        for (int i = 0; i < Math.min(3, cap - alive); i++) {
            double a = r.nextDouble(Math.PI * 2);
            Location at = boss.getLocation().add(Math.cos(a) * 3, 0.2, Math.sin(a) * 3);
            if (!at.getBlock().isPassable()) at = boss.getLocation();
            WitherSkeleton w = world.spawn(at, WitherSkeleton.class, s -> {
                base(s, 3, cfgD("boss.minion-health", 24));
                s.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                s.customName(mm("<dark_gray>Прислужник Дьявола")); s.setCustomNameVisible(false);
            });
            mobs.add(w);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 30, 0.3, 0.7, 0.3, 0.05);
        }
    }

    private void flameRing() {
        Location c = boss.getLocation();
        world.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.5f);
        for (int i = 0; i < 48; i++) { double a = Math.PI * 2 * i / 48; for (double rr = 2; rr <= 8; rr += 2) world.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr), 1, 0, 0.1, 0, 0.01); }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (boss == null || !boss.isValid()) return;
            for (Player p : online()) if (p.getLocation().distanceSquared(boss.getLocation()) <= 64) { p.damage(cfgD("boss.ring-damage", 5), boss); p.setFireTicks(80); }
        }, 15);
    }

    private void bossKilled(Player killer) {
        state = 4;
        Location at = boss.getLocation();
        boss = null;
        for (LivingEntity e : mobs) e.remove();
        mobs.clear();
        openExit();
        MoreEvents.firework(pl, at.clone().add(0, 2, 0), Color.RED);
        MoreEvents.firework(pl, at.clone().add(0, 3, 0), Color.ORANGE);
        int bonus = cfg("hearts.boss", 0);
        for (Player p : online()) {
            give(p, key());
            for (ItemStack it : pl.events().rarityBonus("events.dungeon.boss-bonus", runTier)) give(p, it);
            if (bonus > 0) { give(p, heart(run, bonus)); earned.merge(p.getUniqueId(), bonus, Integer::sum); }
            p.showTitle(Title.title(mm("<gradient:#ffb000:#ff2a2a><bold>Дьявол повержен!</bold></gradient>"), mm("<gray>Ключ у тебя - войди в портал за троном"), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(600))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
        }
        Bukkit.broadcast(mm(pre() + "<gold>☠ Дьявол повержен" + (killer != null ? " игроком <white>" + killer.getName() : "") + "<gold>! <gray>Игроки выходят из подземелья с сердцами."));
    }

    // ===================== тики =====================
    private void tick() {
        try { holo(); } catch (Exception ignored) {}
        if (!sealedInit && state == 0 && ready) { sealedInit = true; try { setPortal(false); } catch (Exception ignored) {} }
        if (state == 0) return;
        long now = System.currentTimeMillis();
        try {
            players.removeIf(u -> Bukkit.getPlayer(u) == null);
            if (state == 1) quota = Math.max(quota, levelQuota(1, players.size()));
            boolean done = (state == 1 || state == 2) && killed >= quota;
            long minOpen = cfg("quota.min-portal-seconds", 60) * 1000L;
            if (state == 1 && (now >= levelEnd || done && now - levelStart >= minOpen)) {
                setPortal(false);
                if (online().isEmpty()) { finish("<gray>Никто не вошёл - портал закрылся."); return; }
                if (!done) { failQuota(); return; }
                Bukkit.broadcast(mm(pre() + MoreEvents.name("dungeon") + " <dark_gray>- <gray>квота уровня 1 выполнена, портал закрылся, внутри <white>" + players.size() + " <gray>игр. Уровень 2!"));
                goLevel(2);
            } else if (state == 2 && (done || now >= levelEnd)) {
                if (!done) { failQuota(); return; }
                goLevel(3);
            }
            else if (state >= 3 && now >= end) { timeout(); return; }
            if (state == 1 && ThreadLocalRandom.current().nextInt(5) == 0) setPortal(true);
            maintainMobs(now);
            Lvl l = lv.get(Math.min(3, state));
            if (l != null) for (LivingEntity m : mobs) if (m.isValid() && !l.bounds().clone().expand(3).contains(m.getLocation().toVector())) {
                if (l.mobs().isEmpty()) m.remove(); else m.teleport(l.mobs().get(ThreadLocalRandom.current().nextInt(l.mobs().size())));
            }
            barTick(now);
            checkEmpty();
        } catch (Exception ex) { pl.getLogger().warning("Dungeon: " + ex); ex.printStackTrace(); }
    }

    private void fastTick() {
        try { portalFx(); } catch (Exception ignored) {}
        if (state == 0) return;
        long now = System.currentTimeMillis();
        try {
            if (state == 3) bossTick(now);
            Lvl l = lv.get(Math.min(3, state));
            if (l == null) return;
            for (Player p : online()) {
                if (p.getLocation().getY() < l.bounds().getMinY() - 8) { p.teleport(l.spawn()); p.setFallDistance(0); continue; }
                if (state == 4 && l.exit() != null && l.exit().clone().expand(0.4).contains(p.getLocation().toVector())) {
                    boolean has = false;
                    for (ItemStack it : p.getInventory().getContents()) if (isKey(it)) { has = true; break; }
                    if (has) exit(p); else warn(p, "<red>Нужен Ключ дьявола.");
                }
            }
        } catch (Exception ex) { pl.getLogger().warning("Dungeon: " + ex); ex.printStackTrace(); }
    }

    private void barTick(long now) {
        if (bar == null) return;
        switch (state) {
            case 1, 2 -> {
                bar.name(mm(MoreEvents.name("dungeon") + " <dark_gray>| <white>Уровень " + state + " <dark_gray>| <gray>мобы <white>" + killed + "<gray>/" + quota
                        + " <dark_gray>| <gray>осталось <white>" + MoreEvents.mmss(levelEnd - now)));
                bar.progress(clamp(quota <= 0 ? 0 : 1f - killed / (float) quota));
                bar.color(killed >= quota ? BossBar.Color.GREEN : BossBar.Color.RED);
            }
            case 3 -> {
                double hp = boss != null && boss.isValid() ? boss.getHealth() : bossMax;
                bar.name(mm("<gradient:#ff2a2a:#ffb000><bold>Дьявол</bold></gradient> <red>❤ <white>" + (int) Math.ceil(hp) + "<gray>/" + (int) bossMax + (enraged ? " <dark_red><bold>ЯРОСТЬ</bold>" : "") + " <dark_gray>| <gray>время <white>" + MoreEvents.mmss(end - now)));
                bar.progress(clamp((float) (hp / Math.max(1, bossMax))));
                bar.color(enraged ? BossBar.Color.RED : BossBar.Color.PURPLE);
            }
            default -> {
                bar.name(mm("<green>Дьявол повержен! <gray>Войди в портал за троном <dark_gray>| <white>" + MoreEvents.mmss(end - now)));
                bar.progress(clamp((end - now) / (float) lvlMs()));
                bar.color(BossBar.Color.GREEN);
            }
        }
        for (Player p : online()) p.showBossBar(bar);
    }
    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    // ===================== Посейдон =====================
    private static final class PoseidonMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }
    private static final Material[] ICONS = {Material.PRISMARINE_SHARD, Material.NAUTILUS_SHELL, Material.CONDUIT, Material.TRIDENT};
    private static final String[] GIFTS = {"Дар прибоя", "Дар глубин", "Дар шторма", "Дар Посейдона"};
    private static final int[] SLOTS = {10, 12, 14, 16};

    private int price(Events.Tier t) { int[] d = {10, 25, 35, 60}; return Math.max(1, pl.getConfig().getInt("events.dungeon.poseidon." + t.id, d[t.ordinal()])); }
    private int giftMin() { return Math.max(1, pl.getConfig().getInt("events.dungeon.poseidon.items-min", 2)); }
    private int giftMax() { return Math.max(giftMin(), pl.getConfig().getInt("events.dungeon.poseidon.items-max", 3)); }
    /** Дар Посейдона: items-min..items-max случайных предметов из лута этой редкости (не весь сундук ивента). */
    private List<ItemStack> gift(Events.Tier t) {
        List<ItemStack> all = new ArrayList<>(pl.events().tierLoot(t, "dungeon"));
        Collections.shuffle(all);
        int n = ThreadLocalRandom.current().nextInt(giftMin(), giftMax() + 1);
        return new ArrayList<>(all.subList(0, Math.min(n, all.size())));
    }
    private boolean tradeable(ItemStack it) {
        if (it == null || it.getType() != Material.HEART_OF_THE_SEA) return false;
        String t = heartTag(it);
        return "ok".equals(t) || t == null && pl.getConfig().getBoolean("events.dungeon.poseidon.accept-vanilla", false);
    }
    private int count(Player p) { int n = 0; for (ItemStack it : p.getInventory().getContents()) if (tradeable(it)) n += it.getAmount(); return n; }
    private void take(Player p, int n) {
        var inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length && n > 0; i++) {
            if (!tradeable(c[i])) continue;
            int k = Math.min(n, c[i].getAmount());
            n -= k;
            if (k >= c[i].getAmount()) inv.setItem(i, null); else { c[i].setAmount(c[i].getAmount() - k); inv.setItem(i, c[i]); }
        }
    }

    public void poseidon(Player p) {
        boolean admin = p.isOp() || p.hasPermission("anarchy.admin");
        Location np = PlayerData.locFromString(pl.getConfig().getString("npcs.poseidon.location", ""));
        if (!admin && (np == null || !p.getWorld().equals(np.getWorld()) || p.getLocation().distanceSquared(np) > 100)) {
            pl.msg(p, "<gray>Посейдон ждёт на спавне - у портала Подземелья дьявола. Подойди к нему и нажми ПКМ.");
            return;
        }
        if (inDungeon(p)) { pl.msg(p, "<red>Сначала выберись из подземелья."); return; }
        PoseidonMenu h = new PoseidonMenu();
        Inventory inv = Bukkit.createInventory(h, 27, mm("<gradient:#5ef2ff:#1e7bff><bold>Посейдон</bold></gradient> <dark_gray>- обмен сердец"));
        h.inv = inv;
        int have = count(p);
        ItemStack glass = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.setHideTooltip(true); glass.setItemMeta(gm);
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);
        for (Events.Tier t : Events.Tier.values()) {
            int pr = price(t);
            ItemStack it = new ItemStack(ICONS[t.ordinal()]);
            ItemMeta m = it.getItemMeta();
            m.displayName(mm("<!i>" + t.grad + "<bold>" + GIFTS[t.ordinal()] + "</bold></gradient>"));
            String cnt = giftMin() == giftMax() ? String.valueOf(giftMin()) : giftMin() + "-" + giftMax();
            m.lore(List.of(mm("<!i><gray>" + cnt + " случайных предмета из лута редкости"), mm("<!i>" + t.tag()), mm("<!i> "),
                    mm("<!i><gray>Цена: <aqua>" + pr + " ❤ <gray>Сердец моря"),
                    mm(have >= pr ? "<!i><green>▶ ЛКМ - обменять" : "<!i><red>Не хватает " + (pr - have) + " ❤")));
            m.setEnchantmentGlintOverride(have >= pr);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            it.setItemMeta(m);
            inv.setItem(SLOTS[t.ordinal()], it);
        }
        ItemStack info = heart("ok", Math.max(1, Math.min(64, have)));
        ItemMeta im = info.getItemMeta();
        im.displayName(mm("<!i><aqua>У тебя сердец: <white>" + have));
        im.lore(List.of(mm("<!i><gray>Сердца моря добываются в"), mm("<!i>" + MoreEvents.name("dungeon")), mm("<!i><gray>Выйди с ключом - иначе сердца сгорят")));
        info.setItemMeta(im);
        inv.setItem(22, info);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_ENTER, 0.6f, 1.2f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PoseidonMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int idx = -1;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == e.getRawSlot()) idx = i;
        if (idx < 0) return;
        Events.Tier t = Events.Tier.values()[idx];
        int pr = price(t);
        if (count(p) < pr) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f); pl.msg(p, "<red>Нужно " + pr + " ❤ Сердец моря."); return; }
        take(p, pr);
        List<ItemStack> loot = gift(t);
        for (ItemStack it : loot) give(p, it);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        pl.msg(p, "<aqua>Посейдон принял " + pr + " ❤ <gray>и даровал тебе " + t.tag(GIFTS[idx].toLowerCase(Locale.ROOT).replace("дар", "дар")) + " <gray>(" + loot.size() + " предм.)");
        if (t.ordinal() >= Events.Tier.EPIC.ordinal()) MoreEvents.firework(pl, p.getLocation().add(0, 2, 0), t.color);
        if (t == Events.Tier.DIVINE) Bukkit.broadcast(mm(pre() + "<white>" + p.getName() + " <aqua>получил " + t.grad + "<bold>" + GIFTS[idx] + "</bold></gradient> <aqua>от Посейдона!"));
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) poseidon(p); });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof PoseidonMenu) e.setCancelled(true); }

    // ===================== правила мира подземелья =====================
    private boolean bypass(Player p) { return p != null && p.isOp() && p.getGameMode() == GameMode.CREATIVE; }
    private boolean dw(World w) { return isDungeon(w); }
    private static Player shooter(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (d instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }
    private boolean ourMob(Entity e) { return e != null && e.getPersistentDataContainer().has(MOB, PersistentDataType.INTEGER); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        if (!ourMob(m)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Player k = m.getKiller();
        if (m == boss) { bossKilled(k); return; }
        Integer lvl = m.getPersistentDataContainer().get(MOB, PersistentDataType.INTEGER);
        if (lvl != null && lvl <= 2 && state == lvl) {
            killed++;
            if (killed == quota) for (Player p : online()) { p.sendActionBar(mm("<green><bold>Квота уровня выполнена!</bold> <gray>(" + killed + "/" + quota + ")")); p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f); }
        }
        if (k == null || !players.contains(k.getUniqueId()) || lvl == null || lvl > 2 || state != lvl) return;
        int n = cfg("hearts.level" + lvl, lvl);
        if (n <= 0) return;
        Item it = m.getWorld().dropItemNaturally(m.getLocation().add(0, 0.5, 0), heart(run, n));
        it.setOwner(k.getUniqueId());
        it.setGlowing(true);
        it.setUnlimitedLifetime(true);
        it.setCanMobPickup(false);
        earned.merge(k.getUniqueId(), n, Integer::sum);
        k.sendActionBar(mm("<aqua>+" + n + " ❤ Сердце моря <dark_gray>| <gray>за забег: <white>" + earned.get(k.getUniqueId())));
        k.playSound(k.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!dw(e.getEntity().getWorld())) return;
        Entity v = e.getEntity(), d = e.getDamager();
        if (v instanceof Player && shooter(d) != null && !shooter(d).equals(v)) { e.setCancelled(true); return; }   // без PvP
        if (ourMob(v)) {
            Entity src = d instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : d;
            if (ourMob(src)) e.setCancelled(true);   // мобы не бьют друг друга
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent e) {
        Entity v = e.getEntity();
        if (!dw(v.getWorld())) return;
        if (ourMob(v)) {
            switch (e.getCause()) {
                case FALL, FIRE, FIRE_TICK, LAVA, SUFFOCATION, DROWNING, HOT_FLOOR, CONTACT, BLOCK_EXPLOSION, ENTITY_EXPLOSION, CRAMMING, FREEZE -> e.setCancelled(true);
                default -> {}
            }
            return;
        }
        if (v instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.VOID && players.contains(p.getUniqueId())) {
            e.setCancelled(true);
            Lvl l = lv.get(Math.max(1, Math.min(3, state)));
            if (l != null) { p.teleport(l.spawn()); p.setFallDistance(0); }
        }
    }

    private static final Set<PotionEffectType> BAD = Set.of(PotionEffectType.INSTANT_DAMAGE, PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
            PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA, PotionEffectType.MINING_FATIGUE, PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION, PotionEffectType.DARKNESS, PotionEffectType.GLOWING, PotionEffectType.UNLUCK, PotionEffectType.SLOW_FALLING);

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!dw(e.getEntity().getWorld()) || !(e.getEntity().getShooter() instanceof Player s)) return;
        if (e.getPotion().getEffects().stream().noneMatch(x -> BAD.contains(x.getType()))) return;
        for (LivingEntity le : e.getAffectedEntities()) if (le instanceof Player o && !o.equals(s)) e.setIntensity(le, 0);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        if (!dw(e.getEntity().getWorld()) || !(e.getEntity().getSource() instanceof Player s)) return;
        e.getAffectedEntities().removeIf(le -> le instanceof Player o && !o.equals(s));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucket(PlayerBucketEmptyEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIgnite(BlockIgniteEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPlace(EntityPlaceEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHanging(HangingPlaceEvent e) { if (dw(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) { if (dw(e.getLocation().getWorld())) e.blockList().clear(); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) { if (dw(e.getBlock().getWorld())) e.blockList().clear(); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(BlockBurnEvent e) { if (dw(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFade(BlockFadeEvent e) { if (dw(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpread(BlockSpreadEvent e) { if (dw(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onForm(BlockFormEvent e) { if (dw(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFlow(BlockFromToEvent e) { if (dw(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChange(EntityChangeBlockEvent e) { if (dw(e.getBlock().getWorld()) && !(e.getEntity() instanceof Player pp && bypass(pp))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent e) { if (dw(e.getFrom().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent e) { if (dw(e.getFrom().getWorld())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalCreate(PortalCreateEvent e) { if (dw(e.getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!dw(e.getLocation().getWorld())) return;
        var r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.CUSTOM && r != CreatureSpawnEvent.SpawnReason.COMMAND && r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!dw(e.getPlayer().getWorld()) || bypass(e.getPlayer()) || e.getClickedBlock() == null) return;
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobTarget(EntityTargetLivingEntityEvent e) { if (ourMob(e.getEntity()) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) { if (!(e.getEntity() instanceof Player) && heartTag(e.getItem().getItemStack()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!dw(e.getEntity().getWorld())) return;
        e.setKeepInventory(true); e.getDrops().clear(); e.setKeepLevel(true); e.setDroppedExp(0);
        Player p = e.getEntity();
        if (state == 0 || !players.contains(p.getUniqueId())) return;
        // погиб - выбывает из забега: возрождение на спавне, сердца забега сгорают, войти снова нельзя до следующего подземелья
        fallen.add(p.getUniqueId());
        int n = process(p, 0);
        out(p);
        pl.msg(p, "<red>Ты погиб в подземелье и выбыл из забега." + (n > 0 ? " Сердца забега сгорели: <white>" + n + "<red>." : "") + " <gray>Войти снова можно в следующем подземелье.");
        for (Player o : online()) pl.msg(o, "<gray>" + p.getName() + " <red>погиб и выбыл из забега.");
        Bukkit.getScheduler().runTask(pl, this::checkEmpty);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (players.contains(p.getUniqueId()) && state != 0) {
            Lvl l = lv.get(Math.max(1, Math.min(3, state)));
            if (l != null) e.setRespawnLocation(l.spawn());
            Bukkit.getScheduler().runTask(pl, () -> { if (bar != null && p.isOnline()) p.showBossBar(bar); });
        } else if (dw(p.getWorld())) e.setRespawnLocation(outLoc());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        boolean toD = dw(e.getTo().getWorld()), fromD = dw(e.getFrom().getWorld());
        if (fromD && toD && players.contains(p.getUniqueId())) {
            var c = e.getCause();
            if (c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) { e.setCancelled(true); warn(p, "<red>В подземелье телепорты не работают."); }
            return;
        }
        if (toD && !fromD && !players.contains(p.getUniqueId()) && !p.isOp()) { e.setCancelled(true); pl.msg(p, "<red>В подземелье можно попасть только через портал на спавне во время ивента."); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!dw(e.getFrom()) || exiting.contains(p.getUniqueId()) || !players.contains(p.getUniqueId())) return;
        int n = process(p, 0);
        players.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        pl.msg(p, "<gray>Ты покинул подземелье." + (n > 0 ? " <red>Сердца забега сгорели: <white>" + n : ""));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        warnCd.remove(p.getUniqueId());
        if (!players.remove(p.getUniqueId())) return;
        process(p, 0);
        if (bar != null) p.hideBossBar(bar);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline() || players.contains(p.getUniqueId())) return;
            boolean stray = false;
            for (ItemStack it : p.getInventory().getContents()) { String t = heartTag(it); if (isKey(it) || t != null && !t.equals("ok")) { stray = true; break; } }
            if (stray) { int n = process(p, 0); if (n > 0) pl.msg(p, "<gray>Сердца незавершённого забега сгорели: <white>" + n); }
        }, 5);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!dw(p.getWorld()) || p.isOp()) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (c.contains(":")) c = c.substring(c.indexOf(':') + 1);
        List<String> ok = pl.getConfig().getStringList("events.dungeon.allowed-commands");
        if (ok.isEmpty()) ok = List.of("event", "events", "msg", "r", "tell", "w", "m", "reply", "cc", "l", "login");
        if (!ok.contains(c)) { e.setCancelled(true); pl.msg(p, "<red>В подземелье команды недоступны. Выйти (сердца сгорят): <yellow>/event leave"); }
    }
}
