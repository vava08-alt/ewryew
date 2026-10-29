package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Tag;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;

/**
 * PvP-варп: отдельный мир с зелёной ареной (холмы, деревья, вышки, постройки), лут-шалкерами по всей карте,
 * ямой в центре, которая ведёт в подземный «ад» с двумя проходами к залу элитного шалкера.
 * Элитный шалкер появляется по расписанию и ломается за N ударов киркой.
 */
public final class PvpArena implements Listener {
    static final String WORLD = "pvp";
    private static final int VERSION = 4, HUB_VERSION = 2;
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final int PIT_R = 4, CAVE_FLOOR = 20;
    private static final int CH_X = 0, CH_Z = -44;   // зал элитного шалкера (под храмом)

    private final AnarchyCore pl;
    private World world;
    private final File stateFile;
    private YamlConfiguration state;
    /** Точка лут-шалкера: ломается ударами, после поломки появляется снова через КД своей редкости. */
    private static final class Spot {
        final int x, y, z; final String rarity;
        int hits; long readyAt, lastHit;
        TextDisplay holo;
        Spot(int x, int y, int z, String rarity) { this.x = x; this.y = y; this.z = z; this.rarity = rarity; }
    }
    private final List<Spot> spots = new ArrayList<>();
    private final Map<Long, Spot> spotAt = new HashMap<>();
    private final List<String> spotDefs = new ArrayList<>();
    private static final String[] RARITIES = {"common", "rare", "epic", "legendary"};
    private final Map<Long, Long> temp = new HashMap<>();
    private final Map<UUID, Long> pitFall = new HashMap<>();
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Set<Long> tickets = new HashSet<>();
    private Location spawn;
    private int altarY = CAVE_FLOOR + 4;
    private boolean ready;

    private Block elite;
    private int eliteLeft, eliteTotal;
    private long eliteUntil, eliteStart;
    private TextDisplay eliteHolo;
    private BossBar eliteBar;
    private final Map<UUID, Integer> eliteHits = new HashMap<>();
    private final Map<UUID, Long> hitCd = new HashMap<>();
    /** Клетки стены безопасной зоны, которые сейчас показаны игроку (только ему, на клиенте). */
    private final Map<UUID, Set<Long>> wallShown = new HashMap<>();
    private List<int[]> wallRing;
    private static final BlockData WALL = Bukkit.createBlockData(Material.RED_STAINED_GLASS);
    private String lastSlot = "", warnedSlot = "";

