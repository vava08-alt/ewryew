package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

public final class Events implements Listener {
    private final AnarchyCore pl;
    private final NamespacedKey CHECK_COINS, CHECK_ARI, LOOT;
    private int rotation;
    // аирдроп
    private final List<Block> dropExtra = new ArrayList<>(); private final LinkedHashMap<Block, org.bukkit.block.data.BlockData> dropBlocks = new LinkedHashMap<>(); private boolean dropFilled;
    private Block drop; private TextDisplay dropHolo; private long dropUnlock, dropRemove; private boolean dropOpened; private Tier dropTier = Tier.COMMON;
    /** Аирдроп выглядит одиночным сундуком, а открывается как двойной: общий инвентарь на 54 слота */
    private static final class DropHolder implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;
        @Override public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }
    private org.bukkit.inventory.Inventory dropInv;

    public Events(AnarchyCore pl) {
        this.pl = pl;
        CHECK_COINS = new NamespacedKey(pl, "check_coins");
        CHECK_ARI = new NamespacedKey(pl, "check_ari");
        LOOT = new NamespacedKey(pl, "event_loot");
        nextAt = System.currentTimeMillis() + intervalMs();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20, 20);
    }

    public double coinMultiplier() { return pl.boosts().coinMult(); }

    public String status() {
        long now = System.currentTimeMillis();
        String bs = pl.bossArena() == null ? "" : pl.bossArena().status();
        if (!bs.isEmpty()) return bs;
        if (drop != null) return dropUnlock > now ? "<gold>⬛ Аирдроп " + (dropUnlock - now) / 1000 + "с" : "<gold>⬛ Аирдроп!";
        String more = pl.moreEvents().status();
        if (!more.isEmpty()) return more;
        return pl.boosts().status();
    }

    // ---------- расписание ----------
    private long nextAt; private boolean warned5, warned1;
    private long intervalMs() { return Math.max(5, pl.getConfig().getLong("events.interval-minutes", 30)) * 60_000L; }
    public long nextAt() { return nextAt; }
    public String nextType() { List<String> rot = pl.getConfig().getStringList("events.rotation"); return rot.isEmpty() ? null : rot.get(rotation % rot.size()); }
    public void skipTimer() { nextAt = System.currentTimeMillis() + 10_000; warned5 = warned1 = true; }

    private long nextExtraDrop;

    private void schedule() {
        long now = System.currentTimeMillis(), left = nextAt - now;
        long extra = pl.getConfig().getLong("events.airdrop.extra-interval-minutes", 20) * 60_000L;
        if (extra > 0) {
            if (nextExtraDrop == 0) nextExtraDrop = now + extra;
            if (now >= nextExtraDrop) {
                nextExtraDrop = now + extra;
                // дополнительный аирдроп, если сейчас нет другого и до основного ивента больше 3 минут
                if (drop == null && left > 180_000 && !Bukkit.getOnlinePlayers().isEmpty()) start("airdrop");
            }
        }
        String nt = nextType();
        if (nt == null) return;
        if (!warned5 && left <= 300_000 && left > 290_000) { warned5 = true; Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>Через <yellow>5 минут</yellow> ивент: </gray>" + MoreEvents.name(nt) + " <dark_gray>(/event delay)")); }
        if (!warned1 && left <= 60_000 && left > 50_000) { warned1 = true; Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>Через <yellow>1 минуту</yellow> ивент: </gray>" + MoreEvents.name(nt) + "<gray>! Готовься")); }
        if (left <= 0) {
            nextAt = now + intervalMs(); warned5 = warned1 = false;
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            rotation++;
            start(nt);
        }
    }

    /** Активные ивенты для боссбара и /event delay. */
    public List<MoreEvents.Active> active() {
        List<MoreEvents.Active> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String dn = MoreEvents.name("airdrop") + " <dark_gray>[" + dropTier.tag() + "<dark_gray>]";
        if (drop != null) out.add(dropUnlock > now
                ? new MoreEvents.Active("airdrop", dn, drop.getLocation(), dropStart, dropUnlock, "откроется через", dropTier.bar)
                : new MoreEvents.Active("airdrop", dn, drop.getLocation(), dropUnlock, dropRemove, "открыт, исчезнет через", BossBar.Color.GREEN));
        if (pl.bossArena() != null) out.addAll(pl.bossArena().active());
        return out;
    }
    private long dropStart;

    /** Остановить ивент. true - если он шёл */
    public boolean stop(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "airdrop" -> { if (drop == null) return false; clearDrop(); }
            case "boss" -> { return pl.bossArena() != null && pl.bossArena().stop(); }
            default -> { return pl.moreEvents().stop(type); }
        }
        return true;
    }

    public boolean start(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "airdrop" -> airdrop();
            case "boss" -> { return pl.bossArena() != null && pl.bossArena().start(); }
            default -> { return pl.moreEvents().start(type); }
        }
        return true;
    }

    public void announce(String title, String sub) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(mm(title), mm(sub), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1f);
        }
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + title + " <dark_gray>- " + sub));
    }

    private World world() {
        World w = Bukkit.getWorld(pl.getConfig().getString("rtp.world", "world"));
        return w == null ? Bukkit.getWorlds().get(0) : w;
    }

    /** Точка для следующего запуска ивента (/event start <тип> here) */
    public Location forced;
    private final java.util.Set<String> searching = new java.util.HashSet<>();

    public void randomSurface(String path, java.util.function.Consumer<Location> cb) {
        if (forced != null) { Location l = forced; forced = null; cb.accept(l); return; }
        if (!searching.add(path)) return;  // уже ищем место для этого ивента
        java.util.function.Consumer<Location> done = l -> { searching.remove(path); cb.accept(l); };
        World w = world();
        Location c = pl.spawn() != null && pl.spawn().getWorld().equals(w) ? pl.spawn() : w.getSpawnLocation();
        int min = pl.getConfig().getInt(path + ".min-radius", 300), max = pl.getConfig().getInt(path + ".max-radius", 2000);
        tryFind(w, c, min, Math.max(min + 1, max), 15, done);
    }

    private void tryFind(World w, Location c, int min, int max, int tries, java.util.function.Consumer<Location> cb) {
        if (tries <= 0) { cb.accept(null); return; }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI * 2);
        int dist = r.nextInt(min, max);
        int x = c.getBlockX() + (int) (Math.cos(a) * dist), z = c.getBlockZ() + (int) (Math.sin(a) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
            try {
                Block b = w.getHighestBlockAt(x, z);
                if (b.isLiquid() || b.getType().isAir() || pl.regions().at(b.getLocation()) != null
                        || pl.monuments() != null && pl.monuments().near(b.getLocation(), 45)) tryFind(w, c, min, max, tries - 1, cb);
                else cb.accept(b.getLocation().add(0, 1, 0));
            } catch (Throwable ex) { pl.getLogger().log(java.util.logging.Level.WARNING, "event place: " + ex, ex); }
        });
    }

    // ================= АИРДРОП =================
    private void airdrop() {
        if (drop != null) clearDrop();
        randomSurface("events.airdrop", loc -> {
            if (loc == null) return;
            Block b = loc.getBlock();
            dropStart = System.currentTimeMillis();
            Tier t = rollTier("airdrop");
            dropTier = t;
            fallAnimation(loc, t);
            dropBlocks.clear(); dropExtra.clear(); dropFilled = false;
            dropBlocks.putIfAbsent(b, b.getBlockData().clone());
            b.setBlockData(Bukkit.createBlockData("minecraft:barrel[facing=up]"), false);
            drop = b;
            DropHolder holder = new DropHolder();
            dropInv = Bukkit.createInventory(holder, 54, mm(t.tag("аирдроп")));
            holder.inv = dropInv;
            hold("airdrop", loc, 3);
            dropExtra.addAll(buildCache("airdrop", b, t, (bl, d) -> { dropBlocks.putIfAbsent(bl, bl.getBlockData().clone()); bl.setBlockData(d, false); }, this::cacheTouchable));
            dropOpened = false;
            dropUnlock = System.currentTimeMillis() + pl.getConfig().getLong("events.airdrop.unlock-seconds", 90) * 1000;
            dropRemove = dropUnlock + pl.getConfig().getLong("events.airdrop.despawn-minutes", 10) * 60_000;
            dropHolo = loc.getWorld().spawn(loc.clone().add(0.5, 1.8, 0.5), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.text(mm(t.tag("аирдроп")));
            });
            loc.getWorld().strikeLightningEffect(loc);
            announce("<gradient:#ffb000:#ff4000><bold>АИРДРОП</bold></gradient>",
                    t.tag("аирдроп") + " <white>- <yellow>" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + "</yellow> <gray>(" + loc.getWorld().getName() + ")");
        });
    }

    /** Купол и стропы парашюта аирдропа: остаются лежать рядом с бочкой, пока аирдроп не исчезнет. */
    private final List<org.bukkit.entity.Display> chute = new ArrayList<>();

    private static org.bukkit.util.Transformation tf(float tx, float ty, float tz, org.joml.Quaternionf rot, float sx, float sy, float sz) {
        return new org.bukkit.util.Transformation(new org.joml.Vector3f(tx, ty, tz), rot, new org.joml.Vector3f(sx, sy, sz), new org.joml.Quaternionf());
    }

    /** Бочка на парашюте цвета редкости спускается с неба (купол 3x3 + 4 стропы), после приземления купол опадает рядом. */
    private void fallAnimation(Location loc, Tier tier) {
        World w = loc.getWorld();
        clearChute();
        Location top = loc.clone().add(0.5, 45, 0.5);
        org.bukkit.entity.BlockDisplay barrel = w.spawn(top, org.bukkit.entity.BlockDisplay.class, d -> {
            d.setPersistent(false); d.setBlock(Bukkit.createBlockData("minecraft:barrel[facing=up]"));
            d.setTransformation(tf(-0.5f, 0, -0.5f, new org.joml.Quaternionf(), 1, 1, 1));
            d.setTeleportDuration(3); d.setViewRange(4f); d.setGlowing(true); d.setGlowColorOverride(tier.color);
        });
        // купол: 3x3 панели, центр выше - получается «шапка» парашюта
        List<org.bukkit.entity.BlockDisplay> canopy = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            float y = 3.6f - 0.32f * (Math.abs(dx) + Math.abs(dz));
            float fx = dx, fz = dz;
            canopy.add(w.spawn(top, org.bukkit.entity.BlockDisplay.class, d -> {
                d.setPersistent(false); d.setBlock(tier.wool.createBlockData());
                d.setTransformation(tf(fx * 1.05f - 0.55f, y, fz * 1.05f - 0.55f, new org.joml.Quaternionf(), 1.1f, 0.18f, 1.1f));
                d.setTeleportDuration(3); d.setViewRange(4f);
            }));
        }
        // стропы: тонкие нити от углов бочки к углам купола
        List<org.bukkit.entity.BlockDisplay> lines = new ArrayList<>();
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
            org.joml.Vector3f from = new org.joml.Vector3f(sx * 0.42f, 1f, sz * 0.42f), to = new org.joml.Vector3f(sx * 1.55f, 2.95f, sz * 1.55f);
            org.joml.Vector3f dir = new org.joml.Vector3f(to).sub(from);
            float len = dir.length();
            org.joml.Quaternionf rot = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 1, 0), dir.normalize());
            lines.add(w.spawn(top, org.bukkit.entity.BlockDisplay.class, d -> {
                d.setPersistent(false); d.setBlock(Material.WHITE_CONCRETE.createBlockData());
                d.setTransformation(tf(from.x, from.y, from.z, rot, 0.04f, len, 0.04f));
                d.setTeleportDuration(3); d.setViewRange(4f);
            }));
        }
        chute.addAll(canopy); chute.addAll(lines);
        new org.bukkit.scheduler.BukkitRunnable() {
            double y = 45;
            @Override public void run() {
                y -= 0.75;
                if (y <= 0 || !barrel.isValid()) {
                    barrel.remove(); cancel();
                    for (org.bukkit.entity.BlockDisplay l : lines) l.remove();
                    chute.removeAll(lines);
                    // купол медленно опадает на землю рядом с бочкой
                    Location ground = loc.clone().add(0.5, 0, 0.5);
                    int i = 0;
                    for (org.bukkit.entity.BlockDisplay c : canopy) {
                        if (!c.isValid()) continue;
                        c.teleport(ground);
                        int gx = i % 3 - 1, gz = i / 3 - 1; i++;
                        c.setInterpolationDelay(0); c.setInterpolationDuration(25);
                        c.setTransformation(tf(1.6f + gx * 1.0f, 0.02f + 0.03f * (Math.abs(gx) + Math.abs(gz)), gz * 1.0f - 0.5f, new org.joml.Quaternionf(), 1.0f, 0.06f, 1.0f));
                    }
                    w.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 0.5, 0.5), 40, 1, 0.2, 1, 0.05);
                    w.playSound(loc, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.7f);
                    return;
                }
                Location l = loc.clone().add(0.5 + Math.sin(y / 4) * 0.4, y, 0.5);
                barrel.teleport(l);
                for (org.bukkit.entity.BlockDisplay c : canopy) c.teleport(l);
                for (org.bukkit.entity.BlockDisplay c : lines) c.teleport(l);
                w.spawnParticle(Particle.FIREWORK, l.clone().add(0, -0.2, 0), 2, 0.2, 0, 0.2, 0.01);
            }
        }.runTaskTimer(pl, 0, 3);
    }

    private void clearChute() { for (org.bukkit.entity.Display d : chute) d.remove(); chute.clear(); }

    /** Заполняет аирдроп: минимум events.airdrop.min-items предметов, каждый в свой слот. */
    private void fillDrop() {
        if (dropFilled || drop == null) return;
        dropFilled = true;
        pl.mystery().start("airdrop", drop, dropInv, eventLoot(dropTier, "airdrop"), pl.getConfig().getDouble("events.airdrop.drip-seconds", 0.5), dropTier);
    }
    /** Весь лут ивента (ценный + ресурсы), перемешан: выдаётся по одному «???» предмету */
    public List<ItemStack> eventLoot(Tier t, String event) {
        List<ItemStack> all = new ArrayList<>(tierLoot(t, event));
        int rolls = pl.getConfig().getInt("events." + event + ".resource-rolls", 1);
        for (int i = 0; i < rolls; i++) all.addAll(resourceLoot(t, event));
        Collections.shuffle(all);
        return all;
    }
    private boolean dropEmpty() {
        Mystery.Feeder f = pl.mystery().feeder("airdrop");
        if (f != null && f.left() > 0) return false;
        if (dropInv != null && pl.mystery().hasLoot(dropInv)) return false;
        for (Block b : dropExtra) if (b.getState() instanceof Chest c && !c.getBlockInventory().isEmpty()) return false;
        return true;
    }
    private boolean isDropChest(Block b) { return b != null && drop != null && (b.equals(drop) || dropExtra.contains(b)); }

    /** Аирдроп опустел - убираем его сразу. */
    @EventHandler
    public void onDropClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        boolean main = e.getInventory().getHolder() instanceof DropHolder;
        if (drop == null || !dropOpened || !(main || e.getInventory().getHolder() instanceof Chest c && isDropChest(c.getBlock()))) return;
        Bukkit.getScheduler().runTask(pl, () -> {
            if (drop == null || !(drop.getState() instanceof org.bukkit.block.Barrel ch)) return;
            if (main && dropInv != null && dropInv.getViewers().isEmpty()) ch.close();
            if (!dropEmpty() || !ch.getInventory().getViewers().isEmpty() || dropInv != null && !dropInv.getViewers().isEmpty()) return;
            drop.getWorld().spawnParticle(Particle.CLOUD, drop.getLocation().add(0.5, 0.5, 0.5), 30, 0.4, 0.4, 0.4, 0.05);
            clearDrop();
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>⬛ Аирдроп полностью разграблен"));
        });
    }

    // ===== удержание чанков ивента: иначе голограммы (TextDisplay) пропадают при выгрузке чанка =====
    private record Held(World w, java.util.Set<Long> chunks) {}
    private final java.util.Map<String, Held> held = new java.util.HashMap<>();
    public void hold(String key, Location c, int r) {
        release(key);
        if (c == null || c.getWorld() == null) return;
        World w = c.getWorld();
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (int cx = (c.getBlockX() - r) >> 4; cx <= (c.getBlockX() + r) >> 4; cx++)
            for (int cz = (c.getBlockZ() - r) >> 4; cz <= (c.getBlockZ() + r) >> 4; cz++)
                if (s.add(((long) cx << 32) ^ (cz & 0xffffffffL))) w.addPluginChunkTicket(cx, cz, pl);
        held.put(key, new Held(w, s));
    }
    public void release(String key) {
        Held h = held.remove(key);
        if (h == null) return;
        for (long k : h.chunks()) {
            boolean other = false;
            for (Held o : held.values()) if (o.w().equals(h.w()) && o.chunks().contains(k)) { other = true; break; }
            if (!other) h.w().removePluginChunkTicket((int) (k >> 32), (int) k, pl);
        }
    }

    private void clearDrop() {
        if (!dropBlocks.isEmpty()) {
            List<Map.Entry<Block, org.bukkit.block.data.BlockData>> list = new ArrayList<>(dropBlocks.entrySet());
            Collections.reverse(list);
            for (var en : list) try {
                if (en.getKey().getState(false) instanceof org.bukkit.block.Container c) c.getInventory().clear();
                en.getKey().setBlockData(en.getValue(), false);
            } catch (Exception ignored) {}
            dropBlocks.clear();
        } else if (drop != null && drop.getType() == Material.BARREL) {
            if (drop.getState() instanceof org.bukkit.block.Container c) c.getInventory().clear();
            drop.setType(Material.AIR);
        }
        if (dropInv != null) {
            for (org.bukkit.entity.HumanEntity v : new ArrayList<>(dropInv.getViewers())) v.closeInventory();
            dropInv.clear();
            dropInv = null;
        }
        dropExtra.clear(); unguard("airdrop"); pl.mystery().stop("airdrop"); clearChute();
        if (dropHolo != null) dropHolo.remove();
        drop = null; dropHolo = null;
        release("airdrop");
    }

    // ================= РЕДКОСТИ И ЛУТ =================
    /** Редкость лута ивентов: обычный / редкий / эпический / божественный. */
    public enum Tier {
        COMMON("common", "Обычный", "<gradient:#f4f4f4:#a8a8a8>", Color.WHITE, BossBar.Color.WHITE, Material.WHITE_WOOL, Particle.END_ROD),
        RARE("rare", "Редкий", "<gradient:#5ef2ff:#1e7bff>", Color.fromRGB(0x2F6BFF), BossBar.Color.BLUE, Material.BLUE_WOOL, Particle.GLOW),
        EPIC("epic", "Эпический", "<gradient:#e070ff:#8a2cff>", Color.fromRGB(0xA040FF), BossBar.Color.PURPLE, Material.PURPLE_WOOL, Particle.WITCH),
        DIVINE("divine", "Божественный", "<gradient:#fff6a0:#ffc21a:#ff7a00>", Color.fromRGB(0xFFC21A), BossBar.Color.YELLOW, Material.YELLOW_WOOL, Particle.TOTEM_OF_UNDYING);
        public final String id, label, grad; public final Color color; public final BossBar.Color bar; public final Material wool; public final Particle particle;
        Tier(String id, String label, String grad, Color color, BossBar.Color bar, Material wool, Particle particle) {
            this.id = id; this.label = label; this.grad = grad; this.color = color; this.bar = bar; this.wool = wool; this.particle = particle;
        }
        /** «Эпический» цветом редкости */
        public String tag() { return grad + "<bold>" + label + "</bold></gradient>"; }
        /** «Эпический аирдроп» цветом редкости */
        public String tag(String noun) { return grad + "<bold>" + label + " " + noun + "</bold></gradient>"; }
        public static Tier of(String s) { for (Tier t : values()) if (t.id.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) return t; return null; }
    }

    /** Редкость самого ивента (Стрела Зевса, подземелье, турнир): events.<ивент>.event-rarity-weights или общие веса. */
    public Tier rollEventTier(String event) { return roll(pl.getConfig().getConfigurationSection("events." + event + ".event-rarity-weights")); }

    /** Случайная редкость по весам events.<ивент>.rarity-weights (или общим events.rarity-weights). */
    public Tier rollTier(String event) { return roll(event == null ? null : pl.getConfig().getConfigurationSection("events." + event + ".rarity-weights")); }

    private Tier roll(ConfigurationSection s) {
        if (s == null) s = pl.getConfig().getConfigurationSection("events.rarity-weights");
        double[] w = new double[Tier.values().length]; double total = 0;
        double[] def = {50, 30, 15, 5};
        for (Tier t : Tier.values()) { w[t.ordinal()] = Math.max(0, s == null ? def[t.ordinal()] : s.getDouble(t.id, 0)); total += w[t.ordinal()]; }
        if (total <= 0) return Tier.COMMON;
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (Tier t : Tier.values()) { r -= w[t.ordinal()]; if (r < 0) return t; }
        return Tier.COMMON;
    }

    /** Лут нужной редкости: events.loot.<редкость>.items + бонус ивента events.<ивент>.bonus.<редкость>, не меньше min-items предметов,
     *  плюс гарантированное events.loot.<редкость>.guaranteed и редкий дроп сверх лимита тира .jackpot. */
    public List<ItemStack> tierLoot(Tier t, String event) { return tierLoot(t, event, t); }

    /** То же, но тир сфер и талисманов ограничен редкостью самого ивента (eventTier). */
    public List<ItemStack> tierLoot(Tier t, String event, Tier eventTier) {
        String base = "events.loot." + t.id;
        List<String> table = new ArrayList<>(pl.getConfig().getStringList(base + ".items"));
        if (event != null) table.addAll(pl.getConfig().getStringList("events." + event + ".bonus." + t.id));
        List<ItemStack> items = rollTable(table, Math.max(1, pl.getConfig().getInt(base + ".min-items", 5)));
        items.addAll(guaranteed(base + ".guaranteed"));
        if (items.isEmpty()) items.add(new ItemStack(Material.GOLDEN_APPLE, 4));
        List<ItemStack> out = capSea(items, seaCap(eventTier));
        out.addAll(loot(pl.getConfig().getStringList(base + ".jackpot")));   // редкий дроп сверх лимита тира (Кракен в эпическом)
        return out;
    }

    /** Гарантированный лут: каждая строка списка (как в пулах, без шанса) выдаётся всегда. */
    public List<ItemStack> guaranteed(String path) {
        List<ItemStack> out = new ArrayList<>();
        for (String g : pl.getConfig().getStringList(path)) { ItemStack it = line(g + ":100", true); if (it != null) out.add(it); }
        return out;
    }

    /** Максимальный тир сфер/талисманов для ивента этой редкости: events.sea-tier-cap.<редкость>. */
    public int seaCap(Tier eventTier) {
        int[] def = {2, 4, 5, 7};
        Tier t = eventTier == null ? Tier.DIVINE : eventTier;
        return Math.max(1, Math.min(7, pl.getConfig().getInt("events.sea-tier-cap." + t.id, def[t.ordinal()])));
    }

    /** Сферы и талисманы выше cap заменяются на тир cap (той же линейки). */
    public List<ItemStack> capSea(List<ItemStack> items, int cap) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack it = items.get(i);
            String id = pl.items().id(it);
            if (!CustomItems.isOffhandItem(id) || CustomItems.seaTier(id) <= cap) continue;
            ItemStack low = pl.items().create(id.substring(0, id.indexOf('_') + 1) + CustomItems.SEA.get(Math.max(1, cap) - 1));
            if (low != null) items.set(i, low);
        }
        return items;
    }

    /** Бонус-лут списком (events.<ивент>.bonus) + строки по редкости ивента (<путь>-by-rarity.<редкость>), тир сфер ограничен. */
    public List<ItemStack> rarityBonus(String listPath, Tier eventTier) {
        List<String> table = new ArrayList<>(pl.getConfig().getStringList(listPath));
        table.addAll(pl.getConfig().getStringList(listPath + "-by-rarity." + eventTier.id));
        return capSea(loot(table), seaCap(eventTier));
    }

    /** Один предмет из пула events.pools.<имя> (по весам). */
    public ItemStack fromPool(String pool) { return line("pool:" + pool + ":100", true); }

    /** Строка лута (формат events.loot) - для других модулей. force - выдать без проверки шанса. */
    public ItemStack lootLine(String spec, boolean force) { return line(spec, force); }

    /** Ресурсы для доп. сундуков «маяка»: events.resources.<редкость> (+ events.<ивент>.resources.<редкость>). */
    public List<ItemStack> resourceLoot(Tier t, String event) {
        String base = "events.resources." + t.id;
        List<String> table = new ArrayList<>(pl.getConfig().getStringList(base + ".items"));
        if (event != null) table.addAll(pl.getConfig().getStringList("events." + event + ".resources." + t.id));
        List<ItemStack> items = rollTable(table, Math.max(1, pl.getConfig().getInt(base + ".min-items", 8)));
        if (items.isEmpty()) { items.add(new ItemStack(Material.IRON_INGOT, 16)); items.add(new ItemStack(Material.COOKED_BEEF, 16)); }
        return items;
    }

    /** Таблица строк лута (формат events.loot) с добором до min предметов - для других модулей. */
    public List<ItemStack> rollLoot(List<String> table, int min) { return rollTable(table, min); }

    /** Ролл таблицы лута + добор до min по шансам (редкие строки добираются редко). */
    private List<ItemStack> rollTable(List<String> table, int min) {
        List<ItemStack> items = loot(table);
        List<String> extra = new ArrayList<>(); List<Double> weights = new ArrayList<>(); double total = 0;
        for (String line : table) {
            // добор только из «частых» строк (шанс >= 25%), чтобы редкие предметы выпадали ровно с указанным шансом
            try { double c = Double.parseDouble(line.substring(line.lastIndexOf(':') + 1)); if (c >= 25 && c < 100) { extra.add(line); weights.add(c); total += c; } } catch (Exception ignored) {}
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 40 && items.size() < min && total > 0; i++) {
            double x = r.nextDouble(total); int k = 0;
            while (k < weights.size() - 1 && (x -= weights.get(k)) >= 0) k++;
            ItemStack it = line(extra.get(k), true);
            if (it != null) items.add(it);
        }
        Collections.shuffle(items);
        return items;
    }

    public void fillResources(List<Block> chests, Tier t, String event) {
        for (Block b : chests) if (b.getState() instanceof Chest c) fillChest(c.getBlockInventory(), resourceLoot(t, event));
    }

    // ===== «Маяк» ивента (по мотивам FunTime): маяк с лучом цвета редкости под главным сундуком + кольцо сундуков с ресурсами =====
    private final Map<String, List<Block>> guarded = new HashMap<>();
    public boolean isGuarded(Block b) { for (List<Block> l : guarded.values()) if (l.contains(b)) return true; return false; }
    public void unguard(String key) { guarded.remove(key); }
    private boolean cacheTouchable(Block b) {
        Material m = b.getType();
        if (m == Material.BEDROCK || m == Material.END_PORTAL_FRAME || m == Material.SPAWNER || b.isLiquid()) return false;
        if (b.getState(false) instanceof org.bukkit.block.TileState) return false;
        return pl.regions().at(b.getLocation()) == null;
    }
    private static Material glass(Tier t) {
        return switch (t) { case COMMON -> Material.WHITE_STAINED_GLASS; case RARE -> Material.LIGHT_BLUE_STAINED_GLASS; case EPIC -> Material.PURPLE_STAINED_GLASS; case DIVINE -> Material.YELLOW_STAINED_GLASS; };
    }
    /**
     * Строит под сундуком main маяк (стекло цвета редкости, маяк, основание 3x3) и ставит вокруг доп. сундуки.
     * put - поставить блок с запоминанием оригинала; ok - можно ли трогать блок. Возвращает доп. сундуки (пустые).
     */
    public List<Block> buildCache(String key, Block main, Tier t, java.util.function.BiConsumer<Block, org.bukkit.block.data.BlockData> put, java.util.function.Predicate<Block> ok) {
        List<Block> guard = new ArrayList<>(), chests = new ArrayList<>();
        String p = "events." + key + ".";
        if (pl.getConfig().getBoolean(p + "beacon", false) && main.getY() - 3 > main.getWorld().getMinHeight()) {
            Block g = main.getRelative(0, -1, 0), bc = main.getRelative(0, -2, 0);
            List<Block> base = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) base.add(main.getRelative(dx, -3, dz));
            boolean can = (ok.test(g) || g.getType() == Material.CHEST) && ok.test(bc);
            for (Block b : base) can &= ok.test(b);
            if (can) {
                Material bm = t == Tier.DIVINE ? Material.GOLD_BLOCK : Material.IRON_BLOCK;
                for (Block b : base) { put.accept(b, bm.createBlockData()); guard.add(b); }
                put.accept(bc, Material.BEACON.createBlockData()); guard.add(bc);
                put.accept(g, glass(t).createBlockData()); guard.add(g);
            }
        }
        guarded.put(key, guard);
        return chests;
    }

    /** Разложить предметы по случайным слотам инвентаря. */
    public void fillChest(org.bukkit.inventory.Inventory inv, List<ItemStack> items) {
        inv.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots);
        int i = 0;
        for (ItemStack it : items) { if (i >= slots.size()) break; inv.setItem(slots.get(i++), it); }
    }

    /** Метка «лут ивента» для предметов на земле - их не трогает очистка. */
    public NamespacedKey lootKey() { return LOOT; }
    public Item dropLoot(Location l, ItemStack it) {
        Item e = l.getWorld().dropItemNaturally(l, it);
        e.getPersistentDataContainer().set(LOOT, PersistentDataType.BYTE, (byte) 1);
        e.setGlowing(true);
        return e;
    }

    public List<ItemStack> loot(List<String> table) {
        List<ItemStack> out = new ArrayList<>();
        for (String line : table) {
            ItemStack it = line(line, false);
            if (it != null) out.add(it);
        }
        return out;
    }

    private static long range(String s) {
        int i = s.indexOf('-', 1);
        if (i < 0) return Long.parseLong(s.trim());
        long a = Long.parseLong(s.substring(0, i).trim()), b = Long.parseLong(s.substring(i + 1).trim());
        return a >= b ? a : ThreadLocalRandom.current().nextLong(a, b + 1);
    }

    /** Строка лута: ПРЕДМЕТ:кол-во:шанс (кол-во можно "a-b"), custom:id:шанс, pool:имя:шанс, coins/ari:сумма:шанс, key:кейс:n:шанс, enchant:id:ур:шанс, region:id:шанс */
    private ItemStack line(String line, boolean force) {
        String[] p = line.split(":");
        try {
            double chance = Double.parseDouble(p[p.length - 1]);
            if (!force && ThreadLocalRandom.current().nextDouble(100) >= chance) return null;
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "custom" -> { return pl.items().create(p[1]); }
                case "coins" -> { return check(range(p[1]), false); }
                case "ari" -> { return check(range(p[1]), true); }
                case "key" -> { return pl.cases().keyItem(p[1], (int) range(p[2])); }
                case "enchant" -> { return pl.enchants().book(p[1], (int) range(p[2])); }
                case "region" -> { return pl.regions().item(p[1]); }
                case "pool" -> {
                    List<String> pool = pl.getConfig().getStringList("events.pools." + p[1]);
                    if (pool.isEmpty()) { pl.getLogger().warning("Пустой пул лута: " + p[1]); return null; }
                    // пул: равный шанс или вес в конце записи после '*' (custom:sphere_fish*60)
                    double tw = 0; double[] w = new double[pool.size()];
                    for (int i = 0; i < w.length; i++) { String e = pool.get(i); int k = e.lastIndexOf('*'); w[i] = k > 0 ? Double.parseDouble(e.substring(k + 1)) : 1; tw += w[i]; }
                    double x = ThreadLocalRandom.current().nextDouble(tw); int pick = 0;
                    while (pick < w.length - 1 && (x -= w[pick]) >= 0) pick++;
                    String e = pool.get(pick); int k = e.lastIndexOf('*');
                    return line((k > 0 ? e.substring(0, k) : e) + ":100", true);
                }
                default -> {
                    Material m = Material.matchMaterial(p[0]);
                    if (m == null || !m.isItem()) { pl.getLogger().warning("Ошибка в луте (нет предмета): " + line); return null; }
                    return new ItemStack(m, (int) Math.max(1, Math.min(m.getMaxStackSize(), range(p[1]))));
                }
            }
        } catch (Exception e) { pl.getLogger().warning("Ошибка в луте: " + line); return null; }
    }

    /** Чек на монеты/Ari - ПКМ чтобы активировать. */
    public ItemStack check(long amount, boolean ari) {
        ItemStack it = new ItemStack(ari ? Material.AMETHYST_SHARD : Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.displayName(mm(ari ? "<!italic><light_purple><bold>Чек на " + amount + " " + pl.ariName() : "<!italic><gold><bold>Чек на " + amount + " " + pl.cur()));
        m.lore(List.of(mm("<!italic><gray>ПКМ - зачислить на баланс")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(ari ? CHECK_ARI : CHECK_COINS, PersistentDataType.LONG, amount);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() == EquipmentSlot.HAND && e.getItem() != null && e.getItem().hasItemMeta() && e.getAction().isRightClick()) {
            var pdc = e.getItem().getItemMeta().getPersistentDataContainer();
            Long coins = pdc.get(CHECK_COINS, PersistentDataType.LONG), ari = pdc.get(CHECK_ARI, PersistentDataType.LONG);
            if (coins != null || ari != null) {
                e.setCancelled(true);
                PlayerData d = pl.data(p);
                if (coins != null) { d.balance += coins; pl.msg(p, "<gold>+" + coins + " " + pl.cur() + " зачислено"); }
                if (ari != null) { d.ari += ari; pl.msg(p, "<light_purple>+" + ari + " " + pl.ariName() + " зачислено"); }
                e.getItem().setAmount(e.getItem().getAmount() - 1);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                return;
            }
        }
        if (e.getClickedBlock() != null && e.getAction().isRightClick() && isGuarded(e.getClickedBlock()) && e.getClickedBlock().getType() == Material.BEACON) { e.setCancelled(true); return; }
        if (drop != null && e.getClickedBlock() != null && isDropChest(e.getClickedBlock()) && e.getAction().isRightClick()) {
            long left = dropUnlock - System.currentTimeMillis();
            if (left > 0) { e.setCancelled(true); pl.msg(p, "<red>Аирдроп откроется через " + (left / 1000 + 1) + " сек"); return; }
            if (!dropOpened) {
                dropOpened = true;
                fillDrop();
                PlayerData d = pl.data(p);
                d.airdrops++;
                pl.quests().progress(p, "AIRDROP", null, 1);
                Bukkit.broadcast(mm("<gold>⬛ </gold>").append(pl.displayName(d)).append(mm(" <gold>первым вскрыл аирдроп!")));
            }
            if (e.getClickedBlock().equals(drop) && dropInv != null) {
                e.setCancelled(true);
                p.openInventory(dropInv);
                if (drop.getState() instanceof org.bukkit.block.Barrel ch) ch.open();
                p.playSound(drop.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 1f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (drop != null && e.getBlock().equals(drop)) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>Аирдроп нельзя сломать - открой его"); }
        else if (isGuarded(e.getBlock())) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>Маяк ивента нельзя ломать - он исчезнет сам"); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isGuarded); if (drop != null) e.blockList().remove(drop); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) { e.blockList().removeIf(this::isGuarded); if (drop != null) e.blockList().remove(drop); }

    // ================= тик =================
    private int ticks;
    private void tick() {
        ticks++;
        schedule();
        long now = System.currentTimeMillis();
        if (drop != null) {
            if (drop.getType() != Material.BARREL || now > dropRemove) { clearDrop(); }
            else {
                long left = (dropUnlock - now) / 1000;
                Mystery.Feeder fd = pl.mystery().feeder("airdrop");
                if (dropHolo != null) dropHolo.text(mm(left > 0 ? dropTier.tag("аирдроп") + "\n<white>Откроется через <yellow>" + left + "с"
                        : dropTier.tag("аирдроп") + "\n<green><bold>ОТКРЫТ" + (fd != null && fd.left() > 0 ? "\n<gray>Ещё предметов: <white>" + fd.left() + " <dark_gray>(+1 через " + (fd.nextIn() / 1000 + 1) + "с)" : "")));
                if (left <= 0 && !dropFilled) {
                    fillDrop();
                    Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gold>Аирдроп открыт! <yellow>" + drop.getX() + " " + drop.getY() + " " + drop.getZ()));
                }
                drop.getWorld().spawnParticle(dropTier.particle, drop.getLocation().add(0.5, 1, 0.5), dropTier == Tier.DIVINE ? 12 : 6, 0.15, 3, 0.15, 0.01);
            }
        }
    }

    public void shutdown() { clearDrop(); }
}
