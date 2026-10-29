package ru.anarchy.core.ac;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Проверки предметов и инвентаря: кристалл- и якорь-аура, автототем, InvMove, чест-стилер, чит-клиенты. */
final class ItemChecks implements Listener {
    private final AntiCheat ac;
    private final Map<Integer, Long> crystalSpawn = new HashMap<>();        // id кристалла -> nanoTime появления
    private final Map<Integer, long[]> unresolved = new HashMap<>();        // id -> {nanos удара, uuid msb, uuid lsb, ms}
    private final Map<Long, long[]> anchors = new HashMap<>();              // блок -> {nanos, uuid msb, uuid lsb}

    ItemChecks(AntiCheat ac) { this.ac = ac; }

    private static String fmt(String f, Object... a) { return String.format(Locale.ROOT, f, a); }

    private static boolean isTotem(ItemStack it) { return it != null && it.getType() == Material.TOTEM_OF_UNDYING; }

    private static long key(Block b) {
        return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | ((long) b.getY() & 0xFFF) ^ (long) b.getWorld().getName().hashCode() << 52;
    }

    void second() {
        long now = System.currentTimeMillis(), nano = System.nanoTime();
        unresolved.values().removeIf(v -> now - v[3] > 2000);
        crystalSpawn.values().removeIf(v -> nano - v > 30_000_000_000L);
        for (Iterator<long[]> it = anchors.values().iterator(); it.hasNext(); ) if (nano - it.next()[0] > 30_000_000_000L) it.remove();
    }

