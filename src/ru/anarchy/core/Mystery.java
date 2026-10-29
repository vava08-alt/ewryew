package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

/**
 * «Неизвестный дроп» (как на FunTime): в сундуке ивента лежат закрытые предметы «???»,
 * настоящий предмет игрок видит только когда забрал его в свой инвентарь.
 * Сундук пополняется постепенно: по одному предмету раз в drip-seconds (0.5 = 2 предмета в секунду).
 * Настоящие предметы хранятся на сервере (клиент их не видит).
 */
public final class Mystery implements Listener {
    private final AnarchyCore pl;
    private final NamespacedKey KEY, FILL;
    private final Map<String, ItemStack> real = new HashMap<>();

    /** Очередь выдачи для сундука ивента */
    public static final class Feeder {
        public final Block chest; public final Inventory custom; public final Deque<ItemStack> queue; public final long intervalMs; public final Events.Tier tier; long next;
        Feeder(Block chest, Inventory custom, List<ItemStack> items, long intervalMs, Events.Tier tier) { this.chest = chest; this.custom = custom; this.queue = new ArrayDeque<>(items); this.intervalMs = intervalMs; this.tier = tier; }
        /** Свой инвентарь (например, 54 слота у аирдропа) или инвентарь самого сундука */
        Inventory inv() { if (custom != null) return custom; return chest.getState() instanceof Chest c ? c.getBlockInventory() : null; }
        public int left() { return queue.size(); }
        public long nextIn() { return Math.max(0, next - System.currentTimeMillis()); }
    }
    private final Map<String, Feeder> feeders = new HashMap<>();

