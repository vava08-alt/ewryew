package ru.anarchy.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static ru.anarchy.core.AnarchyCore.mm;

/** Бусты сервера (x2 монет / x2 опыта пропуска для всех) и скидки (акции, выходные). */
public final class Boosts {
    private final AnarchyCore pl;
    private long coinsUntil, passUntil;
    private String coinsBy = "", passBy = "";
    private final BossBar bar = BossBar.bossBar(mm(""), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    public Boosts(AnarchyCore pl) {
        this.pl = pl;
        coinsUntil = pl.getConfig().getLong("boosts.state.coins-until"); coinsBy = pl.getConfig().getString("boosts.state.coins-by", "");
        passUntil = pl.getConfig().getLong("boosts.state.pass-until"); passBy = pl.getConfig().getString("boosts.state.pass-by", "");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40, 40);
    }

    // ---------- скидки ----------
    public int salePercent() {
        long now = System.currentTimeMillis();
        int manual = pl.getConfig().getLong("sales.until", 0) > now ? pl.getConfig().getInt("sales.percent", 0) : 0;
        int weekend = 0;
        DayOfWeek dw = LocalDate.now(ZoneId.of(pl.getConfig().getString("sales.timezone", "Europe/Moscow"))).getDayOfWeek();
        if (dw == DayOfWeek.SATURDAY || dw == DayOfWeek.SUNDAY) weekend = pl.getConfig().getInt("sales.weekend-percent", 15);
        long startUntil = pl.getConfig().getLong("sales.start-until", 0);
        int start = startUntil > now ? pl.getConfig().getInt("sales.start-percent", 30) : 0;
        return Math.max(0, Math.min(90, Math.max(manual, Math.max(weekend, start))));
    }

    public String saleName() {
        long now = System.currentTimeMillis();
        if (pl.getConfig().getLong("sales.start-until", 0) > now && pl.getConfig().getInt("sales.start-percent", 30) == salePercent()) return "Скидки на открытии";
        if (pl.getConfig().getLong("sales.until", 0) > now && pl.getConfig().getInt("sales.percent", 0) == salePercent()) return pl.getConfig().getString("sales.name", "Акция");
        return "Скидки выходного дня";
    }

    public long price(long base) {
        int p = salePercent();
        return p <= 0 || base <= 0 ? base : Math.max(1, (long) Math.ceil(base * (100 - p) / 100.0));
    }

    /** "<s>100</s> 80 Ari (-20%)" */
    public String priceText(long base) {
        long p = price(base);
        return p == base ? "<light_purple>" + base + " " + pl.ariName() : "<dark_gray><st>" + base + "</st> <light_purple><bold>" + p + " " + pl.ariName() + "</bold> <red>-" + salePercent() + "%";
    }

    public void setSale(int percent, int hours, String name) {
        pl.getConfig().set("sales.percent", percent);
        pl.getConfig().set("sales.until", System.currentTimeMillis() + hours * 3_600_000L);
        pl.getConfig().set("sales.name", name);
        pl.saveConfig();
        if (percent > 0) {
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<red><bold>АКЦИЯ!</bold> <yellow>" + name + ": <white>-" + percent + "% <yellow>на весь донат " + hours + " ч! <gray>/don /shop /cases"));
            for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(Title.title(mm("<red><bold>-" + percent + "%"), mm("<yellow>" + name + " - " + hours + " ч")));
        }
    }

    // ---------- бусты ----------
    public double coinMult() { return coinsUntil > System.currentTimeMillis() ? pl.getConfig().getDouble("boosts.coins.multiplier", 2) : 1; }
    public double passMult() { return passUntil > System.currentTimeMillis() ? pl.getConfig().getDouble("boosts.pass.multiplier", 2) : 1; }

