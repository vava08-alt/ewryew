package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Consumer;

import static ru.anarchy.core.AnarchyCore.mm;
import static ru.anarchy.core.AnarchyCore.time;

public final class Menus implements Listener {
    public static final class Menu implements InventoryHolder {
        final Inventory inv;
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        public Menu(int rows, String title) { inv = Bukkit.createInventory(this, rows * 9, mm(title)); }
        @Override public Inventory getInventory() { return inv; }
        public void set(int slot, ItemStack it, Consumer<ClickType> a) { inv.setItem(slot, it); if (a != null) actions.put(slot, a); }
        public void fill() {
            ItemStack g = icon(Material.BLACK_STAINED_GLASS_PANE, " "), c = icon(Material.CYAN_STAINED_GLASS_PANE, " "), b = icon(Material.BLUE_STAINED_GLASS_PANE, " ");
            int rows = inv.getSize() / 9;
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) != null) continue;
                int r = i / 9, col = i % 9;
                boolean edge = r == 0 || r == rows - 1 || col == 0 || col == 8;
                boolean corner = (r == 0 || r == rows - 1) && (col == 0 || col == 8);
                inv.setItem(i, corner ? b : edge ? c : g);
            }
        }
    }

    private final AnarchyCore pl;
    public Menus(AnarchyCore pl) { this.pl = pl; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof Menu)) return;
        Consumer<ClickType> a = m.actions.get(e.getRawSlot());
        if (a != null && e.getWhoClicked() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            a.accept(e.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ---------- утилиты ----------
    public static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm("<!italic>" + s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack withLore(ItemStack base, List<String> extra) {
        ItemStack it = base.clone();
        ItemMeta m = it.getItemMeta();
        List<Component> l = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        for (String s : extra) l.add(mm("<!italic>" + s));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack head(PlayerData d, String name, String... lore) {
        ItemStack it = icon(Material.PLAYER_HEAD, name, lore);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(d.uuid));
        it.setItemMeta(sm);
        return it;
    }

    private static String bar(int cur, int max) {
        int n = max == 0 ? 10 : (int) Math.round(10.0 * cur / max);
        return "<green>" + "■".repeat(Math.max(0, n)) + "<dark_gray>" + "■".repeat(Math.max(0, 10 - n));
    }

    private void back(Menu m, int slot, Runnable r) { m.set(slot, icon(Material.ARROW, "<gray>« Назад"), c -> r.run()); }

    private void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    public void confirm(Player p, ItemStack what, String price, Runnable ok, Runnable back) {
        Menu m = new Menu(3, "<dark_gray>Подтверждение покупки");
        m.set(13, withLore(what, List.of("", "<gray>Цена: " + price)), null);
        m.set(11, icon(Material.LIME_STAINED_GLASS_PANE, "<green><bold>КУПИТЬ", "<gray>" + price), c -> { ok.run(); back.run(); });
        m.set(15, icon(Material.RED_STAINED_GLASS_PANE, "<red><bold>Отмена"), c -> back.run());
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= ГЛАВНОЕ МЕНЮ =================
    public void main(Player p) {
        PlayerData d = pl.data(p);
        Rank r = pl.rank(d);
        Menu m = new Menu(5, "<gradient:#00f0ff:#0080ff><bold>✦ " + pl.getConfig().getString("server-name") + "</bold></gradient> <dark_gray>| меню");
        m.set(4, head(d, "<white><bold>" + d.name, "<gray>Ранг: " + r.prefix(), "<gray>Титул: " + pl.titleDisplay(d),
                "<gray>Монет: <gold>" + d.balance + " " + pl.cur(), "<gray>" + pl.ariName() + ": <light_purple>" + d.ari + " " + pl.ariSym()), null);
        // ряд 1 - мир и игроки
        m.set(10, icon(Material.ENDER_PEARL, "<green><bold>Случайный телепорт", "<gray>Обычный, большой, к игроку", "", "<yellow>▶ Открыть"), c -> rtp(p));
        Clans.Clan cl = pl.clans().of(p.getUniqueId());
        m.set(12, icon(Material.WHITE_BANNER, "<aqua><bold>Кланы", cl == null ? "<gray>Ты не в клане" : "<gray>Клан: " + cl.display() + " <white>" + AnarchyCore.esc(cl.name), "<gray>Общий приват, банк, клан-чат,", "<gray>войны и топ кланов", "", "<yellow>▶ /clan"), c -> { p.closeInventory(); p.performCommand("clan"); });
        m.set(13, icon(Material.LODESTONE, "<aqua><bold>Варпы", "<gray>PvP-арена, аукцион, донат-магазин,", "<gray>кланы, боевой пропуск, Посейдон", "", "<yellow>▶ /warp"), c -> pl.warps().menu(p));
        m.set(14, icon(Material.ENDER_EYE, "<green><bold>Варпы игроков", "<gray>Базы, магазины, фермы игроков", "<dark_gray>/pwarp set <название>", "", "<yellow>▶ /pwarps"), c -> pl.pwarps().menu(p, 0));
        m.set(16, icon(Material.GOLD_BLOCK, "<gold><bold>Аукцион", "<gray>Покупай и продавай предметы", "<dark_gray>/ah sell <цена>", "", "<yellow>▶ /ah"), c -> pl.auction().open(p, 0, "all", null));
        // ряд 2 - прогресс и награды
        m.set(19, icon(Material.WRITABLE_BOOK, "<aqua><bold>Ежедневные квесты", "<gray>Выполнено сегодня: <white>" + pl.quests().doneToday(d) + "/" + d.quests.size(), "<gray>Бонус за все: <light_purple>+" + pl.getConfig().getLong("quests.all-done-ari") + " " + pl.ariSym(), "", "<yellow>▶ Открыть"), c -> quests(p));
        m.set(20, icon(Material.NAME_TAG, "<yellow><bold>Титулы", "<gray>Текущий: " + pl.titleDisplay(d), "", "<yellow>▶ Выбрать"), c -> titles(p));
        m.set(21, icon(Material.CLOCK, "<gold><bold>Ежедневная награда", d.cooldownLeft("daily") > 0 ? "<red>Через " + time(d.cooldownLeft("daily")) : "<green>Доступна!", "", "<yellow>▶ Забрать"), c -> { p.closeInventory(); p.performCommand("daily"); });
        m.set(22, icon(Material.CHEST, "<white><bold>Киты", "<gray>Бесплатные наборы", "", "<yellow>▶ Открыть"), c -> { p.closeInventory(); p.performCommand("kit"); });
        m.set(23, icon(Material.POTION, "<light_purple><bold>Бафы", "<gray>Вкл/выкл вечные бафы", "<gray>Купить - в магазине БП", "", "<yellow>▶ Открыть"), c -> buffs(p));
        m.set(24, icon(Material.ENDER_CHEST, "<gradient:#00f0ff:#b000ff><bold>Кейсы</bold></gradient>", "<gray>Донат-кейс, Ari-кейс, кейс титулов", "<gray>Твоих ключей: <white>" + pl.cases().totalKeys(d), "", "<yellow>▶ /cases"), c -> pl.cases().list(p));
        m.set(25, icon(Material.EXPERIENCE_BOTTLE, "<gradient:#ffd700:#ff8c00><bold>Боевой пропуск</bold></gradient>", "<gray>Уровень: <white>" + pl.pass().level(d) + "/" + BattlePass.LEVELS, d.passPremium ? "<gold>Премиум активен" : "<gray>Бесплатная ветка", "", "<yellow>▶ /pass"), c -> pl.pass().menu(p, pl.pass().level(d) / 9));
        // ряд 3 - магазины
        m.set(29, icon(Material.CRAFTING_TABLE, "<aqua><bold>Особые крафты", "<gray>Рецепты спец-предметов:", "<gray>купол, стена храма, аура моря...", "<dark_gray>/crafts", "", "<yellow>▶ Открыть"), c -> pl.crafts().menu(p));
        m.set(30, icon(Material.EMERALD, "<green><bold>Магазин", "<gray>За монеты " + pl.cur(), "<gray>Приваты, тотемы, яблоки, элитры", "", "<yellow>▶ Открыть"), c -> shop(p));
        m.set(31, icon(Material.GOLDEN_HELMET, "<gold><bold>Привилегии", "<gray>6 рангов с уникальными командами", "", "<yellow>▶ /don"), c -> ranks(p));
        m.set(32, icon(Material.NETHER_STAR, "<gradient:#ff00aa:#aa00ff><bold>Донат-магазин</bold></gradient>", "<gray>Клинок Кратоса, Броня Олимпа,", "<gray>сферы, спавнеры, большие приваты", "", "<yellow>▶ /shop"), c -> donate(p));
        m.set(33, icon(Material.HOPPER, "<gold><bold>Скупщик", "<gray>Сдай ресурсы за монеты " + pl.cur(), "<gray>ТОП-5 предметов - цена x2-3", "", "<yellow>▶ /seller-buyer"), c -> pl.buyer().open(p));
        m.set(40, icon(Material.BARRIER, "<red>Закрыть"), c -> p.closeInventory());
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= ПРОФИЛЬ =================
    public void profile(Player viewer, PlayerData d) {
        Rank r = pl.rank(d);
        Menu m = new Menu(6, "<dark_gray>Профиль: <white>" + d.name);
        boolean online = Bukkit.getPlayer(d.uuid) != null;
        m.set(4, head(d, "<white><bold>" + d.name, "<gray>Ранг: " + r.prefix(), "<gray>Титул: " + pl.titleDisplay(d),
                online ? "<green>● В сети" : "<red>● Не в сети",
                d.rankExpire > 0 ? "<gray>Ранг ещё: <white>" + time((d.rankExpire - System.currentTimeMillis()) / 1000) : ""), null);
        String kd = d.deaths == 0 ? String.valueOf(d.kills) : String.format(Locale.US, "%.2f", (double) d.kills / d.deaths);
        m.set(20, icon(Material.NETHERITE_SWORD, "<red><bold>Бой",
                "<gray>Убийств: <red>" + d.kills, "<gray>Смертей: <white>" + d.deaths, "<gray>K/D: <yellow>" + kd,
                "<gray>Серия: <gold>" + d.streak + " <dark_gray>(лучшая " + d.bestStreak + ")", "<gray>Мобов убито: <white>" + d.mobKills), null);
        m.set(22, icon(Material.GOLD_INGOT, "<gold><bold>Экономика", "<gray>Монет: <gold>" + d.balance + " " + pl.cur(),
                "<gray>" + pl.ariName() + ": <light_purple>" + d.ari + " " + pl.ariSym()), null);
        m.set(24, icon(Material.CLOCK, "<green><bold>Активность", "<gray>Наиграно: <green>" + time(d.playMinutes * 60),
                "<gray>Квестов выполнено: <aqua>" + d.questsDone, "<gray>Аирдропов вскрыто: <gold>" + d.airdrops,
                "<gray>Блоков добыто: <white>" + d.blocksMined, "<gray>Домов: <white>" + d.homes.size() + "/" + r.homes(),
                "<gray>Приватов: <white>" + pl.regions().owned(d.uuid).size() + "/" + r.regions()), null);
        List<String> deaths = new ArrayList<>();
        deaths.add("<gray>Последние смерти:");
        if (d.deathLog.isEmpty()) deaths.add("<dark_gray>  Пока ни одной. Повезло.");
        for (int i = d.deathLog.size() - 1; i >= 0; i--) deaths.add("<red>☠ <white>" + AnarchyCore.esc(d.deathLog.get(i)));
        m.set(31, icon(Material.SKELETON_SKULL, "<dark_red><bold>Смерти <gray>(" + d.deaths + ")", deaths.toArray(new String[0])), null);
        List<String> buffs = new ArrayList<>();
        for (String b : d.buffs) { CustomItems.Buff bf = CustomItems.BUFFS.get(b); if (bf != null) buffs.add((d.buffsOff.contains(b) ? "<red>✘ " : "<green>✔ ") + "<white>" + bf.name()); }
        if (buffs.isEmpty()) buffs.add("<dark_gray>Нет бафов");
        m.set(33, icon(Material.POTION, "<light_purple><bold>Бафы", buffs.toArray(new String[0])), null);
        m.set(29, icon(Material.NAME_TAG, "<yellow><bold>Титулы", "<gray>Текущий: " + pl.titleDisplay(d), viewer.getUniqueId().equals(d.uuid) ? "<yellow>Нажми, чтобы сменить" : ""),
                viewer.getUniqueId().equals(d.uuid) ? c -> titles(viewer) : null);
        m.set(49, icon(Material.BARRIER, "<red>Закрыть"), c -> viewer.closeInventory());
        if (viewer.getUniqueId().equals(d.uuid)) back(m, 45, () -> main(viewer));
        m.fill();
        viewer.openInventory(m.inv);
    }

    // ================= КВЕСТЫ =================
    public void quests(Player p) {
        PlayerData d = pl.data(p);
        pl.quests().ensure(d);
        Menu m = new Menu(3, "<dark_gray>Ежедневные квесты");
        ConfigurationSection pool = pl.quests().pool();
        int[] slots = {11, 13, 15, 12, 14};
        int i = 0;
        for (String id : d.quests) {
            ConfigurationSection q = pool.getConfigurationSection(id);
            if (q == null || i >= slots.length) continue;
            int need = q.getInt("amount", 1), cur = d.questProgress.getOrDefault(id, 0);
            boolean done = d.questClaimed.contains(id);
            m.set(slots[i++], icon(done ? Material.LIME_DYE : Material.PAPER, (done ? "<green>✔ " : "<yellow>") + q.getString("name"),
                    bar(cur, need) + " <white>" + cur + "/" + need, "<gray>Награда: <gold>" + q.getLong("reward") + " " + pl.cur(),
                    done ? "<green>Выполнено" : "<gray>Награда выдаётся автоматически"), null);
        }
        m.set(22, icon(Material.NETHER_STAR, "<gradient:#00e5ff:#7a00ff>Бонус за все квесты", "<gold>+" + pl.getConfig().getLong("quests.all-done-bonus") + " " + pl.cur()
                + " <light_purple>+" + pl.getConfig().getLong("quests.all-done-ari") + " " + pl.ariSym(), d.questBonus ? "<green>Получен" : "<gray>Новые квесты каждый день"), null);
        back(m, 18, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= ТИТУЛЫ =================
    public void titles(Player p) {
        PlayerData d = pl.data(p);
        Menu m = new Menu(4, "<dark_gray>Титулы");
        ConfigurationSection ts = pl.getConfig().getConfigurationSection("titles");
        int slot = 10;
        if (ts != null) for (String id : ts.getKeys(false)) {
            if (slot == 17 || slot == 26) slot += 2;
            if (slot >= 27) break;
            ConfigurationSection t = ts.getConfigurationSection(id);
            boolean has = pl.titleUnlocked(d, id);
            boolean cur = id.equals(d.title);
            long ari = t.getLong("ari");
            String cond = pl.titleCondition(t);
            Material mat = cur ? Material.ENCHANTED_BOOK : has ? Material.NAME_TAG : ari > 0 ? Material.AMETHYST_SHARD : Material.GRAY_DYE;
            String status = cur ? "<green>✔ Выбран" : has ? "<yellow>Нажми, чтобы выбрать" : ari > 0 ? "<light_purple>Нажми, чтобы купить за " + ari + " " + pl.ariSym() : "<red>✘ " + cond;
            m.set(slot++, icon(mat, t.getString("display"), "<gray>" + cond, "", status), c -> {
                if (has) { d.title = id; pl.msg(p, "<green>Титул выбран: </green>" + t.getString("display")); titles(p); }
                else if (ari > 0) confirm(p, icon(Material.NAME_TAG, t.getString("display")), ari + " " + pl.ariName(), () -> {
                    if (d.ari < ari) { pl.msg(p, "<red>Недостаточно " + pl.ariName() + ". Пополнить: " + pl.getConfig().getString("donate-site")); return; }
                    d.ari -= ari; d.titles.add(id); d.title = id;
                    pl.msg(p, "<green>Титул куплен и выбран: </green>" + t.getString("display"));
                }, () -> titles(p));
            });
        }
        back(m, 27, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= БАФЫ =================
    public void buffs(Player p) {
        PlayerData d = pl.data(p);
        Menu m = new Menu(3, "<dark_gray>Мои бафы");
        int slot = 10;
        for (CustomItems.Buff b : CustomItems.BUFFS.values()) {
            boolean has = d.buffs.contains(b.id()), on = has && !d.buffsOff.contains(b.id());
            m.set(slot++, icon(has ? b.icon() : Material.GRAY_DYE, (on ? "<green>" : has ? "<red>" : "<gray>") + b.name(),
                    has ? (on ? "<green>Включён <gray>- нажми чтобы выключить" : "<red>Выключен <gray>- нажми чтобы включить") : "<gray>Не открыт - нажми: магазин БП (/bpshop)",
                    CustomItems.pvpOff(b) ? "<red>Не действует в режиме боя (PvP)" : ""), c -> {
                if (!has) { pl.bpShop().open(p); return; }
                if (on) { d.buffsOff.add(b.id()); p.removePotionEffect(b.type()); } else d.buffsOff.remove(b.id());
                buffs(p);
            });
        }
        back(m, 18, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= RTP =================
    public void rtp(Player p) {
        Menu m = new Menu(3, "<dark_gray>Случайный телепорт");
        m.set(11, icon(Material.GRASS_BLOCK, "<green><bold>Обычный RTP", "<gray>" + pl.getConfig().getInt("rtp.normal.min") + " - " + pl.getConfig().getInt("rtp.normal.max") + " блоков", "<gray>Для всех"), c -> { p.closeInventory(); p.performCommand("rtp"); });
        m.set(13, icon(Material.ELYTRA, "<gold><bold>Большой RTP", "<gray>" + pl.getConfig().getInt("rtp.far.min") + " - " + pl.getConfig().getInt("rtp.far.max") + " блоков", "<gray>С привилегии </gray>" + rankName("rtp-far")), c -> { p.closeInventory(); p.performCommand("rtp far"); });
        m.set(15, icon(Material.PLAYER_HEAD, "<red><bold>RTP к игроку", "<gray>Рядом со случайным игроком", "<gray>С привилегии </gray>" + rankName("rtp-player")), c -> { p.closeInventory(); p.performCommand("rtp player"); });
        back(m, 18, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    private String rankName(String perk) { Rank r = pl.rankForPerk(perk); return r == null ? "?" : r.prefix(); }

    // ================= МАГАЗИН (монеты) =================
    public void shop(Player p) {
        PlayerData d = pl.data(p);
        Menu m = new Menu(5, "<dark_gray>Магазин <gold>" + pl.cur() + " <dark_gray>| баланс: <gold>" + d.balance);
        ConfigurationSection s = pl.getConfig().getConfigurationSection("shop");
        int slot = 10;
        if (s != null) for (String id : s.getKeys(false)) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 35) break;
            String item = s.getString(id + ".item");
            long price = s.getLong(id + ".price");
            ItemStack it = pl.parseItem(item);
            if (it == null) continue;
            m.set(slot++, withLore(it, List.of("", "<gray>Цена: <gold>" + price + " " + pl.cur(), "<yellow>Нажми, чтобы купить")), c -> {
                if (d.balance < price) { pl.msg(p, "<red>Недостаточно монет"); return; }
                d.balance -= price;
                give(p, pl.parseItem(item));
                pl.msg(p, "<green>Куплено за " + price + " " + pl.cur());
                shop(p);
            });
        }
        back(m, 36, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    // ================= ДОНАТ-МАГАЗИН (Ari) =================
    public void donate(Player p) {
        PlayerData d = pl.data(p);
        Menu m = new Menu(5, "<gradient:#ff00aa:#aa00ff><bold>Донат-магазин</bold></gradient> <dark_gray>| <light_purple>" + d.ari + " " + pl.ariSym());
        m.set(4, icon(Material.AMETHYST_CLUSTER, "<light_purple><bold>Твой баланс: " + d.ari + " " + pl.ariName(),
                "<gray>" + pl.ariName() + " - донатная валюта сервера", "<gray>1 " + pl.ariName() + " = 1 ₽", "", "<yellow>Пополнить: <white>" + pl.getConfig().getString("donate-site")), null);
        m.set(19, icon(Material.NETHERITE_SWORD, "<red><bold>Оружие богов", "<gray>Клинок Кратоса, Топор Левиафана,", "<gray>Лук Артемиды, Трезубец Зевса,", "<gray>Коса Аида, Булава Посейдона,", "<gray>Арбалет Аполлона, Щит Ареса"), c -> donCategory(p, "weapons"));
        m.set(20, icon(Material.NETHERITE_PICKAXE, "<yellow><bold>Инструменты", "<gray>Кирка Титана, Бур Гефеста 3x3"), c -> donCategory(p, "tools"));
        m.set(21, icon(Material.NETHERITE_CHESTPLATE, "<white><bold>Броня", "<gray>Олимп: Защита VI + бонус сета", "<aqua>Чешуя Левиафана: Защита V", "<gray>Сапоги Гермеса (+10% скорости)"), c -> donCategory(p, "armor"));
        m.set(23, icon(Material.HEART_OF_THE_SEA, "<gradient:#00e5ff:#2d60ff><bold>Магазин БП</bold></gradient>", "<gray>Вечные бафы, сферы и талисманы", "<gray>I-IV тира - за " + pl.bpShop().coinName().toLowerCase(Locale.ROOT), "<gray>(дают за уровни боевого пропуска)", "", "<yellow>▶ /bpshop"), c -> pl.bpShop().open(p));
        m.set(24, icon(Material.SPAWNER, "<gold><bold>Спавнеры", "<gray>Зомби, скелеты, ифриты, големы..."), c -> donCategory(p, "spawners"));
        m.set(25, icon(Material.DIAMOND_BLOCK, "<aqua><bold>Большие приваты", "<gray>До 121x121, защита от взрывов"), c -> donCategory(p, "regions"));
        ItemStack sph = icon(Material.PLAYER_HEAD, "<gradient:#00e5ff:#2d60ff><bold>Сферы</bold></gradient>", "<gray>Головы со скином - сильный буст во второй руке:", "<gray>урон, броня, здоровье, скорость", "", "<dark_gray>7 уровней: от <gray>Сферы Рыбы</gray> до <gold>Сферы Посейдона");
        ItemStack sphSrc = pl.items().sphereHead("sphere_poseidon");
        if (sphSrc.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta s1 && sph.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta s2 && s1.getPlayerProfile() != null) { s2.setPlayerProfile(s1.getPlayerProfile()); sph.setItemMeta(s2); }
        m.set(28, sph, c -> donCategory(p, "spheres"));
        m.set(34, icon(Material.TOTEM_OF_UNDYING, "<gradient:#ffd36b:#ff8a00><bold>Талисманы</bold></gradient>", "<gray>Тотем бессмертия + буст во второй руке", "<gray>Спасает от смерти (и рассыпается)", "", "<dark_gray>7 уровней: от <gray>Талисмана Рыбы</gray> до <gold>Талисмана Посейдона"), c -> donCategory(p, "talismans"));
        m.set(30, icon(Material.NAME_TAG, "<yellow><bold>Титулы", "<gray>Эксклюзивные титулы за " + pl.ariName()), c -> titles(p));
        m.set(29, icon(Material.EXPERIENCE_BOTTLE, "<gold><bold>Премиум пропуска", "<gray>Цена: " + pl.boosts().priceText(pl.getConfig().getLong("pass.premium-price", 149))), c -> pl.pass().menu(p, 0));
        m.set(31, icon(Material.BEACON, "<yellow><bold>Бусты сервера", "<gray>x2 монет / опыта для всех"), c -> pl.boosts().menu(p));
        m.set(33, icon(Material.ENCHANTED_BOOK, "<aqua><bold>Книги зачарований", "<gray>Вампиризм, взрывные стрелы,", "<gray>автоплавка, телекинез"), c -> donCategory(p, "enchants"));
        m.set(13, icon(Material.GOLD_BLOCK, "<gradient:#00e5ff:#2d60ff><bold>Спец-предметы</bold></gradient>", "<gray>Золотой купол, Снег правды,", "<gray>Заряд аквамарина, Аура моря,", "<gray>Стена храма, Коралловая сеть"), c -> donCategory(p, "special"));
        m.set(14, icon(Material.SPLASH_POTION, "<gradient:#ff00aa:#aa00ff><bold>Донатные зелья</bold></gradient>", "<gray>Поцелуй Афины, Кровь жнеца,", "<gray>Зелье шторма, Зелье фугу,", "<gray>Зелье ярости"), c -> donCategory(p, "potions"));
        if (pl.boosts().salePercent() > 0) m.set(40, icon(Material.RED_CANDLE, "<red><bold>" + pl.boosts().saleName() + ": -" + pl.boosts().salePercent() + "%", "<gray>Скидка на всё за " + pl.ariName()), null);
        m.set(32, icon(Material.SUNFLOWER, "<gold><bold>Обменник", "<gray>1 " + pl.ariName() + " = " + pl.getConfig().getLong("economy.ari-to-coins") + " " + pl.cur()), c -> exchange(p));
        back(m, 36, () -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    public static final Map<String, String> PERKS = new LinkedHashMap<>();
    static {
        PERKS.put("hat", "/hat - любой блок на голову");
        PERKS.put("tpahere", "/tpahere - позвать игрока к себе");
        PERKS.put("trash", "/trash - мусорка");
        PERKS.put("ext", "/ext - потушить себя");
        PERKS.put("craft", "/craft - карманный верстак");
        PERKS.put("rtp-far", "<gold>/rtp far</gold> - большой RTP 5к-30к блоков");
        PERKS.put("hunt", "/hunt - найти ближайшего игрока");
        PERKS.put("stonecutter", "/stonecutter - камнерез");
        PERKS.put("loom", "/loom - ткацкий станок");
        PERKS.put("nv", "/nv - ночное зрение");
        PERKS.put("ghost", "/ghost - невидимость 30 сек");
        PERKS.put("ec", "/ec - эндер-сундук");
        PERKS.put("anvil", "/anvil - наковальня");
        PERKS.put("grindstone", "/grindstone - точило");
        PERKS.put("silent-join", "Тихий вход и выход");
        PERKS.put("rtp-player", "<red>/rtp player</red> - RTP к случайному игроку");
        PERKS.put("feed", "/feed - утолить голод");
        PERKS.put("repair", "/repair - починить предмет");
        PERKS.put("back", "/back - к месту смерти");
        PERKS.put("top", "/top - на поверхность");
        PERKS.put("smithing", "/smithing - стол кузнеца");
        PERKS.put("cartography", "/cartography - картографический стол");
        PERKS.put("heal", "/heal - полное исцеление");
        PERKS.put("near", "/near - кто рядом");
        PERKS.put("repair-all", "/repair all - починить всё");
        PERKS.put("fly", "<gold>/fly</gold> - полёт в своём привате");
        PERKS.put("bc", "/bc - объявление на весь сервер");
        PERKS.put("join-broadcast", "Торжественный вход");
        PERKS.put("invsee", "<light_purple>/invsee</light_purple> - смотреть инвентарь игроков");
        PERKS.put("ecsee", "/ecsee - смотреть эндер-сундуки");
        PERKS.put("color", "/color - свой цвет ника");
        PERKS.put("ptime", "/ptime - своё время суток");
        PERKS.put("pweather", "/pweather - своя погода");
        PERKS.put("daily-ari", "<light_purple>+5 Ari</light_purple> каждый день в /daily");
    }

    /** Красивая карточка привилегии. */
    public ItemStack rankCard(Rank r, Rank cur) {
        List<String> lore = new ArrayList<>();
        for (String t : r.description()) lore.add("<dark_gray><i>" + t);
        lore.add("");
        lore.add("<white><bold>Характеристики:");
        lore.add(" <gray>Домов: <white>" + r.homes() + "  <gray>Приватов: <white>" + r.regions());
        lore.add(" <gray>/rtp раз в <white>" + time(r.rtpCooldown()) + "  <gray>Телепорт: <white>" + (r.warmup() == 0 ? "мгновенно" : r.warmup() + " сек"));
        lore.add(" <gray>Монеты за убийства: <gold>x" + r.killMultiplier() + "  <gray>Кит: <white>/kit " + r.id());
        lore.add("");
        lore.add("<white><bold>Новые команды и возможности:");
        for (String perk : r.perks()) lore.add(" <gradient:#ff00aa:#aa00ff>✦</gradient> <white>" + PERKS.getOrDefault(perk, perk));
        Rank prev = null;
        for (Rank o : pl.ranks()) if (!o.hidden() && o.weight() < r.weight() && o.priceAri() > 0) prev = o;
        if (prev != null) lore.add(" <green>+ всё из </green>" + prev.prefix().trim());
        if (r.chatCooldown()) lore.add(" <gray>Задержка в чате"); else lore.add(" <gray>Чат без задержки");
        lore.add("");
        boolean owned = cur.weight() >= r.weight();
        long price = Math.max(0, r.priceAri() - cur.priceAri());
        lore.add("<gray>Цена: <light_purple><bold>" + r.priceAri() + " " + pl.ariName() + "</bold></light_purple> <dark_gray>(" + r.priceAri() + " ₽)");
        if (owned) lore.add("<green><bold>✔ У ТЕБЯ ЕСТЬ ЭТОТ РАНГ");
        else {
            if (price != r.priceAri()) lore.add("<yellow>Доплата с твоего ранга: <light_purple>" + price + " " + pl.ariName());
            lore.add("<yellow>▶ Нажми, чтобы купить");
        }
        Material icon = Material.matchMaterial(r.icon());
        ItemStack it = icon(icon == null ? Material.PAPER : icon, r.prefix().trim(), lore.toArray(new String[0]));
        ItemMeta m = it.getItemMeta();
        m.setEnchantmentGlintOverride(!owned);
        it.setItemMeta(m);
        return it;
    }

    public void ranks(Player p) {
        PlayerData d = pl.data(p);
        Rank cur = pl.rank(d);
        Menu m = new Menu(5, "<gradient:#ffd700:#ff00aa><bold>Привилегии сервера</bold></gradient>");
        m.set(4, icon(Material.AMETHYST_CLUSTER, "<light_purple><bold>Баланс: " + d.ari + " " + pl.ariName(), "<gray>Твой ранг: " + cur.prefix() + (d.rankExpire > 0 ? " <gray>(ещё " + Util.left(d.rankExpire) + ")" : ""),
                pl.boosts().salePercent() > 0 ? "<red><bold>" + pl.boosts().saleName() + ": -" + pl.boosts().salePercent() + "%" : "<gray>Привилегии на 30 дней - дешевле (ПКМ)",
                "<gray>Покупаешь старший ранг - платишь только разницу", "", "<yellow>Пополнить " + pl.ariName() + ": <white>" + pl.getConfig().getString("donate-site")), null);
        int[] slots = {20, 21, 22, 23, 24, 31, 30, 32};
        int i = 0;
        for (Rank r : pl.ranks()) {
            if (r.hidden() || r.priceAri() <= 0 || i >= slots.length) continue;
            boolean temp = d.rankExpire > 0;
            Rank permCur = temp ? (d.permRank == null ? pl.rankById("player") : pl.rankById(d.permRank)) : cur;
            if (permCur == null) permCur = pl.rankById("player");
            long price = pl.boosts().price(Math.max(0, r.priceAri() - permCur.priceAri()));
            long monthPrice = pl.boosts().price(Math.max(1, Math.round(r.priceAri() * pl.getConfig().getDouble("ranks-30d-factor", 0.4))));
            boolean owned = permCur.weight() >= r.weight();
            ItemStack card = withLore(rankCard(r, permCur), owned ? List.of() : List.of("",
                    "<white>ЛКМ <gray>- навсегда: " + pl.boosts().priceText(Math.max(0, r.priceAri() - permCur.priceAri())),
                    "<white>ПКМ <gray>- на 30 дней: " + pl.boosts().priceText(Math.max(1, Math.round(r.priceAri() * pl.getConfig().getDouble("ranks-30d-factor", 0.4)))),
                    temp && d.rank.equals(r.id()) ? "<green>Активен ещё " + Util.left(d.rankExpire) + " <gray>(ПКМ - продлить)" : ""));
            m.set(slots[i++], card, owned ? null : c -> {
                boolean month = c.isRightClick();
                long cost = month ? monthPrice : price;
                if (month && temp && !d.rank.equals(r.id()) && cur.weight() > r.weight()) { pl.msg(p, "<red>У тебя уже активен временный ранг выше"); return; }
                confirm(p, card, cost + " " + pl.ariName() + (month ? " (30 дней)" : " (навсегда)"), () -> {
                    if (d.ari < cost) { pl.msg(p, "<red>Недостаточно " + pl.ariName() + ". Пополнить: " + pl.getConfig().getString("donate-site")); return; }
                    d.ari -= cost;
                    if (!month && temp) { d.rankExpire = 0; d.permRank = null; }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rank set " + d.name + " " + r.id() + (month ? " 30" : ""));
                }, () -> ranks(p));
            });
        }
        m.set(40, icon(Material.NETHER_STAR, "<gradient:#ff00aa:#aa00ff><bold>Донат-магазин</bold></gradient>", "<gray>Оружие, броня, бафы, спавнеры", "<yellow>▶ /shop"), c -> donate(p));
        m.set(36, icon(Material.ARROW, "<gray>« В меню"), c -> main(p));
        m.fill();
        p.openInventory(m.inv);
    }

    public void donCategory(Player p, String cat) {
        PlayerData d = pl.data(p);
        ConfigurationSection s = pl.getConfig().getConfigurationSection("donshop." + cat);
        Menu m = new Menu(4, "<dark_gray>Донат: <light_purple>" + d.ari + " " + pl.ariSym());
        int slot = 10;
        if (s != null) for (String id : s.getKeys(false)) {
            if (slot % 9 == 8) slot += 2;
            if (slot >= 26) break;
            ConfigurationSection cs = s.getConfigurationSection(id);
            long basePrice = cs != null ? cs.getLong("price") : s.getLong(id);
            long price = pl.boosts().price(basePrice);
            ItemStack shown;
            Runnable buy;
            int setAmt = 1;
            switch (cat) {
                case "buffs" -> {
                    CustomItems.Buff b = CustomItems.BUFFS.get(id);
                    if (b == null) continue;
                    boolean has = d.buffs.contains(id);
                    shown = icon(b.icon(), "<light_purple><bold>Баф: " + b.name(), "<gray>Действует постоянно,", "<gray>можно выключить в /buffs", has ? "<green>✔ Куплен" : "");
                    buy = has ? null : () -> { d.buffs.add(id); pl.msg(p, "<green>Баф " + b.name() + " куплен навсегда!"); };
                }
                case "spawners" -> {
                    EntityType t;
                    try { t = EntityType.valueOf(id); } catch (Exception e) { continue; }
                    shown = pl.items().spawner(t);
                    buy = () -> give(p, pl.items().spawner(t));
                }
                case "enchants" -> {
                    String[] sp = id.split("_");
                    int lv = sp.length > 1 ? Integer.parseInt(sp[sp.length - 1]) : 1;
                    String eid = sp.length > 1 ? id.substring(0, id.lastIndexOf('_')) : id;
                    shown = pl.enchants().book(eid, lv);
                    if (shown == null) continue;
                    buy = () -> give(p, pl.enchants().book(eid, lv));
                }
                case "consumables" -> {
                    if (cs == null) continue;
                    ItemStack one = consumable(cs.getString("item", ""), Math.max(1, cs.getInt("amount", 1)));
                    if (one == null) { pl.getLogger().warning("donshop.consumables." + id + ": неверный item"); continue; }
                    shown = one;
                    int total = Math.max(1, cs.getInt("amount", 1));
                    setAmt = total;
                    buy = () -> { int left = total; while (left > 0) { ItemStack st = consumable(cs.getString("item", ""), left); if (st == null) break; give(p, st); left -= st.getAmount(); } };
                }
                case "regions" -> {
                    shown = pl.regions().item(id);
                    if (shown == null) continue;
                    buy = () -> give(p, pl.regions().item(id));
                }
                default -> {
                    shown = pl.items().create(id);
                    if (shown == null) continue;
                    int amt = cs != null ? Math.max(1, cs.getInt("amount", 1)) : 1;
                    shown.setAmount(Math.min(amt, shown.getMaxStackSize()));
                    setAmt = amt;
                    buy = () -> { for (int k = 0; k < amt; k++) give(p, pl.items().create(id)); };
                }
            }
            ItemStack disp = withLore(shown, List.of("", setAmt > 1 ? "<gray>Набор: <white>" + setAmt + " шт." : "", "<gray>Цена: " + pl.boosts().priceText(basePrice) + " " + pl.ariSym(), buy == null ? "" : "<yellow>Нажми, чтобы купить"));
            Runnable fb = buy;
            m.set(slot++, disp, fb == null ? null : c -> confirm(p, shown, price + " " + pl.ariName(), () -> {
                if (d.ari < price) { pl.msg(p, "<red>Недостаточно " + pl.ariName() + ". Пополнить: " + pl.getConfig().getString("donate-site")); return; }
                d.ari -= price;
                fb.run();
                pl.msg(p, "<green>Покупка успешна! <gray>Списано " + price + " " + pl.ariName());
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }, () -> donCategory(p, cat)));
        }
        back(m, 27, () -> donate(p));
        m.fill();
        p.openInventory(m.inv);
    }

    /** Расходник для магазина: MATERIAL | potion:<тип> | splash:<тип> | lingering:<тип> (тип - PotionType, напр. STRONG_STRENGTH). */
    private ItemStack consumable(String spec, int amount) {
        try {
            String[] p = spec.split(":");
            if (p.length == 2 && (p[0].equalsIgnoreCase("potion") || p[0].equalsIgnoreCase("splash") || p[0].equalsIgnoreCase("lingering"))) {
                Material m = p[0].equalsIgnoreCase("splash") ? Material.SPLASH_POTION : p[0].equalsIgnoreCase("lingering") ? Material.LINGERING_POTION : Material.POTION;
                ItemStack it = new ItemStack(m);
                org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) it.getItemMeta();
                pm.setBasePotionType(org.bukkit.potion.PotionType.valueOf(p[1].toUpperCase()));
                it.setItemMeta(pm);
                PotionStack.fix(it, pl);
                it.setAmount(Math.min(amount, it.getMaxStackSize()));
                return it;
            }
            Material m = Material.matchMaterial(spec);
            if (m == null || !m.isItem()) return null;
            return new ItemStack(m, Math.min(amount, m.getMaxStackSize()));
        } catch (Exception e) { return null; }
    }

    private void exchange(Player p) {
        PlayerData d = pl.data(p);
        long rate = pl.getConfig().getLong("economy.ari-to-coins", 1000);
        Menu m = new Menu(3, "<dark_gray>Обменник " + pl.ariName() + " → " + pl.cur());
        int slot = 11;
        for (int amount : new int[]{1, 10, 50, 100}) {
            m.set(slot, icon(Material.SUNFLOWER, "<gold>" + amount + " " + pl.ariName() + " → " + (amount * rate) + " " + pl.cur(), "<yellow>Нажми, чтобы обменять"), c -> {
                if (d.ari < amount) { pl.msg(p, "<red>Недостаточно " + pl.ariName()); return; }
                d.ari -= amount; d.balance += amount * rate;
                pl.msg(p, "<green>Обменено: +" + (amount * rate) + " " + pl.cur());
                exchange(p);
            });
            slot += 2;
        }
        back(m, 18, () -> donate(p));
        m.fill();
        p.openInventory(m.inv);
    }
}
