package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Стакающиеся зелья: обычным (питьевым) зельям ставится макс. размер стака potion-stack (по умолч. 16).
 *  Взрывные и туманные - поштучно (старые стаки разбиваются). */
public final class PotionStack implements Listener {
    private final AnarchyCore pl;

    public PotionStack(AnarchyCore pl) {
        this.pl = pl;
        // периодически приводим зелья в инвентарях игроков (сварены, выпали из сундуков, куплены у жителей и т.п.)
        Bukkit.getScheduler().runTaskTimer(pl, () -> { for (Player p : Bukkit.getOnlinePlayers()) fixAll(p.getInventory()); }, 100, 40);
    }

    static int size(AnarchyCore pl) { return Math.max(1, Math.min(99, pl.getConfig().getInt("potion-stack", 16))); }

    static boolean potion(Material m) { return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION; }

    /** true - предмет изменён */
    public static boolean fix(ItemStack it, AnarchyCore pl) {
        if (it == null || !potion(it.getType())) return false;
        int n = it.getType() == Material.POTION ? size(pl) : 1;
        ItemMeta m = it.getItemMeta();
        if (m == null || (m.hasMaxStackSize() && m.getMaxStackSize() == n)) return false;
        if (n == 1) { if (!m.hasMaxStackSize()) return false; m.setMaxStackSize(null); }
        else m.setMaxStackSize(n);
        it.setItemMeta(m);
        return true;
    }

    private void fixAll(Inventory inv) {
        if (inv == null) return;
        ItemStack[] c = inv.getContents();
        boolean ch = false;
        java.util.List<ItemStack> extra = new java.util.ArrayList<>();
        for (ItemStack it : c) {
            ch |= fix(it, pl);
            // стак больше допустимого (взрывные были по 16) - лишнее раскладываем по одному
            if (it != null && potion(it.getType()) && it.getAmount() > it.getMaxStackSize()) {
                int over = it.getAmount() - it.getMaxStackSize();
                it.setAmount(it.getMaxStackSize());
                for (int i = 0; i < over; i++) { ItemStack one = it.clone(); one.setAmount(1); extra.add(one); }
                ch = true;
            }
        }
        if (!ch) return;
        inv.setContents(c);
        for (ItemStack one : extra)
            for (ItemStack left : inv.addItem(one).values()) {
                org.bukkit.Location l = inv.getLocation() != null ? inv.getLocation() : inv.getHolder() instanceof org.bukkit.entity.Entity en ? en.getLocation() : null;
                if (l != null) l.getWorld().dropItemNaturally(l, left);
            }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (fix(it, pl)) {
            if (it.getAmount() > it.getMaxStackSize()) {   // выпавший стак взрывных - по одному
                int over = it.getAmount() - it.getMaxStackSize();
                it.setAmount(it.getMaxStackSize());
                for (int i = 0; i < over; i++) { ItemStack one = it.clone(); one.setAmount(1); e.getLocation().getWorld().dropItem(e.getLocation(), one); }
            }
            e.getEntity().setItemStack(it);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        java.util.List<ItemStack> r = e.getResults();
        for (int i = 0; i < r.size(); i++) { ItemStack it = r.get(i); if (fix(it, pl)) r.set(i, it); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) { fixAll(e.getInventory()); fixAll(e.getPlayer().getInventory()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { fixAll(e.getPlayer().getInventory()); }
}
