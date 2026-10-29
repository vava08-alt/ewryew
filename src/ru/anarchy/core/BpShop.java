package ru.anarchy.core;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static ru.anarchy.core.AnarchyCore.mm;

/** Магазин БП: аквакоины (за уровни боевого пропуска) меняются на сферы и талисманы I-IV тира и вечные бафы. */
public final class BpShop {
    private final AnarchyCore pl;

    public BpShop(AnarchyCore pl) { this.pl = pl; }

    public String coinName() { return pl.getConfig().getString("bpshop.currency-name", "Аквакоины"); }
    public String sym() { return pl.getConfig().getString("bpshop.currency-symbol", "❂"); }
    /** «аквакоинов» - для фраз «не хватает ...». */
    public String genitive() { return pl.getConfig().getString("bpshop.currency-genitive", "аквакоинов"); }

    private long price(String id, long def) { return pl.getConfig().getLong("bpshop.prices." + id, def); }

    public void open(Player p) {
        PlayerData d = pl.data(p);
        pl.pass().syncCoins(p);
        Menus.Menu m = new Menus.Menu(6, "<gradient:#00e5ff:#2d60ff><bold>Магазин БП</bold></gradient> <dark_gray>| <aqua>" + d.aquacoins + " " + sym());
        m.set(4, Menus.icon(Material.HEART_OF_THE_SEA, "<aqua><bold>" + coinName() + ": " + d.aquacoins + " " + sym(),
                "<gray>Начисляются за уровни боевого пропуска:", "<white> +" + pl.pass().coinsPerLevel(d) + " " + sym() + " <gray>за уровень" + (d.passPremium ? " <gold>(премиум x2)" : " <dark_gray>(с премиумом x2)"),
                "<gray>После 50 уровня опыт пропуска", "<gray>продолжает приносить " + coinName().toLowerCase(java.util.Locale.ROOT) + ".", "", "<yellow>▶ Нажми - боевой пропуск"),
                c -> pl.pass().menu(p, pl.pass().level(d) / 9));
        section(m, 10, Material.PRISMARINE_CRYSTALS, "<gradient:#00e5ff:#2d60ff><bold>Сферы</bold></gradient>", "<gray>Буст во второй руке", "<gray>Тиры I-IV");
        section(m, 19, Material.TOTEM_OF_UNDYING, "<gradient:#ffd36b:#ff8a00><bold>Талисманы</bold></gradient>", "<gray>Тотем + буст во второй руке", "<gray>Тиры I-IV");
        section(m, 28, Material.BREWING_STAND, "<gradient:#ff9cf1:#9d5cff><bold>Вечные бафы</bold></gradient>", "<gray>Действуют всегда", "<gray>Вкл/выкл: /buffs");
        long[] sp = {60, 120, 200, 320}, tp = {30, 60, 100, 160};
        for (int i = 0; i < 4; i++) {
            String sphere = "sphere_" + CustomItems.SEA.get(i), talisman = "talisman_" + CustomItems.SEA.get(i);
            item(p, m, 12 + i, sphere, price(sphere, sp[i]));
            item(p, m, 21 + i, talisman, price(talisman, tp[i]));
        }
        int slot = 30;
        long[] bp = {300, 250, 600, 700};
        int bi = 0;
        for (CustomItems.Buff b : CustomItems.BUFFS.values()) buff(p, m, slot++, b, price("buff_" + b.id(), bp[Math.min(bp.length - 1, bi++)]));
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Боевой пропуск"), c -> pl.pass().menu(p, pl.pass().level(d) / 9));
        m.set(49, Menus.icon(Material.BOOK, "<aqua><bold>Как это работает",
                "<gray>Качай боевой пропуск: PvP, мобы,", "<gray>квесты, аирдропы, время в игре.", "<gray>Каждый уровень - " + coinName().toLowerCase(java.util.Locale.ROOT) + ".",
                "", "<gray>Сферы и талисманы V-VII тира", "<gray>только в ивентах и донат-магазине."), null);
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void section(Menus.Menu m, int slot, Material icon, String name, String... lore) {
        ItemStack it = Menus.icon(icon, name, lore);
        ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im);
        m.set(slot, it, null);
    }

