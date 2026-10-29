package ru.anarchy.core.ac;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import ru.anarchy.core.AnarchyCore;
import ru.anarchy.core.Menus;
import ru.anarchy.core.PlayerData;
import ru.anarchy.core.Util;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.anarchy.core.AnarchyCore.esc;
import static ru.anarchy.core.AnarchyCore.mm;

/**
 * ИИ-античит AquaGuard. Собирает улики из пакетов игрока, своих проверок, GrimAC и детектора X-Ray,
 * оценивает вероятность чита моделью, при умеренном подозрении следит за игроком, при сильном -
 * вызывает на проверку и шлёт письмо модераторам. Принявший проверку модератор получает все сообщения античита.
 */
public final class AntiCheat implements Listener {
    /** Метка проверяющего в Admin, пока вызов ИИ не принял живой модератор. */
    public static final UUID AI = new UUID(0L, 0xA16AL);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZONE);
    private static final String ICON = "⛨";
    private static final String[] GRIM_MOVE = {"simulation", "prediction", "phase", "groundspoof", "nofall", "elytra", "vehicle", "noslow",
            "antikb", "knockback", "antiexplosion", "explosion", "sprint", "entitycontrol", "fly", "speed", "step", "jesus"};
    private static final String[] GRIM_PACKET = {"badpackets", "packetorder", "multiactions", "multiinteract", "invalidinteract", "selfinteract",
            "post", "transactionorder", "crash", "exploit", "inventory"};

    record GrimFlag(UUID uuid, String check, String verbose, boolean setback, boolean experimental) {}

    private final AnarchyCore pl;
    private final Map<UUID, ACPlayer> players = new HashMap<>();
    final Model model;
    final CaseBook book;
    private final PacketTap tap;
    private final ItemChecks items;
    private final Processor proc;
    private GrimBridge grim;
    private final ConcurrentLinkedQueue<GrimFlag> grimFlags = new ConcurrentLinkedQueue<>();
    private final AtomicInteger grimQueued = new AtomicInteger();
    private final EnumSet<Feature> disabled = EnumSet.noneOf(Feature.class);
    private final double[] decayNormal = new double[ACPlayer.F], decayWatch = new double[ACPlayer.F];
    private boolean nms = true, webhookWarned;
    private long lastCleanup, lastFlush;
    private HttpClient http;

    public AntiCheat(AnarchyCore pl) {
        this.pl = pl;
        model = new Model(new File(pl.getDataFolder(), "anticheat-model.yml"), pl.getLogger());
        book = new CaseBook(new File(pl.getDataFolder(), "anticheat-cases.yml"), pl);
        tap = new PacketTap(this);
        items = new ItemChecks(this);
        proc = new Processor(this, items);
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            decayNormal[i] = f.halfLife <= 0 ? 1 : Math.pow(0.5, 1.0 / f.halfLife);
            decayWatch[i] = f.halfLife <= 0 ? 1 : Math.pow(0.5, 1.0 / (f.halfLife * 2.0));   // под наблюдением улики держатся вдвое дольше
        }
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, pl);
        Bukkit.getPluginManager().registerEvents(items, pl);
        if (Bukkit.getPluginManager().getPlugin("GrimAC") != null) {
            try { grim = new GrimBridge(this); grim.hook(); }
            catch (Throwable t) { grim = null; warn("GrimAC найден, но его API недоступно: " + t); }
        } else info("GrimAC не найден - ИИ работает только на собственных проверках");
        refreshDisabled();
        for (Player p : Bukkit.getOnlinePlayers()) join(p);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(pl, this::second, 20L, 20L);
        info(aiName() + " запущен" + (enabled() ? "" : " (выключен: anticheat.enabled = false)"));
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) tap.eject(p);
        if (grim != null) grim.unhook();
        book.flush(false);
    }

    public void save() { book.flush(true); }

    // ============================== служебное ==============================
    AnarchyCore plugin() { return pl; }
    void info(String s) { pl.getLogger().info("[AquaGuard] " + s); }
    void warn(String s) { pl.getLogger().warning("[AquaGuard] " + s); }
    String stamp() { return TIME.format(Instant.now()); }

    private boolean enabled() { return pl.getConfig().getBoolean("anticheat.enabled", true); }
    String aiName() { return pl.getConfig().getString("anticheat.name", "AquaGuard AI"); }
    private double watchT() { return pl.getConfig().getDouble("anticheat.watch-threshold", 0.40); }
    private double callT() { return pl.getConfig().getDouble("anticheat.call-threshold", 0.93); }
    private double minEvents() { return pl.getConfig().getDouble("anticheat.min-events", 5); }
    private boolean requireStaff() { return pl.getConfig().getBoolean("anticheat.require-staff-online", true); }
    private long acceptTimeout() { return Math.max(1, pl.getConfig().getLong("anticheat.accept-timeout-minutes", 5)) * 60_000L; }
    private long recallCooldown() { return pl.getConfig().getLong("anticheat.recall-cooldown-minutes", 30) * 60_000L; }
    private long cleanCooldown() { return pl.getConfig().getLong("anticheat.clean-cooldown-minutes", 120) * 60_000L; }
    private boolean exemptStaff() { return pl.getConfig().getBoolean("anticheat.exempt-staff", true); }
    private boolean learning() { return pl.getConfig().getBoolean("anticheat.learning", true); }
    private int maxPing() { return pl.getConfig().getInt("anticheat.max-ping", 400); }
    public boolean banOnQuitBeforeAccept() { return pl.getConfig().getBoolean("anticheat.ban-on-quit-before-accept", true); }

    private void refreshDisabled() {
        disabled.clear();
        for (String s : pl.getConfig().getStringList("anticheat.disabled-checks")) {
            Feature f = Feature.byName(s.trim());
            if (f != null) disabled.add(f);
        }
    }

    private boolean isAdmin(CommandSender s) { return !(s instanceof Player p) || p.isOp() || p.hasPermission("anarchy.admin"); }

    static String pct(double p) { return p >= 0.995 ? ">99%" : Math.round(p * 100) + "%"; }

    static String probColor(double p) { return p >= 0.9 ? "red" : p >= 0.6 ? "gold" : p >= 0.3 ? "yellow" : "green"; }

    private static String colored(double p) { String c = probColor(p); return "<" + c + ">" + pct(p) + "</" + c + ">"; }

    String blacklisted(String s, String path) {
        if (s == null || s.isEmpty()) return null;
        String l = s.toLowerCase(Locale.ROOT);
        for (String b : pl.getConfig().getStringList(path)) {
            if (b == null || b.isBlank()) continue;
            if (l.contains(b.toLowerCase(Locale.ROOT).trim())) return b;
        }
        return null;
    }

    // ============================== игроки ==============================
    ACPlayer peek(UUID u) { return players.get(u); }

    private ACPlayer data(Player p) { return players.computeIfAbsent(p.getUniqueId(), u -> new ACPlayer(u, p.getName())); }

    private void join(Player p) {
        ACPlayer d = data(p);
        d.name = p.getName();
        d.quitAt = 0;
        d.joined = System.currentTimeMillis();
        d.resetStream();
        d.tickEnd = false;
        d.tapped = false;
        d.clientChecked = false;
        d.contOpen = false;
        d.popPending = false;
        tap.inject(p, d);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        join(p);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && pl.admin().staff(p)) remindWaiting(p); }, 80L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        tap.eject(e.getPlayer());
        ACPlayer d = players.get(e.getPlayer().getUniqueId());
        if (d == null) return;
        d.quitAt = System.currentTimeMillis();
        d.tapped = false;
        d.active = false;
        d.contOpen = false;
    }

    /** Сразу применить права игрока в GrimAC (grim.disabled), не дожидаясь его периодической проверки. */
    public void grimRefresh(Player p) { if (grim != null) grim.refresh(p); }

    int rtt(Player p, ACPlayer d) {
        int g = grim == null ? -1 : grim.ping(p);
        return g >= 0 ? g : Math.max(0, p.getPing());
    }

    /** Через сколько серверных тиков игрок видит позицию цели (пинг + интерполяция клиента). */
    int viewDelay(Player p, ACPlayer d) { return (int) MathX.clamp(rtt(p, d) / 50 + 3, 2, 18); }

    double grimStep(Player p) { return grim == null ? 0 : grim.step(p); }

    Entity entity(Player p, int id) {
        if (nms) {
            try {
                net.minecraft.world.entity.Entity e = ((CraftWorld) p.getWorld()).getHandle().getEntity(id);
                return e == null ? null : e.getBukkitEntity();
            } catch (Throwable t) {
                nms = false;
                warn("Быстрый поиск сущностей недоступен (" + t + "), включён медленный");
            }
        }
        for (Entity e : p.getNearbyEntities(8, 8, 8)) if (e.getEntityId() == id) return e;
        return null;
    }

    private boolean computeActive(Player p, ACPlayer d, long now) {
        if (!enabled() || now < d.exemptUntil) return false;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
        PlayerData pd = pl.store().get(p.getUniqueId());
        if (pd == null || !pd.authed || pl.antibot().pending(p)) return false;
        if (pl.admin().isFrozen(p)) return false;
        if (book.testers.contains(p.getUniqueId())) return true;
        if (p.hasPermission("anarchy.ac.bypass")) return false;
        return !(exemptStaff() && pl.admin().staff(p));
    }

    /** Насколько можно верить уликам сейчас: высокий пинг, просевший TPS и первые секунды после входа дают ложные срабатывания. */
    private double reliability(int rtt, double tps, long sinceJoin) {
        double r = 1;
        if (rtt > maxPing()) r *= 0.15; else if (rtt > 250) r *= 0.6;
        if (tps < 15) r *= 0.25; else if (tps < 18) r *= 0.6;
        if (sinceJoin < 15_000) r *= 0.3;
        return r;
    }

    // ============================== такты ==============================
    private void tick() {
        GrimFlag g;
        int n = 0;
        while (n++ < 3000 && (g = grimFlags.poll()) != null) { grimQueued.decrementAndGet(); grim(g); }
        boolean ok = !tap.broken();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ACPlayer d = players.get(p.getUniqueId());
            if (d == null) continue;
            d.recordPos(p.getLocation());
            proc.drain(p, d, ok && d.active);
        }
    }

    private void second() {
        long now = System.currentTimeMillis();
        refreshDisabled();
        double tps = Bukkit.getTPS()[0], watchT = watchT(), callT = callT();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ACPlayer d = players.get(p.getUniqueId());
            if (d == null) continue;
            d.active = computeActive(p, d, now);
            d.rel = reliability(rtt(p, d), tps, now - d.joined);
            if (d.popPending && System.nanoTime() - d.popNanos > 5_000_000_000L) d.popPending = false;
            double[] k = d.status >= 1 ? decayWatch : decayNormal;
            for (int i = 0; i < ACPlayer.F; i++) {
                d.score[i] *= k[i];
                d.events[i] *= k[i];
                if (d.score[i] < 1e-4) d.score[i] = 0;
            }
            evaluate(d);
            if (d.status == 2 || !d.active) continue;
            if (d.status == 0 && d.prob >= watchT) startWatch(d, now);
            else if (d.status == 1) {
                if (d.prob < watchT * 0.5) {
                    if (d.lowSince == 0) d.lowSince = now;
                    else if (now - d.lowSince > 180_000) stopWatch(d, now);
                } else d.lowSince = 0;
            }
            if (d.prob >= callT && callable(p, d, now)) {
                if (book.testers.contains(d.id)) selfTestCall(p, d, now);
                else if (requireStaff() && staffOnline(p) == 0) noStaff(p, d, now);
                else call(p, d, false);
            }
        }
        items.second();
        timeouts(now);
        if (now - lastCleanup > 60_000) {
            lastCleanup = now;
            players.values().removeIf(d -> d.quitAt > 0 && now - d.quitAt > 15 * 60_000L && d.caseId == 0);
        }
        if (now - lastFlush > 10_000) { lastFlush = now; book.flush(true); }
    }

    private void evaluate(ACPlayer d) {
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            d.xs[i] = disabled.contains(f) ? 0 : Model.x(f, d.score[i]);
        }
        d.prob = model.prob(d.xs);
    }

    // ============================== улики ==============================
    void flag(Player p, Feature f, double sev, String detail) {
        ACPlayer d = players.get(p.getUniqueId());
        if (d != null) flag(p, d, f, sev, detail);
    }

    void flag(Player p, ACPlayer d, Feature f, double sev, String detail) {
        if (d.active) add(p, d, f, sev, detail, false);
    }

    /** Улика без поправки на пинг/лаг и даже до входа в аккаунт (бренд клиента, X-Ray). */
    void flagAlways(Player p, ACPlayer d, Feature f, double sev, String detail) { add(p, d, f, sev, detail, true); }

    private void add(Player p, ACPlayer d, Feature f, double sev, String detail, boolean raw) {
        if (!enabled() || disabled.contains(f)) return;
        if (!raw) sev *= d.rel;
        if (sev < 0.005) return;
        int i = f.ordinal();
        d.score[i] += sev;
        d.events[i] += 1;
        d.total[i]++;
        long now = System.currentTimeMillis();
        if (now - d.lastLine[i] < 1500) { d.silent[i]++; return; }
        int more = d.silent[i];
        d.silent[i] = 0;
        d.lastLine[i] = now;
        evaluate(d);
        d.addLog(TIME.format(Instant.ofEpochMilli(now)) + " " + f.title + ": " + detail + (more > 0 ? " (+" + more + " похожих)" : "") + " [ИИ " + pct(d.prob) + "]");
        Component msg = mm("<dark_aqua>" + ICON + " <aqua>" + esc(d.name) + "</aqua> <dark_gray>»</dark_gray> <white>" + esc(f.title) + "</white><gray>: " + esc(detail)
                + (more > 0 ? " <dark_gray>(+" + more + ")" : "") + " <dark_gray>| <gray>ИИ " + colored(d.prob))
                .clickEvent(ClickEvent.runCommand("/ac info " + d.name)).hoverEvent(HoverEvent.showText(mm("<gray>Открыть досье")));
        for (Player s : listeners(d)) if (!s.equals(p)) s.sendMessage(msg);
        if (book.testers.contains(d.id)) p.sendMessage(msg);
    }

    /** Кому идут сообщения античита об игроке: модератор, принявший его проверку, и включившие /ac alerts. */
    private Set<Player> listeners(ACPlayer d) {
        Set<Player> to = new HashSet<>();
        for (UUID u : book.alerts) {
            Player s = Bukkit.getPlayer(u);
            if (s != null && pl.admin().staff(s)) to.add(s);
        }
        Player m = checker(d);
        if (m != null) to.add(m);
        return to;
    }

    private Player checker(ACPlayer d) {
        if (d.caseId == 0) return null;
        CaseBook.Case c = book.get(d.caseId);
        return c != null && CaseBook.ACCEPTED.equals(c.status) && c.moderator != null ? Bukkit.getPlayerExact(c.moderator) : null;
    }

    /** Вызывается из потоков GrimAC. */
    void grimFlag(UUID u, String check, String verbose, boolean setback, boolean experimental) {
        if (grimQueued.incrementAndGet() > 20_000) { grimQueued.decrementAndGet(); return; }
        grimFlags.add(new GrimFlag(u, check == null ? "?" : check, verbose == null ? "" : verbose, setback, experimental));
    }

    private void grim(GrimFlag g) {
        Player p = Bukkit.getPlayer(g.uuid());
        if (p == null) return;
        String n = g.check().toLowerCase(Locale.ROOT);
        Feature f = grimFeature(n);
        if (f == null) return;
        String v = g.verbose().replaceAll("§.|<[^>]*>", "").trim();
        if (v.length() > 60) v = v.substring(0, 60) + "…";
        flag(p, f, grimSeverity(n, f, g.setback()) * (g.experimental() ? 0.5 : 1), "Grim " + g.check() + (v.isEmpty() ? "" : " (" + v + ")"));
    }

    static Feature grimFeature(String n) {
        if (n.startsWith("reach") || n.startsWith("hitbox")) return Feature.GRIM_COMBAT;
        if (n.startsWith("aim")) return Feature.GRIM_AIM;
        if (n.contains("timer")) return Feature.GRIM_TIMER;
        for (String m : GRIM_MOVE) if (n.startsWith(m)) return Feature.GRIM_MOVE;
        if (n.contains("place") || n.startsWith("scaffold")) return Feature.GRIM_PLACE;
        if (n.contains("break")) return Feature.GRIM_BREAK;
        for (String m : GRIM_PACKET) if (n.startsWith(m)) return Feature.GRIM_PACKET;
        return null;
    }

    static double grimSeverity(String n, Feature f, boolean setback) {
        return switch (f) {
            case GRIM_COMBAT -> n.startsWith("reach") ? 0.35 : 0.3;
            case GRIM_MOVE -> n.startsWith("simulation") || n.startsWith("prediction") ? (setback ? 0.08 : 0.05)
                    : n.startsWith("phase") || n.startsWith("antikb") || n.startsWith("knockback") ? 0.25 : n.startsWith("nofall") ? 0.15 : 0.1;
            case GRIM_PLACE -> 0.18;
            case GRIM_BREAK -> 0.15;
            case GRIM_PACKET -> n.startsWith("crash") || n.startsWith("exploit") ? 0.4 : 0.18;
            default -> 0.12;
        };
    }

    /** Детектор X-Ray из модерации. */
    public void xray(Player p, String reason) {
        ACPlayer d = players.get(p.getUniqueId());
        if (d != null) add(p, d, Feature.XRAY, 0.8, reason, true);
    }

    // ============================== наблюдение и вызов ==============================
    private record Reason(Feature f, double contrib, double share) {}

    private List<Reason> reasons(double[] xs) {
        List<Reason> out = new ArrayList<>();
        double sum = 0;
        for (Feature f : Feature.ALL) sum += Math.max(0, model.w[f.ordinal()] * xs[f.ordinal()]);
        for (Feature f : Feature.ALL) {
            double c = model.w[f.ordinal()] * xs[f.ordinal()];
            if (c > 0.05) out.add(new Reason(f, c, sum > 0 ? c / sum : 0));
        }
        out.sort((a, b) -> Double.compare(b.contrib(), a.contrib()));
        return out;
    }

    private List<String> reasonLines(ACPlayer d, int n) {
        List<String> out = new ArrayList<>();
        for (Reason r : reasons(d.xs)) {
            if (out.size() >= n) break;
            out.add(r.f().title + " - " + d.total[r.f().ordinal()] + " эп., вклад " + Math.round(r.share() * 100) + "%");
        }
        return out;
    }

    private String reasonsShort(double[] xs, int n) {
        List<String> out = new ArrayList<>();
        for (Reason r : reasons(xs)) {
            if (out.size() >= n) break;
            out.add(r.f().title);
        }
        return out.isEmpty() ? "улик нет" : String.join(", ", out);
    }

    private void startWatch(ACPlayer d, long now) {
        d.status = 1;
        d.statusSince = now;
        d.lowSince = 0;
        String r = reasonsShort(d.xs, 3);
        d.addLog(stamp() + " " + ICON + " ИИ начал усиленное наблюдение: вероятность читов " + pct(d.prob) + " (" + r + ")");
        Component m = mm("<dark_aqua>" + ICON + " <aqua>" + esc(aiName()) + "</aqua> <gray>следит за <white>" + esc(d.name) + "</white>: вероятность читов " + colored(d.prob) + " <dark_gray>(" + esc(r) + ")")
                .clickEvent(ClickEvent.runCommand("/ac info " + d.name));
        for (UUID u : book.alerts) { Player s = Bukkit.getPlayer(u); if (s != null && pl.admin().staff(s)) s.sendMessage(m); }
        Player self = book.testers.contains(d.id) ? Bukkit.getPlayer(d.id) : null;
        if (self != null && !book.alerts.contains(d.id)) self.sendMessage(m);
        info(d.name + ": начато наблюдение, " + pct(d.prob) + " - " + r);
    }

    private void stopWatch(ACPlayer d, long now) {
        d.status = 0;
        d.statusSince = now;
        d.lowSince = 0;
        d.addLog(stamp() + " " + ICON + " Наблюдение снято: подозрение упало до " + pct(d.prob));
    }

    private boolean callable(Player p, ACPlayer d, long now) {
        if (now < d.nextCallAt || d.caseId != 0 || pl.admin().onCheck(d.id) || pl.admin().isFrozen(p)) return false;
        int medium = 0;
        boolean strong = false;
        double ev = 0;
        for (Feature f : Feature.ALL) {
            double x = d.xs[f.ordinal()];
            if (x >= 0.35) medium++;
            if (f.strong && x >= 0.75) strong = true;
            if (x >= 0.1) ev += d.events[f.ordinal()];
        }
        if (!strong && medium < 2) return false;
        return ev >= minEvents() || d.xs[Feature.CLIENT.ordinal()] >= 0.5;
    }

    private int staffOnline(Player except) {
        int n = 0;
        for (Player s : Bukkit.getOnlinePlayers()) if (!s.equals(except) && pl.admin().staff(s)) n++;
        return n;
    }

    private void staffMsg(String text) {
        for (Player s : Bukkit.getOnlinePlayers()) if (pl.admin().staff(s)) s.sendMessage(mm(text));
    }

    private CaseBook.Case newCase(Player p, ACPlayer d, boolean test) {
        CaseBook.Case c = book.create(d.id, p.getName());
        c.prob = d.prob;
        c.test = test;
        Location l = p.getLocation();
        c.where = l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        System.arraycopy(d.xs, 0, c.xs, 0, ACPlayer.F);
        c.reasons.addAll(reasonLines(d, 5));
        return c;
    }

    /** Подозрение сильное, но модераторов нет: не замораживаем, оставляем письмо в почте (и в Discord). */
    private void noStaff(Player p, ACPlayer d, long now) {
        if (now - d.noStaffNote < 30 * 60_000L) return;
        d.noStaffNote = now;
        CaseBook.Case c = newCase(p, d, false);
        c.status = CaseBook.NOTED;
        c.closed = now;
        c.report = pl.moderation().aiReport(p, "ИИ " + pct(d.prob) + ", не вызван - модераторов не было в сети: " + reasonsShort(d.xs, 3));
        d.addLog(stamp() + " " + ICON + " Пора вызывать на проверку (" + pct(d.prob) + "), но модераторов нет в сети - письмо оставлено в почте (дело #" + c.id + ")");
        book.snapshotLog(c, d.log);
        webhook("⛨ **" + aiName() + "** сильно подозревает **" + p.getName() + "** (" + pct(d.prob) + "), но модераторов нет в сети - проверку некому принять.\n"
                + "Улики: " + reasonsShort(d.xs, 3) + "\nДело #" + c.id + ", " + c.where);
        warn(p.getName() + " - вероятность читов " + pct(d.prob) + ", модераторов нет: письмо в почте, дело #" + c.id);
    }

    private void call(Player p, ACPlayer d, boolean test) {
        long now = System.currentTimeMillis();
        CaseBook.Case c = newCase(p, d, test);
        c.report = pl.moderation().aiReport(p, (test ? "[ТЕСТ] " : "") + "ИИ " + pct(d.prob) + ": " + reasonsShort(d.xs, 3));
        d.caseId = c.id;
        d.status = 2;
        d.statusSince = now;
        pl.admin().aiCall(p, c.id);
        d.addLog(stamp() + " " + ICON + " ВЫЗОВ НА ПРОВЕРКУ" + (test ? " (ТЕСТ)" : "") + ": дело #" + c.id + ", вероятность читов " + pct(d.prob));
        book.snapshotLog(c, d.log);
        p.sendMessage(mm(" "));
        p.sendMessage(mm("<dark_gray>▬▬▬▬▬▬ <red><bold>ПРОВЕРКА НА ЧИТЫ</bold></red> <dark_gray>▬▬▬▬▬▬"));
        p.sendMessage(mm("<aqua>" + ICON + " ИИ-античит <bold>" + esc(aiName()) + "</bold> вызвал тебя на проверку."));
        p.sendMessage(mm("<white>Ты заморожен. Модераторам уже пришло письмо - дождись, пока один из них примет проверку."));
        if (banOnQuitBeforeAccept()) p.sendMessage(mm("<red>Не выходи с сервера: выход = бан на 30 дней."));
        p.sendMessage(mm("<gray>Написать модераторам: <yellow>/mod <сообщение>"));
        p.showTitle(Title.title(mm("<red><bold>ВЫЗОВ НА ПРОВЕРКУ"), mm("<white>Тебя вызвал ИИ-античит. <yellow>Жди модератора"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 0.7f);
        letter(c, p);
        webhook("⛨ **" + aiName() + "** вызвал на проверку **" + p.getName() + "**" + (test ? " (ТЕСТ)" : "") + "\nУверенность ИИ: " + pct(c.prob)
                + "\nУлики: " + String.join("; ", c.reasons) + "\nДело #" + c.id + ", " + c.where + "\nПринять в игре: /ac accept #" + c.id);
        warn(p.getName() + " вызван на проверку: " + pct(c.prob) + ", дело #" + c.id + " - " + reasonsShort(d.xs, 3));
    }

    /** Самопроверка: показать, что ИИ вызвал бы игрока, без заморозки и письма модераторам. */
    private void selfTestCall(Player p, ACPlayer d, long now) {
        CaseBook.Case c = newCase(p, d, true);
        c.status = CaseBook.SELFTEST;
        c.closed = now;
        d.nextCallAt = now + 60_000;
        d.status = 1;
        d.addLog(stamp() + " " + ICON + " [САМОПРОВЕРКА] ИИ вызвал бы на проверку: вероятность " + pct(d.prob) + " (дело #" + c.id + ")");
        book.snapshotLog(c, d.log);
        p.sendMessage(mm(" "));
        p.sendMessage(mm("<dark_gray>▬▬▬▬ <aqua><bold>" + ICON + " САМОПРОВЕРКА: ВЫЗОВ НА ПРОВЕРКУ</bold></aqua> <dark_gray>▬▬▬▬"));
        p.sendMessage(mm("<white>ИИ вызвал бы тебя на проверку. Уверенность: <bold>" + colored(d.prob) + "</bold> <dark_gray>(дело #" + c.id + ")"));
        for (String r : c.reasons) p.sendMessage(mm(" <red>▸</red> <white>" + esc(r)));
        p.sendMessage(mm("<gray>Обычного игрока сейчас заморозило бы, а модераторам ушло бы письмо. ")
                .append(mm("<gold>[≡ Досье]").clickEvent(ClickEvent.runCommand("/ac info " + p.getName())))
                .append(mm(" ")).append(mm("<aqua>[✉ Письмо]").clickEvent(ClickEvent.runCommand("/ac letter #" + c.id))));
        p.showTitle(Title.title(mm("<aqua><bold>" + ICON + " ВЫЗОВ (ТЕСТ)"), mm("<white>ИИ вызвал бы тебя: " + colored(d.prob)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 0.7f);
        info(p.getName() + " [самопроверка]: ИИ вызвал бы на проверку, " + pct(d.prob) + ", дело #" + c.id + " - " + reasonsShort(d.xs, 3));
        book.touch();
    }

    /** Письмо всем модераторам в сети: кто вызван, почему и кнопка «Принять». */
    private void letter(CaseBook.Case c, Player t) {
        List<Component> lines = letterLines(c, t);
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.equals(t) || !pl.admin().staff(s)) continue;
            for (Component l : lines) s.sendMessage(l);
            s.showTitle(Title.title(mm("<aqua>✉ Письмо от ИИ-античита"), mm("<white>" + esc(c.name) + " <gray>вызван на проверку - <yellow>/ac accept"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
            s.playSound(s.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.6f);
        }
    }

    private List<Component> letterLines(CaseBook.Case c, Player t) {
        List<Component> out = new ArrayList<>();
        out.add(mm(" "));
        out.add(mm("<dark_gray>▬▬▬▬ <aqua><bold>✉ ПИСЬМО ОТ ИИ-АНТИЧИТА</bold></aqua> <dark_gray>▬▬▬▬"));
        out.add(mm("<aqua>" + ICON + " " + esc(aiName()) + "</aqua> <white>вызвал на проверку <yellow><bold>" + esc(c.name) + "</bold></yellow> <dark_gray>(дело #" + c.id + (c.test ? ", ТЕСТ" : "") + ")"));
        ACPlayer d = players.get(c.uuid);
        out.add(mm("<gray>Уверенность ИИ: <bold>" + colored(c.prob) + "</bold> <dark_gray>| <gray>пинг <white>" + (t != null && d != null ? rtt(t, d) + " мс" : "?")
                + " <dark_gray>| <gray>" + esc(c.where)));
        out.add(mm("<gray>Главные улики:"));
        for (int i = 0; i < Math.min(3, c.reasons.size()); i++) out.add(mm(" <red>▸</red> <white>" + esc(c.reasons.get(i))));
        out.add(mm(" ").append(mm("<green><bold>[✔ ПРИНЯТЬ ПРОВЕРКУ]").clickEvent(ClickEvent.runCommand("/ac accept #" + c.id)).hoverEvent(HoverEvent.showText(mm("<gray>Взять проверку себе и получить все сообщения античита"))))
                .append(mm("  ")).append(mm("<aqua>[◉ Следить]").clickEvent(ClickEvent.runCommand("/spec " + c.name)))
                .append(mm("  ")).append(mm("<gold>[≡ Досье]").clickEvent(ClickEvent.runCommand("/ac info " + c.name))));
        out.add(mm("<dark_gray>Игрок заморожен и ждёт. Если за " + acceptTimeout() / 60_000 + " мин никто не примет - его отпустят."));
        return out;
    }

    private void remindWaiting(Player s) {
        for (CaseBook.Case c : book.open()) {
            if (!CaseBook.WAITING.equals(c.status)) continue;
            Player t = Bukkit.getPlayer(c.uuid);
            if (t == null) continue;
            for (Component l : letterLines(c, t)) s.sendMessage(l);
            s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
        }
    }

    private void timeouts(long now) {
        for (CaseBook.Case c : book.open()) {
            if (!CaseBook.WAITING.equals(c.status) || now - c.time < acceptTimeout()) continue;
            c.status = CaseBook.EXPIRED;
            c.closed = now;
            Player t = Bukkit.getPlayer(c.uuid);
            ACPlayer d = players.get(c.uuid);
            if (t != null) {
                pl.admin().aiRelease(t);
                pl.msg(t, "<yellow>Модераторы не успели принять проверку - ты разморожен. <gray>Приятной игры!");
            }
            if (d != null) {
                d.caseId = 0;
                d.status = 1;
                d.statusSince = now;
                d.nextCallAt = now + recallCooldown();
                if (c.test) d.scale(0); else d.scale(0.6);
                d.addLog(stamp() + " " + ICON + " Проверку никто не принял за " + acceptTimeout() / 60_000 + " мин - игрок отпущен, наблюдение продолжается");
                book.snapshotLog(c, d.log);
            }
            pl.moderation().aiReportNote(c.report, "никто не принял");
            staffMsg("<gray>" + ICON + " Проверку <white>" + esc(c.name) + "</white> никто не принял за " + acceptTimeout() / 60_000 + " мин - игрок отпущен, ИИ продолжает следить. <dark_gray>(дело #" + c.id + ")");
            book.touch();
        }
    }

    // ============================== принятие и решения ==============================
    public void accept(Player mod, String arg) {
        CaseBook.Case c = findCase(arg, true);
        if (c == null) { pl.msg(mod, "<red>Нет вызовов ИИ, ожидающих модератора" + (arg == null ? "" : " (" + esc(arg) + ")")); return; }
        Player t = Bukkit.getPlayer(c.uuid);
        if (t == null) { pl.msg(mod, "<red>" + esc(c.name) + " не в сети"); return; }
        if (t.equals(mod)) { pl.msg(mod, "<red>Нельзя проверять самого себя"); return; }
        if (CaseBook.ACCEPTED.equals(c.status)) {
            Player cur = c.moderator == null ? null : Bukkit.getPlayerExact(c.moderator);
            if (cur != null && !cur.equals(mod)) { pl.msg(mod, "<yellow>Проверку уже принял <white>" + esc(c.moderator)); return; }
        } else if (!CaseBook.WAITING.equals(c.status)) {
            pl.msg(mod, "<red>Дело #" + c.id + " уже закрыто: " + CaseBook.statusName(c.status));
            return;
        }
        c.status = CaseBook.ACCEPTED;
        c.moderator = mod.getName();
        c.accepted = System.currentTimeMillis();
        ACPlayer d = players.get(c.uuid);
        pl.admin().aiAccept(mod, t);
        pl.moderation().aiReportTaken(c.report, mod.getName());
        if (d != null) {
            d.addLog(stamp() + " " + ICON + " Проверку принял модератор " + mod.getName());
            book.snapshotLog(c, d.log);
        }
        pl.msg(t, "<green>✔ Модератор <white>" + esc(mod.getName()) + "</white> принял твою проверку. <gray>Следуй его указаниям, связь: <yellow>/mod <сообщение>");
        String ct = pl.getConfig().getString("admin.check-text", "");
        if (ct != null && !ct.isBlank()) pl.msg(t, "<gray>" + ct);
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        for (Player s : Bukkit.getOnlinePlayers())
            if (!s.equals(mod) && !s.equals(t) && pl.admin().staff(s))
                pl.msg(s, "<gold>[Проверка] <white>" + esc(mod.getName()) + " <gray>принял вызов ИИ: <white>" + esc(c.name) + " <dark_gray>(дело #" + c.id + ")");
        pl.msg(mod, "<green>Проверка <white>" + esc(c.name) + "</white> твоя. <gray>Все сообщения античита об игроке будут приходить тебе. Окончить: <white>/check stop " + esc(c.name) + "</white> или <white>/check ban " + esc(c.name));
        dossier(mod, c.name);
        letterBook(mod, c);
        book.touch();
    }

    private CaseBook.Case findCase(String arg, boolean openOnly) {
        if (arg == null || arg.isBlank()) {
            for (CaseBook.Case c : book.open()) if (CaseBook.WAITING.equals(c.status) && Bukkit.getPlayer(c.uuid) != null) return c;
            return null;
        }
        String a = arg.startsWith("#") ? arg.substring(1) : arg;
        try {
            CaseBook.Case c = book.get(Integer.parseInt(a));
            if (c != null) return c;
        } catch (NumberFormatException ignored) {}
        PlayerData pd = pl.store().byName(a);
        if (pd == null) return null;
        CaseBook.Case c = book.active(pd.uuid);
        return c != null || openOnly ? c : book.last(pd.uuid);
    }

    /** /check stop и /check ban. cheat - решение модератора. */
    public void checkEnded(UUID u, boolean cheat, String by) {
        ACPlayer d = players.get(u);
        CaseBook.Case c = book.active(u);
        if (c != null) { close(c, d, cheat ? CaseBook.CHEAT : CaseBook.CLEAN, by); return; }
        if (d != null && learning()) {
            double max = 0;
            for (double x : d.xs) max = Math.max(max, x);
            if (max >= 0.3) {
                model.learn(d.xs, cheat, 0.5);
                d.addLog(stamp() + " " + ICON + " Ручная проверка (" + by + "): " + (cheat ? "читер" : "чист") + " - ИИ дообучен");
            }
        }
        if (!cheat && d != null) afterClean(d);
    }

    /** Любой бан игрока во время открытого дела = решение «читер». */
    public void banned(UUID u, String by) {
        CaseBook.Case c = book.active(u);
        if (c != null) close(c, players.get(u), CaseBook.CHEAT, by);
    }

    /** Игрок вышел во время вызова/проверки. Возвращает, банить ли его. */
    public boolean quitDuringCheck(UUID u, boolean beforeAccept) {
        CaseBook.Case c = book.active(u);
        if (c != null) {
            ACPlayer d = players.get(u);
            c.status = CaseBook.QUIT;
            c.closed = System.currentTimeMillis();
            if (d != null) {
                d.addLog(stamp() + " " + ICON + " Игрок вышел с сервера во время " + (beforeAccept ? "вызова" : "проверки"));
                book.snapshotLog(c, d.log);
                d.caseId = 0;
                d.status = 1;
            }
            pl.moderation().aiReportClose(c.report, "система", "вышел с проверки");
            staffMsg("<red>" + ICON + " " + esc(c.name) + " вышел во время " + (beforeAccept ? "вызова ИИ" : "проверки") + " <dark_gray>(дело #" + c.id + ")");
            book.touch();
        }
        return !beforeAccept || banOnQuitBeforeAccept();
    }

    private void close(CaseBook.Case c, ACPlayer d, String status, String by) {
        c.status = status;
        c.closed = System.currentTimeMillis();
        if (c.moderator == null) c.moderator = by;
        boolean cheat = CaseBook.CHEAT.equals(status);
        boolean teach = (cheat || CaseBook.CLEAN.equals(status) || CaseBook.CANCELLED.equals(status)) && !c.test && !c.learned && learning();
        if (teach) { model.learn(c.xs, cheat, 1.0); c.learned = true; }
        String plain = CaseBook.statusName(status).replaceAll("<[^>]*>", "");
        if (d != null) {
            d.addLog(stamp() + " " + ICON + " Решение по делу #" + c.id + ": " + plain + " (" + by + ")" + (teach ? " - ИИ дообучен" : ""));
            book.snapshotLog(c, d.log);
            d.caseId = 0;
            if (c.test) d.scale(0);
            if (cheat) d.status = 1; else afterClean(d);
        }
        pl.moderation().aiReportClose(c.report, by, plain);
        staffMsg("<gray>" + ICON + " Дело #" + c.id + " (<white>" + esc(c.name) + "</white>) закрыто: " + CaseBook.statusName(status) + " <dark_gray>(" + esc(by) + ")" + (teach ? " <dark_aqua>ИИ дообучен" : ""));
        book.touch();
    }

    private void afterClean(ACPlayer d) {
        d.nextCallAt = System.currentTimeMillis() + cleanCooldown();
        d.scale(0.25);
        d.status = 0;
        d.lowSince = 0;
    }

    // ============================== досье ==============================
    public void dossier(CommandSender s, String name) {
        PlayerData pd = pl.store().byName(name);
        ACPlayer d = pd == null ? null : players.get(pd.uuid);
        CaseBook.Case c = pd == null ? null : book.last(pd.uuid);
        if (d == null && c == null) { s.sendMessage(mm("<red>У ИИ нет данных об игроке " + esc(name))); return; }
        String nm = d != null ? d.name : c.name;
        Player t = Bukkit.getPlayerExact(nm);
        s.sendMessage(mm("<dark_gray>▬▬▬ <aqua><bold>" + ICON + " Досье ИИ-античита: " + esc(nm) + "</bold></aqua> <dark_gray>▬▬▬"));
        if (d != null) {
            String st = d.status == 2 ? "<red>вызван на проверку" : d.status == 1 ? "<gold>под наблюдением" : "<green>чист";
            s.sendMessage(mm("<gray>Вероятность читов: <bold>" + colored(d.prob) + "</bold> <dark_gray>| <gray>статус: " + st
                    + " <dark_gray>| <gray>клиент: <white>" + esc(d.brand == null ? "?" : d.brand) + (t != null ? " <dark_gray>| <gray>пинг <white>" + rtt(t, d) + " мс" : " <dark_gray>| не в сети")));
            List<String> rl = reasonLines(d, 6);
            if (rl.isEmpty()) s.sendMessage(mm("<green>Улик нет"));
            else {
                s.sendMessage(mm("<gray>Улики (вклад в решение ИИ):"));
                for (String r : rl) s.sendMessage(mm(" <red>▸</red> <white>" + esc(r)));
            }
            if (d.totemReactN > 0) s.sendMessage(mm("<gray>Тотемы: <white>" + d.totemPops + "</white> срабатываний, возврат в руку в среднем за <white>" + d.totemReactSum / d.totemReactN + " мс"));
            if (!d.active && t != null) s.sendMessage(mm("<dark_gray>Сейчас улики не собираются (заморожен, не вошёл, креатив или персонал)"));
        }
        if (c != null) s.sendMessage(mm("<gray>Последнее дело: <white>#" + c.id + "</white> " + DATE.format(Instant.ofEpochMilli(c.time)) + " - " + CaseBook.statusName(c.status)
                + (c.moderator != null ? " <dark_gray>(" + esc(c.moderator) + ")" : "")));
        List<String> log = d != null ? new ArrayList<>(d.log) : c.log;
        int from = Math.max(0, log.size() - 15);
        s.sendMessage(mm("<gray>Сообщения античита (последние " + (log.size() - from) + " из " + log.size() + "):"));
        for (int i = from; i < log.size(); i++) s.sendMessage(mm(" <dark_gray>" + esc(log.get(i))));
        Component b = mm(" ").append(mm("<yellow>[≡ Все сообщения]").clickEvent(ClickEvent.runCommand("/ac log " + nm + " 1")))
                .append(mm("  ")).append(mm("<aqua>[✉ Письмо]").clickEvent(ClickEvent.runCommand("/ac letter " + nm)).hoverEvent(HoverEvent.showText(mm("<gray>Открыть все сообщения книгой"))));
        if (t != null) {
            b = b.append(mm("  ")).append(mm("<aqua>[◉ Следить]").clickEvent(ClickEvent.runCommand("/spec " + nm)));
            if (pl.admin().onCheck(t.getUniqueId())) {
                b = b.append(mm("  ")).append(mm("<white>[❄ Заморозка]").clickEvent(ClickEvent.runCommand("/freeze " + nm)).hoverEvent(HoverEvent.showText(mm("<gray>Разморозить/заморозить: размороженный игрок\n<gray>двигается, а ИИ шлёт тебе новые улики"))))
                        .append(mm("  ")).append(mm("<green>[✔ Чист]").clickEvent(ClickEvent.runCommand("/check stop " + nm)))
                        .append(mm("  ")).append(mm("<red>[⛔ Читер]").clickEvent(ClickEvent.suggestCommand("/check ban " + nm)));
            } else if (d != null && d.caseId != 0 && isWaitingCase(d.caseId)) {
                b = b.append(mm("  ")).append(mm("<green><bold>[✔ Принять проверку]").clickEvent(ClickEvent.runCommand("/ac accept #" + d.caseId)));
            }
        }
        s.sendMessage(b);
    }

    private boolean isWaitingCase(int id) {
        CaseBook.Case c = book.get(id);
        return c != null && CaseBook.WAITING.equals(c.status);
    }

    /** Номер дела, если игрок сейчас ждёт модератора (для почты модерации). */
    public int waitingCase(String name) {
        for (CaseBook.Case c : book.open()) if (CaseBook.WAITING.equals(c.status) && c.name.equalsIgnoreCase(name)) return c.id;
        return 0;
    }

    private List<String> logOf(String name) {
        PlayerData pd = pl.store().byName(name);
        if (pd == null) return null;
        ACPlayer d = players.get(pd.uuid);
        if (d != null) return new ArrayList<>(d.log);
        CaseBook.Case c = book.last(pd.uuid);
        return c == null ? null : c.log;
    }

    private void logCmd(CommandSender s, String name, int page) {
        List<String> log = logOf(name);
        if (log == null) { s.sendMessage(mm("<red>У ИИ нет сообщений об игроке " + esc(name))); return; }
        int per = 15, pages = Math.max(1, (log.size() + per - 1) / per);
        page = Math.max(1, Math.min(page, pages));
        s.sendMessage(mm("<dark_gray>▬▬▬ <aqua>" + ICON + " Сообщения античита: " + esc(name) + " <gray>(стр. " + page + "/" + pages + ", всего " + log.size() + ")"));
        for (int i = (page - 1) * per; i < Math.min(log.size(), page * per); i++) s.sendMessage(mm(" <gray>" + esc(log.get(i))));
        Component nav = mm(" ");
        if (page > 1) nav = nav.append(mm("<yellow>[◀ Назад]").clickEvent(ClickEvent.runCommand("/ac log " + name + " " + (page - 1)))).append(mm("  "));
        if (page < pages) nav = nav.append(mm("<yellow>[Вперёд ▶]").clickEvent(ClickEvent.runCommand("/ac log " + name + " " + (page + 1))));
        s.sendMessage(nav);
    }

    /** Письмо-книга: все сообщения античита об игроке (открывается без выдачи предмета). */
    private void letterBook(Player viewer, CaseBook.Case c) {
        ACPlayer d = players.get(c.uuid);
        List<String> log = d != null ? new ArrayList<>(d.log) : c.log;
        List<Component> pages = new ArrayList<>();
        Component first = Component.text("✉ Письмо от ИИ-античита\n\n", NamedTextColor.DARK_AQUA)
                .append(Component.text("Игрок: ", NamedTextColor.DARK_GRAY)).append(Component.text(c.name + "\n", NamedTextColor.BLACK))
                .append(Component.text("Дело #" + c.id + (c.test ? " (тест)" : "") + "\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("Уверенность: ", NamedTextColor.DARK_GRAY)).append(Component.text(pct(d != null ? d.prob : c.prob) + "\n", NamedTextColor.DARK_RED))
                .append(Component.text("Статус: " + CaseBook.statusName(c.status).replaceAll("<[^>]*>", "") + "\n\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("Улики:\n", NamedTextColor.DARK_BLUE));
        for (int i = 0; i < Math.min(3, c.reasons.size()); i++) {
            String r = c.reasons.get(i);
            int cut = r.indexOf(" - ");
            first = first.append(Component.text("• " + (cut > 0 ? r.substring(0, cut) : r) + "\n", NamedTextColor.BLACK));
        }
        pages.add(first);
        int startAt = Math.max(0, log.size() - 400);
        StringBuilder page = new StringBuilder();
        int lines = 0;
        for (int i = startAt; i < log.size(); i++) {
            String l = log.get(i);
            if (l.length() > 200) l = l.substring(0, 200) + "…";
            if (page.length() + l.length() > 230 || lines >= 6) {
                pages.add(Component.text(page.toString(), NamedTextColor.BLACK));
                page.setLength(0);
                lines = 0;
            }
            page.append(l).append("\n\n");
            lines++;
        }
        if (page.length() > 0) pages.add(Component.text(page.toString(), NamedTextColor.BLACK));
        if (pages.size() > 100) pages = new ArrayList<>(pages.subList(pages.size() - 99, pages.size()));
        if (pages.size() == 1) pages.add(Component.text("Сообщений пока нет.", NamedTextColor.DARK_GRAY));
        viewer.openBook(Book.book(Component.text("Письмо ИИ: " + c.name), Component.text(aiName()), pages));
    }

    // ============================== команды ==============================
    public void command(CommandSender s, String[] a) {
        if (!pl.admin().staff(s)) { s.sendMessage(mm("<red>Нет прав")); return; }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        String arg = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "accept", "a" -> { if (s instanceof Player p) accept(p, arg); else s.sendMessage("Только в игре"); }
            case "info", "dossier", "i" -> { if (arg == null) s.sendMessage(mm("<red>/ac info <ник>")); else dossier(s, arg); }
            case "log" -> { if (arg == null) s.sendMessage(mm("<red>/ac log <ник> [страница]")); else logCmd(s, arg, a.length > 2 ? parse(a[2], 1) : Integer.MAX_VALUE); }
            case "letter", "book" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Только в игре"); return; }
                CaseBook.Case c = findCase(arg, false);
                if (c == null) { pl.msg(p, "<red>Дело не найдено. /ac letter <ник|#дело>"); return; }
                letterBook(p, c);
            }
            case "top", "list" -> top(s);
            case "cases" -> cases(s, arg == null ? 1 : parse(arg, 1));
            case "case" -> caseInfo(s, arg);
            case "alerts" -> { if (s instanceof Player p) toggleAlerts(p); }
            case "verdict" -> verdict(s, a);
            case "cancel" -> cancel(s, arg);
            case "exempt" -> exempt(s, a);
            case "model" -> model(s, arg != null && arg.equalsIgnoreCase("reset"));
            case "menu", "gui" -> { if (s instanceof Player p) menu(p); }
            case "test" -> test(s, arg);
            case "debug" -> debug(s, arg);
            case "testme", "selftest" -> { if (s instanceof Player p) testme(p); else s.sendMessage("Только в игре"); }
            default -> help(s);
        }
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s); } catch (Exception e) { return def; }
    }

    private void help(CommandSender s) {
        s.sendMessage(mm("<dark_gray>▬▬▬ <aqua><bold>" + ICON + " " + esc(aiName()) + "</bold></aqua> <dark_gray>▬▬▬"));
        s.sendMessage(mm("<yellow>/ac accept [ник|#дело] <gray>- принять вызов ИИ на проверку"));
        s.sendMessage(mm("<yellow>/ac info <ник> <gray>- досье: вероятность, улики, сообщения античита"));
        s.sendMessage(mm("<yellow>/ac log <ник> [стр] <gray>- все сообщения античита об игроке"));
        s.sendMessage(mm("<yellow>/ac letter <ник|#дело> <gray>- письмо-книга со всеми сообщениями"));
        s.sendMessage(mm("<yellow>/ac top <gray>- самые подозрительные игроки онлайн"));
        s.sendMessage(mm("<yellow>/ac cases [стр] <gray>| <yellow>/ac case <номер> <gray>- журнал дел"));
        s.sendMessage(mm("<yellow>/ac alerts <gray>- каждая улика ИИ в чат (вкл/выкл)"));
        s.sendMessage(mm("<yellow>/ac cancel <ник> <gray>- отменить вызов (ошибка ИИ, игрок чист)"));
        s.sendMessage(mm("<yellow>/ac verdict <ник> <cheat|clean> <gray>- научить ИИ по закрытому делу"));
        s.sendMessage(mm("<yellow>/ac menu <gray>- меню античита"));
        s.sendMessage(mm("<yellow>/ac testme <gray>- самопроверка: ИИ следит за тобой, даже если ты модератор (тест читов)"));
        if (isAdmin(s)) s.sendMessage(mm("<gold>/ac exempt <ник> <30m|2h|off> <gray>| <gold>/ac model [reset] <gray>| <gold>/ac test <ник> <gray>- тестовый вызов"));
        s.sendMessage(mm("<dark_gray>Проверку заканчивают как обычно: /check stop <ник> (чист) или /check ban <ник> (читер) - ИИ учится на этих решениях."));
    }

    private void debug(CommandSender s, String name) {
        if (!isAdmin(s)) { s.sendMessage(mm("<red>Только для администрации")); return; }
        Player t = name == null ? null : Bukkit.getPlayerExact(name);
        ACPlayer d = t == null ? null : players.get(t.getUniqueId());
        if (d == null) { s.sendMessage(mm("<red>/ac debug <ник> - игрок должен быть в сети")); return; }
        s.sendMessage(mm("<gray>" + esc(d.name) + ": перехват=" + d.tapped + (tap.broken() ? " (СЛОМАН)" : "") + " активен=" + d.active + " rel=" + String.format(Locale.ROOT, "%.2f", d.rel)
                + " пинг=" + rtt(t, d) + " TPS=" + String.format(Locale.ROOT, "%.1f", Bukkit.getTPS()[0])));
        s.sendMessage(mm("<gray>пакетов=" + d.dbgPackets + " тиков клиента=" + d.ctick + " tickEnd=" + d.tickEnd + " поворот=" + d.rotKnown
                + " ударов=" + d.dbgAttacks + " (цель найдена " + d.dbgResolved + ", не найдена " + d.dbgUnresolved + ")"));
        s.sendMessage(mm("<gray>кулдаун-удары=" + d.hitN + " клики в бою=" + d.accN + " W-tap=" + d.wtapN + " слежение=" + d.trN + " спринт=" + d.sprinting));
    }

    private void testme(Player p) {
        if (book.testers.remove(p.getUniqueId())) {
            pl.msg(p, "<yellow>Самопроверка выключена: <gray>ИИ снова не проверяет тебя как модератора.");
        } else {
            book.testers.add(p.getUniqueId());
            data(p).nextCallAt = 0;
            pl.msg(p, "<green>Самопроверка включена. <gray>ИИ следит за тобой как за обычным игроком (опка не мешает), каждая улика приходит тебе в чат,"
                    + " а когда ИИ решит вызвать - придёт тестовый вызов без заморозки. Играй в режиме выживания. Досье: <white>/ac info " + esc(p.getName()) + "</white>, выключить: <white>/ac testme");
        }
        book.touch();
    }

    private void toggleAlerts(Player p) {
        if (book.alerts.remove(p.getUniqueId())) pl.msg(p, "<yellow>Оповещения ИИ-античита выключены. <gray>Письма о вызовах на проверку приходят всегда.");
        else { book.alerts.add(p.getUniqueId()); pl.msg(p, "<green>Оповещения ИИ-античита включены: <gray>каждая улика будет приходить в чат."); }
        book.touch();
    }

    private List<ACPlayer> onlineSorted(int limit, double min) {
        List<ACPlayer> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ACPlayer d = players.get(p.getUniqueId());
            if (d != null && d.prob >= min) l.add(d);
        }
        l.sort((x, y) -> Double.compare(y.prob, x.prob));
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    private void top(CommandSender s) {
        List<ACPlayer> l = onlineSorted(10, 0.02);
        s.sendMessage(mm("<dark_gray>▬▬▬ <aqua><bold>" + ICON + " Подозрительные игроки онлайн</bold></aqua> <dark_gray>▬▬▬"));
        if (l.isEmpty()) { s.sendMessage(mm("<green>Подозрительных нет")); return; }
        for (ACPlayer d : l) {
            String st = d.status == 2 ? "<red>вызван" : d.status == 1 ? "<gold>наблюдение" : "<gray>-";
            s.sendMessage(mm(" <bold>" + colored(d.prob) + "</bold> <white>" + esc(d.name) + " <dark_gray>| " + st + " <dark_gray>| <gray>" + esc(reasonsShort(d.xs, 2)))
                    .clickEvent(ClickEvent.runCommand("/ac info " + d.name)).hoverEvent(HoverEvent.showText(mm("<gray>Досье"))));
        }
    }

    private void cases(CommandSender s, int page) {
        List<CaseBook.Case> all = book.all();
        int per = 10, pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(1, Math.min(page, pages));
        s.sendMessage(mm("<dark_gray>▬▬▬ <gold><bold>Журнал дел ИИ</bold></gold> <gray>(стр. " + page + "/" + pages + ", всего " + all.size() + ") <dark_gray>▬▬▬"));
        if (all.isEmpty()) s.sendMessage(mm("<gray>Дел пока нет"));
        for (int i = all.size() - 1 - (page - 1) * per; i >= Math.max(0, all.size() - page * per); i--) {
            CaseBook.Case c = all.get(i);
            s.sendMessage(mm(" <dark_gray>#" + c.id + " <gray>" + DATE.format(Instant.ofEpochMilli(c.time)) + " <white>" + esc(c.name) + " <dark_gray>| " + colored(c.prob)
                    + " <dark_gray>| " + CaseBook.statusName(c.status) + (c.moderator != null ? " <dark_gray>(" + esc(c.moderator) + ")" : "") + (c.test ? " <dark_gray>[тест]" : ""))
                    .clickEvent(ClickEvent.runCommand("/ac case " + c.id)));
        }
        if (page < pages) s.sendMessage(mm(" <yellow>[Дальше ▶]").clickEvent(ClickEvent.runCommand("/ac cases " + (page + 1))));
    }

    private void caseInfo(CommandSender s, String arg) {
        CaseBook.Case c = arg == null ? null : book.get(parse(arg, -1));
        if (c == null) { s.sendMessage(mm("<red>/ac case <номер>")); return; }
        s.sendMessage(mm("<dark_gray>▬▬▬ <gold><bold>Дело #" + c.id + "</bold></gold> <white>" + esc(c.name) + " <dark_gray>▬▬▬"));
        s.sendMessage(mm("<gray>Вызов: <white>" + DATE.format(Instant.ofEpochMilli(c.time)) + " <dark_gray>| <gray>уверенность " + colored(c.prob) + " <dark_gray>| <gray>" + esc(c.where)));
        s.sendMessage(mm("<gray>Статус: " + CaseBook.statusName(c.status) + (c.moderator != null ? " <dark_gray>| <gray>модератор <white>" + esc(c.moderator) : "")
                + (c.closed > 0 ? " <dark_gray>| <gray>закрыто " + DATE.format(Instant.ofEpochMilli(c.closed)) : "") + (c.test ? " <dark_gray>[тест]" : "")));
        for (String r : c.reasons) s.sendMessage(mm(" <red>▸</red> <white>" + esc(r)));
        int from = Math.max(0, c.log.size() - 10);
        for (int i = from; i < c.log.size(); i++) s.sendMessage(mm(" <dark_gray>" + esc(c.log.get(i))));
        s.sendMessage(mm(" ").append(mm("<aqua>[✉ Письмо со всеми сообщениями]").clickEvent(ClickEvent.runCommand("/ac letter #" + c.id))));
    }

    private void verdict(CommandSender s, String[] a) {
        if (a.length < 3 || !(a[2].equalsIgnoreCase("cheat") || a[2].equalsIgnoreCase("clean"))) { s.sendMessage(mm("<red>/ac verdict <ник> <cheat|clean>")); return; }
        boolean cheat = a[2].equalsIgnoreCase("cheat");
        PlayerData pd = pl.store().byName(a[1]);
        if (pd == null) { s.sendMessage(mm("<red>Игрок не найден")); return; }
        CaseBook.Case act = book.active(pd.uuid);
        if (act != null) { s.sendMessage(mm("<yellow>Проверка ещё идёт - закончи её: <white>/check stop " + esc(pd.name) + "</white> или <white>/check ban " + esc(pd.name))); return; }
        if (!learning()) { s.sendMessage(mm("<red>Обучение выключено в конфиге (anticheat.learning)")); return; }
        CaseBook.Case c = book.last(pd.uuid);
        ACPlayer d = players.get(pd.uuid);
        if (c != null && !c.learned && !c.test) {
            model.learn(c.xs, cheat, 1.0);
            c.learned = true;
            if (!CaseBook.CHEAT.equals(c.status) && !CaseBook.CLEAN.equals(c.status)) c.status = cheat ? CaseBook.CHEAT : CaseBook.CLEAN;
            book.touch();
            s.sendMessage(mm("<green>ИИ дообучен по делу #" + c.id + ": " + (cheat ? "<red>читер" : "<green>чист")));
        } else if (d != null) {
            model.learn(d.xs, cheat, 0.5);
            s.sendMessage(mm("<green>ИИ дообучен по текущим уликам " + esc(pd.name) + ": " + (cheat ? "<red>читер" : "<green>чист")));
        } else { s.sendMessage(mm("<red>Нет ни дела, ни улик - учить не на чем")); return; }
        if (d != null) {
            d.addLog(stamp() + " " + ICON + " Решение модератора " + s.getName() + ": " + (cheat ? "читер" : "чист") + " - ИИ дообучен");
            if (!cheat) afterClean(d);
        }
    }

    private void cancel(CommandSender s, String name) {
        if (name == null) { s.sendMessage(mm("<red>/ac cancel <ник> <gray>- отменить вызов ИИ, игрок чист")); return; }
        PlayerData pd = pl.store().byName(name);
        CaseBook.Case c = pd == null ? null : book.active(pd.uuid);
        if (c == null) { s.sendMessage(mm("<red>У игрока нет открытого вызова ИИ")); return; }
        Player t = Bukkit.getPlayer(c.uuid);
        if (t != null) {
            pl.admin().aiRelease(t);
            pl.msg(t, "<green>Проверка отменена - всё в порядке. Приятной игры!");
        }
        close(c, players.get(c.uuid), CaseBook.CANCELLED, s.getName());
    }

    private void exempt(CommandSender s, String[] a) {
        if (!isAdmin(s)) { s.sendMessage(mm("<red>Только для администрации")); return; }
        if (a.length < 3) { s.sendMessage(mm("<red>/ac exempt <ник> <30m|2h|1d|off> <gray>- ИИ не собирает улики на игрока")); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage(mm("<red>Игрок не в сети")); return; }
        ACPlayer d = data(t);
        if (a[2].equalsIgnoreCase("off")) { d.exemptUntil = 0; s.sendMessage(mm("<green>ИИ снова следит за " + esc(t.getName()))); return; }
        long ms = Util.parseTime(a[2]);
        if (ms <= 0) { s.sendMessage(mm("<red>Время: 30m, 2h, 1d")); return; }
        d.exemptUntil = System.currentTimeMillis() + ms;
        d.addLog(stamp() + " " + ICON + " " + s.getName() + " выключил ИИ для игрока на " + a[2]);
        s.sendMessage(mm("<yellow>ИИ не следит за " + esc(t.getName()) + " ещё " + a[2]));
    }

    private void model(CommandSender s, boolean reset) {
        if (reset) {
            if (!isAdmin(s)) { s.sendMessage(mm("<red>Только для администрации")); return; }
            model.reset();
            model.save();
            s.sendMessage(mm("<yellow>Модель ИИ сброшена к стартовым весам"));
            return;
        }
        s.sendMessage(mm("<dark_gray>▬▬▬ <light_purple><bold>Модель ИИ " + esc(aiName()) + "</bold></light_purple> <dark_gray>▬▬▬"));
        s.sendMessage(mm("<gray>Логистическая модель по " + ACPlayer.F + " уликам. Выучено решений: <white>" + (model.cheats + model.cleans)
                + "</white> (читеров <red>" + model.cheats + "</red>, чистых <green>" + model.cleans + "</green>). Смещение: <white>" + String.format(Locale.ROOT, "%.2f", model.bias)));
        s.sendMessage(mm("<gray>Порог наблюдения " + pct(watchT()) + ", вызова " + pct(callT()) + (disabled.isEmpty() ? "" : ". <red>Выключены: " + disabled)));
        for (Feature f : Feature.ALL) {
            int i = f.ordinal();
            double w = model.w[i];
            String trend = w > f.weight * 1.02 ? "<red>▲" : w < f.weight * 0.98 ? "<green>▼" : "<dark_gray>=";
            s.sendMessage(mm(" " + trend + " <white>" + String.format(Locale.ROOT, "%5.2f", w) + " <dark_gray>(старт " + f.weight + ") <gray>" + esc(f.title)
                    + (model.seen[i] > 0 ? " <dark_gray>| дел: " + model.seen[i] + ", читеров <red>" + model.cheat[i] + "</red>, чистых <green>" + model.clean[i] : "")
                    + (disabled.contains(f) ? " <red>[выкл]" : "")));
        }
    }

    private void test(CommandSender s, String name) {
        if (!isAdmin(s)) { s.sendMessage(mm("<red>Только для администрации")); return; }
        Player t = name == null ? null : Bukkit.getPlayerExact(name);
        if (t == null) { s.sendMessage(mm("<red>/ac test <ник> <gray>- тестовый вызов на проверку (игрок будет заморожен)")); return; }
        if (s instanceof Player p && p.equals(t)) { s.sendMessage(mm("<red>Проверь на другом игроке или твинке: вызванный игрок замораживается и не может писать команды.")); return; }
        if (pl.admin().onCheck(t.getUniqueId())) { s.sendMessage(mm("<red>Игрок уже на проверке")); return; }
        ACPlayer d = data(t);
        for (Feature f : new Feature[]{Feature.AURA_FOV, Feature.AIM_SNAP}) {
            d.score[f.ordinal()] += 3;
            d.events[f.ordinal()] += 6;
            d.total[f.ordinal()] += 6;
            d.addLog(stamp() + " [ТЕСТ] " + f.title + ": тестовая улика от " + s.getName());
        }
        evaluate(d);
        call(t, d, true);
        s.sendMessage(mm("<green>Тестовый вызов создан. <gray>Модераторам пришло письмо - нажми «Принять». ИИ на тестовых делах не учится."));
    }

    public List<String> tab(CommandSender s, String[] a) {
        if (!pl.admin().staff(s)) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("accept", "info", "log", "letter", "top", "cases", "case", "alerts", "verdict", "cancel", "menu", "testme", "help"));
        if (a.length == 1 && isAdmin(s)) out.addAll(List.of("exempt", "model", "test"));
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "accept" -> { for (CaseBook.Case c : book.open()) { out.add("#" + c.id); out.add(c.name); } }
                case "model" -> out.add("reset");
                case "case" -> { List<CaseBook.Case> all = book.all(); for (int i = Math.max(0, all.size() - 10); i < all.size(); i++) out.add(String.valueOf(all.get(i).id)); }
                case "info", "log", "letter", "verdict", "cancel", "exempt", "test" -> { for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName()); }
                default -> {}
            }
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("verdict")) out.addAll(List.of("cheat", "clean"));
        if (a.length == 3 && a[0].equalsIgnoreCase("exempt")) out.addAll(List.of("30m", "2h", "1d", "off"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    // ============================== меню ==============================
    public void menu(Player p) {
        Menus.Menu m = new Menus.Menu(6, "<gradient:#00f0ff:#0080ff><bold>" + ICON + " ИИ-античит</bold></gradient> <dark_gray>| " + esc(aiName()));
        long watched = players.values().stream().filter(d -> d.quitAt == 0 && d.status == 1).count();
        List<CaseBook.Case> open = book.open();
        m.set(4, Menus.icon(Material.BEACON, "<aqua><bold>" + esc(aiName()), "<gray>Под наблюдением: <white>" + watched, "<gray>Открытых дел: <white>" + open.size(),
                "<gray>Порог наблюдения: <white>" + pct(watchT()) + " <gray>| вызова: <white>" + pct(callT()),
                "<gray>Выучено решений модераторов: <white>" + (model.cheats + model.cleans),
                grim != null ? "<green>GrimAC подключён" : "<yellow>GrimAC не найден", tap.broken() ? "<red>Перехват пакетов выключен" : "<green>Перехват пакетов работает"), null);
        int[] caseSlots = {10, 11, 12, 13, 14, 15, 16};
        int ci = 0;
        for (CaseBook.Case c : open) {
            if (ci >= caseSlots.length) break;
            boolean waiting = CaseBook.WAITING.equals(c.status);
            ItemStack it = Menus.icon(Material.PLAYER_HEAD, (waiting ? "<yellow><bold>✉ Ждёт модератора: " : "<aqua><bold>Идёт проверка: ") + "<white>" + esc(c.name),
                    "<gray>Дело <white>#" + c.id + (c.test ? " <dark_gray>[тест]" : ""), "<gray>Уверенность ИИ: " + colored(c.prob),
                    "<gray>Вызван: <white>" + DATE.format(Instant.ofEpochMilli(c.time)), c.moderator != null ? "<gray>Проверяет: <white>" + esc(c.moderator) : "",
                    "", waiting ? "<green>ЛКМ - принять проверку" : "<yellow>ЛКМ - досье", "<yellow>ПКМ - письмо со всеми сообщениями");
            head(it, Bukkit.getPlayer(c.uuid));
            m.set(caseSlots[ci++], it, click -> {
                p.closeInventory();
                if (click.isRightClick()) letterBook(p, c);
                else if (waiting) accept(p, "#" + c.id);
                else dossier(p, c.name);
            });
        }
        if (open.isEmpty()) m.set(13, Menus.icon(Material.LIME_DYE, "<green><bold>Открытых вызовов нет"), null);
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int si = 0;
        for (ACPlayer d : onlineSorted(slots.length, 0.03)) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Вероятность читов: <bold>" + colored(d.prob));
            lore.add("<gray>Статус: " + (d.status == 2 ? "<red>вызван" : d.status == 1 ? "<gold>под наблюдением" : "<gray>чист"));
            for (String r : reasonLines(d, 3)) lore.add("<dark_gray>▸ <white>" + esc(r));
            lore.add("");
            lore.add("<yellow>ЛКМ <gray>- досье, <yellow>ПКМ <gray>- следить");
            ItemStack it = Menus.icon(Material.PLAYER_HEAD, "<white><bold>" + esc(d.name), lore.toArray(new String[0]));
            head(it, Bukkit.getPlayer(d.id));
            String nm = d.name;
            m.set(slots[si++], it, click -> {
                p.closeInventory();
                if (click.isRightClick()) p.performCommand("spec " + nm); else dossier(p, nm);
            });
        }
        if (si == 0) m.set(31, Menus.icon(Material.EMERALD, "<green><bold>Подозрительных игроков нет", "<gray>ИИ никого не подозревает"), null);
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Админ-панель"), c -> pl.admin().panel(p));
        boolean al = book.alerts.contains(p.getUniqueId());
        m.set(47, Menus.icon(al ? Material.LIME_DYE : Material.GRAY_DYE, al ? "<green><bold>Оповещения: ВКЛ" : "<gray><bold>Оповещения: ВЫКЛ", "<gray>Каждая улика ИИ в чат"), c -> { toggleAlerts(p); menu(p); });
        m.set(49, Menus.icon(Material.KNOWLEDGE_BOOK, "<light_purple><bold>Модель ИИ", "<gray>Веса улик и чему ИИ научился"), c -> { p.closeInventory(); model(p, false); });
        m.set(51, Menus.icon(Material.BOOK, "<gold><bold>Журнал дел", "<gray>Вызовы и решения модераторов"), c -> { p.closeInventory(); cases(p, 1); });
        m.set(53, Menus.icon(Material.BARRIER, "<red>Закрыть"), c -> p.closeInventory());
        m.fill();
        p.openInventory(m.getInventory());
    }

    private static void head(ItemStack it, Player owner) {
        if (owner == null || !(it.getItemMeta() instanceof SkullMeta sm)) return;
        sm.setOwningPlayer(owner);
        it.setItemMeta(sm);
    }

    // ============================== Discord ==============================
    private void webhook(String text) {
        String url = pl.getConfig().getString("anticheat.discord-webhook", "");
        if (url == null || url.isBlank() || !url.startsWith("https://")) return;
        String body = "{\"username\":\"" + json(aiName()) + "\",\"content\":\"" + json(text.length() > 1900 ? text.substring(0, 1900) : text)
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((r, t) -> {
                if ((t != null || r.statusCode() >= 300) && !webhookWarned) {
                    webhookWarned = true;
                    warn("Discord-вебхук не принял письмо: " + (t != null ? t : "HTTP " + r.statusCode()));
                }
            });
        } catch (Exception e) {
            if (!webhookWarned) { webhookWarned = true; warn("Discord-вебхук: " + e); }
        }
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append("\\t");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.toString();
    }
}
