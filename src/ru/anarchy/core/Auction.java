package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Аукцион /ah: продажа предметов за монеты, категории, поиск, комиссия. */
public final class Auction {
    public static final class Lot { long id; UUID seller; String sellerName; ItemStack item; long price; long expires; }

    private final AnarchyCore pl;
    private final File file;
    private final LinkedHashMap<Long, Lot> lots = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> storage = new HashMap<>();
    private long nextId = 1;
    private boolean dirty;

    private static final String[] CATS = {"all", "weapons", "armor", "tools", "blocks", "food", "potions", "misc"};
    private static final String[] CAT_NAMES = {"Все", "Оружие", "Броня", "Инструменты", "Блоки", "Еда", "Зелья и книги", "Разное"};
    private static final Material[] CAT_ICONS = {Material.CHEST, Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_PICKAXE, Material.GRASS_BLOCK, Material.COOKED_BEEF, Material.POTION, Material.ENDER_PEARL};

    public Auction(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "auction.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::expire, 1200, 1200);
        Bukkit.getScheduler().runTaskTimer(pl, () -> { if (dirty) save(); }, 600, 600);
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getLong("next-id", 1);
        ConfigurationSection s = y.getConfigurationSection("lots");
        if (s != null) for (String k : s.getKeys(false)) {
            ConfigurationSection l = s.getConfigurationSection(k);
            Lot lot = new Lot();
            lot.id = Long.parseLong(k); lot.seller = UUID.fromString(l.getString("seller")); lot.sellerName = l.getString("name");
            lot.item = Util.itemFromString(l.getString("item")); lot.price = l.getLong("price"); lot.expires = l.getLong("expires");
            if (lot.item != null) lots.put(lot.id, lot);
        }
        ConfigurationSection st = y.getConfigurationSection("storage");
        if (st != null) for (String k : st.getKeys(false)) {
            List<ItemStack> list = new ArrayList<>();
            for (String b : st.getStringList(k)) { ItemStack it = Util.itemFromString(b); if (it != null) list.add(it); }
            storage.put(UUID.fromString(k), list);
        }
    }