    // ===================== кристаллы =====================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal) || e.getPlayer() == null) return;
        long now = System.nanoTime();
        int id = e.getEntity().getEntityId();
        if (crystalSpawn.size() > 5000) crystalSpawn.clear();
        crystalSpawn.put(id, now);
        long[] u = unresolved.remove(id);
        if (u != null && u[0] < now - 1_000_000L) {
            Player a = Bukkit.getPlayer(new UUID(u[1], u[2]));
            if (a != null) ac.flag(a, Feature.CRYSTAL, 0.8, fmt("удар по кристаллу за %.0f мс до его появления (ID-predict)", (now - u[0]) / 1e6));
        }
    }

    /** Удар по сущности, которой на сервере (ещё) нет: возможно, удар по кристаллу «наперёд». */
    void unresolved(Player p, int id, long nanos) {
        if (unresolved.size() > 2000) unresolved.clear();
        UUID u = p.getUniqueId();
        unresolved.put(id, new long[]{nanos, u.getMostSignificantBits(), u.getLeastSignificantBits(), System.currentTimeMillis()});
    }

    void crystalHit(Player p, ACPlayer d, Entity crystal, long nanos, double angle) {
        Long sp = crystalSpawn.get(crystal.getEntityId());
        if (sp != null && nanos < sp - 1_000_000L)
            ac.flag(p, d, Feature.CRYSTAL, 0.8, fmt("удар по кристаллу за %.0f мс до его появления (ID-predict)", (sp - nanos) / 1e6));
        if (angle > 25) ac.flag(p, d, Feature.CRYSTAL, 0.35, fmt("удар по кристаллу не глядя на него: %.0f° от прицела", angle));
        long now = System.currentTimeMillis();
        d.crystalHits.addLast(now);
        while (!d.crystalHits.isEmpty() && now - d.crystalHits.peekFirst() > 5000) d.crystalHits.pollFirst();
        if (d.crystalHits.size() >= 60 && now - d.crystalRateFlag > 5000) {
            d.crystalRateFlag = now;
            ac.flag(p, d, Feature.CRYSTAL, 0.3, fmt("%d ударов по кристаллам за 5 секунд", d.crystalHits.size()));
        }
    }

    // ===================== якоря =====================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnchorPlace(BlockPlaceEvent e) {
        if (e.getBlock().getType() != Material.RESPAWN_ANCHOR) return;
        if (anchors.size() > 2000) anchors.clear();
        UUID u = e.getPlayer().getUniqueId();
        anchors.put(key(e.getBlock()), new long[]{System.nanoTime(), u.getMostSignificantBits(), u.getLeastSignificantBits()});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnchorUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || b == null || b.getType() != Material.RESPAWN_ANCHOR) return;
        if (e.useInteractedBlock() == Event.Result.DENY || b.getWorld().getEnvironment() == World.Environment.NETHER) return;
        if (!(b.getBlockData() instanceof RespawnAnchor ra) || ra.getCharges() == 0) return;
        ItemStack it = e.getItem();
        if (it != null && it.getType() == Material.GLOWSTONE && ra.getCharges() < ra.getMaximumCharges()) return;   // это зарядка
        long[] a = anchors.remove(key(b));
        if (a == null) return;
        Player p = e.getPlayer();
        if (a[1] != p.getUniqueId().getMostSignificantBits() || a[2] != p.getUniqueId().getLeastSignificantBits()) return;
        double ms = (System.nanoTime() - a[0]) / 1e6;
        if (ms < 60) ac.flag(p, Feature.ANCHOR, 0.5, fmt("якорь поставлен, заряжен и взорван за %.0f мс", ms));
        else if (ms < 110) ac.flag(p, Feature.ANCHOR, 0.2, fmt("якорь поставлен и взорван за %.0f мс", ms));
    }

    // ===================== автототем =====================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ACPlayer d = ac.peek(p.getUniqueId());
        if (d == null) return;
        d.popPending = true;
        d.popNanos = System.nanoTime();
        d.popHand = e.getHand();
        d.totemPops++;
    }

    /** Клик в инвентаре (пакет). Контейнер 0 - инвентарь игрока: слот 45 - вторая рука, 5..8 - броня. */
    void click(Player p, ACPlayer d, Pk k) {
        boolean moving = (d.input & Pk.IN_MOVE) != 0;
        if (k.a != 0) {
            if (d.contOpen) {
                if (d.contBlock) stealClick(d, k);
                if (moving && (k.nanos - d.contOpenNanos) / 1e6 > ac.rtt(p, d) + 250)
                    ac.flag(p, d, Feature.INVMOVE, 0.25, "клики в открытом сундуке/меню с зажатыми клавишами движения");
            }
            return;
        }
        boolean offhand = k.b == 45 || (k.d == Pk.CLICK_SWAP && k.c == 40);
        boolean armor = k.b >= 5 && k.b <= 8;
        if (moving && (offhand || armor))
            ac.flag(p, d, Feature.INVMOVE, 0.3, "клик по " + (offhand ? "второй руке" : "слоту брони") + " с зажатыми клавишами движения");
        if (offhand && d.popPending) totem(p, d, k.nanos, 0, moving);
    }

    /** Клавиша F вне инвентаря. */
    void swap(Player p, ACPlayer d, Pk k) {
        if (d.popPending && isTotem(p.getInventory().getItemInMainHand())) totem(p, d, k.nanos, 1, false);
    }

    /** Смена слота хотбара. */
    void slot(Player p, ACPlayer d, Pk k) {
        if (d.popPending && d.popHand == EquipmentSlot.HAND && k.a >= 0 && k.a < 9 && isTotem(p.getInventory().getItem(k.a)))
            totem(p, d, k.nanos, 2, false);
    }

    private void totem(Player p, ACPlayer d, long nanos, int kind, boolean moving) {
        d.popPending = false;
        double ms = (nanos - d.popNanos) / 1e6;
        int rtt = ac.rtt(p, d);
        Bukkit.getScheduler().runTaskLater(ac.plugin(), () -> {
            if (!p.isOnline()) return;
            ItemStack it = kind == 2 ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
            if (!isTotem(it)) return;
            double react = Math.max(0, ms - rtt * 0.75);   // запас на случай, если пинг сейчас ниже среднего
            d.totemReactSum += Math.round(react);
            d.totemReactN++;
            if (rtt > 350) return;
            String how = kind == 0 ? "через инвентарь" : kind == 1 ? "клавишей F" : "сменой слота";
            if (kind == 0) {
                if (react < 150) { d.totemFast++; ac.flag(p, d, Feature.TOTEM, 0.45, fmt("тотем снова в руке через %.0f мс после срабатывания (%s)", react, how)); }
                else if (react < 250) ac.flag(p, d, Feature.TOTEM, 0.15, fmt("тотем снова в руке через %.0f мс (%s)", react, how));
                if (moving) ac.flag(p, d, Feature.TOTEM, 0.4, "тотем переложен во вторую руку на бегу - в ванилле инвентарь так не открыть");
            } else {
                if (react < 60) { d.totemFast++; ac.flag(p, d, Feature.TOTEM, 0.35, fmt("тотем снова в руке через %.0f мс после срабатывания (%s)", react, how)); }
                else if (react < 110) ac.flag(p, d, Feature.TOTEM, 0.12, fmt("тотем снова в руке через %.0f мс (%s)", react, how));
            }
        }, 3L);
    }

    // ===================== сундуки и меню =====================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        ACPlayer d = ac.peek(p.getUniqueId());
        if (d == null) return;
        InventoryType t = e.getInventory().getType();
        if (t == InventoryType.CRAFTING || t == InventoryType.CREATIVE) return;
        InventoryHolder h = e.getInventory().getHolder(false);
        d.contOpen = true;
        d.contBlock = h instanceof Container || h instanceof DoubleChest;
        d.contOpenNanos = System.nanoTime();
        d.contFirstNanos = 0;
        d.contClicks = 0;
        d.contGapN = 0;
        d.contLastTick = d.ctick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        ACPlayer d = ac.peek(p.getUniqueId());
        if (d == null || !d.contOpen) return;
        d.contOpen = false;
        if (d.contBlock && d.active) stealer(p, d);
    }

    private void stealClick(ACPlayer d, Pk k) {
        if (k.d != Pk.CLICK_QUICK_MOVE && k.d != Pk.CLICK_PICKUP) return;
        d.contClicks++;
        if (d.contFirstNanos == 0) d.contFirstNanos = k.nanos;
        else {
            long gap = d.ctick - d.contLastTick;
            if (gap <= 10 && d.contGapN < d.contGaps.length) d.contGaps[d.contGapN++] = gap;
        }
        d.contLastTick = d.ctick;
    }

    private void stealer(Player p, ACPlayer d) {
        if (d.contClicks < 5) return;
        int rtt = ac.rtt(p, d);
        double first = (d.contFirstNanos - d.contOpenNanos) / 1e6 - rtt;
        if (rtt < 300 && first < 70)
            ac.flag(p, d, Feature.STEALER, 0.35, fmt("первый клик в сундуке через %.0f мс после открытия (%d кликов)", Math.max(0, first), d.contClicks));
        if (d.contGapN >= 8) {
            double m = MathX.mean(d.contGaps, d.contGapN), sd = MathX.std(d.contGaps, d.contGapN);
            if (sd < 0.15 && m >= 1)
                ac.flag(p, d, Feature.STEALER, 0.3, fmt("%d кликов в сундуке с одинаковым интервалом %.0f тик.", d.contGapN + 1, m));
        }
    }

    // ===================== клиент =====================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(ac.plugin(), () -> client(p, false), 100L);
        Bukkit.getScheduler().runTaskLater(ac.plugin(), () -> client(p, true), 600L);
    }

    private void client(Player p, boolean last) {
        if (!p.isOnline()) return;
        ACPlayer d = ac.peek(p.getUniqueId());
        if (d == null || d.clientChecked) return;
        String brand = p.getClientBrandName();
        if (brand != null && !brand.equals(d.brand)) {
            d.brand = brand;
            d.addLog(ac.stamp() + " Клиент игрока: " + brand);
        }
        String bad = ac.blacklisted(brand, "anticheat.client-brands");
        if (bad != null) {
            d.clientChecked = true;
            ac.flagAlways(p, d, Feature.CLIENT, 2.0, "клиент сообщает о себе: " + brand);
            return;
        }
        for (String ch : p.getListeningPluginChannels()) {
            if (ac.blacklisted(ch, "anticheat.client-channels") != null) {
                d.clientChecked = true;
                ac.flagAlways(p, d, Feature.CLIENT, 1.5, "канал чит-мода: " + ch);
                return;
            }
        }
        if (last) d.clientChecked = true;
    }
}
