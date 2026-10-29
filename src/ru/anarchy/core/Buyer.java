package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ru.anarchy.core.AnarchyCore.mm;

/** Скупщик (/seller-buyer, /sell): ресурсы за монеты по категориям, лимит сдачи на каждый предмет со сбросом
 *  каждые buyer.reset-hours, ТОП-5 ценных предметов - цена x2-3, лимит меньше. */
public final class Buyer implements Listener {
    private record Entry(Material m, double price, int limit, double mult) { boolean top() { return mult > 0; } }
    private record Cat(String id, String name, Material icon, List<Entry> items) {}
    private static final class SellWindow implements InventoryHolder {
        Inventory inv; boolean done;
        @Override public Inventory getInventory() { return inv; }
    }
    /** Итог одной продажи: строки чека и сумма. */
    private static final class Receipt {
        final Map<Material, Integer> counts = new LinkedHashMap<>();
        double money;
        final Set<Material> capped = new LinkedHashSet<>();
        void add(Entry e, int n) { counts.merge(e.m(), n, Integer::sum); money += n * e.price(); }
    }

    private final AnarchyCore pl;
    private final File file;
    private final Map<Material, Entry> entries = new HashMap<>();
    private final List<Entry> top = new ArrayList<>();
    private final Map<String, Cat> cats = new LinkedHashMap<>();
    private final Map<UUID, Map<Material, Integer>> sold = new ConcurrentHashMap<>();
    private final Map<UUID, Long> earned = new ConcurrentHashMap<>();
    private final Map<UUID, Double> change = new ConcurrentHashMap<>();   // дробные копейки, чтобы дешёвые ресурсы не продавались за 0
    private long period;
    private boolean dirty;

