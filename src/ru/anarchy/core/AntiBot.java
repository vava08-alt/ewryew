package ru.anarchy.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.persistence.PersistentDataType;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/** Защита от ботов: капча-карта при первом входе, лимиты с IP, режим бот-атаки. */
public final class AntiBot implements Listener {
    private final AnarchyCore pl;
    private final NamespacedKey MAP;
    private final Map<UUID, String> codes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fails = new ConcurrentHashMap<>();
    private final Set<UUID> passed = ConcurrentHashMap.newKeySet();
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private final Map<String, Deque<Long>> ipJoins = new ConcurrentHashMap<>();
    private volatile long attackUntil;

    public AntiBot(AnarchyCore pl) { this.pl = pl; MAP = new NamespacedKey(pl, "captcha"); }

    private boolean on(String k, boolean def) { return pl.getConfig().getBoolean("antibot." + k, def); }
    private int num(String k, int def) { return pl.getConfig().getInt("antibot." + k, def); }
    private boolean exempt(String ip) { return pl.sharedIp(ip); }
    public boolean underAttack() { return attackUntil > System.currentTimeMillis(); }
    public boolean pending(Player p) { return codes.containsKey(p.getUniqueId()); }

    // ---------- до входа ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !on("enabled", true)) return;
        String ip = e.getAddress().getHostAddress();
        boolean known = pl.store().byName(e.getName()) != null && pl.store().byName(e.getName()).passHash != null;
        long now = System.currentTimeMillis();
        if (!known) {
            synchronized (newJoins) {
                newJoins.addLast(now);
                while (!newJoins.isEmpty() && now - newJoins.peekFirst() > 10_000) newJoins.pollFirst();
                if (newJoins.size() > num("attack-threshold", 8) && !underAttack()) {
                    attackUntil = now + num("attack-seconds", 120) * 1000L;
                    pl.getLogger().warning("АНТИБОТ: обнаружена бот-атака, новые игроки временно не пускаются");
                    Util.sync(() -> { for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("anarchy.admin")) pl.msg(p, "<red>⚠ Антибот: бот-атака! Новые игроки не пускаются " + num("attack-seconds", 120) + " сек"); });
                }
            }
            if (underAttack()) { e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, mm("<red>Сервер отражает бот-атаку.\n<gray>Новые игроки могут зайти через пару минут.")); return; }
        }
        if (exempt(ip)) return;
        Deque<Long> q = ipJoins.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > 30_000) q.pollFirst();
            if (q.size() > num("joins-per-ip-30s", 5)) { e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, mm("<red>Слишком частые заходы. Подожди 30 секунд.")); return; }
        }
        long same = Bukkit.getOnlinePlayers().stream().filter(p -> p.getAddress() != null && ip.equals(p.getAddress().getAddress().getHostAddress())).count();
        if (same >= num("max-online-per-ip", 3)) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, mm("<red>С твоего IP уже играет " + same + " аккаунта - это максимум."));
    }

    // ---------- капча ----------
    // Новичок попадает в отдельный пустой мир "captcha" и бесконечно падает, пока не решит капчу.
    // Виды: digits / letters / math / count (карта в руке, ответ в чат), color / item / order (клик в меню).
    private static final String[] TYPES = {"digits", "letters", "math", "count", "color", "item", "order"};
    private final Map<UUID, String> kinds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> started = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> orderStep = new ConcurrentHashMap<>();
    private World capWorld;

    public boolean needsCaptcha(Player p) {
        if (!on("enabled", true) || !on("captcha", true) || passed.contains(p.getUniqueId())) return false;
        return pl.data(p).passHash == null;
    }

    public World captchaWorld() {
        if (capWorld != null) return capWorld;
        capWorld = Bukkit.getWorld("captcha");
        if (capWorld == null) capWorld = new WorldCreator("captcha").generator(new Worlds.Void()).environment(World.Environment.THE_END).generateStructures(false).createWorld();
        if (capWorld != null) {
            capWorld.setGameRule(GameRules.SPAWN_MOBS, false);
            capWorld.setGameRule(GameRules.ADVANCE_TIME, false);
            capWorld.setGameRule(GameRules.ADVANCE_WEATHER, false);
            capWorld.setGameRule(GameRules.FALL_DAMAGE, false);
            capWorld.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            capWorld.setPVP(false);
            capWorld.setSpawnLocation(0, 300, 0);
        }
        return capWorld;
    }

    public void startTask() {
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            World w = capWorld;
            long now = System.currentTimeMillis();
            int limit = num("captcha-seconds", 120);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!pending(p)) {
                    if (w != null && p.getWorld().equals(w) && !started.containsKey(p.getUniqueId())) sendToHub(p);
                    continue;
                }
                if (w != null && !p.getWorld().equals(w)) { p.teleport(fallPoint(p)); continue; }
                if (p.getLocation().getY() < 150) {
                    var v = p.getVelocity();
                    Location to = p.getLocation().clone(); to.setY(300);
                    p.teleport(to);
                    p.setVelocity(v);
                }
                p.setFallDistance(0);
                long left = limit - (now - started.getOrDefault(p.getUniqueId(), now)) / 1000;
                if (left <= 0) { p.kick(mm("<red>Время на капчу вышло. Зайди снова.")); continue; }
                String k = kinds.getOrDefault(p.getUniqueId(), "");
                boolean gui = k.equals("color") || k.equals("item") || k.equals("order");
                p.sendActionBar(mm((gui ? "<yellow>Нажми нужный предмет в меню" : "<yellow>Посмотри на карту в руке и напиши ответ в чат") + " <dark_gray>| <gray>осталось <white>" + left + " сек"));
                if (gui && !(p.getOpenInventory().getTopInventory().getHolder() instanceof CapHolder)) openGui(p);
                if (w != null) w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, -3, 0), 6, 2, 1, 2, 0.02);
            }
        }, 20, 10);
    }

    private Location fallPoint(Player p) {
        World w = captchaWorld();
        int i = Math.floorMod(p.getUniqueId().hashCode(), 64);
        return new Location(w, (i % 8) * 2000 + 0.5, 300, (i / 8) * 2000 + 0.5, p.getLocation().getYaw(), 70);
    }

    private void sendToHub(Player p) {
        Location h = pl.worlds().hubPoint();
        if (h != null) p.teleport(h);
        p.setFallDistance(0);
        p.setVelocity(new org.bukkit.util.Vector());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        removeMaps(p);
        if (!needsCaptcha(p)) {
            if (capWorld != null && p.getWorld().equals(capWorld)) Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) sendToHub(p); }, 2);
            return;
        }
        fails.remove(p.getUniqueId());
        started.put(p.getUniqueId(), System.currentTimeMillis());
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            pl.hold().set(p, "captcha", true);
            p.teleport(fallPoint(p));
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) { p.hidePlayer(pl, o); o.hidePlayer(pl, p); }
            newCaptcha(p, null);
            p.showTitle(Title.title(mm("<aqua><bold>Проверка на бота"), mm("<gray>Реши капчу - и попадёшь на сервер"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(500))));
        }, 5);
    }

    private void newCaptcha(Player p, String not) {
        List<String> allowed = new ArrayList<>(pl.getConfig().getStringList("antibot.captcha-types"));
        allowed.retainAll(Arrays.asList(TYPES));
        if (allowed.isEmpty()) allowed.addAll(Arrays.asList(TYPES));
        if (allowed.size() > 1 && not != null) allowed.remove(not);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String kind = allowed.get(r.nextInt(allowed.size()));
        kinds.put(p.getUniqueId(), kind);
        removeMaps(p);
        p.closeInventory();
        switch (kind) {
            case "digits" -> {
                String code = String.valueOf(r.nextInt(1000, 10000));
                codes.put(p.getUniqueId(), code);
                giveMap(p, new Captcha(code, Captcha.TEXT));
                pl.msg(p, "<yellow>Капча: <white>напиши в чат <aqua>4 цифры</aqua> с карты в руке.");
            }
            case "letters" -> {
                String abc = "ABCEHKMOPTX";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
                codes.put(p.getUniqueId(), sb.toString());
                giveMap(p, new Captcha(sb.toString(), Captcha.TEXT));
                pl.msg(p, "<yellow>Капча: <white>напиши в чат <aqua>4 буквы</aqua> с карты в руке <gray>(раскладка любая).");
            }
            case "math" -> {
                int a = r.nextInt(2, 10), b = r.nextInt(2, 10);
                int op = r.nextInt(3);
                String expr; int ans;
                if (op == 0) { expr = a + "+" + b; ans = a + b; }
                else if (op == 1) { int x = Math.max(a, b) + r.nextInt(1, 9), y = Math.min(a, b); expr = x + "-" + y; ans = x - y; }
                else { expr = a + "x" + b; ans = a * b; }
                codes.put(p.getUniqueId(), String.valueOf(ans));
                giveMap(p, new Captcha(expr + "=?", Captcha.TEXT));
                pl.msg(p, "<yellow>Капча: <white>реши пример с карты и напиши <aqua>ответ</aqua> в чат.");
            }
            case "count" -> {
                int n = r.nextInt(3, 9);
                codes.put(p.getUniqueId(), String.valueOf(n));
                giveMap(p, new Captcha(String.valueOf(n), Captcha.CIRCLES));
                pl.msg(p, "<yellow>Капча: <white>сколько <aqua>жёлтых кругов</aqua> на карте? Напиши число в чат.");
            }
            default -> {
                codes.put(p.getUniqueId(), "gui");
                orderStep.put(p.getUniqueId(), 1);
                Bukkit.getScheduler().runTask(pl, () -> openGui(p));
            }
        }
    }

    private void giveMap(Player p, Captcha c) {
        MapView view = Bukkit.createMap(p.getWorld());
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(c);
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta mm2 = (MapMeta) map.getItemMeta();
        mm2.setMapView(view);
        mm2.displayName(mm("<!italic><aqua><bold>Капча - ответ напиши в чат"));
        mm2.getPersistentDataContainer().set(MAP, PersistentDataType.BYTE, (byte) 1);
        map.setItemMeta(mm2);
        p.getInventory().setItem(0, map);
        p.getInventory().setHeldItemSlot(0);
    }

    private static final class CapHolder implements InventoryHolder {
        final String kind; final Map<Integer, String> slots = new HashMap<>(); String target; Inventory inv;
        CapHolder(String kind) { this.kind = kind; }
        @Override public Inventory getInventory() { return inv; }
    }
    private record Opt(Material m, String name) {}
    private static final Opt[] COLORS = {
            new Opt(Material.RED_WOOL, "КРАСНЫЙ"), new Opt(Material.LIME_WOOL, "ЗЕЛЁНЫЙ"), new Opt(Material.BLUE_WOOL, "СИНИЙ"),
            new Opt(Material.YELLOW_WOOL, "ЖЁЛТЫЙ"), new Opt(Material.WHITE_WOOL, "БЕЛЫЙ"), new Opt(Material.BLACK_WOOL, "ЧЁРНЫЙ"),
            new Opt(Material.ORANGE_WOOL, "ОРАНЖЕВЫЙ"), new Opt(Material.PURPLE_WOOL, "ФИОЛЕТОВЫЙ"), new Opt(Material.PINK_WOOL, "РОЗОВЫЙ")};
    private static final Opt[] ITEMS = {
            new Opt(Material.DIAMOND, "АЛМАЗ"), new Opt(Material.APPLE, "ЯБЛОКО"), new Opt(Material.BONE, "КОСТЬ"),
            new Opt(Material.FEATHER, "ПЕРО"), new Opt(Material.BREAD, "ХЛЕБ"), new Opt(Material.CARROT, "МОРКОВЬ"),
            new Opt(Material.COMPASS, "КОМПАС"), new Opt(Material.CLOCK, "ЧАСЫ"), new Opt(Material.BOOK, "КНИГА"),
            new Opt(Material.TORCH, "ФАКЕЛ"), new Opt(Material.BUCKET, "ВЕДРО"), new Opt(Material.EGG, "ЯЙЦО"),
            new Opt(Material.COOKIE, "ПЕЧЕНЬЕ"), new Opt(Material.SHEARS, "НОЖНИЦЫ"), new Opt(Material.CAKE, "ТОРТ")};

    private void openGui(Player p) {
        String kind = kinds.get(p.getUniqueId());
        if (kind == null) return;
        CapHolder h = new CapHolder(kind);
        String title;
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 27; i++) free.add(i);
        Collections.shuffle(free);
        Inventory inv;
        switch (kind) {
            case "color" -> {
                List<Opt> l = new ArrayList<>(Arrays.asList(COLORS)); Collections.shuffle(l);
                Opt t = l.get(0); h.target = t.m().name();
                title = "Нажми на " + t.name() + " блок";
                inv = Bukkit.createInventory(h, 27, mm("<dark_gray>" + title));
                for (int i = 0; i < 6; i++) { Opt o = l.get(i); int s = free.get(i); inv.setItem(s, Menus.icon(o.m(), "<white>?")); h.slots.put(s, o.m().name()); }
            }
            case "item" -> {
                List<Opt> l = new ArrayList<>(Arrays.asList(ITEMS)); Collections.shuffle(l);
                Opt t = l.get(0); h.target = t.m().name();
                title = "Нажми на предмет: " + t.name();
                inv = Bukkit.createInventory(h, 27, mm("<dark_gray>" + title));
                for (int i = 0; i < 7; i++) { Opt o = l.get(i); int s = free.get(i); inv.setItem(s, Menus.icon(o.m(), "<white>?")); h.slots.put(s, o.m().name()); }
            }
            default -> {
                int step = orderStep.getOrDefault(p.getUniqueId(), 1);
                title = "Нажимай по порядку: 1, 2, 3, 4";
                inv = Bukkit.createInventory(h, 27, mm("<dark_gray>" + title));
                for (int n = 1; n <= 4; n++) {
                    int s = free.get(n - 1);
                    ItemStack it = n < step ? Menus.icon(Material.LIME_STAINED_GLASS_PANE, "<green>✔") : Menus.icon(Material.GOLD_NUGGET, "<yellow>" + n);
                    if (n >= step) it.setAmount(n);
                    inv.setItem(s, it); h.slots.put(s, String.valueOf(n));
                }
                h.target = String.valueOf(step);
            }
        }
        h.inv = inv;
        p.openInventory(inv);
        pl.msg(p, "<yellow>Капча: <white>" + title);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCapClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof CapHolder h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        String v = h.slots.get(e.getRawSlot());
        if (v == null) return;
        if (!v.equals(h.target)) { fail(p, h.kind); return; }
        if (h.kind.equals("order")) {
            int step = orderStep.merge(p.getUniqueId(), 1, Integer::sum);
            if (step <= 4) {
                h.target = String.valueOf(step);
                e.getInventory().setItem(e.getRawSlot(), Menus.icon(Material.LIME_STAINED_GLASS_PANE, "<green>✔"));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f + step * 0.1f);
                return;
            }
        }
        success(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCapDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof CapHolder) e.setCancelled(true); }

    private void fail(Player p, String kind) {
        int f = fails.merge(p.getUniqueId(), 1, Integer::sum);
        if (f >= num("captcha-attempts", 3)) { Util.sync(() -> p.kick(mm("<red>Капча не пройдена. Попробуй зайти снова."))); return; }
        Util.sync(() -> {
            pl.msg(p, "<red>Неверно! <gray>Вот другая капча. Осталось попыток: " + (num("captcha-attempts", 3) - f));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            newCaptcha(p, kind);
        });
    }

    private void success(Player p) {
        UUID u = p.getUniqueId();
        codes.remove(u); kinds.remove(u); orderStep.remove(u); started.remove(u);
        passed.add(u);
        Util.sync(() -> {
            p.closeInventory();
            removeMaps(p);
            sendToHub(p);
            pl.hold().set(p, "captcha", false);
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p && !pending(o)) { p.showPlayer(pl, o); o.showPlayer(pl, p); }
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            pl.msg(p, "<green>✔ Капча пройдена! <yellow>Теперь зарегистрируйся: <white>/reg <пароль> <пароль>");
            p.showTitle(Title.title(mm("<green><bold>✔ Добро пожаловать!"), mm("<gray>Регистрация: <aqua>/reg <пароль> <пароль>")));
            int timeout = pl.getConfig().getInt("auth.timeout-seconds", 60);
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && !pl.data(p).authed) p.kick(mm("<red>Время на регистрацию истекло")); }, timeout * 20L);
        });
    }

    private void removeMaps(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == Material.FILLED_MAP && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(MAP)) p.getInventory().setItem(i, null);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        codes.remove(u); kinds.remove(u); orderStep.remove(u); started.remove(u);
        removeMaps(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) { if (pending(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) { if (e.getEntity() instanceof Player p && (pending(p) || capWorld != null && p.getWorld().equals(capWorld))) e.setCancelled(true); }

    private static String normalize(String s) {
        String from = "АВСЕНКМОРТХ", to = "ABCEHKMOPTX";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).replace(" ", "").toCharArray()) { int i = from.indexOf(c); sb.append(i >= 0 ? to.charAt(i) : c); }
        return sb.toString();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String code = codes.get(p.getUniqueId());
        if (code == null) return;
        e.setCancelled(true);
        if (code.equals("gui")) { pl.msg(p, "<yellow>Эта капча - в меню. Нажми нужный предмет."); return; }
        String msg = normalize(PlainTextComponentSerializer.plainText().serialize(e.message()).trim());
        if (msg.equals(code)) success(p);
        else fail(p, kinds.get(p.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        boolean reg = cmd.equals("reg") || cmd.equals("register");
        if (pending(p)) {
            e.setCancelled(true);
            pl.msg(p, "<red>Сначала пройди капчу.");
            return;
        }
        if (reg && !p.hasPermission("anarchy.admin") && p.getAddress() != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            if (exempt(ip)) return;
            long accounts = pl.store().all().stream().filter(d -> d.passHash != null && ip.equals(d.lastIp)).count();
            if (accounts >= num("max-accounts-per-ip", 3)) {
                e.setCancelled(true);
                pl.msg(p, "<red>С твоего IP уже зарегистрировано " + accounts + " аккаунта - это максимум.");
            }
        }
    }

    /** Карта: текст крупным шрифтом или круги для подсчёта, с помехами. */
    private static final class Captcha extends MapRenderer {
        static final int TEXT = 0, CIRCLES = 1;
        private final String code; private final int mode; private boolean drawn;
        Captcha(String code, int mode) { super(false); this.code = code; this.mode = mode; }
        @Override public void render(MapView view, MapCanvas c, Player player) {
            if (drawn) return;
            drawn = true;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int theme = r.nextInt(3);
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                Color bg = switch (theme) {
                    case 0 -> new Color(20 + r.nextInt(40), 30 + r.nextInt(40), 50 + r.nextInt(50));
                    case 1 -> new Color(40 + r.nextInt(30), 20 + r.nextInt(30), 50 + r.nextInt(40));
                    default -> new Color(15 + r.nextInt(30), 45 + r.nextInt(35), 35 + r.nextInt(35));
                };
                c.setPixelColor(x, y, bg);
            }
            for (int i = 0; i < 10; i++) {
                int x1 = r.nextInt(128), y1 = r.nextInt(128), x2 = r.nextInt(128), y2 = r.nextInt(128);
                Color col = new Color(r.nextInt(60, 160), r.nextInt(60, 160), r.nextInt(60, 160));
                for (int t = 0; t <= 100; t++) c.setPixelColor(x1 + (x2 - x1) * t / 100, y1 + (y2 - y1) * t / 100, col);
            }
            if (mode == CIRCLES) {
                int n = Integer.parseInt(code);
                List<int[]> placed = new ArrayList<>();
                int guard = 0;
                while (placed.size() < n && guard++ < 2000) {
                    int rad = r.nextInt(6, 11), cx = r.nextInt(rad + 2, 126 - rad), cy = r.nextInt(rad + 2, 126 - rad);
                    boolean ok = true;
                    for (int[] q : placed) if (Math.hypot(q[0] - cx, q[1] - cy) < q[2] + rad + 4) { ok = false; break; }
                    if (!ok) continue;
                    placed.add(new int[]{cx, cy, rad});
                    Color col = new Color(255, 220 + r.nextInt(35), r.nextInt(60));
                    for (int x = -rad; x <= rad; x++) for (int y = -rad; y <= rad; y++) if (x * x + y * y <= rad * rad) c.setPixelColor(cx + x, cy + y, col);
                }
                for (int i = 0; i < r.nextInt(2, 5); i++) {
                    int s = r.nextInt(6, 12), x0 = r.nextInt(2, 126 - s), y0 = r.nextInt(2, 126 - s);
                    Color col = new Color(60 + r.nextInt(40), 160 + r.nextInt(60), 255);
                    for (int x = 0; x < s; x++) for (int y = 0; y < s; y++) c.setPixelColor(x0 + x, y0 + y, col);
                }
            } else {
                int len = code.length();
                int scale = len <= 4 ? 4 : 3;
                int total = 0;
                for (char ch : code.toCharArray()) { MapFont.CharacterSprite sp = MinecraftFont.Font.getChar(ch); total += (sp == null ? 4 : sp.getWidth() + 1) * scale + 4; }
                int x0 = Math.max(2, (128 - total) / 2);
                for (int i = 0; i < len; i++) {
                    char ch = code.charAt(i);
                    MapFont.CharacterSprite sp = MinecraftFont.Font.getChar(ch);
                    if (sp == null) { x0 += 4 * scale; continue; }
                    int ox = x0 + r.nextInt(-1, 2), oy = 44 + r.nextInt(-10, 11);
                    Color col = switch (r.nextInt(3)) { case 0 -> new Color(255, 230, 90); case 1 -> new Color(120, 255, 200); default -> new Color(255, 150, 220); };
                    for (int sx = 0; sx < sp.getWidth(); sx++) for (int sy = 0; sy < sp.getHeight(); sy++)
                        if (sp.get(sy, sx)) for (int dx = 0; dx < scale; dx++) for (int dy = 0; dy < scale; dy++) {
                            int px = ox + sx * scale + dx, py = oy + sy * scale + dy;
                            if (px >= 0 && px < 128 && py >= 0 && py < 128) c.setPixelColor(px, py, col);
                        }
                    x0 += (sp.getWidth() + 1) * scale + 4;
                }
            }
            for (int i = 0; i < 220; i++) c.setPixelColor(r.nextInt(128), r.nextInt(128), new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
        }
    }
}
