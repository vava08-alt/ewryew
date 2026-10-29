package ru.anarchy.core;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import net.kyori.adventure.text.Component;

import java.util.*;

import static ru.anarchy.core.AnarchyCore.mm;

/** Особые крафты: рецепты предметов плагина на верстаке (config: crafts.recipes), меню /crafts и книга рецептов. */
public final class Crafts implements Listener {
    private record Craft(String id, NamespacedKey key, String[] shape, Map<Character, Material> ing, int amount, ItemStack result) {}

    private final AnarchyCore pl;
    private final List<Craft> crafts = new ArrayList<>();
    private final String ns;

    public Crafts(AnarchyCore pl) {
        this.pl = pl;
        this.ns = new NamespacedKey(pl, "x").getNamespace();
    }

    /** (Пере)регистрирует рецепты из конфига. resend - сразу разослать игрокам (после /core reload). */
    public void load(boolean resend) {
        for (Craft c : crafts) Bukkit.removeRecipe(c.key(), false);
        crafts.clear();
        ConfigurationSection rs = pl.getConfig().getConfigurationSection("crafts.recipes");
        if (pl.getConfig().getBoolean("crafts.enabled", true) && rs != null) for (String id : rs.getKeys(false)) {
            Craft c = parse(id, rs.getConfigurationSection(id));
            if (c == null) continue;
            ShapedRecipe r = new ShapedRecipe(c.key(), c.result());
            r.shape(c.shape());
            c.ing().forEach((ch, m) -> r.setIngredient(ch, new RecipeChoice.MaterialChoice(m)));
            r.setCategory(CraftingBookCategory.MISC);
            r.setGroup("aqua_special");
            if (Bukkit.addRecipe(r, false)) crafts.add(c);
            else pl.getLogger().warning("Особые крафты: рецепт " + id + " не добавлен (ключ занят)");
        }
        if (resend) Bukkit.updateRecipes();
        for (Player p : Bukkit.getOnlinePlayers()) discover(p);
        pl.getLogger().info("Особые крафты: рецептов " + crafts.size());
    }

    public void shutdown() {
        for (Craft c : crafts) Bukkit.removeRecipe(c.key(), false);
        crafts.clear();
    }

    private Craft parse(String id, ConfigurationSection r) {
        if (r == null) return null;
        ItemStack res = pl.items().create(id);
        if (res == null) { bad(id, "нет предмета с таким id"); return null; }
        List<String> rows = r.getStringList("shape");
        if (rows.isEmpty() || rows.size() > 3) { bad(id, "shape - от 1 до 3 строк"); return null; }
        int w = rows.get(0).length();
        ConfigurationSection is = r.getConfigurationSection("ingredients");
        Map<Character, Material> ing = new LinkedHashMap<>();
        for (String row : rows) {
            if (w < 1 || w > 3 || row.length() != w) { bad(id, "строки shape должны быть одной длины, 1-3 символа"); return null; }
            for (char ch : row.toCharArray()) {
                if (ch == ' ' || ing.containsKey(ch)) continue;
                String name = is == null ? null : is.getString(String.valueOf(ch));
                Material m = name == null ? null : Material.matchMaterial(name);
                if (m == null || m.isAir() || !m.isItem()) { bad(id, "ингредиент '" + ch + "' = " + name + " - нет такого предмета"); return null; }
                ing.put(ch, m);
            }
        }
        if (ing.isEmpty()) { bad(id, "пустой рецепт"); return null; }
        int amount = Math.max(1, Math.min(res.getMaxStackSize(), r.getInt("amount", 1)));
        res.setAmount(amount);
        return new Craft(id, new NamespacedKey(pl, "craft_" + id.toLowerCase(Locale.ROOT)), rows.toArray(new String[0]), ing, amount, res);
    }

    private void bad(String id, String why) { pl.getLogger().warning("Особые крафты: рецепт " + id + " пропущен - " + why); }

