package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/** Кейсы: донат-кейс, Ari-кейс, кейс титулов. Ключи виртуальные (+ предмет-ключ из лута). */
public final class Cases implements Listener {
    public enum Rarity {
        COMMON("<gray>Обычный", "<gray>", Material.LIGHT_GRAY_STAINED_GLASS_PANE, Color.GRAY),
        RARE("<aqua>Редкий", "<aqua>", Material.LIGHT_BLUE_STAINED_GLASS_PANE, Color.AQUA),
        EPIC("<light_purple>Эпический", "<light_purple>", Material.MAGENTA_STAINED_GLASS_PANE, Color.FUCHSIA),
        LEGENDARY("<gold>Легендарный", "<gold>", Material.ORANGE_STAINED_GLASS_PANE, Color.ORANGE),
        MYTHIC("<gradient:#ff0055:#00f0ff>Мифический</gradient>", "<gradient:#ff0055:#00f0ff>", Material.RED_STAINED_GLASS_PANE, Color.RED);
        final String label, color; final Material pane; final Color fw;
        Rarity(String l, String c, Material p, Color f) { label = l; color = c; pane = p; fw = f; }
    }

    public record Reward(String spec, double weight, Rarity rarity) {}

    public static final class Case {
        String id, display, block, active, anim; Location loc; long priceAri, priceCoins; Material icon;
        /** Сезонный кейс: active = "MM-dd..MM-dd" */
        public boolean isActive() {
            if (active == null || active.isBlank()) return true;
            if (AnarchyCore.INSTANCE.getConfig().getStringList("seasonal-force").contains(id)) return true;
            try {
                String[] r = active.split("\\.\\.");
                java.time.MonthDay now = java.time.MonthDay.now(java.time.ZoneId.of("Europe/Moscow"));
                java.time.MonthDay a = java.time.MonthDay.parse("--" + r[0].trim()), b = java.time.MonthDay.parse("--" + r[1].trim());
                return a.isBefore(b) || a.equals(b) ? !now.isBefore(a) && !now.isAfter(b) : !now.isBefore(a) || !now.isAfter(b);
            } catch (Exception e) { return true; }
        }
        List<String> description = new ArrayList<>();
        final List<Reward> rewards = new ArrayList<>();
        double total() { return rewards.stream().mapToDouble(Reward::weight).sum(); }
    }

    private static final class Spin implements InventoryHolder {
        Inventory inv; Case c; Reward win; boolean done; List<Reward> strip; int pos; org.bukkit.entity.ItemDisplay disp;
        @Override public Inventory getInventory() { return inv; }
    }

    private final AnarchyCore pl;
    private final LinkedHashMap<String, Case> cases = new LinkedHashMap<>();
    /** Идущие прокрутки: игрок -> кейс -> прокрутка. Разные кейсы можно крутить одновременно, один и тот же - по одному. */
    private final Map<UUID, Map<String, Spin>> spinning = new HashMap<>();
    private boolean spinningCase(Player p, Case c) { Map<String, Spin> m = spinning.get(p.getUniqueId()); return m != null && m.containsKey(c.id); }
    private final NamespacedKey KEY;