    public PvpArena(AnarchyCore pl) {
        this.pl = pl;
        stateFile = new File(pl.getDataFolder(), "pvp-arena.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
    }

    private org.bukkit.configuration.ConfigurationSection cfg() {
        org.bukkit.configuration.ConfigurationSection s = pl.getConfig().getConfigurationSection("pvp-arena");
        return s != null ? s : new YamlConfiguration();
    }

    public boolean isArena(World w) { return w != null && world != null && w.equals(world); }

    // =====================================================================
    //                              ЗАПУСК
    // =====================================================================
    public void init() {
        if (!cfg().getBoolean("enabled", true)) return;
        try {
            world = Bukkit.getWorld(WORLD);
            if (world == null) world = new WorldCreator(WORLD).environment(World.Environment.NORMAL).generator(new Worlds.Void())
                    .biomeProvider(new Biomes()).generateStructures(false).createWorld();
            if (world == null) return;
            rule(GameRules.ADVANCE_TIME, false); rule(GameRules.ADVANCE_WEATHER, false);
            rule(GameRules.SPAWN_MOBS, false); rule(GameRules.SPAWN_MONSTERS, false); rule(GameRules.SPAWN_PATROLS, false);
            rule(GameRules.SPAWN_WANDERING_TRADERS, false); rule(GameRules.SPAWN_PHANTOMS, false); rule(GameRules.SPAWN_WARDENS, false);
            rule(GameRules.MOB_GRIEFING, false); rule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0); rule(GameRules.RANDOM_TICK_SPEED, 0);
            rule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false); rule(GameRules.PVP, true); rule(GameRules.KEEP_INVENTORY, false);
            world.setTime(6000);
            world.setStorm(false);
            if (state.getInt("arena-version") != VERSION) buildArena();
            loadSpots();
            altarY = state.getInt("altar-y", altarY);
            spawn = new Location(world, 0.5, state.getInt("spawn-y", 67), 58.5, 180, 0);
            world.setSpawnLocation(spawn);
            for (int cx = -6; cx <= 5; cx++) for (int cz = -6; cz <= 5; cz++) hold(cx * 16, cz * 16);   // остров целиком загружен: шалкеры и голограммы живут всегда
            spawnHolos();
            for (Spot sp : spots) { spotHolo(sp); holoReady(sp); }
            ready = true;
            if (state.getInt("hub-version") != HUB_VERSION) Bukkit.getScheduler().runTaskLater(pl, this::buildHub, 60);
            Bukkit.getScheduler().runTaskTimer(pl, this::second, 20, 20);
        } catch (Exception ex) {
            pl.getLogger().warning("PvP-арена не запущена: " + ex);
            ex.printStackTrace();
        }
    }

    public void shutdown() {
        removeElite(false);
        for (Spot sp : spots) if (sp.holo != null && sp.holo.isValid()) sp.holo.remove();
        for (TextDisplay t : holos) if (t.isValid()) t.remove();
        holos.clear();
        if (world != null) for (long k : tickets) world.removePluginChunkTicket((int) (k >> 32), (int) k, pl);
        for (Map.Entry<Long, Long> e : temp.entrySet()) { int[] p = unkey(e.getKey()); if (world != null) world.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false); }
        temp.clear();
    }

    private <T> void rule(GameRule<T> r, T v) { try { world.setGameRule(r, v); } catch (Exception ignored) {} }

    private void hold(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (tickets.add(((long) cx << 32) ^ (cz & 0xffffffffL))) world.addPluginChunkTicket(cx, cz, pl);
    }

    private void saveState() { try { state.save(stateFile); } catch (Exception e) { pl.getLogger().warning("pvp-arena.yml: " + e); } }

    static final class Biomes extends BiomeProvider {
        @Override public Biome getBiome(WorldInfo w, int x, int y, int z) {
            if (y >= 47) return Biome.FOREST;
            if (z < -58) return Biome.NETHER_WASTES;
            if (x < -6) return Biome.CRIMSON_FOREST;
            if (x > 6) return Biome.SOUL_SAND_VALLEY;
            return Biome.NETHER_WASTES;
        }
        @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.FOREST, Biome.NETHER_WASTES, Biome.CRIMSON_FOREST, Biome.SOUL_SAND_VALLEY); }
    }

    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    private static int[] unkey(long k) {
        int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
        if (x >= 1 << 25) x -= 1 << 26;
        if (z >= 1 << 25) z -= 1 << 26;
        if (y >= 1 << 11) y -= 1 << 12;
        return new int[]{x, y, z};
    }

    // =====================================================================
    //                           СТРОИТЕЛЬ АРЕНЫ
    // =====================================================================
    private static final int OFF = 110, SIZE = 221;
    private static final int M_PATH = 1, M_BUILD = 2, M_PLAZA = 3, M_WATER = 4, M_TREE = 5;
    private int[] H;
    private byte[] mask;
    private Random r;
    private int placed;
    private final Map<String, BlockData> bdCache = new HashMap<>();

    private int idx(int x, int z) { return (x + OFF) * SIZE + (z + OFF); }
    private boolean in(int x, int z) { return x > -OFF && x < OFF && z > -OFF && z < OFF; }
    private int h(int x, int z) { return in(x, z) ? H[idx(x, z)] : Integer.MIN_VALUE; }
    private boolean ground(int x, int z) { return h(x, z) != Integer.MIN_VALUE; }
    private int m(int x, int z) { return in(x, z) ? mask[idx(x, z)] : 0; }
    private void mark(int x, int z, int v) { if (in(x, z) && mask[idx(x, z)] == 0) mask[idx(x, z)] = (byte) v; }
    private void markForce(int x, int z, int v) { if (in(x, z)) mask[idx(x, z)] = (byte) v; }

    private BlockData bd(String s) { return bdCache.computeIfAbsent(s, Bukkit::createBlockData); }
    private void set(int x, int y, int z, Material m) { world.getBlockAt(x, y, z).setType(m, false); placed++; }
    private void set(int x, int y, int z, BlockData d) { world.getBlockAt(x, y, z).setBlockData(d, false); placed++; }
    private void set(int x, int y, int z, String data) { set(x, y, z, bd(data)); }
    private Material type(int x, int y, int z) { return world.getBlockAt(x, y, z).getType(); }
    private boolean air(int x, int y, int z) { return world.getBlockAt(x, y, z).getType().isAir(); }
    private <T> T pick(T[] a) { return a[r.nextInt(a.length)]; }
    private double rnd() { return r.nextDouble(); }

    private void leaves(int x, int y, int z, Material m) {
        if (!air(x, y, z)) return;
        BlockData d = m.createBlockData();
        if (d instanceof Leaves l) l.setPersistent(true);
        set(x, y, z, d);
    }

    private void log(int x, int y, int z, Material m, org.bukkit.Axis axis) {
        BlockData d = m.createBlockData();
        if (d instanceof Orientable o) o.setAxis(axis);
        set(x, y, z, d);
    }

    private void stairs(int x, int y, int z, Material m, BlockFace f, boolean top) {
        Stairs s = (Stairs) m.createBlockData();
        s.setFacing(f);
        s.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        set(x, y, z, s);
    }

    private void box(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
    }

    private void buildArena() {
        long t0 = System.currentTimeMillis();
        pl.getLogger().info("PvP-арена: строю карту (один раз, несколько секунд)...");
        H = new int[SIZE * SIZE];
        Arrays.fill(H, Integer.MIN_VALUE);
        mask = new byte[SIZE * SIZE];
        r = new Random(20261013L);
        placed = 0;
        spotDefs.clear();
        clearOld();
        planUnderground();
        terrain();
        ponds();
        plaza();
        pitRing();
        underground();
        mainRoad();
        towers();
        temple(0, -44);
        cabin(-48, 8);
        bunker(48, -6);
        camp(-28, 34);
        fort(26, -28);
        market(18, 16);
        shrines();
        exitWell();
        sidePaths();
        trees();
        boulders();
        flora();
        caveShulkers();
        connectFences();
        barrierRing();
        state.set("arena-version", VERSION);
        state.set("spawn-y", h(0, 58) + 1);
        state.set("altar-y", altarY);
        state.set("shulkers", new ArrayList<>(spotDefs));
        saveState();
        H = null; mask = null; keelS = null; keelD = null;
        pl.getLogger().info("PvP-арена построена: " + placed + " блоков, шалкеров " + spotDefs.size() + ", " + (System.currentTimeMillis() - t0) + " мс");
    }

    /** При перестройке: стереть старую карту (пустой мир - всё, что не воздух, наше). */
    private void clearOld() {
        if (state.getInt("arena-version") == 0) return;
        for (int x = -112; x < 112; x++) for (int z = -112; z < 112; z++) {
            for (int y = -30; y <= 130; y++) if (!air(x, y, z)) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            if (type(x, 131, z) == Material.BARRIER) for (int y = 131; y <= 319; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
    }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    private static double flatten(double h, double dist, double in, double out, double target) {
        if (dist <= in) return target;
        if (dist >= out) return h;
        return target + (h - target) * smooth((dist - in) / (out - in));
    }

    // ---------------------------- рельеф ----------------------------
    private final Set<Long> islandEdge = new HashSet<>();

    private void terrain() {
        SimplexOctaveGenerator g1 = new SimplexOctaveGenerator(11L, 4); g1.setScale(1 / 52.0);
        SimplexOctaveGenerator g2 = new SimplexOctaveGenerator(17L, 2); g2.setScale(1 / 15.0);
        SimplexOctaveGenerator g3 = new SimplexOctaveGenerator(23L, 3); g3.setScale(1.0);
        SimplexOctaveGenerator rock = new SimplexOctaveGenerator(31L, 2); rock.setScale(1 / 9.0);
        SimplexOctaveGenerator kn = new SimplexOctaveGenerator(37L, 2); kn.setScale(1 / 8.0);
        for (int x = -100; x <= 100; x++) for (int z = -100; z <= 100; z++) {
            double d = Math.hypot(x, z), ang = Math.atan2(z, x);
            double er = 76 + 8 * g3.noise(Math.cos(ang) * 1.6, Math.sin(ang) * 1.6, 0.5, 0.5, true);
            if (d > er) continue;
            double t = d / er;
            double hh = 66 + 5.5 * g1.noise(x, z, 0.5, 0.5, true) + 1.6 * g2.noise(x, z, 0.5, 0.5, true);
            double hill = g1.noise(x + 700, z - 300, 0.5, 0.5, true);
            if (hill > 0.15) hh += (hill - 0.15) * 22;
            hh = flatten(hh, d, 12, 22, 66);
            hh = flatten(hh, Math.hypot(x, z - 58), 10, 19, 66);
            if (t > 0.84) hh -= (t - 0.84) / 0.16 * 4.5;
            int top = (int) Math.round(hh);
            int depth = 5 + (int) (30 * (1 - t * t)) + (int) Math.round(3 * g2.noise(x * 3, z * 3, 0.5, 0.5, true));
            int bottom = top - Math.max(4, depth);
            // киль: под подземельем остров уходит вниз адской скалой, полости ада целиком внутри острова
            int i = idx(x, z);
            float ks = keelS[i];
            boolean keel = ks > 0;
            if (ks > -KM) {
                double deep = keelD[i] - (keel ? Math.min(16, 2 + ks * 0.7) : 0) + 2.5 * kn.noise(x, z, 0.5, 0.5, true);
                double k = ks >= 0 ? 1 : smooth((ks + KM) / KM);
                bottom = (int) Math.round(Math.min(bottom, bottom + (deep - bottom) * k));
            }
            int dirt = 3 + (g2.noise(x * 2, z * 2, 0.5, 0.5, true) > 0.3 ? 1 : 0);
            for (int y = bottom; y <= top; y++) {
                Material m;
                if (y == top) m = Material.GRASS_BLOCK;
                else if (y >= top - dirt) m = Material.DIRT;
                else m = deepRock(x, y, z, y == bottom, rock);
                set(x, y, z, m);
            }
            if (keel) {
                double q = rnd();
                if (q < 0.06) { int len = 1 + r.nextInt(4); for (int k2 = 1; k2 <= len; k2++) set(x, bottom - k2, z, k2 == len ? Material.WEEPING_VINES : Material.WEEPING_VINES_PLANT); }
                else if (q < 0.085) set(x, bottom, z, Material.SHROOMLIGHT);
                else if (q < 0.11) set(x, bottom, z, Material.MAGMA_BLOCK);
                else if (q < 0.13) { set(x, bottom - 1, z, "minecraft:pointed_dripstone[vertical_direction=down,thickness=frustum]"); set(x, bottom - 2, z, "minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]"); }
            } else if (rnd() < 0.05 && t > 0.6) set(x, bottom - 1, z, "minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]");
            else if (rnd() < 0.06) set(x, bottom - 1, z, bottom < 40 ? Material.WEEPING_VINES : Material.HANGING_ROOTS);
            H[i] = top;
            if (t > 0.9) islandEdge.add(((long) x << 32) ^ (z & 0xffffffffL));
        }
    }

    /** Порода по высоте: сверху камень, ниже 44 - всё больше адских пород (низ острова и киль выглядят как Ад). */
    private Material deepRock(int x, int y, int z, boolean bottom, SimplexOctaveGenerator rock) {
        double rn = rock.noise(x, y * 1.4, z, 0.5, 0.5, true);
        double hell = y >= 44 ? 0 : y <= 30 ? 1 : (44 - y) / 14.0;
        if (hell > 0 && rnd() < hell) {
            double q = rnd();
            if (q < 0.025) return Material.NETHER_GOLD_ORE;
            if (q < 0.05) return Material.NETHER_QUARTZ_ORE;
            if (rn > 0.5) return Material.BLACKSTONE;
            if (rn < -0.55) return Material.BASALT;
            if (q < 0.09) return Material.MAGMA_BLOCK;
            return Material.NETHERRACK;
        }
        return rn > 0.55 ? Material.ANDESITE : rn < -0.6 ? Material.TUFF : bottom && rnd() < 0.3 ? Material.COBBLESTONE : Material.STONE;
    }

    private void setTop(int x, int z, int y, Material topMat) {
        int cur = h(x, z);
        if (cur == Integer.MIN_VALUE) return;
        if (cur > y) for (int yy = y + 1; yy <= cur; yy++) set(x, yy, z, Material.AIR);
        else if (cur < y) for (int yy = cur; yy < y; yy++) set(x, yy, z, Material.DIRT);
        set(x, y, z, topMat);
        H[idx(x, z)] = y;
    }

    /** Ровная площадка под постройку, края плавно сходят в рельеф. */
    private int pad(int cx, int cz, int rx, int rz, int margin) {
        long sum = 0; int n = 0;
        for (int x = cx - rx; x <= cx + rx; x++) for (int z = cz - rz; z <= cz + rz; z++) if (ground(x, z)) { sum += h(x, z); n++; }
        if (n == 0) return 66;
        int y = (int) Math.round(sum / (double) n);
        for (int x = cx - rx - margin; x <= cx + rx + margin; x++) for (int z = cz - rz - margin; z <= cz + rz + margin; z++) {
            if (!ground(x, z)) continue;
            int dx = Math.max(0, Math.abs(x - cx) - rx), dz = Math.max(0, Math.abs(z - cz) - rz);
            double d = Math.hypot(dx, dz);
            int ty = d <= 0 ? y : (int) Math.round(y + (h(x, z) - y) * smooth(d / (margin + 1)));
            if (ty != h(x, z)) setTop(x, z, ty, Material.GRASS_BLOCK);
            if (d <= 0) mark(x, z, M_BUILD);
        }
        return y;
    }

    // ---------------------------- пруды ----------------------------
    private void ponds() {
        pond(27, 27, 6, 5);
        pond(-30, -24, 5, 6);
        pond(-12, 50, 3, 3);
    }

    private void pond(int cx, int cz, int rx, int rz) {
        int wy = Integer.MAX_VALUE;
        for (int x = cx - rx; x <= cx + rx; x++) for (int z = cz - rz; z <= cz + rz; z++) if (ground(x, z)) wy = Math.min(wy, h(x, z));
        if (wy == Integer.MAX_VALUE) return;
        wy = wy - 1;
        for (int x = cx - rx - 2; x <= cx + rx + 2; x++) for (int z = cz - rz - 2; z <= cz + rz + 2; z++) {
            if (!ground(x, z)) continue;
            double e = Math.pow((x - cx) / (rx + 0.5), 2) + Math.pow((z - cz) / (rz + 0.5), 2);
            if (e <= 1.0) {
                int depth = e < 0.35 ? 3 : e < 0.7 ? 2 : 1;
                for (int y = wy + 1; y <= h(x, z); y++) set(x, y, z, Material.AIR);
                for (int y = wy - depth + 1; y <= wy; y++) set(x, y, z, Material.WATER);
                set(x, wy - depth, z, rnd() < 0.5 ? Material.CLAY : Material.SAND);
                H[idx(x, z)] = wy - depth;
                markForce(x, z, M_WATER);
                if (e < 0.8 && rnd() < 0.08) set(x, wy + 1, z, Material.LILY_PAD);
            } else if (e <= 1.6) {
                setTop(x, z, Math.min(h(x, z), wy), rnd() < 0.35 ? Material.SAND : Material.GRASS_BLOCK);
                mark(x, z, M_WATER);
                if (rnd() < 0.25 && type(x, h(x, z), z) != Material.WATER && waterNear(x, wy, z)) {
                    int hgt = 1 + r.nextInt(3);
                    for (int i = 1; i <= hgt; i++) set(x, h(x, z) + i, z, Material.SUGAR_CANE);
                }
            }
        }
    }

    private boolean waterNear(int x, int y, int z) {
        return type(x + 1, y, z) == Material.WATER || type(x - 1, y, z) == Material.WATER || type(x, y, z + 1) == Material.WATER || type(x, y, z - 1) == Material.WATER;
    }

    // ---------------------------- площадка прибытия ----------------------------
    private void plaza() {
        int y = 66;
        for (int x = -10; x <= 10; x++) for (int z = 48; z <= 68; z++) {
            if (!ground(x, z)) continue;
            double d = Math.hypot(x, z - 58);
            if (d <= 8.5) {
                setTop(x, z, y, Material.STONE_BRICKS);
                Material m = d <= 2.6 ? Material.CHISELED_STONE_BRICKS : d <= 3.6 ? Material.POLISHED_ANDESITE
                        : rnd() < 0.18 ? Material.MOSSY_STONE_BRICKS : rnd() < 0.12 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
                if (d > 7.6) m = Material.POLISHED_DEEPSLATE;
                set(x, y, z, m);
                markForce(x, z, M_PLAZA);
            } else if (d <= 11) mark(x, z, M_PLAZA);
        }
        // фонари по кругу
        int[][] posts = {{6, 52}, {-6, 52}, {6, 64}, {-6, 64}};
        for (int[] p : posts) {
            set(p[0], y + 1, p[1], Material.STONE_BRICK_WALL);
            set(p[0], y + 2, p[1], Material.STONE_BRICK_WALL);
            set(p[0], y + 3, p[1], Material.LANTERN);
        }
        // скамейки
        for (int x = -2; x <= 2; x++) { stairs(x, y + 1, 51, Material.SPRUCE_STAIRS, BlockFace.NORTH, false); }
        for (int z = 56; z <= 60; z++) { stairs(-7, y + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST, false); stairs(7, y + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST, false); }
        set(-7, y + 1, 55, Material.POTTED_RED_TULIP);
        set(7, y + 1, 61, Material.POTTED_BLUE_ORCHID);
        // памятник на южном краю площади: меч в камне
        int mz = 64;
        set(0, y + 1, mz, Material.CHISELED_STONE_BRICKS);
        set(0, y + 2, mz, Material.CHISELED_STONE_BRICKS);
        stairs(1, y + 1, mz, Material.STONE_BRICK_STAIRS, BlockFace.WEST, false);
        stairs(-1, y + 1, mz, Material.STONE_BRICK_STAIRS, BlockFace.EAST, false);
        stairs(0, y + 1, mz + 1, Material.STONE_BRICK_STAIRS, BlockFace.NORTH, false);
        stairs(0, y + 1, mz - 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH, false);
        for (int[] c : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) set(c[0], y + 1, mz + c[1], Material.MOSSY_STONE_BRICK_SLAB);
        for (int yy = y + 3; yy <= y + 5; yy++) set(0, yy, mz, "minecraft:iron_bars");
        set(0, y + 6, mz, Material.POLISHED_BLACKSTONE);
        set(1, y + 6, mz, "minecraft:lightning_rod[facing=east]");
        set(-1, y + 6, mz, "minecraft:lightning_rod[facing=west]");
        set(0, y + 7, mz, "minecraft:iron_chain[axis=y]");
        set(0, y + 8, mz, "minecraft:lantern[hanging=false]");
    }


    // ---------------------------- яма в центре ----------------------------
    private void pitRing() {
        int y = 66;
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            double d = Math.hypot(x, z);
            if (d > 9.5 || !ground(x, z)) continue;
            setTop(x, z, y, Material.POLISHED_BLACKSTONE_BRICKS);
            Material m;
            if (d <= PIT_R + 0.5) continue;
            else if (d <= PIT_R + 1.6) m = rnd() < 0.3 ? Material.CRYING_OBSIDIAN : Material.GILDED_BLACKSTONE;
            else if (d <= 7.5) m = rnd() < 0.25 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
            else m = rnd() < 0.5 ? Material.BLACKSTONE : rnd() < 0.5 ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
            set(x, y, z, m);
            markForce(x, z, M_PLAZA);
        }
        int[][] br = {{7, 0}, {-7, 0}, {0, 7}, {0, -7}};
        for (int[] b : br) {
            set(b[0], y + 1, b[1], Material.CHISELED_POLISHED_BLACKSTONE);
            set(b[0], y + 2, b[1], Material.SOUL_CAMPFIRE);
        }
        for (int[] b : new int[][]{{5, 5}, {-5, 5}, {5, -5}, {-5, -5}}) {
            set(b[0], y + 1, b[1], Material.POLISHED_BLACKSTONE_WALL);
            set(b[0], y + 2, b[1], Material.POLISHED_BLACKSTONE_WALL);
            set(b[0], y + 3, b[1], Material.SOUL_LANTERN);
        }
    }

    // ---------------------------- подземный ад ----------------------------
    private record Piece(double x, double z, double floor, double rx, double ry, double rz, int theme) {}
    private static final int T_LAND = 0, T_CRIMSON = 1, T_SOUL = 2, T_HALL = 3;
    private final List<Piece> pieces = new ArrayList<>();
    private final Set<Long> cave = new HashSet<>();

    private static final int KM = 13;
    private float[] keelS, keelD;
    private double[][] westPts, eastPts;

    /** Геометрия ада (до рельефа): по ней остров получает киль, внутри которого лежат полости. */
    private void planUnderground() {
        pieces.clear();
        cave.clear();
        pieces.add(new Piece(0, 0, CAVE_FLOOR, 15, 13, 15, T_LAND));
        westPts = new double[][]{{-11, -5}, {-29, -9}, {-40, -22}, {-37, -37}, {-25, -46}, {-13, -45}};
        eastPts = new double[westPts.length][];
        for (int i = 0; i < westPts.length; i++) eastPts[i] = new double[]{-westPts[i][0], westPts[i][1]};
        passage(westPts, T_CRIMSON);
        passage(eastPts, T_SOUL);
        pieces.add(new Piece(CH_X, CH_Z, CAVE_FLOOR, 17, 16, 17, T_HALL));
        keelS = new float[SIZE * SIZE];
        keelD = new float[SIZE * SIZE];
        Arrays.fill(keelS, -999f);
        Arrays.fill(keelD, 999f);
        List<Piece> keelParts = new ArrayList<>(pieces);
        keelParts.add(new Piece(-21, -27, CAVE_FLOOR, 14, 0, 14, T_HALL));
        keelParts.add(new Piece(21, -27, CAVE_FLOOR, 14, 0, 14, T_HALL));
        keelParts.add(new Piece(0, -20, CAVE_FLOOR, 12, 0, 12, T_HALL));
        for (Piece p : keelParts) {
            double rr = Math.max(p.rx, p.rz) + 4;
            int r0 = (int) Math.ceil(rr + KM);
            for (int x = (int) p.x - r0; x <= p.x + r0; x++) for (int z = (int) p.z - r0; z <= p.z + r0; z++) {
                if (!in(x, z)) continue;
                double dist = Math.hypot(x - p.x, z - p.z);
                int i = idx(x, z);
                float ks = (float) (rr - dist);
                if (ks > keelS[i]) keelS[i] = ks;
                if (dist < rr + KM) keelD[i] = Math.min(keelD[i], (float) (p.floor - 5));
            }
        }
    }

    private void underground() {
        // 1) оболочка из адских пород
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(41L, 2); g.setScale(1 / 7.0);
        for (Piece p : pieces) {
            int rx = (int) Math.ceil(p.rx + 4), ry = (int) Math.ceil(p.ry + 4), rz = (int) Math.ceil(p.rz + 4);
            for (int x = (int) p.x - rx; x <= p.x + rx; x++) for (int z = (int) p.z - rz; z <= p.z + rz; z++)
                for (int y = (int) p.floor - 4; y <= p.floor + ry; y++) {
                    double e = sq((x - p.x) / (p.rx + 4)) + sq((y - p.floor) / (p.ry + 4)) + sq((z - p.z) / (p.rz + 4));
                    if (e > 1) continue;
                    set(x, y, z, rock(x, y, z, p.theme, g));
                }
        }
        // шахта ямы: стенки
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
            double d = Math.hypot(x, z);
            if (d <= PIT_R + 0.5 || d > PIT_R + 2.6) continue;
            for (int y = CAVE_FLOOR + 8; y <= 65; y++) {
                double k = (y - CAVE_FLOOR - 8) / 37.0;
                Material m = k > 0.8 ? (rnd() < 0.5 ? Material.STONE : Material.COBBLESTONE)
                        : k > 0.55 ? (rnd() < 0.5 ? Material.NETHERRACK : Material.STONE)
                        : rnd() < 0.12 ? Material.MAGMA_BLOCK : rnd() < 0.08 ? Material.CRYING_OBSIDIAN : rnd() < 0.5 ? Material.NETHERRACK : Material.BLACKSTONE;
                if (d <= PIT_R + 1.6 && y % 6 == 0 && rnd() < 0.35) m = Material.SHROOMLIGHT;
                set(x, y, z, m);
            }
        }
        // 2) полости
        for (Piece p : pieces) carve(p);
        for (int x = -PIT_R; x <= PIT_R; x++) for (int z = -PIT_R; z <= PIT_R; z++)
            if (Math.hypot(x, z) <= PIT_R + 0.5) for (int y = CAVE_FLOOR + 5; y <= 70; y++) { set(x, y, z, Material.AIR); cave.add(key(x, y, z)); }
        // 3) отделка
        decorateCave();
        hallDetails();
        lavaChannels(westPts, 2);
        landingDetails();
    }

    private static double sq(double v) { return v * v; }

    private void passage(double[][] pts, int theme) {
        // кривая Катмулла-Рома по опорным точкам, шаг ~0.7 блока
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < pts.length - 1; i++) {
            double[] p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
            double len = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int steps = (int) Math.ceil(len / 0.7);
            for (int s = 0; s < steps; s++) {
                double t = s / (double) steps, t2 = t * t, t3 = t2 * t;
                double x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                double z = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                path.add(new double[]{x, z});
            }
        }
        for (int i = 0; i < path.size(); i++) {
            double[] p = path.get(i);
            double wave = Math.sin(i / 14.0) * 1.2;
            double rx = 3.6 + Math.sin(i / 9.0) * 0.6;
            pieces.add(new Piece(p[0], p[1], CAVE_FLOOR + Math.round(wave), rx, 7.5 + Math.sin(i / 7.0), rx, theme));
        }
    }

    private Material rock(int x, int y, int z, int theme, SimplexOctaveGenerator g) {
        double n = g.noise(x, y, z, 0.5, 0.5, true);
        double q = rnd();
        if (q < 0.02) return Material.NETHER_QUARTZ_ORE;
        if (q < 0.035) return Material.NETHER_GOLD_ORE;
        return switch (theme) {
            case T_CRIMSON -> n > 0.4 ? Material.NETHER_WART_BLOCK : n < -0.5 ? Material.MAGMA_BLOCK : Material.NETHERRACK;
            case T_SOUL -> n > 0.35 ? Material.BASALT : n < -0.35 ? Material.BLACKSTONE : q < 0.3 ? Material.SOUL_SOIL : Material.BLACKSTONE;
            case T_HALL -> n > 0.3 ? Material.POLISHED_BLACKSTONE_BRICKS : n < -0.4 ? Material.BLACKSTONE : Material.BASALT;
            default -> n > 0.45 ? Material.BLACKSTONE : n < -0.55 ? Material.MAGMA_BLOCK : Material.NETHERRACK;
        };
    }

    private void carve(Piece p) {
        int rx = (int) Math.ceil(p.rx), ry = (int) Math.ceil(p.ry), rz = (int) Math.ceil(p.rz);
        for (int x = (int) Math.floor(p.x - rx); x <= p.x + rx; x++) for (int z = (int) Math.floor(p.z - rz); z <= p.z + rz; z++)
            for (int y = (int) p.floor; y <= p.floor + ry; y++) {
                double e = sq((x - p.x) / p.rx) + sq((y - p.floor) / p.ry) + sq((z - p.z) / p.rz);
                if (e > 1) continue;
                long k = key(x, y, z);
                if (cave.add(k)) set(x, y, z, Material.AIR);
            }
    }

    private int themeAt(int x, int z) {
        if (z < CH_Z + 16 && Math.hypot(x - CH_X, z - CH_Z) < 18) return T_HALL;
        if (Math.hypot(x, z) < 16) return T_LAND;
        return x < 0 ? T_CRIMSON : T_SOUL;
    }

    private void decorateCave() {
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(53L, 2); g.setScale(1 / 6.0);
        List<Long> list = new ArrayList<>(cave);
        Collections.sort(list);
        for (long k : list) {
            int[] p = unkey(k);
            int x = p[0], y = p[1], z = p[2];
            if (Math.hypot(x, z) <= PIT_R + 0.5 && y > CAVE_FLOOR + 6) continue;
            int th = themeAt(x, z);
            boolean floor = !cave.contains(key(x, y - 1, z)), ceil = !cave.contains(key(x, y + 1, z));
            double n = g.noise(x, z, 0.5, 0.5, true);
            if (floor) {
                Material f = switch (th) {
                    case T_CRIMSON -> n > -0.2 ? Material.CRIMSON_NYLIUM : rnd() < 0.5 ? Material.NETHERRACK : Material.NETHER_WART_BLOCK;
                    case T_SOUL -> n > 0.15 ? Material.SOUL_SOIL : n < -0.35 ? Material.BASALT : Material.SOUL_SAND;
                    case T_HALL -> Material.POLISHED_BLACKSTONE_BRICKS;
                    default -> n > 0.45 ? Material.CRIMSON_NYLIUM : rnd() < 0.06 ? Material.MAGMA_BLOCK : Material.NETHERRACK;
                };
                set(x, y - 1, z, f);
                if (th == T_CRIMSON && f == Material.CRIMSON_NYLIUM) {
                    double q = rnd();
                    if (q < 0.045) set(x, y, z, Material.CRIMSON_FUNGUS);
                    else if (q < 0.16) set(x, y, z, Material.CRIMSON_ROOTS);
                } else if (th == T_SOUL && f == Material.SOUL_SOIL && rnd() < 0.035) set(x, y, z, Material.SOUL_FIRE);
                else if (th == T_LAND && f == Material.CRIMSON_NYLIUM && rnd() < 0.12) set(x, y, z, Material.CRIMSON_ROOTS);
            }
            if (ceil && th != T_HALL) {
                double q = rnd();
                if (q < 0.03) set(x, y + 1, z, Material.GLOWSTONE);
                else if (th == T_CRIMSON && q < 0.06) set(x, y + 1, z, Material.SHROOMLIGHT);
                else if ((th == T_CRIMSON || th == T_LAND) && q < 0.12) {
                    int len = 1 + r.nextInt(4);
                    for (int i = 0; i < len && cave.contains(key(x, y - i, z)) && air(x, y - i, z); i++)
                        set(x, y - i, z, i == len - 1 ? Material.WEEPING_VINES : Material.WEEPING_VINES_PLANT);
                } else if (th == T_SOUL && q < 0.045) {
                    int len = 1 + r.nextInt(3);
                    int i = 0;
                    for (; i < len && cave.contains(key(x, y - i, z)); i++) set(x, y - i, z, Material.IRON_CHAIN);
                    if (cave.contains(key(x, y - i, z))) set(x, y - i, z, "minecraft:soul_lantern[hanging=true]");
                }
            }
        }
        // колонны базальта и рёбра-окаменелости в душевном проходе
        for (Piece p : pieces) {
            if (p.theme == T_SOUL && rnd() < 0.05) {
                int sx = (int) Math.round(p.x + (rnd() < 0.5 ? -1 : 1) * (p.rx - 1.2)), sz = (int) Math.round(p.z);
                for (int y = (int) p.floor; y <= p.floor + p.ry && cave.contains(key(sx, y, sz)); y++) log(sx, y, sz, Material.BASALT, org.bukkit.Axis.Y);
            }
            if (p.theme == T_SOUL && rnd() < 0.025) {
                int bx = (int) Math.round(p.x), bz = (int) Math.round(p.z);
                for (double a = 0; a <= Math.PI; a += 0.12) {
                    int x = (int) Math.round(bx + Math.cos(a) * (p.rx - 0.6)), y = (int) Math.round(p.floor + Math.sin(a) * (p.ry - 1.5));
                    if (cave.contains(key(x, y, bz))) log(x, y, bz, Material.BONE_BLOCK, org.bukkit.Axis.Z);
                }
            }
            if (p.theme == T_CRIMSON && rnd() < 0.03) {
                int sx = (int) Math.round(p.x + (rnd() < 0.5 ? -1 : 1) * (p.rx - 1.4)), sz = (int) Math.round(p.z);
                int top = (int) (p.floor + 3 + r.nextInt(3));
                for (int y = (int) p.floor; y <= top && cave.contains(key(sx, y, sz)); y++) log(sx, y, sz, Material.CRIMSON_STEM, org.bukkit.Axis.Y);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (cave.contains(key(sx + dx, top + 1, sz + dz))) set(sx + dx, top + 1, sz + dz, rnd() < 0.2 ? Material.SHROOMLIGHT : Material.NETHER_WART_BLOCK);
            }
        }
    }

    /** Поперёк багрового прохода - лавовые рвы с базальтовыми мостами. */
    private void lavaChannels(double[][] west, int count) {
        List<Piece> crimson = new ArrayList<>();
        for (Piece p : pieces) if (p.theme == T_CRIMSON) crimson.add(p);
        for (int c = 1; c <= count; c++) {
            Piece p = crimson.get(crimson.size() * c / (count + 1));
            Piece q = crimson.get(Math.min(crimson.size() - 1, crimson.indexOf(p) + 2));
            double dx = q.x - p.x, dz = q.z - p.z, len = Math.hypot(dx, dz);
            dx /= len; dz /= len;
            double nx = -dz, nz = dx;
            for (double s = -0.9; s <= 0.9; s += 0.5) for (double w = -p.rx - 1; w <= p.rx + 1; w += 0.5) {
                int x = (int) Math.round(p.x + dx * s + nx * w), z = (int) Math.round(p.z + dz * s + nz * w);
                int y = (int) p.floor - 1;
                if (!cave.contains(key(x, y + 1, z))) continue;
                boolean bridge = Math.abs(w) < 1.1;
                set(x, y, z, bridge ? Material.POLISHED_BASALT : Material.LAVA);
                if (!bridge) { set(x, y - 1, z, Material.MAGMA_BLOCK); if (air(x, y + 1, z) || type(x, y + 1, z) == Material.CRIMSON_ROOTS || type(x, y + 1, z) == Material.CRIMSON_FUNGUS) set(x, y + 1, z, Material.AIR); }
            }
        }
    }

    private void landingDetails() {
        // площадка приземления под шахтой и колонны
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            double d = Math.hypot(x, z);
            if (d <= 5.5 && cave.contains(key(x, CAVE_FLOOR, z))) {
                set(x, CAVE_FLOOR - 1, z, d <= 2.5 ? Material.CRYING_OBSIDIAN : d <= 4 ? Material.CRIMSON_NYLIUM : Material.NETHERRACK);
                if (!air(x, CAVE_FLOOR, z)) set(x, CAVE_FLOOR, z, Material.AIR);
            }
        }
        int[][] cols = {{10, 6}, {-10, 6}, {9, -9}, {-9, -9}};
        for (int[] c : cols) for (int y = CAVE_FLOOR; y <= CAVE_FLOOR + 14; y++) {
            if (!cave.contains(key(c[0], y, c[1]))) break;
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) if (cave.contains(key(c[0] + dx, y, c[1] + dz))) log(c[0] + dx, y, c[1] + dz, Material.BASALT, org.bukkit.Axis.Y);
        }
        // озерцо лавы в чаше
        for (int x = 6; x <= 9; x++) for (int z = -2; z <= 1; z++) {
            if (!cave.contains(key(x, CAVE_FLOOR, z))) continue;
            set(x, CAVE_FLOOR - 1, z, Material.LAVA);
            set(x, CAVE_FLOOR - 2, z, Material.MAGMA_BLOCK);
            if (!air(x, CAVE_FLOOR, z)) set(x, CAVE_FLOOR, z, Material.AIR);
        }
        for (int x = 5; x <= 10; x++) for (int z = -3; z <= 2; z++) {
            if ((x == 5 || x == 10 || z == -3 || z == 2) && cave.contains(key(x, CAVE_FLOOR, z))) set(x, CAVE_FLOOR - 1, z, Material.POLISHED_BLACKSTONE);
        }
    }

    private void hallDetails() {
        int cy = CAVE_FLOOR;
        // пол зала - кольца из полированного черняка с золотыми вставками
        for (int x = CH_X - 17; x <= CH_X + 17; x++) for (int z = CH_Z - 17; z <= CH_Z + 17; z++) {
            if (!cave.contains(key(x, cy, z))) continue;
            double d = Math.hypot(x - CH_X, z - CH_Z);
            Material f = ((int) d) % 4 == 0 ? Material.POLISHED_BLACKSTONE : rnd() < 0.15 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
            if (((int) d) % 4 == 0 && rnd() < 0.18) f = Material.GILDED_BLACKSTONE;
            set(x, cy - 1, z, f);
        }
        // алтарь: три ступени
        int[] rads = {6, 4, 2};
        for (int t = 0; t < rads.length; t++) for (int x = -rads[t]; x <= rads[t]; x++) for (int z = -rads[t]; z <= rads[t]; z++) {
            double d = Math.hypot(x, z);
            if (d > rads[t] + 0.4) continue;
            Material m = d > rads[t] - 0.8 ? Material.POLISHED_BLACKSTONE_BRICKS : t == 2 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE;
            set(CH_X + x, cy + t, CH_Z + z, m);
        }
        set(CH_X, cy + 3, CH_Z, Material.CRYING_OBSIDIAN);
        altarY = cy + 4;
        // шесть колонн
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + Math.PI / 6;
            int x = (int) Math.round(CH_X + Math.cos(a) * 11.5), z = (int) Math.round(CH_Z + Math.sin(a) * 11.5);
            for (int y = cy; y <= cy + 15; y++) {
                if (!cave.contains(key(x, y, z))) break;
                set(x, y, z, y == cy || y % 5 == 0 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
            }
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (cave.contains(key(x + o[0], cy, z + o[1]))) set(x + o[0], cy, z + o[1], "minecraft:soul_lantern[hanging=false]");
        }
        // лавовые чаши у стен
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            int bx = (int) Math.round(CH_X + Math.cos(a) * 13.5), bz = (int) Math.round(CH_Z + Math.sin(a) * 13.5);
            for (int x = bx - 1; x <= bx + 1; x++) for (int z = bz - 1; z <= bz + 1; z++) {
                if (!cave.contains(key(x, cy, z))) continue;
                set(x, cy - 1, z, Material.LAVA);
                set(x, cy - 2, z, Material.MAGMA_BLOCK);
            }
            for (int x = bx - 2; x <= bx + 2; x++) for (int z = bz - 2; z <= bz + 2; z++)
                if ((Math.abs(x - bx) == 2 || Math.abs(z - bz) == 2) && cave.contains(key(x, cy, z))) set(x, cy - 1, z, Material.GILDED_BLACKSTONE);
        }
        // люстра из цепей над алтарём
        int topY = cy + 15;
        while (topY > cy + 6 && cave.contains(key(CH_X, topY + 1, CH_Z))) topY++;
        for (int y = topY; y > cy + 9; y--) if (cave.contains(key(CH_X, y, CH_Z))) set(CH_X, y, CH_Z, Material.IRON_CHAIN);
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            for (int y = topY; y > cy + 11; y--) if (cave.contains(key(CH_X + o[0], y, CH_Z + o[1]))) set(CH_X + o[0], y, CH_Z + o[1], Material.IRON_CHAIN);
            if (cave.contains(key(CH_X + o[0], cy + 11, CH_Z + o[1]))) set(CH_X + o[0], cy + 11, CH_Z + o[1], "minecraft:soul_lantern[hanging=true]");
        }
        // свет в куполе
        for (int i = 0; i < 40; i++) {
            int x = CH_X + r.nextInt(31) - 15, z = CH_Z + r.nextInt(31) - 15;
            for (int y = cy + 16; y > cy + 6; y--) if (cave.contains(key(x, y, z)) && !cave.contains(key(x, y + 1, z))) { set(x, y + 1, z, rnd() < 0.5 ? Material.SHROOMLIGHT : Material.GLOWSTONE); break; }
        }
    }

    // ---------------------------- дороги ----------------------------
    private void road(double x1, double z1, double x2, double z2, int width, boolean main) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        int steps = (int) Math.ceil(len * 2);
        double nx = -(z2 - z1) / len, nz = (x2 - x1) / len;
        int lamp = 0;
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            double cx = x1 + (x2 - x1) * t + Math.sin(t * Math.PI * 2) * (main ? 0 : 1.2) * nx;
            double cz = z1 + (z2 - z1) * t + Math.sin(t * Math.PI * 2) * (main ? 0 : 1.2) * nz;
            for (double w = -width / 2.0; w <= width / 2.0; w += 0.5) {
                int x = (int) Math.round(cx + nx * w), z = (int) Math.round(cz + nz * w);
                if (!ground(x, z) || m(x, z) == M_PLAZA || m(x, z) == M_WATER || m(x, z) == M_BUILD) continue;
                // сглаживаем перепады вдоль дороги
                int ty = h(x, z);
                double q = rnd();
                Material mat;
                boolean edge = Math.abs(w) > width / 2.0 - 0.6;
                if (main) mat = edge ? (q < 0.6 ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE) : q < 0.55 ? Material.DIRT_PATH : q < 0.75 ? Material.GRAVEL : q < 0.9 ? Material.COARSE_DIRT : Material.COBBLESTONE;
                else mat = q < 0.7 ? Material.DIRT_PATH : q < 0.85 ? Material.COARSE_DIRT : Material.GRAVEL;
                if (mat == Material.DIRT_PATH && !air(x, ty + 1, z)) mat = Material.COARSE_DIRT;
                set(x, ty, z, mat);
                if (!air(x, ty + 1, z) && type(x, ty + 1, z) != Material.LANTERN) set(x, ty + 1, z, Material.AIR);
                markForce(x, z, M_PATH);
            }
            if (main && s % 20 == 10) {
                int side = (lamp++ % 2 == 0) ? 1 : -1;
                int x = (int) Math.round(cx + nx * side * (width / 2.0 + 1)), z = (int) Math.round(cz + nz * side * (width / 2.0 + 1));
                if (ground(x, z) && m(x, z) == 0) {
                    int y = h(x, z);
                    set(x, y + 1, z, Material.SPRUCE_FENCE); set(x, y + 2, z, Material.SPRUCE_FENCE);
                    set(x, y + 3, z, Material.LANTERN);
                    fences.add(key(x, y + 1, z)); fences.add(key(x, y + 2, z));
                    markForce(x, z, M_BUILD);
                }
            }
        }
    }

    private void mainRoad() { road(0, 49, 0, 9, 4, true); }

    private void sidePaths() {
        road(3, 8, 36, 36, 2, false);
        road(-3, 8, -36, 36, 2, false);
        road(3, -8, 36, -36, 2, false);
        road(-3, -8, -36, -36, 2, false);
        road(0, -9, 0, -36, 3, true);
        road(-9, 2, -42, 8, 2, false);
        road(9, -2, 42, -6, 2, false);
        road(-6, 54, -24, 36, 2, false);
        road(8, 6, 16, 12, 2, false);
        road(6, -8, 22, -24, 2, false);
    }

    // ---------------------------- постройки ----------------------------
    private final Set<Long> fences = new HashSet<>();

    private void fence(int x, int y, int z, Material m) { set(x, y, z, m); fences.add(key(x, y, z)); }

    private void connectFences() {
        for (long k : fences) {
            int[] p = unkey(k);
            Block b = world.getBlockAt(p[0], p[1], p[2]);
            if (!(b.getBlockData() instanceof MultipleFacing mf)) continue;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (!mf.getAllowedFaces().contains(f)) continue;
                Block n = b.getRelative(f);
                boolean join = fences.contains(key(n.getX(), n.getY(), n.getZ())) || n.getType().isOccluding();
                mf.setFace(f, join);
            }
            b.setBlockData(mf, false);
        }
    }

    private void shulker(int x, int y, int z, String rarity) {
        set(x, y, z, rarityBlock(rarity));
        spotDefs.add(x + "," + y + "," + z + "," + rarity);
    }

    private void towers() {
        int[][] at = {{37, 37, 1}, {-37, 37, 0}, {37, -37, 0}, {-37, -37, 1}};   // шалкер наверху только у двух вышек
        for (int[] a : at) tower(a[0], a[1], a[2] == 1);
    }

    private void tower(int cx, int cz, boolean loot) {
        int y = pad(cx, cz, 3, 3, 4);
        Material[] base = {Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.STONE_BRICKS};
        // каменное основание с арками
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) {
            boolean wall = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3;
            set(x, y, z, Material.STONE_BRICKS);
            if (wall) for (int yy = y + 1; yy <= y + 4; yy++) {
                boolean arch = (x == cx || z == cz) && yy <= y + 2;
                if (!arch) set(x, yy, z, pick(base));
            }
        }
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) set(x, y + 4, z, Material.SPRUCE_PLANKS);
        // деревянная вышка
        int top = y + 13;
        int[][] corners = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        for (int[] c : corners) for (int yy = y + 5; yy <= top; yy++) log(cx + c[0], yy, cz + c[1], Material.SPRUCE_LOG, org.bukkit.Axis.Y);
        for (int yy = y + 1; yy <= top - 1; yy++) {
            set(cx, yy, cz - 2, yy <= y + 4 ? Material.STONE_BRICKS : Material.SPRUCE_PLANKS);
            set(cx, yy, cz - 1, "minecraft:ladder[facing=south]");
        }
        // площадка
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) {
            if (x == cx && z == cz - 1) continue;
            set(x, top, z, Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3 ? Material.SPRUCE_SLAB : Material.SPRUCE_PLANKS);
        }
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++)
            if ((Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) && !(Math.abs(x - cx) == 3 && Math.abs(z - cz) == 3)) fence(x, top + 1, z, Material.SPRUCE_FENCE);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) { log(cx + c[0], top + 1, cz + c[1], Material.STRIPPED_SPRUCE_LOG, org.bukkit.Axis.Y); log(cx + c[0], top + 2, cz + c[1], Material.STRIPPED_SPRUCE_LOG, org.bukkit.Axis.Y); log(cx + c[0], top + 3, cz + c[1], Material.STRIPPED_SPRUCE_LOG, org.bukkit.Axis.Y); }
        // крыша-пирамида
        for (int layer = 0; layer <= 3; layer++) {
            int rr = 4 - layer, yy = top + 4 + layer;
            for (int x = cx - rr; x <= cx + rr; x++) for (int z = cz - rr; z <= cz + rr; z++) {
                boolean edge = Math.abs(x - cx) == rr || Math.abs(z - cz) == rr;
                if (!edge) continue;
                BlockFace f = Math.abs(x - cx) == rr && (Math.abs(z - cz) < rr) ? (x > cx ? BlockFace.WEST : BlockFace.EAST) : (z > cz ? BlockFace.NORTH : BlockFace.SOUTH);
                stairs(x, yy, z, Material.DARK_OAK_STAIRS, f, false);
            }
        }
        set(cx, top + 8, cz, Material.DARK_OAK_PLANKS);
        set(cx, top + 9, cz, Material.LIGHTNING_ROD);
        set(cx + 1, top + 3, cz + 1, "minecraft:lantern[hanging=true]");
        set(cx - 1, top + 3, cz - 1, "minecraft:lantern[hanging=true]");
        if (loot) shulker(cx + 2, top + 1, cz + 2, "rare");
    }

    private void temple(int cx, int cz) {
        int y = pad(cx, cz, 8, 7, 4);
        for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 7; z <= cz + 7; z++) {
            double q = rnd();
            set(x, y, z, q < 0.25 ? Material.MOSSY_STONE_BRICKS : q < 0.45 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
            if (Math.abs(x - cx) <= 6 && Math.abs(z - cz) <= 5) set(x, y, z, (x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
        }
        // колонны по периметру (часть обрушена)
        for (int x = cx - 7; x <= cx + 7; x += 2) for (int z : new int[]{cz - 6, cz + 6}) column(x, y, z);
        for (int z = cz - 4; z <= cz + 4; z += 2) for (int x : new int[]{cx - 7, cx + 7}) column(x, y, z);
        // задняя стена с аркой
        for (int x = cx - 5; x <= cx + 5; x++) for (int yy = y + 1; yy <= y + 5; yy++) {
            if (Math.abs(x - cx) <= 1 && yy <= y + 3) continue;
            if (rnd() < 0.12 && yy > y + 3) continue;
            set(x, yy, cz - 5, rnd() < 0.3 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            if (rnd() < 0.25 && air(x, yy, cz - 4)) set(x, yy, cz - 4, "minecraft:vine[north=true]");
        }
        // алтарь со шалкером
        box(cx - 1, y + 1, cz + 1, cx + 1, y + 1, cz + 2, Material.CHISELED_STONE_BRICKS);
        shulker(cx, y + 2, cz + 1, "epic");
        for (int i = 0; i < 18; i++) { int x = cx - 6 + r.nextInt(13), z = cz - 5 + r.nextInt(11); if (air(x, y + 1, z)) set(x, y + 1, z, Material.MOSS_CARPET); }
        set(cx + 3, y + 1, cz - 3, "minecraft:lantern[hanging=false]");
        set(cx - 3, y + 1, cz - 3, "minecraft:lantern[hanging=false]");
    }

    private void column(int x, int y, int z) {
        int hgt = rnd() < 0.3 ? 1 + r.nextInt(3) : 5;
        set(x, y + 1, z, Material.CHISELED_STONE_BRICKS);
        for (int yy = y + 2; yy <= y + hgt; yy++) set(x, yy, z, Material.STONE_BRICK_WALL);
        if (hgt == 5) set(x, y + 6, z, Material.STONE_BRICK_SLAB);
        else if (rnd() < 0.6) set(x + (rnd() < 0.5 ? 1 : -1), y + 1, z, Material.STONE_BRICK_SLAB);
    }

    private void cabin(int cx, int cz) {
        int y = pad(cx, cz, 5, 4, 4);
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 3; z <= cz + 3; z++) {
            set(x, y, z, Material.COBBLESTONE);
            boolean wallX = Math.abs(x - cx) == 4, wallZ = Math.abs(z - cz) == 3;
            for (int yy = y + 1; yy <= y + 4; yy++) {
                if (wallX && wallZ) log(x, yy, z, Material.SPRUCE_LOG, org.bukkit.Axis.Y);
                else if (wallX || wallZ) {
                    boolean door = z == cz + 3 && x == cx && yy <= y + 2;
                    boolean window = yy == y + 2 && ((wallZ && Math.abs(x - cx) == 2) || (wallX && z == cz));
                    if (door) set(x, yy, z, Material.AIR);
                    else if (window) set(x, yy, z, wallZ ? "minecraft:glass_pane[east=true,west=true]" : "minecraft:glass_pane[north=true,south=true]");
                    else set(x, yy, z, yy == y + 1 ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS);
                } else set(x, y, z, Material.SPRUCE_PLANKS);
            }
        }
        // двускатная крыша
        for (int i = 0; i <= 4; i++) for (int x = cx - 5; x <= cx + 5; x++) {
            stairs(x, y + 5 + i, cz - 4 + i, Material.DARK_OAK_STAIRS, BlockFace.SOUTH, false);
            stairs(x, y + 5 + i, cz + 4 - i, Material.DARK_OAK_STAIRS, BlockFace.NORTH, false);
            if (i == 4) set(x, y + 9, cz, Material.DARK_OAK_SLAB);
        }
        for (int i = 0; i <= 3; i++) for (int x : new int[]{cx - 4, cx + 4}) for (int z = cz - 3 + i; z <= cz + 3 - i; z++) set(x, y + 5 + i, z, Material.SPRUCE_PLANKS);
        // труба
        for (int yy = y + 1; yy <= y + 10; yy++) set(cx + 3, yy, cz - 2, Material.COBBLESTONE);
        set(cx + 3, y + 11, cz - 2, Material.CAMPFIRE);
        // внутри
        set(cx - 3, y + 1, cz - 2, Material.CRAFTING_TABLE);
        set(cx - 2, y + 1, cz - 2, Material.BARREL);
        set(cx + 2, y + 1, cz - 2, Material.FURNACE);
        set(cx, y + 4, cz, "minecraft:lantern[hanging=true]");
        set(cx, y, cz + 4, Material.SPRUCE_PLANKS);
        for (int s2 : new int[]{-2, 2}) { fence(cx + s2, y + 1, cz + 4, Material.SPRUCE_FENCE); set(cx + s2, y + 2, cz + 4, "minecraft:lantern[hanging=false]"); }
    }

    private void bunker(int cx, int cz) {
        int y = pad(cx, cz, 5, 4, 4);
        int floor = y - 3;
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 4; z <= cz + 4; z++) {
            boolean wall = Math.abs(x - cx) == 5 || Math.abs(z - cz) == 4;
            for (int yy = floor; yy <= y + 2; yy++) {
                if (yy == floor || yy == y + 2) set(x, yy, z, yy == y + 2 ? (rnd() < 0.3 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS) : Material.POLISHED_DEEPSLATE);
                else if (wall) set(x, yy, z, rnd() < 0.2 ? Material.CRACKED_DEEPSLATE_TILES : Material.DEEPSLATE_TILES);
                else set(x, yy, z, Material.AIR);
            }
        }
        // мешки с песком (земля на крыше) и смотровые щели
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 4; z <= cz + 4; z++) if (rnd() < 0.5) set(x, y + 3, z, Material.MOSS_CARPET);
        for (int x = cx - 3; x <= cx + 3; x += 3) set(x, y + 1, cz - 4, Material.IRON_BARS);
        // вход-лестница
        for (int i = 0; i <= 3; i++) {
            set(cx, y + 1 - i, cz + 4 + (3 - i), Material.AIR);
            set(cx, y - i, cz + 4 + (3 - i), "minecraft:deepslate_brick_stairs[facing=south,half=bottom]");
            set(cx, y + 2 - i, cz + 4 + (3 - i), Material.AIR);
        }
        set(cx, floor + 1, cz + 4, Material.AIR); set(cx, floor + 2, cz + 4, Material.AIR);
        for (int x = cx - 3; x <= cx + 3; x += 3) set(x, y + 1, cz - 3, Material.AIR);
        set(cx - 4, floor + 1, cz - 3, Material.SHROOMLIGHT);
        set(cx + 4, floor + 1, cz - 3, Material.SHROOMLIGHT);
        set(cx, y + 1, cz, "minecraft:lantern[hanging=true]");
        shulker(cx - 4, floor + 1, cz + 3, "rare");
        set(cx + 4, floor + 1, cz, Material.ANVIL);
    }

    private void camp(int cx, int cz) {
        int y = pad(cx, cz, 6, 5, 4);
        set(cx, y, cz, Material.COBBLESTONE);
        set(cx, y + 1, cz, Material.CAMPFIRE);
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}}) log(cx + o[0], y + 1, cz + o[1], Material.OAK_LOG, o[0] != 0 ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
        tent(cx - 4, y, cz - 2, Material.RED_WOOL);
        tent(cx + 3, y, cz - 3, Material.WHITE_WOOL);
        shulker(cx - 4, y + 1, cz - 3, "common");
        set(cx + 2, y + 1, cz + 3, Material.BARREL);
    }

    private void tent(int x0, int y, int z0, Material wool) {
        for (int z = z0 - 2; z <= z0 + 2; z++) {
            for (int i = 0; i <= 2; i++) {
                set(x0 - i, y + 1 + (2 - i), z, wool);
                set(x0 + i, y + 1 + (2 - i), z, wool);
            }
            if (z == z0 - 2 || z == z0 + 2) fence(x0, y + 4, z, Material.OAK_FENCE);
        }
        for (int z = z0 - 1; z <= z0 + 1; z++) set(x0, y + 1, z, Material.AIR);
        set(x0, y, z0, Material.HAY_BLOCK);
    }

    private void fort(int cx, int cz) {
        int y = pad(cx, cz, 7, 7, 4);
        for (int x = cx - 7; x <= cx + 7; x++) for (int z = cz - 7; z <= cz + 7; z++) {
            boolean wall = Math.abs(x - cx) == 7 || Math.abs(z - cz) == 7;
            if (!wall) { if (rnd() < 0.3) set(x, y, z, Material.COARSE_DIRT); continue; }
            boolean gate = z == cz + 7 && Math.abs(x - cx) <= 1;
            int hgt = gate ? 0 : rnd() < 0.25 ? 1 + r.nextInt(2) : 4;
            for (int yy = y + 1; yy <= y + hgt; yy++) set(x, yy, z, rnd() < 0.25 ? Material.MOSSY_COBBLESTONE : rnd() < 0.3 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            if (hgt == 4 && (x + z) % 2 == 0) set(x, y + 5, z, Material.STONE_BRICK_WALL);
        }
        for (int[] c : new int[][]{{-7, -7}, {7, -7}, {-7, 7}, {7, 7}}) for (int yy = y + 1; yy <= y + 6; yy++) set(cx + c[0], yy, cz + c[1], Material.STONE_BRICKS);
        box(cx - 1, y + 1, cz - 1, cx + 1, y + 1, cz + 1, Material.SPRUCE_PLANKS);
        shulker(cx, y + 2, cz, "epic");
        set(cx + 5, y + 1, cz - 5, "minecraft:lantern[hanging=false]");
    }

    private void market(int cx, int cz) {
        int y = pad(cx, cz, 5, 4, 3);
        // колодец
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            boolean ring = !(x == cx && z == cz);
            set(x, y, z, ring ? Material.COBBLESTONE : Material.WATER);
            set(x, y - 1, z, ring ? Material.COBBLESTONE : Material.WATER);
            set(x, y - 2, z, Material.COBBLESTONE);
            if (ring) set(x, y + 1, z, Material.COBBLESTONE_WALL);
        }
        for (int[] c : new int[][]{{-1, -1}, {1, 1}}) { fence(cx + c[0], y + 2, cz + c[1], Material.OAK_FENCE); fence(cx + c[0], y + 3, cz + c[1], Material.OAK_FENCE); }
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) set(x, y + 4, z, Material.OAK_SLAB);
        // два прилавка с тентами
        stall(cx - 4, y, cz + 2, Material.YELLOW_WOOL);
        stall(cx + 4, y, cz - 2, Material.LIGHT_BLUE_WOOL);
        shulker(cx - 4, y + 1, cz + 3, "common");
    }

    private void stall(int x0, int y, int z0, Material wool) {
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) for (int yy = y + 1; yy <= y + 3; yy++) fence(x0 + c[0], yy, z0 + c[1], Material.SPRUCE_FENCE);
        for (int x = x0 - 1; x <= x0 + 1; x++) for (int z = z0 - 1; z <= z0 + 1; z++) set(x, y + 4, z, (x + z) % 2 == 0 ? wool : Material.WHITE_WOOL);
        set(x0, y + 1, z0 - 1, Material.BARREL);
        set(x0 + 1, y + 1, z0 - 1, Material.CRAFTING_TABLE);
    }

    // ---------------------------- деревья: меньше, но кастомные ----------------------------
    private SimplexOctaveGenerator leafNoise;

    private boolean soft(Material m) {
        if (m == Material.WATER || m == Material.LAVA) return false;
        return m.isAir() || !m.isSolid() || Tag.LEAVES.isTagged(m) || m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT
                || m == Material.PODZOL || m == Material.ROOTED_DIRT || m == Material.MOSS_BLOCK;
    }

    private void wood(int x, int y, int z, Material m) { if (soft(type(x, y, z))) set(x, y, z, m); }

    /** Слой ствола: круг радиуса rr (утолщение к земле). */
    private void trunkLayer(double cx, int y, double cz, double rr, Material log) {
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        if (rr < 0.75) { if (soft(type(bx, y, bz))) log(bx, y, bz, log, org.bukkit.Axis.Y); return; }
        int ir = (int) Math.ceil(rr);
        for (int dx = -ir; dx <= ir; dx++) for (int dz = -ir; dz <= ir; dz++)
            if (dx * dx + dz * dz <= rr * rr && soft(type(bx + dx, y, bz + dz))) log(bx + dx, y, bz + dz, log, org.bukkit.Axis.Y);
    }

    /** Ветвь/корень от точки к точке с утоньшением; тонкие ветви сплошные (без диагональных разрывов). */
    private void branch(double x0, double y0, double z0, double x1, double y1, double z1, double r0, double r1, Material m) {
        double len = Math.sqrt(sq(x1 - x0) + sq(y1 - y0) + sq(z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(len * 3));
        int px = Integer.MIN_VALUE, py = 0, pz = 0;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps, rr = r0 + (r1 - r0) * t;
            int bx = (int) Math.floor(x0 + (x1 - x0) * t), by = (int) Math.floor(y0 + (y1 - y0) * t), bz = (int) Math.floor(z0 + (z1 - z0) * t);
            if (rr < 0.75) {
                if (px != Integer.MIN_VALUE) {
                    if (bx != px && (by != py || bz != pz)) wood(bx, py, pz, m);
                    if (by != py && bz != pz) wood(bx, by, pz, m);
                }
                wood(bx, by, bz, m);
                px = bx; py = by; pz = bz;
                continue;
            }
            int ir = (int) Math.ceil(rr);
            for (int dx = -ir; dx <= ir; dx++) for (int dy = -ir; dy <= ir; dy++) for (int dz = -ir; dz <= ir; dz++)
                if (dx * dx + dy * dy + dz * dz <= rr * rr) wood(bx + dx, by + dy, bz + dz, m);
            px = bx; py = by; pz = bz;
        }
    }

    /** Комок листвы: эллипсоид с неровным краем (шум) и редкими просветами. */
    private void cluster(double cx, double cy, double cz, double rx, double ry, double rz, Material leaf, Material alt, double altChance) {
        int x0 = (int) Math.floor(cx - rx - 1), x1 = (int) Math.ceil(cx + rx + 1), y0 = (int) Math.floor(cy - ry - 1), y1 = (int) Math.ceil(cy + ry + 1);
        int z0 = (int) Math.floor(cz - rz - 1), z1 = (int) Math.ceil(cz + rz + 1);
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            double e = sq((x + 0.5 - cx) / rx) + sq((y + 0.5 - cy) / ry) + sq((z + 0.5 - cz) / rz);
            if (e > 1 + 0.32 * leafNoise.noise(x, y, z, 0.5, 0.5, true)) continue;
            if (e > 0.6 && rnd() < 0.07) continue;
            leaves(x, y, z, alt != null && rnd() < altChance ? alt : leaf);
        }
    }

    /** Нижний лист в столбце (сверху вниз, первый сплошной участок листвы). */
    private int lowestLeaf(int x, int z, int top, int bottom) {
        int low = Integer.MIN_VALUE;
        for (int y = top; y >= bottom; y--) {
            if (Tag.LEAVES.isTagged(type(x, y, z))) low = y;
            else if (low != Integer.MIN_VALUE) break;
        }
        return low;
    }

    /** Свисающие пряди листвы под кроной. */
    private void hangLeaves(double cx, double cy, double cz, double rad, Material leaf, double chance, int maxLen) {
        int r0 = (int) Math.ceil(rad);
        for (int dx = -r0; dx <= r0; dx++) for (int dz = -r0; dz <= r0; dz++) {
            if (dx * dx + dz * dz > rad * rad || rnd() >= chance) continue;
            int x = (int) Math.floor(cx) + dx, z = (int) Math.floor(cz) + dz;
            int low = lowestLeaf(x, z, (int) cy + 4, (int) cy - 4);
            if (low == Integer.MIN_VALUE) continue;
            int g = ground(x, z) ? h(x, z) : low - 40, len = 1 + r.nextInt(maxLen);
            for (int i = 1; i <= len; i++) { int y = low - i; if (y <= g + 2 || !air(x, y, z)) break; leaves(x, y, z, leaf); }
        }
    }

    /** Пещерные лианы под кроной, часть со светящимися ягодами. */
    private void caveVines(double cx, double cy, double cz, double rad, double chance, double berry) {
        int r0 = (int) Math.ceil(rad);
        for (int dx = -r0; dx <= r0; dx++) for (int dz = -r0; dz <= r0; dz++) {
            if (dx * dx + dz * dz > rad * rad || rnd() >= chance) continue;
            int x = (int) Math.floor(cx) + dx, z = (int) Math.floor(cz) + dz;
            int low = lowestLeaf(x, z, (int) cy + 5, (int) cy - 9);
            if (low == Integer.MIN_VALUE) continue;
            int g = ground(x, z) ? h(x, z) : low - 40, len = 0, want = 1 + r.nextInt(3);
            while (len < want && low - len - 1 > g + 2 && air(x, low - len - 1, z)) len++;
            for (int i = 1; i <= len; i++) {
                boolean b = rnd() < berry;
                set(x, low - i, z, i == len ? "minecraft:cave_vines[age=25,berries=" + b + "]" : "minecraft:cave_vines_plant[berries=" + b + "]");
            }
        }
    }

    private void groundCover(int cx, int cz, int rad, String data, double chance) {
        for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
            if (dx * dx + dz * dz > rad * rad || rnd() >= chance) continue;
            int x = cx + dx, z = cz + dz;
            if (!ground(x, z)) continue;
            int y = h(x, z);
            if (type(x, y, z) != Material.GRASS_BLOCK || !air(x, y + 1, z)) continue;
            set(x, y + 1, z, data);
        }
    }

    private void petals(int cx, int cz, int rad, double chance) {
        String[] f = {"north", "south", "east", "west"};
        for (int k = 0; k < 4; k++) groundCover(cx, cz, rad, "minecraft:pink_petals[flower_amount=" + (1 + r.nextInt(4)) + ",facing=" + f[r.nextInt(4)] + "]", chance / 4);
    }

    private void mushrooms(int cx, int cz, int rad, int n) {
        for (int k = 0; k < n; k++) {
            int x = cx + r.nextInt(rad * 2 + 1) - rad, z = cz + r.nextInt(rad * 2 + 1) - rad;
            if (!ground(x, z) || type(x, h(x, z), z) != Material.GRASS_BLOCK || !air(x, h(x, z) + 1, z)) continue;
            set(x, h(x, z) + 1, z, rnd() < 0.5 ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM);
        }
    }

    /** Могучий дуб: ствол с утолщением, корни, 5-7 ветвей с кронами и веточками, лианы. */
    private void grandOak(int x, int y, int z) {
        int hgt = 8 + r.nextInt(4);
        double cx = x + 0.5, cz = z + 0.5;
        for (int i = 0; i < hgt; i++) trunkLayer(cx, y + i, cz, 1.45 - 0.95 * Math.pow(i / (double) hgt, 0.6), Material.OAK_LOG);
        int nr = 5 + r.nextInt(3);
        double a0 = rnd() * Math.PI * 2;
        for (int k = 0; k < nr; k++) {
            double a = a0 + k * Math.PI * 2 / nr + (rnd() - 0.5) * 0.5, len = 2.6 + rnd() * 2.2;
            branch(cx + Math.cos(a) * 0.9, y + 1.3, cz + Math.sin(a) * 0.9, cx + Math.cos(a) * len, y - 0.5, cz + Math.sin(a) * len, 0.85, 0.4, Material.OAK_WOOD);
        }
        int nb = 5 + r.nextInt(3);
        double b0 = rnd() * Math.PI * 2;
        for (int k = 0; k < nb; k++) {
            double a = b0 + k * Math.PI * 2 / nb + (rnd() - 0.5) * 0.6, sy = y + hgt * (0.5 + 0.38 * rnd());
            double el = Math.toRadians(18 + rnd() * 30), len = 4 + rnd() * 3;
            double ex = cx + Math.cos(a) * Math.cos(el) * len, ey = sy + Math.sin(el) * len, ez = cz + Math.sin(a) * Math.cos(el) * len;
            branch(cx, sy, cz, ex, ey, ez, 0.8, 0.45, Material.OAK_WOOD);
            cluster(ex, ey + 0.8, ez, 2.9 + rnd() * 0.9, 1.9 + rnd() * 0.6, 2.9 + rnd() * 0.9, Material.OAK_LEAVES, Material.AZALEA_LEAVES, 0.07);
            if (rnd() < 0.6) {
                double a2 = a + (rnd() < 0.5 ? 0.9 : -0.9), mx = (cx + ex) / 2, my = (sy + ey) / 2, mz = (cz + ez) / 2;
                double tx = mx + Math.cos(a2) * 2.6, ty = my + 1.6, tz = mz + Math.sin(a2) * 2.6;
                branch(mx, my, mz, tx, ty, tz, 0.45, 0.4, Material.OAK_WOOD);
                cluster(tx, ty + 0.5, tz, 2.2, 1.6, 2.2, Material.OAK_LEAVES, Material.AZALEA_LEAVES, 0.07);
            }
        }
        cluster(cx, y + hgt + 0.6, cz, 3.6, 2.6, 3.6, Material.OAK_LEAVES, Material.AZALEA_LEAVES, 0.07);
        caveVines(cx, y + hgt, cz, 6.5, 0.07, 0.15);
        groundCover(x, z, 6, "minecraft:leaf_litter", 0.2);
        mushrooms(x, z, 4, 3);
    }

    /** Сакура: изогнутый ствол, широкие плоские розовые кроны, свисающие ветки, лепестки на траве. */
    private void sakura(int x, int y, int z) {
        int hgt = 5 + r.nextInt(2);
        double cx = x + 0.5, cz = z + 0.5, a = rnd() * Math.PI * 2, lean = 1.2 + rnd() * 0.9;
        double mx = cx + Math.cos(a) * lean * 0.35, mz = cz + Math.sin(a) * lean * 0.35;
        double tx = cx + Math.cos(a) * lean, tz = cz + Math.sin(a) * lean, ty = y + hgt;
        branch(cx, y - 0.4, cz, mx, y + hgt * 0.5, mz, 0.95, 0.6, Material.CHERRY_WOOD);
        branch(mx, y + hgt * 0.5, mz, tx, ty, tz, 0.6, 0.45, Material.CHERRY_WOOD);
        int nb = 3 + r.nextInt(2);
        for (int k = 0; k < nb; k++) {
            double b = a + k * Math.PI * 2 / nb + (rnd() - 0.5) * 0.7, len = 3.4 + rnd() * 2.0, el = Math.toRadians(6 + rnd() * 18);
            double ex = tx + Math.cos(b) * Math.cos(el) * len, ey = ty + Math.sin(el) * len, ez = tz + Math.sin(b) * Math.cos(el) * len;
            branch(tx, ty, tz, ex, ey, ez, 0.5, 0.4, Material.CHERRY_WOOD);
            cluster(ex, ey + 0.7, ez, 3.3 + rnd() * 0.8, 1.6 + rnd() * 0.4, 3.3 + rnd() * 0.8, Material.CHERRY_LEAVES, null, 0);
            hangLeaves(ex, ey, ez, 3.6, Material.CHERRY_LEAVES, 0.22, 2);
        }
        cluster(tx, ty + 1.4, tz, 3.0, 1.8, 3.0, Material.CHERRY_LEAVES, null, 0);
        petals(x, z, 7, 0.34);
    }

    /** Плакучая ива у пруда: наклонный ствол, купол и длинные свисающие пряди до земли. */
    private void willow(int x, int y, int z) {
        int hgt = 6 + r.nextInt(2);
        double cx = x + 0.5, cz = z + 0.5, a = rnd() * Math.PI * 2;
        double tx = cx + Math.cos(a) * 0.8, tz = cz + Math.sin(a) * 0.8;
        branch(cx, y - 0.4, cz, tx, y + hgt, tz, 1.05, 0.55, Material.DARK_OAK_WOOD);
        for (int k = 0; k < 6; k++) {
            double b = a + k * Math.PI * 2 / 6 + (rnd() - 0.5) * 0.5, len = 3 + rnd() * 1.6, el = Math.toRadians(5 + rnd() * 15);
            double sy = y + hgt - 1 - rnd() * 1.5;
            double ex = tx + Math.cos(b) * len, ey = sy + Math.sin(el) * len, ez = tz + Math.sin(b) * len;
            branch(tx, sy, tz, ex, ey, ez, 0.5, 0.4, Material.DARK_OAK_WOOD);
            cluster(ex, ey + 0.6, ez, 2.6, 1.5, 2.6, Material.MANGROVE_LEAVES, Material.OAK_LEAVES, 0.25);
        }
        cluster(tx, y + hgt + 0.8, tz, 3.4, 2.0, 3.4, Material.MANGROVE_LEAVES, Material.OAK_LEAVES, 0.25);
        hangLeaves(tx, y + hgt, tz, 6.5, Material.MANGROVE_LEAVES, 0.5, 5);
        caveVines(tx, y + hgt, tz, 6.5, 0.12, 0.2);
    }

    /** Гигантская ель: ствол 2x2, ярусы с провисшими краями и сучьями, подзол и папоротники. */
    private void pine(int x, int y, int z) {
        int hgt = 15 + r.nextInt(5);
        for (int i = 0; i < hgt; i++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) log(x + dx, y + i, z + dz, Material.SPRUCE_LOG, org.bukkit.Axis.Y);
        for (int[] f : new int[][]{{-1, 0}, {2, 1}, {1, -1}, {0, 2}, {-1, 1}, {2, 0}}) if (rnd() < 0.6) {
            wood(x + f[0], y, z + f[1], Material.SPRUCE_WOOD);
            if (rnd() < 0.5) wood(x + f[0], y + 1, z + f[1], Material.SPRUCE_WOOD);
        }
        double cx = x + 1.0, cz = z + 1.0;
        for (int i = 4; i <= hgt; i += 2) {
            double t = (i - 4) / (double) Math.max(1, hgt - 4);
            tier(cx, y + i, cz, 1.4 + (1 - t) * (1 - t * 0.3) * 4.4);
        }
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) leaves(x + dx, y + hgt, z + dz, Material.SPRUCE_LEAVES);
        leaves(x, y + hgt + 1, z, Material.SPRUCE_LEAVES);
        leaves(x, y + hgt + 2, z, Material.SPRUCE_LEAVES);
        for (int dx = -4; dx <= 5; dx++) for (int dz = -4; dz <= 5; dz++) {
            int px = x + dx, pz = z + dz;
            if (Math.hypot(dx - 0.5, dz - 0.5) > 4.5 || !ground(px, pz) || type(px, h(px, pz), pz) != Material.GRASS_BLOCK) continue;
            double q = rnd();
            if (q < 0.45) set(px, h(px, pz), pz, Material.PODZOL); else if (q < 0.6) set(px, h(px, pz), pz, Material.COARSE_DIRT);
        }
        groundCover(x, z, 5, "minecraft:fern", 0.12);
    }

    private void tier(double cx, int y, double cz, double rad) {
        int r0 = (int) Math.ceil(rad) + 1;
        for (int dx = -r0; dx <= r0; dx++) for (int dz = -r0; dz <= r0; dz++) {
            int bx = (int) Math.floor(cx) + dx, bz = (int) Math.floor(cz) + dz;
            double d = Math.hypot(bx + 0.5 - cx, bz + 0.5 - cz), lim = rad + 0.6 * leafNoise.noise(bx, y * 3, bz, 0.5, 0.5, true);
            if (d > lim) continue;
            leaves(bx, y, bz, Material.SPRUCE_LEAVES);
            if (d > lim - 1.3) leaves(bx, y - 1, bz, Material.SPRUCE_LEAVES);
            else if (d < lim - 2.2 && rnd() < 0.5) leaves(bx, y + 1, bz, Material.SPRUCE_LEAVES);
        }
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + rnd() * 0.6;
            branch(cx, y - 0.5, cz, cx + Math.cos(a) * rad * 0.7, y - 0.2, cz + Math.sin(a) * rad * 0.7, 0.45, 0.4, Material.SPRUCE_WOOD);
        }
    }

    /** Берёзовая рощица: 2-3 наклонных ствола с двухъярусной кроной, рядом упавшее бревно с мхом. */
    private void birchGroup(int x, int y, int z) {
        int n = 2 + r.nextInt(2);
        for (int k = 0; k < n; k++) {
            int bx = x + (k == 0 ? 0 : r.nextInt(5) - 2), bz = z + (k == 0 ? 0 : r.nextInt(5) - 2);
            if (!ground(bx, bz) || type(bx, h(bx, bz), bz) != Material.GRASS_BLOCK || !air(bx, h(bx, bz) + 1, bz)) continue;
            int by = h(bx, bz) + 1, hgt = 7 + r.nextInt(4);
            double la = rnd() * Math.PI * 2, lean = rnd() * 1.1;
            double tx = bx + 0.5 + Math.cos(la) * lean, tz = bz + 0.5 + Math.sin(la) * lean;
            branch(bx + 0.5, by - 0.4, bz + 0.5, tx, by + hgt, tz, 0.45, 0.4, Material.BIRCH_LOG);
            cluster(tx, by + hgt - 0.5, tz, 2.2, 2.3, 2.2, Material.BIRCH_LEAVES, null, 0);
            cluster(tx + (rnd() - 0.5), by + hgt - 3.0, tz + (rnd() - 0.5), 2.4, 1.4, 2.4, Material.BIRCH_LEAVES, null, 0);
        }
        if (rnd() < 0.7) fallenLog(x + 3, z + (rnd() < 0.5 ? 2 : -2), Material.BIRCH_LOG);
    }

    private void fallenLog(int x, int z, Material log) {
        boolean alongX = rnd() < 0.5;
        int len = 3 + r.nextInt(2);
        if (!ground(x, z)) return;
        int y = h(x, z);
        for (int i = 0; i < len; i++) {
            int lx = x + (alongX ? i : 0), lz = z + (alongX ? 0 : i);
            if (!ground(lx, lz) || h(lx, lz) != y || (m(lx, lz) != 0 && m(lx, lz) != M_TREE) || !air(lx, y + 1, lz)) return;
        }
        for (int i = 0; i < len; i++) {
            int lx = x + (alongX ? i : 0), lz = z + (alongX ? 0 : i);
            log(lx, y + 1, lz, log, alongX ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
            if (rnd() < 0.5) set(lx, y + 2, lz, Material.MOSS_CARPET); else if (rnd() < 0.3) set(lx, y + 2, lz, Material.BROWN_MUSHROOM);
            markForce(lx, lz, M_TREE);
        }
    }

    /** Древний тёмный дуб - ориентир: ствол 5x5, мощные корни, огромная крона, лианы, мох и грибы. */
    private void ancientOak(int x, int y, int z) {
        int hgt = 10 + r.nextInt(3);
        double cx = x + 0.5, cz = z + 0.5;
        for (int i = 0; i < hgt; i++) trunkLayer(cx, y + i, cz, 2.25 - 1.5 * Math.pow(i / (double) hgt, 0.55), Material.DARK_OAK_LOG);
        double a0 = rnd() * Math.PI * 2;
        for (int k = 0; k < 7; k++) {
            double a = a0 + k * Math.PI * 2 / 7 + (rnd() - 0.5) * 0.4, len = 4 + rnd() * 2.5;
            branch(cx + Math.cos(a) * 1.6, y + 2.2, cz + Math.sin(a) * 1.6, cx + Math.cos(a) * len, y - 0.6, cz + Math.sin(a) * len, 1.3, 0.5, Material.DARK_OAK_WOOD);
        }
        double b0 = rnd() * Math.PI * 2;
        for (int k = 0; k < 7; k++) {
            double a = b0 + k * Math.PI * 2 / 7 + (rnd() - 0.5) * 0.5, sy = y + hgt * (0.55 + 0.35 * rnd());
            double el = Math.toRadians(15 + rnd() * 25), len = 5.5 + rnd() * 3;
            double ex = cx + Math.cos(a) * Math.cos(el) * len, ey = sy + Math.sin(el) * len, ez = cz + Math.sin(a) * Math.cos(el) * len;
            branch(cx, sy, cz, ex, ey, ez, 1.1, 0.5, Material.DARK_OAK_WOOD);
            cluster(ex, ey + 0.9, ez, 4.0 + rnd() * 0.8, 2.5 + rnd() * 0.5, 4.0 + rnd() * 0.8, Material.DARK_OAK_LEAVES, Material.OAK_LEAVES, 0.06);
            double a2 = a + (rnd() < 0.5 ? 1.0 : -1.0), mx = (cx + ex) / 2, my = (sy + ey) / 2, mz = (cz + ez) / 2;
            double tx = mx + Math.cos(a2) * 3.2, ty = my + 2.0, tz = mz + Math.sin(a2) * 3.2;
            branch(mx, my, mz, tx, ty, tz, 0.6, 0.45, Material.DARK_OAK_WOOD);
            cluster(tx, ty + 0.6, tz, 2.8, 1.9, 2.8, Material.DARK_OAK_LEAVES, Material.OAK_LEAVES, 0.06);
        }
        cluster(cx, y + hgt + 1, cz, 5.0, 3.0, 5.0, Material.DARK_OAK_LEAVES, Material.OAK_LEAVES, 0.06);
        caveVines(cx, y + hgt, cz, 9.5, 0.12, 0.2);
        groundCover(x, z, 8, "minecraft:moss_carpet", 0.12);
        groundCover(x, z, 7, "minecraft:leaf_litter", 0.2);
        mushrooms(x, z, 6, 6);
    }

    /** Цветущая азалия: изогнутый ствол, крона с розовыми цветами, цветок спор (зелёные искры в воздухе). */
    private void azaleaTree(int x, int y, int z) {
        set(x, y - 1, z, Material.ROOTED_DIRT);
        int hgt = 4 + r.nextInt(2);
        double cx = x + 0.5, cz = z + 0.5, a = rnd() * Math.PI * 2;
        double mx = cx + Math.cos(a) * 1.1, mz = cz + Math.sin(a) * 1.1;
        branch(cx, y - 0.4, cz, mx, y + hgt * 0.55, mz, 0.45, 0.4, Material.OAK_WOOD);
        branch(mx, y + hgt * 0.55, mz, cx, y + hgt, cz, 0.45, 0.4, Material.OAK_WOOD);
        cluster(cx, y + hgt + 0.5, cz, 3.0, 1.9, 3.0, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, 0.45);
        boolean spore = false;
        for (int k = 0; k < 8 && !spore; k++) {
            int sx = x + r.nextInt(5) - 2, sz = z + r.nextInt(5) - 2;
            for (int yy = y + hgt + 2; yy >= y + 2; yy--) {
                if (Tag.LEAVES.isTagged(type(sx, yy, sz)) && air(sx, yy - 1, sz)) { set(sx, yy - 1, sz, Material.SPORE_BLOSSOM); spore = true; break; }
            }
        }
        groundCover(x, z, 3, "minecraft:moss_carpet", 0.2);
    }

    private boolean treeSpot(int x, int z, int clear, int spacing, List<int[]> placedTrees) {
        if (!ground(x, z) || type(x, h(x, z), z) != Material.GRASS_BLOCK || !air(x, h(x, z) + 1, z)) return false;
        double d = Math.hypot(x, z);
        if (d < 18 || d > 72 || Math.hypot(x, z - 58) < 16 || islandEdge.contains(((long) x << 32) ^ (z & 0xffffffffL))) return false;
        for (int dx = -clear; dx <= clear; dx++) for (int dz = -clear; dz <= clear; dz++) { int mm = m(x + dx, z + dz); if (mm != 0 && mm != M_TREE) return false; }
        for (int[] t : placedTrees) if (Math.hypot(t[0] - x, t[1] - z) < Math.max(spacing, t[2])) return false;
        int lo = h(x, z), hi = lo;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (ground(x + dx, z + dz)) { lo = Math.min(lo, h(x + dx, z + dz)); hi = Math.max(hi, h(x + dx, z + dz)); }
        return hi - lo <= 2;
    }

    private void plant(List<int[]> placedTrees, int x, int z, String kind, int spacing) {
        int y = h(x, z) + 1;
        switch (kind) {
            case "ancient" -> ancientOak(x, y, z);
            case "willow" -> willow(x, y, z);
            case "pine" -> pine(x, y, z);
            case "sakura" -> sakura(x, y, z);
            case "birch" -> birchGroup(x, y, z);
            case "azalea" -> azaleaTree(x, y, z);
            default -> grandOak(x, y, z);
        }
        placedTrees.add(new int[]{x, z, spacing});
        int rr = kind.equals("ancient") ? 4 : 2;
        for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) mark(x + dx, z + dz, M_TREE);
    }

    private void grove(List<int[]> placedTrees, int cx, int cz, int radius, String kind, int count, int clear, int spacing) {
        int made = 0;
        for (int attempt = 0; attempt < 500 && made < count; attempt++) {
            double a = rnd() * Math.PI * 2, rr = Math.sqrt(rnd()) * radius;
            int x = (int) Math.round(cx + Math.cos(a) * rr), z = (int) Math.round(cz + Math.sin(a) * rr);
            if (!treeSpot(x, z, clear, spacing, placedTrees)) continue;
            plant(placedTrees, x, z, kind, spacing);
            made++;
        }
    }

    private void willowAt(List<int[]> placedTrees, int px, int pz, int rx) {
        for (int k = 0; k < 80; k++) {
            double a = rnd() * Math.PI * 2, d = rx + 2.0 + rnd() * 2.5;
            int x = (int) Math.round(px + Math.cos(a) * d), z = (int) Math.round(pz + Math.sin(a) * d);
            if (!ground(x, z) || type(x, h(x, z), z) != Material.GRASS_BLOCK || !air(x, h(x, z) + 1, z)) continue;
            if (Math.hypot(x, z - 58) < 15 || Math.hypot(x, z) < 18) continue;
            boolean free = true;
            for (int dx = -1; dx <= 1 && free; dx++) for (int dz = -1; dz <= 1; dz++) { int mm = m(x + dx, z + dz); if (mm != 0 && mm != M_WATER && mm != M_TREE) { free = false; break; } }
            if (!free) continue;
            plant(placedTrees, x, z, "willow", 8);
            return;
        }
    }

    /** Деревья рощами по районам острова: ориентиры, ивы у прудов, ельник, сакуры, берёзы, дубы и азалии. Всего ~45, а не 125. */
    private void trees() {
        leafNoise = new SimplexOctaveGenerator(83L, 2);
        leafNoise.setScale(1 / 2.8);
        List<int[]> placedTrees = new ArrayList<>();
        grove(placedTrees, -58, -28, 5, "ancient", 1, 5, 16);
        grove(placedTrees, 55, 42, 5, "ancient", 1, 5, 16);
        willowAt(placedTrees, 27, 27, 6);
        willowAt(placedTrees, -30, -24, 6);
        willowAt(placedTrees, -12, 50, 3);
        grove(placedTrees, 55, -45, 15, "pine", 7, 3, 9);
        grove(placedTrees, -46, -56, 10, "pine", 4, 3, 9);
        grove(placedTrees, -52, 42, 12, "sakura", 5, 3, 8);
        grove(placedTrees, 24, 52, 8, "sakura", 3, 3, 8);
        grove(placedTrees, -63, -2, 9, "birch", 3, 2, 7);
        grove(placedTrees, 63, 14, 9, "birch", 2, 2, 7);
        grove(placedTrees, 0, 0, 68, "oak", 9, 4, 13);
        grove(placedTrees, 0, 0, 68, "azalea", 6, 2, 9);
        // кусты
        for (int i = 0, made = 0; i < 300 && made < 32; i++) {
            int x = r.nextInt(160) - 80, z = r.nextInt(160) - 80;
            if (!ground(x, z) || m(x, z) != 0 || type(x, h(x, z), z) != Material.GRASS_BLOCK || Math.hypot(x, z) < 14 || Math.hypot(x, z - 58) < 13
                    || islandEdge.contains(((long) x << 32) ^ (z & 0xffffffffL))) continue;
            int y = h(x, z) + 1;
            log(x, y, z, Material.OAK_LOG, org.bukkit.Axis.Y);
            cluster(x + 0.5, y + 0.6, z + 0.5, 1.6 + rnd() * 0.5, 1.1, 1.6 + rnd() * 0.5, rnd() < 0.5 ? Material.OAK_LEAVES : Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, 0.2);
            mark(x, z, M_TREE);
            made++;
        }
    }

    /** Невидимая стена по всему краю острова от поверхности до потолка мира - с острова не уйти. */
    private void barrierRing() {
        int cols = 0;
        for (int x = -OFF + 2; x < OFF - 1; x++) for (int z = -OFF + 2; z < OFF - 1; z++) {
            if (ground(x, z)) continue;
            int minTop = Integer.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (ground(x + dx, z + dz)) minTop = Math.min(minTop, h(x + dx, z + dz));
            if (minTop == Integer.MAX_VALUE) continue;
            for (int y = minTop - 8; y <= 319; y++) world.getBlockAt(x, y, z).setType(Material.BARRIER, false);
            cols++;
        }
        placed += cols * 250;
    }

    // ---------------------------- камни, трава, цветы ----------------------------
    private void boulders() {
        for (int i = 0; i < 44; i++) {
            int x = r.nextInt(160) - 80, z = r.nextInt(160) - 80;
            if (!ground(x, z) || m(x, z) != 0 || Math.hypot(x, z) < 14 || Math.hypot(x, z - 58) < 12) continue;
            int y = h(x, z) + 1;
            double rad = 1 + rnd() * 1.4;
            Material[] mats = {Material.MOSSY_COBBLESTONE, Material.COBBLESTONE, Material.ANDESITE, Material.STONE, Material.MOSSY_COBBLESTONE};
            int ir = (int) Math.ceil(rad);
            for (int dx = -ir; dx <= ir; dx++) for (int dy = -1; dy <= ir; dy++) for (int dz = -ir; dz <= ir; dz++) {
                if (Math.sqrt(dx * dx + dy * dy * 1.8 + dz * dz) > rad) continue;
                set(x + dx, y + dy, z + dz, pick(mats));
            }
            for (int dx = -ir; dx <= ir; dx++) for (int dz = -ir; dz <= ir; dz++) markForce(x + dx, z + dz, M_BUILD);
            if (rnd() < 0.5) set(x, y + ir, z, Material.MOSS_CARPET);
        }
    }

    private void flora() {
        SimplexOctaveGenerator patch = new SimplexOctaveGenerator(71L, 2); patch.setScale(1 / 11.0);
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM, Material.LILY_OF_THE_VALLEY, Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP};
        for (int x = -100; x <= 100; x++) for (int z = -100; z <= 100; z++) {
            if (!ground(x, z)) continue;
            int mm = m(x, z);
            if (mm == M_PATH || mm == M_PLAZA || mm == M_WATER || mm == M_BUILD) continue;
            int y = h(x, z);
            if (type(x, y, z) != Material.GRASS_BLOCK || !air(x, y + 1, z)) continue;
            double p = patch.noise(x, z, 0.5, 0.5, true), q = rnd();
            if (p > 0.45 && q < 0.35) set(x, y + 1, z, flowers[Math.floorMod((int) Math.round(p * 10) + x * 7 + z * 13, flowers.length)]);
            else if (p < -0.5 && q < 0.25) set(x, y + 1, z, "minecraft:wildflowers[flower_amount=" + (1 + r.nextInt(4)) + ",facing=north]");
            else if (q < 0.26) set(x, y + 1, z, Material.SHORT_GRASS);
            else if (q < 0.30 && air(x, y + 2, z)) { set(x, y + 1, z, "minecraft:tall_grass[half=lower]"); set(x, y + 2, z, "minecraft:tall_grass[half=upper]"); }
            else if (q < 0.315) set(x, y + 1, z, Material.BUSH);
            else if (q < 0.325) set(x, y + 1, z, Material.FERN);
            else if (q < 0.328) set(x, y + 1, z, Material.FIREFLY_BUSH);
            else if (mm == M_TREE && q < 0.40) set(x, y + 1, z, Material.LEAF_LITTER);
        }
    }

    /** Лут-шалкеры в проходах ада и в зале приземления - на постаментах в нишах у стен. */
    private void caveShulkers() {
        List<Piece> west = new ArrayList<>(), east = new ArrayList<>();
        for (Piece p : pieces) { if (p.theme == T_CRIMSON) west.add(p); else if (p.theme == T_SOUL) east.add(p); }
        for (List<Piece> list : List.of(west, east)) {
            if (list.size() < 4) continue;
            for (double f : new double[]{0.5}) {
                int pi = (int) (list.size() * f);
                Piece p = list.get(pi), q = list.get(Math.min(list.size() - 1, pi + 2));
                double dx = q.x - p.x, dz = q.z - p.z, len = Math.max(0.01, Math.hypot(dx, dz));
                double nx = -dz / len, nz = dx / len;
                for (int side : new int[]{1, -1}) if (caveShulker(p.x, p.z, nx * side, nz * side, p.rx, (int) p.floor)) break;
            }
        }
        caveShulker(0, 0, -0.8, -0.6, 13.5, CAVE_FLOOR);
    }

    private boolean caveShulker(double cx, double cz, double nx, double nz, double reach, int floor) {
        for (double w = reach - 1.0; w >= 1.5; w -= 0.5) {
            int x = (int) Math.round(cx + nx * w), z = (int) Math.round(cz + nz * w);
            for (int y = floor - 2; y <= floor + 3; y++) {
                if (!cave.contains(key(x, y, z)) || cave.contains(key(x, y - 1, z)) || !cave.contains(key(x, y + 1, z)) || !cave.contains(key(x, y + 2, z))) continue;
                set(x, y - 1, z, Material.CHISELED_POLISHED_BLACKSTONE);
                shulker(x, y, z, "legendary");
                int lx = (int) Math.round(x - nz), lz = (int) Math.round(z + nx);
                if (cave.contains(key(lx, y, lz)) && !cave.contains(key(lx, y - 1, lz))) set(lx, y, lz, "minecraft:soul_lantern[hanging=false]");
                return true;
            }
        }
        return false;
    }

    /** Святилища с обычными шалкерами в определённых местах острова. */
    private void shrines() {
        int[][] at = {{-10, 28}, {54, 20}, {-58, -10}};
        for (int[] a : at) if (ground(a[0], a[1])) shrine(a[0], a[1]);
    }

    private void shrine(int cx, int cz) {
        int y = pad(cx, cz, 2, 2, 3);
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
            boolean ring = Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2;
            set(x, y, z, ring ? (rnd() < 0.4 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS) : Material.POLISHED_ANDESITE);
        }
        set(cx, y + 1, cz, Material.CHISELED_STONE_BRICKS);
        shulker(cx, y + 2, cz, "common");
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            set(cx + c[0], y + 1, cz + c[1], Material.MOSSY_STONE_BRICK_WALL);
            set(cx + c[0], y + 2, cz + c[1], "minecraft:lantern[hanging=false]");
        }
        for (int i = 0; i < 5; i++) { int x = cx - 2 + r.nextInt(5), z = cz - 2 + r.nextInt(5); if (air(x, y + 1, z)) set(x, y + 1, z, Material.MOSS_CARPET); }
    }

    /** Выход из Ада: пузырьковый подъём 2x2 из зала Элитного шалкера в колодец на поверхности (порталов нет). */
    private void exitWell() {
        int ex = 0, ez = CH_Z - 15;
        int y = pad(ex, ez, 4, 4, 3);
        for (int x = ex - 1; x <= ex + 2; x++) for (int z = ez - 1; z <= ez + 2; z++) set(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
        for (int[] c : new int[][]{{-1, -1}, {2, -1}, {-1, 2}, {2, 2}}) {
            set(ex + c[0], y + 1, ez + c[1], Material.POLISHED_BLACKSTONE_WALL);
            set(ex + c[0], y + 2, ez + c[1], Material.POLISHED_BLACKSTONE_WALL);
            set(ex + c[0], y + 3, ez + c[1], "minecraft:soul_lantern[hanging=false]");
        }
        for (int i = 0; i < 14; i++) {
            int x = ex - 3 + r.nextInt(8), z = ez - 3 + r.nextInt(8);
            if (x >= ex - 1 && x <= ex + 2 && z >= ez - 1 && z <= ez + 2) continue;
            if (ground(x, z) && type(x, h(x, z), z) == Material.GRASS_BLOCK) set(x, h(x, z), z, rnd() < 0.5 ? Material.NETHERRACK : Material.SOUL_SOIL);
        }
        // столб пузырей от пола зала до поверхности
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
            set(ex + dx, CAVE_FLOOR - 1, ez + dz, Material.SOUL_SAND);
            for (int yy = CAVE_FLOOR; yy <= y; yy++) { set(ex + dx, yy, ez + dz, "minecraft:bubble_column[drag=false]"); cave.remove(key(ex + dx, yy, ez + dz)); }
        }
        // стеклянная труба в зале, вход с юга (к алтарю)
        for (int x = ex - 1; x <= ex + 2; x++) for (int z = ez - 1; z <= ez + 2; z++) {
            if (x >= ex && x <= ex + 1 && z >= ez && z <= ez + 1) continue;
            for (int yy = CAVE_FLOOR; yy <= CAVE_FLOOR + 16; yy++) {
                if (!cave.contains(key(x, yy, z))) continue;
                if (z == ez + 2 && x >= ex && x <= ex + 1 && yy <= CAVE_FLOOR + 1) continue;
                set(x, yy, z, Material.GLASS);
            }
        }
        for (int sx : new int[]{ex - 2, ex + 3}) if (cave.contains(key(sx, CAVE_FLOOR, ez + 2))) set(sx, CAVE_FLOOR, ez + 2, "minecraft:soul_lantern[hanging=false]");
        state.set("exit-well-y", y);
    }

    // =====================================================================
    //                 ВХОД НА СПАВНЕ: ДОРОГА, ВОРОТА И NPC
    // =====================================================================
    private static final int HUB_FY = 4;                       // уровень пола купола
    private static final int GATE_X = 302, GATE_Z = 303;       // ворота у северо-западной стены купола (рядом с 298 305)
    /** Опорные точки дороги: от края площади спавна между аквариумом и храмом к воротам (первая и последняя - направляющие). */
    private static final double[][] HUB_ROAD = {{318.0, 334.5}, {316.5, 333.0}, {311.0, 329.5}, {309.4, 325.5}, {306.6, 320.0}, {304.0, 314.5}, {302.5, 310.0}, {302.5, 307.0}, {302.5, 305.5}};

    private static long ck(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /**
     * Дорога от площади спавна к воротам PvP-арены, площадь перед воротами, ворота и NPC.
     * Над стеклянным полом (под ним риф) дорога из тонированного стекла - кораллы остаются видны.
     * Цвет дороги по пути переходит из аква-стиля спавна в тёмный "адский". Все заменённые блоки сохраняются для отката (/pvp unhub).
     */
    public void buildHub() {
        World hub = pl.worlds().hub();
        if (hub == null) return;
        if (!state.getStringList("hub-backup").isEmpty()) restoreHub(hub);
        final int fy = HUB_FY;
        // 1) свободный пол - до любых изменений
        Set<Long> free = new HashSet<>();
        for (int x = 284; x <= 346; x++) for (int z = 292; z <= 352; z++) {
            Block f = hub.getBlockAt(x, fy, z);
            if (!f.getType().isSolid() || f.isLiquid()) continue;
            if (hub.getBlockAt(x, fy + 1, z).getType().isAir() && hub.getBlockAt(x, fy + 2, z).getType().isAir() && hub.getBlockAt(x, fy + 3, z).getType().isAir()) free.add(ck(x, z));
        }
        // 2) клетки дороги по сглаженной кривой (w - смещение от оси, t - доля пути, s - длина по пути)
        List<double[]> pts = new ArrayList<>();
        for (int i = 1; i < HUB_ROAD.length - 2; i++)
            for (int k = 0; k < 40; k++) pts.add(catmull(HUB_ROAD[i - 1], HUB_ROAD[i], HUB_ROAD[i + 1], HUB_ROAD[i + 2], k / 40.0));
        pts.add(HUB_ROAD[HUB_ROAD.length - 2].clone());
        double[] arc = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) arc[i] = arc[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        double total = arc[arc.length - 1];
        Map<Long, double[]> cells = new HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] c = pts.get(i), a1 = pts.get(Math.max(0, i - 1)), a2 = pts.get(Math.min(pts.size() - 1, i + 1));
            double dx = a2[0] - a1[0], dz = a2[1] - a1[1], n = Math.max(1e-6, Math.hypot(dx, dz)), nx = -dz / n, nz = dx / n;
            for (int w10 = -25; w10 <= 25; w10++) {
                double w = w10 / 10.0;
                long k = ck((int) Math.floor(c[0] + nx * w), (int) Math.floor(c[1] + nz * w));
                double[] prev = cells.get(k);
                if (prev == null || Math.abs(w) < Math.abs(prev[0])) cells.put(k, new double[]{w, arc[i] / total, arc[i]});
            }
        }
        Set<Long> road = new HashSet<>();
        for (long k : cells.keySet()) if (free.contains(k)) road.add(k);

        List<String> backup = new ArrayList<>();
        Set<Long> touched = new HashSet<>();
        java.util.function.BiConsumer<Block, BlockData> put = (bl, d) -> {
            long k = key(bl.getX(), bl.getY(), bl.getZ());
            if (touched.add(k)) backup.add(bl.getX() + "," + bl.getY() + "," + bl.getZ() + "|" + bl.getBlockData().getAsString());
            bl.setBlockData(d, false);
        };
        // 3) полотно: бордюр / обочины / осевая со светом
        for (long k : road) {
            int x = (int) (k >> 32), z = (int) k;
            double[] c = cells.get(k);
            double w = Math.abs(c[0]), t = c[1], sArc = c[2];
            boolean border = !road.contains(ck(x + 1, z)) || !road.contains(ck(x - 1, z)) || !road.contains(ck(x, z + 1)) || !road.contains(ck(x, z - 1));
            Block fb = hub.getBlockAt(x, fy, z);
            boolean glass = fb.getType().name().contains("GLASS");
            Material m;
            if (border) m = glass ? Material.BLACK_STAINED_GLASS : Material.POLISHED_BLACKSTONE_BRICKS;
            else if (w <= 0.5) m = glass ? Material.RED_STAINED_GLASS : ((int) Math.floor(sArc) % 4 == 0 ? (t < 0.5 ? Material.SEA_LANTERN : Material.SHROOMLIGHT) : Material.RED_NETHER_BRICKS);
            else m = glass ? null : t < 0.4 ? Material.DARK_PRISMARINE : t < 0.7 ? Material.POLISHED_DEEPSLATE : Material.POLISHED_BLACKSTONE;
            if (m != null) put.accept(fb, m.createBlockData());
        }
        // 4) фонари по обе стороны: в начале призмарин и морской фонарь, ближе к воротам - черняк и фонарь душ
        for (double at = 3; at < total - 4; at += 6.5) {
            int i = 0;
            while (i < arc.length - 1 && arc[i] < at) i++;
            double[] c = pts.get(i), a1 = pts.get(Math.max(0, i - 1)), a2 = pts.get(Math.min(pts.size() - 1, i + 1));
            double dx = a2[0] - a1[0], dz = a2[1] - a1[1], n = Math.max(1e-6, Math.hypot(dx, dz)), nx = -dz / n, nz = dx / n;
            boolean dark = at / total >= 0.4;
            for (int side : new int[]{1, -1}) {
                int x = (int) Math.floor(c[0] + nx * 3.6 * side), z = (int) Math.floor(c[1] + nz * 3.6 * side);
                if (!free.contains(ck(x, z)) || road.contains(ck(x, z))) continue;
                put.accept(hub.getBlockAt(x, fy + 1, z), (dark ? Material.CHISELED_POLISHED_BLACKSTONE : Material.DARK_PRISMARINE).createBlockData());
                put.accept(hub.getBlockAt(x, fy + 2, z), (dark ? Material.POLISHED_BLACKSTONE_WALL : Material.PRISMARINE_WALL).createBlockData());
                put.accept(hub.getBlockAt(x, fy + 3, z), (dark ? Material.POLISHED_BLACKSTONE_WALL : Material.PRISMARINE_WALL).createBlockData());
                put.accept(hub.getBlockAt(x, fy + 4, z), dark ? bd("minecraft:soul_lantern[hanging=false]") : Material.SEA_LANTERN.createBlockData());
                free.remove(ck(x, z));
            }
        }
        // 5) площадь перед воротами (полукруг) и ворота
        for (int x = GATE_X - 6; x <= GATE_X + 6; x++) for (int z = GATE_Z + 1; z <= GATE_Z + 6; z++) {
            double d = Math.hypot(x - GATE_X, z - GATE_Z);
            if (d > 5.6 || !free.contains(ck(x, z))) continue;
            Block fb = hub.getBlockAt(x, fy, z);
            Material m = d <= 1.5 ? Material.SHROOMLIGHT : d <= 2.6 ? Material.POLISHED_BLACKSTONE : d <= 3.6 ? ((x + z) % 2 == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS)
                    : d <= 4.6 ? Material.RED_NETHER_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
            if (fb.getType().name().contains("GLASS")) m = d > 3.6 && d <= 4.6 ? Material.RED_STAINED_GLASS : Material.BLACK_STAINED_GLASS;
            put.accept(fb, m.createBlockData());
        }
        buildGate(hub, fy, put, free);
        state.set("hub-backup", backup);
        state.set("hub-version", HUB_VERSION);
        state.set("hub-arch", GATE_X + "," + (fy + 15) + "," + GATE_Z);
        double[] st = pts.get(0);
        state.set("hub-road-start", Math.floor(st[0]) + "," + (fy + 3) + "," + Math.floor(st[1]));
        saveState();
        // NPC-воин в проёме ворот, лицом к дороге
        pl.getConfig().set("npcs.pvp.location", "spawn;" + (GATE_X + 0.5) + ";" + (fy + 1) + ";" + (GATE_Z + 0.5) + ";0;0");
        pl.getConfig().set("npcs.pvp.skin", pl.getConfig().getString("npcs.pvp.skin", "Technoblade"));
        pl.getConfig().set("npcs.pvp.command", pl.getConfig().getString("npcs.pvp.command", "pvp"));
        if (!pl.getConfig().isSet("npcs.pvp.lines")) pl.getConfig().set("npcs.pvp.lines", List.of("<gradient:#ff3355:#ff9900><bold>⚔ PvP-Арена</bold></gradient>", "<gray>ПКМ - на арену"));
        if (!pl.getConfig().isSet("npcs.pvp.equipment")) {
            pl.getConfig().set("npcs.pvp.equipment.main-hand", "NETHERITE_SWORD");
            pl.getConfig().set("npcs.pvp.equipment.off-hand", "TOTEM_OF_UNDYING");
            pl.getConfig().set("npcs.pvp.equipment.helmet", "NETHERITE_HELMET");
            pl.getConfig().set("npcs.pvp.equipment.chestplate", "NETHERITE_CHESTPLATE");
            pl.getConfig().set("npcs.pvp.equipment.leggings", "NETHERITE_LEGGINGS");
            pl.getConfig().set("npcs.pvp.equipment.boots", "NETHERITE_BOOTS");
            pl.getConfig().set("npcs.pvp.equipment.glint", true);
        }
        pl.saveConfig();
        pl.npcs().spawnAll();
        spawnHolos();
        pl.getLogger().info("PvP-арена: на спавне построены дорога (" + road.size() + " клеток, " + Math.round(total) + " блоков длиной), площадь и ворота; заменено блоков: " + touched.size() + ". Откат: /pvp unhub");
    }

    private static double[] catmull(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] o = new double[2];
        for (int i = 0; i < 2; i++)
            o[i] = 0.5 * (2 * p1[i] + (-p0[i] + p2[i]) * t + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2 + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3);
        return o;
    }

    /** Ворота из полированного черняка 7x13 в два слоя: арка, плачущий обсидиан, цепи с фонарями душ, знамёна и жаровни. */
    private void buildGate(World hub, int fy, java.util.function.BiConsumer<Block, BlockData> put, Set<Long> free) {
        int gx = GATE_X, gz = GATE_Z;
        BlockData bricks = bd("minecraft:polished_blackstone_bricks"), chis = bd("minecraft:chiseled_polished_blackstone"),
                cry = bd("minecraft:crying_obsidian"), gild = bd("minecraft:gilded_blackstone");
        java.util.function.BiConsumer<Block, BlockData> air = (bl, d) -> { if (bl.getType().isAir()) put.accept(bl, d); };
        for (int z = gz; z >= gz - 1; z--) {
            for (int sx : new int[]{-3, 3}) for (int y = fy + 1; y <= fy + 9; y++)
                air.accept(hub.getBlockAt(gx + sx, y, z), y == fy + 1 ? chis : y == fy + 5 ? cry : y == fy + 8 ? gild : bricks);
            for (int x = gx - 3; x <= gx + 3; x++) air.accept(hub.getBlockAt(x, fy + 9, z), bricks);
            for (int x = gx - 2; x <= gx + 2; x++) air.accept(hub.getBlockAt(x, fy + 10, z), x == gx ? cry : bricks);
            for (int x = gx - 1; x <= gx + 1; x++) air.accept(hub.getBlockAt(x, fy + 11, z), bricks);
            air.accept(hub.getBlockAt(gx, fy + 12, z), gild);
            for (int sx : new int[]{-2, 2}) {
                Stairs st = (Stairs) Material.POLISHED_BLACKSTONE_BRICK_STAIRS.createBlockData();
                st.setFacing(sx < 0 ? BlockFace.WEST : BlockFace.EAST);
                st.setHalf(Bisected.Half.TOP);
                air.accept(hub.getBlockAt(gx + sx, fy + 8, z), st);
            }
        }
        // зубцы по краям верха и фонарь душ на вершине
        for (int sx : new int[]{-3, 3}) air.accept(hub.getBlockAt(gx + sx, fy + 10, gz), bd("minecraft:polished_blackstone_wall"));
        air.accept(hub.getBlockAt(gx, fy + 13, gz), bd("minecraft:soul_lantern[hanging=false]"));
        // цепи с фонарями душ в проёме
        for (int sx : new int[]{-1, 1}) {
            air.accept(hub.getBlockAt(gx + sx, fy + 8, gz), bd("minecraft:iron_chain[axis=y]"));
            air.accept(hub.getBlockAt(gx + sx, fy + 7, gz), bd("minecraft:soul_lantern[hanging=true]"));
        }
        // красный порог под воином
        for (int x = gx - 2; x <= gx + 2; x++) if (free.contains(ck(x, gz))) put.accept(hub.getBlockAt(x, fy, gz), Material.RED_NETHER_BRICKS.createBlockData());
        // знамёна с черепом на колоннах
        for (int sx : new int[]{-3, 3}) {
            Block b = hub.getBlockAt(gx + sx, fy + 7, gz + 1);
            if (!b.getType().isAir()) continue;
            put.accept(b, bd("minecraft:red_wall_banner[facing=south]"));
            if (b.getState() instanceof org.bukkit.block.Banner bn) {
                bn.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.BLACK, org.bukkit.block.banner.PatternType.GRADIENT));
                bn.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.BLACK, org.bukkit.block.banner.PatternType.SKULL));
                bn.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.ORANGE, org.bukkit.block.banner.PatternType.TRIANGLES_BOTTOM));
                bn.addPattern(new org.bukkit.block.banner.Pattern(org.bukkit.DyeColor.BLACK, org.bukkit.block.banner.PatternType.BORDER));
                bn.update(true, false);
            }
        }
        // жаровни по бокам площади
        for (int sx : new int[]{-5, 5}) {
            int x = gx + sx, z = gz + 1;
            if (!free.contains(ck(x, z))) continue;
            put.accept(hub.getBlockAt(x, fy + 1, z), bd("minecraft:polished_blackstone_bricks"));
            put.accept(hub.getBlockAt(x, fy + 2, z), bd("minecraft:soul_campfire[lit=true,signal_fire=false,waterlogged=false]"));
        }
    }

    private int restoreHub(World hub) {
        List<String> backup = state.getStringList("hub-backup");
        for (int i = backup.size() - 1; i >= 0; i--) {
            String[] a = backup.get(i).split("\\|", 2), p = a[0].split(",");
            try { hub.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).setBlockData(Bukkit.createBlockData(a[1]), false); } catch (Exception ignored) {}
        }
        state.set("hub-backup", null);
        state.set("hub-version", 0);
        state.set("hub-arch", null);
        state.set("hub-road-start", null);
        saveState();
        return backup.size();
    }

    private void unbuildHub(CommandSender s) {
        World hub = pl.worlds().hub();
        if (hub == null || state.getStringList("hub-backup").isEmpty()) { s.sendMessage(mm("<red>Нечего откатывать")); return; }
        int n = restoreHub(hub);
        pl.getConfig().set("npcs.pvp", null);
        pl.saveConfig();
        pl.npcs().spawnAll();
        spawnHolos();
        s.sendMessage(mm("<green>Дорога, площадь и ворота на спавне убраны, блоки восстановлены (" + n + ")"));
    }

    // =====================================================================
    //                          ГОЛОГРАММЫ
    // =====================================================================
    private void spawnHolos() {
        for (TextDisplay t : holos) if (t.isValid()) t.remove();
        holos.clear();
        if (world != null && spawn != null) {
            holo(new Location(world, 0.5, spawn.getY() + 3.2, 52.5), 1.3f, "<gradient:#ff3355:#ff9900><bold>⚔ PvP-АРЕНА ⚔</bold></gradient>\n<green>🛡 Площадь - безопасная зона\n<gray>За её границей PvP, вещи выпадают\n<aqua>Шалкеры<gray>: <white>обычные<gray>, <aqua>редкие<gray>, <light_purple>эпические<gray>, <gold>легендарные<gray> - ломай ударами\n<red>В центре яма в Ад<gray> - там <light_purple>Элитный шалкер\n<dark_gray>Выход: /spawn");
            holo(new Location(world, 0.5, 70.5, 0.5), 1.6f, "<gradient:#ff2200:#ff8800><bold>☠ ЯМА В АД ☠</bold></gradient>\n<gray>Прыгай - внизу два прохода к логову\n<light_purple>Элитного шалкера");
            holo(new Location(world, 0.5, CAVE_FLOOR + 5.5, 9.5), 1.0f, "<red><bold>Ты в Аду</bold>\n<gray>Слева - <red>багровый проход</red>, справа - <aqua>проход душ\n<gray>Оба ведут к <light_purple>Элитному шалкеру\n<gold>Выход наверх - в его зале");
            holo(new Location(world, 1.0, CAVE_FLOOR + 3.4, CH_Z - 12.4), 1.0f, "<green><bold>⬆ Выход наверх</bold>\n<gray>встань в пузыри");
            int wy = state.getInt("exit-well-y", 66);
            holo(new Location(world, 1.0, wy + 4.6, CH_Z - 14.0), 1.0f, "<red><bold>Колодец из Ада</bold>\n<gray>здесь поднимаются выжившие");
        }
        World hub = pl.worlds().hub();
        String arch = state.getString("hub-arch"), road = state.getString("hub-road-start");
        if (hub != null && arch != null) {
            String[] a = arch.split(",");
            holo(new Location(hub, Double.parseDouble(a[0]) + 0.5, Double.parseDouble(a[1]) + 0.2, Double.parseDouble(a[2]) + 0.5), 1.5f, "<gradient:#ff3355:#ff9900><bold>⚔ PvP-АРЕНА ⚔</bold></gradient>\n<gray>Лут-шалкеры <dark_gray>•</dark_gray> <red>Яма в Ад</red> <dark_gray>•</dark_gray> <light_purple>Элитный шалкер\n<white>Нажми на воина в воротах");
        }
        if (hub != null && road != null) {
            String[] a = road.split(",");
            holo(new Location(hub, Double.parseDouble(a[0]) + 0.5, Double.parseDouble(a[1]), Double.parseDouble(a[2]) + 0.5), 1.1f, "<gradient:#ff3355:#ff9900><bold>⚔ PvP-Арена ⚔</bold></gradient>\n<gray>иди по красной дорожке");
        }
    }

    private TextDisplay holo(Location l, float scale, String text) {
        TextDisplay t = l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(scale, scale, scale), new org.joml.AxisAngle4f()));
            d.text(mm(text));
        });
        holos.add(t);
        return t;
    }

    // =====================================================================
    //                       ТАКТ: ЛУТ, ЭЛИТНЫЙ ШАЛКЕР
    // =====================================================================
    private void second() {
        if (!ready) return;
        long now = System.currentTimeMillis();
        // временные блоки игроков
        if (!temp.isEmpty()) {
            Iterator<Map.Entry<Long, Long>> it = temp.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() > now) continue;
                int[] p = unkey(e.getKey());
                Block b = world.getBlockAt(p[0], p[1], p[2]);
                if (!b.getType().isAir()) {
                    b.getWorld().spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.2, 0.2, 0.2, 0.01);
                    b.setType(Material.AIR, false);
                }
                it.remove();
            }
        }
        // частицы в шахте ямы
        if (!world.getPlayers().isEmpty()) {
            for (int i = 0; i < 6; i++) {
                double a = Math.random() * Math.PI * 2, rr = Math.random() * PIT_R;
                world.spawnParticle(Math.random() < 0.5 ? Particle.FLAME : Particle.LARGE_SMOKE, Math.cos(a) * rr + 0.5, CAVE_FLOOR + 10 + Math.random() * 30, Math.sin(a) * rr + 0.5, 0, 0, 0.08, 0, 1);
            }
        }
        spotsTick(now);
        for (UUID id : new ArrayList<>(wallShown.keySet())) { Player wp = Bukkit.getPlayer(id); if (wp == null) wallShown.remove(id); else updateWall(wp); }
        if (!world.getPlayers().isEmpty()) {
            double sr = safeR();
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(80, 255, 170), 1.3f);
            for (int i = 0; i < 56; i++) {
                double a = i * Math.PI * 2 / 56;
                world.spawnParticle(Particle.DUST, 0.5 + Math.cos(a) * sr, spawn.getY() + 0.15, 58.5 + Math.sin(a) * sr, 1, 0, 0, 0, 0, dust);
            }
        }
        eliteTick(now);
        // расписание элитного шалкера
        ZonedDateTime t = ZonedDateTime.now(MSK);
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm")), day = t.format(DateTimeFormatter.ISO_LOCAL_DATE);
        int warn = cfg().getInt("elite.warn-minutes", 5);
        String warnHm = t.plusMinutes(warn).format(DateTimeFormatter.ofPattern("HH:mm"));
        List<String> times = cfg().getStringList("elite.times");
        if (times.contains(warnHm) && !(day + warnHm).equals(warnedSlot) && elite == null) {
            warnedSlot = day + warnHm;
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<light_purple>☠ Через " + warn + " мин в Аду PvP-арены появится <bold>Элитный шалкер</bold>! <gray>Вход: NPC у купола или <white>/pvp"));
        }
        if (times.contains(hm) && !(day + hm).equals(lastSlot)) {
            lastSlot = day + hm;
            if (elite == null) spawnElite();
        }
    }

    // ---------- лут-шалкеры: редкости, удары, КД ----------
    private org.bukkit.configuration.ConfigurationSection rsec(String rarity) {
        org.bukkit.configuration.ConfigurationSection c = cfg().getConfigurationSection("shulkers." + rarity);
        return c != null ? c : new YamlConfiguration();
    }

    private Material rarityBlock(String rarity) {
        Material m = Material.matchMaterial(rsec(rarity).getString("block", ""));
        if (m != null && m.name().endsWith("SHULKER_BOX")) return m;
        return switch (rarity) {
            case "rare" -> Material.LIGHT_BLUE_SHULKER_BOX;
            case "epic" -> Material.PURPLE_SHULKER_BOX;
            case "legendary" -> Material.ORANGE_SHULKER_BOX;
            default -> Material.WHITE_SHULKER_BOX;
        };
    }

    private String rarityName(String rarity) {
        return rsec(rarity).getString("name", switch (rarity) {
            case "rare" -> "<aqua>Редкий";
            case "epic" -> "<light_purple>Эпический";
            case "legendary" -> "<gold>Легендарный";
            default -> "<white>Обычный";
        });
    }

    private int rarityHits(String rarity) {
        return Math.max(1, rsec(rarity).getInt("hits", switch (rarity) { case "rare" -> 35; case "epic" -> 60; case "legendary" -> 100; default -> 20; }));
    }

    private int rarityCd(String rarity) {
        return Math.max(5, rsec(rarity).getInt("cooldown-seconds", switch (rarity) { case "rare" -> 300; case "epic" -> 600; case "legendary" -> 1200; default -> 120; }));
    }

    private static String mmss(long sec) { return sec / 60 + ":" + String.format("%02d", sec % 60); }

    private void loadSpots() {
        for (Spot sp : spots) if (sp.holo != null && sp.holo.isValid()) sp.holo.remove();
        spots.clear();
        spotAt.clear();
        for (String line : state.getStringList("shulkers")) {
            String[] a = line.split(",");
            if (a.length < 4) continue;
            Spot sp = new Spot(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), a[3]);
            sp.hits = rarityHits(sp.rarity);
            spots.add(sp);
            spotAt.put(key(sp.x, sp.y, sp.z), sp);
            world.getBlockAt(sp.x, sp.y, sp.z).setType(rarityBlock(sp.rarity), false);
        }
    }

    private void spotHolo(Spot sp) {
        if (sp.holo != null && sp.holo.isValid()) sp.holo.remove();
        sp.holo = world.spawn(new Location(world, sp.x + 0.5, sp.y + 1.45, sp.z + 0.5), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setViewRange(0.4f);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.8f, 0.8f, 0.8f), new org.joml.AxisAngle4f()));
        });
    }

    private void holoText(Spot sp, String text) {
        if (sp.holo == null || !sp.holo.isValid()) spotHolo(sp);
        sp.holo.text(mm(text));
    }

    private void holoReady(Spot sp) {
        holoText(sp, "<bold>" + rarityName(sp.rarity) + " шалкер</bold>\n<gray>ломай ударами: <white>" + sp.hits + "<gray>/" + rarityHits(sp.rarity));
    }

    private void spotsTick(long now) {
        for (Spot sp : spots) {
            if (sp.readyAt > 0) {
                if (now >= sp.readyAt) { respawn(sp, true); continue; }
                holoText(sp, rarityName(sp.rarity) + " шалкер\n<gray>появится через <white>" + mmss((sp.readyAt - now) / 1000 + 1));
            } else if (sp.hits < rarityHits(sp.rarity) && now - sp.lastHit > 60_000) {
                sp.hits = rarityHits(sp.rarity);
                holoReady(sp);
            }
        }
    }

    private void respawn(Spot sp, boolean effect) {
        Block b = world.getBlockAt(sp.x, sp.y, sp.z);
        b.setType(rarityBlock(sp.rarity), false);
        sp.readyAt = 0;
        sp.hits = rarityHits(sp.rarity);
        holoReady(sp);
        if (effect) {
            Location c = b.getLocation().add(0.5, 0.5, 0.5);
            world.spawnParticle(Particle.REVERSE_PORTAL, c, 30, 0.4, 0.4, 0.4, 0.05);
            world.playSound(c, Sound.BLOCK_SHULKER_BOX_OPEN, 0.6f, 1.2f);
        }
    }

    private int respawnAll() {
        for (Spot sp : spots) respawn(sp, false);
        return spots.size();
    }

    private void breakSpot(Spot sp, Player p) {
        Block b = world.getBlockAt(sp.x, sp.y, sp.z);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        BlockData look = b.getBlockData();
        b.setType(Material.AIR, false);
        world.spawnParticle(Particle.BLOCK, c, 40, 0.35, 0.35, 0.35, look);
        boolean big = sp.rarity.equals("epic") || sp.rarity.equals("legendary");
        if (big) world.spawnParticle(Particle.TOTEM_OF_UNDYING, c, sp.rarity.equals("legendary") ? 90 : 40, 0.6, 0.6, 0.6, 0.4);
        world.playSound(c, Sound.ENTITY_SHULKER_DEATH, 1f, 1f);
        world.playSound(c, big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, big ? 0.8f : 0.6f, 1.2f);
        dropLoot(sp.rarity, c);
        int cd = rarityCd(sp.rarity);
        sp.readyAt = System.currentTimeMillis() + cd * 1000L;
        sp.hits = 0;
        holoText(sp, rarityName(sp.rarity) + " шалкер\n<gray>появится через <white>" + mmss(cd));
        pl.msg(p, "<green>Ты сломал " + rarityName(sp.rarity) + " шалкер<green>! <gray>Снова появится через <white>" + mmss(cd));
        if (big) for (Player o : world.getPlayers()) if (!o.equals(p)) pl.msg(o, "<gray>" + esc(p.getName()) + " сломал " + rarityName(sp.rarity) + " шалкер");
    }

    private void dropLoot(String rarity, Location c) {
        org.bukkit.configuration.ConfigurationSection sec = rsec(rarity);
        List<String> loot = sec.getStringList("loot");
        if (loot.isEmpty()) return;
        List<String[]> table = new ArrayList<>();
        double total = 0;
        for (String line : loot) {
            String[] a = line.trim().split("\\s+");
            double w = 1;
            try { if (a.length > 1) w = Double.parseDouble(a[1]); } catch (NumberFormatException ignored) {}
            total += w;
            table.add(new String[]{a[0], String.valueOf(total)});
        }
        int min = sec.getInt("items-min", 2), max = Math.max(min, sec.getInt("items-max", 4));
        Random rr = new Random();
        int n = min + rr.nextInt(max - min + 1);
        for (int i = 0; i < n; i++) {
            double roll = rr.nextDouble() * total;
            String spec = table.get(table.size() - 1)[0];
            for (String[] e : table) if (roll < Double.parseDouble(e[1])) { spec = e[0]; break; }
            ItemStack it;
            try { it = pl.parseItem(spec); } catch (Exception ex) { it = null; }
            if (it != null) world.dropItemNaturally(c, it);
        }
    }

    public boolean eliteRunning() { return elite != null; }
    public boolean stopElite() { if (elite == null) return false; removeElite(true); return true; }
    public List<MoreEvents.Active> eliteActive() {
        if (elite == null) return List.of();
        return List.of(new MoreEvents.Active("elite", MoreEvents.name("elite") + " <dark_gray>[<light_purple>ур. дропа " + eliteLevel() + "<dark_gray>]",
                elite.getLocation(), eliteStart, eliteUntil, "ударов " + eliteLeft + "/" + eliteTotal + ", исчезнет через", BossBar.Color.PURPLE));
    }

    /** Уровень дропа: +1 за каждые elite.level-online-step (20) игроков онлайн. */
    public int eliteLevel() {
        int step = Math.max(1, cfg().getInt("elite.level-online-step", 20));
        return Math.max(1, Math.min(cfg().getInt("elite.max-level", 6), 1 + Bukkit.getOnlinePlayers().size() / step));
    }

    public boolean spawnElite() {
        if (world == null) return false;
        removeElite(false);
        eliteStart = System.currentTimeMillis();
        elite = world.getBlockAt(CH_X, altarY, CH_Z);
        elite.setType(Material.BLACK_SHULKER_BOX, false);
        eliteTotal = eliteLeft = Math.max(1, cfg().getInt("elite.hits", 500));
        eliteUntil = System.currentTimeMillis() + cfg().getInt("elite.despawn-minutes", 30) * 60_000L;
        eliteHits.clear();
        eliteHolo = world.spawn(new Location(world, CH_X + 0.5, altarY + 1.6, CH_Z + 0.5), TextDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setAlignment(TextDisplay.TextAlignment.CENTER); d.setShadowed(true);
        });
        eliteBar = BossBar.bossBar(mm(""), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
        updateElite();
        world.strikeLightningEffect(elite.getLocation());
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<light_purple><bold>☠ ЭЛИТНЫЙ ШАЛКЕР</bold> появился в Аду PvP-арены! <gray>Сломай его киркой за <white>" + eliteTotal
                + "</white> ударов и забери элитный дроп <light_purple>(уровень " + eliteLevel() + ")</light_purple>. Вход: NPC у купола или <white>/pvp"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
        return true;
    }

    private void updateElite() {
        if (elite == null) return;
        if (eliteHolo != null && eliteHolo.isValid())
            eliteHolo.text(mm("<gradient:#b000ff:#ff00aa><bold>☠ ЭЛИТНЫЙ ШАЛКЕР ☠</bold></gradient>\n<gray>Ударов киркой осталось: <white><bold>" + eliteLeft + "</bold><gray>/" + eliteTotal
                    + "\n<light_purple>Уровень дропа: <white>" + eliteLevel() + " <dark_gray>(+1 за каждые " + Math.max(1, cfg().getInt("elite.level-online-step", 20)) + " онлайна)"
                    + "\n<dark_gray>исчезнет через " + Math.max(0, (eliteUntil - System.currentTimeMillis()) / 60_000) + " мин"));
        if (eliteBar != null) {
            eliteBar.name(mm("<light_purple>☠ Элитный шалкер <dark_gray>| <white>" + eliteLeft + "/" + eliteTotal + " <gray>ударов киркой"));
            eliteBar.progress(Math.max(0f, Math.min(1f, eliteLeft / (float) eliteTotal)));
        }
    }

    private void eliteTick(long now) {
        if (elite == null) return;
        if (elite.getType() != Material.BLACK_SHULKER_BOX) elite.setType(Material.BLACK_SHULKER_BOX, false);
        if (now > eliteUntil) {
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>☠ Элитный шалкер никто не сломал - он ушёл обратно в Бездну"));
            removeElite(true);
            return;
        }
        Location c = elite.getLocation().add(0.5, 0.5, 0.5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 12, 0.6, 0.6, 0.6, 0.02);
        world.spawnParticle(Particle.PORTAL, c, 20, 0.8, 0.8, 0.8, 0.3);
        for (Player p : world.getPlayers()) {
            boolean near = p.getLocation().distanceSquared(c) < 48 * 48;
            if (near) p.showBossBar(eliteBar); else p.hideBossBar(eliteBar);
        }
        if (now / 1000 % 10 == 0) updateElite();
    }

    private void removeElite(boolean effect) {
        if (elite != null && elite.getType() == Material.BLACK_SHULKER_BOX) {
            if (effect) world.spawnParticle(Particle.LARGE_SMOKE, elite.getLocation().add(0.5, 0.5, 0.5), 40, 0.5, 0.5, 0.5, 0.05);
            elite.setType(Material.AIR, false);
        }
        elite = null;
        if (eliteHolo != null && eliteHolo.isValid()) eliteHolo.remove();
        eliteHolo = null;
        if (eliteBar != null) { for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(eliteBar); eliteBar = null; }
    }

    private void breakElite(Player p) {
        Location c = elite.getLocation().add(0.5, 0.6, 0.5);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, c, 150, 1, 1, 1, 0.6);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        world.playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Random rr = new Random();
        int level = eliteLevel(), mult = Math.max(1, cfg().getInt("elite.loot-multiplier", 2));
        List<ItemStack> loot = new ArrayList<>();
        // базовый дроп x loot-multiplier
        for (int k = 0; k < mult; k++) for (String spec : cfg().getStringList("elite.drops")) {
            try { ItemStack it = pl.parseItem(spec); if (it != null) loot.add(it); } catch (Exception ignored) {}
        }
        // бонус по уровню дропа: сферы, талисманы, спец-предметы, донат-бафы, части Чешуи Левиафана (без нагрудника - полный сет не выпадает)
        int rolls = cfg().getInt("elite.bonus-rolls.base", 3) + cfg().getInt("elite.bonus-rolls.per-level", 2) * level;
        int cap = Math.max(1, Math.min(7, cfg().getInt("elite.sea-tier-cap.base", 2) + cfg().getInt("elite.sea-tier-cap.per-level", 1) * level));
        String pool = cfg().getString("elite.bonus-pool", "elite_bonus");
        List<ItemStack> bonus = new ArrayList<>();
        for (int k = 0; k < rolls; k++) { ItemStack it = pl.events().fromPool(pool); if (it != null) bonus.add(it); }
        loot.addAll(pl.events().capSea(bonus, cap));
        for (ItemStack it : loot) {
            org.bukkit.entity.Item drop = world.dropItem(c, it);
            drop.setVelocity(new org.bukkit.util.Vector((rr.nextDouble() - 0.5) * 0.6, 0.35 + rr.nextDouble() * 0.25, (rr.nextDouble() - 0.5) * 0.6));
            drop.getPersistentDataContainer().set(pl.events().lootKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
        for (String rw : cfg().getStringList("elite.rewards")) {
            try { pl.cases().grant(p, new Cases.Reward(rw, 1, Cases.Rarity.LEGENDARY)); } catch (Exception ignored) {}
        }
        UUID top = null; int best = 0;
        for (Map.Entry<UUID, Integer> e : eliteHits.entrySet()) if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        Player tp = top == null ? null : Bukkit.getPlayer(top);
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<light_purple><bold>☠ " + esc(p.getName()) + "</bold> сломал Элитного шалкера и забрал элитный дроп <white>(уровень " + level + ", " + loot.size() + " предм.)</white>!"
                + (tp != null && !tp.equals(p) ? " <gray>Больше всех ударов: <white>" + esc(tp.getName()) + " <gray>(" + best + ")" : "")));
        removeElite(false);
    }

    // =====================================================================
    //                              СОБЫТИЯ
    // =====================================================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || world == null || !isArena(b.getWorld())) return;
        if (elite != null && b.equals(elite)) { eliteHit(e); return; }
        Spot sp = spotAt.get(key(b.getX(), b.getY(), b.getZ()));
        if (sp == null || sp.readyAt > 0) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        int total = rarityHits(sp.rarity);
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) {
            p.sendActionBar(mm(rarityName(sp.rarity) + " шалкер <gray>- ломай ударами: <white>" + sp.hits + "</white>/" + total));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - hitCd.getOrDefault(p.getUniqueId(), 0L) < 120) return;
        hitCd.put(p.getUniqueId(), now);
        sp.hits--;
        sp.lastHit = now;
        p.sendActionBar(mm(rarityName(sp.rarity) + " шалкер <gray>- осталось ударов: <white>" + Math.max(0, sp.hits) + "</white>/" + total));
        world.playSound(b.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, 0.5f, 0.7f + 0.9f * (1 - Math.max(0, sp.hits) / (float) total));
        world.spawnParticle(Particle.CRIT, b.getLocation().add(0.5, 1, 0.5), 4, 0.3, 0.2, 0.3, 0.05);
        if (sp.hits <= 0) breakSpot(sp, p); else holoReady(sp);
    }

    private void eliteHit(PlayerInteractEvent e) {
        e.setCancelled(true);
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) { p.sendActionBar(mm("<red>Элитного шалкера ломают только киркой!")); return; }
        long now = System.currentTimeMillis();
        if (now - hitCd.getOrDefault(p.getUniqueId(), 0L) < 120) return;
        hitCd.put(p.getUniqueId(), now);
        eliteLeft--;
        eliteHits.merge(p.getUniqueId(), 1, Integer::sum);
        world.playSound(elite.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, 0.6f, 0.6f + (float) Math.random() * 0.4f);
        world.spawnParticle(Particle.CRIT, elite.getLocation().add(0.5, 1, 0.5), 6, 0.3, 0.3, 0.3, 0.1);
        p.sendActionBar(mm("<light_purple>☠ Элитный шалкер: <white>" + eliteLeft + "</white>/" + eliteTotal + " <gray>| твоих ударов: <white>" + eliteHits.get(p.getUniqueId())));
        if (eliteLeft % 10 == 0) updateElite();
        if (eliteLeft <= 0) breakElite(p);
    }

    private boolean admin(Player p) { return p.isOp() || p.hasPermission("anarchy.admin"); }

    private boolean isShulkerSpot(Block b) { return spotAt.containsKey(key(b.getX(), b.getY(), b.getZ())); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        Block b = e.getBlock();
        if (elite != null && b.equals(elite)) { e.setCancelled(true); return; }
        if (temp.remove(key(b.getX(), b.getY(), b.getZ())) != null) return;
        if (admin(e.getPlayer()) && e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        e.setCancelled(true);
        if (isShulkerSpot(b)) e.getPlayer().sendActionBar(mm("<aqua>Шалкер ломается ударами - бей по нему"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        if (admin(e.getPlayer()) && e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        Block b = e.getBlock();
        if (inSafeZone(b.getLocation().add(0.5, 0, 0.5))) { e.setCancelled(true); e.getPlayer().sendActionBar(mm("<green>🛡 На безопасной территории строить нельзя")); return; }
        temp.put(key(b.getX(), b.getY(), b.getZ()), System.currentTimeMillis() + cfg().getInt("temp-block-seconds", 60) * 1000L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        if (e.getBucket() == Material.LAVA_BUCKET) { e.setCancelled(true); e.getPlayer().sendActionBar(mm("<red>Лаву на арене выливать нельзя")); return; }
        if (inSafeZone(e.getBlock().getLocation().add(0.5, 0, 0.5))) { e.setCancelled(true); return; }
        Block b = e.getBlock();
        temp.put(key(b.getX(), b.getY(), b.getZ()), System.currentTimeMillis() + 15_000L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (isArena(e.getLocation().getWorld())) e.blockList().removeIf(b -> !temp.containsKey(key(b.getX(), b.getY(), b.getZ()))); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (isArena(e.getBlock().getWorld())) e.blockList().removeIf(b -> !temp.containsKey(key(b.getX(), b.getY(), b.getZ()))); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (isArena(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (isArena(e.getBlock().getWorld()) && e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (isArena(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { if (isArena(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (isArena(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) { if (isArena(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        Block b = e.getBlock();
        if (!temp.containsKey(key(b.getX(), b.getY(), b.getZ()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPush(org.bukkit.event.block.BlockPistonExtendEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        for (Block b : e.getBlocks()) if (!temp.containsKey(key(b.getX(), b.getY(), b.getZ()))) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(org.bukkit.event.block.BlockPistonRetractEvent e) {
        if (!isArena(e.getBlock().getWorld())) return;
        for (Block b : e.getBlocks()) if (!temp.containsKey(key(b.getX(), b.getY(), b.getZ()))) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) { if (isArena(e.getFrom().getWorld())) e.setCancelled(true); }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) { if (isArena(e.getFrom().getWorld())) e.setCancelled(true); }

    private double safeR() { return cfg().getDouble("safe-radius", 11); }

    /** Центр сферы безопасной зоны - середина площади на уровне пола. */
    private double safeCY() { return spawn.getY() - 1; }

    /** Оболочка сферы сразу за границей безопасной зоны (выше пола): x, y, z. */
    private List<int[]> wallRing() {
        if (wallRing != null) return wallRing;
        List<int[]> shell = new ArrayList<>();
        double sr = safeR(), cy = safeCY();
        int ir = (int) Math.ceil(sr) + 2, y0 = (int) Math.floor(cy) + 1, y1 = (int) Math.ceil(cy + sr) + 2;
        for (int x = -ir; x <= ir; x++) for (int z = 58 - ir; z <= 58 + ir; z++) for (int y = y0; y <= y1; y++) {
            double d = Math.sqrt(sq(x + 0.5 - 0.5) + sq(y + 0.5 - cy) + sq(z + 0.5 - 58.5));
            if (d > sr && d <= sr + 1.0) shell.add(new int[]{x, y, z});
        }
        return wallRing = shell;
    }

    /** Игроку в режиме боя у границы зоны показываем стеклянную стену (видит только он); вышел из боя или отошёл - стена пропадает. */
    private void updateWall(Player p) {
        UUID id = p.getUniqueId();
        if (world == null || spawn == null || !isArena(p.getWorld())) { wallShown.remove(id); return; }
        Set<Long> old = wallShown.getOrDefault(id, Set.of());
        Set<Long> now = new HashSet<>();
        Location l = p.getLocation();
        double ex = l.getX(), ey = l.getY() + 1, ez = l.getZ();
        if (pl.data(p).inCombat() && Math.sqrt(sq(ex - 0.5) + sq(ey - safeCY()) + sq(ez - 58.5)) < safeR() + 12)
            for (int[] c : wallRing())
                if (sq(ex - (c[0] + 0.5)) + sq(ey - (c[1] + 0.5)) + sq(ez - (c[2] + 0.5)) <= 72) now.add(key(c[0], c[1], c[2]));
        if (now.equals(old)) return;
        List<org.bukkit.block.BlockState> send = new ArrayList<>();
        for (long k : now) if (!old.contains(k)) {
            int[] q = unkey(k);
            Block b = world.getBlockAt(q[0], q[1], q[2]);
            if (!b.isPassable()) continue;
            org.bukkit.block.BlockState st = b.getState();
            st.setBlockData(WALL);
            send.add(st);
        }
        for (long k : old) if (!now.contains(k)) { int[] q = unkey(k); send.add(world.getBlockAt(q[0], q[1], q[2]).getState()); }
        if (!send.isEmpty()) p.sendBlockChanges(send);
        if (now.isEmpty()) wallShown.remove(id); else wallShown.put(id, now);
    }

    @EventHandler
    public void onQuitWall(org.bukkit.event.player.PlayerQuitEvent e) { wallShown.remove(e.getPlayer().getUniqueId()); }

    /** В режиме боя перка (и хорус) в безопасную зону не телепортирует. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(org.bukkit.event.player.PlayerTeleportEvent e) {
        String c = e.getCause().name();
        if (!c.equals("ENDER_PEARL") && !c.equals("CHORUS_FRUIT") && !c.equals("CONSUMABLE_EFFECT")) return;
        Player p = e.getPlayer();
        if (!inSafeZone(e.getTo()) || inSafeZone(e.getFrom()) || !pl.data(p).inCombat()) return;
        e.setCancelled(true);
        p.sendActionBar(mm("<red>⚔ В режиме боя перка в безопасную зону не телепортирует"));
    }

    /** Площадь появления - сфера: PvP выключено, урона и отбрасывания нет. Сверху тоже закрыто (заряд ветра не поможет). */
    public boolean inSafeZone(Location l) {
        if (spawn == null || l == null || !isArena(l.getWorld())) return false;
        double cy = safeCY();
        if (l.getY() < cy - 1) return false;
        double dy = Math.max(0, l.getY() - cy);
        return Math.sqrt(sq(l.getX() - 0.5) + sq(dy) + sq(l.getZ() - 58.5)) <= safeR();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSafeKnockback(org.bukkit.event.entity.EntityKnockbackEvent e) {
        if (e.getEntity() instanceof Player p && inSafeZone(p.getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isArena(e.getTo().getWorld()) || !e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (Math.hypot(to.getX(), to.getZ()) > 92 || to.getY() < -16) {
            p.teleport(spawn);
            pl.msg(p, "<red>С острова не уйти");
            return;
        }
        if (pl.data(p).inCombat() || wallShown.containsKey(p.getUniqueId())) updateWall(p);
        boolean was = inSafeZone(e.getFrom()), now = inSafeZone(to);
        if (now && !was) {
            if (pl.data(p).inCombat()) {
                e.setCancelled(true);
                org.bukkit.util.Vector out = e.getFrom().toVector().subtract(new org.bukkit.util.Vector(0.5, safeCY(), 58.5));
                out.setY(0);
                if (out.lengthSquared() < 1e-4) out = new org.bukkit.util.Vector(0, 0, 1);
                out.normalize().multiply(0.8).setY(0.25);
                p.setVelocity(out);
                p.sendActionBar(mm("<red>⚔ В режиме боя на безопасную территорию нельзя"));
                return;
            }
            p.sendActionBar(mm("<green>🛡 Безопасная территория <gray>- PvP выключено"));
        } else if (was && !now) {
            p.sendActionBar(mm("<red>⚔ Ты вышел за территорию <gray>- PvP включено!"));
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.7f, 1f);
        }
        double d = Math.hypot(to.getX() - 0.5, to.getZ() - 0.5);
        if (d < PIT_R + 0.5 && to.getY() < 62 && to.getY() > CAVE_FLOOR + 2) {
            if (!pitFall.containsKey(p.getUniqueId())) {
                p.showTitle(net.kyori.adventure.title.Title.title(mm("<dark_red><bold>☠ АД ☠"), mm("<gray>добро пожаловать вниз"),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(600))));
                p.playSound(p.getLocation(), Sound.AMBIENT_NETHER_WASTES_MOOD, 1f, 0.8f);
            }
            pitFall.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.FALL || !isArena(p.getWorld())) return;
        Long t = pitFall.remove(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 8000) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.LAVA, p.getLocation(), 20, 0.6, 0.1, 0.6, 0);
            p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.6f);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (isArena(e.getPlayer().getWorld()) && pl.spawn() != null && !e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(pl.spawn());
    }

    // =====================================================================
    //                              КОМАНДА
    // =====================================================================
    public void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        boolean adm = !(s instanceof Player pp) || admin(pp);
        if (adm && !sub.isEmpty()) {
            switch (sub) {
                case "rebuild" -> { state.set("arena-version", 0); saveState(); s.sendMessage(mm("<yellow>Арена будет перестроена при следующем запуске сервера")); return; }
                case "hub" -> { buildHub(); s.sendMessage(mm("<green>Дорога и NPC на спавне построены")); return; }
                case "unhub" -> { unbuildHub(s); return; }
                case "elite" -> {
                    if (a.length > 1 && a[1].equalsIgnoreCase("stop")) { removeElite(true); s.sendMessage(mm("<yellow>Элитный шалкер убран")); }
                    else { spawnElite(); s.sendMessage(mm("<green>Элитный шалкер призван")); }
                    return;
                }
                case "refill" -> { s.sendMessage(mm("<green>Все шалкеры арены появились снова: " + respawnAll())); return; }
                case "help" -> {
                    s.sendMessage(mm("<gold>/pvp <gray>- на арену  <gold>/pvp elite [stop] <gray>- элитный шалкер  <gold>/pvp refill <gray>- все шалкеры появятся сразу"));
                    s.sendMessage(mm("<gold>/pvp hub <gray>| <gold>/pvp unhub <gray>- дорога и NPC на спавне / откат  <gold>/pvp rebuild <gray>- перестроить арену"));
                    return;
                }
                default -> {}
            }
        }
        if (!(s instanceof Player p)) { s.sendMessage("/pvp help"); return; }
        if (!ready || spawn == null) { pl.msg(p, "<red>PvP-арена ещё готовится"); return; }
        if (pl.admin().isFrozen(p)) { pl.msg(p, "<red>Ты заморожен"); return; }
        if (pl.data(p).inCombat()) { pl.msg(p, "<red>Нельзя на арену в режиме боя"); return; }
        if (pl.bossArena() != null && pl.bossArena().isLocked(p)) { pl.msg(p, "<red>☠ С арены Стража не уйти, пока он жив!"); return; }
        Runnable go = () -> {
            if (!p.isOnline()) return;
            p.teleport(spawn);
            p.playSound(spawn, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f);
            p.showTitle(net.kyori.adventure.title.Title.title(mm("<gradient:#ff3355:#ff9900><bold>⚔ PvP-АРЕНА ⚔"), mm("<green>площадь - безопасная зона<gray>, за ней PvP"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(500))));
            pl.msg(p, "<gradient:#ff3355:#ff9900><bold>⚔ PvP-Арена</bold></gradient> <gray>- PvP включено, вещи выпадают. В центре яма в Ад к <light_purple>Элитному шалкеру"
                    + (elite != null ? " <green>(он сейчас там!)" : nextEliteText()));
        };
        go.run();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSafeDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inSafeZone(p.getLocation())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSafePvp(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v) || !isArena(v.getWorld())) return;
        Player a = e.getDamager() instanceof Player pp ? pp
                : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player ps ? ps : null;
        if (a == null || a.equals(v) || !inSafeZone(a.getLocation())) return;
        e.setCancelled(true);
        a.sendActionBar(mm("<green>🛡 С безопасной территории бить нельзя"));
    }

    private String nextEliteText() {
        List<String> times = cfg().getStringList("elite.times");
        if (times.isEmpty()) return "";
        String now = ZonedDateTime.now(MSK).format(DateTimeFormatter.ofPattern("HH:mm"));
        List<String> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        for (String t : sorted) if (t.compareTo(now) > 0) return " <gray>(следующий в <white>" + t + "</white> МСК)";
        return " <gray>(следующий в <white>" + sorted.get(0) + "</white> МСК)";
    }
}
