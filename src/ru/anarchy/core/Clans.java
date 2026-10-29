package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static ru.anarchy.core.AnarchyCore.mm;

/** Кланы: общий приват, банк, клан-чат, дом, без урона по своим, войны, топ. */
public final class Clans implements Listener {
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9]{2,5}$");

    public static final class Clan {
        public String tag, name;
        public UUID owner;
        public final Set<UUID> officers = new HashSet<>();
        public final Set<UUID> members = new LinkedHashSet<>();   // включая владельца и офицеров
        public long bank;
        public int kills, deaths, kothWins, warsWon;
        public String home;
        public long created;
        public String color = "#00E5FF";
        // война
        public String warWith; public long warUntil; public int warKills;
        // уровни / сундук
        public long xp, depositNet, depositMax;
        public List<String> chestData = new ArrayList<>();
        Inventory chest;
        public String id() { return tag.toLowerCase(Locale.ROOT); }
        public boolean isOfficer(UUID u) { return owner.equals(u) || officers.contains(u); }
        public String display() { return "<" + color + ">[" + tag + "]</" + color + ">"; }
    }

    private final AnarchyCore pl;
    private final File file;
    private final Map<String, Clan> clans = new LinkedHashMap<>();
    private final Map<UUID, String> byMember = new HashMap<>();
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<String, String> warRequests = new HashMap<>();
    private final Set<UUID> buffsOff = new HashSet<>();
    private final Map<String, Long> killXpCd = new HashMap<>();
    private Territories territories;

    /** Держатель инвентаря кланового сундука. */
    public static final class ClanChest implements InventoryHolder {
        final String id; Inventory inv;
        ClanChest(String id) { this.id = id; }
        @Override public Inventory getInventory() { return inv; }
    }

    public Clans(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "clans.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::warTick, 200, 200);
        Bukkit.getScheduler().runTaskTimer(pl, this::buffTick, 100, 100);
        Bukkit.getScheduler().runTaskTimer(pl, this::onlineXpTick, 12000, 12000);
        territories = new Territories(pl, this);
        Bukkit.getPluginManager().registerEvents(territories, pl);
    }

    public Clan of(UUID u) { String id = byMember.get(u); return id == null ? null : clans.get(id); }
    public Clan get(String tag) { return tag == null ? null : clans.get(tag.toLowerCase(Locale.ROOT)); }
    public Collection<Clan> all() { return clans.values(); }
    public boolean same(UUID a, UUID b) { String x = byMember.get(a); return x != null && x.equals(byMember.get(b)); }
    public String tagOf(UUID u) { Clan c = of(u); return c == null ? "" : c.display() + " "; }

    // ---------- сохранение ----------
    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String u : y.getStringList("_buffs-off")) try { buffsOff.add(UUID.fromString(u)); } catch (Exception ignored) {}
        for (String k : y.getKeys(false)) {
            if (k.startsWith("_")) continue;
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Clan c = new Clan();
                c.tag = s.getString("tag", k); c.name = s.getString("name", c.tag);
                c.owner = UUID.fromString(s.getString("owner"));
                for (String u : s.getStringList("officers")) c.officers.add(UUID.fromString(u));
                for (String u : s.getStringList("members")) c.members.add(UUID.fromString(u));
                c.members.add(c.owner);
                c.bank = s.getLong("bank"); c.kills = s.getInt("kills"); c.deaths = s.getInt("deaths");
                c.kothWins = s.getInt("koth"); c.warsWon = s.getInt("wars"); c.home = s.getString("home");
                c.created = s.getLong("created"); c.color = s.getString("color", c.color);
                c.warWith = s.getString("war-with"); c.warUntil = s.getLong("war-until"); c.warKills = s.getInt("war-kills");
                c.xp = s.getLong("xp"); c.depositNet = s.getLong("deposit-net"); c.depositMax = s.getLong("deposit-max");
                c.chestData = new ArrayList<>(s.getStringList("chest"));
                clans.put(c.id(), c);
                for (UUID u : c.members) byMember.put(u, c.id());
            } catch (Exception e) { pl.getLogger().warning("Клан " + k + ": " + e); }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Clan c : clans.values()) {
            String k = c.id();
            y.set(k + ".tag", c.tag); y.set(k + ".name", c.name); y.set(k + ".owner", c.owner.toString());
            y.set(k + ".officers", c.officers.stream().map(UUID::toString).toList());
            y.set(k + ".members", c.members.stream().map(UUID::toString).toList());
            y.set(k + ".bank", c.bank); y.set(k + ".kills", c.kills); y.set(k + ".deaths", c.deaths);
            y.set(k + ".koth", c.kothWins); y.set(k + ".wars", c.warsWon); y.set(k + ".home", c.home);
            y.set(k + ".created", c.created); y.set(k + ".color", c.color);
            y.set(k + ".war-with", c.warWith); y.set(k + ".war-until", c.warUntil); y.set(k + ".war-kills", c.warKills);
            y.set(k + ".xp", c.xp); y.set(k + ".deposit-net", c.depositNet); y.set(k + ".deposit-max", c.depositMax);
            syncChest(c);
            y.set(k + ".chest", c.chestData);
        }
        y.set("_buffs-off", buffsOff.stream().map(UUID::toString).toList());
        try { y.save(file); } catch (Exception e) { pl.getLogger().severe("clans.yml: " + e); }
    }

    private String name(UUID u) { PlayerData d = pl.store().get(u); return d == null ? "?" : d.name; }
    public int maxMembers(Clan c) { return lvl(level(c)).getInt("members", pl.getConfig().getInt("clans.max-members", 15)); }

    // ---------- уровни ----------
    private ConfigurationSection levels() { return pl.getConfig().getConfigurationSection("clans.levels"); }
    public int maxLevel() { ConfigurationSection l = levels(); int m = 1; if (l != null) for (String k : l.getKeys(false)) try { m = Math.max(m, Integer.parseInt(k)); } catch (Exception ignored) {} return m; }
    private ConfigurationSection lvl(int n) {
        ConfigurationSection l = levels();
        ConfigurationSection s = l == null ? null : l.getConfigurationSection(String.valueOf(n));
        return s != null ? s : new YamlConfiguration();
    }
    public long xpFor(int n) { return n <= 1 ? 0 : lvl(n).getLong("xp", Long.MAX_VALUE); }
    public int level(Clan c) { int n = 1, max = maxLevel(); while (n < max && c.xp >= xpFor(n + 1)) n++; return n; }
    public int chestRows(Clan c) { return Math.max(0, Math.min(6, lvl(level(c)).getInt("chest-rows", 0))); }
    public List<String> buffs(Clan c) { return lvl(level(c)).getStringList("buffs"); }
    private long xpCfg(String k, long def) { return pl.getConfig().getLong("clans.xp." + k, def); }

    public void addXp(Clan c, long amount, String why) {
        if (c == null || amount <= 0) return;
        int before = level(c);
        c.xp += amount;
        int after = level(c);
        if (after > before) {
            broadcastClan(c, "<gold><bold>▲ Уровень клана " + after + "!</bold> <gray>" + perksText(c));
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>Клан " + c.display() + " <gray>достиг <gold>" + after + " уровня<gray>!"));
            for (UUID u : c.members) { Player x = Bukkit.getPlayer(u); if (x != null) x.playSound(x.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f); }
        }
    }

    private String perksText(Clan c) {
        int rows = chestRows(c);
        List<String> b = buffs(c);
        return "Мест: " + maxMembers(c) + ", сундук: " + (rows == 0 ? "нет" : rows + " ряд.") + ", бафы: " + (b.isEmpty() ? "нет" : String.join(", ", b.stream().map(this::buffName).toList()));
    }

    private String buffName(String spec) {
        String[] p = spec.split(":");
        String n = switch (p[0].toUpperCase(Locale.ROOT)) {
            case "HASTE" -> "Спешка"; case "SPEED" -> "Скорость"; case "NIGHT_VISION" -> "Ночное зрение"; case "RESISTANCE" -> "Сопротивление";
            case "STRENGTH" -> "Сила"; case "REGENERATION" -> "Регенерация"; case "FIRE_RESISTANCE" -> "Огнестойкость"; case "LUCK" -> "Удача";
            case "WATER_BREATHING" -> "Подводное дыхание"; case "JUMP_BOOST" -> "Прыгучесть"; case "HERO_OF_THE_VILLAGE" -> "Герой деревни";
            default -> p[0];
        };
        int amp = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        return n + " " + (amp + 1);
    }

    private String bar(Clan c) {
        int l = level(c);
        if (l >= maxLevel()) return "<gold>МАКС. уровень <gray>(" + c.xp + " опыта)";
        long a = xpFor(l), b = xpFor(l + 1);
        int fill = (int) Math.max(0, Math.min(20, (c.xp - a) * 20 / Math.max(1, b - a)));
        return "<green>" + "|".repeat(fill) + "<dark_gray>" + "|".repeat(20 - fill) + " <gray>" + c.xp + "/" + b;
    }

    private void buffTick() {
        for (Clan c : clans.values()) {
            List<String> b = buffs(c);
            if (b.isEmpty()) continue;
            for (UUID u : c.members) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || buffsOff.contains(u) || pl.worlds().isHub(p.getWorld()) || p.getWorld().getName().equals("captcha") || pl.moreEvents().inTournament(p)) continue;
                for (String spec : b) {
                    try {
                        String[] x = spec.split(":");
                        PotionEffectType t = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(x[0].trim().toLowerCase(Locale.ROOT)));
                        if (t == null) continue;
                        int amp = x.length > 1 ? Integer.parseInt(x[1].trim()) : 0;
                        PotionEffect cur = p.getPotionEffect(t);
                        if (cur != null && (cur.getAmplifier() > amp || (cur.getAmplifier() == amp && cur.getDuration() > 220))) continue;
                        p.addPotionEffect(new PotionEffect(t, 300, amp, true, false, true));
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void onlineXpTick() {
        long per = xpCfg("online-member-10min", 2);
        for (Clan c : clans.values()) {
            int n = 0;
            for (UUID u : c.members) { Player p = Bukkit.getPlayer(u); if (p != null && !pl.worlds().isHub(p.getWorld()) && !p.getWorld().getName().equals("captcha")) n++; }
            if (n > 0) addXp(c, n * per, "онлайн");
        }
        save();
    }

    // ---------- сундук ----------
    public Inventory chest(Clan c) {
        int rows = chestRows(c);
        if (rows <= 0) return null;
        if (c.chest == null || c.chest.getSize() < rows * 9) {
            ItemStack[] old = c.chest != null ? c.chest.getContents() : null;
            if (c.chest != null) for (var v : new ArrayList<>(c.chest.getViewers())) v.closeInventory();
            ClanChest h = new ClanChest(c.id());
            Inventory inv = Bukkit.createInventory(h, rows * 9, mm("<gradient:#00f0ff:#0080ff><bold>⚑ Сундук " + c.tag + "</bold></gradient>"));
            h.inv = inv;
            if (old != null) { for (int i = 0; i < old.length && i < inv.getSize(); i++) inv.setItem(i, old[i]); }
            else for (String e : c.chestData) {
                int i = e.indexOf(':');
                try { int slot = Integer.parseInt(e.substring(0, i)); ItemStack it = Util.itemFromString(e.substring(i + 1)); if (it != null && slot < inv.getSize()) inv.setItem(slot, it); } catch (Exception ignored) {}
            }
            c.chest = inv;
        }
        return c.chest;
    }

    private void syncChest(Clan c) {
        if (c.chest == null) return;
        List<String> l = new ArrayList<>();
        ItemStack[] it = c.chest.getContents();
        for (int i = 0; i < it.length; i++) if (it[i] != null && !it[i].getType().isAir()) l.add(i + ":" + Util.itemToString(it[i]));
        c.chestData = l;
    }

    private boolean chestEmpty(Clan c) {
        if (c.chest != null) { for (ItemStack i : c.chest.getContents()) if (i != null && !i.getType().isAir()) return false; return true; }
        return c.chestData.isEmpty();
    }

    private void openChest(Player p, Clan c) {
        if (c == null) { pl.msg(p, "<red>Ты не в клане"); return; }
        if (pl.getConfig().getBoolean("clans.chest-officers-only", false) && !c.isOfficer(p.getUniqueId())) { pl.msg(p, "<red>Сундук доступен только главе и офицерам"); return; }
        Inventory inv = chest(c);
        if (inv == null) { pl.msg(p, "<red>Клановый сундук открывается с " + firstLevel("chest-rows") + " уровня клана <gray>(у вас " + level(c) + ")"); return; }
        p.openInventory(inv);
    }

    private int firstLevel(String key) { for (int i = 1; i <= maxLevel(); i++) if (lvl(i).getInt(key, 0) > 0) return i; return maxLevel(); }

    @EventHandler
    public void onChestClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof ClanChest h) { Clan c = clans.get(h.id); if (c != null && e.getInventory().getViewers().size() <= 1) save(); }
    }

    public Territories territories() { return territories; }
    public void broadcast(Clan c, String msg) { broadcastClan(c, msg); }

    private void broadcastClan(Clan c, String msg) {
        for (UUID u : c.members) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage(mm("<dark_gray>[</dark_gray>" + c.display() + "<dark_gray>]</dark_gray> " + msg)); }
    }

    // ---------- команда ----------
    public void command(Player p, String[] a) {
        PlayerData d = pl.data(p);
        Clan my = of(p.getUniqueId());
        if (a.length == 0) { if (my != null) menu(p, my); else help(p); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (my != null) { pl.msg(p, "<red>Ты уже в клане " + my.display()); return; }
                if (a.length < 2 || !TAG.matcher(a[1]).matches()) { pl.msg(p, "<red>/clan create <тег 2-5 символов A-Z 0-9> [название]"); return; }
                if (get(a[1]) != null) { pl.msg(p, "<red>Клан с таким тегом уже есть"); return; }
                long cost = pl.getConfig().getLong("clans.create-cost", 5000);
                if (d.balance < cost) { pl.msg(p, "<red>Создание клана стоит " + cost + " " + pl.cur()); return; }
                d.balance -= cost;
                Clan c = new Clan();
                c.tag = a[1].toUpperCase(Locale.ROOT); c.name = a.length > 2 ? Util.join(a, 2) : c.tag;
                if (c.name.length() > 24) c.name = c.name.substring(0, 24);
                c.owner = p.getUniqueId(); c.members.add(p.getUniqueId()); c.created = System.currentTimeMillis();
                clans.put(c.id(), c); byMember.put(p.getUniqueId(), c.id());
                save();
                Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<white>" + AnarchyCore.esc(p.getName()) + " <gray>основал клан " + c.display() + " <white>" + AnarchyCore.esc(c.name)));
                pl.applyTeam(p);
            }
            case "invite" -> {
                if (my == null || !my.isOfficer(p.getUniqueId())) { pl.msg(p, "<red>Приглашать могут глава и офицеры клана"); return; }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) { pl.msg(p, "<red>/clan invite <ник> (игрок в сети)"); return; }
                if (of(t.getUniqueId()) != null) { pl.msg(p, "<red>Игрок уже в клане"); return; }
                if (my.members.size() >= maxMembers(my)) { pl.msg(p, "<red>В клане максимум " + maxMembers(my) + " участников"); return; }
                invites.put(t.getUniqueId(), my.id());
                pl.msg(p, "<green>Приглашение отправлено " + AnarchyCore.esc(t.getName()));
                t.sendMessage(mm(pl.getConfig().getString("prefix", "") + "<gray>Тебя пригласили в клан " + my.display() + " <white>" + AnarchyCore.esc(my.name) + " ")
                        .append(mm("<green><bold>[ПРИНЯТЬ]</bold>").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/clan accept")))
                        .append(mm(" <red>[ОТКАЗ]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/clan deny"))));
            }
            case "accept" -> {
                Clan c = clans.get(invites.remove(p.getUniqueId()));
                if (c == null) { pl.msg(p, "<red>Нет приглашений"); return; }
                if (my != null) { pl.msg(p, "<red>Сначала выйди из своего клана"); return; }
                if (c.members.size() >= maxMembers(c)) { pl.msg(p, "<red>Клан заполнен"); return; }
                c.members.add(p.getUniqueId()); byMember.put(p.getUniqueId(), c.id());
                broadcastClan(c, "<green>" + AnarchyCore.esc(p.getName()) + " вступил в клан!");
                pl.applyTeam(p);
                save();
            }
            case "deny" -> { invites.remove(p.getUniqueId()); pl.msg(p, "<gray>Приглашение отклонено"); }
            case "leave" -> {
                if (my == null) { pl.msg(p, "<red>Ты не в клане"); return; }
                if (my.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>Глава не может выйти. Передай клан (/clan owner <ник>) или распусти (/clan disband)"); return; }
                removeMember(my, p.getUniqueId());
                broadcastClan(my, "<yellow>" + AnarchyCore.esc(p.getName()) + " покинул клан");
                pl.msg(p, "<yellow>Ты вышел из клана");
                pl.applyTeam(p);
            }
            case "kick" -> {
                if (my == null || !my.isOfficer(p.getUniqueId()) || a.length < 2) { pl.msg(p, "<red>/clan kick <ник> - для главы и офицеров"); return; }
                PlayerData t = pl.store().byName(a[1]);
                if (t == null || !my.members.contains(t.uuid) || t.uuid.equals(my.owner)) { pl.msg(p, "<red>Игрок не в твоём клане"); return; }
                if (my.officers.contains(t.uuid) && !my.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>Офицера может выгнать только глава"); return; }
                removeMember(my, t.uuid);
                broadcastClan(my, "<red>" + AnarchyCore.esc(t.name) + " исключён из клана");
                Player tp = Bukkit.getPlayer(t.uuid); if (tp != null) { pl.msg(tp, "<red>Тебя исключили из клана"); pl.applyTeam(tp); }
            }
            case "promote", "demote" -> {
                if (my == null || !my.owner.equals(p.getUniqueId()) || a.length < 2) { pl.msg(p, "<red>/clan " + sub + " <ник> - только глава"); return; }
                PlayerData t = pl.store().byName(a[1]);
                if (t == null || !my.members.contains(t.uuid)) { pl.msg(p, "<red>Игрок не в клане"); return; }
                if (sub.equals("promote")) my.officers.add(t.uuid); else my.officers.remove(t.uuid);
                broadcastClan(my, "<aqua>" + AnarchyCore.esc(t.name) + (sub.equals("promote") ? " теперь офицер" : " больше не офицер"));
                save();
            }
            case "owner" -> {
                if (my == null || !my.owner.equals(p.getUniqueId()) || a.length < 2) { pl.msg(p, "<red>/clan owner <ник> - передать клан"); return; }
                PlayerData t = pl.store().byName(a[1]);
                if (t == null || !my.members.contains(t.uuid)) { pl.msg(p, "<red>Игрок не в клане"); return; }
                my.officers.add(my.owner); my.owner = t.uuid; my.officers.remove(t.uuid);
                broadcastClan(my, "<gold>Новый глава клана: " + AnarchyCore.esc(t.name));
                save();
            }
            case "disband" -> {
                if (my == null || !my.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>Распустить клан может только глава"); return; }
                if (!chestEmpty(my)) { pl.msg(p, "<red>Сначала опустоши клановый сундук (/clan chest)"); return; }
                if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) { pl.msg(p, "<red>Точно? Банк клана (" + my.bank + " " + pl.cur() + ") вернётся тебе. <white>/clan disband confirm"); return; }
                d.balance += my.bank;
                broadcastClan(my, "<red>Клан распущен");
                for (UUID u : new ArrayList<>(my.members)) { byMember.remove(u); Player x = Bukkit.getPlayer(u); if (x != null) pl.applyTeam(x); }
                clans.remove(my.id());
                territories.release(my.id());
                save();
            }
            case "deposit", "dep" -> {
                if (my == null) { pl.msg(p, "<red>Ты не в клане"); return; }
                long v = num(a, 1);
                if (v <= 0 || d.balance < v) { pl.msg(p, "<red>/clan deposit <сумма> (у тебя " + d.balance + ")"); return; }
                d.balance -= v; my.bank += v;
                my.depositNet += v;
                if (my.depositNet > my.depositMax) {
                    long per = Math.max(1, xpCfg("deposit-coins-per-xp", 100));
                    long gained = my.depositNet / per - my.depositMax / per;
                    my.depositMax = my.depositNet;
                    addXp(my, gained, "вклад");
                }
                broadcastClan(my, "<gold>" + AnarchyCore.esc(p.getName()) + " положил в банк " + v + " " + pl.cur() + " <gray>(банк: " + my.bank + ")");
                save();
            }
            case "withdraw", "wd" -> {
                if (my == null || !my.isOfficer(p.getUniqueId())) { pl.msg(p, "<red>Снимать деньги могут глава и офицеры"); return; }
                long v = num(a, 1);
                if (v <= 0 || my.bank < v) { pl.msg(p, "<red>/clan withdraw <сумма> (в банке " + my.bank + ")"); return; }
                my.bank -= v; d.balance += v; my.depositNet -= v;
                broadcastClan(my, "<yellow>" + AnarchyCore.esc(p.getName()) + " снял из банка " + v + " " + pl.cur());
                save();
            }
            case "sethome" -> {
                if (my == null || !my.isOfficer(p.getUniqueId())) { pl.msg(p, "<red>Только глава и офицеры"); return; }
                if (pl.worlds().isHub(p.getWorld())) { pl.msg(p, "<red>Нельзя на спавне"); return; }
                my.home = PlayerData.locToString(p.getLocation());
                broadcastClan(my, "<green>Дом клана установлен");
                save();
            }
            case "home" -> {
                if (my == null || my.home == null) { pl.msg(p, "<red>У клана нет дома (/clan sethome)"); return; }
                Location l = PlayerData.locFromString(my.home);
                if (l == null) return;
                pl.teleports().start(p, "в дом клана", () -> pl.teleports().teleport(p, l));
            }
            case "chat", "c" -> {
                if (my == null || a.length < 2) { pl.msg(p, "<red>/clan chat <сообщение> или /cc <сообщение>"); return; }
                chat(p, Util.join(a, 1));
            }
            case "color" -> {
                if (my == null || !my.owner.equals(p.getUniqueId()) || a.length < 2 || !a[1].matches("^#[0-9A-Fa-f]{6}$")) { pl.msg(p, "<red>/clan color #RRGGBB - только глава"); return; }
                my.color = a[1].toUpperCase(Locale.ROOT); save();
                for (UUID u : my.members) { Player x = Bukkit.getPlayer(u); if (x != null) pl.applyTeam(x); }
                pl.msg(p, "<green>Цвет тега изменён: " + my.display());
            }
            case "war" -> war(p, my, a);
            case "chest", "storage" -> openChest(p, my);
            case "buffs", "buff" -> {
                if (my == null) { pl.msg(p, "<red>Ты не в клане"); return; }
                if (buffsOff.remove(p.getUniqueId())) pl.msg(p, "<green>Клановые бафы включены");
                else { buffsOff.add(p.getUniqueId()); for (String b : buffs(my)) { var t = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(b.split(":")[0].trim().toLowerCase(Locale.ROOT))); PotionEffect cur = t == null ? null : p.getPotionEffect(t); if (cur != null && cur.isAmbient()) p.removePotionEffect(t); } pl.msg(p, "<yellow>Клановые бафы выключены для тебя"); }
                save();
            }
            case "level", "lvl", "levels" -> levelInfo(p, my);
            case "territory", "territories", "zones", "zone", "points" -> territories.list(p);
            case "info" -> {
                Clan c = a.length > 1 ? get(a[1]) : my;
                if (c == null) { pl.msg(p, "<red>Клан не найден"); return; }
                info(p, c);
            }
            case "top", "list" -> top(p);
            default -> help(p);
        }
    }

    private long num(String[] a, int i) { try { return Long.parseLong(a[i]); } catch (Exception e) { return -1; } }

    private void removeMember(Clan c, UUID u) {
        c.members.remove(u); c.officers.remove(u); byMember.remove(u);
        Player x = Bukkit.getPlayer(u);
        if (x != null && x.getOpenInventory().getTopInventory().getHolder() instanceof ClanChest) x.closeInventory();
        save();
    }

    private void levelInfo(Player p, Clan c) {
        if (c == null) { pl.msg(p, "<red>Ты не в клане"); return; }
        p.sendMessage(mm("<gradient:#00f0ff:#0080ff><bold>⚑ Уровень клана " + c.display() + "</bold></gradient> <gold>" + level(c) + "/" + maxLevel()));
        p.sendMessage(mm(bar(c)));
        p.sendMessage(mm("<gray>Сейчас: <white>" + perksText(c)));
        for (int i = 2; i <= maxLevel(); i++) {
            boolean got = level(c) >= i;
            ConfigurationSection l = lvl(i);
            List<String> b = l.getStringList("buffs");
            p.sendMessage(mm((got ? "<green>✔ " : "<dark_gray>✘ ") + "<white>" + i + " ур. <gray>(" + xpFor(i) + " опыта): мест " + l.getInt("members") + ", сундук " + l.getInt("chest-rows") + " ряд."
                    + (b.isEmpty() ? "" : ", " + String.join(", ", b.stream().map(this::buffName).toList()))));
        }
        p.sendMessage(mm("<gray>Опыт: убийство <white>+" + xpCfg("kill", 10) + "<gray>, онлайн участника <white>+" + xpCfg("online-member-10min", 2) + "/10 мин<gray>, вклад в банк <white>+1 за "
                + xpCfg("deposit-coins-per-xp", 100) + " " + pl.cur() + "<gray>, победа в войне <white>+" + xpCfg("war-win", 300) + "<gray>, территории <white>+" + pl.getConfig().getLong("territories.income-xp", 50) + "/выплата"));
    }

    public void chat(Player p, String msg) {
        Clan c = of(p.getUniqueId());
        if (c == null) { pl.msg(p, "<red>Ты не в клане"); return; }
        if (pl.admin() != null && pl.admin().isMuted(p)) return;
        String role = c.owner.equals(p.getUniqueId()) ? "<gold>★" : c.officers.contains(p.getUniqueId()) ? "<yellow>☆" : "<gray>•";
        broadcastClan(c, role + " <white>" + AnarchyCore.esc(p.getName()) + " <dark_gray>»</dark_gray> <aqua>" + AnarchyCore.esc(msg));
    }

    private void help(Player p) {
        for (String l : List.of("<gradient:#00f0ff:#0080ff><bold>⚑ Кланы</bold></gradient>",
                "<yellow>/clan create <тег> [название] <gray>- создать (" + pl.getConfig().getLong("clans.create-cost", 5000) + " " + pl.cur() + ")",
                "<yellow>/clan invite|kick <ник> <gray>- пригласить / выгнать",
                "<yellow>/clan accept|deny|leave <gray>- принять / отклонить / выйти",
                "<yellow>/clan deposit|withdraw <сумма> <gray>- банк клана",
                "<yellow>/clan sethome|home <gray>- дом клана",
                "<yellow>/cc <текст> <gray>- клан-чат",
                "<yellow>/clan war <тег> <gray>- объявить войну (1 час)",
                "<yellow>/clan level <gray>- уровень, опыт и бонусы клана",
                "<yellow>/clan chest <gray>- общий клановый сундук (со 2 ур.)",
                "<yellow>/clan buffs <gray>- вкл/выкл клановые бафы для себя",
                "<yellow>/clan zones <gray>- территории: захвати и получай доход",
                "<yellow>/clan promote|demote|owner <ник>, /clan color #hex, /clan disband",
                "<yellow>/clan info [тег], /clan top",
                "<gray>Участники клана: общие приваты, нет урона друг по другу."))
            p.sendMessage(mm(l));
    }

    private void info(Player p, Clan c) {
        p.sendMessage(mm("<gradient:#00f0ff:#0080ff><bold>⚑ Клан</bold></gradient> " + c.display() + " <white>" + AnarchyCore.esc(c.name)));
        p.sendMessage(mm("<gray>Глава: <gold>" + AnarchyCore.esc(name(c.owner)) + " <gray>| Участников: <white>" + c.members.size() + "/" + maxMembers(c)));
        p.sendMessage(mm("<gray>Убийств: <red>" + c.kills + " <gray>| Смертей: <white>" + c.deaths + " <gray>| Захватов: <aqua>" + c.kothWins + " <gray>| Побед в войнах: <gold>" + c.warsWon));
        p.sendMessage(mm("<gray>Уровень: <gold>" + level(c) + " <gray>| " + bar(c) + " <gray>| Территорий: <aqua>" + territories.count(c)));
        p.sendMessage(mm("<gray>Банк: <gold>" + c.bank + " " + pl.cur() + (c.warWith != null ? " <red>| В войне с [" + c.warWith.toUpperCase(Locale.ROOT) + "]" : "")));
        p.sendMessage(mm("<gray>Состав: <white>" + String.join(", ", c.members.stream().map(this::name).toList())));
    }

    private int score(Clan c) { return c.kills * 2 + c.kothWins * 25 + c.warsWon * 40; }

    public List<Clan> sorted() { return clans.values().stream().sorted(Comparator.comparingInt(this::score).reversed()).toList(); }

    private void top(Player p) {
        p.sendMessage(mm("<gradient:#00f0ff:#0080ff><bold>⚑ Топ кланов</bold></gradient> <gray>(очки = убийства x2 + захваты x25 + войны x40)"));
        int i = 1;
        for (Clan c : sorted()) { if (i > 10) break; p.sendMessage(mm("<gray>" + i++ + ". " + c.display() + " <white>" + AnarchyCore.esc(c.name) + " <dark_gray>- <aqua>" + score(c) + " <gray>очков, " + c.members.size() + " чел.")); }
        if (i == 1) p.sendMessage(mm("<gray>Кланов пока нет. Создай первый: /clan create"));
    }

    public String topText() {
        StringBuilder b = new StringBuilder();
        List<Clan> l = sorted();
        for (int i = 0; i < 10; i++) {
            if (i > 0) b.append("\n");
            String n = i == 0 ? "<#ffd700>①" : i == 1 ? "<#c0c0c0>②" : i == 2 ? "<#cd7f32>③" : "<gray>" + (i + 1) + ".";
            if (i < l.size()) b.append(n).append(" ").append(l.get(i).display()).append(" <white>").append(AnarchyCore.esc(l.get(i).name)).append(" <dark_gray>- <aqua>").append(score(l.get(i)));
            else b.append(n).append(" <dark_gray>---");
        }
        return b.toString();
    }

    // ---------- меню ----------
    private void menu(Player p, Clan c) {
        Menus.Menu m = new Menus.Menu(6, "<gradient:#00f0ff:#0080ff><bold>⚑ " + c.tag + "</bold></gradient> <dark_gray>| " + c.name);
        m.set(4, Menus.icon(Material.WHITE_BANNER, c.display() + " <white><bold>" + AnarchyCore.esc(c.name), "<gray>Участников: <white>" + c.members.size() + "/" + maxMembers(c),
                "<gray>Очков: <aqua>" + score(c), "<gray>Убийств: <red>" + c.kills + " <gray>| Захватов: <aqua>" + c.kothWins, "<gray>Побед в войнах: <gold>" + c.warsWon,
                c.warWith != null ? "<red>⚔ Война с [" + c.warWith.toUpperCase(Locale.ROOT) + "] - " + Util.left(c.warUntil) : "<gray>Войны нет"), null);
        m.set(2, Menus.icon(Material.EXPERIENCE_BOTTLE, "<gold><bold>Уровень " + level(c) + "/" + maxLevel(), bar(c), "", "<gray>" + perksText(c), "", "<yellow>▶ Подробнее"), x -> { p.closeInventory(); levelInfo(p, c); });
        m.set(6, Menus.icon(Material.BEACON, "<aqua><bold>Территории", "<gray>Под контролем: <white>" + territories.count(c), "<gray>Держи точку - клан получает доход", "", "<yellow>▶ Список"), x -> { p.closeInventory(); territories.list(p); });
        m.set(52, Menus.icon(Material.ENDER_CHEST, "<light_purple><bold>Сундук клана", chestRows(c) > 0 ? "<gray>" + chestRows(c) + " ряд." : "<red>Откроется на " + firstLevel("chest-rows") + " уровне", "", "<yellow>▶ Открыть"), x -> { p.closeInventory(); openChest(p, c); });
        m.set(53, Menus.icon(Material.BREWING_STAND, "<green><bold>Клановые бафы", buffs(c).isEmpty() ? "<gray>Пока нет (растут с уровнем)" : "<white>" + String.join(", ", buffs(c).stream().map(this::buffName).toList()),
                buffsOff.contains(p.getUniqueId()) ? "<red>Выключены для тебя" : "<green>Включены", "", "<yellow>▶ Переключить"), x -> { command(p, new String[]{"buffs"}); menu(p, c); });
        int slot = 19;
        for (UUID u : c.members) {
            if (slot % 9 == 8) slot += 2;
            if (slot > 34) break;
            PlayerData md = pl.store().get(u);
            ItemStack h = Menus.icon(Material.PLAYER_HEAD, (c.owner.equals(u) ? "<gold>★ " : c.officers.contains(u) ? "<yellow>☆ " : "<white>") + AnarchyCore.esc(name(u)),
                    Bukkit.getPlayer(u) != null ? "<green>В сети" : "<gray>Не в сети", md == null ? "" : "<gray>Убийств: <red>" + md.kills);
            SkullMeta sm = (SkullMeta) h.getItemMeta(); sm.setOwningPlayer(Bukkit.getOfflinePlayer(u)); h.setItemMeta(sm);
            m.set(slot++, h, null);
        }
        m.set(46, Menus.icon(Material.GOLD_BLOCK, "<gold><bold>Банк клана", "<gold>" + c.bank + " " + pl.cur(), "", "<gray>Положить: <yellow>/clan deposit <сумма>", "<gray>Снять: <yellow>/clan withdraw <сумма>"), null);
        m.set(47, Menus.icon(Material.RED_BED, "<green><bold>Дом клана", c.home == null ? "<red>Не установлен" : "<gray>Установлен", "", "<yellow>▶ Телепорт"), x -> { p.closeInventory(); command(p, new String[]{"home"}); });
        m.set(48, Menus.icon(Material.WRITABLE_BOOK, "<aqua><bold>Клан-чат", "<yellow>/cc <сообщение>"), null);
        m.set(49, Menus.icon(Material.IRON_SWORD, "<red><bold>Война", "<gray>/clan war <тег> - вызвать клан", "<gray>1 час, кто больше убьёт -", "<gray>получает 10% банка врага"), null);
        m.set(50, Menus.icon(Material.NETHER_STAR, "<aqua><bold>Топ кланов", "", "<yellow>▶ Показать"), x -> { p.closeInventory(); top(p); });
        m.set(51, Menus.icon(Material.OAK_DOOR, "<red>Выйти из клана", "<gray>/clan leave"), x -> { p.closeInventory(); command(p, new String[]{"leave"}); });
        m.set(45, Menus.icon(Material.ARROW, "<gray>« В меню"), x -> pl.menus().main(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    // ---------- войны ----------
    private void war(Player p, Clan my, String[] a) {
        if (my == null || !my.isOfficer(p.getUniqueId()) || a.length < 2) { pl.msg(p, "<red>/clan war <тег> - для главы/офицеров"); return; }
        Clan t = get(a[1]);
        if (t == null || t == my) { pl.msg(p, "<red>Клан не найден"); return; }
        if (my.warWith != null || t.warWith != null) { pl.msg(p, "<red>Один из кланов уже воюет"); return; }
        if (my.id().equals(warRequests.get(t.id()))) {
            warRequests.remove(t.id());
            long until = System.currentTimeMillis() + pl.getConfig().getLong("clans.war-minutes", 60) * 60_000;
            my.warWith = t.id(); t.warWith = my.id(); my.warUntil = t.warUntil = until; my.warKills = t.warKills = 0;
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<red><bold>⚔ ВОЙНА!</bold> " + my.display() + " <gray>против " + t.display() + " <gray>на 1 час. Победитель заберёт 10% банка врага!"));
            save();
            return;
        }
        warRequests.put(my.id(), t.id());
        broadcastClan(my, "<yellow>Вызов на войну отправлен клану " + t.display());
        broadcastClan(t, "<red>Клан " + my.display() + " <red>вызывает вас на войну! <gray>Принять: <yellow>/clan war " + my.tag);
    }

    private void warTick() {
        long now = System.currentTimeMillis();
        for (Clan c : clans.values()) {
            if (c.warWith == null || c.warUntil > now) continue;
            Clan o = clans.get(c.warWith);
            if (o == null) { c.warWith = null; continue; }
            Clan win = c.warKills == o.warKills ? null : c.warKills > o.warKills ? c : o;
            Clan lose = win == null ? null : win == c ? o : c;
            if (win != null) {
                long prize = lose.bank / 10;
                lose.bank -= prize; win.bank += prize; win.warsWon++;
                addXp(win, xpCfg("war-win", 300), "война");
                Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gold>⚔ Война окончена! " + win.display() + " <gold>победил " + lose.display()
                        + " <gray>(" + win.warKills + ":" + lose.warKills + ") и забрал <yellow>" + prize + " " + pl.cur()));
            } else Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<gray>⚔ Война " + c.display() + " <gray>и " + o.display() + " <gray>окончилась ничьей"));
            c.warWith = o.warWith = null; c.warKills = o.warKills = 0;
            save();
        }
    }

    // ---------- события ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player a = e.getDamager() instanceof Player x ? x : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player x2 ? x2 : null;
        if (a == null || a.equals(v)) return;
        if (same(a.getUniqueId(), v.getUniqueId()) && !pl.moreEvents().inTournament(v)) {
            e.setCancelled(true);
            a.sendActionBar(mm("<aqua>⚑ Это твой соклановец"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity(), k = v.getKiller();
        if (pl.moreEvents().inTournament(v) || pl.dungeon() != null && pl.dungeon().isDungeon(v.getWorld())) return;
        Clan vc = of(v.getUniqueId());
        if (vc != null) vc.deaths++;
        if (k == null || k.equals(v)) return;
        Clan kc = of(k.getUniqueId());
        if (kc == null || kc == vc) return;
        kc.kills++;
        String key = k.getUniqueId() + ">" + v.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = killXpCd.get(key);
        if (last == null || now - last > xpCfg("kill-same-victim-minutes", 10) * 60_000) {
            killXpCd.put(key, now);
            if (killXpCd.size() > 5000) killXpCd.values().removeIf(t -> now - t > 3_600_000);
            addXp(kc, xpCfg("kill", 10) + (vc != null && vc.id().equals(kc.warWith) ? xpCfg("war-kill", 10) : 0), "убийство");
        }
        if (vc != null && vc.id().equals(kc.warWith)) {
            kc.warKills++;
            broadcastClan(kc, "<red>⚔ +1 в войне! <gray>Счёт: <white>" + kc.warKills + ":" + vc.warKills);
            broadcastClan(vc, "<red>⚔ Враг убил вашего! <gray>Счёт: <white>" + vc.warKills + ":" + kc.warKills);
        }
    }

    public void addKoth(UUID u) { Clan c = of(u); if (c != null) { c.kothWins++; addXp(c, xpCfg("koth", 150), "KOTH"); save(); } }
}
