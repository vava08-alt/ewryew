package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Боевой пропуск: 50 уровней, бесплатная и платная ветки. Опыт за PvP, мобов, игру, квесты, аирдропы. */
public final class BattlePass {
    public static final int LEVELS = 50;
    private final AnarchyCore pl;
    private final Map<UUID, Integer> mineCounter = new HashMap<>();

    public BattlePass(AnarchyCore pl) { this.pl = pl; }

    public int xpPerLevel() { return pl.getConfig().getInt("pass.xp-per-level", 100); }
    public int level(PlayerData d) { return Math.min(LEVELS, d.passXp / xpPerLevel()); }

    /** Награда уровня. */
    public String reward(int lvl, boolean premium) {
        if (!premium) {
            if (lvl == LEVELS) return "title:pass_free";
            if (lvl % 10 == 0) return "key:titles:1";
            if (lvl % 5 == 0) return "key:ari:1";
            if (lvl % 7 == 0) return "enchant:" + List.of("telekinesis", "autosmelt", "vampirism", "explosive").get(lvl / 7 % 4) + ":1";
            return "coins:" + (100 + lvl * 20);
        }
        if (lvl == LEVELS) return "title:pass_premium";
        if (lvl == 25 || lvl == 45) return "key:donate:1";
        if (lvl == 15) return "custom:titan_pickaxe";
        if (lvl == 35) return "custom:kratos_blade";
        if (lvl == 40) return "custom:olympus_chestplate";
        if (lvl % 10 == 0) return "enchant:" + List.of("vampirism", "explosive", "vampirism", "explosive").get(lvl / 10 % 4) + ":" + Math.min(3, lvl / 15 + 1);
        if (lvl % 6 == 0) return "key:titles:1";
        if (lvl % 4 == 0) return "ari:5";
        if (lvl % 3 == 0) return "key:ari:2";
        return "coins:" + (300 + lvl * 50);
    }

    private Cases.Reward rw(String spec) { return new Cases.Reward(spec, 1, Cases.Rarity.RARE); }

    // ---------- опыт ----------
    public void track(Player p, String type, Material m, int amount) {
        int xp = switch (type) {
            case "KILL_PLAYER" -> 25 * amount;
            case "KILL_MOB" -> 2 * amount;
            case "PLAYTIME" -> amount;
            case "FISH" -> 3 * amount;
            case "AIRDROP" -> 60 * amount;
            case "MINE" -> {
                if (m != null && (m.name().contains("DIAMOND_ORE") || m == Material.ANCIENT_DEBRIS)) yield 8;
                int c = mineCounter.merge(p.getUniqueId(), amount, Integer::sum);
                if (c >= 40) { mineCounter.put(p.getUniqueId(), 0); yield 2; }
                yield 0;
            }
            default -> 0;
        };
        if (xp > 0) addXp(p, xp);
    }

    /** После 50 уровня опыт продолжает копиться - каждые xp-per-level дают аквакоины. */
    public void addXp(Player p, int xp) {
        PlayerData d = pl.data(p);
        int before = level(d);
        d.passXp += (int) Math.round(xp * pl.boosts().passMult());
        int after = level(d);
        if (after > before) {
            pl.msg(p, "<gradient:#ffd700:#ff8c00><bold>Боевой пропуск: уровень " + after + "!</bold></gradient> <gray>Забери награду: <yellow>/pass");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        }
        syncCoins(p);
    }

    /** Аквакоинов за уровень: pass.aquacoins.per-level, с премиумом x premium-multiplier. */
    public long coinsPerLevel(PlayerData d) {
        long base = pl.getConfig().getLong("pass.aquacoins.per-level", 10);
        return d.passPremium ? Math.round(base * pl.getConfig().getDouble("pass.aquacoins.premium-multiplier", 2)) : base;
    }