    public Mystery(AnarchyCore pl) {
        this.pl = pl;
        KEY = new NamespacedKey(pl, "mystery");
        FILL = new NamespacedKey(pl, "mystery_fill");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20, 5);
    }

    // ---------- предметы ----------
    public ItemStack wrap(ItemStack it, Events.Tier t) {
        String id = UUID.randomUUID().toString();
        real.put(id, it.clone());
        ItemStack m = new ItemStack(Material.PAPER);
        ItemMeta meta = m.getItemMeta();
        meta.displayName(mm("<!italic>" + t.grad + "<bold>❓ Неизвестный предмет</bold></gradient>"));
        meta.lore(List.of(mm("<!italic><gray>Забери в инвентарь,"), mm("<!italic><gray>чтобы узнать, что выпало"), mm("<!italic><dark_gray>Редкость: " + t.tag())));
        meta.setEnchantmentGlintOverride(t.ordinal() >= 2);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        m.setItemMeta(meta);
        return m;
    }
    /** Заглушка в свободных слотах сундука (вытащить нельзя, потом заменяется на «???») */
    public ItemStack filler(Events.Tier t) {
        ItemStack m = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = m.getItemMeta();
        meta.displayName(mm("<!italic><dark_gray>Ожидание..."));
        meta.lore(List.of(mm("<!italic><gray>Скоро здесь появится"), mm("<!italic>" + t.grad + "неизвестный предмет</gradient>")));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(FILL, PersistentDataType.BYTE, (byte) 1);
        m.setItemMeta(meta);
        return m;
    }
    public boolean isFiller(ItemStack it) { return it != null && it.getType() == Material.GRAY_STAINED_GLASS_PANE && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(FILL, PersistentDataType.BYTE); }
    /** Есть ли в инвентаре что забрать (заглушки не считаются) */
    public boolean hasLoot(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir() && !isFiller(it)) return true;
        return false;
    }
    public boolean isMystery(ItemStack it) { return it != null && it.getType() == Material.PAPER && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.STRING); }
    /** Настоящий предмет (и забыть его) или null */
    private ItemStack unwrap(ItemStack it) {
        if (!isMystery(it)) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        ItemStack r = real.remove(id);
        return r != null ? r : new ItemStack(Material.AIR);
    }

    /** Раскрыть все «???» в инвентаре и на курсоре игрока */
    public void reveal(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isFiller(it)) { inv.setItem(i, null); continue; }
            ItemStack r = unwrap(it);
            if (r == null) continue;
            inv.setItem(i, null);
            if (r.getType().isAir()) continue;
            announce(p, r);
            for (ItemStack left : inv.addItem(r).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        ItemStack cur = p.getItemOnCursor();
        if (isFiller(cur)) { p.setItemOnCursor(null); return; }
        ItemStack r = unwrap(cur);
        if (r != null) { p.setItemOnCursor(r.getType().isAir() ? null : r); if (!r.getType().isAir()) announce(p, r); }
    }
    private void announce(Player p, ItemStack r) {
        net.kyori.adventure.text.Component name = r.getItemMeta() != null && r.getItemMeta().hasDisplayName() ? r.getItemMeta().displayName() : net.kyori.adventure.text.Component.translatable(r.translationKey());
        p.sendActionBar(mm("<gray>Тебе выпало: ").append(name).append(mm(" <white>x" + r.getAmount())));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.4f);
    }

    // ---------- постепенное пополнение ----------
    public Feeder start(String key, Block chest, List<ItemStack> items, double intervalSec, Events.Tier t) { return start(key, chest, null, items, intervalSec, t); }

    public Feeder start(String key, Block chest, Inventory custom, List<ItemStack> items, double intervalSec, Events.Tier t) {
        long iv = Math.max(250, (long) (intervalSec * 1000));
        double maxSec = pl.getConfig().getDouble("events.drip-max-seconds", 100);   // большой лут выдаётся быстрее, чтобы успеть до исчезновения сундука
        if (maxSec > 0 && !items.isEmpty()) iv = Math.max(250, Math.min(iv, (long) (maxSec * 1000 / items.size())));
        Feeder f = new Feeder(chest, custom, items, iv, t);
        f.next = 0;
        feeders.put(key, f);
        Inventory inv = f.inv();
        if (inv != null) {
            inv.clear();
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler(t));
        }
        return f;
    }
    public Feeder feeder(String key) { return feeders.get(key); }
    public void stop(String key) { feeders.remove(key); }

    /** Пока лут не выдан до конца, забранные слоты снова становятся заглушками (и потом опять дают предмет) */
    private void refill(Feeder f) {
        Inventory inv = f.queue.isEmpty() ? null : f.inv();
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) { ItemStack it = inv.getItem(i); if (it == null || it.getType().isAir()) inv.setItem(i, filler(f.tier)); }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Feeder f : feeders.values()) {
            refill(f);
            if (f.queue.isEmpty() || now + 100 < f.next) continue;   // допуск меньше периода тика (250 мс), иначе интервал 0.5 с растягивается до 0.75 с
            Inventory inv = f.inv();
            if (inv == null) continue;
            List<Integer> free = new ArrayList<>(), fill = new ArrayList<>();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (isFiller(it)) fill.add(i); else if (it == null || it.getType().isAir()) free.add(i);
            }
            if (!fill.isEmpty()) free = fill;   // сначала заменяем заглушки
            if (free.isEmpty()) { f.next = now + f.intervalMs; continue; }
            inv.setItem(free.get(ThreadLocalRandom.current().nextInt(free.size())), wrap(f.queue.poll(), f.tier));
            f.next = now + f.intervalMs;
            if (f.queue.isEmpty()) for (int i = 0; i < inv.getSize(); i++) if (isFiller(inv.getItem(i))) inv.setItem(i, null);  // всё выдали - убираем заглушки
            Location l = f.chest.getLocation().add(0.5, 1.0, 0.5);
            l.getWorld().spawnParticle(f.tier.particle, l, 10, 0.25, 0.2, 0.25, 0.02);
            l.getWorld().playSound(l, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        }
    }

    // ---------- раскрытие при попадании в инвентарь ----------
    private void later(org.bukkit.entity.HumanEntity h) {
        if (h instanceof Player p) Bukkit.getScheduler().runTask(pl, () -> { reveal(p); for (Feeder f : feeders.values()) refill(f); });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFillerClick(InventoryClickEvent e) {
        ItemStack hot = e.getHotbarButton() >= 0 ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : null;
        if (isFiller(e.getCurrentItem()) || isFiller(e.getCursor()) || isFiller(hot)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) { later(e.getWhoClicked()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) { later(e.getWhoClicked()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) { later(e.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack r = unwrap(e.getItem().getItemStack());
        if (r == null) return;
        if (r.getType().isAir()) { e.setCancelled(true); e.getItem().remove(); return; }
        e.getItem().setItemStack(r);
        if (e.getEntity() instanceof Player p) announce(p, r);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        ItemStack r = unwrap(e.getEntity().getItemStack());
        if (r == null) return;
        if (r.getType().isAir()) e.setCancelled(true); else e.getEntity().setItemStack(r);
    }
    /** Воронки/вагонетки не вытащат «???» - отменяем */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) { if (isMystery(e.getItem()) || isFiller(e.getItem())) e.setCancelled(true); }
}