    public Buyer(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "buyer.yml");
        reload();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        period = y.getLong("period", currentPeriod());
        ConfigurationSection s = y.getConfigurationSection("sold");
        if (s != null) for (String u : s.getKeys(false)) {
            ConfigurationSection ps = s.getConfigurationSection(u);
            if (ps == null) continue;
            Map<Material, Integer> m = new ConcurrentHashMap<>();
            for (String k : ps.getKeys(false)) { Material mt = Material.matchMaterial(k); if (mt != null) m.put(mt, ps.getInt(k)); }
            sold.put(UUID.fromString(u), m);
        }
        ConfigurationSection es = y.getConfigurationSection("earned");
        if (es != null) for (String u : es.getKeys(false)) earned.put(UUID.fromString(u), es.getLong(u));
        ConfigurationSection cs = y.getConfigurationSection("change");
        if (cs != null) for (String u : cs.getKeys(false)) change.put(UUID.fromString(u), cs.getDouble(u));
        rollPeriod(false);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { rollPeriod(true); if (dirty) save(); }, 1200, 1200);
    }

    public void reload() {
        entries.clear(); top.clear(); cats.clear();
        for (String line : pl.getConfig().getStringList("buyer.top")) {
            String[] p = line.trim().split("\\s+");
            Material m = p.length >= 4 ? Material.matchMaterial(p[0]) : null;
            if (m == null || !m.isItem()) { pl.getLogger().warning("Скупщик: неверная строка ТОП " + line); continue; }
            double base = Double.parseDouble(p[1]), mult = Double.parseDouble(p[2]);
            Entry e = new Entry(m, base * mult, Integer.parseInt(p[3]), mult);
            top.add(e); entries.put(m, e);
        }
        ConfigurationSection cs = pl.getConfig().getConfigurationSection("buyer.categories");
        if (cs != null) for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            List<Entry> list = new ArrayList<>();
            for (String line : c.getStringList("items")) {
                String[] p = line.trim().split("\\s+");
                Material m = p.length >= 3 ? Material.matchMaterial(p[0]) : null;
                if (m == null || !m.isItem()) { pl.getLogger().warning("Скупщик: неверная строка " + line); continue; }
                if (entries.containsKey(m)) continue;   // предмет из ТОП-5 продаётся только там
                Entry e = new Entry(m, Double.parseDouble(p[1]), Integer.parseInt(p[2]), 0);
                list.add(e); entries.put(m, e);
            }
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            cats.put(id, new Cat(id, c.getString("name", id), icon == null ? Material.CHEST : icon, list));
        }
    }

    // ---------- период и лимиты ----------
    private long periodMs() { return Math.max(1, pl.getConfig().getLong("buyer.reset-hours", 5)) * 3_600_000L; }
    private long currentPeriod() { return System.currentTimeMillis() / periodMs(); }
    private long resetLeftSec() { return ((currentPeriod() + 1) * periodMs() - System.currentTimeMillis()) / 1000; }

    private void rollPeriod(boolean announce) {
        long cur = currentPeriod();
        if (cur == period) return;
        period = cur;
        sold.clear(); earned.clear();
        dirty = true;
        if (announce) for (Player p : Bukkit.getOnlinePlayers()) pl.msg(p, "<gold>♻ Лимиты скупщика обновлены! <gray>Сдать ресурсы: <white>/seller-buyer");
    }

    private int soldCount(UUID u, Material m) { Map<Material, Integer> s = sold.get(u); return s == null ? 0 : s.getOrDefault(m, 0); }
    private int left(UUID u, Entry e) { return Math.max(0, e.limit() - soldCount(u, e.m())); }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("period", period);
        for (var e : sold.entrySet()) for (var k : e.getValue().entrySet()) y.set("sold." + e.getKey() + "." + k.getKey().name(), k.getValue());
        for (var e : earned.entrySet()) y.set("earned." + e.getKey(), e.getValue());
        for (var e : change.entrySet()) if (e.getValue() > 0.0001) y.set("change." + e.getKey(), Math.round(e.getValue() * 1000) / 1000.0);
        try { y.save(file); dirty = false; } catch (Exception ex) { pl.getLogger().warning("buyer.yml: " + ex); }
    }

    /** «Чистый» ресурс: без имени, описания, чар, поломки и данных плагинов. */
    private static boolean clean(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (!it.hasItemMeta()) return true;
        ItemMeta m = it.getItemMeta();
        if (m.hasDisplayName() || m.hasLore() || m.hasEnchants() || !m.getPersistentDataContainer().isEmpty()) return false;
        return !(m instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage());
    }

    // ---------- продажа ----------
    /** Продать из инвентаря до want штук предмета (в пределах лимита). */
    private void sellFromInventory(Player p, Entry e, int want, Receipt r) {
        int lim = left(p.getUniqueId(), e);
        if (lim <= 0) { r.capped.add(e.m()); return; }
        ItemStack[] st = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack it : st) if (it != null && it.getType() == e.m() && clean(it)) have += it.getAmount();
        int n = Math.min(Math.min(want, lim), have);
        if (n <= 0) return;
        int need = n;
        for (int i = 0; i < st.length && need > 0; i++) {
            ItemStack it = st[i];
            if (it == null || it.getType() != e.m() || !clean(it)) continue;
            int take = Math.min(need, it.getAmount());
            need -= take;
            if (take == it.getAmount()) p.getInventory().setItem(i, null);
            else { ItemStack rest = it.clone(); rest.setAmount(it.getAmount() - take); p.getInventory().setItem(i, rest); }
        }
        credit(p, e, n, r);
        if (want > n && n == lim) r.capped.add(e.m());
    }

    private void credit(Player p, Entry e, int n, Receipt r) {
        sold.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).merge(e.m(), n, Integer::sum);
        r.add(e, n);
        dirty = true;
    }

    private void finish(Player p, Receipt r) {
        if (r.counts.isEmpty()) {
            if (!r.capped.isEmpty()) pl.msg(p, "<red>Лимит скупщика на этот предмет исчерпан. <gray>Сброс через <white>" + AnarchyCore.time(resetLeftSec()));
            else pl.msg(p, "<red>Нечего продать. <gray>Что скупается: <white>/seller-buyer");
            return;
        }
        double total = r.money + change.getOrDefault(p.getUniqueId(), 0.0);
        long money = (long) Math.floor(total);
        change.put(p.getUniqueId(), total - money);
        PlayerData d = pl.data(p);
        d.balance += money;
        earned.merge(p.getUniqueId(), money, Long::sum);
        StringBuilder lines = new StringBuilder();
        int i = 0;
        for (var c : r.counts.entrySet()) {
            if (i++ == 8) { lines.append("\n<gray> ...и ещё ").append(r.counts.size() - 8).append(" видов"); break; }
            Entry e = entries.get(c.getKey());
            lines.append("\n<gray> • <white>").append(nice(c.getKey())).append(" x").append(c.getValue()).append(" <dark_gray>→ <gold>").append(fmt(c.getValue() * e.price()))
                    .append(" <dark_gray>(лимит ").append(soldCount(p.getUniqueId(), c.getKey())).append("/").append(e.limit()).append(")");
        }
        pl.msg(p, "<green>Продано на <gold><bold>" + money + " " + pl.cur() + "</bold>" + lines);
        if (!r.capped.isEmpty()) pl.msg(p, "<yellow>Упёрлись в лимит: <white>" + String.join("<gray>, <white>", r.capped.stream().map(Buyer::nice).toList())
                + " <gray>- остаток вернётся после сброса через <white>" + AnarchyCore.time(resetLeftSec()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    /** Продать все подходящие ресурсы из инвентаря (только перечисленные предметы, если list != null). */
    private void sellAll(Player p, Collection<Entry> list) {
        Receipt r = new Receipt();
        Set<Material> seen = new HashSet<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || !seen.add(it.getType())) continue;
            Entry e = entries.get(it.getType());
            if (e != null && (list == null || list.contains(e))) sellFromInventory(p, e, Integer.MAX_VALUE, r);
        }
        finish(p, r);
    }

    // ---------- команды ----------
    public void sellCommand(Player p, String[] a) {
        if (p.getWorld().getName().equals("captcha")) return;
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "", "gui", "window" -> window(p);
            case "hand", "рука" -> {
                ItemStack it = p.getInventory().getItemInMainHand();
                Entry e = it.getType().isAir() || !clean(it) ? null : entries.get(it.getType());
                if (e == null) { pl.msg(p, "<red>Этот предмет не скупается. <gray>Список: <white>/seller-buyer"); return; }
                Receipt r = new Receipt();
                sellFromInventory(p, e, it.getAmount(), r);
                finish(p, r);
            }
            case "all", "все", "всё" -> sellAll(p, null);
            case "prices", "price", "цены", "menu" -> open(p);
            default -> pl.msg(p, "<yellow>/seller-buyer <gray>- скупщик, <yellow>/sell <gray>- окно продажи, <yellow>/sell hand <gray>- предмет в руке, <yellow>/sell all <gray>- всё из инвентаря");
        }
    }

    private void window(Player p) {
        SellWindow h = new SellWindow();
        h.inv = Bukkit.createInventory(h, 54, mm("<gradient:#ffd36b:#ff8a00><bold>⛃ Продажа</bold></gradient> <dark_gray>| закрой, чтобы продать"));
        p.openInventory(h.inv);
        pl.msg(p, "<gray>Положи ресурсы и <white>закрой окно<gray> - продадутся в пределах лимитов, остальное вернётся.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellWindow h) || h.done || !(e.getPlayer() instanceof Player p)) return;
        h.done = true;
        List<ItemStack> back = new ArrayList<>();
        Receipt r = new Receipt();
        for (ItemStack it : h.inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            Entry en = clean(it) ? entries.get(it.getType()) : null;
            int n = en == null ? 0 : Math.min(it.getAmount(), left(p.getUniqueId(), en));
            if (en != null && n < it.getAmount()) r.capped.add(en.m());
            if (n > 0) credit(p, en, n, r);
            if (n < it.getAmount()) { ItemStack rest = it.clone(); rest.setAmount(it.getAmount() - n); back.add(rest); }
        }
        h.inv.clear();
        if (r.counts.isEmpty() && back.isEmpty()) return;
        finish(p, r);
        if (!back.isEmpty()) {
            for (ItemStack left : p.getInventory().addItem(back.toArray(new ItemStack[0])).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            pl.msg(p, "<gray>Непроданное возвращено в инвентарь");
        }
    }

    // ---------- меню ----------
    public void open(Player p) {
        Menus.Menu m = new Menus.Menu(6, "<gradient:#ffd36b:#ff8a00><bold>⛃ Скупщик</bold></gradient> <dark_gray>| сдача ресурсов");
        m.set(4, Menus.icon(Material.CLOCK, "<gold><bold>Лимиты сдачи",
                "<gray>У каждого предмета свой лимит.", "<gray>Сброс всех лимитов через <white>" + AnarchyCore.time(resetLeftSec()),
                "<gray>Заработано за период: <gold>" + earned.getOrDefault(p.getUniqueId(), 0L) + " " + pl.cur(), "",
                "<yellow>ЛКМ <gray>по предмету - продать 64", "<yellow>ПКМ <gray>- продать 1", "<yellow>Shift+клик <gray>- продать всё"), null);
        m.set(13, Menus.icon(Material.NETHER_STAR, pl.getConfig().getString("buyer.top-title", "<gold><bold>★ ТОП-5"),
                "<gray>Самые ценные предметы сервера:", "<gray>цена <gold>в 2-3 раза выше</gold>, но лимит меньше"), null);
        int[] topSlots = {20, 21, 22, 23, 24};
        for (int i = 0; i < Math.min(top.size(), topSlots.length); i++) {
            Entry e = top.get(i);
            m.set(topSlots[i], icon(p, e), c -> { click(p, e, c); open(p); });
        }
        int[] catSlots = {38, 39, 40, 41, 42};
        int i = 0;
        for (Cat c : cats.values()) {
            if (i >= catSlots.length) break;
            int full = (int) c.items().stream().filter(e -> left(p.getUniqueId(), e) == 0).count();
            m.set(catSlots[i++], Menus.icon(c.icon(), c.name(), "<gray>Предметов: <white>" + c.items().size() + (full > 0 ? " <dark_gray>(лимит исчерпан: " + full + ")" : ""), "", "<yellow>▶ Открыть"), cl -> category(p, c));
        }
        m.set(47, Menus.icon(Material.HOPPER, "<green><bold>Продать всё", "<gray>Все ресурсы из инвентаря,", "<gray>сколько позволяют лимиты"), c -> { sellAll(p, null); open(p); });
        m.set(49, Menus.icon(Material.BARRIER, "<red>Закрыть"), c -> p.closeInventory());
        m.set(51, Menus.icon(Material.CHEST, "<aqua><bold>Окно продажи", "<gray>Положи ресурсы и закрой окно"), c -> window(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void category(Player p, Cat c) {
        Menus.Menu m = new Menus.Menu(6, "<gold>⛃ Скупщик <dark_gray>| " + c.name());
        int slot = 10;
        for (Entry e : c.items()) {
            if (slot % 9 == 8) slot += 2;
            if (slot > 43) break;
            m.set(slot++, icon(p, e), cl -> { click(p, e, cl); category(p, c); });
        }
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Назад"), cl -> open(p));
        m.set(49, Menus.icon(Material.BARRIER, "<red>Закрыть"), cl -> p.closeInventory());
        m.set(53, Menus.icon(Material.HOPPER, "<green><bold>Продать всё из категории", "<gray>сколько позволяют лимиты"), cl -> { sellAll(p, c.items()); category(p, c); });
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void click(Player p, Entry e, ClickType c) {
        Receipt r = new Receipt();
        sellFromInventory(p, e, c.isShiftClick() ? Integer.MAX_VALUE : c.isRightClick() ? 1 : 64, r);
        if (r.counts.isEmpty() && r.capped.isEmpty()) { pl.msg(p, "<red>У тебя нет: <white>" + nice(e.m())); return; }
        finish(p, r);
    }

    private ItemStack icon(Player p, Entry e) {
        int have = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == e.m() && clean(it)) have += it.getAmount();
        int done = soldCount(p.getUniqueId(), e.m()), left = left(p.getUniqueId(), e);
        List<String> lore = new ArrayList<>();
        if (e.top()) lore.add("<gray>Цена: <gold><bold>" + fmt(e.price()) + " " + pl.cur() + "</bold> <gray>за шт. <dark_gray>(x" + fmt(e.mult()) + " к обычной " + fmt(e.price() / e.mult()) + ")");
        else lore.add("<gray>Цена: <gold>" + fmt(e.price()) + " " + pl.cur() + " <gray>за шт.");
        lore.add("<gray>Сдано: <white>" + done + "<gray>/" + e.limit() + " " + bar(done, e.limit()));
        lore.add("<gray>У тебя: <white>" + have + " шт." + (have > 0 && left > 0 ? " <dark_gray>→ <gold>" + fmt(Math.min(have, left) * e.price()) + " " + pl.cur() : ""));
        lore.add("");
        if (left == 0) lore.add("<red>Лимит исчерпан - сброс через " + AnarchyCore.time(resetLeftSec()));
        else { lore.add("<yellow>ЛКМ <gray>- продать 64, <yellow>ПКМ <gray>- 1"); lore.add("<yellow>Shift+клик <gray>- продать всё (до " + left + ")"); }
        ItemStack it = Menus.icon(e.m(), (e.top() ? "<gold><bold>★ " : "<white>") + nice(e.m()), lore.toArray(new String[0]));
        if (e.top()) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
        return it;
    }

    private static String bar(int cur, int max) {
        int n = max <= 0 ? 10 : (int) Math.round(10.0 * Math.min(cur, max) / max);
        return (n >= 10 ? "<red>" : "<green>") + "|".repeat(n) + "<dark_gray>" + "|".repeat(10 - n);
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return String.valueOf(Math.round(v));
        return v >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String nice(Material m) { return "<lang:" + m.translationKey() + ">"; }
}
