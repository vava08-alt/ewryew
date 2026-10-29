package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/**
 * Монументы - маленькие данжи на карте RTP (5 видов, всего monuments.count = 20). Неломаемые, в каждом до 3 ящиков:
 * обычный, редкий, эпический (в эпическом - шанс на Элитру моря). Когда все ящики залутаны - монумент рассыпается,
 * рельеф возвращается как был. Буст «Восстановить все данжи» (/boost) ставит все монументы заново в новых местах.
 */
public final class Monuments implements Listener {
    private static final String[] TYPE_NAMES = {"Затонувший алтарь", "Руины маяка", "Святилище Кракена", "Обелиск Посейдона", "Обломки корабля"};
    private static final String[] TYPE_GRAD = {"<gradient:#5ef2ff:#1e7bff>", "<gradient:#ffffff:#ff5555>", "<gradient:#e070ff:#5a00b0>", "<gradient:#7fffd4:#ffd36b>", "<gradient:#d9a066:#6b4226>"};
    private static final String[] RARITY = {"common", "rare", "epic"};
    private static final String[] CRATE_NAME = {"<gradient:#f4f4f4:#a8a8a8><bold>Обычный ящик</bold></gradient>", "<gradient:#5ef2ff:#1e7bff><bold>Редкий ящик</bold></gradient>", "<gradient:#e070ff:#8a2cff><bold>Эпический ящик</bold></gradient>"};

