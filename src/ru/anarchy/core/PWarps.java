package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

import static ru.anarchy.core.AnarchyCore.mm;

/** Варпы игроков /pwarp: создание за монеты, платная реклама (поднятие в топ + объявление). */
public final class PWarps {
    public static final class Warp { String name, desc = "", loc, ownerName; UUID owner; long created, adUntil; int visits; Material icon = Material.ENDER_PEARL; }
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_А-Яа-яЁё]{2,16}$");
    private final AnarchyCore pl;
    private final File file;
    private final LinkedHashMap<String, Warp> warps = new LinkedHashMap<>();

    public PWarps(AnarchyCore pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "pwarps.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            Warp w = new Warp();
            w.name = s.getString("name", k); w.desc = s.getString("desc", ""); w.loc = s.getString("loc"); w.owner = UUID.fromString(s.getString("owner"));
            w.ownerName = s.getString("owner-name", "?"); w.created = s.getLong("created"); w.adUntil = s.getLong("ad-until"); w.visits = s.getInt("visits");
            Material m = Material.matchMaterial(s.getString("icon", "ENDER_PEARL")); if (m != null) w.icon = m;
            warps.put(w.name.toLowerCase(Locale.ROOT), w);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Warp w : warps.values()) {
            String k = w.name.toLowerCase(Locale.ROOT);
            y.set(k + ".name", w.name); y.set(k + ".desc", w.desc); y.set(k + ".loc", w.loc); y.set(k + ".owner", w.owner.toString());
            y.set(k + ".owner-name", w.ownerName); y.set(k + ".created", w.created); y.set(k + ".ad-until", w.adUntil); y.set(k + ".visits", w.visits); y.set(k + ".icon", w.icon.name());
        }
        try { y.save(file); } catch (Exception e) { pl.getLogger().severe("pwarps.yml: " + e); }
    }

    private int limit(PlayerData d) { return pl.getConfig().getInt("pwarps.base-limit", 1) + pl.rank(d).weight() / 20; }

    public void command(Player p, String[] a) {
        PlayerData d = pl.data(p);
        if (a.length == 0) { menu(p, 0); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set", "create" -> {
                if (a.length < 2 || !NAME.matcher(a[1]).matches()) { pl.msg(p, "<red>/pwarp set <название 2-16 символов> [описание]"); return; }
                if (warps.containsKey(a[1].toLowerCase(Locale.ROOT))) { pl.msg(p, "<red>Варп с таким названием уже есть"); return; }
                if (pl.worlds().isHub(p.getWorld()) || pl.moreEvents().inArena(p.getLocation())) { pl.msg(p, "<red>Здесь нельзя ставить варп"); return; }
                long mine = warps.values().stream().filter(w -> w.owner.equals(p.getUniqueId())).count();
                if (mine >= limit(d)) { pl.msg(p, "<red>Лимит варпов: " + limit(d) + " <gray>(больше с привилегией)"); return; }
                long cost = pl.getConfig().getLong("pwarps.cost", 2000);
                if (d.balance < cost) { pl.msg(p, "<red>Создание варпа стоит " + cost + " " + pl.cur()); return; }
                d.balance -= cost;
                Warp w = new Warp();
                w.name = a[1]; w.desc = a.length > 2 ? Util.join(a, 2) : ""; if (w.desc.length() > 60) w.desc = w.desc.substring(0, 60);
                w.loc = PlayerData.locToString(p.getLocation()); w.owner = p.getUniqueId(); w.ownerName = p.getName(); w.created = System.currentTimeMillis();
                { Material m = p.getInventory().getItemInMainHand().getType(); if (!m.isAir() && m.isItem()) w.icon = m; }
                warps.put(w.name.toLowerCase(Locale.ROOT), w);
                save();
                pl.msg(p, "<green>Варп <white>" + w.name + "</white> создан! <gray>Иконка - предмет в руке. Реклама: <yellow>/pwarp ad " + w.name);
            }
            case "del", "delete", "remove" -> {
                Warp w = a.length > 1 ? warps.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null || (!w.owner.equals(p.getUniqueId()) && !p.hasPermission("anarchy.admin"))) { pl.msg(p, "<red>/pwarp del <твой варп>"); return; }
                warps.remove(w.name.toLowerCase(Locale.ROOT)); save();
                pl.msg(p, "<yellow>Варп удалён");
            }
            case "ad", "reklama" -> {
                Warp w = a.length > 1 ? warps.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null || !w.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>/pwarp ad <твой варп> - реклама на " + pl.getConfig().getInt("pwarps.ad-hours", 24) + " ч"); return; }
                if (pl.admin().isMuted(p)) return;
                long cost = pl.getConfig().getLong("pwarps.ad-cost", 1000);
                if (d.balance < cost) { pl.msg(p, "<red>Реклама стоит " + cost + " " + pl.cur()); return; }
                d.balance -= cost;
                w.adUntil = Math.max(System.currentTimeMillis(), w.adUntil) + pl.getConfig().getInt("pwarps.ad-hours", 24) * 3_600_000L;
                save();
                Bukkit.broadcast(mm("<dark_gray>[<gold>Реклама</gold>]</dark_gray> <white>" + AnarchyCore.esc(p.getName()) + " <gray>приглашает на варп <yellow><bold>" + AnarchyCore.esc(w.name) + "</bold></yellow>"
                        + (w.desc.isEmpty() ? "" : " <gray>- " + AnarchyCore.esc(w.desc)) + " ").append(mm("<green>[ТП]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/pwarp " + w.name))));
            }
            case "list", "menu" -> menu(p, 0);
            case "my" -> { for (Warp w : warps.values()) if (w.owner.equals(p.getUniqueId())) pl.msg(p, "<white>" + w.name + " <gray>посещений: " + w.visits + (w.adUntil > System.currentTimeMillis() ? " <gold>реклама " + Util.left(w.adUntil) : "")); }
            case "help" -> help(p);
            default -> {
                Warp w = warps.get(sub);
                if (w == null) { pl.msg(p, "<red>Варп не найден. <gray>/pwarps - список, /pwarp help"); return; }
                go(p, w);
            }
        }
    }

    private void help(Player p) {
        for (String l : List.of("<gradient:#00f0ff:#0080ff><bold>Варпы игроков</bold></gradient>",
                "<yellow>/pwarps <gray>- список (сначала с рекламой)", "<yellow>/pwarp <название> <gray>- телепорт",
                "<yellow>/pwarp set <название> [описание] <gray>- создать (" + pl.getConfig().getLong("pwarps.cost", 2000) + " " + pl.cur() + ")",
                "<yellow>/pwarp ad <название> <gray>- реклама: объявление + вверху списка (" + pl.getConfig().getLong("pwarps.ad-cost", 1000) + " " + pl.cur() + ")",
                "<yellow>/pwarp del <название>, /pwarp my")) p.sendMessage(mm(l));
    }

    private void go(Player p, Warp w) {
        Location l = PlayerData.locFromString(w.loc);
        if (l == null) { pl.msg(p, "<red>Мир варпа недоступен"); return; }
        pl.teleports().start(p, "на варп " + w.name, () -> {
            pl.teleports().teleport(p, l);
            if (!w.owner.equals(p.getUniqueId())) { w.visits++; }
            pl.msg(p, "<gray>Варп <white>" + AnarchyCore.esc(w.name) + " <gray>от " + AnarchyCore.esc(w.ownerName) + ". <red>Осторожно - это анархия!");
        });
    }

    public void menu(Player p, int page) {
        long now = System.currentTimeMillis();
        List<Warp> list = warps.values().stream().sorted(Comparator.comparing((Warp w) -> w.adUntil > now ? 0 : 1).thenComparing(w -> -w.visits)).toList();
        int pages = Math.max(1, (list.size() + 35) / 36);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menus.Menu m = new Menus.Menu(6, "<gradient:#00f0ff:#0080ff><bold>Варпы игроков</bold></gradient> <dark_gray>" + (pg + 1) + "/" + pages);
        for (int i = 0; i < 36 && pg * 36 + i < list.size(); i++) {
            Warp w = list.get(pg * 36 + i);
            boolean ad = w.adUntil > now;
            var it = Menus.icon(w.icon, (ad ? "<gold><bold>★ " : "<white><bold>") + AnarchyCore.esc(w.name), w.desc.isEmpty() ? "<dark_gray>без описания" : "<gray>" + AnarchyCore.esc(w.desc),
                    "<gray>Владелец: <white>" + AnarchyCore.esc(w.ownerName), "<gray>Посещений: <white>" + w.visits, ad ? "<gold>Реклама" : "", "", "<yellow>▶ Телепорт");
            if (ad) { var meta = it.getItemMeta(); meta.setEnchantmentGlintOverride(true); it.setItemMeta(meta); }
            m.set(i, it, c -> { p.closeInventory(); go(p, w); });
        }
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Назад"), c -> { if (pg > 0) menu(p, pg - 1); else pl.menus().main(p); });
        m.set(53, Menus.icon(Material.ARROW, "<gray>Вперёд »"), c -> menu(p, pg + 1));
        m.set(49, Menus.icon(Material.OAK_SIGN, "<green><bold>Создать свой варп", "<yellow>/pwarp set <название> [описание]", "<gray>Цена: <gold>" + pl.getConfig().getLong("pwarps.cost", 2000) + " " + pl.cur(),
                "<gray>Реклама: <yellow>/pwarp ad <название>", "<gray>Цена: <gold>" + pl.getConfig().getLong("pwarps.ad-cost", 1000) + " " + pl.cur()), null);
        m.fill();
        p.openInventory(m.getInventory());
    }

    public Collection<String> names() { return warps.values().stream().map(w -> w.name).toList(); }
}