    public void menu(Player p) {
        PlayerData d = pl.data(p);
        Menus.Menu m = new Menus.Menu(3, "<gradient:#ffd700:#ff8c00><bold>⚡ Бусты сервера</bold></gradient>");
        long now = System.currentTimeMillis();
        long pc = pl.getConfig().getLong("boosts.coins.price", 99), pp = pl.getConfig().getLong("boosts.pass.price", 79);
        int min = pl.getConfig().getInt("boosts.minutes", 60);
        m.set(11, Menus.icon(Material.GOLD_BLOCK, "<gold><bold>x2 монет для ВСЕХ", "<gray>Все игроки получают в 2 раза", "<gray>больше монет за убийства и игру", "<gray>Длительность: <white>" + min + " мин",
                "<gray>Твой ник объявят всему серверу!", "", coinsUntil > now ? "<green>Сейчас активен (" + coinsBy + ") ещё " + Util.left(coinsUntil) : "<gray>Сейчас не активен",
                "<gray>Цена: " + priceText(pc), "", "<yellow>▶ Купить / продлить"), c -> buy(p, d, true));
        m.set(15, Menus.icon(Material.EXPERIENCE_BOTTLE, "<aqua><bold>x2 опыта пропуска для ВСЕХ", "<gray>Боевой пропуск качается вдвое быстрее", "<gray>Длительность: <white>" + min + " мин", "",
                passUntil > now ? "<green>Сейчас активен (" + passBy + ") ещё " + Util.left(passUntil) : "<gray>Сейчас не активен",
                "<gray>Цена: " + priceText(pp), "", "<yellow>▶ Купить / продлить"), c -> buy(p, d, false));
        long pm = pl.getConfig().getLong("boosts.monuments.price", 200);
        int have = pl.monuments() == null ? 0 : pl.monuments().count(), want = pl.getConfig().getInt("monuments.count", 20);
        m.set(13, Menus.icon(Material.PRISMARINE_BRICKS, "<gradient:#5ef2ff:#1e7bff><bold>Восстановить все данжи</bold></gradient>",
                "<gray>Все монументы на карте RTP", "<gray>появятся заново - <white>в новых местах", "<gray>со свежими ящиками (обычный,", "<gray>редкий, эпический + Элитра моря)", "",
                "<gray>Сейчас на карте: <white>" + have + "<gray>/" + want, pl.monuments() != null && pl.monuments().busy() ? "<yellow>Идёт восстановление..." : "",
                "<gray>Твой ник объявят всему серверу!", "<gray>Цена: " + priceText(pm), "", "<yellow>▶ Купить"), c -> buyMonuments(p, d));
        m.set(22, Menus.icon(Material.AMETHYST_SHARD, "<light_purple><bold>Баланс: " + d.ari + " " + pl.ariName(), "<gray>Пополнить: <white>" + pl.getConfig().getString("donate-site")), null);
        m.set(18, Menus.icon(Material.ARROW, "<gray>« В меню"), c -> pl.menus().main(p));
        m.fill();
        p.openInventory(m.getInventory());
    }

    private void buy(Player p, PlayerData d, boolean coins) {
        long price = price(pl.getConfig().getLong(coins ? "boosts.coins.price" : "boosts.pass.price", coins ? 99 : 79));
        if (d.ari < price) { pl.msg(p, "<red>Нужно " + price + " " + pl.ariName() + ". <gray>Пополнить: " + pl.getConfig().getString("donate-site")); return; }
        d.ari -= price;
        activate(p.getName(), coins, pl.getConfig().getInt("boosts.minutes", 60));
        p.closeInventory();
    }

    private void buyMonuments(Player p, PlayerData d) {
        if (pl.monuments() == null || pl.monuments().busy()) { pl.msg(p, "<yellow>Монументы уже восстанавливаются - подожди немного"); return; }
        long price = price(pl.getConfig().getLong("boosts.monuments.price", 200));
        if (d.ari < price) { pl.msg(p, "<red>Нужно " + price + " " + pl.ariName() + ". <gray>Пополнить: " + pl.getConfig().getString("donate-site")); return; }
        d.ari -= price;
        p.closeInventory();
        pl.monuments().regenerateAll(p.getName());
        pl.msg(p, "<green>Монументы восстанавливаются - через минуту они появятся на карте. Спасибо!");
    }

    public void activate(String by, boolean coins, int minutes) {
        long now = System.currentTimeMillis(), add = minutes * 60_000L;
        if (coins) { coinsUntil = Math.max(now, coinsUntil) + add; coinsBy = by; }
        else { passUntil = Math.max(now, passUntil) + add; passBy = by; }
        pl.getConfig().set("boosts.state.coins-until", coinsUntil); pl.getConfig().set("boosts.state.coins-by", coinsBy);
        pl.getConfig().set("boosts.state.pass-until", passUntil); pl.getConfig().set("boosts.state.pass-by", passBy);
        pl.saveConfig();
        String what = coins ? "<gold>x2 МОНЕТ" : "<aqua>x2 ОПЫТА ПРОПУСКА";
        Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<yellow>⚡ <white>" + AnarchyCore.esc(by) + " <yellow>активировал буст " + what + " <yellow>для всех на " + minutes + " мин! <gray>Спасибо! (/boost)"));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(Title.title(mm("<yellow><bold>⚡ БУСТ " + what), mm("<gray>от <white>" + AnarchyCore.esc(by)), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
        }
    }

    public String status() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        if (coinsUntil > now) b.append("<gold>⚡x2⛃ ").append((coinsUntil - now) / 60000).append("м ");
        if (passUntil > now) b.append("<aqua>⚡x2XP ").append((passUntil - now) / 60000).append("м");
        return b.toString().trim();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean c = coinsUntil > now, x = passUntil > now;
        if (!c && !x) { for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar); return; }
        String t = (c ? "<gold>⚡ x2 монет от <white>" + AnarchyCore.esc(coinsBy) + "<gold> - " + Util.left(coinsUntil) : "")
                + (c && x ? " <dark_gray>| " : "") + (x ? "<aqua>⚡ x2 опыта пропуска от <white>" + AnarchyCore.esc(passBy) + "<aqua> - " + Util.left(passUntil) : "");
        bar.name(mm(t));
        long until = c ? coinsUntil : passUntil;
        bar.progress((float) Math.max(0, Math.min(1, (until - now) / (pl.getConfig().getInt("boosts.minutes", 60) * 60_000.0))));
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
    }
}
