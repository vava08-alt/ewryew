package ru.anarchy.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

public final class Regions implements Listener {
    public static final class Region {
        public int id; public UUID owner; public String ownerName; public String world;
        public int x, y, z, radius; public String tier; public boolean explosionProof;
        public final Set<UUID> members = new HashSet<>();
        public boolean contains(Location l) {
            return l.getWorld().getName().equals(world) && Math.abs(l.getBlockX() - x) <= radius && Math.abs(l.getBlockZ() - z) <= radius;
        }
        public boolean canUse(UUID u) { return owner.equals(u) || members.contains(u) || (AnarchyCore.INSTANCE.clans() != null && AnarchyCore.INSTANCE.clans().same(owner, u)); }
        public int size() { return radius * 2 + 1; }
    }

    private final AnarchyCore pl;
    private final File file;
    private final Map<Integer, Region> regions = new HashMap<>();
    private final Map<String, List<Region>> index = new HashMap<>(); // world:cx:cz
    private int nextId = 1;
    public final NamespacedKey KEY;

    public Regions(AnarchyCore pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "regions.yml");
        this.KEY = new NamespacedKey(pl, "region_tier");
        load();
    }

    // ---------- хранение ----------
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next-id", 1);
        ConfigurationSection s = y.getConfigurationSection("regions");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(k);
            Region g = new Region();
            g.id = Integer.parseInt(k);
            g.owner = UUID.fromString(r.getString("owner"));
            g.ownerName = r.getString("owner-name");
            g.world = r.getString("world");
            g.x = r.getInt("x"); g.y = r.getInt("y"); g.z = r.getInt("z");
            g.radius = r.getInt("radius"); g.tier = r.getString("tier");
            g.explosionProof = r.getBoolean("explosion-proof");
            for (String m : r.getStringList("members")) g.members.add(UUID.fromString(m));
            add(g);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Region g : regions.values()) {
            String k = "regions." + g.id;
            y.set(k + ".owner", g.owner.toString()); y.set(k + ".owner-name", g.ownerName);
            y.set(k + ".world", g.world); y.set(k + ".x", g.x); y.set(k + ".y", g.y); y.set(k + ".z", g.z);
            y.set(k + ".radius", g.radius); y.set(k + ".tier", g.tier); y.set(k + ".explosion-proof", g.explosionProof);
            y.set(k + ".members", g.members.stream().map(UUID::toString).toList());
        }
        try { y.save(file); } catch (Exception e) { pl.getLogger().severe("regions.yml: " + e); }
    }

    private void add(Region g) {
        regions.put(g.id, g);
        for (int cx = (g.x - g.radius) >> 4; cx <= (g.x + g.radius) >> 4; cx++)
            for (int cz = (g.z - g.radius) >> 4; cz <= (g.z + g.radius) >> 4; cz++)
                index.computeIfAbsent(g.world + ":" + cx + ":" + cz, k -> new ArrayList<>()).add(g);
    }

    private void remove(Region g) {
        regions.remove(g.id);
        for (List<Region> l : index.values()) l.remove(g);
        save();
    }

    public Region at(Location l) {
        List<Region> list = index.get(l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4));
        if (list == null) return null;
        for (Region g : list) if (g.contains(l)) return g;
        return null;
    }

    public List<Region> owned(UUID u) { return regions.values().stream().filter(g -> g.owner.equals(u)).toList(); }

    // ---------- предмет привата ----------
    public ItemStack item(String tier) {
        ConfigurationSection t = pl.getConfig().getConfigurationSection("regions.tiers." + tier);
        if (t == null) return null;
        Material m = Material.matchMaterial(t.getString("material", "COAL_BLOCK"));
        ItemStack it = new ItemStack(m == null ? Material.COAL_BLOCK : m);
        ItemMeta meta = it.getItemMeta();
        int r = t.getInt("radius");
        meta.displayName(mm("<!italic>" + t.getString("name")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(mm("<!italic><gray>Поставь блок - территория <white>" + (r * 2 + 1) + "x" + (r * 2 + 1) + "</white> станет твоей"));
        lore.add(mm("<!italic><gray>Защита: ломание, установка, сундуки, вёдра, огонь"));
        lore.add(mm(t.getBoolean("explosion-proof") ? "<!italic><green>✔ Защита от взрывов" : "<!italic><red>✘ Не защищает от взрывов - блок привата можно взорвать!"));
        lore.add(mm("<!italic><dark_gray>/rg add <ник> - добавить друга"));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, tier);
        it.setItemMeta(meta);
        return it;
    }

    // ---------- проверки ----------
    public boolean inSpawn(Location l) {
        return l.getWorld() != null && pl.worlds().isHub(l.getWorld());
    }

    private boolean bypass(Player p) { return p.hasPermission("anarchy.admin") && p.getGameMode() == GameMode.CREATIVE; }

    public boolean canBuild(Player p, Location l) {
        if (bypass(p)) return true;
        if (inSpawn(l)) return pl.getConfig().getStringList("spawn.builders").stream().anyMatch(n -> n.equalsIgnoreCase(p.getName()));
        Region g = at(l);
        return g == null || g.canUse(p.getUniqueId());
    }

    private void deny(Player p, Location l) {
        Region g = at(l);
        p.sendActionBar(mm(g == null ? "<red>✘ Территория спавна защищена" : "<red>✘ Приват игрока <white>" + AnarchyCore.esc(g.ownerName)));
    }

    // ---------- события ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Location l = e.getBlock().getLocation();
        if (!canBuild(p, l)) { e.setCancelled(true); deny(p, l); return; }
        ItemStack it = e.getItemInHand();
        if (!it.hasItemMeta()) return;
        String tier = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (tier == null) return;
        ConfigurationSection t = pl.getConfig().getConfigurationSection("regions.tiers." + tier);
        if (t == null) { e.setCancelled(true); return; }
        PlayerData d = pl.data(p);
        int limit = pl.rank(d).regions();
        if (owned(p.getUniqueId()).size() >= limit) {
            e.setCancelled(true);
            pl.msg(p, "<red>Лимит приватов: " + limit + ". <gray>Больше - на старших рангах (/don)");
            return;
        }
        int r = t.getInt("radius");
        Location s = pl.spawn();
        int minDist = pl.getConfig().getInt("regions.min-distance-from-spawn", 100);
        if (s != null && s.getWorld().equals(l.getWorld()) && Math.max(Math.abs(l.getBlockX() - s.getBlockX()), Math.abs(l.getBlockZ() - s.getBlockZ())) < minDist + r) {
            e.setCancelled(true); pl.msg(p, "<red>Слишком близко к спавну"); return;
        }
        for (Region o : regions.values())
            if (o.world.equals(l.getWorld().getName()) && Math.abs(o.x - l.getBlockX()) <= o.radius + r && Math.abs(o.z - l.getBlockZ()) <= o.radius + r) {
                e.setCancelled(true); pl.msg(p, "<red>Пересекается с приватом игрока " + AnarchyCore.esc(o.ownerName)); return;
            }
        Region g = new Region();
        g.id = nextId++; g.owner = p.getUniqueId(); g.ownerName = p.getName(); g.world = l.getWorld().getName();
        g.x = l.getBlockX(); g.y = l.getBlockY(); g.z = l.getBlockZ(); g.radius = r; g.tier = tier;
        g.explosionProof = t.getBoolean("explosion-proof");
        add(g);
        save();
        pl.msg(p, "<green>Приват создан! <white>" + g.size() + "x" + g.size() + "</white> " + (g.explosionProof ? "<green>(защищён от взрывов)" : "<red>(блок привата можно взорвать!)"));
        pl.msg(p, "<gray>Добавить друга: <yellow>/rg add <ник></yellow>, границы: <yellow>/rg show");
        show(p, g);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (pl.mine() != null && pl.mine().contains(b.getLocation())) return;
        Region core = coreAt(b);
        if (core != null) {
            if (!core.owner.equals(p.getUniqueId()) && !bypass(p)) { e.setCancelled(true); deny(p, b.getLocation()); return; }
            e.setDropItems(false);
            remove(core);
            ItemStack it = item(core.tier);
            if (it != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
            pl.msg(p, "<yellow>Приват удалён, блок привата возвращён.");
            return;
        }
        if (!canBuild(p, b.getLocation())) { e.setCancelled(true); deny(p, b.getLocation()); }
    }

    private Region coreAt(Block b) {
        Region g = at(b.getLocation());
        return g != null && g.x == b.getX() && g.y == b.getY() && g.z == b.getZ() ? g : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL || (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable())) {
            if (!canBuild(e.getPlayer(), b.getLocation())) {
                if (inSpawn(b.getLocation()) && isSpawnUsable(b.getType())) return;
                e.setCancelled(true);
                if (e.getAction() != org.bukkit.event.block.Action.PHYSICAL) deny(e.getPlayer(), b.getLocation());
            }
        }
    }

    private boolean isSpawnUsable(Material m) {
        String n = m.name();
        return n.endsWith("_DOOR") || n.endsWith("_BUTTON") || n.endsWith("PRESSURE_PLATE") || n.endsWith("_GATE") || m == Material.ENDER_CHEST || m == Material.CRAFTING_TABLE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); deny(e.getPlayer(), e.getBlock().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); deny(e.getPlayer(), e.getBlock().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { explode(e.blockList()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { explode(e.blockList()); }

    private void explode(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (inSpawn(b.getLocation())) { it.remove(); continue; }
            Region g = at(b.getLocation());
            if (g == null) continue;
            if (g.explosionProof) { it.remove(); continue; }
            if (g.x == b.getX() && g.y == b.getY() && g.z == b.getZ()) {
                remove(g);
                b.setType(Material.AIR);
                it.remove();
                Bukkit.broadcast(mm("<red>☄ Приват игрока <white>" + AnarchyCore.esc(g.ownerName) + "</white> взорван! <gray>(" + g.x + ", " + g.z + ")"));
                Player o = Bukkit.getPlayer(g.owner);
                if (o != null) o.playSound(o.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Region to = at(e.getToBlock().getLocation());
        if (to != null && to != at(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { piston(e.getBlock(), e.getBlocks(), e.getDirection(), e); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent e) { piston(e.getBlock(), e.getBlocks(), e.getDirection(), e); }

    private void piston(Block piston, List<Block> blocks, org.bukkit.block.BlockFace dir, org.bukkit.event.Cancellable e) {
        Region src = at(piston.getLocation());
        for (Block b : blocks) {
            if (coreAt(b) != null) { e.setCancelled(true); return; }
            Region r1 = at(b.getLocation()), r2 = at(b.getRelative(dir).getLocation());
            if ((r1 != null && r1 != src) || (r2 != null && r2 != src)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Region g = at(e.getBlock().getLocation());
        if (g == null && !inSpawn(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null) { if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
        else if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (at(e.getBlock().getLocation()) != null || inSpawn(e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (e.getEntity() instanceof FallingBlock) return;
        Region g = at(e.getBlock().getLocation());
        if ((g != null && g.explosionProof) || inSpawn(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && !canBuild(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityHit(EntityDamageByEntityEvent e) {
        Player p = e.getDamager() instanceof Player x ? x : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player x2 ? x2 : null;
        if (p == null) return;
        if (e.getEntity() instanceof Player victim) {
            if (inSpawn(victim.getLocation()) || inSpawn(p.getLocation())) {
                e.setCancelled(true);
                p.sendActionBar(mm("<red>✘ PvP на спавне запрещено"));
            }
            return;
        }
        if ((e.getEntity() instanceof ItemFrame || e.getEntity() instanceof ArmorStand || e.getEntity() instanceof Villager
                || e.getEntity() instanceof Animals) && !canBuild(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if ((en instanceof ItemFrame || en instanceof ArmorStand || en instanceof org.bukkit.entity.minecart.StorageMinecart
                || en instanceof ChestBoat) && !canBuild(e.getPlayer(), en.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        PlayerData d = pl.data(p);
        Region g = at(e.getTo());
        String key = g == null ? (inSpawn(e.getTo()) ? "spawn" : null) : String.valueOf(g.id);
        if (!Objects.equals(key, d.lastRegion)) {
            d.lastRegion = key;
            if (g != null) p.sendActionBar(mm("<green>⛨ Приват игрока <white>" + AnarchyCore.esc(g.ownerName) + "</white> <dark_gray>(" + g.size() + "x" + g.size() + ")"));
            else if ("spawn".equals(key)) p.sendActionBar(mm("<aqua>⛨ Спавн - безопасная зона"));
            else p.sendActionBar(mm("<red>☠ Дикая зона - здесь можно всё"));
        }
        if (d.flying && (g == null || !g.canUse(p.getUniqueId())) && p.getGameMode() == GameMode.SURVIVAL) {
            d.flying = false;
            p.setAllowFlight(false);
            p.setFlying(false);
            p.setFallDistance(0);
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 200, 0));
            pl.msg(p, "<red>Полёт выключен: ты покинул свой приват <gray>(плавное падение 10 сек)");
        }
    }

    // ---------- визуализация ----------
    public void show(Player p, Region g) {
        World w = Bukkit.getWorld(g.world);
        if (w == null) return;
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (t[0]++ > 10 || !p.isOnline()) { task.cancel(); return; }
            double y = p.getLocation().getY() + 1;
            Particle.DustOptions dust = new Particle.DustOptions(g.explosionProof ? Color.AQUA : Color.LIME, 1.5f);
            for (int i = -g.radius; i <= g.radius; i += Math.max(1, g.radius / 15)) {
                for (int[] c : new int[][]{{g.x + i, g.z - g.radius}, {g.x + i, g.z + g.radius + 1}, {g.x - g.radius, g.z + i}, {g.x + g.radius + 1, g.z + i}})
                    p.spawnParticle(Particle.DUST, c[0], y, c[1], 2, 0, 0.5, 0, 0, dust);
            }
        }, 0, 20);
    }

    // ---------- команды /rg ----------
    public void command(Player p, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        Region here = at(p.getLocation());
        switch (sub) {
            case "info" -> {
                if (here == null) { pl.msg(p, "<gray>Здесь нет привата"); return; }
                List<String> mem = here.members.stream().map(u -> { PlayerData d = pl.store().get(u); return d == null ? "?" : d.name; }).toList();
                pl.msg(p, "<gray>Приват <white>#" + here.id + "</white> владелец <white>" + AnarchyCore.esc(here.ownerName) + "</white>, размер <white>"
                        + here.size() + "x" + here.size() + "</white>, центр " + here.x + " " + here.y + " " + here.z
                        + (here.explosionProof ? " <green>[от взрывов]" : " <red>[взрываемый]") + "<gray>, друзья: <white>" + (mem.isEmpty() ? "нет" : String.join(", ", mem)));
            }
            case "list" -> {
                List<Region> list = owned(p.getUniqueId());
                pl.msg(p, "<gray>Твои приваты (" + list.size() + "/" + pl.rank(pl.data(p)).regions() + "):");
                for (Region g : list) p.sendMessage(mm("<gray> #" + g.id + " <white>" + g.size() + "x" + g.size() + "</white> " + g.world + " " + g.x + " " + g.y + " " + g.z));
            }
            case "add", "remove" -> {
                if (here == null || !here.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>Встань в свой приват"); return; }
                if (a.length < 2) { pl.msg(p, "<red>/rg " + sub + " <ник>"); return; }
                PlayerData t = pl.store().byName(a[1]);
                if (t == null) { pl.msg(p, "<red>Игрок не найден"); return; }
                if (sub.equals("add")) { here.members.add(t.uuid); pl.msg(p, "<green>" + AnarchyCore.esc(t.name) + " добавлен в приват"); }
                else { here.members.remove(t.uuid); pl.msg(p, "<yellow>" + AnarchyCore.esc(t.name) + " удалён из привата"); }
                save();
            }
            case "show" -> { if (here == null) pl.msg(p, "<gray>Здесь нет привата"); else show(p, here); }
            case "delete" -> {
                if (here == null || !here.owner.equals(p.getUniqueId())) { pl.msg(p, "<red>Встань в свой приват"); return; }
                World w = Bukkit.getWorld(here.world);
                if (w != null) w.getBlockAt(here.x, here.y, here.z).setType(Material.AIR);
                remove(here);
                for (ItemStack left : p.getInventory().addItem(item(here.tier)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                pl.msg(p, "<yellow>Приват удалён, блок возвращён в инвентарь");
            }
            default -> {
                pl.msg(p, "<gold>Приваты: <gray>купи блок привата в <yellow>/market</yellow> или <light_purple>/shop</light_purple> и поставь его");
                p.sendMessage(mm("<yellow>/rg info</yellow> <gray>- инфо, <yellow>/rg list</yellow> - твои, <yellow>/rg add|remove <ник></yellow> - друзья"));
                p.sendMessage(mm("<yellow>/rg show</yellow> <gray>- границы, <yellow>/rg delete</yellow> - удалить. Сломать блок привата = снять приват"));
            }
        }
    }
}
