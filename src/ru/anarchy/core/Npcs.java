package ru.anarchy.core;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** NPC на спавне (манекены со скинами) - клик открывает меню/команду. */
public final class Npcs implements Listener {
    private record Npc(String id, Location loc, String skin, List<String> lines, String command, Mannequin[] ent, TextDisplay[] holo, ConfigurationSection eq) {}
    private final AnarchyCore pl;
    private final NamespacedKey TAG;
    private final List<Npc> npcs = new ArrayList<>();
    private final Map<UUID, Long> clickCd = new HashMap<>();

    public Npcs(AnarchyCore pl) {
        this.pl = pl;
        TAG = new NamespacedKey(pl, "npc");
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 100, 10);
    }

    public void spawnAll() {
        removeAll();
        ConfigurationSection s = pl.getConfig().getConfigurationSection("npcs");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Location l = PlayerData.locFromString(c.getString("location", ""));
            if (l == null) { pl.getLogger().warning("NPC " + id + ": неверная позиция"); continue; }
            Npc n = new Npc(id, l, c.getString("skin", ""), c.getStringList("lines"), c.getString("command", "menu"), new Mannequin[1], new TextDisplay[1], c.getConfigurationSection("equipment"));
            npcs.add(n);
            spawn(n);
        }
    }

    private void spawn(Npc n) {
        World w = n.loc().getWorld();
        w.addPluginChunkTicket(n.loc().getBlockX() >> 4, n.loc().getBlockZ() >> 4, pl);
        n.ent()[0] = w.spawn(n.loc(), Mannequin.class, m -> {
            m.setPersistent(false);
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setAI(false);
            m.setGravity(false);
            m.setSilent(true);
            m.setCollidable(false);
            m.setRemoveWhenFarAway(false);
            m.setDescription(null);
            m.setCustomNameVisible(false);
            if (!n.skin().isBlank()) try { m.setProfile(ResolvableProfile.resolvableProfile().name(n.skin()).build()); } catch (Exception ignored) {}
            m.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, n.id());
            if (n.eq() != null) equip(m, n.eq());
        });
        n.holo()[0] = w.spawn(n.loc().clone().add(0, 2.05, 0), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(110, 0, 20, 40));
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, n.id());
            t.text(mm(String.join("\n", n.lines()) + "\n<yellow><bold>▶ ПКМ</bold>"));
        });
    }

    /** equipment: {main-hand, off-hand, helmet, chestplate, leggings, boots: МАТЕРИАЛ, color: "#RRGGBB" (для кожи), glint: true} */
    private void equip(Mannequin m, ConfigurationSection c) {
        var eq = m.getEquipment();
        if (eq == null) return;
        Color col = null;
        try { String h = c.getString("color", ""); if (!h.isBlank()) col = Color.fromRGB(Integer.parseInt(h.replace("#", ""), 16)); } catch (Exception ignored) {}
        for (String slot : List.of("main-hand", "off-hand", "helmet", "chestplate", "leggings", "boots")) {
            Material mat = Material.matchMaterial(c.getString(slot, ""));
            if (mat == null || mat.isAir()) continue;
            ItemStack it = new ItemStack(mat);
            if (col != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) { lm.setColor(col); it.setItemMeta(lm); }
            if (c.getBoolean("glint", false)) { var im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
            switch (slot) {
                case "main-hand" -> eq.setItemInMainHand(it);
                case "off-hand" -> eq.setItemInOffHand(it);
                case "helmet" -> eq.setHelmet(it);
                case "chestplate" -> eq.setChestplate(it);
                case "leggings" -> eq.setLeggings(it);
                default -> eq.setBoots(it);
            }
        }
    }

    public void removeAll() {
        for (Npc n : npcs) { if (n.ent()[0] != null) n.ent()[0].remove(); if (n.holo()[0] != null) n.holo()[0].remove(); }
        npcs.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) e.remove();
    }

    private void tick() {
        for (Npc n : npcs) {
            Mannequin m = n.ent()[0];
            if (m == null || !m.isValid()) {
                if (!n.loc().isChunkLoaded()) continue;
                if (m != null) m.remove();
                if (n.holo()[0] != null) n.holo()[0].remove();
                spawn(n);
                continue;
            }
            // поворачивается к ближайшему игроку
            Player best = null; double bd = 64;
            for (Player p : m.getWorld().getPlayers()) { double d = p.getLocation().distanceSquared(m.getLocation()); if (d < bd) { bd = d; best = p; } }
            Location look = n.loc().clone();
            if (best != null) {
                Vector v = best.getEyeLocation().toVector().subtract(m.getEyeLocation().toVector());
                look.setDirection(v);
            }
            m.setRotation(look.getYaw(), Math.max(-30, Math.min(30, look.getPitch())));
        }
    }

    private Npc byEntity(Entity e) {
        String id = e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        if (id == null) return null;
        for (Npc n : npcs) if (n.id().equals(id)) return n;
        return null;
    }

    private void click(Player p, Npc n) {
        long now = System.currentTimeMillis();
        if (now - clickCd.getOrDefault(p.getUniqueId(), 0L) < 500) return;
        clickCd.put(p.getUniqueId(), now);
        if (!pl.data(p).authed) return;
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1.3f);
        p.performCommand(n.command());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = byEntity(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        click(e.getPlayer(), n);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(PlayerInteractAtEntityEvent e) { if (byEntity(e.getRightClicked()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        Npc n = byEntity(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) click(p, n);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) { if (byEntity(e.getEntity()) != null) e.setCancelled(true); }
}