    /** Начислить аквакоины за все уровни пропуска (в том числе «уровни» сверх 50), за которые ещё не начислено. */
    public void syncCoins(Player p) {
        PlayerData d = pl.data(p);
        int virtual = d.passXp / Math.max(1, xpPerLevel());
        if (virtual <= d.passCoinLevel) return;
        long add = (long) (virtual - d.passCoinLevel) * coinsPerLevel(d);
        d.passCoinLevel = virtual;
        if (add <= 0) return;
        d.aquacoins += add;
        pl.msg(p, "<aqua>+" + add + " " + pl.bpShop().sym() + " " + pl.bpShop().coinName() + " <gray>за уровни боевого пропуска. Потратить: <yellow>/bpshop");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.6f);
    }

    // ---------- меню ----------
    public void menu(Player p, int page) {
        PlayerData d = pl.data(p);
        syncCoins(p);
        int lvl = level(d);
        int pages = (LEVELS + 8) / 9;
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menus.Menu m = new Menus.Menu(6, "<gradient:#ffd700:#ff8c00><bold>Боевой пропуск</bold></gradient> <dark_gray>| ур. " + lvl);
        for (int i = 0; i < 9; i++) {
            int L = pg * 9 + i + 1;
            if (L > LEVELS) break;
            boolean reached = lvl >= L;
            m.set(i, Menus.icon(reached ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, (reached ? "<green>" : "<gray>") + "Уровень " + L,
                    "<gray>Нужно опыта: <white>" + L * xpPerLevel()), null);
            m.set(9 + i, icon(d, L, false, reached), c -> claim(p, L, false, pg));
            m.set(27 + i, icon(d, L, true, reached), c -> claim(p, L, true, pg));
        }
        m.set(18, Menus.icon(Material.PAPER, "<white>↑ <bold>Бесплатная ветка</bold>"), null);
        m.set(36, Menus.icon(Material.GOLD_INGOT, "<gold>↑ <bold>Премиум ветка</bold>"), null);
        int into = d.passXp - lvl * xpPerLevel();
        m.set(49, Menus.icon(Material.EXPERIENCE_BOTTLE, "<aqua><bold>Уровень " + lvl + "/" + LEVELS,
                lvl >= LEVELS ? "<green>Максимальный уровень!" : "<gray>Опыт: <white>" + into + "/" + xpPerLevel(),
                "", "<gray>Опыт дают:", "<white> Убийство игрока <aqua>+25", "<white> Моб <aqua>+2  <white>Рыба <aqua>+3", "<white> Минута игры <aqua>+1", "<white> Алмазы/обломки <aqua>+8",
                "<white> Квест <aqua>+40  <white>Аирдроп <aqua>+60", pl.boosts().passMult() > 1 ? "<yellow>⚡ Сейчас действует буст x2!" : "<dark_gray>Буст x2 опыта: /boost"), null);
        long price = pl.getConfig().getLong("pass.premium-price", 149);
        m.set(50, d.passPremium ? Menus.icon(Material.NETHER_STAR, "<gold><bold>Премиум активен", "<gray>Забирай награды нижнего ряда") :
                Menus.icon(Material.GOLD_BLOCK, "<gold><bold>Купить премиум", "<gray>Открывает нижнюю ветку наград:", "<gray>ключи донат-кейса, оружие богов,", "<gray>книги зачарований, Ari, титул", "", "<gray>Цена: " + pl.boosts().priceText(price), "", "<yellow>▶ Купить"),
                c -> { if (!d.passPremium) buyPremium(p); });
        m.set(48, Menus.icon(Material.HOPPER, "<green><bold>Забрать всё доступное"), c -> { claimAll(p); menu(p, pg); });
        m.set(47, Menus.icon(Material.HEART_OF_THE_SEA, "<gradient:#00e5ff:#2d60ff><bold>Магазин БП</bold></gradient>",
                "<gray>" + pl.bpShop().coinName() + ": <aqua>" + d.aquacoins + " " + pl.bpShop().sym(),
                "<gray>Сферы и талисманы I-IV тира,", "<gray>вечные бафы", "",
                "<gray>За каждый уровень пропуска: <aqua>+" + coinsPerLevel(d) + " " + pl.bpShop().sym() + (d.passPremium ? "" : " <dark_gray>(премиум x2)"), "", "<yellow>▶ /bpshop"), c -> pl.bpShop().open(p));
        m.set(45, Menus.icon(Material.ARROW, "<gray>« " + (pg > 0 ? "Назад" : "В меню")), c -> { if (pg > 0) menu(p, pg - 1); else pl.menus().main(p); });
        m.set(53, Menus.icon(Material.ARROW, "<gray>Вперёд » <dark_gray>(" + (pg + 1) + "/" + pages + ")"), c -> menu(p, pg + 1));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private ItemStack icon(PlayerData d, int L, boolean prem, boolean reached) {
        String spec = reward(L, prem);
        ItemStack it = pl.cases().display(rw(spec)).clone();
        String key = (prem ? "p" : "f") + L;
        boolean claimed = d.passClaimed.contains(key);
        ItemMeta m = it.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        lore.add(mm(""));
        lore.add(mm("<!italic>" + (prem ? "<gold>Премиум" : "<white>Бесплатно") + " <gray>| уровень " + L));
        lore.add(mm("<!italic>" + (claimed ? "<green>✔ Получено" : !reached ? "<red>Не достигнут" : prem && !d.passPremium ? "<gold>Нужен премиум" : "<yellow>▶ Нажми - забрать")));
        m.lore(lore);
        m.setEnchantmentGlintOverride(reached && !claimed && (!prem || d.passPremium));
        it.setItemMeta(m);
        if (claimed) it = Menus.icon(Material.MINECART, "<green>✔ Получено", "<gray>" + pl.cases().name(rw(spec)));
        return it;
    }

    private boolean give(Player p, int L, boolean prem) {
        PlayerData d = pl.data(p);
        String key = (prem ? "p" : "f") + L;
        if (d.passClaimed.contains(key) || level(d) < L || (prem && !d.passPremium)) return false;
        d.passClaimed.add(key);
        String name = pl.cases().grant(p, rw(reward(L, prem)));
        pl.msg(p, "<green>Награда пропуска (ур. " + L + "): <white>" + name);
        return true;
    }

    private void claim(Player p, int L, boolean prem, int pg) {
        PlayerData d = pl.data(p);
        if (prem && !d.passPremium) { pl.msg(p, "<gold>Это награда премиум-ветки. <gray>Купи премиум в этом меню."); return; }
        if (level(d) < L) { pl.msg(p, "<red>Нужен уровень " + L); return; }
        if (give(p, L, prem)) p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.2f);
        menu(p, pg);
    }

    private void claimAll(Player p) {
        int n = 0;
        for (int L = 1; L <= LEVELS; L++) { if (give(p, L, false)) n++; if (give(p, L, true)) n++; }
        pl.msg(p, n > 0 ? "<green>Получено наград: " + n : "<gray>Нет доступных наград");
    }

    private void buyPremium(Player p) {
        PlayerData d = pl.data(p);
        long price = pl.boosts().price(pl.getConfig().getLong("pass.premium-price", 149));
        if (d.ari < price) { pl.msg(p, "<red>Нужно " + price + " " + pl.ariName() + ". <gray>Пополнить: " + pl.getConfig().getString("donate-site")); return; }
        d.ari -= price;
        setPremium(d);
        menu(p, 0);
    }

    public void setPremium(PlayerData d) {
        d.passPremium = true;
        Player p = Bukkit.getPlayer(d.uuid);
        if (p != null) { pl.msg(p, "<gold><bold>Премиум боевого пропуска активирован!</bold> <gray>/pass"); p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); }
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<white>" + AnarchyCore.esc(d.name) + " <gold>купил премиум боевого пропуска! <gray>/pass"));
    }
}
