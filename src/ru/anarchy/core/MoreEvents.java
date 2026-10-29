package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/** Метеорит, PvP-турнир (+ подключение Подземелья дьявола и Стрелы Зевса) + боссбар ивентов (в радиусе 20 блоков), /event delay, /event track. */
public final class MoreEvents implements Listener {
    public record Active(String type, String name, Location loc, long start, long end, String phase, BossBar.Color color) {}

    public static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("airdrop", "<gradient:#ffb000:#ff4000>⬛ Аирдроп</gradient>");
        NAMES.put("boss", "<gradient:#7000ff:#ff0040>☠ Босс «Страж Бездны»</gradient>");
        NAMES.put("meteor", "<gradient:#ff4400:#ffcc00>☄ Метеорит</gradient>");
        NAMES.put("zeus", "<gradient:#fff7a8:#4fc3ff>⚡ Стрела Зевса</gradient>");
        NAMES.put("dungeon", "<gradient:#ff2a2a:#7a0000>☠ Подземелье дьявола</gradient>");
        NAMES.put("tournament", "<gradient:#ffd700:#ff4400>⚔ PvP-турнир</gradient>");
        NAMES.put("elite", "<gradient:#b000ff:#ff00aa>☠ Элитный шалкер</gradient>");
    }
    public static String name(String type) { return NAMES.getOrDefault(type.toLowerCase(Locale.ROOT), type); }

    private final AnarchyCore pl;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Set<UUID> tracking = new HashSet<>();
    private int ticks;

    public MoreEvents(AnarchyCore pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20, 20);
        Bukkit.getScheduler().runTask(pl, this::restorePending);
        Bukkit.getScheduler().runTask(pl, this::restoreCrater);
        Bukkit.getScheduler().runTaskLater(pl, this::arena, 100);
    }

    private String pre() { return pl.getConfig().getString("prefix", ""); }
    private int cfg(String k, int def) { return pl.getConfig().getInt("events." + k, def); }
    public static int barRadius() { return AnarchyCore.INSTANCE.getConfig().getInt("events.bossbar-radius", 20); }
    public static boolean near(Player p, Location l) { int r = barRadius(); return l != null && p.getWorld().equals(l.getWorld()) && p.getLocation().distanceSquared(l) <= r * r; }
    public static String mmss(long ms) { long s = Math.max(0, ms / 1000); return s >= 3600 ? (s / 3600) + ":" + String.format("%02d:%02d", s % 3600 / 60, s % 60) : String.format("%02d:%02d", s / 60, s % 60); }
    private static String xyz(Location l) { return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(); }

    public boolean start(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "meteor" -> meteor();
            case "tournament" -> tournament();
            case "dungeon" -> { return pl.dungeon() != null && pl.dungeon().start(); }
            case "zeus" -> { return pl.zeus() != null && pl.zeus().start(); }
            case "elite" -> { return pl.pvp() != null && !pl.pvp().eliteRunning() && pl.pvp().spawnElite(); }
            default -> { return false; }
        }
        return true;
    }

    public boolean stop(String type) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "meteor" -> { if (meteorLoc == null) return false; meteorClear(); }
            case "tournament" -> { if (tState == 0) return false; tEnd(); }
            case "dungeon" -> { return pl.dungeon() != null && pl.dungeon().stop(); }
            case "zeus" -> { return pl.zeus() != null && pl.zeus().stop(); }
            case "elite" -> { return pl.pvp() != null && pl.pvp().stopElite(); }
            default -> { return false; }
        }
        return true;
    }

    public String status() { List<Active> a = active(); return a.isEmpty() ? "" : a.get(0).name(); }

    public List<Active> active() {
        List<Active> out = new ArrayList<>();
        if (meteorLoc != null) {
            String mn = name("meteor") + " <dark_gray>[" + meteorTier.tag() + "<dark_gray>]";
            if (meteorAt > 0) out.add(new Active("meteor", mn, meteorLoc, meteorStart, meteorAt, "падение через", BossBar.Color.RED));
            else out.add(new Active("meteor", mn, meteorChest != null ? meteorChest.getLocation() : meteorLoc, meteorAt0, meteorRemove, "ядро остынет через", meteorTier.bar));
        }
        if (tState == 1) out.add(new Active("tournament", name("tournament") + " <dark_gray>[" + tTier.tag() + "<dark_gray>]", null, tStart, tTime, "набор, /event join", BossBar.Color.YELLOW));
        if (tState == 2) out.add(new Active("tournament", name("tournament") + " <dark_gray>[" + tTier.tag() + "<dark_gray>]", null, tStart, tTime, "бой, осталось " + tPlayers.size() + " игр.", BossBar.Color.YELLOW));
        if (pl.dungeon() != null) out.addAll(pl.dungeon().active());
        if (pl.zeus() != null) out.addAll(pl.zeus().active());
        if (pl.pvp() != null) out.addAll(pl.pvp().eliteActive());
        return out;
    }

    private List<Active> all() { List<Active> l = new ArrayList<>(pl.events().active()); l.addAll(active()); return l; }

    // ===================== /event =====================
    public void command(org.bukkit.command.CommandSender s, String[] a) {
        String sub = a.length == 0 ? "delay" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player x ? x : null;
        switch (sub) {
            case "join" -> {
                if (p == null) return;
                if (tState != 1 && pl.dungeon() != null && pl.dungeon().isOpen()) { pl.dungeon().joinCommand(p); return; }
                join(p);
            }
            case "leave" -> {
                if (p == null) return;
                if (inTournament(p)) { eliminate(p, false); pl.msg(p, "<yellow>Ты покинул турнир, вещи возвращены"); }
                else if (pl.dungeon() != null && pl.dungeon().inDungeon(p)) pl.dungeon().leave(p, true);
            }
            case "poseidon" -> { if (p != null && pl.dungeon() != null) pl.dungeon().poseidon(p); }
            case "start", "spawn" -> {
                if (!admin(s)) { delay(s); return; }
                if (a.length < 2) { s.sendMessage(mm("<gray>/event start <" + String.join("|", NAMES.keySet()) + "> [here] <dark_gray>- here = прямо у тебя")); return; }
                String t = a[1].toLowerCase(Locale.ROOT);
                if (!NAMES.containsKey(t)) { s.sendMessage(mm("<red>Нет такого ивента. Есть: " + String.join(", ", NAMES.keySet()))); return; }
                if (all().stream().anyMatch(x -> x.type().equals(t))) {
                    if (a.length > 2 && (a[2].equalsIgnoreCase("force") || a[2].equalsIgnoreCase("here"))) pl.events().stop(t);
                    else { s.sendMessage(mm("<yellow>" + name(t) + " <yellow>уже идёт. Перезапустить: <white>/event start " + t + " here</white> или <white>/event start " + t + " force</white>, остановить: <white>/event stop " + t)); return; }
                }
                boolean here = a.length > 2 && a[2].equalsIgnoreCase("here") && p != null;
                if (here) {
                    if (t.equals("tournament") || t.equals("dungeon") || t.equals("elite")) s.sendMessage(mm("<gray>Этот ивент без точки - запускаю обычный"));
                    else pl.events().forced = p.getLocation().getBlock().getLocation();
                }
                if (!pl.events().start(t)) { pl.events().forced = null; s.sendMessage(mm("<red>Не удалось запустить " + name(t) + " <red>(уже идёт или мир не готов)")); return; }
                s.sendMessage(mm("<green>Ивент " + name(t) + " <green>запущен" + (here ? " у тебя" : "") + (!here && (t.equals("zeus") || t.equals("meteor") || t.equals("airdrop") || t.equals("boss")) ? " <gray>(ищу место...)" : "")));
            }
            case "stop" -> {
                if (!admin(s)) { delay(s); return; }
                if (a.length < 2) { for (Active x : all()) { pl.events().stop(x.type()); } s.sendMessage(mm("<yellow>Все ивенты остановлены")); return; }
                s.sendMessage(mm(pl.events().stop(a[1]) ? "<yellow>Ивент остановлен" : "<red>Этот ивент сейчас не идёт"));
            }
            case "skip" -> { if (admin(s)) { pl.events().skipTimer(); s.sendMessage(mm("<green>Следующий ивент запустится через 10 секунд")); } }
            case "loot" -> {
                if (!admin(s)) { delay(s); return; }
                Events.Tier t = a.length > 2 ? Events.Tier.of(a[2]) : null;
                if (t == null) { s.sendMessage(mm("<gray>/event loot <airdrop|meteor|boss|zeus> <common|rare|epic|divine> [кол-во] <dark_gray>- пробные броски лута сундука, ничего не выдаётся")); return; }
                int n = 1;
                try { if (a.length > 3) n = Math.max(1, Math.min(500, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) {}
                lootPreview(s, a[1].toLowerCase(Locale.ROOT), t, n);
            }
            default -> delay(s);
        }
    }

    /** /event loot: пробные броски основного лута сундука (без ресурсов) - сколько в среднем сфер, талисманов, спец-предметов, зелий и бафов. */
    private void lootPreview(org.bukkit.command.CommandSender s, String event, Events.Tier t, int n) {
        Set<String> special = poolIds("special"), don = poolIds("don_potions");
        double[] c = new double[8];
        int noSphere = 0;
        Map<String, Integer> one = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            boolean sphere = false;
            for (ItemStack it : pl.events().tierLoot(t, event)) {
                c[5]++;
                String id = pl.items().id(it);
                if (n == 1) one.merge(id != null ? id : it.getType().name().toLowerCase(Locale.ROOT), it.getAmount(), Integer::sum);
                if (id == null) continue;
                if (id.endsWith("_kraken")) c[6]++;
                if (id.endsWith("_poseidon")) c[7]++;
                if (id.startsWith("sphere_")) { c[0]++; sphere = true; }
                else if (id.startsWith("talisman_")) c[1]++;
                else if (special.contains(id)) c[2]++;
                else if (don.contains(id)) c[3]++;
                else if (id.startsWith("buff_")) c[4]++;
            }
            if (!sphere) noSphere++;
        }
        java.util.function.DoubleFunction<String> f = v -> String.format(Locale.US, "%.1f", v / n);
        s.sendMessage(mm("<gold>Лут: </gold>" + name(event) + " <dark_gray>[" + t.tag() + "<dark_gray>] <gray>x" + n + " <dark_gray>(основной лут, без ресурсов)"));
        s.sendMessage(mm("<gray>За сундук: <white>сферы " + f.apply(c[0]) + "<gray>, талисманы <white>" + f.apply(c[1]) + "<gray>, спец-предметы <white>" + f.apply(c[2])
                + "<gray>, донат-зелья <white>" + f.apply(c[3]) + "<gray>, свитки бафов <white>" + f.apply(c[4]) + "<gray>, всего <white>" + f.apply(c[5])
                + "<gray>; Кракен <white>" + String.format(Locale.US, "%.1f%%", 100.0 * c[6] / n) + "<gray>, Посейдон <white>" + String.format(Locale.US, "%.1f%%", 100.0 * c[7] / n) + " <dark_gray>(сферы и талисманы на 100 сундуков)"
                + (n > 1 ? "<gray>; без сфер: <white>" + String.format(Locale.US, "%.1f%%", 100.0 * noSphere / n) : "")));
        if (n == 1) {
            List<String> parts = new ArrayList<>();
            one.forEach((k, v) -> parts.add(v > 1 ? k + " x" + v : k));
            s.sendMessage(mm("<dark_gray>" + String.join(", ", parts)));
        }
    }

    private Set<String> poolIds(String pool) {
        Set<String> out = new HashSet<>();
        for (String e : pl.getConfig().getStringList("events.pools." + pool)) {
            int k = e.lastIndexOf('*');
            String v = k > 0 ? e.substring(0, k) : e;
            if (v.startsWith("custom:")) out.add(v.substring(7));
        }
        return out;
    }

    private boolean admin(org.bukkit.command.CommandSender s) { return !(s instanceof Player p) || p.isOp() || p.hasPermission("anarchy.admin") || pl.admin().staff(p); }

    public void delay(org.bukkit.command.CommandSender s) {
        long now = System.currentTimeMillis();
        s.sendMessage(mm("<dark_gray>▬▬▬▬▬▬▬ <gradient:#00f0ff:#0080ff><bold>ИВЕНТЫ</bold></gradient> <dark_gray>▬▬▬▬▬▬▬"));
        List<Active> list = all();
        if (list.isEmpty()) s.sendMessage(mm(" <gray>Сейчас ивентов нет."));
        for (Active a : list) {
            String where = a.loc() == null ? (a.type().equals("tournament") ? "<white>арена <dark_gray>(/event join)" : "<white>весь сервер")
                    : "<white>" + xyz(a.loc()) + " <dark_gray>(" + worldName(a.loc().getWorld()) + ")";
            s.sendMessage(mm(" <green>● </green>" + a.name() + " <dark_gray>| <gray>" + a.phase() + " <yellow>" + mmss(a.end() - now)));
            String dist = s instanceof Player p && a.loc() != null && p.getWorld().equals(a.loc().getWorld()) ? " <gray>- до него <aqua>" + (int) p.getLocation().distance(a.loc()) + " бл." : "";
            s.sendMessage(mm("    <gray>Где: " + where + dist));
        }
        String nt = pl.events().nextType();
        if (nt != null) s.sendMessage(mm(" <gray>Следующий ивент: </gray>" + name(nt) + " <gray>через <yellow>" + mmss(pl.events().nextAt() - now)));
        s.sendMessage(mm(" <dark_gray>Все: аирдроп, метеорит, босс, стрела Зевса, подземелье дьявола, PvP-турнир, элитный шалкер"));
        s.sendMessage(mm(" <dark_gray>Редкость лута: <white>обычный</white>, <aqua>редкий</aqua>, <light_purple>эпический</light_purple>, <gold>божественный"));
        if (pl.monuments() != null) s.sendMessage(mm(" <dark_gray>Монументов на карте: <aqua>" + pl.monuments().count() + " <dark_gray>(/monuments)"));
        if (s instanceof Player) {
            if (tState == 1) s.sendMessage(mm(" ").append(mm("<gold>[⚔ Войти в турнир]").clickEvent(ClickEvent.runCommand("/event join"))));
            if (pl.dungeon() != null && pl.dungeon().isOpen()) s.sendMessage(mm(" ").append(mm("<red>[☠ Портал в подземелье - на спавне]").clickEvent(ClickEvent.runCommand("/event join"))));
            if (admin(s)) s.sendMessage(mm(" <dark_gray>Админ: /event start <тип> [here|force], /event stop [тип], /event skip"));
        }
    }

    private static String worldName(World w) {
        if (w.getName().equals("arena")) return "арена";
        if (AnarchyCore.INSTANCE.pvp() != null && AnarchyCore.INSTANCE.pvp().isArena(w)) return "Ад PvP-арены, /pvp";
        if (AnarchyCore.INSTANCE.worlds().isHub(w)) return "спавн";
        if (AnarchyCore.INSTANCE.dungeon() != null && AnarchyCore.INSTANCE.dungeon().isDungeon(w)) return "подземелье";
        return switch (w.getEnvironment()) { case NETHER -> "ад"; case THE_END -> "энд"; default -> "обычный мир"; };
    }

    // ===================== боссбар (только рядом с ивентом) =====================
    private void barTick() {
        List<Active> list = all();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Active best = null; double bd = Double.MAX_VALUE;
            for (Active a : list) {
                if (a.type().equals("zeus") || a.type().equals("boss") || a.type().equals("elite") || a.type().equals("dungeon") && pl.dungeon().inDungeon(p)) continue; // у них свой подробный боссбар
                if (a.type().equals("tournament")) { if (inArena(p.getLocation())) { best = a; break; } continue; }
                if (a.loc() == null || !near(p, a.loc())) continue;
                double d = a.loc().distanceSquared(p.getLocation());
                if (d < bd) { bd = d; best = a; }
            }
            BossBar b = bars.get(p.getUniqueId());
            if (best == null || !pl.data(p).authed) { if (b != null) p.hideBossBar(b); continue; }
            if (b == null) { b = BossBar.bossBar(mm(""), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS); bars.put(p.getUniqueId(), b); }
            b.name(mm(best.name() + " <dark_gray>| <gray>" + best.phase() + " <yellow>" + mmss(best.end() - now)));
            b.color(best.color());
            b.progress((float) Math.max(0, Math.min(1, (best.end() - now) / (double) Math.max(1, best.end() - best.start()))));
            p.showBossBar(b);
        }
    }

    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    private void trackTick() {
        if (tracking.isEmpty()) return;
        List<Active> list = all();
        for (UUID u : tracking) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Active best = null; double bd = Double.MAX_VALUE;
            for (Active a : list) if (a.loc() != null && a.loc().getWorld().equals(p.getWorld())) { double d = a.loc().distanceSquared(p.getLocation()); if (d < bd) { bd = d; best = a; } }
            if (best == null) { p.sendActionBar(mm("<gray>Нет ивентов в этом мире <dark_gray>(/event delay)")); continue; }
            Vector to = best.loc().toVector().subtract(p.getLocation().toVector());
            double ang = Math.toDegrees(Math.atan2(-to.getX(), to.getZ())) - p.getLocation().getYaw();
            int idx = (int) Math.round(((ang % 360 + 360) % 360) / 45.0) % 8;
            p.sendActionBar(mm(best.name() + " <yellow><bold>" + ARROWS[idx] + "</bold> <white>" + (int) Math.sqrt(bd) + " бл. <dark_gray>| <gray>" + best.phase() + " " + mmss(best.end() - System.currentTimeMillis())));
        }
    }

    // ===================== МЕТЕОРИТ =====================
    private Location meteorLoc; private long meteorStart, meteorAt, meteorAt0, meteorRemove; private Block meteorChest; private final List<Block> meteorExtra = new ArrayList<>(); private TextDisplay meteorHolo; private boolean meteorFalling;
    private Events.Tier meteorTier = Events.Tier.COMMON;
    /** Ядро метеорита выглядит одиночным сундуком, а открывается как двойной: общий инвентарь на 54 слота. */
    private static final class MeteorHolder implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;
        @Override public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }
    private org.bukkit.inventory.Inventory meteorInv;
    /** Оболочка ядра: остывает по стадиям (раскалённая магма -> остывший камень), после последней трескается и открывает ядро. */
    private final List<Block> shell = new ArrayList<>();
    private int shellStage, shellR; private long shellNext;
    private static final String[] STAGE_NAMES = {"<#ff7a1a>Раскалённое", "<#ff4a2a>Тлеющее", "<#d0603a>Остывающее", "<#a89a94>Тёплое", "<#8fa3b8>Остывшее"};
    private List<ItemStack> meteorLoot = List.of();
    /** Блоки, изменённые метеоритом, с оригиналами: после ивента всё возвращается как было (журнал - на случай сбоя). */
    private final LinkedHashMap<Block, org.bukkit.block.data.BlockData> crater = new LinkedHashMap<>();
    private int craterR;
    private static final Set<Material> UNTOUCH = EnumSet.of(Material.BEDROCK, Material.BARRIER, Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_PORTAL_FRAME,
            Material.END_GATEWAY, Material.REINFORCED_DEEPSLATE, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW, Material.LIGHT, Material.SPAWNER, Material.TRIAL_SPAWNER, Material.VAULT);
    private File craterFile() { return new File(pl.getDataFolder(), "meteor-restore.yml"); }

    private void meteor() {
        if (meteorLoc != null) return;
        meteorTier = pl.events().rollTier("meteor");
        pl.events().randomSurface("events.meteor", loc -> {
            if (loc == null) return;
            meteorLoc = loc;
            pl.events().hold("meteor", loc, 26);
            meteorStart = System.currentTimeMillis();
            meteorAt = meteorStart + cfg("meteor.warn-seconds", 90) * 1000L;
            meteorFalling = false;
            pl.events().announce(name("meteor"), meteorTier.tag("метеорит") + " <yellow>упадёт через " + cfg("meteor.warn-seconds", 90) + " сек: <white>" + xyz(loc) + " <gray>- в ядре сокровища!");
        });
    }

    /** Анимация: огненная глыба летит с неба 5 секунд. */
    private void meteorFall() {
        meteorFalling = true;
        World w = meteorLoc.getWorld();
        Location target = meteorLoc.clone().add(0.5, 0, 0.5);
        Location from = target.clone().add(-35, 70, -20);
        float s = 3f + meteorTier.ordinal() * 0.6f;
        BlockDisplay rock = w.spawn(from, BlockDisplay.class, b -> {
            b.setPersistent(false);
            b.setBlock((meteorTier == Events.Tier.DIVINE ? Material.GILDED_BLACKSTONE : meteorTier == Events.Tier.EPIC ? Material.CRYING_OBSIDIAN : Material.MAGMA_BLOCK).createBlockData());
            b.setTransformation(new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
            b.setTeleportDuration(2);
            b.setGlowing(true); b.setGlowColorOverride(meteorTier.color);
            b.setViewRange(4f);
        });
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(target) < 250 * 250) p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.5f);
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t += 2;
                double k = Math.min(1, t / 100.0);
                if (!rock.isValid()) { cancel(); meteorImpact(); return; }
                Location l = from.clone().add(target.clone().subtract(from).toVector().multiply(k));
                rock.teleport(l);
                w.spawnParticle(Particle.FLAME, l, 25, 1, 1, 1, 0.08);
                w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 1, 1, 1, 0.02);
                w.spawnParticle(Particle.LAVA, l, 3, 0.8, 0.8, 0.8, 0);
                w.spawnParticle(meteorTier.particle, l, 8, 1.2, 1.2, 1.2, 0.05);
                if (t % 20 == 0) w.playSound(l, Sound.ENTITY_GHAST_SHOOT, 3f, 0.5f);
                if (k >= 1) { rock.remove(); cancel(); meteorImpact(); }
            }
        }.runTaskTimer(pl, 0, 2);
    }

    private void meteorImpact() {
        if (meteorLoc == null || meteorAt == 0) return;
        Location l = meteorLoc;
        World w = l.getWorld();
        boolean prot = pl.regions().at(l) != null;
        // взрыв только наносит урон - блоки не ломает; воронку вырезаем сами и запоминаем оригиналы
        w.createExplosion(l.clone().add(0.5, 0.5, 0.5), (float) pl.getConfig().getDouble("events.meteor.explosion-power", 5), false, false, null);
        w.strikeLightningEffect(l);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 4, 2, 1, 2);
        w.spawnParticle(Particle.FLAME, l, 200, 4, 2, 4, 0.2);
        w.spawnParticle(meteorTier.particle, l, 120, 4, 3, 4, 0.2);
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(l) < 300 * 300) { p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f); p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.6f); }
        Block top = w.getHighestBlockAt(l);
        int rad = cfg("meteor.crater-radius." + meteorTier.id, 5 + meteorTier.ordinal());
        Block center = carve(w, top.getX(), top.getY(), top.getZ(), prot ? 0 : Math.max(0, Math.min(20, rad)));
        meteorChest = center;
        meteorExtra.clear();
        meteorExtra.addAll(pl.events().buildCache("meteor", center, meteorTier, (b, d) -> { crater.putIfAbsent(b, b.getBlockData().clone()); b.setBlockData(d, false); },
                b -> b.getType().isAir() || crater.containsKey(b) || touchable(b)));
        MeteorHolder mh = new MeteorHolder();
        meteorInv = Bukkit.createInventory(mh, 54, mm(meteorTier.tag("метеорит")));
        mh.inv = meteorInv;
        meteorLoot = pl.events().eventLoot(meteorTier, "meteor");
        buildShell(center);
        saveCrater();
        boolean hot = !shell.isEmpty();
        double hy = hot ? shellR + 1.2 : 1.7;
        meteorHolo = w.spawn(center.getLocation().add(0.5, hy, 0.5), TextDisplay.class, t -> { t.setPersistent(false); t.setBillboard(Display.Billboard.CENTER); });
        meteorAt0 = System.currentTimeMillis();
        meteorRemove = meteorAt0 + (hot ? STAGE_NAMES.length * stageMs() : 0) + cfg("meteor.despawn-minutes", 10) * 60_000L;
        meteorAt = 0;
        if (!hot) shellGone(); else meteorHoloText();
        Bukkit.broadcast(mm(pre() + "<red>☄ " + meteorTier.tag("метеорит") + " <red>упал! <white>" + xyz(center.getLocation())
                + (hot ? " <gray>- раскалённое ядро остывает: <gold>" + STAGE_NAMES.length + " стадий</gold> по " + stageMs() / 1000 + " сек, потом треснет." : " <gray>- сундук-ядро с сокровищами.")));
    }

    private long stageMs() { return Math.max(1, cfg("meteor.stage-seconds", 10)) * 1000L; }

    /** Каменная оболочка-купол вокруг ядра (только в пустоте воронки). */
    private void buildShell(Block core) {
        shell.clear();
        shellStage = 0;
        shellR = Math.max(0, Math.min(5, cfg("meteor.shell-radius", 3)));
        for (int dx = -shellR; dx <= shellR; dx++) for (int dy = -shellR; dy <= shellR; dy++) for (int dz = -shellR; dz <= shellR; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 0.5 || d > shellR + 0.5) continue;
            Block b = core.getRelative(dx, dy, dz);
            if (!b.getType().isAir() || nearWet(b) || pl.regions().at(b.getLocation()) != null) continue;
            put(b, Material.MAGMA_BLOCK);
            shell.add(b);
        }
        shellNext = System.currentTimeMillis() + stageMs();
    }

    /** Блоки стадии остывания: основной, прожилки и доля основного. Последняя стадия - камень по редкости метеорита. */
    private Material[] stageBlocks(int stage) {
        return switch (stage) {
            case 0 -> new Material[]{Material.MAGMA_BLOCK, Material.MAGMA_BLOCK};
            case 1 -> new Material[]{Material.NETHERRACK, Material.MAGMA_BLOCK};
            case 2 -> new Material[]{Material.BASALT, Material.NETHERRACK};
            case 3 -> new Material[]{Material.BLACKSTONE, Material.BASALT};
            default -> new Material[]{switch (meteorTier) {
                case DIVINE -> Material.GILDED_BLACKSTONE; case EPIC -> Material.CRYING_OBSIDIAN; case RARE -> Material.OBSIDIAN; default -> Material.COBBLED_DEEPSLATE;
            }, Material.BLACKSTONE};
        };
    }
    private static final double[] STAGE_MAIN = {1.0, 0.65, 0.7, 0.75, 0.8};

    private void paintShell() {
        Material[] m = stageBlocks(shellStage);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Block b : shell) b.setType(r.nextDouble() < STAGE_MAIN[shellStage] ? m[0] : m[1], false);
    }

    private void meteorHoloText() {
        if (meteorHolo == null || !meteorHolo.isValid()) return;
        if (!shell.isEmpty()) {
            long left = Math.max(0, shellNext - System.currentTimeMillis());
            boolean last = shellStage >= STAGE_NAMES.length - 1;
            meteorHolo.text(mm(name("meteor") + "\n" + meteorTier.tag() + "\n<gold>Остывание: <white>" + (shellStage + 1) + "/" + STAGE_NAMES.length + " <dark_gray>- " + STAGE_NAMES[shellStage]
                    + "\n<gray>" + (last ? "Треснет" : "Следующая стадия") + " через <yellow>" + (left / 1000 + 1) + "с"));
        } else meteorHolo.text(mm(name("meteor") + "\n" + meteorTier.tag() + "\n<gray>Ядро метеорита - внутри сокровища\n<gray>Пополняется постепенно"));
    }

    /** Тик оболочки (раз в секунду): в срок - следующая стадия остывания, после последней оболочка трескается. */
    private void shellTick(long now) {
        if (shell.isEmpty() || meteorChest == null) return;
        World w = meteorChest.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = meteorChest.getLocation().add(0.5, 0.5, 0.5);
        if (now >= shellNext) {
            if (shellStage >= STAGE_NAMES.length - 1) {
                List<Block> all = new ArrayList<>(shell);
                shell.clear();
                shatter(all);
                shellGone();
                return;
            }
            shellStage++;
            paintShell();
            shellNext = now + stageMs();
            w.playSound(c, Sound.BLOCK_LAVA_EXTINGUISH, 2f, 0.5f + shellStage * 0.1f);
            w.playSound(c, Sound.BLOCK_BASALT_PLACE, 1.5f, 0.6f);
            for (int i = 0; i < Math.min(20, shell.size()); i++)
                w.spawnParticle(shellStage < 3 ? Particle.LARGE_SMOKE : Particle.CLOUD, shell.get(r.nextInt(shell.size())).getLocation().add(0.5, 1, 0.5), 3, 0.3, 0.3, 0.3, 0.02);
        } else if (shellStage <= 1) {
            for (int i = 0; i < 4; i++) w.spawnParticle(Particle.LAVA, shell.get(r.nextInt(shell.size())).getLocation().add(0.5, 1, 0.5), 1, 0.2, 0.1, 0.2, 0);
        } else if (r.nextInt(3) == 0) {
            w.spawnParticle(Particle.SMOKE, shell.get(r.nextInt(shell.size())).getLocation().add(0.5, 1, 0.5), 4, 0.25, 0.2, 0.25, 0.01);
        }
        meteorHoloText();
    }

    /** Остывшая оболочка трескается: блоки исчезают, куски разлетаются. */
    private void shatter(List<Block> layer) {
        if (layer.isEmpty()) return;
        World w = layer.get(0).getWorld();
        Location c = meteorChest.getLocation().add(0.5, 0.5, 0.5);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int fx = 0;
        for (Block b : layer) {
            org.bukkit.block.data.BlockData d = b.getBlockData();
            b.setType(Material.AIR, false);
            if (r.nextInt(3) == 0) w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, d);
            if (fx < 24 && r.nextInt(Math.max(1, layer.size() / 24)) == 0) {
                fx++;
                Location at = b.getLocation().add(0.5, 0.5, 0.5);
                Vector out = at.toVector().subtract(c.toVector());
                if (out.lengthSquared() < 0.01) out = new Vector(0, 1, 0);
                Vector dir = out.normalize().multiply(1.6).setY(Math.abs(out.getY()) + 0.8);
                BlockDisplay chunk = w.spawn(at, BlockDisplay.class, x -> {
                    x.setPersistent(false); x.setBlock(d);
                    x.setTransformation(new Transformation(new Vector3f(-0.3f, -0.3f, -0.3f), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
                    x.setTeleportDuration(2);
                });
                new org.bukkit.scheduler.BukkitRunnable() {
                    int t = 0; final Location pos = at.clone();
                    @Override public void run() {
                        if (++t > 12 || !chunk.isValid()) { chunk.remove(); cancel(); return; }
                        pos.add(dir.getX() * 0.25, dir.getY() * 0.25 - 0.06 * t, dir.getZ() * 0.25);
                        chunk.teleport(pos);
                    }
                }.runTaskTimer(pl, 1, 2);
            }
        }
        w.playSound(c, Sound.BLOCK_BASALT_BREAK, 2f, 0.7f);
        w.playSound(c, Sound.BLOCK_DEEPSLATE_BREAK, 1.5f, 0.6f);
        w.spawnParticle(Particle.LAVA, c, 20, 2, 1, 2, 0);
    }

    /** Оболочки больше нет - ядро открыто, лут пошёл. */
    private void shellGone() {
        if (meteorChest == null || meteorInv == null) return;
        pl.mystery().start("meteor", meteorChest, meteorInv, meteorLoot, pl.getConfig().getDouble("events.meteor.drip-seconds", 0.5), meteorTier);
        if (meteorHolo != null && meteorHolo.isValid()) meteorHolo.teleport(meteorChest.getLocation().add(0.5, 1.7, 0.5));
        meteorHoloText();
        Location l = meteorChest.getLocation().add(0.5, 1.2, 0.5);
        firework(l, meteorTier.color);
        meteorChest.getWorld().playSound(l, Sound.BLOCK_CHEST_OPEN, 1.5f, 0.7f);
        Bukkit.broadcast(mm(pre() + "<gold>☄ Ядро метеорита остыло - сокровища внутри! <white>" + xyz(meteorChest.getLocation())));
    }

    private boolean wet(Block b) {
        if (b.isLiquid()) return true;
        Material m = b.getType();
        if (m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }
    private boolean touchable(Block b) {
        if (UNTOUCH.contains(b.getType()) || wet(b)) return false;
        if (pl.monuments() != null && pl.monuments().isProtected(b)) return false;
        if (b.getState(false) instanceof org.bukkit.block.TileState) return false;
        return pl.regions().at(b.getLocation()) == null;
    }
    private boolean nearWet(Block b) {
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.DOWN})
            if (wet(b.getRelative(f))) return true;
        return false;
    }
    private void put(Block b, Material m) { crater.putIfAbsent(b, b.getBlockData().clone()); b.setBlockData(m.createBlockData(), false); }
    private Material lining(ThreadLocalRandom r) {
        int x = r.nextInt(100);
        return switch (meteorTier) {
            case COMMON -> x < 25 ? Material.MAGMA_BLOCK : x < 55 ? Material.BLACKSTONE : x < 80 ? Material.COBBLED_DEEPSLATE : Material.BASALT;
            case RARE -> x < 25 ? Material.MAGMA_BLOCK : x < 50 ? Material.BLACKSTONE : x < 70 ? Material.OBSIDIAN : x < 85 ? Material.BASALT : Material.COBBLED_DEEPSLATE;
            case EPIC -> x < 25 ? Material.MAGMA_BLOCK : x < 45 ? Material.OBSIDIAN : x < 60 ? Material.CRYING_OBSIDIAN : x < 85 ? Material.BLACKSTONE : Material.BASALT;
            case DIVINE -> x < 20 ? Material.MAGMA_BLOCK : x < 40 ? Material.GILDED_BLACKSTONE : x < 55 ? Material.CRYING_OBSIDIAN : x < 70 ? Material.OBSIDIAN : Material.BLACKSTONE;
        };
    }

    /** Вырезает воронку (эллипсоид) с оплавленными стенками; вода/лава, контейнеры, регионы и бедрок не трогаются. Возвращает блок-ядро (сундук). */
    private Block carve(World w, int cx, int cy, int cz, int rad) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        crater.clear();
        craterR = rad;
        Set<Block> cut = new LinkedHashSet<>();
        int minY = w.getMinHeight() + 1, maxY = w.getMaxHeight() - 2;
        if (rad > 0) {
            double hv = Math.max(1.5, rad * 0.75);
            int up = (int) Math.ceil(hv) + 3;
            for (int dy = (int) -Math.ceil(hv); dy <= up; dy++) for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                int y = cy + 1 + dy;
                if (y < minY || y > maxY) continue;
                double vy = dy > 0 ? hv * 1.4 : hv;
                double d = (dx * dx + dz * dz) / (double) (rad * rad) + (dy * dy) / (vy * vy);
                if (d > 1 + (r.nextDouble() - 0.5) * 0.3) continue;
                Block b = w.getBlockAt(cx + dx, y, cz + dz);
                if (b.getType().isAir() || !touchable(b) || nearWet(b)) continue;
                put(b, Material.AIR);
                cut.add(b);
            }
            org.bukkit.block.BlockFace[] sides = {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};
            for (Block b : new ArrayList<>(cut)) {
                // то, что висело бы в воздухе: растения/факелы сверху и сбоку, песок/гравий сверху
                Block a = b.getRelative(org.bukkit.block.BlockFace.UP);
                for (int k = 0; k < 6 && !cut.contains(a) && a.getY() <= maxY; k++) {
                    Material m = a.getType();
                    if (m.isAir() || !touchable(a)) break;
                    if (m.hasGravity()) { put(a, lining(r)); break; }
                    if (m.isSolid()) break;
                    put(a, Material.AIR); cut.add(a);
                    a = a.getRelative(org.bukkit.block.BlockFace.UP);
                }
                for (org.bukkit.block.BlockFace f : sides) {
                    Block n = b.getRelative(f);
                    if (cut.contains(n) || n.getType().isAir() || n.getType().isSolid() || !touchable(n) || nearWet(n)) continue;
                    put(n, Material.AIR); cut.add(n);
                }
            }
            // оплавленные стенки и дно
            for (Block b : cut) for (org.bukkit.block.BlockFace f : org.bukkit.block.BlockFace.values()) {
                if (!f.isCartesian()) continue;
                Block n = b.getRelative(f);
                if (cut.contains(n) || crater.containsKey(n)) continue;
                Material m = n.getType();
                if (!m.isSolid() || !m.isOccluding() || !touchable(n)) continue;
                if (r.nextDouble() < (f == org.bukkit.block.BlockFace.DOWN ? 0.8 : 0.45)) put(n, lining(r));
            }
        }
        Block center = null;
        for (int y = Math.min(maxY, cy + 6); y >= Math.max(minY, cy - rad - 2); y--) { Block b = w.getBlockAt(cx, y, cz); if (cut.contains(b)) center = b; }
        if (center == null) center = w.getHighestBlockAt(cx, cz).getRelative(org.bukkit.block.BlockFace.UP);
        put(center, Material.CHEST);
        Material core = switch (meteorTier) { case COMMON -> Material.MAGMA_BLOCK; case RARE -> Material.OBSIDIAN; case EPIC -> Material.CRYING_OBSIDIAN; case DIVINE -> Material.GILDED_BLACKSTONE; };
        for (int[] o : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            Block n = center.getRelative(o[0], 0, o[1]);
            if ((cut.contains(n) || n.getType().isAir()) && touchable(n) && !nearWet(n)) put(n, core);
        }
        Block under = center.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (under.getType().isSolid() && touchable(under)) put(under, core);
        Block above = center.getRelative(org.bukkit.block.BlockFace.UP);
        if (!above.getType().isAir() && !above.getType().isOccluding() && touchable(above)) put(above, Material.AIR);
        return center;
    }

    private void meteorClear() {
        List<Player> inside = new ArrayList<>();
        Block mc = meteorChest != null ? meteorChest : meteorLoc != null ? meteorLoc.getBlock() : null;
        if (mc != null && !crater.isEmpty()) {
            double r = Math.max(craterR, shellR + 1) + 3;
            for (Player p : mc.getWorld().getPlayers()) if (p.getLocation().distanceSquared(mc.getLocation()) <= r * r) inside.add(p);
        }
        if (meteorInv != null) {
            for (org.bukkit.entity.HumanEntity v : new ArrayList<>(meteorInv.getViewers())) v.closeInventory();
            meteorInv.clear();
            meteorInv = null;
        }
        if (meteorChest != null && meteorChest.getState(false) instanceof org.bukkit.block.Container c) c.getInventory().clear();
        if (meteorHolo != null) meteorHolo.remove();
        if (!crater.isEmpty()) {
            List<Map.Entry<Block, org.bukkit.block.data.BlockData>> list = new ArrayList<>(crater.entrySet());
            Collections.reverse(list);
            for (var en : list) {
                try {
                    if (en.getKey().getState(false) instanceof org.bukkit.block.Container c) c.getInventory().clear();
                    en.getKey().setBlockData(en.getValue(), false);
                } catch (Exception ex) { pl.getLogger().warning("meteor restore: " + ex.getMessage()); }
            }
            crater.clear();
            for (Player p : inside) Util.unstuck(p);
        } else if (meteorChest != null && meteorChest.getType() == Material.CHEST) meteorChest.setType(Material.AIR);
        craterFile().delete();
        shell.clear(); shellStage = 0; meteorLoot = List.of();
        meteorChest = null; meteorHolo = null; meteorLoc = null; meteorAt = 0; meteorExtra.clear(); pl.events().unguard("meteor"); pl.mystery().stop("meteor");
        pl.events().release("meteor");
    }

    private void saveCrater() {
        if (crater.isEmpty()) { craterFile().delete(); return; }
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", crater.keySet().iterator().next().getWorld().getName());
        List<String> l = new ArrayList<>(crater.size());
        for (var en : crater.entrySet()) { Block b = en.getKey(); l.add(b.getX() + "," + b.getY() + "," + b.getZ() + "|" + en.getValue().getAsString()); }
        y.set("blocks", l);
        try { y.save(craterFile()); } catch (Exception ex) { pl.getLogger().warning("meteor-restore: " + ex.getMessage()); }
    }

    /** После сбоя/рестарта во время метеорита - вернуть рельеф по журналу. */
    private void restoreCrater() { restoreCraterTry(0); }
    private void restoreCraterTry(int tries) {
        File f = craterFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        World w = Bukkit.getWorld(y.getString("world", "world"));
        if (w == null) { if (tries < 12) Bukkit.getScheduler().runTaskLater(pl, () -> restoreCraterTry(tries + 1), 100); return; }
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
        pl.getLogger().info("Метеорит: после перезапуска восстановлено блоков воронки: " + n);
    }

    private boolean inCrater(Block b) { return meteorChest != null && (b.equals(meteorChest) || crater.containsKey(b)); }
    /** Блок воронки/оболочки метеорита (для спец-предметов). */
    public boolean isMeteorBlock(Block b) { return !crater.isEmpty() && crater.containsKey(b); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorBreak(BlockBreakEvent e) {
        if (!inCrater(e.getBlock())) return;
        e.setCancelled(true);
        pl.msg(e.getPlayer(), e.getBlock().equals(meteorChest) ? "<red>Ядро нельзя сломать - открой его" : "<red>Воронку метеорита нельзя ломать - она исчезнет сама после ивента");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorPlace(BlockPlaceEvent e) {
        if (!inCrater(e.getBlockPlaced())) return;
        e.setCancelled(true);
        pl.msg(e.getPlayer(), "<red>В воронку метеорита нельзя ставить блоки");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorBucket(PlayerBucketEmptyEvent e) { if (inCrater(e.getBlockClicked().getRelative(e.getBlockFace())) || inCrater(e.getBlockClicked())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorFlow(org.bukkit.event.block.BlockFromToEvent e) { if (inCrater(e.getToBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorExplode(EntityExplodeEvent e) { if (meteorChest != null) e.blockList().removeIf(this::inCrater); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) { if (meteorChest != null) e.blockList().removeIf(this::inCrater); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorBurn(org.bukkit.event.block.BlockBurnEvent e) { if (inCrater(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorLeaves(org.bukkit.event.block.LeavesDecayEvent e) {
        if (meteorChest == null || craterR <= 0 || !e.getBlock().getWorld().equals(meteorChest.getWorld())) return;
        if (e.getBlock().getLocation().distanceSquared(meteorChest.getLocation()) < (craterR + 8) * (craterR + 8)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorPiston(org.bukkit.event.block.BlockPistonExtendEvent e) { if (meteorChest != null && e.getBlocks().stream().anyMatch(b -> inCrater(b) || inCrater(b.getRelative(e.getDirection())))) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent e) { if (meteorChest != null && e.getBlocks().stream().anyMatch(this::inCrater)) e.setCancelled(true); }
    /** ПКМ по ядру метеорита - открывается общий инвентарь на 54 слота. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMeteorOpen(org.bukkit.event.player.PlayerInteractEvent e) {
        if (meteorChest == null || meteorInv == null || meteorAt != 0 || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || e.getClickedBlock() == null || !e.getClickedBlock().equals(meteorChest)) return;
        if (e.getPlayer().isSneaking() && e.getItem() != null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!shell.isEmpty()) { pl.msg(p, "<gold>Ядро ещё остывает: стадия <white>" + (shellStage + 1) + "/" + STAGE_NAMES.length + " <gray>- откроется, когда оболочка треснет"); return; }
        p.openInventory(meteorInv);
        if (meteorChest.getState() instanceof Chest ch) ch.open();
        p.playSound(meteorChest.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMeteorClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        boolean main = e.getInventory().getHolder() instanceof MeteorHolder;
        if (main && meteorChest != null) Bukkit.getScheduler().runTask(pl, () -> {
            if (meteorChest != null && meteorInv != null && meteorInv.getViewers().isEmpty() && meteorChest.getState() instanceof Chest ch) ch.close();
        });
        if (meteorChest == null || meteorAt != 0 || !(main || e.getInventory().getHolder() instanceof Chest c && (c.getBlock().equals(meteorChest) || meteorExtra.contains(c.getBlock())))) return;
        if (pl.mystery().hasLoot(e.getInventory())) return;
        if (meteorInv != null && pl.mystery().hasLoot(meteorInv)) return;
        Mystery.Feeder mf = pl.mystery().feeder("meteor");
        if (mf != null && mf.left() > 0) return;
        for (Block b : meteorExtra) if (b.getState() instanceof Chest xc && !xc.getBlockInventory().isEmpty()) return;
        long soon = System.currentTimeMillis() + 30_000L;
        if (meteorRemove > soon) { meteorRemove = soon; if (e.getPlayer() instanceof Player p) pl.msg(p, "<gray>Все сундуки пусты - воронка остынет и исчезнет через 30 сек."); }
    }

    /** Фейерверк-эффект (без урона) */
    public static void firework(AnarchyCore pl, Location l, Color c) {
        Firework fw = l.getWorld().spawn(l, Firework.class);
        var fm = fw.getFireworkMeta();
        fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(c, Color.WHITE).trail(true).flicker(true).build());
        fm.setPower(1);
        fw.setFireworkMeta(fm);
        fw.setMetadata("aqua_safe", new org.bukkit.metadata.FixedMetadataValue(pl, true));
    }
    private void firework(Location l, Color c) { firework(pl, l, c); }

    // ===================== ТУРНИР =====================
    private int tState; // 0 нет, 1 набор, 2 бой
    private Events.Tier tTier = Events.Tier.COMMON;
    private long tStart, tTime;
    private final Set<UUID> tPlayers = new LinkedHashSet<>();
    private final Map<UUID, Integer> tKills = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedInv = new HashMap<>();
    private final Map<UUID, Location> savedLoc = new HashMap<>();
    private World arena;
    private File pendingFile() { return new File(pl.getDataFolder(), "tournament-restore.yml"); }

    public boolean inTournament(Player p) { return tState > 0 && tPlayers.contains(p.getUniqueId()); }
    public boolean inArena(Location l) { return arena != null && l != null && arena.equals(l.getWorld()); }

    private World arena() {
        if (arena != null) return arena;
        arena = new WorldCreator("arena").generator(new Worlds.Void()).environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        if (arena == null) return null;
        arena.setGameRule(GameRules.SPAWN_MOBS, false);
        arena.setGameRule(GameRules.ADVANCE_TIME, false);
        arena.setGameRule(GameRules.ADVANCE_WEATHER, false);
        arena.setGameRule(GameRules.KEEP_INVENTORY, true);
        arena.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        arena.setGameRule(GameRules.FALL_DAMAGE, false);
        arena.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        arena.setTime(13000);
        arena.setPVP(true);
        arena.setSpawnLocation(0, 65, 0);
        // Постройка арены: чанки грузим асинхронно, блоки ставим без физики -
        // иначе соседние обновления на границах чанков синхронно генерируют чанки и сервер зависает на 10+ сек.
        World w = arena;
        List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> wait = new ArrayList<>();
        for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) wait.add(w.getChunkAtAsync(cx, cz));
        java.util.concurrent.CompletableFuture.allOf(wait.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((v, ex) -> Bukkit.getScheduler().runTask(pl, () -> buildArena(w)));
        return arena;
    }

    private void buildArena(World w) {
        if (w.getBlockAt(0, 63, 0).getType() == Material.BEDROCK) return;
        int R = 20;
        for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.sqrt(x * x + z * z);
            w.getBlockAt(x, 63, z).setType(Material.BEDROCK, false);
            Material floor = d < 3 ? Material.SEA_LANTERN : d < 4 ? Material.CYAN_TERRACOTTA : Math.floorMod(x * 7 + z * 13, 11) == 0 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                    : (Math.abs(x) + Math.abs(z)) % 2 == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES;
            w.getBlockAt(x, 64, z).setType(floor, false);
            boolean edge = Math.abs(x) == R + 1 || Math.abs(z) == R + 1;
            if (edge) for (int y = 65; y <= 72; y++) w.getBlockAt(x, y, z).setType(y <= 66 ? Material.POLISHED_BLACKSTONE_BRICKS : y == 67 ? Material.SEA_LANTERN : Material.BARRIER, false);
        }
        int[][] pillars = {{8, 8}, {-9, 8}, {8, -9}, {-9, -9}, {14, 0}, {-15, 0}, {0, 14}, {0, -15}};
        for (int[] pp : pillars) for (int y = 65; y <= 67; y++) for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++)
            w.getBlockAt(pp[0] + dx, y, pp[1] + dz).setType(y == 67 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_DEEPSLATE, false);
        pl.getLogger().info("Арена турнира построена");
    }

    private void tournament() {
        if (tState != 0 || arena() == null) return;
        tState = 1;
        tTier = pl.events().rollEventTier("tournament");
        tStart = System.currentTimeMillis();
        tTime = tStart + cfg("tournament.join-seconds", 90) * 1000L;
        tPlayers.clear(); tKills.clear();
        pl.events().announce(name("tournament"), tTier.tag("турнир") + " <yellow>- вход: <white>/event join</white> <gray>(" + cfg("tournament.join-seconds", 90) + " сек). Свои вещи не теряются!");
        Bukkit.broadcast(mm(pre() + "<gold>⚔ Набор на турнир! Инвентарь сохранится, всем одинаковый набор. Приз: <gold>"
                + cfg("tournament.reward-coins", 5000) + " " + pl.cur() + " <light_purple>+" + cfg("tournament.reward-ari", 15) + " " + pl.ariName() + " ").append(mm("<green><bold>[ВОЙТИ]").clickEvent(ClickEvent.runCommand("/event join"))));
    }

    private void join(Player p) {
        if (tState != 1) { pl.msg(p, tState == 2 ? "<red>Турнир уже идёт" : "<red>Сейчас нет набора на турнир. <gray>/event delay"); return; }
        if (tPlayers.contains(p.getUniqueId())) { pl.msg(p, "<gray>Ты уже записан"); return; }
        PlayerData d = pl.data(p);
        if (d.inCombat()) { pl.msg(p, "<red>Нельзя войти в режиме боя"); return; }
        if (pl.bossArena() != null && pl.bossArena().isLocked(p)) { pl.msg(p, "<red>☠ С арены Стража не уйти, пока он жив!"); return; }
        if (tPlayers.size() >= cfg("tournament.max-players", 24)) { pl.msg(p, "<red>Арена заполнена"); return; }
        tPlayers.add(p.getUniqueId());
        savedInv.put(p.getUniqueId(), p.getInventory().getContents().clone());
        savedLoc.put(p.getUniqueId(), p.getLocation());
        savePending();
        p.getInventory().clear();
        kit(p);
        double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Location spot = new Location(arena, Math.cos(a) * 16 + 0.5, 65, Math.sin(a) * 16 + 0.5);
        spot.setDirection(new Vector(-Math.cos(a), 0, -Math.sin(a)));
        p.teleport(spot);
        var mh = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(mh == null ? 20 : mh.getValue());
        p.setFoodLevel(20); p.setSaturation(20); p.setFireTicks(0);
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        p.setGameMode(GameMode.SURVIVAL);
        Bukkit.broadcast(mm("<gold>⚔ <white>" + AnarchyCore.esc(p.getName()) + " <gray>вступил в турнир <dark_gray>(" + tPlayers.size() + ")"));
    }

    private void kit(Player p) {
        var inv = p.getInventory();
        inv.setHelmet(ench(Material.DIAMOND_HELMET, "protection", 2)); inv.setChestplate(ench(Material.DIAMOND_CHESTPLATE, "protection", 2));
        inv.setLeggings(ench(Material.DIAMOND_LEGGINGS, "protection", 2)); inv.setBoots(ench(Material.DIAMOND_BOOTS, "protection", 2));
        inv.setItem(0, ench(Material.DIAMOND_SWORD, "sharpness", 2)); inv.setItem(1, ench(Material.BOW, "power", 2));
        inv.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 4)); inv.setItem(3, new ItemStack(Material.COOKED_BEEF, 16));
        inv.setItem(8, new ItemStack(Material.ARROW, 32));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));
    }

    private static ItemStack ench(Material m, String e, int lvl) {
        ItemStack it = new ItemStack(m);
        var en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e));
        if (en != null) it.addUnsafeEnchantment(en, lvl);
        return it;
    }

    private void savePending() {
        YamlConfiguration y = new YamlConfiguration();
        for (var en : savedInv.entrySet()) {
            List<String> l = new ArrayList<>();
            for (ItemStack it : en.getValue()) l.add(it == null ? "" : Util.itemToString(it));
            y.set(en.getKey() + ".inv", l);
            Location loc = savedLoc.get(en.getKey());
            if (loc != null) y.set(en.getKey() + ".loc", PlayerData.locToString(loc));
        }
        try { y.save(pendingFile()); } catch (Exception ignored) {}
    }

    private void restorePending() {
        File f = pendingFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String k : y.getKeys(false)) {
            List<String> l = y.getStringList(k + ".inv");
            ItemStack[] inv = new ItemStack[l.size()];
            for (int i = 0; i < l.size(); i++) inv[i] = l.get(i).isEmpty() ? null : Util.itemFromString(l.get(i));
            UUID u = UUID.fromString(k);
            savedInv.put(u, inv);
            Location loc = PlayerData.locFromString(y.getString(k + ".loc", ""));
            if (loc != null) savedLoc.put(u, loc);
        }
    }

    private void restore(Player p) {
        ItemStack[] inv = savedInv.remove(p.getUniqueId());
        Location loc = savedLoc.remove(p.getUniqueId());
        if (inv != null) { p.getInventory().clear(); p.getInventory().setContents(inv); }
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        p.setFireTicks(0);
        var mh = p.getAttribute(Attribute.MAX_HEALTH);
        if (!p.isDead()) p.setHealth(mh == null ? 20 : mh.getValue());
        p.setFoodLevel(20);
        if (inArena(p.getLocation())) p.teleport(loc != null ? loc : pl.worlds().hubPoint());
        savePending();
    }

    private void eliminate(Player p, boolean died) {
        tPlayers.remove(p.getUniqueId());
        pl.data(p).combatUntil = 0;
        if (!died) restore(p);
        if (tState == 2) checkWinner();
    }

    private void checkWinner() {
        if (tPlayers.size() > 1) return;
        if (tPlayers.size() == 1) {
            Player w = Bukkit.getPlayer(tPlayers.iterator().next());
            if (w != null) {
                PlayerData d = pl.data(w);
                long coins = cfg("tournament.reward-coins", 5000), ari = cfg("tournament.reward-ari", 15);
                d.balance += coins; d.ari += ari;
                pl.cases().addKeys(d, "titles", 1);
                firework(w.getLocation(), Color.YELLOW);
                pl.events().announce("<gradient:#ffd700:#ff4400><bold>ПОБЕДИТЕЛЬ ТУРНИРА</bold></gradient>", "<white>" + AnarchyCore.esc(w.getName()) + " <gray>(" + tKills.getOrDefault(w.getUniqueId(), 0) + " убийств)");
                pl.msg(w, "<gold>Награда: +" + coins + " " + pl.cur() + " <light_purple>+" + ari + " " + pl.ariName() + " <gray>+ ключ кейса титулов");
                tPlayers.clear();
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (!w.isOnline()) return;
                    restore(w);
                    for (ItemStack it : pl.events().rarityBonus("events.tournament.bonus", tTier))
                        for (ItemStack o : w.getInventory().addItem(it).values()) w.getWorld().dropItem(w.getLocation(), o);
                }, 60);
            }
        }
        tEnd();
    }

    private void tEnd() {
        for (UUID u : new ArrayList<>(tPlayers)) { Player p = Bukkit.getPlayer(u); if (p != null) restore(p); }
        tPlayers.clear();
        tState = 0;
    }

    private void tournamentTick() {
        long now = System.currentTimeMillis();
        if (tState == 1) {
            long left = (tTime - now + 999) / 1000;
            for (UUID u : tPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                p.sendActionBar(mm("<gold>Турнир начнётся через <white>" + left + " сек <gray>| участников: " + tPlayers.size()));
                if (left <= 5 && left > 0) { p.showTitle(net.kyori.adventure.title.Title.title(mm("<gold><bold>" + left), mm("<gray>приготовься"), net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ZERO))); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1); }
            }
            if (left == 60 || left == 30 || left == 10) Bukkit.broadcast(mm(pre() + "<gold>⚔ Турнир через " + left + " сек! Участников: " + tPlayers.size() + " ").append(mm("<green>[ВОЙТИ]").clickEvent(ClickEvent.runCommand("/event join"))));
            if (now >= tTime) {
                int min = cfg("tournament.min-players", 2);
                if (tPlayers.size() < min) { Bukkit.broadcast(mm(pre() + "<gray>Турнир отменён - мало участников (нужно " + min + ")")); tEnd(); return; }
                tState = 2;
                tStart = now;
                tTime = now + cfg("tournament.max-minutes", 10) * 60_000L;
                for (UUID u : tPlayers) { Player p = Bukkit.getPlayer(u); if (p != null) { p.showTitle(net.kyori.adventure.title.Title.title(mm("<red><bold>⚔ БОЙ!"), mm("<gray>Последний выживший побеждает"))); p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1, 1); } }
                Bukkit.broadcast(mm(pre() + "<gold>⚔ Турнир начался! Участников: " + tPlayers.size()));
            }
        } else if (tState == 2) {
            for (UUID u : new ArrayList<>(tPlayers)) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) { tPlayers.remove(u); continue; }
                p.sendActionBar(mm("<red>⚔ Осталось: <white>" + tPlayers.size() + " <dark_gray>| <gray>убийств: <white>" + tKills.getOrDefault(u, 0) + " <dark_gray>| <yellow>" + mmss(tTime - now)));
                if (p.getLocation().getY() < 55) p.setHealth(0);
            }
            if (tPlayers.size() <= 1) { checkWinner(); return; }
            if (tTime - now < 120_000) for (UUID u : tPlayers) { Player p = Bukkit.getPlayer(u); if (p != null && Math.hypot(p.getLocation().getX(), p.getLocation().getZ()) > 9) { p.damage(2); p.sendActionBar(mm("<red><bold>Зона сужается!</bold> Иди к центру")); } }
            if (now > tTime) {
                UUID best = tPlayers.stream().max(Comparator.comparingInt(u -> tKills.getOrDefault(u, 0))).orElse(null);
                Bukkit.broadcast(mm(pre() + "<gray>Время турнира вышло - побеждает игрок с большим числом убийств"));
                for (UUID u : new ArrayList<>(tPlayers)) if (!u.equals(best)) { Player p = Bukkit.getPlayer(u); tPlayers.remove(u); if (p != null) restore(p); }
                checkWinner();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaHit(EntityDamageByEntityEvent e) { if (e.getEntity() instanceof Player v && inArena(v.getLocation()) && tState != 2) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArenaDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!inTournament(p) && !inArena(p.getLocation())) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepLevel(true);
        Player k = p.getKiller();
        if (k != null && inTournament(k)) {
            tKills.merge(k.getUniqueId(), 1, Integer::sum);
            k.setHealth(Math.min(k.getHealth() + 6, 20));
            Bukkit.broadcast(mm("<gold>⚔ <white>" + AnarchyCore.esc(k.getName()) + " <gray>выбил <white>" + AnarchyCore.esc(p.getName()) + " <dark_gray>(осталось " + (tPlayers.size() - 1) + ")"));
        }
        if (inTournament(p)) eliminate(p, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!savedInv.containsKey(p.getUniqueId())) return;
        Location loc = savedLoc.get(p.getUniqueId());
        e.setRespawnLocation(loc != null ? loc : pl.worlds().hubPoint());
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) restore(p); }, 2);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        tracking.remove(p.getUniqueId());
        BossBar b = bars.remove(p.getUniqueId()); if (b != null) p.hideBossBar(b);
        if (inTournament(p)) eliminate(p, false);
        else if (savedInv.containsKey(p.getUniqueId())) restore(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (savedInv.containsKey(p.getUniqueId()) && !inTournament(p)) Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) restore(p); }, 5);
        else if (inArena(p.getLocation()) && !p.hasPermission("anarchy.admin")) Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) p.teleport(pl.worlds().hubPoint()); }, 5);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArenaBuild(BlockPlaceEvent e) { if (inArena(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("anarchy.admin")) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onArenaBreak(BlockBreakEvent e) { if (inArena(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("anarchy.admin")) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onArenaDrop(PlayerDropItemEvent e) { if (inTournament(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onArenaTp(PlayerTeleportEvent e) {
        if (inTournament(e.getPlayer()) && e.getTo().getWorld() != arena && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void onArenaCmd(PlayerCommandPreprocessEvent e) {
        if (!inTournament(e.getPlayer())) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!List.of("event", "events", "msg", "r", "tell", "w", "cc", "l", "login").contains(c)) { e.setCancelled(true); pl.msg(e.getPlayer(), "<red>На турнире команды недоступны. Выйти: /event leave"); }
    }

    // ===================== общий тик =====================
    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        try {
            if (meteorLoc != null) {
                if (meteorAt > 0) {
                    Location c = meteorLoc.clone().add(0.5, 0, 0.5);
                    for (int y = 0; y < 50; y += 3) meteorLoc.getWorld().spawnParticle(Particle.FLAME, c.clone().add(0, y, 0), 1, 0.1, 0.5, 0.1, 0);
                    if (meteorAt - now <= 5200 && !meteorFalling) meteorFall();
                } else if (now > meteorRemove || meteorChest == null || meteorChest.getType() != Material.CHEST) meteorClear();
                else {
                    shellTick(now);
                    Location c = meteorChest.getLocation().add(0.5, 1, 0.5);
                    meteorChest.getWorld().spawnParticle(Particle.LAVA, c, 2, 0.3, 0.2, 0.3, 0);
                    meteorChest.getWorld().spawnParticle(meteorTier.particle, c.add(0, 0.5, 0), 6, 0.5, 0.6, 0.5, 0.02);
                    if (craterR > 0) meteorChest.getWorld().spawnParticle(Particle.LARGE_SMOKE, c, 4, craterR * 0.5, 0.4, craterR * 0.5, 0.01);
                }
            }
            if (tState > 0) tournamentTick();
        } catch (Exception ex) { pl.getLogger().warning("MoreEvents: " + ex); ex.printStackTrace(); }
        barTick();
    }

    public void shutdown() {
        if (meteorLoc != null) meteorClear();
        tEnd();
        for (Player p : Bukkit.getOnlinePlayers()) { BossBar b = bars.get(p.getUniqueId()); if (b != null) p.hideBossBar(b); }
    }
}
