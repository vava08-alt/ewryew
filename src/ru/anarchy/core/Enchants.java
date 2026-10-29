package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Кастомные зачарования: вампиризм, взрывная стрела, автоплавка, телекинез. Накладываются книгой. */
public final class Enchants implements Listener {
    public record Ench(String id, String name, int max, String desc, String targets) {}
    public static final Map<String, Ench> ALL = new LinkedHashMap<>();
    static {
        ALL.put("vampirism", new Ench("vampirism", "Вампиризм", 3, "Лечит на 10% урона за уровень", "меч, топор"));
        ALL.put("explosive", new Ench("explosive", "Взрывная стрела", 2, "Стрелы взрываются при попадании", "лук, арбалет"));
        ALL.put("autosmelt", new Ench("autosmelt", "Автоплавка", 1, "Руда сразу переплавляется", "кирка, лопата"));
        ALL.put("telekinesis", new Ench("telekinesis", "Телекинез", 1, "Добыча сразу в инвентарь", "кирка, топор, лопата, мотыга"));
    }
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

    private final AnarchyCore pl;
    private final NamespacedKey BOOK;

    public Enchants(AnarchyCore pl) { this.pl = pl; BOOK = new NamespacedKey(pl, "cench_book"); }

    private NamespacedKey key(String id) { return new NamespacedKey(pl, "ce_" + id); }

    public ItemStack book(String id, int lvl) {
        Ench e = ALL.get(id);
        if (e == null) return null;
        lvl = Math.max(1, Math.min(e.max(), lvl));
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(mm("<!italic><gradient:#00f0ff:#b000ff><bold>Книга: " + e.name() + " " + ROMAN[lvl] + "</bold></gradient>"));
        m.lore(List.of(mm("<!italic><gray>" + e.desc()), mm("<!italic><gray>Для: <white>" + e.targets()), mm(""),
                mm("<!italic><yellow>Возьми книгу курсором и нажми"), mm("<!italic><yellow>на предмет в инвентаре")));
        m.getPersistentDataContainer().set(BOOK, PersistentDataType.STRING, id + ":" + lvl);
        it.setItemMeta(m);
        return it;
    }

    public String name(String id, int lvl) { Ench e = ALL.get(id); return e == null ? id : e.name() + " " + ROMAN[Math.max(1, Math.min(5, lvl))]; }

    public static boolean fits(String id, Material m) {
        String n = m.name();
        return switch (id) {
            case "vampirism" -> n.endsWith("_SWORD") || n.endsWith("_AXE");
            case "explosive" -> m == Material.BOW || m == Material.CROSSBOW;
            case "autosmelt" -> n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL");
            case "telekinesis" -> n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
            default -> false;
        };
    }

    public int level(ItemStack it, String id) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(key(id), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    // ---------- наложение ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApply(InventoryClickEvent e) {
        ItemStack cur = e.getCursor(), target = e.getCurrentItem();
        if (cur == null || cur.getType() != Material.ENCHANTED_BOOK || !cur.hasItemMeta() || target == null || target.getType().isAir()) return;
        String spec = cur.getItemMeta().getPersistentDataContainer().get(BOOK, PersistentDataType.STRING);
        if (spec == null || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != p.getInventory()) return;
        String[] s = spec.split(":");
        String id = s[0]; int lvl = Integer.parseInt(s[1]);
        e.setCancelled(true);
        if (!fits(id, target.getType())) { pl.msg(p, "<red>Это зачарование не подходит для этого предмета. <gray>Для: " + ALL.get(id).targets()); return; }
        if (target.getAmount() != 1) return;
        int has = level(target, id);
        if (has >= lvl) { pl.msg(p, "<red>На предмете уже есть такое же или сильнее"); return; }
        ItemMeta m = target.getItemMeta();
        m.getPersistentDataContainer().set(key(id), PersistentDataType.INTEGER, lvl);
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        String plainOld = has > 0 ? name(id, has) : null;
        lore.removeIf(c -> plainOld != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains(plainOld));
        lore.add(0, mm("<!italic><gradient:#00f0ff:#b000ff>✦ " + name(id, lvl)));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        target.setItemMeta(m);
        cur.setAmount(cur.getAmount() - 1);
        e.getView().setCursor(cur.getAmount() <= 0 ? null : cur);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        pl.msg(p, "<green>Наложено: <light_purple>" + name(id, lvl));
    }

    // ---------- вампиризм ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity)) return;
        int lvl = level(p.getInventory().getItemInMainHand(), "vampirism");
        if (lvl <= 0) return;
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double heal = e.getFinalDamage() * 0.10 * lvl;
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
        p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1.2, 0), 3, 0.3, 0.3, 0.3, 0);
    }

    // ---------- взрывная стрела ----------
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        int lvl = level(e.getBow(), "explosive");
        if (lvl > 0 && e.getProjectile() instanceof Projectile pr) pr.setMetadata("ce_explosive", new FixedMetadataValue(pl, lvl));
    }

    @EventHandler
    public void onProjectile(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!pr.hasMetadata("ce_explosive")) return;
        int lvl = pr.getMetadata("ce_explosive").get(0).asInt();
        pr.removeMetadata("ce_explosive", pl);
        if (pl.worlds().isHub(pr.getWorld())) return;
        org.bukkit.Location l = pr.getLocation();
        if (pl.regions().at(l) != null || pl.moreEvents().inArena(l)) { l.getWorld().spawnParticle(Particle.EXPLOSION, l, 1); return; }
        Entity src = pr.getShooter() instanceof Entity en ? en : null;
        l.getWorld().createExplosion(l, 1.2f + lvl * 0.6f, false, false, src);
        pr.remove();
    }

    // ---------- автоплавка / телекинез ----------
    private static Material smelt(Material m) {
        return switch (m) {
            case RAW_IRON -> Material.IRON_INGOT;
            case RAW_GOLD -> Material.GOLD_INGOT;
            case RAW_COPPER -> Material.COPPER_INGOT;
            case COBBLESTONE -> Material.STONE;
            case COBBLED_DEEPSLATE -> Material.DEEPSLATE;
            case SAND -> Material.GLASS;
            case RED_SAND -> Material.GLASS;
            case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
            case CLAY_BALL -> Material.BRICK;
            case NETHERRACK -> Material.NETHER_BRICK;
            case CACTUS -> Material.GREEN_DYE;
            case WET_SPONGE -> Material.SPONGE;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean smelt = level(tool, "autosmelt") > 0, tele = level(tool, "telekinesis") > 0;
        if (!smelt && !tele) return;
        if (smelt) for (Item it : e.getItems()) {
            ItemStack st = it.getItemStack();
            Material r = smelt(st.getType());
            if (r != null) { it.setItemStack(new ItemStack(r, st.getAmount())); p.getWorld().spawnParticle(Particle.FLAME, e.getBlock().getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0.01); }
        }
        if (tele) {
            for (Item it : e.getItems()) for (ItemStack left : p.getInventory().addItem(it.getItemStack()).values()) p.getWorld().dropItemNaturally(e.getBlock().getLocation(), left);
            e.getItems().clear();
        }
    }
}