    public Cases(AnarchyCore pl) {
        this.pl = pl;
        KEY = new NamespacedKey(pl, "case_key");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::particles, 40, 5);
    }

    public Collection<Case> all() { return cases.values(); }
    public Case get(String id) { return id == null ? null : cases.get(id.toLowerCase(Locale.ROOT)); }

    public void load() {
        cases.clear();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("cases");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Case k = new Case();
            k.id = id.toLowerCase(Locale.ROOT);
            k.display = c.getString("display", id);
            k.block = c.getString("block", "ENDER_CHEST");
            k.loc = PlayerData.locFromString(c.getString("location", ""));
            k.priceAri = c.getLong("key-price-ari", 0);
            k.priceCoins = c.getLong("key-price-coins", 0);
            Material ic = Material.matchMaterial(c.getString("icon", "TRIPWIRE_HOOK"));
            k.icon = ic == null ? Material.TRIPWIRE_HOOK : ic;
            k.description = c.getStringList("description");
            k.active = c.getString("active");
            k.anim = c.getString("animation", "amethyst").toLowerCase(Locale.ROOT);
            for (String line : c.getStringList("rewards")) {
                String[] p = line.split(";");
                try { k.rewards.add(new Reward(p[0].trim(), Double.parseDouble(p[1].trim()), Rarity.valueOf(p[2].trim().toUpperCase(Locale.ROOT)))); }
                catch (Exception e) { pl.getLogger().warning("Кейс " + id + ": ошибка в награде " + line); }
            }
            cases.put(k.id, k);
        }
    }

    /** Ставит блоки кейсов на спавне. */
    public void placeBlocks() {
        for (Case c : cases.values()) {
            if (c.loc == null) continue;
            Block b = c.loc.getBlock();
            String type = c.block.toUpperCase(Locale.ROOT);
            try {
                if (type.equals("OMINOUS_VAULT")) b.setBlockData(Bukkit.createBlockData("minecraft:vault[ominous=true,facing=south]"), false);
                else if (type.equals("VAULT")) b.setBlockData(Bukkit.createBlockData("minecraft:vault[facing=south]"), false);
                else {
                    Material m = Material.matchMaterial(type);
                    if (m != null && b.getType() != m) b.setType(m, false);
                    if (b.getBlockData() instanceof org.bukkit.block.data.Directional dr && dr.getFacing() != org.bukkit.block.BlockFace.SOUTH) { dr.setFacing(org.bukkit.block.BlockFace.SOUTH); b.setBlockData(dr, false); }
                }
            } catch (Exception e) { pl.getLogger().warning("Блок кейса " + c.id + ": " + e.getMessage()); }
        }
    }

    public Case at(Block b) {
        for (Case c : cases.values())
            if (c.loc != null && c.loc.getWorld().equals(b.getWorld()) && c.loc.getBlockX() == b.getX() && c.loc.getBlockY() == b.getY() && c.loc.getBlockZ() == b.getZ()) return c;
        return null;
    }

    // ---------- ключи ----------
    public int keys(PlayerData d, String id) { return d.caseKeys.getOrDefault(id, 0); }
    public int totalKeys(PlayerData d) { return d.caseKeys.values().stream().mapToInt(Integer::intValue).sum(); }
    public void addKeys(PlayerData d, String id, int n) { d.caseKeys.merge(id, n, Integer::sum); if (d.caseKeys.get(id) <= 0) d.caseKeys.remove(id); }

    public ItemStack keyItem(String id, int amount) {
        Case c = get(id);
        if (c == null) return null;
        ItemStack it = new ItemStack(Material.TRIPWIRE_HOOK, Math.max(1, Math.min(64, amount)));
        ItemMeta m = it.getItemMeta();
        m.displayName(mm("<!italic>Ключ: " + c.display));
        m.lore(List.of(mm("<!italic><gray>ПКМ - добавить ключ на аккаунт"), mm("<!italic><gray>Кейсы стоят на спавне: <aqua>/spawn")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, c.id);
        it.setItemMeta(m);
        return it;
    }

    // ---------- события ----------
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() != null && (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_BLOCK)) {
            Case c = at(e.getClickedBlock());
            if (c != null) {
                e.setCancelled(true);
                if (!pl.data(p).authed) return;
                preview(p, c);
                return;
            }
        }
        ItemStack it = e.getItem();
        if (it != null && it.getType() == Material.TRIPWIRE_HOOK && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            String id = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
            Case c = get(id);
            if (c == null) return;
            e.setCancelled(true);
            int n = it.getAmount();
            p.getInventory().setItemInMainHand(null);
            addKeys(pl.data(p), c.id, n);
            pl.msg(p, "<green>+" + n + " ключ(ей) для " + c.display + "<green>. <gray>Открыть: /cases или на спавне");
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1.4f);
        }
    }

    // ---------- меню ----------
    public void list(Player p) {
        PlayerData d = pl.data(p);
        Menus.Menu m = new Menus.Menu(4, "<gradient:#00f0ff:#0080ff><bold>✦ Кейсы</bold></gradient> <dark_gray>| ключей: " + totalKeys(d));
        int[] slots = {11, 13, 15, 20, 22, 24};
        int i = 0;
        for (Case c : cases.values()) {
            if (i >= slots.length) break;
            if (!c.isActive()) continue;
            List<String> lore = new ArrayList<>(c.description);
            lore.add("");
            lore.add("<gray>Твоих ключей: <white><bold>" + keys(d, c.id));
            lore.add("<gray>Цена ключа: " + price(c));
            lore.add("");
            lore.add("<yellow>▶ Нажми - открыть кейс");
            m.set(slots[i++], Menus.icon(c.icon, c.display, lore.toArray(String[]::new)), x -> preview(p, c));
        }
        m.set(31, Menus.icon(Material.ARROW, "<gray>« В меню"), x -> pl.menus().main(p));
        m.set(27, Menus.icon(Material.BEACON, "<aqua><bold>Кейсы на спавне", "<gray>Кейсы стоят на спавне за", "<gray>центральной площадью.", "", "<yellow>▶ /spawn"), x -> { p.closeInventory(); p.performCommand("spawn"); });
        m.set(35, Menus.icon(Material.AMETHYST_SHARD, "<light_purple><bold>Баланс", "<gray>" + pl.ariName() + ": <light_purple>" + d.ari + " " + pl.ariSym(), "<gray>Монеты: <gold>" + d.balance + " " + pl.cur(), "", "<gray>Пополнить: <white>" + pl.getConfig().getString("donate-site")), null);
        fillAqua(m);
        p.openInventory(m.getInventory());
    }

    private String price(Case c) {
        List<String> s = new ArrayList<>();
        if (c.priceAri > 0) s.add(pl.boosts().priceText(c.priceAri));
        if (c.priceCoins > 0) s.add("<gold>" + c.priceCoins + " " + pl.cur());
        return s.isEmpty() ? "<red>не продаётся" : String.join(" <gray>или ", s);
    }

    private void fillAqua(Menus.Menu m) {
        ItemStack a = Menus.icon(Material.CYAN_STAINED_GLASS_PANE, " "), b = Menus.icon(Material.BLUE_STAINED_GLASS_PANE, " ");
        Inventory inv = m.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, (i % 2 == 0) ? a : b);
    }

    public void preview(Player p, Case c) {
        PlayerData d = pl.data(p);
        Menus.Menu m = new Menus.Menu(6, c.display + " <dark_gray>| ключей: " + keys(d, c.id));
        double total = c.total();
        List<Reward> sorted = new ArrayList<>(c.rewards);
        sorted.sort(Comparator.comparingDouble(Reward::weight).reversed());
        int slot = 10;
        for (Reward r : sorted) {
            if (slot % 9 == 8) slot += 2;
            if (slot > 34) break;
            ItemStack it = display(r);
            appendLore(it, List.of("", "<gray>Редкость: " + r.rarity().label, "<gray>Шанс: <white>" + chance(r.weight() / total * 100)));
            m.set(slot++, it, null);
        }
        int k = keys(d, c.id);
        m.set(48, Menus.icon(Material.TRIPWIRE_HOOK, "<yellow><bold>Купить ключ", "<gray>Цена: " + price(c),
                c.priceAri > 0 ? "<gray>ЛКМ - за " + pl.ariName() : "", c.priceCoins > 0 ? "<gray>ПКМ - за монеты" : "",
                "<gray>Shift - купить 5 штук"), click -> {
            boolean coins = c.priceCoins > 0 && (click.isRightClick() || c.priceAri <= 0);
            int n = click.isShiftClick() ? 5 : 1;
            buyKey(p, c, n, coins);
            preview(p, c);
        });
        m.set(49, Menus.icon(k > 0 ? Material.LIME_DYE : Material.GRAY_DYE, k > 0 ? "<green><bold>ОТКРЫТЬ КЕЙС" : "<red><bold>Нет ключей",
                "<gray>Твоих ключей: <white>" + k, "", k > 0 ? "<yellow>▶ Нажми, чтобы крутить" : "<gray>Купи ключ слева"), click -> {
            if (keys(pl.data(p), c.id) <= 0) { pl.msg(p, "<red>Нет ключа для этого кейса. <gray>Купи его в этом меню."); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
            open(p, c);
        });
        m.set(51, Menus.icon(k > 0 ? Material.CHEST_MINECART : Material.MINECART, "<gold><bold>⚡ Быстрое открытие",
                "<gray>Сразу несколько кейсов, без прокрутки -", "<gray>все призы выпадают мгновенно", "",
                "<yellow>ЛКМ <gray>- открыть 5", "<yellow>ПКМ <gray>- открыть 10", "<yellow>Shift <gray>- открыть все ключи (" + k + ")"), click -> {
            int n = click.isShiftClick() ? MANY_MAX : click.isRightClick() ? 10 : 5;
            openMany(p, c, n);
        });
        String site = pl.getConfig().getString("donate-site");
        String[] how = "donate".equals(c.id)
                ? new String[]{"<gray>- Только за " + pl.ariName() + ": купить здесь", "<gray>- Премиум-пропуск /pass (25 и 45 уровень)", "<gray>- Пополнить " + pl.ariName() + ": <white>" + site, "", "<dark_gray>Из ивентов и других кейсов не выпадает"}
                : new String[]{"<gray>- Купить здесь", "<gray>- Найти в луте ивентов", "<gray>- Выполнить все квесты дня", "<gray>- Ежедневная награда /daily", "<gray>- Купить на сайте: <white>" + site};
        m.set(50, Menus.icon(Material.BOOK, "<aqua><bold>Как получить ключи", how), null);
        m.set(45, Menus.icon(Material.ARROW, "<gray>« Все кейсы"), x -> list(p));
        fillAqua(m);
        p.openInventory(m.getInventory());
    }

    private static String chance(double v) { return v >= 1 ? String.format(Locale.US, "%.1f%%", v) : String.format(Locale.US, "%.2f%%", v); }

    private void buyKey(Player p, Case c, int n, boolean coins) {
        PlayerData d = pl.data(p);
        if (coins) {
            if (c.priceCoins <= 0) return;
            long cost = c.priceCoins * n;
            if (d.balance < cost) { pl.msg(p, "<red>Не хватает монет: нужно " + cost + " " + pl.cur()); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
            d.balance -= cost;
        } else {
            if (c.priceAri <= 0) return;
            long cost = pl.boosts().price(c.priceAri) * n;
            if (d.ari < cost) { pl.msg(p, "<red>Не хватает " + pl.ariName() + ": нужно " + cost + ". <gray>Пополнить: <white>" + pl.getConfig().getString("donate-site")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
            d.ari -= cost;
        }
        addKeys(d, c.id, n);
        pl.msg(p, "<green>Куплено ключей: " + n + " <gray>(" + c.display + "<gray>)");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.3f);
    }

    // ---------- рулетка ----------
    private Reward roll(Case c) {
        double r = ThreadLocalRandom.current().nextDouble(c.total());
        for (Reward w : c.rewards) { r -= w.weight(); if (r < 0) return w; }
        return c.rewards.get(c.rewards.size() - 1);
    }

    private static final int MANY_MAX = 100;

    /** Быстрое открытие сразу нескольких кейсов: без прокрутки, все призы сразу + окно с результатами. */
    public void openMany(Player p, Case c, int want) {
        PlayerData d = pl.data(p);
        int n = Math.min(Math.min(want, MANY_MAX), keys(d, c.id));
        if (n <= 0 || c.rewards.isEmpty()) { pl.msg(p, "<red>Нет ключей для этого кейса. <gray>Купи их в этом меню."); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return; }
        addKeys(d, c.id, -n);
        List<Reward> wins = new ArrayList<>();
        for (int i = 0; i < n; i++) wins.add(roll(c));
        wins.sort(Comparator.comparing((Reward w) -> w.rarity().ordinal()).reversed());
        Map<String, Integer> got = new LinkedHashMap<>();
        List<ItemStack> icons = new ArrayList<>();
        Rarity best = Rarity.COMMON;
        for (Reward w : wins) {
            String name = grant(p, w);
            got.merge(w.rarity().color + name + "<reset>", 1, Integer::sum);
            ItemStack ic = display(w);
            appendLore(ic, List.of("", "<gray>Редкость: " + w.rarity().label));
            icons.add(ic);
            if (w.rarity().ordinal() > best.ordinal()) best = w.rarity();
            if (w.rarity().ordinal() >= Rarity.LEGENDARY.ordinal())
                Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<white>" + AnarchyCore.esc(p.getName()) + " <gray>выбил из " + c.display
                        + " <gray>" + w.rarity().label + "<gray>: " + w.rarity().color + "<bold>" + name));
        }
        d.casesOpened += n;
        pl.store().save(true);
        List<String> parts = new ArrayList<>();
        got.forEach((k, v) -> parts.add((v > 1 ? "<white>" + v + "x " : "") + k));
        pl.msg(p, "<gray>Открыто кейсов " + c.display + "<gray>: <white>" + n + "<gray>. Выпало: " + String.join("<gray>, ", parts));
        Menus.Menu m = new Menus.Menu(6, c.display + " <dark_gray>| открыто: " + n);
        for (int i = 0; i < icons.size() && i < 45; i++) m.set(i, icons.get(i), null);
        if (icons.size() > 45) m.set(44, Menus.icon(Material.PAPER, "<gray>И ещё " + (icons.size() - 44) + " - весь список в чате"), null);
        int left = keys(d, c.id), again = Math.min(n, left);
        m.set(45, Menus.icon(Material.ARROW, "<gray>« К кейсу"), x -> preview(p, c));
        m.set(49, Menus.icon(again > 0 ? Material.CHEST_MINECART : Material.MINECART, again > 0 ? "<gold><bold>⚡ Ещё " + again : "<red><bold>Ключи закончились",
                "<gray>Осталось ключей: <white>" + left), again > 0 ? x -> openMany(p, c, again) : null);
        p.openInventory(m.getInventory());
        switch (best) {
            case COMMON, RARE -> p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            case EPIC -> p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            default -> { p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f); }
        }
    }

    public void open(Player p, Case c) {
        PlayerData d = pl.data(p);
        if (spinningCase(p, c)) { pl.msg(p, "<gray>Этот кейс ещё крутится - дождись приза. Другие кейсы можно открывать одновременно."); return; }
        if (keys(d, c.id) <= 0 || c.rewards.isEmpty()) return;
        addKeys(d, c.id, -1);
        Spin s = new Spin();
        s.c = c;
        s.win = roll(c);
        s.inv = Bukkit.createInventory(s, 27, mm(c.display + " <dark_gray>| крутим..."));
        int steps = 38 + ThreadLocalRandom.current().nextInt(6);
        s.strip = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) s.strip.add(roll(c));
        s.strip.set(steps + 4, s.win);
        spinning.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(c.id, s);
        if (c.loc != null && c.loc.getWorld().equals(p.getWorld()) && c.loc.distanceSquared(p.getLocation()) < 400 && !busy.contains(c.id)
                && pl.getConfig().getBoolean("cases-world-animation", true)) {
            p.closeInventory();
            s.inv = null;
            worldSpin(p, s);
            return;
        }
        ItemStack mark = Menus.icon(Material.HOPPER, "<yellow>▼ Твой приз ▼");
        s.inv.setItem(4, mark);
        s.inv.setItem(22, Menus.icon(Material.END_ROD, "<yellow>▲"));
        render(s, 0);
        if (c.loc != null && c.loc.getWorld().equals(p.getWorld()) && c.loc.distanceSquared(p.getLocation()) < 400) {
            s.disp = c.loc.getWorld().spawn(c.loc.clone().add(0.5, 1.1, 0.5), org.bukkit.entity.ItemDisplay.class, d2 -> {
                d2.setPersistent(false);
                d2.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
                d2.setItemStack(display(s.strip.get(4)));
                d2.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.55f, 0.55f, 0.55f), new org.joml.AxisAngle4f()));
                d2.setGlowing(true);
            });
        }
        p.openInventory(s.inv);
        step(p, s, 0, steps);
    }

    private void render(Spin s, int offset) {
        for (int i = 0; i < 9; i++) s.inv.setItem(9 + i, display(s.strip.get(offset + i)));
        Material[] cyc = {Material.CYAN_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE};
        for (int i = 0; i < 9; i++) {
            if (i != 4) s.inv.setItem(i, Menus.icon(cyc[(i + offset) % cyc.length], " "));
            if (i != 4) s.inv.setItem(18 + i, Menus.icon(cyc[(i + offset + 2) % cyc.length], " "));
        }
    }

    private void step(Player p, Spin s, int i, int steps) {
        if (s.done) return;
        if (!p.isOnline()) { finish(p, s); return; }
        render(s, i);
        if (s.disp != null && s.disp.isValid()) {
            s.disp.setItemStack(display(s.strip.get(i + 4)));
            if (s.c.loc != null) s.c.loc.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, s.c.loc.clone().add(0.5, 1.2, 0.5), 6, 0.3, 0.3, 0.3, 0.5);
        }
        float pitch = 0.6f + 1.2f * i / steps;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, pitch);
        if (i >= steps) {
            for (int k = 0; k < 9; k++) if (k != 4) s.inv.setItem(9 + k, Menus.icon(Material.BLACK_STAINED_GLASS_PANE, " "));
            for (int k = 0; k < 9; k++) { if (k != 4) s.inv.setItem(k, Menus.icon(s.win.rarity().pane, " ")); s.inv.setItem(18 + k, Menus.icon(s.win.rarity().pane, " ")); }
            finish(p, s);
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == s) preview(p, s.c); }, 50);
            return;
        }
        double t = (double) i / steps;
        long delay = Math.max(1, Math.round(1 + 9 * t * t * t));
        Bukkit.getScheduler().runTaskLater(pl, () -> step(p, s, i + 1, steps), delay);
    }

    private void finish(Player p, Spin s) {
        if (s.done) return;
        s.done = true;
        Map<String, Spin> mine = spinning.get(p.getUniqueId());
        if (mine != null) { mine.remove(s.c.id, s); if (mine.isEmpty()) spinning.remove(p.getUniqueId()); }
        Reward w = s.win;
        if (s.disp != null && s.disp.isValid()) {
            org.bukkit.entity.ItemDisplay dd = s.disp;
            dd.setItemStack(display(w));
            dd.setInterpolationDelay(0); dd.setInterpolationDuration(10);
            dd.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.35f, 0), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.9f, 0.9f, 0.9f), new org.joml.AxisAngle4f()));
            dd.setGlowColorOverride(w.rarity().fw);
            dd.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, dd.getLocation().add(0, 0.4, 0), 30, 0.3, 0.4, 0.3, 0.3);
            org.bukkit.entity.TextDisplay label = dd.getWorld().spawn(dd.getLocation().add(0, 1.0, 0), org.bukkit.entity.TextDisplay.class, t -> {
                t.setPersistent(false); t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                t.text(mm("<white>" + (p == null ? "Демо" : AnarchyCore.esc(p.getName())) + "\n" + w.rarity().color + "<bold>" + name(w)));
            });
            Bukkit.getScheduler().runTaskLater(pl, () -> { dd.remove(); label.remove(); }, 70);
            s.disp = null;
        }
        String name = grant(p, w);
        PlayerData d = pl.data(p);
        d.casesOpened++;
        pl.store().save(true);
        if (!p.isOnline()) return;
        p.showTitle(Title.title(mm(w.rarity().color + "<bold>" + w.rarity().label.replaceAll("<[^>]+>", "").toUpperCase(Locale.ROOT)),
                mm("<white>" + name), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        pl.msg(p, "<gray>Из кейса " + s.c.display + " <gray>выпало: " + w.rarity().color + name + " <dark_gray>(" + w.rarity().label + "<dark_gray>)");
        switch (w.rarity()) {
            case COMMON, RARE -> p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            case EPIC -> p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            default -> { p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f); }
        }
        if (w.rarity().ordinal() >= Rarity.EPIC.ordinal()) {
            Location fl = s.c.loc != null && s.c.loc.getWorld().equals(p.getWorld()) ? s.c.loc.clone().add(0.5, 1.2, 0.5) : p.getLocation();
            Firework fw = (Firework) fl.getWorld().spawnEntity(fl, EntityType.FIREWORK_ROCKET);
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(w.rarity().fw, Color.AQUA).withFade(Color.WHITE).trail(true).flicker(true).build());
            fm.setPower(1);
            fw.setFireworkMeta(fm);
            fw.setMetadata("aqua_safe", new org.bukkit.metadata.FixedMetadataValue(pl, true));
        }
        if (w.rarity().ordinal() >= Rarity.LEGENDARY.ordinal()) {
            Bukkit.broadcast(mm(pl.getConfig().getString("prefix", "") + "<white>" + AnarchyCore.esc(p.getName()) + " <gray>выбил из " + s.c.display
                    + " <gray>" + w.rarity().label + "<gray>: " + w.rarity().color + "<bold>" + name));
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.playSound(o.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Spin) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        Map<String, Spin> mine = spinning.get(e.getPlayer().getUniqueId());
        if (mine != null) for (Spin s : new ArrayList<>(mine.values())) finish(e.getPlayer(), s);   // вышел во время прокрутки - призы выдаются сразу, до сохранения инвентаря
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Spin) e.setCancelled(true); }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Spin s && !s.done && e.getPlayer() instanceof Player p) {
            if (e.getReason() == InventoryCloseEvent.Reason.DISCONNECT) finish(p, s);   // выход с сервера - выдаём сейчас, через тик игрок уже сохранён
            else Bukkit.getScheduler().runTask(pl, () -> finish(p, s));
        }
    }

    public void shutdown() {
        for (Map.Entry<UUID, Map<String, Spin>> en : new ArrayList<>(spinning.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) for (Spin s : new ArrayList<>(en.getValue().values())) finish(p, s);
        }
        for (org.bukkit.entity.Entity e : new ArrayList<>(animEntities)) e.remove();
        animEntities.clear();
    }

    // ================= Анимация прокрута в мире (в стиле HolyWorld) =================
    // Предметы кружатся хороводом вокруг кейса в кольце частиц, замедляются - приз останавливается напротив игрока.
    private final Set<String> busy = new HashSet<>();
    private final Set<org.bukkit.entity.Entity> animEntities = new HashSet<>();

    private record Style(Particle main, Particle accent, Color c1, Color c2, Sound tick, Sound start, double radius, boolean twoRings) {}

    private static Style style(String id) {
        return switch (id) {
            case "vortex" -> new Style(Particle.SOUL_FIRE_FLAME, Particle.WITCH, Color.fromRGB(0xB000FF), Color.fromRGB(0xFF0077),
                    Sound.BLOCK_TRIAL_SPAWNER_AMBIENT_OMINOUS, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.7, false);
            case "portal" -> new Style(Particle.REVERSE_PORTAL, Particle.PORTAL, Color.fromRGB(0x7A00FF), Color.fromRGB(0x00E5FF),
                    Sound.BLOCK_AMETHYST_BLOCK_CHIME, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.8, true);
            case "snow" -> new Style(Particle.SNOWFLAKE, Particle.END_ROD, Color.fromRGB(0xFFFFFF), Color.fromRGB(0xFF1744),
                    Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.ENTITY_PLAYER_LEVELUP, 1.6, false);
            default -> new Style(Particle.END_ROD, Particle.WAX_ON, Color.fromRGB(0x00E5FF), Color.fromRGB(0xFF55DD),
                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE, Sound.BLOCK_BEACON_ACTIVATE, 1.6, false);
        };
    }

    /** Демо анимации для админа (без ключа и награды). */
    public boolean demo(Case c) {
        if (c.loc == null || busy.contains(c.id) || c.rewards.isEmpty()) return false;
        Spin s = new Spin(); s.c = c; s.win = roll(c);
        worldSpin(null, s);
        return true;
    }

    private void worldSpin(Player p, Spin s) {
        Case c = s.c;
        busy.add(c.id);
        Style st = style(c.anim);
        World w = c.loc.getWorld();
        Location center = c.loc.clone().add(0.5, 0, 0.5);
        double ringY = 1.55;
        int n = 8, T = 130;
        // направление на игрока - туда в конце встанет приз
        org.bukkit.util.Vector toP = p == null ? new org.bukkit.util.Vector(0, 0, 1) : p.getLocation().toVector().subtract(center.toVector()).setY(0);
        double front = toP.lengthSquared() < 0.01 ? 0 : Math.atan2(toP.getZ(), toP.getX());
        double total = Math.PI * 2 * (3 + ThreadLocalRandom.current().nextInt(2)) + ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double step = Math.PI * 2 / n;
        // какой слот окажется ближе всего к игроку
        int winIdx = 0; double best = 9;
        for (int i = 0; i < n; i++) {
            if (st.twoRings() && i % 2 == 1) continue;   // приз - на внешнем кольце
            double a = total + i * step - front;
            a = Math.atan2(Math.sin(a), Math.cos(a));
            if (Math.abs(a) < best) { best = Math.abs(a); winIdx = i; }
        }
        double correction = -(Math.atan2(Math.sin(total + winIdx * step - front), Math.cos(total + winIdx * step - front)));
        double fTotal = total + correction;   // точная остановка напротив игрока
        List<org.bukkit.entity.ItemDisplay> ds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Reward r = i == winIdx ? s.win : roll(c);
            double a = i * step;
            double rad = st.twoRings() && i % 2 == 1 ? st.radius() - 0.45 : st.radius();
            Location l = center.clone().add(Math.cos(a) * rad, ringY, Math.sin(a) * rad);
            org.bukkit.entity.ItemDisplay d = w.spawn(l, org.bukkit.entity.ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
                e.setItemStack(display(r));
                e.setTeleportDuration(2);
                e.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.01f, 0.01f, 0.01f), new org.joml.AxisAngle4f()));
            });
            ds.add(d); animEntities.add(d);
            // появление: из точки в нормальный размер
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (!d.isValid()) return;
                d.setInterpolationDelay(0); d.setInterpolationDuration(8);
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.AxisAngle4f()));
            }, 2 + i);
        }
        // стрелка-указатель: стоит напротив игрока, под ней остановится приз
        Location ptrLoc = center.clone().add(Math.cos(front) * st.radius(), ringY + 0.62, Math.sin(front) * st.radius());
        org.bukkit.entity.TextDisplay ptr = w.spawn(ptrLoc, org.bukkit.entity.TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            e.text(mm("<yellow><bold>▼</bold>"));
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            e.setTeleportDuration(2);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(2.2f, 2.2f, 2.2f), new org.joml.AxisAngle4f()));
        });
        animEntities.add(ptr);
        w.playSound(center, st.start(), 1f, 1.2f);
        Particle.DustOptions d1 = new Particle.DustOptions(st.c1(), 1.1f), d2 = new Particle.DustOptions(st.c2(), 0.9f);
        int fw = winIdx;
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0, lastSector = 0;
            @Override public void run() {
                if (s.done && t < T) { cleanup(); cancel(); return; }
                t++;
                double k = Math.min(1, (double) t / T);
                double ang = fTotal * (1 - Math.pow(1 - k, 3));     // плавное замедление
                for (int i = 0; i < n; i++) {
                    org.bukkit.entity.ItemDisplay d = ds.get(i);
                    if (!d.isValid()) continue;
                    double rad = st.twoRings() && i % 2 == 1 ? st.radius() - 0.45 : st.radius();
                    double a = (st.twoRings() && i % 2 == 1 ? -ang : ang) + i * step;   // внутреннее кольцо крутится навстречу
                    double bob = Math.sin(t * 0.25 + i) * 0.06;
                    d.teleport(center.clone().add(Math.cos(a) * rad, ringY + bob, Math.sin(a) * rad));
                }
                // звук "щелчка", когда предмет проходит мимо игрока
                int sector = (int) Math.floor(ang / step);
                if (sector != lastSector) { lastSector = sector; w.playSound(center, st.tick(), 0.6f, (float) (0.7 + 1.1 * k)); }
                ringParticles(w, center, st, d1, d2, t, ringY);
                // стрелка подпрыгивает и мигает, к концу - цветом редкости ближайшего предмета
                if (ptr.isValid() && t % 2 == 0) {
                    ptr.teleport(ptrLoc.clone().add(0, Math.abs(Math.sin(t * 0.2)) * 0.12, 0));
                    w.spawnParticle(Particle.DUST, ptrLoc.clone().add(0, -0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.YELLOW, 0.8f));
                }
                if (t >= T) {
                    cancel();
                    // приз под стрелкой: подсветка и название
                    org.bukkit.entity.ItemDisplay wd = ds.get(fw);
                    if (wd.isValid()) {
                        wd.setGlowing(true); wd.setGlowColorOverride(s.win.rarity().fw);
                        wd.setInterpolationDelay(0); wd.setInterpolationDuration(6);
                        wd.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0.85f, 0.85f, 0.85f), new org.joml.AxisAngle4f()));
                    }
                    if (ptr.isValid()) {
                        ptr.teleport(ptrLoc.clone().add(0, 0.15, 0));
                        ptr.text(mm(s.win.rarity().color + "<bold>" + name(s.win) + "</bold>\n<yellow><bold>▼</bold>"));
                        ptr.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(1.3f, 1.3f, 1.3f), new org.joml.AxisAngle4f()));
                    }
                    w.spawnParticle(Particle.DUST, wd.getLocation(), 20, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(s.win.rarity().fw, 1.3f));
                    w.playSound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
                    Bukkit.getScheduler().runTaskLater(pl, () -> { ptr.remove(); animEntities.remove(ptr); reveal(p, s, ds, fw, center, st); }, 25);
                }
            }
            void cleanup() { for (org.bukkit.entity.ItemDisplay d : ds) { d.remove(); animEntities.remove(d); } ptr.remove(); animEntities.remove(ptr); busy.remove(c.id); }
        }.runTaskTimer(pl, 1, 1);
    }

    /** Круг частиц вокруг кейса - у каждого стиля свой. */
    private void ringParticles(World w, Location c, Style st, Particle.DustOptions d1, Particle.DustOptions d2, int t, double ringY) {
        double r = st.radius() + 0.35;
        int pts = 28;
        // основное кольцо на полу и бегущие огоньки
        if (t % 2 == 0) for (int i = 0; i < pts; i++) {
            double a = i * Math.PI * 2 / pts + t * 0.04;
            w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.08, Math.sin(a) * r), 1, 0, 0, 0, 0, i % 2 == 0 ? d1 : d2);
        }
        for (int j = 0; j < 3; j++) {
            double a = t * 0.18 + j * Math.PI * 2 / 3;
            w.spawnParticle(st.main(), c.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0);
        }
        switch (st.main().name()) {
            case "SOUL_FIRE_FLAME" -> {   // вихрь: двойная спираль поднимается вверх
                for (int j = 0; j < 2; j++) {
                    double a = t * 0.3 + j * Math.PI, y = (t % 30) / 30.0 * 2.6;
                    double rr = r * (1 - y / 4);
                    w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * rr, y, Math.sin(a) * rr), 2, 0.02, 0.02, 0.02, 0, j == 0 ? d1 : d2);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(Math.cos(a) * rr, y, Math.sin(a) * rr), 1, 0, 0, 0, 0);
                }
                if (t % 4 == 0) w.spawnParticle(Particle.WITCH, c.clone().add(0, ringY, 0), 4, r * 0.6, 0.2, r * 0.6, 0);
            }
            case "REVERSE_PORTAL" -> {   // портал: второе кольцо на высоте предметов и частицы, стекающиеся в центр
                if (t % 2 == 0) for (int i = 0; i < 16; i++) {
                    double a = -(i * Math.PI * 2 / 16) - t * 0.06;
                    w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * (r - 0.9), ringY + 0.75, Math.sin(a) * (r - 0.9)), 1, 0, 0, 0, 0, d2);
                }
                w.spawnParticle(Particle.REVERSE_PORTAL, c.clone().add(0, ringY, 0), 6, 0.6, 0.6, 0.6, 0.05);
                if (t % 5 == 0) w.spawnParticle(Particle.PORTAL, c.clone().add(0, 0.2, 0), 10, r * 0.5, 0, r * 0.5, 0.3);
            }
            case "SNOWFLAKE" -> {   // снег: падающие снежинки и красно-белая спираль
                w.spawnParticle(Particle.SNOWFLAKE, c.clone().add(0, 3, 0), 3, r * 0.7, 0.2, r * 0.7, 0);
                double a = t * 0.25, y = (t % 25) / 25.0 * 2.2;
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r * 0.9, y, Math.sin(a) * r * 0.9), 1, 0, 0, 0, 0, d2);
                w.spawnParticle(Particle.DUST, c.clone().add(-Math.cos(a) * r * 0.9, y, -Math.sin(a) * r * 0.9), 1, 0, 0, 0, 0, d1);
            }
            default -> {   // аметист: кольцо на высоте предметов + искры
                if (t % 3 == 0) for (int i = 0; i < 20; i++) {
                    double a = i * Math.PI * 2 / 20 - t * 0.05;
                    w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * (r - 0.15), ringY - 0.45, Math.sin(a) * (r - 0.15)), 1, 0, 0, 0, 0, d2);
                }
                if (t % 3 == 0) w.spawnParticle(Particle.WAX_ON, c.clone().add(0, ringY, 0), 2, r * 0.7, 0.4, r * 0.7, 0.5);
                if (t % 6 == 0) w.spawnParticle(Particle.END_ROD, c.clone().add(0, 0.3, 0), 2, 0.2, 0.1, 0.2, 0.06);
            }
        }
    }

    /** Финал: остальные предметы растворяются, приз всплывает над кейсом. */
    private void reveal(Player p, Spin s, List<org.bukkit.entity.ItemDisplay> ds, int winIdx, Location center, Style st) {
        World w = center.getWorld();
        org.bukkit.entity.ItemDisplay win = ds.get(winIdx);
        for (int i = 0; i < ds.size(); i++) {
            org.bukkit.entity.ItemDisplay d = ds.get(i);
            if (i == winIdx || !d.isValid()) continue;
            d.setInterpolationDelay(0); d.setInterpolationDuration(8);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(0, 0, 0), new org.joml.AxisAngle4f()));
            w.spawnParticle(Particle.POOF, d.getLocation(), 4, 0.1, 0.1, 0.1, 0.02);
            Bukkit.getScheduler().runTaskLater(pl, () -> { d.remove(); animEntities.remove(d); }, 9);
        }
        Color rc = s.win.rarity().fw;
        Particle.DustOptions rd = new Particle.DustOptions(rc, 1.4f);
        w.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.4f);
        // приз плывёт в центр над кейсом и вырастает
        if (win.isValid()) {
            win.setTeleportDuration(12);
            win.teleport(center.clone().add(0, 1.9, 0));
            win.setGlowing(true);
            win.setGlowColorOverride(rc);
            win.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
            win.setInterpolationDelay(0); win.setInterpolationDuration(12);
            win.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(1.0f, 1.0f, 1.0f), new org.joml.AxisAngle4f()));
        }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            // вспышка: расходящееся кольцо цвета редкости
            new org.bukkit.scheduler.BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    double r = 0.3 + t * 0.25;
                    for (int i = 0; i < 36; i++) { double a = i * Math.PI * 2 / 36; w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * r, 1.9, Math.sin(a) * r), 1, 0, 0, 0, 0, rd); }
                    if (++t > 10) cancel();
                }
            }.runTaskTimer(pl, 0, 1);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, center.clone().add(0, 1.9, 0), 40, 0.3, 0.4, 0.3, 0.35);
            w.spawnParticle(st.accent(), center.clone().add(0, 1.9, 0), 25, 0.5, 0.5, 0.5, 0.05);
            org.bukkit.entity.TextDisplay label = w.spawn(center.clone().add(0, 2.75, 0), org.bukkit.entity.TextDisplay.class, t -> {
                t.setPersistent(false); t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                t.text(mm("<white>" + (p == null ? "Демо" : AnarchyCore.esc(p.getName())) + "\n" + s.win.rarity().color + "<bold>" + name(s.win)));
            });
            animEntities.add(label);
            if (p != null) finish(p, s); else s.done = true;
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (win.isValid()) { w.spawnParticle(Particle.END_ROD, win.getLocation(), 15, 0.2, 0.2, 0.2, 0.08); win.remove(); }
                label.remove(); animEntities.remove(win); animEntities.remove(label);
                busy.remove(s.c.id);
            }, 70);
        }, 13);
    }

    // ---------- награды ----------
    private static void appendLore(ItemStack it, List<String> extra) {
        ItemMeta m = it.getItemMeta();
        List<Component> l = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        for (String s : extra) l.add(mm("<!italic>" + s));
        m.lore(l);
        it.setItemMeta(m);
    }

    public String name(Reward r) {
        String[] p = r.spec().split(":");
        return switch (p[0]) {
            case "rank" -> { Rank k = pl.rankById(p[1]); yield "Привилегия " + (k == null ? p[1] : k.display()); }
            case "ari" -> p[1] + " " + pl.ariName() + " " + pl.ariSym();
            case "coins" -> p[1] + " " + pl.cur();
            case "title" -> "Титул " + pl.getConfig().getString("titles." + p[1] + ".display", p[1]).replaceAll("<[^>]+>", "");
            case "buff" -> { CustomItems.Buff b = CustomItems.BUFFS.get(p[1]); yield "Вечный баф " + (b == null ? p[1] : b.name()); }
            case "spawner" -> "Спавнер " + p[1].toLowerCase(Locale.ROOT);
            case "key" -> { Case c = get(p[1]); yield (p.length > 2 ? p[2] : "1") + " ключ: " + (c == null ? p[1] : c.display.replaceAll("<[^>]+>", "")); }
            case "custom" -> { ItemStack it = pl.items().create(p[1]); yield it == null ? p[1] : plain(it); }
            case "enchant" -> "Книга: " + pl.enchants().name(p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1);
            case "boost" -> "Буст x2 " + (p[1].equals("coins") ? "монет" : "опыта пропуска") + " для всех";
            case "region" -> { ItemStack it = pl.regions().item(p[1]); yield it == null ? p[1] : plain(it); }
            default -> { ItemStack it = item(p); yield it == null ? r.spec() : (it.getAmount() + "x " + it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')); }
        };
    }

    private static String plain(ItemStack it) {
        Component n = it.getItemMeta().displayName();
        return n == null ? it.getType().name() : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(n);
    }

    private ItemStack item(String[] p) {
        Material m = Material.matchMaterial(p[0]);
        return m == null ? null : new ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
    }

    public ItemStack display(Reward r) {
        String[] p = r.spec().split(":");
        ItemStack it;
        String nm = r.rarity().color + "<bold>" + name(r);
        switch (p[0]) {
            case "rank" -> { Rank k = pl.rankById(p[1]); Material m = k == null ? null : Material.matchMaterial(k.icon()); it = Menus.icon(m == null ? Material.GOLDEN_HELMET : m, nm, "<gray>Навсегда. Если уже есть ранг", "<gray>выше - компенсация " + pl.ariName()); }
            case "ari" -> { it = Menus.icon(Material.AMETHYST_SHARD, nm, "<gray>Сразу на баланс"); it.setAmount(Math.max(1, Math.min(64, Integer.parseInt(p[1])))); }
            case "coins" -> it = Menus.icon(Material.GOLD_INGOT, nm, "<gray>Сразу на баланс");
            case "title" -> it = Menus.icon(Material.NAME_TAG, nm, "<gray>Вид: " + pl.getConfig().getString("titles." + p[1] + ".display", p[1]), "<gray>Только из кейса!", "<gray>Повтор - компенсация монетами");
            case "buff" -> { CustomItems.Buff b = CustomItems.BUFFS.get(p[1]); it = Menus.icon(b == null ? Material.POTION : b.icon(), nm, "<gray>Действует всегда"); }
            case "spawner" -> it = Menus.icon(Material.SPAWNER, nm);
            case "key" -> it = Menus.icon(Material.TRIPWIRE_HOOK, nm);
            case "custom" -> { it = pl.items().create(p[1]); if (it == null) it = Menus.icon(Material.BARRIER, nm); }
            case "enchant" -> { it = pl.enchants().book(p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1); if (it == null) it = Menus.icon(Material.BARRIER, nm); }
            case "boost" -> it = Menus.icon(Material.BEACON, nm, "<gray>Активируется сразу на 1 час");
            case "region" -> { it = pl.regions().item(p[1]); if (it == null) it = Menus.icon(Material.BARRIER, nm); }
            default -> { it = item(p); if (it == null) it = Menus.icon(Material.BARRIER, nm); else { ItemMeta m = it.getItemMeta(); m.displayName(mm("<!italic>" + nm)); it.setItemMeta(m); } }
        }
        it = it.clone();
        ItemMeta m = it.getItemMeta();
        m.addItemFlags(ItemFlag.values());
        if (r.rarity().ordinal() >= Rarity.EPIC.ordinal()) m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** Выдаёт награду, возвращает её название. */
    public String grant(Player p, Reward r) {
        PlayerData d = pl.data(p);
        String[] a = r.spec().split(":");
        String name = name(r);
        try {
            switch (a[0]) {
                case "rank" -> {
                    Rank k = pl.rankById(a[1]);
                    if (k == null) break;
                    if (pl.rank(d).weight() >= k.weight()) {
                        long comp = Math.max(5, k.priceAri() / 3);
                        d.ari += comp;
                        pl.msg(p, "<gray>У тебя уже есть этот ранг или выше - компенсация <light_purple>+" + comp + " " + pl.ariName());
                        name += " (+" + comp + " " + pl.ariName() + ")";
                    } else {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rank set " + d.name + " " + k.id());
                    }
                }
                case "ari" -> d.ari += Long.parseLong(a[1]);
                case "coins" -> d.balance += Long.parseLong(a[1]);
                case "title" -> {
                    if (d.titles.contains(a[1])) {
                        long comp = pl.getConfig().getLong("cases-duplicate." + r.rarity().name().toLowerCase(Locale.ROOT), 300);
                        d.balance += comp;
                        pl.msg(p, "<gray>Этот титул уже есть - компенсация <gold>+" + comp + " " + pl.cur());
                        name += " (+" + comp + " " + pl.cur() + ")";
                    } else {
                        d.titles.add(a[1]);
                        d.title = a[1];
                        pl.msg(p, "<green>Новый титул надет! <gray>Сменить: /titles");
                        pl.applyTeam(p);
                    }
                }
                case "buff" -> {
                    if (d.buffs.contains(a[1])) { d.balance += 1000; pl.msg(p, "<gray>Баф уже куплен - компенсация <gold>+1000 " + pl.cur()); }
                    else d.buffs.add(a[1]);
                }
                case "spawner" -> give(p, pl.items().spawner(EntityType.valueOf(a[1].toUpperCase(Locale.ROOT))));
                case "key" -> addKeys(d, a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1);
                case "custom" -> { ItemStack it = pl.items().create(a[1]); if (it != null) give(p, it); }
                case "enchant" -> { ItemStack it = pl.enchants().book(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1); if (it != null) give(p, it); }
                case "boost" -> pl.boosts().activate(p.getName(), a[1].equals("coins"), 60);
                case "region" -> { ItemStack it = pl.regions().item(a[1]); if (it != null) give(p, it); }
                default -> { ItemStack it = item(a); if (it != null) give(p, it); }
            }
        } catch (Exception e) { pl.getLogger().warning("Награда кейса " + r.spec() + ": " + e); }
        return name;
    }

    // ---------- частицы у кейсов ----------
    private int tick;
    private void particles() {
        tick++;
        for (Case c : cases.values()) {
            if (c.loc == null || c.loc.getWorld().getPlayers().isEmpty()) continue;
            Location b = c.loc.clone().add(0.5, 0, 0.5);
            if (!b.isChunkLoaded()) continue;
            for (int i = 0; i < 2; i++) {
                double a = tick * 0.35 + i * Math.PI;
                double y = 0.2 + (tick % 20) / 20.0 * 1.6;
                b.getWorld().spawnParticle(Particle.END_ROD, b.clone().add(Math.cos(a) * 0.85, y, Math.sin(a) * 0.85), 1, 0, 0, 0, 0);
            }
            b.getWorld().spawnParticle(Particle.DUST, b.clone().add(0, 1.3, 0), 3, 0.35, 0.15, 0.35, 0, new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1.1f));
        }
    }
}