    public void save() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Lot l : lots.values()) {
            String k = "lots." + l.id;
            y.set(k + ".seller", l.seller.toString()); y.set(k + ".name", l.sellerName);
            y.set(k + ".item", Util.itemToString(l.item)); y.set(k + ".price", l.price); y.set(k + ".expires", l.expires);
        }
        for (var e : storage.entrySet()) if (!e.getValue().isEmpty()) y.set("storage." + e.getKey(), e.getValue().stream().map(Util::itemToString).toList());
        try { y.save(file); } catch (Exception e) { pl.getLogger().severe("auction.yml: " + e); }
    }

    private int limit(PlayerData d) {
        int w = pl.rank(d).weight();
        int base = pl.getConfig().getInt("auction.max-lots", 5);
        return base + Math.min(10, w / 10);
    }
    private int tax() { return pl.getConfig().getInt("auction.tax-percent", 5); }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Lot l : new ArrayList<>(lots.values())) if (l.expires < now) {
            lots.remove(l.id);
            storage.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            dirty = true;
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) pl.msg(s, "<yellow>Лот на аукционе истёк - предмет в хранилище: <white>/ah storage");
        }
    }

    // ---------- команда ----------
    public void command(Player p, String[] a) {
        if (a.length == 0) { open(p, 0, "all", null); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> sell(p, a);
            case "search", "find" -> { if (a.length < 2) pl.msg(p, "<red>/ah search <текст>"); else open(p, 0, "all", Util.join(a, 1).toLowerCase(Locale.ROOT)); }
            case "my", "lots" -> mine(p);
            case "storage", "st" -> storage(p);
            default -> pl.msg(p, "<yellow>/ah <gray>- аукцион, <yellow>/ah sell <цена> <gray>- продать предмет в руке, <yellow>/ah search <текст>, /ah my, /ah storage");
        }
    }

    private void sell(Player p, String[] a) {
        PlayerData d = pl.data(p);
        long price;
        try { price = Long.parseLong(a[1]); } catch (Exception e) { pl.msg(p, "<red>/ah sell <цена> - продать предмет в руке"); return; }
        long max = pl.getConfig().getLong("auction.max-price", 100_000_000);
        if (price < 1 || price > max) { pl.msg(p, "<red>Цена от 1 до " + max); return; }
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) { pl.msg(p, "<red>Возьми предмет в руку"); return; }
        if (pl.worlds().isHub(p.getWorld()) && false) return;
        long mine = lots.values().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        if (mine >= limit(d)) { pl.msg(p, "<red>Максимум лотов: " + limit(d) + " <gray>(больше с привилегией)"); return; }
        Lot l = new Lot();
        l.id = nextId++; l.seller = p.getUniqueId(); l.sellerName = p.getName(); l.item = it.clone(); l.price = price;
        l.expires = System.currentTimeMillis() + pl.getConfig().getLong("auction.hours", 48) * 3_600_000L;
        p.getInventory().setItemInMainHand(null);
        lots.put(l.id, l);
        dirty = true;
        pl.msg(p, "<green>Лот выставлен за <gold>" + price + " " + pl.cur() + " <gray>(комиссия " + tax() + "% при продаже)");
        if (pl.getConfig().getBoolean("auction.announce", true))
            Bukkit.broadcast(mm("<dark_gray>[<gold>Аукцион</gold>]</dark_gray> <white>" + AnarchyCore.esc(p.getName()) + " <gray>продаёт ")
                    .append(itemName(l.item)).append(mm(" <gray>за <gold>" + price + " " + pl.cur() + " <dark_gray>(/ah)")));
    }

    private static Component itemName(ItemStack it) {
        Component n = it.getItemMeta() != null && it.getItemMeta().hasDisplayName() ? it.getItemMeta().displayName() : Component.translatable(it.translationKey());
        return mm("<white>" + it.getAmount() + "x ").append(n).hoverEvent(it.asHoverEvent());
    }

    private static String plainName(ItemStack it) {
        String s = it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (it.getItemMeta() != null && it.getItemMeta().hasDisplayName())
            s += " " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()).toLowerCase(Locale.ROOT);
        return s;
    }

    private static String category(ItemStack it) {
        Material m = it.getType(); String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") && false || m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT || m == Material.MACE || n.endsWith("ARROW")) return "weapons";
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || m == Material.ELYTRA || m == Material.SHIELD || m == Material.TURTLE_HELMET) return "armor";
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || m == Material.FISHING_ROD || m == Material.SHEARS || m == Material.FLINT_AND_STEEL) return "tools";
        if (m.isEdible()) return "food";
        if (n.contains("POTION") || m == Material.ENCHANTED_BOOK || m == Material.EXPERIENCE_BOTTLE || m == Material.TOTEM_OF_UNDYING) return "potions";
        if (m.isBlock()) return "blocks";
        return "misc";
    }

    private ItemStack lotIcon(Lot l, boolean own) {
        ItemStack it = l.item.clone();
        ItemMeta m = it.getItemMeta();
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        lore.add(mm("<!italic><dark_gray>────────────"));
        lore.add(mm("<!italic><gray>Цена: <gold><bold>" + l.price + " " + pl.cur()));
        lore.add(mm("<!italic><gray>Продавец: <white>" + AnarchyCore.esc(l.sellerName)));
        lore.add(mm("<!italic><gray>Осталось: <white>" + Util.left(l.expires)));
        lore.add(mm(own ? "<!italic><red>▶ Нажми - снять с продажи" : "<!italic><yellow>▶ Нажми - купить"));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    public void open(Player p, int page, String cat, String search) {
        List<Lot> list = lots.values().stream().filter(l -> cat.equals("all") || category(l.item).equals(cat))
                .filter(l -> search == null || plainName(l.item).contains(search) || l.sellerName.toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparingLong((Lot l) -> l.id).reversed()).toList();
        int pages = Math.max(1, (list.size() + 35) / 36);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menus.Menu m = new Menus.Menu(6, "<gradient:#ffd700:#ff8c00><bold>Аукцион</bold></gradient> <dark_gray>| " + (search != null ? "поиск: " + AnarchyCore.esc(search) : CAT_NAMES[Arrays.asList(CATS).indexOf(cat)]) + " " + (pg + 1) + "/" + pages);
        for (int i = 0; i < 36 && pg * 36 + i < list.size(); i++) {
            Lot l = list.get(pg * 36 + i);
            boolean own = l.seller.equals(p.getUniqueId());
            m.set(i, lotIcon(l, own), c -> { if (own) cancel(p, l); else confirmBuy(p, l, pg, cat, search); });
        }
        for (int i = 0; i < CATS.length; i++) {
            String c = CATS[i];
            m.set(36 + i, Menus.icon(CAT_ICONS[i], (c.equals(cat) ? "<green><bold>▶ " : "<white>") + CAT_NAMES[i], "<gray>Категория"), x -> open(p, 0, c, null));
        }
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Назад", "<gray>Страница " + (pg + 1) + "/" + pages), c -> { if (pg > 0) open(p, pg - 1, cat, search); else pl.menus().main(p); });
        m.set(53, Menus.icon(Material.ARROW, "<gray>Вперёд »", "<gray>Страница " + (pg + 1) + "/" + pages), c -> open(p, pg + 1, cat, search));
        m.set(47, Menus.icon(Material.OAK_SIGN, "<yellow><bold>Поиск", "<gray>/ah search <текст>"), null);
        m.set(48, Menus.icon(Material.EMERALD, "<green><bold>Продать", "<gray>Возьми предмет в руку и напиши", "<yellow>/ah sell <цена>", "<gray>Комиссия: <white>" + tax() + "%", "<gray>Срок лота: <white>" + pl.getConfig().getLong("auction.hours", 48) + " ч"), null);
        m.set(49, Menus.icon(Material.GOLD_INGOT, "<gold><bold>Баланс", "<gold>" + pl.data(p).balance + " " + pl.cur(), "<gray>Лотов на аукционе: <white>" + lots.size()), null);
        m.set(50, Menus.icon(Material.BOOK, "<aqua><bold>Мои лоты", "<gray>Снять с продажи"), c -> mine(p));
        int st = storage.getOrDefault(p.getUniqueId(), List.of()).size();
        m.set(51, Menus.icon(Material.ENDER_CHEST, "<light_purple><bold>Хранилище", "<gray>Непроданные и снятые предметы", "<gray>Предметов: <white>" + st), c -> storage(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void confirmBuy(Player p, Lot l, int pg, String cat, String search) {
        Menus.Menu m = new Menus.Menu(3, "<dark_gray>Купить за " + l.price + " " + pl.cur() + "?");
        m.set(13, lotIcon(l, false), null);
        m.set(11, Menus.icon(Material.LIME_STAINED_GLASS_PANE, "<green><bold>КУПИТЬ", "<gold>" + l.price + " " + pl.cur()), c -> { buy(p, l); open(p, pg, cat, search); });
        m.set(15, Menus.icon(Material.RED_STAINED_GLASS_PANE, "<red><bold>Отмена"), c -> open(p, pg, cat, search));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void buy(Player p, Lot l) {
        if (!lots.containsKey(l.id)) { pl.msg(p, "<red>Лот уже продан или снят"); return; }
        PlayerData d = pl.data(p);
        if (d.balance < l.price) { pl.msg(p, "<red>Не хватает монет: нужно " + l.price + " " + pl.cur()); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
        if (p.getInventory().firstEmpty() < 0) { pl.msg(p, "<red>Освободи место в инвентаре"); return; }
        lots.remove(l.id);
        d.balance -= l.price;
        long get = l.price - l.price * tax() / 100;
        PlayerData sd = pl.store().get(l.seller);
        if (sd != null) sd.balance += get;
        Util.give(p, l.item.clone());
        dirty = true;
        pl.msg(p, "<green>Куплено за <gold>" + l.price + " " + pl.cur());
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.2f);
        Player s = Bukkit.getPlayer(l.seller);
        if (s != null) { s.sendMessage(mm(pl.getConfig().getString("prefix", "") + "<green>Твой лот купил " + AnarchyCore.esc(p.getName()) + ": ").append(itemName(l.item)).append(mm(" <gold>+" + get + " " + pl.cur()))); s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1, 1.5f); }
    }

    private void cancel(Player p, Lot l) {
        if (lots.remove(l.id) == null) return;
        storage.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(l.item);
        dirty = true;
        pl.msg(p, "<yellow>Лот снят - предмет в хранилище (/ah storage)");
        mine(p);
    }

    private void mine(Player p) {
        Menus.Menu m = new Menus.Menu(4, "<dark_gray>Мои лоты");
        int i = 0;
        for (Lot l : lots.values()) if (l.seller.equals(p.getUniqueId()) && i < 27) m.set(i++, lotIcon(l, true), c -> cancel(p, l));
        m.set(31, Menus.icon(Material.ARROW, "<gray>« Аукцион"), c -> open(p, 0, "all", null));
        m.set(35, Menus.icon(Material.ENDER_CHEST, "<light_purple>Хранилище"), c -> storage(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void storage(Player p) {
        List<ItemStack> list = storage.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        Menus.Menu m = new Menus.Menu(4, "<dark_gray>Хранилище аукциона <gray>(нажми - забрать)");
        for (int i = 0; i < list.size() && i < 27; i++) {
            ItemStack it = list.get(i);
            m.set(i, it.clone(), c -> {
                if (!list.contains(it)) return;
                if (p.getInventory().firstEmpty() < 0) { pl.msg(p, "<red>Нет места в инвентаре"); return; }
                list.remove(it); Util.give(p, it); dirty = true; storage(p);
            });
        }
        m.set(31, Menus.icon(Material.ARROW, "<gray>« Аукцион"), c -> open(p, 0, "all", null));
        m.set(35, Menus.icon(Material.HOPPER, "<green>Забрать всё"), c -> {
            for (ItemStack it : new ArrayList<>(list)) { if (p.getInventory().firstEmpty() < 0) break; list.remove(it); Util.give(p, it); }
            dirty = true; storage(p);
        });
        m.fill();
        p.openInventory(m.getInventory());
    }
}