    private static final class Crate { int x, y, z, rarity; boolean opened, looted; }
    private static final class Monument {
        String id, world; int type, x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
        final List<Crate> crates = new ArrayList<>();
        long removeAt;
        boolean contains(Block b) { return b.getWorld().getName().equals(world) && b.getX() >= minX && b.getX() <= maxX && b.getY() >= minY && b.getY() <= maxY && b.getZ() >= minZ && b.getZ() <= maxZ; }
        int left() { int n = 0; for (Crate c : crates) if (!c.looted) n++; return n; }
    }
    /** Шаблон постройки: блоки относительно якоря (y=0 - уровень пола на месте поверхности). */
    private static final class Tpl {
        final int type, r; int height; final String foundation;
        final LinkedHashMap<Long, String> blocks = new LinkedHashMap<>();
        final List<int[]> crates = new ArrayList<>();
        Tpl(int type, int r, String foundation) { this.type = type; this.r = r; this.foundation = foundation; }
        void set(int x, int y, int z, String d) { blocks.put(key(x, y, z), d); height = Math.max(height, y); }
        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String d) { for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) set(x, y, z, d); }
        void crate(int x, int y, int z, int rarity, String d) { set(x, y, z, d); crates.add(new int[]{x, y, z, rarity}); }
    }

    private final AnarchyCore pl;
    private final File file, dir;
    private final LinkedHashMap<String, Monument> all = new LinkedHashMap<>();
    private final Map<Long, List<Monument>> byChunk = new HashMap<>();
    private final Map<String, TextDisplay> holos = new HashMap<>();
    private boolean generating;
    private int ticks;
    /** Сколько монументов ещё нужно поставить (генерация, прерванная рестартом, продолжается) и кто оплатил буст. */
    private int pending; private String pendingBy;

    public Monuments(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "monuments.yml");
        dir = new File(pl.getDataFolder(), "monuments");
        boolean first = !file.exists();
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 100, 40);
        if (pl.getConfig().getBoolean("monuments.enabled", true) && (first || pending > 0))
            Bukkit.getScheduler().runTaskLater(pl, () -> generate(first ? target() : pending, first ? null : pendingBy, null), 20L * 60);
    }

    private int target() { return Math.max(0, pl.getConfig().getInt("monuments.count", 20)); }
    private String pre() { return pl.getConfig().getString("prefix", ""); }
    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    private static long ck(String world, int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL) ^ ((long) world.hashCode() << 16); }
    public int count() { return all.size(); }
    public boolean busy() { return generating; }

    // ===================== шаблоны =====================
    private static String pick(ThreadLocalRandom r, String... opts) { return opts[r.nextInt(opts.length)]; }

    private Tpl template(int type) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Tpl t;
        switch (type) {
            case 0 -> {   // Затонувший алтарь: призмарин, 4 колонны с фонарями, алтарь с эпическим сундуком
                t = new Tpl(0, 4, "minecraft:prismarine");
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
                    t.set(x, 0, z, Math.abs(x) == 4 || Math.abs(z) == 4 ? "minecraft:dark_prismarine" : r.nextInt(6) == 0 ? "minecraft:prismarine" : "minecraft:prismarine_bricks");
                for (int sx = -3; sx <= 3; sx += 6) for (int sz = -3; sz <= 3; sz += 6) {
                    t.fill(sx, 1, sz, sx, 3, sz, "minecraft:prismarine");
                    t.set(sx, 4, sz, "minecraft:sea_lantern");
                }
                for (int i = -2; i <= 2; i++) for (int s = -3; s <= 3; s += 6) { t.set(i, 4, s, "minecraft:prismarine_brick_slab[type=bottom]"); t.set(s, 4, i, "minecraft:prismarine_brick_slab[type=bottom]"); }
                t.fill(-1, 1, -1, 1, 1, 1, "minecraft:dark_prismarine");
                t.set(0, 1, 2, "minecraft:prismarine_brick_stairs[facing=north]");
                t.crate(0, 2, 0, 2, "minecraft:chest[facing=south]");
                t.crate(-2, 1, 2, 0, "minecraft:barrel[facing=up]");
                t.crate(2, 1, 2, 1, "minecraft:chest[facing=south]");
            }
            case 1 -> {   // Руины маяка: полосатая башня с обрушенным верхом, 2 ящика внутри
                t = new Tpl(1, 4, "minecraft:cobblestone");
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > 4.3) continue;
                    t.set(x, 0, z, d < 2.6 ? "minecraft:spruce_planks" : pick(r, "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks"));
                    if (d > 2.5 && d <= 3.5) {
                        double a = Math.atan2(z, x);
                        int h = 5 + (int) Math.round(2.4 * Math.sin(a * 3) + r.nextInt(2));
                        boolean door = z >= 3 && Math.abs(x) <= 0;
                        for (int y = 1; y <= h; y++) {
                            if (door && y <= 2) continue;
                            String band = ((y - 1) / 2) % 2 == 0 ? "minecraft:white_terracotta" : "minecraft:red_terracotta";
                            t.set(x, y, z, r.nextInt(7) == 0 ? "minecraft:cracked_stone_bricks" : band);
                        }
                    }
                }
                t.set(0, 3, 3, "minecraft:stone_brick_slab[type=top]");
                t.crate(-1, 1, -1, 0, "minecraft:barrel[facing=up]");
                t.crate(1, 1, -1, 1, "minecraft:chest[facing=south]");
                t.set(0, 1, -2, "minecraft:lantern[hanging=false]");
            }
            case 2 -> {   // Святилище Кракена: тёмная площадка, 4 щупальца сходятся над алтарём
                t = new Tpl(2, 5, "minecraft:cobbled_deepslate");
                for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > 5.4) continue;
                    t.set(x, 0, z, d >= 1.5 && d < 2.5 ? "minecraft:amethyst_block" : r.nextInt(8) == 0 ? "minecraft:cracked_deepslate_tiles" : pick(r, "minecraft:deepslate_tiles", "minecraft:polished_deepslate"));
                }
                int[][] path = {{4, 1}, {4, 2}, {4, 3}, {3, 4}, {3, 5}, {2, 6}, {1, 6}};
                for (int k = 0; k < 4; k++) for (int i = 0; i < path.length; i++) {
                    int a = path[i][0], y = path[i][1];
                    int x = k == 0 ? a : k == 1 ? -a : 0, z = k == 2 ? a : k == 3 ? -a : 0;
                    t.set(x, y, z, i == path.length - 1 ? "minecraft:crying_obsidian" : i % 2 == 0 ? "minecraft:purple_terracotta" : "minecraft:magenta_terracotta");
                }
                t.set(0, 1, 0, "minecraft:crying_obsidian");
                t.crate(0, 2, 0, 2, "minecraft:chest[facing=south]");
                t.crate(-2, 1, 3, 0, "minecraft:barrel[facing=up]");
                t.crate(2, 1, 3, 1, "minecraft:chest[facing=south]");
            }
            case 3 -> {   // Обелиск Посейдона: кварц и призмарин, золотая вершина
                t = new Tpl(3, 3, "minecraft:smooth_sandstone");
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                    t.set(x, 0, z, Math.abs(x) == 3 || Math.abs(z) == 3 ? "minecraft:prismarine_bricks" : "minecraft:quartz_bricks");
                t.fill(-1, 1, -1, 1, 2, 1, "minecraft:prismarine_bricks");
                t.fill(0, 3, 0, 0, 7, 0, "minecraft:dark_prismarine");
                t.set(0, 8, 0, "minecraft:gold_block");
                t.set(0, 9, 0, "minecraft:end_rod[facing=up]");
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) t.set(o[0], 3, o[1], "minecraft:prismarine_brick_stairs[facing=" + (o[0] == 1 ? "west" : o[0] == -1 ? "east" : o[1] == 1 ? "north" : "south") + "]");
                t.crate(-2, 1, 2, 0, "minecraft:barrel[facing=up]");
                t.crate(2, 1, 2, 1, "minecraft:chest[facing=south]");
            }
            default -> {   // Обломки корабля: корпус, мачта с рваным парусом, 3 ящика в трюме
                t = new Tpl(4, 5, "minecraft:sandstone");
                for (int x = -4; x <= 4; x++) {
                    t.set(x, 0, 0, "minecraft:dark_oak_planks");
                    for (int z = -1; z <= 1; z += 2) t.set(x, 0, z, "minecraft:spruce_planks");
                    for (int z = -2; z <= 2; z += 4) {
                        t.set(x, 1, z, r.nextInt(6) == 0 ? "minecraft:stripped_spruce_wood" : "minecraft:spruce_planks");
                        if (Math.abs(x) <= 3 && r.nextInt(5) != 0) t.set(x, 2, z, "minecraft:spruce_planks");
                    }
                }
                for (int z = -1; z <= 1; z++) { t.set(5, 1, z, "minecraft:dark_oak_planks"); t.set(-5, 1, z, "minecraft:dark_oak_planks"); }
                t.set(5, 2, 0, "minecraft:dark_oak_planks");
                t.fill(1, 1, 0, 1, 7, 0, "minecraft:spruce_log[axis=y]");
                t.fill(1, 6, -2, 1, 6, 2, "minecraft:spruce_log[axis=z]");
                for (int y = 3; y <= 5; y++) for (int z = -2; z <= 2; z++) if (z != 0 && r.nextInt(4) != 0) t.set(1, y, z, "minecraft:white_wool");
                t.crate(-3, 1, 0, 0, "minecraft:barrel[facing=up]");
                t.crate(-1, 1, -1, 1, "minecraft:chest[facing=south]");
                t.crate(3, 1, 0, 2, "minecraft:chest[facing=west]");
            }
        }
        return t;
    }

    // ===================== генерация =====================
    /** Поставить n монументов по одному (асинхронная загрузка чанков). by - кто восстановил (для объявления). */
    public void generate(int n, String by, Runnable done) {
        if (generating || n <= 0) { if (done != null) done.run(); return; }
        generating = true;
        pending = n; pendingBy = by;
        save();
        World w = Bukkit.getWorld(pl.getConfig().getString("rtp.world", "world"));
        if (w == null) { generating = false; return; }
        int[] placed = {0}, tries = {0};
        int maxTries = n * 25;
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (placed[0] >= n || tries[0] >= maxTries) {
                generating = false;
                pending = 0; pendingBy = null;
                save();
                pl.getLogger().info("Монументы: поставлено " + placed[0] + " (попыток " + tries[0] + "), всего на карте " + all.size());
                if (by != null) Bukkit.broadcast(mm(pre() + "<aqua>⌂ <white>" + AnarchyCore.esc(by) + " <aqua>восстановил все монументы! <white>" + all.size()
                        + " <aqua>данжей появились в новых местах карты RTP. <gray>В эпических ящиках - <gradient:#7ff9ff:#0077ff>Элитра моря</gradient>."));
                if (done != null) done.run();
                return;
            }
            tries[0]++;
            tryPlace(w, ok -> {
                if (ok) { placed[0]++; pending = Math.max(0, n - placed[0]); save(); }
                Bukkit.getScheduler().runTaskLater(pl, next[0], ok ? 30 : 2);
            });
        };
        next[0].run();
    }

    private void tryPlace(World w, java.util.function.Consumer<Boolean> cb) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = pl.spawn() != null && pl.spawn().getWorld().equals(w) ? pl.spawn() : w.getSpawnLocation();
        int min = pl.getConfig().getInt("monuments.min-radius", pl.getConfig().getInt("rtp.normal.min", 300));
        int max = Math.max(min + 1, pl.getConfig().getInt("monuments.max-radius", pl.getConfig().getInt("rtp.normal.max", 3000)));
        double a = r.nextDouble(Math.PI * 2);
        int dist = r.nextInt(min, max);
        int x = c.getBlockX() + (int) (Math.cos(a) * dist), z = c.getBlockZ() + (int) (Math.sin(a) * dist);
        double minD = pl.getConfig().getInt("monuments.min-distance", 200);
        for (Monument m : all.values()) if (m.world.equals(w.getName()) && Math.hypot(m.x - x, m.z - z) < minD) { cb.accept(false); return; }
        int type = r.nextInt(TYPE_NAMES.length);
        Tpl t = template(type);
        List<CompletableFuture<Chunk>> wait = new ArrayList<>();
        for (int cx = (x - t.r - 1) >> 4; cx <= (x + t.r + 1) >> 4; cx++) for (int cz = (z - t.r - 1) >> 4; cz <= (z + t.r + 1) >> 4; cz++) wait.add(w.getChunkAtAsync(cx, cz));
        CompletableFuture.allOf(wait.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(pl, () -> {
            boolean ok = false;
            try {
                Integer y = suitable(w, x, z, t);
                if (y != null) { build(w, x, y, z, t, StructureRotation.values()[r.nextInt(4)]); ok = true; }
            } catch (Exception e) { pl.getLogger().warning("Монумент: ошибка постройки - " + e); e.printStackTrace(); }
            cb.accept(ok);
        }));
    }

    /** Высота пола или null: ровно (±3), не вода, без приватов и чужих контейнеров на всей площади, не на ивентах. */
    private Integer suitable(World w, int x, int z, Tpl t) {
        int r = t.r;
        int y = Util.groundY(w, x, z);
        Block top = w.getBlockAt(x, y, z);
        if (top.isLiquid() || top.getType() == Material.ICE || top.getType() == Material.PACKED_ICE) return null;
        if (y < w.getMinHeight() + 10 || y > w.getMaxHeight() - 30) return null;
        int wet = 0, n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int gy = Util.groundY(w, x + dx, z + dz);
            Block b = w.getBlockAt(x + dx, gy, z + dz);
            n++;
            if (b.isLiquid()) wet++;
            if (Math.abs(gy - y) > 4) return null;
            for (int yy = y - 7; yy <= y + t.height + 2; yy++) {
                Block c = w.getBlockAt(x + dx, yy, z + dz);
                if (pl.regions().at(c.getLocation()) != null || c.getState(false) instanceof org.bukkit.block.TileState) return null;
                if (pl.bossArena() != null && pl.bossArena().isProtected(c) || pl.moreEvents() != null && pl.moreEvents().isMeteorBlock(c)) return null;
            }
        }
        return wet * 6 > n ? null : y;
    }

    private void build(World w, int ax, int ay, int az, Tpl t, StructureRotation rot) {
        Monument m = new Monument();
        m.id = Long.toString(System.currentTimeMillis(), 36) + ThreadLocalRandom.current().nextInt(100, 999);
        m.world = w.getName(); m.type = t.type; m.x = ax; m.y = ay; m.z = az;
        m.minX = ax - t.r; m.maxX = ax + t.r; m.minZ = az - t.r; m.maxZ = az + t.r;
        m.minY = ay - 7; m.maxY = ay + t.height + 2;
        LinkedHashMap<Block, BlockData> orig = new LinkedHashMap<>();
        java.util.function.BiConsumer<Block, BlockData> put = (b, d) -> { orig.putIfAbsent(b, b.getBlockData().clone()); b.setBlockData(d, false); };
        BlockData found = Bukkit.createBlockData(t.foundation);
        // фундамент под всем основанием и очистка места над ним
        Set<Long> floor = new HashSet<>();
        for (long k : t.blocks.keySet()) if ((k & 0xFFF) == 0) floor.add(k);
        for (int dx = -t.r; dx <= t.r; dx++) for (int dz = -t.r; dz <= t.r; dz++) {
            int[] p = rotate(dx, dz, rot);
            int x = ax + p[0], z = az + p[1];
            if (floor.contains(key(dx, 0, dz))) for (int y = ay - 1; y >= ay - 6; y--) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isSolid() && !b.isLiquid()) break;
                put.accept(b, found);
            }
            for (int y = ay + 1; y <= ay + t.height + 1; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir() && !(b.getState(false) instanceof org.bukkit.block.TileState)) put.accept(b, Material.AIR.createBlockData());
            }
        }
        for (Map.Entry<Long, String> en : t.blocks.entrySet()) {
            long k = en.getKey();
            int dx = (int) (k >> 38) << 6 >> 6, dz = (int) ((k >> 12) & 0x3FFFFFF) << 6 >> 6, dy = (int) (k & 0xFFF) << 20 >> 20;
            int[] p = rotate(dx, dz, rot);
            BlockData d = Bukkit.createBlockData(en.getValue());
            try { d.rotate(rot); } catch (Throwable ignored) {}
            put.accept(w.getBlockAt(ax + p[0], ay + dy, az + p[1]), d);
        }
        for (int[] c : t.crates) {
            int[] p = rotate(c[0], c[2], rot);
            Crate cr = new Crate();
            cr.x = ax + p[0]; cr.y = ay + c[1]; cr.z = az + p[1]; cr.rarity = c[3];
            m.crates.add(cr);
            BlockState st = w.getBlockAt(cr.x, cr.y, cr.z).getState();
            if (st instanceof org.bukkit.Nameable nm) { nm.customName(mm(CRATE_NAME[cr.rarity])); st.update(true, false); }
            if (st instanceof Container ct) ct.getInventory().clear();
        }
        saveOrig(m, orig);
        all.put(m.id, m);
        index(m);
        save();
    }

    private static int[] rotate(int x, int z, StructureRotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    private void index(Monument m) {
        for (int cx = m.minX >> 4; cx <= m.maxX >> 4; cx++) for (int cz = m.minZ >> 4; cz <= m.maxZ >> 4; cz++)
            byChunk.computeIfAbsent(ck(m.world, cx, cz), k -> new ArrayList<>()).add(m);
    }
    private void unindex(Monument m) {
        for (List<Monument> l : byChunk.values()) l.remove(m);
        byChunk.values().removeIf(List::isEmpty);
    }

    private Monument at(Block b) {
        List<Monument> l = byChunk.get(ck(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4));
        if (l == null) return null;
        for (Monument m : l) if (m.contains(b)) return m;
        return null;
    }
    /** Блок монумента (для защиты от ивентов, взрывов и т.п.). */
    public boolean isProtected(Block b) { return at(b) != null; }
    /** Есть ли монумент ближе r блоков (ивенты не ставятся вплотную). */
    public boolean near(Location l, double r) {
        for (Monument m : all.values()) if (m.world.equals(l.getWorld().getName()) && Math.hypot(m.x - l.getX(), m.z - l.getZ()) < r) return true;
        return false;
    }
    private Crate crate(Monument m, Block b) { for (Crate c : m.crates) if (c.x == b.getX() && c.y == b.getY() && c.z == b.getZ()) return c; return null; }

    // ===================== лут ящиков =====================
    private List<ItemStack> loot(int rarity) {
        String base = "monuments.loot." + RARITY[rarity];
        List<ItemStack> items = pl.events().rollLoot(pl.getConfig().getStringList(base + ".items"), Math.max(1, pl.getConfig().getInt(base + ".min-items", 4)));
        items.addAll(pl.events().guaranteed(base + ".guaranteed"));
        Events.Tier cap = rarity == 0 ? Events.Tier.COMMON : rarity == 1 ? Events.Tier.RARE : Events.Tier.EPIC;
        List<ItemStack> out = pl.events().capSea(items, pl.events().seaCap(cap));
        out.addAll(pl.events().loot(pl.getConfig().getStringList(base + ".jackpot")));   // редкий дроп сверх лимита тира
        return out;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        if (!(b.getState(false) instanceof Container)) return;
        Monument m = at(b);
        if (m == null) return;
        Crate c = crate(m, b);
        if (c == null || c.opened) return;
        if (!(b.getState() instanceof Container ct)) return;
        c.opened = true;
        pl.events().fillChest(ct.getInventory(), loot(c.rarity));
        save();
        Location l = b.getLocation().add(0.5, 1, 0.5);
        Events.Tier tier = c.rarity == 0 ? Events.Tier.COMMON : c.rarity == 1 ? Events.Tier.RARE : Events.Tier.EPIC;
        b.getWorld().spawnParticle(tier.particle, l, 25, 0.3, 0.4, 0.3, 0.05);
        b.getWorld().playSound(l, Sound.BLOCK_TRIAL_SPAWNER_OPEN_SHUTTER, 1f, 1.2f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Location loc = e.getInventory().getLocation();
        if (loc == null || !(e.getInventory().getHolder() instanceof Container)) return;
        Block b = loc.getBlock();
        Monument m = at(b);
        if (m == null) return;
        Crate c = crate(m, b);
        if (c == null || !c.opened || c.looted) return;
        Inventory inv = e.getInventory();
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!inv.isEmpty() || !inv.getViewers().isEmpty() || c.looted) return;
            c.looted = true;
            save();
            b.getWorld().spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 0.6, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
            if (m.left() == 0 && m.removeAt == 0) {
                m.removeAt = System.currentTimeMillis() + pl.getConfig().getInt("monuments.remove-delay-seconds", 10) * 1000L;
                for (Player p : b.getWorld().getPlayers()) if (p.getLocation().distanceSquared(b.getLocation()) < 64 * 64)
                    pl.msg(p, "<aqua>⌂ " + TYPE_GRAD[m.type] + TYPE_NAMES[m.type] + "</gradient> <gray>опустел и рассыпется через " + (m.removeAt - System.currentTimeMillis()) / 1000 + " сек.");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        Location l = e.getSource().getLocation();
        if (l != null && at(l.getBlock()) != null) e.setCancelled(true);
    }

    // ===================== тик: голограммы, частицы, распад =====================
    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (Monument m : new ArrayList<>(all.values())) {
            World w = Bukkit.getWorld(m.world);
            if (w == null) continue;
            if (m.removeAt > 0 && now >= m.removeAt) { crumble(m, true); continue; }
            if (!w.isChunkLoaded(m.x >> 4, m.z >> 4)) { TextDisplay h = holos.remove(m.id); if (h != null) h.remove(); continue; }
            boolean near = false;
            Location anchor = new Location(w, m.x + 0.5, m.y + 1, m.z + 0.5);
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(anchor) < 56 * 56) { near = true; break; }
            TextDisplay h = holos.get(m.id);
            if (!near) { if (h != null) { h.remove(); holos.remove(m.id); } continue; }
            int height = m.maxY - 2 - m.y;
            if (h == null || !h.isValid()) {
                h = w.spawn(anchor.clone().add(0, height + 1.2, 0), TextDisplay.class, d -> { d.setPersistent(false); d.setBillboard(Display.Billboard.CENTER); d.setShadowed(true); });
                holos.put(m.id, h);
            }
            h.text(mm("<bold>" + TYPE_GRAD[m.type] + "⌂ " + TYPE_NAMES[m.type] + "</gradient></bold>\n<gray>Ящиков: <white>" + m.left() + "<gray>/" + m.crates.size()
                    + (m.removeAt > 0 ? "\n<red>Рассыпается через " + Math.max(0, (m.removeAt - now) / 1000) + " сек" : "\n<dark_gray>Монумент нельзя сломать")));
            for (Crate c : m.crates) {
                if (c.looted) continue;
                Particle pt = c.rarity == 2 ? Particle.WITCH : c.rarity == 1 ? Particle.GLOW : Particle.END_ROD;
                w.spawnParticle(pt, c.x + 0.5, c.y + 1.1, c.z + 0.5, c.rarity == 2 ? 6 : 3, 0.25, 0.2, 0.25, 0.01);
            }
            if (m.removeAt > 0) w.spawnParticle(Particle.BLOCK, anchor, 30, 2.5, 1.5, 2.5, Material.PRISMARINE_BRICKS.createBlockData());
        }
    }

    /** Убрать монумент и вернуть рельеф. */
    private void crumble(Monument m, boolean effect) {
        World w = Bukkit.getWorld(m.world);
        all.remove(m.id);
        unindex(m);
        TextDisplay h = holos.remove(m.id);
        if (h != null) h.remove();
        if (w != null) {
            for (Crate c : m.crates) if (w.getBlockAt(c.x, c.y, c.z).getState(false) instanceof Container ct) ct.getInventory().clear();
            List<String> lines = readOrig(m);
            Collections.reverse(lines);
            for (String s : lines) {
                try {
                    int bar = s.indexOf('|'); String[] c = s.substring(0, bar).split(",");
                    Block b = w.getBlockAt(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                    if (b.getState(false) instanceof Container ct) ct.getInventory().clear();
                    b.setBlockData(Bukkit.createBlockData(s.substring(bar + 1)), false);
                } catch (Exception ignored) {}
            }
            if (effect) {
                Location l = new Location(w, m.x + 0.5, m.y + 1, m.z + 0.5);
                w.spawnParticle(Particle.CLOUD, l, 80, 3, 2, 3, 0.05);
                w.playSound(l, Sound.BLOCK_DEEPSLATE_BRICKS_BREAK, 2f, 0.6f);
                w.playSound(l, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1f, 1.2f);
            }
        }
        new File(dir, m.id + ".txt").delete();
        save();
    }

    /** Убрать все монументы и поставить target() новых в других местах (буст за Ari). */
    public boolean regenerateAll(String by) {
        if (generating) return false;
        for (Monument m : new ArrayList<>(all.values())) crumble(m, false);
        generate(target(), by, null);
        return true;
    }

    public void shutdown() {
        for (TextDisplay h : holos.values()) h.remove();
        holos.clear();
        save();
    }

    // ===================== хранение =====================
    private void saveOrig(Monument m, LinkedHashMap<Block, BlockData> orig) {
        dir.mkdirs();
        StringBuilder sb = new StringBuilder();
        for (var en : orig.entrySet()) { Block b = en.getKey(); sb.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ()).append('|').append(en.getValue().getAsString()).append('\n'); }
        try { Files.writeString(new File(dir, m.id + ".txt").toPath(), sb.toString(), StandardCharsets.UTF_8); }
        catch (Exception ex) { pl.getLogger().warning("monument orig: " + ex.getMessage()); }
    }

    private List<String> readOrig(Monument m) {
        try {
            File f = new File(dir, m.id + ".txt");
            if (!f.exists()) return new ArrayList<>();
            List<String> l = new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            l.removeIf(String::isBlank);
            return l;
        } catch (Exception ex) { pl.getLogger().warning("monument orig read: " + ex.getMessage()); return new ArrayList<>(); }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        pending = Math.max(0, y.getInt("pending", 0));
        pendingBy = y.getString("pending-by");
        ConfigurationSection s = y.getConfigurationSection("monuments");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            try {
                Monument m = new Monument();
                m.id = id; m.world = c.getString("world", "world"); m.type = Math.max(0, Math.min(TYPE_NAMES.length - 1, c.getInt("type")));
                String[] p = c.getString("pos", "0,0,0").split(",");
                m.x = Integer.parseInt(p[0]); m.y = Integer.parseInt(p[1]); m.z = Integer.parseInt(p[2]);
                String[] bx = c.getString("box", "0,0,0,0,0,0").split(",");
                m.minX = Integer.parseInt(bx[0]); m.minY = Integer.parseInt(bx[1]); m.minZ = Integer.parseInt(bx[2]);
                m.maxX = Integer.parseInt(bx[3]); m.maxY = Integer.parseInt(bx[4]); m.maxZ = Integer.parseInt(bx[5]);
                for (String line : c.getStringList("crates")) {
                    String[] q = line.split(",");
                    Crate cr = new Crate();
                    cr.x = Integer.parseInt(q[0]); cr.y = Integer.parseInt(q[1]); cr.z = Integer.parseInt(q[2]); cr.rarity = Integer.parseInt(q[3]);
                    cr.opened = q.length > 4 && q[4].equals("1"); cr.looted = q.length > 5 && q[5].equals("1");
                    m.crates.add(cr);
                }
                if (!m.crates.isEmpty() && m.left() == 0) m.removeAt = System.currentTimeMillis() + 5000;
                all.put(id, m);
                index(m);
            } catch (Exception ex) { pl.getLogger().warning("Монумент " + id + ": " + ex.getMessage()); }
        }
        pl.getLogger().info("Монументов на карте: " + all.size());
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pending", pending);
        y.set("pending-by", pendingBy);
        y.set("monuments", null);
        for (Monument m : all.values()) {
            String k = "monuments." + m.id + ".";
            y.set(k + "type", m.type); y.set(k + "world", m.world);
            y.set(k + "pos", m.x + "," + m.y + "," + m.z);
            y.set(k + "box", m.minX + "," + m.minY + "," + m.minZ + "," + m.maxX + "," + m.maxY + "," + m.maxZ);
            List<String> cr = new ArrayList<>();
            for (Crate c : m.crates) cr.add(c.x + "," + c.y + "," + c.z + "," + c.rarity + "," + (c.opened ? 1 : 0) + "," + (c.looted ? 1 : 0));
            y.set(k + "crates", cr);
        }
        try { y.save(file); } catch (Exception ex) { pl.getLogger().warning("monuments.yml: " + ex.getMessage()); }
    }

    // ===================== /monuments =====================
    public void command(CommandSender s, String[] a) {
        boolean admin = !(s instanceof Player p) || p.hasPermission("anarchy.admin");
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (!admin || sub.isEmpty()) {
            int crates = 0; for (Monument m : all.values()) crates += m.left();
            s.sendMessage(mm(pre() + "<aqua>⌂ Монументов на карте: <white>" + all.size() + "<gray>/" + target() + " <dark_gray>(ящиков осталось: " + crates + ")"));
            s.sendMessage(mm(" <gray>Ищи их в мире RTP (" + pl.getConfig().getInt("rtp.normal.min", 300) + "-" + pl.getConfig().getInt("rtp.normal.max", 3000)
                    + " блоков от центра). В эпических ящиках шанс на <gradient:#7ff9ff:#0077ff>Элитру моря</gradient>. Восстановить все: <yellow>/boost"));
            if (admin) s.sendMessage(mm(" <dark_gray>Админ: /monuments list | tp <номер> | regen | remove <номер|all> | gen <кол-во>"));
            return;
        }
        List<Monument> l = new ArrayList<>(all.values());
        switch (sub) {
            case "list" -> {
                int i = 1;
                for (Monument m : l) s.sendMessage(mm(" <white>" + i++ + ". " + TYPE_GRAD[m.type] + TYPE_NAMES[m.type] + "</gradient> <gray>" + m.x + " " + m.y + " " + m.z + " <dark_gray>ящиков " + m.left() + "/" + m.crates.size()));
                if (l.isEmpty()) s.sendMessage(mm(" <gray>Монументов нет. <white>/monuments gen 20"));
            }
            case "tp" -> {
                if (!(s instanceof Player p) || a.length < 2) { s.sendMessage(mm("<gray>/monuments tp <номер>")); return; }
                int i = parse(a[1]) - 1;
                if (i < 0 || i >= l.size()) { s.sendMessage(mm("<red>Нет такого номера")); return; }
                Monument m = l.get(i);
                World w = Bukkit.getWorld(m.world);
                if (w != null) p.teleport(new Location(w, m.x + 0.5, m.maxY + 1, m.z + 0.5));
            }
            case "regen" -> s.sendMessage(mm(regenerateAll(s.getName()) ? "<green>Монументы пересоздаются..." : "<red>Уже идёт генерация"));
            case "remove" -> {
                if (a.length < 2) { s.sendMessage(mm("<gray>/monuments remove <номер|all>")); return; }
                if (a[1].equalsIgnoreCase("all")) { for (Monument m : l) crumble(m, false); s.sendMessage(mm("<yellow>Все монументы убраны")); return; }
                int i = parse(a[1]) - 1;
                if (i < 0 || i >= l.size()) { s.sendMessage(mm("<red>Нет такого номера")); return; }
                crumble(l.get(i), true);
                s.sendMessage(mm("<yellow>Монумент убран"));
            }
            case "gen" -> {
                int n = a.length > 1 ? parse(a[1]) : Math.max(0, target() - all.size());
                if (generating) { s.sendMessage(mm("<red>Уже идёт генерация")); return; }
                generate(n, null, null);
                s.sendMessage(mm("<green>Ставлю монументов: " + n + " <gray>(по одному, смотри консоль)"));
            }
            default -> s.sendMessage(mm("<gray>/monuments list | tp <номер> | regen | remove <номер|all> | gen <кол-во>"));
        }
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }

    // ===================== защита =====================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (at(e.getBlock()) == null) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(mm("<red>Монумент нельзя сломать - залутай ящики, и он рассыпется сам"));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (at(e.getBlockPlaced()) != null) { e.setCancelled(true); e.getPlayer().sendActionBar(mm("<red>У монумента нельзя ставить блоки")); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (at(e.getBlockClicked().getRelative(e.getBlockFace())) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (!byChunk.isEmpty()) e.blockList().removeIf(b -> at(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (!byChunk.isEmpty()) e.blockList().removeIf(b -> at(b) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(b -> at(b) != null || at(b.getRelative(e.getDirection())) != null)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(b -> at(b) != null)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (at(e.getToBlock()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (at(e.getBlock()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent e) {
        if (byChunk.isEmpty()) return;
        Block b = e.getBlock();
        for (int dx = -8; dx <= 8; dx += 8) for (int dz = -8; dz <= 8; dz += 8) if (at(b.getRelative(dx, 0, dz)) != null) { e.setCancelled(true); return; }
    }
}