    private ItemStack withLore(ItemStack base, String... extra) {
        ItemStack it = base.clone();
        ItemMeta m = it.getItemMeta();
        List<net.kyori.adventure.text.Component> l = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        for (String s : extra) l.add(mm("<!italic>" + s));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }

    private void item(Player p, Menus.Menu m, int slot, String id, long price) {
        ItemStack shown = pl.items().create(id);
        if (shown == null) return;
        PlayerData d = pl.data(p);
        m.set(slot, withLore(shown, "", "<gray>Цена: <aqua><bold>" + price + " " + sym(), d.aquacoins >= price ? "<yellow>▶ Нажми, чтобы купить" : "<red>Не хватает " + (price - d.aquacoins) + " " + sym()),
                c -> pl.menus().confirm(p, shown, price + " " + sym() + " " + coinName(), () -> {
                    if (d.aquacoins < price) { pl.msg(p, "<red>Не хватает " + genitive() + ": нужно " + price + " " + sym()); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
                    ItemStack it = pl.items().create(id);
                    if (it == null) return;
                    d.aquacoins -= price;
                    Util.give(p, it);
                    pl.msg(p, "<green>Куплено в магазине БП: </green>" + CustomItems.seaName(id) + " <gray>(-" + price + " " + sym() + ")");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }, () -> open(p)));
    }

    private void buff(Player p, Menus.Menu m, int slot, CustomItems.Buff b, long price) {
        PlayerData d = pl.data(p);
        boolean has = d.buffs.contains(b.id());
        List<String> lore = new ArrayList<>(List.of("<gray>Вечный баф: действует всегда,", "<gray>вкл/выкл в <white>/buffs"));
        if (CustomItems.pvpOff(b)) lore.add("<red>Не действует в режиме боя (PvP)");
        lore.add("");
        if (has) lore.add("<green>✔ Уже открыт");
        else {
            lore.add("<gray>Цена: <aqua><bold>" + price + " " + sym());
            lore.add(d.aquacoins >= price ? "<yellow>▶ Нажми, чтобы купить" : "<red>Не хватает " + (price - d.aquacoins) + " " + sym());
        }
        ItemStack shown = Menus.icon(b.icon(), (has ? "<green>" : "<light_purple>") + "<bold>Баф: " + b.name(), lore.toArray(String[]::new));
        if (has) { ItemMeta im = shown.getItemMeta(); im.setEnchantmentGlintOverride(true); shown.setItemMeta(im); }
        m.set(slot, shown, has ? null : c -> pl.menus().confirm(p, shown, price + " " + sym() + " " + coinName(), () -> {
            if (d.buffs.contains(b.id())) return;
            if (d.aquacoins < price) { pl.msg(p, "<red>Не хватает " + genitive() + ": нужно " + price + " " + sym()); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
            d.aquacoins -= price;
            d.buffs.add(b.id());
            d.buffsOff.remove(b.id());
            pl.msg(p, "<light_purple>Вечный баф <white>" + b.name() + "</white> открыт навсегда! <gray>Вкл/выкл: /buffs");
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.4f);
        }, () -> open(p)));
    }

    /** /bpshop give|take|set <ник> <n> - админ/автодонат. */
    public void admin(org.bukkit.command.CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage(mm("<gold>/bpshop give|take|set <ник> <кол-во> <gray>- аквакоины")); return; }
        PlayerData d = pl.store().byName(a[1]);
        if (d == null) { s.sendMessage(mm("<red>Игрок не найден")); return; }
        long v;
        try { v = Long.parseLong(a[2]); } catch (NumberFormatException e) { s.sendMessage(mm("<red>Неверное число")); return; }
        switch (a[0].toLowerCase(java.util.Locale.ROOT)) {
            case "give" -> d.aquacoins += v;
            case "take" -> d.aquacoins = Math.max(0, d.aquacoins - v);
            case "set" -> d.aquacoins = Math.max(0, v);
            default -> { s.sendMessage(mm("<gold>/bpshop give|take|set <ник> <кол-во>")); return; }
        }
        s.sendMessage(mm("<green>" + coinName() + " у " + d.name + ": <aqua>" + d.aquacoins + " " + sym()));
    }

    /** Цена из конфига bpshop.prices (для подсказок). */
    public ConfigurationSection prices() { return pl.getConfig().getConfigurationSection("bpshop.prices"); }
}
