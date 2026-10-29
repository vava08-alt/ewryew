package ru.anarchy.core;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Кулдауны предметов в режиме боя: чарка, гэпл, регенерация, исцеление. */
public final class ItemCooldowns implements Listener {
    private final AnarchyCore pl;
    /** игрок -> (категория -> время окончания) */
    private final Map<UUID, Map<String, Long>> cd = new HashMap<>();
    private final Set<UUID> visual = new HashSet<>();

    public ItemCooldowns(AnarchyCore pl) { this.pl = pl; }

    private static final Map<String, String> NAMES = Map.of(
            "enchanted_golden_apple", "<light_purple>Чарка", "golden_apple", "<gold>Гэпл",
            "regeneration", "<red>Регенерация", "healing", "<red>Исцеление");

    private boolean onlyCombat() { return pl.getConfig().getBoolean("combat.item-cooldowns-only-in-combat", true); }
    private long seconds(String cat) { return pl.getConfig().getLong("combat.item-cooldowns." + cat, 0); }

    /** Категория предмета или null. */
    static String category(ItemStack it) {
        if (it == null) return null;
        Material m = it.getType();
        if (m == Material.ENCHANTED_GOLDEN_APPLE) return "enchanted_golden_apple";
        if (m == Material.GOLDEN_APPLE) return "golden_apple";
        if (!PotionStack.potion(m) || !(it.getItemMeta() instanceof PotionMeta pm)) return null;
        List<PotionEffectType> types = new ArrayList<>();
        if (pm.getBasePotionType() != null) for (PotionEffect e : pm.getBasePotionType().getPotionEffects()) types.add(e.getType());
        for (PotionEffect e : pm.getCustomEffects()) types.add(e.getType());
        if (types.contains(PotionEffectType.INSTANT_HEALTH)) return "healing";
        if (types.contains(PotionEffectType.REGENERATION)) return "regeneration";
        return null;
    }

    /** true - использовать нельзя (сообщение уже отправлено). */
    private boolean blocked(Player p, ItemStack it) {
        String cat = category(it);
        if (cat == null || seconds(cat) <= 0) return false;
        if (onlyCombat() && !pl.data(p).inCombat()) return false;
        long left = cd.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(cat, 0L) - System.currentTimeMillis();
        if (left > 0) {
            p.sendActionBar(mm(NAMES.get(cat) + " <red>на перезарядке: <white>" + (left / 1000 + 1) + " сек"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            if (it.getType() == Material.ENCHANTED_GOLDEN_APPLE || it.getType() == Material.GOLDEN_APPLE) { p.setCooldown(it.getType(), (int) (left / 50)); visual.add(p.getUniqueId()); }
            return true;
        }
        return false;
    }

    private void used(Player p, ItemStack it) {
        String cat = category(it);
        if (cat == null || seconds(cat) <= 0) return;
        if (onlyCombat() && !pl.data(p).inCombat()) return;
        long s = seconds(cat);
        cd.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(cat, System.currentTimeMillis() + s * 1000);
        if (it.getType() == Material.ENCHANTED_GOLDEN_APPLE || it.getType() == Material.GOLDEN_APPLE) { p.setCooldown(it.getType(), (int) (s * 20)); visual.add(p.getUniqueId()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (blocked(e.getPlayer(), e.getItem())) { e.setCancelled(true); return; }
        used(e.getPlayer(), e.getItem());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(PlayerLaunchProjectileEvent e) {
        ItemStack it = e.getItemStack();
        if (!PotionStack.potion(it.getType())) return;
        if (blocked(e.getPlayer(), it)) { e.setCancelled(true); e.setShouldConsume(false); return; }
        used(e.getPlayer(), it);
    }

    /** Строка для боевого action-bar: активные кулдауны. */
    public String status(Player p) {
        Map<String, Long> m = cd.get(p.getUniqueId());
        if (m == null) return "";
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (String cat : List.of("enchanted_golden_apple", "golden_apple", "regeneration", "healing")) {
            long left = m.getOrDefault(cat, 0L) - now;
            if (left > 0) sb.append(" <dark_gray>| ").append(NAMES.get(cat)).append(" <white>").append(left / 1000 + 1).append("с");
        }
        return sb.toString();
    }

    /** Вне боя предметы можно есть свободно: убираем полоску отката (сам таймер остаётся до следующего боя). */
    public void leaveCombat(Player p) {
        if (!onlyCombat() || !visual.remove(p.getUniqueId())) return;
        p.setCooldown(Material.ENCHANTED_GOLDEN_APPLE, 0);
        p.setCooldown(Material.GOLDEN_APPLE, 0);
    }
}