    private void discover(Player p) {
        if (crafts.isEmpty()) return;
        List<NamespacedKey> keys = new ArrayList<>();
        for (Craft c : crafts) keys.add(c.key());
        p.discoverRecipes(keys);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { discover(e.getPlayer()); }

    // ---------- ингредиенты: только обычные предметы ----------
    private boolean ours(Recipe r) { return r instanceof Keyed k && k.getKey().getNamespace().equals(ns) && k.getKey().getKey().startsWith("craft_"); }

    /** Предмет плагина (спец-предметы, сферы, сердца подземелья, чеки, «неизвестный лут»...) - не ингредиент. */
    private boolean marked(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        for (NamespacedKey k : it.getItemMeta().getPersistentDataContainer().getKeys()) if (k.getNamespace().equals(ns)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        if (!ours(e.getRecipe())) return;
        for (ItemStack it : e.getInventory().getMatrix()) if (marked(it)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (!ours(e.getRecipe()) || !(e.getBlock().getState(false) instanceof org.bukkit.block.Crafter c)) return;
        for (ItemStack it : c.getInventory().getContents()) if (marked(it)) { e.setCancelled(true); return; }
    }

    // ---------- меню ----------
    private static final int[] NICE = {11, 13, 15, 20, 22, 24, 29, 31, 33};

    public void menu(Player p) {
        Menus.Menu m = new Menus.Menu(5, "<gradient:#00e5ff:#2d60ff><bold>Особые крафты</bold></gradient>");
        fill(m, 45);
        m.set(4, Menus.icon(Material.CRAFTING_TABLE, "<aqua><bold>Особые крафты",
                "<gray>Спец-предметы можно скрафтить", "<gray>на обычном верстаке.", "<gray>Рецепты есть и в книге рецептов.", "",
                "<yellow>Нажми на предмет - покажу рецепт", "", "<dark_gray>Спец-предметы и другие предметы", "<dark_gray>сервера ингредиентами не подходят"), null);
        for (int i = 0; i < crafts.size() && i < 21; i++) {
            Craft c = crafts.get(i);
            int slot = crafts.size() <= NICE.length ? NICE[i] : 10 + (i / 7) * 9 + i % 7;
            m.set(slot, withLore(c.result(), "", "<gray>Получается: <white>" + c.amount() + " шт.", "<yellow>▶ Нажми - рецепт"), x -> recipe(p, c));
        }
        if (crafts.isEmpty()) m.set(22, Menus.icon(Material.BARRIER, "<red>Особые крафты выключены"), null);
        m.set(40, Menus.icon(Material.BARRIER, "<red>Закрыть"), x -> p.closeInventory());
        p.openInventory(m.getInventory());
    }

    private void recipe(Player p, Craft c) {
        Menus.Menu m = new Menus.Menu(5, "<gradient:#00e5ff:#2d60ff><bold>Особый крафт</bold></gradient> <dark_gray>| рецепт");
        fill(m, 45);
        int[][] grid = {{10, 11, 12}, {19, 20, 21}, {28, 29, 30}};
        for (int[] row : grid) for (int s : row) m.set(s, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE), null);
        Map<Material, Integer> need = new LinkedHashMap<>();
        for (int r = 0; r < c.shape().length; r++) for (int k = 0; k < c.shape()[r].length(); k++) {
            char ch = c.shape()[r].charAt(k);
            if (ch == ' ') continue;
            Material mat = c.ing().get(ch);
            m.set(grid[r][k], new ItemStack(mat), null);
            need.merge(mat, 1, Integer::sum);
        }
        m.set(23, Menus.icon(Material.ARROW, "<white>➜", "<gray>Сложи на верстаке так же, как слева"), null);
        m.set(25, c.result().clone(), null);
        List<String> lines = new ArrayList<>(List.of("<gray>Нужно:"));
        need.forEach((mat, n) -> lines.add("<white>" + n + " x <lang:" + mat.translationKey() + ">"));
        lines.add("");
        lines.add("<gray>Получится: <white>" + c.amount() + " шт.");
        m.set(16, Menus.icon(Material.BOOK, "<aqua><bold>Ингредиенты", lines.toArray(new String[0])), null);
        m.set(36, Menus.icon(Material.SPECTRAL_ARROW, "<gray>« Все крафты"), x -> menu(p));
        m.set(40, Menus.icon(Material.BARRIER, "<red>Закрыть"), x -> p.closeInventory());
        p.openInventory(m.getInventory());
    }

    /** Консоль / админ: список рецептов и проверка - каждый рецепт на верстаке даёт свой предмет (не перебит ванильным). */
    public void list(CommandSender s) {
        s.sendMessage("Особые крафты: " + crafts.size());
        org.bukkit.World w = Bukkit.getWorlds().get(0);
        for (Craft c : crafts) {
            StringBuilder ing = new StringBuilder();
            c.ing().forEach((ch, m) -> ing.append(ch).append('=').append(m.name()).append(' '));
            ItemStack[] mx = new ItemStack[9];
            for (int r = 0; r < c.shape().length; r++) for (int k = 0; k < c.shape()[r].length(); k++) {
                char ch = c.shape()[r].charAt(k);
                if (ch != ' ') mx[r * 3 + k] = new ItemStack(c.ing().get(ch));
            }
            Recipe got = Bukkit.getCraftingRecipe(mx, w);
            String check = got instanceof Keyed k && k.getKey().equals(c.key())
                    ? (c.id().equals(pl.items().id(got.getResult())) && got.getResult().getAmount() == c.amount() ? "OK" : "рецепт найден, но предмет не тот")
                    : got instanceof Keyed k ? "ПЕРЕБИТ рецептом " + k.getKey() : "не срабатывает";
            s.sendMessage(" " + c.id() + " x" + c.amount() + " [" + String.join("|", c.shape()) + "] " + ing.toString().trim() + " -> " + check);
        }
    }

    private static void fill(Menus.Menu m, int size) { for (int i = 0; i < size; i++) m.set(i, pane(Material.BLACK_STAINED_GLASS_PANE), null); }

    private static ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack withLore(ItemStack base, String... extra) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        List<Component> l = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String s : extra) l.add(mm("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }
}
