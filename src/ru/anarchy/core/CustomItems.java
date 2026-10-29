package ru.anarchy.core;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static ru.anarchy.core.AnarchyCore.mm;

public final class CustomItems implements Listener {
    public record Buff(String id, PotionEffectType type, int amp, String name, Material icon) {}

    public static final LinkedHashMap<String, Buff> BUFFS = new LinkedHashMap<>();
    static {
        BUFFS.put("speed", new Buff("speed", PotionEffectType.SPEED, 0, "Скорость I", Material.SUGAR));
        BUFFS.put("fire_resistance", new Buff("fire_resistance", PotionEffectType.FIRE_RESISTANCE, 0, "Огнестойкость", Material.MAGMA_CREAM));
        BUFFS.put("strength", new Buff("strength", PotionEffectType.STRENGTH, 0, "Сила I", Material.BLAZE_POWDER));
        BUFFS.put("regeneration", new Buff("regeneration", PotionEffectType.REGENERATION, 0, "Регенерация I", Material.GHAST_TEAR));
    }
    /** Баф не действует в режиме боя (PvP). */
    public static boolean pvpOff(Buff b) { return b.type() == PotionEffectType.REGENERATION; }
    /** Длительность эффекта бафа (тиков): по ней баф отличается от зелий, яблок и маяка. */
    private static final int BUFF_TICKS = 100;

    private final AnarchyCore pl;
    public final NamespacedKey KEY, SPAWNER, ARROW, APOLLO;
    private boolean drilling;

    public CustomItems(AnarchyCore pl) {
        this.pl = pl;
        KEY = new NamespacedKey(pl, "custom_item");
        SPAWNER = new NamespacedKey(pl, "spawner_type");
        ARROW = new NamespacedKey(pl, "artemis_arrow");
        APOLLO = new NamespacedKey(pl, "apollo_arrow");
        Bukkit.getScheduler().runTaskTimer(pl, this::effectsTick, 40, 40);
    }

    public static final List<String> IDS = List.of("kratos_blade", "leviathan_axe", "artemis_bow", "titan_pickaxe", "hephaestus_drill",
            "olympus_helmet", "olympus_chestplate", "olympus_leggings", "olympus_boots",
            "leviathan_helmet", "leviathan_chestplate", "leviathan_leggings", "leviathan_boots", "sea_elytra",
            "zeus_trident", "hades_scythe", "poseidon_mace", "apollo_crossbow", "ares_shield", "hermes_boots",
            "sphere_fish", "sphere_jellyfish", "sphere_turtle", "sphere_dolphin", "sphere_shark", "sphere_kraken", "sphere_poseidon",
            "talisman_fish", "talisman_jellyfish", "talisman_turtle", "talisman_dolphin", "talisman_shark", "talisman_kraken", "talisman_poseidon",
            "buff_speed", "buff_fire_resistance", "buff_strength", "buff_regeneration",
            "golden_dome", "truth_snow", "aqua_charge", "sea_aura", "temple_wall", "coral_net", "athena_kiss", "reaper_blood", "storm_potion", "fugu_potion", "rage_potion");

    /** Морская лестница сфер/талисманов: от худшего (рыба) к лучшему (Посейдон). */
    public static final List<String> SEA = List.of("fish", "jellyfish", "turtle", "dolphin", "shark", "kraken", "poseidon");
    private static final String[] SEA_NAME = {"Рыбы", "Медузы", "Черепахи", "Дельфина", "Акулы", "Кракена", "Посейдона"};
    private static final String[] SEA_GRAD = {"<gradient:#d6e4ec:#8aa4b3>", "<gradient:#ffb8f1:#ff4fd8>", "<gradient:#c2ffba:#3fd45a>", "<gradient:#c4fff7:#1fc9b6>",
            "<gradient:#ffb8b0:#ff2e2e>", "<gradient:#e6ccff:#9a4dff>", "<gradient:#ffe27a:#2d7bff>"};
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII"};
    /** Скины голов-сфер (головы морских существ по тирам, textures.minecraft.net). Можно заменить в config: custom-items.sphere-textures.<id> (хеш, ссылка или base64 value). */
    private static final Map<String, String> SPHERE_TEX = Map.of(
            "sphere_fish", "30dbbb37ea14d74892d610584716a2f26dcc6770190e40f3f71f2f578f609bda",
            "sphere_jellyfish", "b441e8811827c2114f63430e282339394a378dffa095ccf9f2044f9e295d12d2",
            "sphere_turtle", "8fa552139966c5fac1b98061ce23fc0ddef058c163142dd6d1c768cd2da207c2",
            "sphere_dolphin", "c56f13b5f22d2a4a56e6773ff3f09524df086f4cadcbed543e6212725dfd05de",
            "sphere_shark", "7770e55c87efaabeca8529a2f94e0ac0cc780b4c795d74ccce0c3a26ad7c9c1a",
            "sphere_kraken", "64d62a402cb6b0794371b024082b7e48c169e24e64356c4e0f06db71415ac72d",
            "sphere_poseidon", "366ff9319776ff3aa48109882f0fce8fa6f499475bd258284948e5c6e1da308b");
    /** Старые сферы/талисманы -> новые (заменяются у игроков при входе). */
    private static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("sphere_ares", "sphere_shark"), Map.entry("sphere_athena", "sphere_turtle"), Map.entry("sphere_hermes", "sphere_dolphin"),
            Map.entry("sphere_apollo", "sphere_jellyfish"), Map.entry("sphere_hades", "sphere_kraken"), Map.entry("sphere_chaos", "sphere_poseidon"),
            Map.entry("talisman_power", "talisman_shark"), Map.entry("talisman_guard", "talisman_turtle"), Map.entry("talisman_life", "talisman_jellyfish"),
            Map.entry("talisman_wind", "talisman_dolphin"), Map.entry("talisman_miner", "talisman_fish"), Map.entry("talisman_luck", "talisman_fish"));

    /** Уровень 1..7 для sphere_x / talisman_x, иначе 0. */
    public static int seaTier(String id) {
        if (id == null) return 0;
        int i = id.indexOf('_');
        return i < 0 ? 0 : SEA.indexOf(id.substring(i + 1)) + 1;
    }
    /** Имя с градиентом: "Сфера Рыбы" / "Талисман Посейдона". */
    public static String seaName(String id) {
        int t = seaTier(id);
        if (t == 0) return id;
        return SEA_GRAD[t - 1] + (id.startsWith("sphere_") ? "Сфера " : "Талисман ") + SEA_NAME[t - 1] + "</gradient>";
    }

    private static final org.bukkit.attribute.Attribute DMG = org.bukkit.attribute.Attribute.ATTACK_DAMAGE, HP = org.bukkit.attribute.Attribute.MAX_HEALTH,
            ARMOR = org.bukkit.attribute.Attribute.ARMOR, TOUGH = org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS, SPEED = org.bukkit.attribute.Attribute.MOVEMENT_SPEED,
            KB = org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE, SWIM = org.bukkit.attribute.Attribute.WATER_MOVEMENT_EFFICIENCY, AIR = org.bukkit.attribute.Attribute.OXYGEN_BONUS,
            ATK_SPEED = org.bukkit.attribute.Attribute.ATTACK_SPEED;
    /** Версия баланса сфер/талисманов: предметы с другой версией пересоздаются при входе/открытии сундука. */
    public static final int SEA_VER = 13;
    /** Модификатор атрибута для сфер/талисманов: атрибут, значение, операция, подпись. */
    private record Mod(org.bukkit.attribute.Attribute attr, double v, org.bukkit.attribute.AttributeModifier.Operation op, String text) {}
    private static Mod add(org.bukkit.attribute.Attribute a, double v, String t) { return new Mod(a, v, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, t); }
    private static Mod pct(org.bukkit.attribute.Attribute a, double v, String t) { return new Mod(a, v, org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR, t); }

    /** Сфера (голова со скином) / талисман (тотем бессмертия): бонусы работают ТОЛЬКО во второй руке (модификаторы атрибутов слота OFFHAND). */
    private ItemStack offhand(String id, String desc, Mod... mods) {
        boolean sphere = id.startsWith("sphere_");
        int tier = Math.max(1, seaTier(id));
        String g = SEA_GRAD[tier - 1];
        ItemStack it = sphere ? sphereHead(id) : new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = it.getItemMeta();
        String kind = sphere ? "Сфера" : "Талисман";
        meta.displayName(mm("<!italic><bold>" + g + (sphere ? "◉ " : "✧ ") + kind + " " + SEA_NAME[tier - 1] + "</gradient>"));
        List<Component> l = new ArrayList<>();
        l.add(mm("<!italic><dark_gray>" + kind + " • <gray>Тир " + g + "<bold>" + ROMAN[tier - 1] + "</bold></gradient> <dark_gray>из VII"));
        l.add(mm("<!italic>" + g + "▰".repeat(tier) + "</gradient><dark_gray>" + "▱".repeat(7 - tier)));
        l.add(mm(" "));
        l.add(mm("<!italic><gray><i>" + desc));
        l.add(mm(" "));
        l.add(mm("<!italic><white><bold>Во второй руке:"));
        for (Mod m : mods) l.add(mm("<!italic>" + (m.v() < 0 ? "<#ff6b6b> ▸ " : "<#7cfc9b> ▸ ") + m.text()));
        if (!sphere) l.add(mm("<!italic><#ffd36b> ▸ Спасает от смерти, как тотем <dark_gray>(рассыпается)"));
        l.add(mm(" "));
        l.add(mm("<!italic><dark_gray>⇄ <gray>[<key:key.swapOffhand>]</gray> - взять во вторую руку"));
        l.add(mm("<!italic>" + g + "━━━━━━ ✦ " + kind.toUpperCase(Locale.ROOT) + " ✦ ━━━━━━</gradient>"));
        meta.lore(l);
        meta.setMaxStackSize(1);
        if (!sphere || tier == 7) meta.setEnchantmentGlintOverride(true);
        int i = 0;
        for (Mod m : mods) meta.addAttributeModifier(m.attr(), new org.bukkit.attribute.AttributeModifier(new NamespacedKey(pl, id + "_" + i++), m.v(), m.op(), org.bukkit.inventory.EquipmentSlotGroup.OFFHAND));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(new NamespacedKey(pl, "sea_ver"), PersistentDataType.INTEGER, SEA_VER);
        it.setItemMeta(meta);
        return it;
    }

    /** Голова со скином сферы. */
    public ItemStack sphereHead(String id) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        String tex = textureValue(pl.getConfig().getString("custom-items.sphere-textures." + id, SPHERE_TEX.get(id)));
        if (tex != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            com.destroystokyo.paper.profile.PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(("aqua:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            prof.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", tex));
            sm.setPlayerProfile(prof);
            it.setItemMeta(sm);
        }
        return it;
    }

    /** Хеш / ссылка textures.minecraft.net / готовое base64 value -> base64 value для профиля. */
    private static String textureValue(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        if (s.startsWith("ey")) return s;
        String url = s.startsWith("http") ? s : "http://textures.minecraft.net/texture/" + s;
        return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static boolean isOffhandItem(String id) { return id != null && (id.startsWith("sphere_") || id.startsWith("talisman_")); }

    // ================= создание предметов =================
    private static Enchantment en(String key) { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)); }

    private ItemStack build(String id, Material m, String name, List<String> lore, Object... ench) {
        return build(id, m, name, lore, "<gradient:#ff00aa:#aa00ff>✦ ЭКСКЛЮЗИВ ✦</gradient> <dark_gray>| донат-предмет", ench);
    }

    private ItemStack build(String id, Material m, String name, List<String> lore, String footer, Object[] ench) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm("<!italic>" + s));
        l.add(mm(" "));
        l.add(mm("<!italic>" + footer));
        meta.lore(l);
        for (int i = 0; i < ench.length; i += 2) {
            Enchantment e = en((String) ench[i]);
            if (e != null) meta.addEnchant(e, (Integer) ench[i + 1], true);
        }
        meta.setUnbreakable(false);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    public ItemStack create(String id) {
        return switch (id) {
            case "kratos_blade" -> build(id, Material.NETHERITE_SWORD, "<gradient:#ff2a00:#ffb300><bold>Клинок Кратоса</bold></gradient>",
                    List.of("<gray>Выкован в пламени Олимпа.", "", "<gold>Способности:", "<yellow>• <white>Сила I, пока клинок в руке",
                            "<yellow>• <white>20% шанс: <red>Гнев Спарты</red> - молния, поджог, +4 урона", "<yellow>• <white>Убийство восстанавливает 2 ❤"),
                    "sharpness", 7, "fire_aspect", 3, "looting", 4, "sweeping_edge", 4, "unbreaking", 5, "mending", 1);
            case "leviathan_axe" -> build(id, Material.NETHERITE_AXE, "<gradient:#7fd6ff:#2b6cff><bold>Топор Левиафана</bold></gradient>",
                    List.of("<gray>Холод Ётунхейма в каждом ударе.", "", "<gold>Способности:",
                            "<yellow>• <white>25% шанс: <aqua>Ледяные оковы</aqua> - заморозка и Замедление II на 3 сек"),
                    "sharpness", 6, "efficiency", 6, "unbreaking", 5, "mending", 1);
            case "artemis_bow" -> build(id, Material.BOW, "<gradient:#c8ff7a:#3fbf3f><bold>Лук Артемиды</bold></gradient>",
                    List.of("<gray>Богиня охоты не промахивается.", "", "<gold>Способности:",
                            "<yellow>• <white>Подсвечивает цель на 8 сек", "<yellow>• <white>Бесконечные стрелы"),
                    "power", 7, "punch", 2, "flame", 1, "infinity", 1, "unbreaking", 5);
            case "titan_pickaxe" -> build(id, Material.NETHERITE_PICKAXE, "<gradient:#ffe066:#ff8800><bold>Кирка Титана</bold></gradient>",
                    List.of("<gray>Горы расступаются перед ней.", "", "<gold>Способности:", "<yellow>• <white>Спешка II, пока кирка в руке"),
                    "efficiency", 7, "fortune", 4, "unbreaking", 5, "mending", 1);
            case "hephaestus_drill" -> build(id, Material.NETHERITE_PICKAXE, "<gradient:#ff5e00:#ff0000><bold>Бур Гефеста</bold></gradient>",
                    List.of("<gray>Кузнец богов сделал его для себя.", "", "<gold>Способности:", "<yellow>• <white>Копает <red>3x3</red>",
                            "<yellow>• <white>Автоплавка руды (железо, золото, медь)"),
                    "efficiency", 6, "unbreaking", 5, "mending", 1);
            case "olympus_helmet" -> armor(id, Material.NETHERITE_HELMET, "Шлем Олимпа", "respiration", 3, "aqua_affinity", 1);
            case "olympus_chestplate" -> armor(id, Material.NETHERITE_CHESTPLATE, "Нагрудник Олимпа");
            case "olympus_leggings" -> armor(id, Material.NETHERITE_LEGGINGS, "Поножи Олимпа", "swift_sneak", 3);
            case "olympus_boots" -> armor(id, Material.NETHERITE_BOOTS, "Ботинки Олимпа", "feather_falling", 6, "depth_strider", 3, "soul_speed", 3);
            case "zeus_trident" -> build(id, Material.TRIDENT, "<gradient:#fff36b:#4fb8ff><bold>Трезубец Зевса</bold></gradient>",
                    List.of("<gray>Громовержец одолжил его смертным.", "", "<gold>Способности:", "<yellow>• <white>25% шанс: <yellow>Удар молнии</yellow> +3 урона",
                            "<yellow>• <white>Работает в ближнем бою и при броске"),
                    "impaling", 6, "loyalty", 3, "unbreaking", 5, "mending", 1);
            case "hades_scythe" -> build(id, Material.NETHERITE_SWORD, "<gradient:#5a0080:#1a1a1a><bold>Коса Аида</bold></gradient>",
                    List.of("<gray>Ею Аид собирает души.", "", "<gold>Способности:", "<yellow>• <white>20% шанс: <dark_purple>Жатва душ</dark_purple> - Иссушение II на 3 сек",
                            "<yellow>• <white>и лечит тебя на 1 ❤"),
                    "sharpness", 6, "looting", 3, "unbreaking", 5, "mending", 1);
            case "poseidon_mace" -> build(id, Material.MACE, "<gradient:#00e5ff:#0047ff><bold>Булава Посейдона</bold></gradient>",
                    List.of("<gray>Сила морских глубин.", "", "<gold>Способности:", "<yellow>• <white>20% шанс: <aqua>Волна</aqua> - подбрасывает цель"),
                    "density", 5, "wind_burst", 2, "unbreaking", 5, "mending", 1);
            case "apollo_crossbow" -> build(id, Material.CROSSBOW, "<gradient:#ffd000:#ff6a00><bold>Арбалет Аполлона</bold></gradient>",
                    List.of("<gray>Солнечные стрелы бога света.", "", "<gold>Способности:", "<yellow>• <white>Стрелы поджигают цель на 5 сек", "<yellow>• <white>и подсвечивают её"),
                    "quick_charge", 3, "multishot", 1, "unbreaking", 5, "mending", 1);
            case "ares_shield" -> build(id, Material.SHIELD, "<gradient:#ff3030:#7a0000><bold>Щит Ареса</bold></gradient>",
                    List.of("<gray>Щит бога войны.", "", "<gold>Способности:", "<yellow>• <white>30% шанс при блоке: <red>Возмездие</red> - 3 урона атакующему"),
                    "unbreaking", 5, "mending", 1);
            case "hermes_boots" -> {
                ItemStack it = build(id, Material.NETHERITE_BOOTS, "<gradient:#e0ffff:#7fffd4><bold>Сапоги Гермеса</bold></gradient>",
                        List.of("<gray>Крылатые сапоги вестника богов.", "", "<gold>Бонус:", "<yellow>• <white>+10% скорости передвижения", "<yellow>• <white>+3 блока безопасного падения"),
                        "protection", 5, "feather_falling", 4, "depth_strider", 3, "unbreaking", 5, "mending", 1);
                ItemMeta m = it.getItemMeta();
                try { m.setAttributeModifiers(Material.NETHERITE_BOOTS.getDefaultAttributeModifiers(org.bukkit.inventory.EquipmentSlot.FEET)); } catch (Throwable ignored) {}
                m.addAttributeModifier(org.bukkit.attribute.Attribute.MOVEMENT_SPEED, new org.bukkit.attribute.AttributeModifier(new NamespacedKey(pl, "hermes_speed"), 0.10, org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR, org.bukkit.inventory.EquipmentSlotGroup.FEET));
                m.addAttributeModifier(org.bukkit.attribute.Attribute.SAFE_FALL_DISTANCE, new org.bukkit.attribute.AttributeModifier(new NamespacedKey(pl, "hermes_fall"), 3, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.FEET));
                it.setItemMeta(m);
                yield it;
            }
            // ---------- Чешуя Левиафана: незерит, Защита V ----------
            case "leviathan_helmet" -> leviathan(id, Material.NETHERITE_HELMET, "Шлем", "respiration", 3, "aqua_affinity", 1);
            case "leviathan_chestplate" -> leviathan(id, Material.NETHERITE_CHESTPLATE, "Нагрудник");
            case "leviathan_leggings" -> leviathan(id, Material.NETHERITE_LEGGINGS, "Поножи", "swift_sneak", 2);
            case "leviathan_boots" -> leviathan(id, Material.NETHERITE_BOOTS, "Ботинки", "feather_falling", 4, "depth_strider", 3);
            case "sea_elytra" -> seaElytra();
            // ---------- сферы: голова со скином, сильные бонусы ----------
            // Баланс v12.4: у каждой сферы своя «фишка»; Акула даёт урон ценой -2 ❤.
            case "sphere_fish" -> offhand(id, "Самая простая сфера. С неё все начинают.",
                    add(ARMOR, 1, "+1 брони"));
            case "sphere_jellyfish" -> offhand(id, "Мягкая, но живучая.",
                    add(HP, 2, "+1 ❤ здоровья"), add(ARMOR, 1, "+1 брони"), pct(SPEED, 0.02, "+2% скорости"));
            case "sphere_turtle" -> offhand(id, "Панцирь, который не пробить.",
                    add(HP, 2, "+1 ❤ здоровья"), add(ARMOR, 2, "+2 брони"), add(KB, 0.05, "+5% стойкости к отбрасыванию"));
            case "sphere_dolphin" -> offhand(id, "Скорость и ловкость дельфина.",
                    add(HP, 2, "+1 ❤ здоровья"), add(ARMOR, 1, "+1 брони"), pct(SPEED, 0.07, "+7% скорости"));
            case "sphere_shark" -> offhand(id, "Хищник морей чует кровь.",
                    add(DMG, 2.5, "+2.5 урона"), pct(SPEED, 0.04, "+4% скорости"), add(HP, -4, "-2 ❤ здоровья"));
            case "sphere_kraken" -> offhand(id, "Мощь бездны в твоей руке.",
                    add(DMG, 1, "+1 урона"), add(HP, 2, "+1 ❤ здоровья"), add(ARMOR, 2, "+2 брони"), add(TOUGH, 1, "+1 твёрдости брони"),
                    pct(SPEED, 0.04, "+4% скорости"), add(KB, 0.1, "+10% стойкости к отбрасыванию"));
            case "sphere_poseidon" -> offhand(id, "Сила владыки морей. Легендарная.",
                    add(DMG, 1.5, "+1.5 урона"), add(HP, 4, "+2 ❤ здоровья"), add(ARMOR, 3, "+3 брони"), add(TOUGH, 1, "+1 твёрдости брони"),
                    pct(SPEED, 0.05, "+5% скорости"), add(KB, 0.1, "+10% стойкости к отбрасыванию"), add(AIR, 3, "+3 к запасу воздуха"));
            // ---------- талисманы: тотем бессмертия + бонусы послабее ----------
            case "talisman_fish" -> offhand(id, "Тотем с крупицей морской силы.",
                    add(ARMOR, 1, "+1 брони"));
            case "talisman_jellyfish" -> offhand(id, "Немного живучести.",
                    add(HP, 2, "+1 ❤ здоровья"), add(ARMOR, 1, "+1 брони"));
            case "talisman_turtle" -> offhand(id, "Крепче стоишь на ногах.",
                    add(ARMOR, 1, "+1 брони"), add(HP, 2, "+1 ❤ здоровья"));
            case "talisman_dolphin" -> offhand(id, "Лёгкость шага.",
                    add(HP, 2, "+1 ❤ здоровья"), pct(SPEED, 0.05, "+5% скорости"));
            case "talisman_shark" -> offhand(id, "Острые зубы в бою.",
                    add(DMG, 2, "+2 урона"), add(HP, 2, "+1 ❤ здоровья"), pct(ATK_SPEED, 0.05, "+5% скорости атаки"));
            case "talisman_kraken" -> offhand(id, "Щупальца бездны держат крепко.",
                    add(DMG, 1, "+1 урона"), add(ARMOR, 2, "+2 брони"), add(HP, 2, "+1 ❤ здоровья"));
            case "talisman_poseidon" -> offhand(id, "Благословение владыки морей.",
                    add(DMG, 2, "+2 урона"), add(ARMOR, 2, "+2 брони"), add(HP, 4, "+2 ❤ здоровья"), pct(SPEED, 0.05, "+5% скорости"));
            default -> id.startsWith("buff_") ? buffScroll(id.substring(5)) : pl.special() == null ? null : pl.special().create(id);
        };
    }

    private ItemStack leviathan(String id, Material m, String part, Object... extra) {
        Object[] base = {"protection", 5, "unbreaking", 4, "mending", 1};
        Object[] all = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        ItemStack it = build(id, m, "<gradient:#3ef2ff:#0a5cff><bold>Чешуя Левиафана</bold></gradient> <dark_gray>| <aqua>" + part,
                List.of("<gray>Выкована из чешуи морского змея,", "<gray>что спит на дне Бездны.", "", "<gold>Свойства:", "<yellow>• <white>Защита V, Прочность IV, Починка"), all);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.ArmorMeta am) {
            try { am.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(org.bukkit.inventory.meta.trim.TrimMaterial.DIAMOND, org.bukkit.inventory.meta.trim.TrimPattern.TIDE)); } catch (Throwable ignored) {}
            am.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ARMOR_TRIM);
            it.setItemMeta(am);
        }
        return it;
    }

    /** Элитра моря: только из эпических ящиков монументов. Броня и твёрдость, пока надета. */
    private ItemStack seaElytra() {
        ItemStack it = build("sea_elytra", Material.ELYTRA, "<gradient:#7ff9ff:#0077ff><bold>Элитра моря</bold></gradient>",
                List.of("<gray>Крылья из морской пены и чешуи глубин.", "", "<gold>Пока надета:", "<yellow>• <white>+3 брони и +2 твёрдости брони", "<yellow>• <white>Прочность V, Починка"),
                "<gradient:#7ff9ff:#0077ff>✦ РЕЛИКВИЯ МОНУМЕНТОВ ✦</gradient>", new Object[]{"unbreaking", 5, "mending", 1});
        ItemMeta m = it.getItemMeta();
        m.addAttributeModifier(ARMOR, new org.bukkit.attribute.AttributeModifier(new NamespacedKey(pl, "sea_elytra_armor"), 3, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.CHEST));
        m.addAttributeModifier(TOUGH, new org.bukkit.attribute.AttributeModifier(new NamespacedKey(pl, "sea_elytra_tough"), 2, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.CHEST));
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(m);
        return it;
    }

    /** Свиток вечного бафа (лут ивентов): ПКМ - баф открывается навсегда. Книга знаний не крафтится и не тратится ванильно. */
    public ItemStack buffScroll(String buffId) {
        Buff b = BUFFS.get(buffId);
        if (b == null) return null;
        ItemStack it = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta m = it.getItemMeta();
        try { m.setItemModel(b.icon().getKey()); } catch (Throwable ignored) {}
        m.displayName(mm("<!italic><gradient:#ff9cf1:#9d5cff><bold>Вечный баф: " + b.name() + "</bold></gradient>"));
        List<Component> l = new ArrayList<>();
        l.add(mm("<!italic><gray>ПКМ - открыть баф <white>навсегда"));
        l.add(mm("<!italic><gray>Включить/выключить: <white>/buffs"));
        if (pvpOff(b)) l.add(mm("<!italic><red>Не действует в режиме боя (PvP)"));
        l.add(mm(" "));
        l.add(mm("<!italic><gradient:#ff9cf1:#9d5cff>✦ ДОНАТ-БАФ ✦</gradient>"));
        m.lore(l);
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(16);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "buff_" + b.id());
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBuffScroll(org.bukkit.event.player.PlayerInteractEvent e) {
        if (!e.getAction().isRightClick() || e.getItem() == null) return;
        String id = id(e.getItem());
        if (id == null || !id.startsWith("buff_")) return;
        e.setCancelled(true);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        Buff b = BUFFS.get(id.substring(5));
        Player p = e.getPlayer();
        if (b == null) return;
        PlayerData d = pl.data(p);
        if (d.buffs.contains(b.id())) { p.sendActionBar(mm("<yellow>Баф " + b.name() + " уже открыт - свиток можно продать или подарить")); return; }
        d.buffs.add(b.id());
        d.buffsOff.remove(b.id());
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        pl.msg(p, "<light_purple>Вечный баф <white>" + b.name() + "</white> открыт навсегда! <gray>Вкл/выкл: /buffs");
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.WITCH, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
    }

    /** Вход в режим боя: снять регенерацию от бафа сразу (ей нельзя лечиться в PvP). */
    public void combatStarted(Player p) {
        PlayerData d = pl.data(p);
        for (String b : d.buffs) {
            Buff buff = BUFFS.get(b);
            if (buff == null || !pvpOff(buff)) continue;
            PotionEffect cur = p.getPotionEffect(buff.type());
            if (cur != null && cur.isAmbient() && cur.getAmplifier() == buff.amp() && cur.getDuration() <= BUFF_TICKS) p.removePotionEffect(buff.type());
        }
    }

    private ItemStack armor(String id, Material m, String name, Object... extra) {
        Object[] base = {"protection", 6, "unbreaking", 5, "mending", 1};
        Object[] all = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return build(id, m, "<gradient:#ffffff:#ffd700><bold>" + name + "</bold></gradient>",
                List.of("<gray>Доспех богов Олимпа.", "", "<gold>Бонус полного сета:", "<yellow>• <white>Сопротивление I + Огнестойкость"), all);
    }

    public ItemStack spawner(EntityType type) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        BlockStateMeta meta = (BlockStateMeta) it.getItemMeta();
        CreatureSpawner cs = (CreatureSpawner) meta.getBlockState();
        cs.setSpawnedType(type);
        meta.setBlockState(cs);
        meta.displayName(mm("<!italic><gradient:#ff7a00:#ffd000>Спавнер: " + mobName(type) + "</gradient>"));
        meta.lore(List.of(mm("<!italic><gray>Поставь - и мобы начнут появляться"), mm("<!italic><dark_gray>Ломается только киркой с Шёлковым касанием")));
        meta.getPersistentDataContainer().set(SPAWNER, PersistentDataType.STRING, type.name());
        it.setItemMeta(meta);
        return it;
    }

    public static String mobName(EntityType t) {
        return switch (t) {
            case ZOMBIE -> "Зомби"; case SKELETON -> "Скелет"; case SPIDER -> "Паук"; case CREEPER -> "Крипер";
            case BLAZE -> "Ифрит"; case MAGMA_CUBE -> "Магмовый куб"; case ENDERMAN -> "Эндермен"; case IRON_GOLEM -> "Железный голем";
            case PIG -> "Свинья"; case COW -> "Корова"; default -> t.name();
        };
    }

    public String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    // ================= эффекты =================
    private void effectsTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = pl.data(p);
            if (!d.authed) continue;
            String hand = id(p.getInventory().getItemInMainHand());
            if ("kratos_blade".equals(hand)) eff(p, PotionEffectType.STRENGTH, 0);
            if ("titan_pickaxe".equals(hand)) eff(p, PotionEffectType.HASTE, 1);
            int set = 0;
            for (ItemStack a : p.getInventory().getArmorContents()) { String i = id(a); if (i != null && i.startsWith("olympus_")) set++; }
            if (set == 4) { eff(p, PotionEffectType.RESISTANCE, 0); eff(p, PotionEffectType.FIRE_RESISTANCE, 0); }
            boolean combat = d.inCombat();
            for (String b : d.buffs) {
                Buff buff = BUFFS.get(b);
                if (buff == null || d.buffsOff.contains(b) || combat && pvpOff(buff)) continue;
                eff(p, buff.type(), buff.amp());
            }
        }
    }

    private static void eff(Player p, PotionEffectType t, int amp) {
        int dur = t == PotionEffectType.NIGHT_VISION ? 400 : BUFF_TICKS;
        PotionEffect cur = p.getPotionEffect(t);
        if (cur != null && (cur.getAmplifier() > amp || cur.isInfinite() || (cur.getAmplifier() == amp && cur.getDuration() > dur - 50))) return;
        p.addPotionEffect(new PotionEffect(t, dur, amp, true, false, true));
    }

    // ================= способности =================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (e.getDamager() instanceof Player p) {
            String id = id(p.getInventory().getItemInMainHand());
            if ("kratos_blade".equals(id) && r.nextInt(100) < 20) {
                target.getWorld().strikeLightningEffect(target.getLocation());
                target.setFireTicks(120);
                e.setDamage(e.getDamage() + 4);
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.05);
                p.sendActionBar(mm("<gradient:#ff2a00:#ffb300><bold>⚔ ГНЕВ СПАРТЫ!"));
            } else if ("leviathan_axe".equals(id) && r.nextInt(100) < 25) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                target.setFreezeTicks(Math.max(target.getFreezeTicks(), 160));
                target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 50, 0.4, 0.7, 0.4, 0.02);
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
                p.sendActionBar(mm("<aqua><bold>❄ ЛЕДЯНЫЕ ОКОВЫ!"));
            }
            else if ("zeus_trident".equals(id) && r.nextInt(100) < 25) zeus(p, target, e);
            else if ("hades_scythe".equals(id) && r.nextInt(100) < 20) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                p.setHealth(Math.min(attr == null ? 20 : attr.getValue(), p.getHealth() + 2));
                target.getWorld().spawnParticle(Particle.SOUL, target.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.03);
                p.sendActionBar(mm("<dark_purple><bold>☠ ЖАТВА ДУШ!"));
            } else if ("poseidon_mace".equals(id) && r.nextInt(100) < 20) {
                Bukkit.getScheduler().runTask(pl, () -> { if (target.isValid()) target.setVelocity(target.getVelocity().setY(0.9)); });
                target.getWorld().spawnParticle(Particle.SPLASH, target.getLocation(), 80, 0.6, 0.3, 0.6, 0.2);
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.8f);
                p.sendActionBar(mm("<aqua><bold>🌊 ВОЛНА ПОСЕЙДОНА!"));
            }
        } else if (e.getDamager() instanceof Trident tr && "zeus_trident".equals(id(tr.getItemStack())) && tr.getShooter() instanceof Player p && r.nextInt(100) < 25) {
            zeus(p, target, e);
        } else if (e.getDamager() instanceof AbstractArrow ar && ar.getPersistentDataContainer().has(APOLLO)) {
            target.setFireTicks(Math.max(target.getFireTicks(), 100));
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
            target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
        } else if (e.getDamager() instanceof Arrow arrow && arrow.getPersistentDataContainer().has(ARROW)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 160, 0));
            target.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3);
        }
    }

    private void zeus(Player p, LivingEntity target, EntityDamageByEntityEvent e) {
        target.getWorld().strikeLightningEffect(target.getLocation());
        e.setDamage(e.getDamage() + 3);
        p.sendActionBar(mm("<yellow><bold>⚡ ГНЕВ ЗЕВСА!"));
    }

    private boolean reflecting;

    /** Щит Ареса: возмездие при блоке. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockHit(EntityDamageByEntityEvent e) {
        if (reflecting || !(e.getEntity() instanceof Player v) || !v.isBlocking()) return;
        if (!(e.getDamager() instanceof LivingEntity att) || att.equals(v)) return;
        if (!"ares_shield".equals(id(v.getInventory().getItemInMainHand())) && !"ares_shield".equals(id(v.getInventory().getItemInOffHand()))) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 30) return;
        reflecting = true;
        try { att.damage(3, v); } finally { reflecting = false; }
        att.getWorld().spawnParticle(Particle.CRIT, att.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);
        v.sendActionBar(mm("<red><bold>🛡 ВОЗМЕЗДИЕ!"));
    }

    /** Сферу-голову нельзя поставить блоком. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlaceSphere(BlockPlaceEvent e) {
        if (isOffhandItem(id(e.getItemInHand()))) e.setCancelled(true);
    }

    /** Талисман сработал как тотем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(org.bukkit.event.entity.EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getHand() == null) return;
        String id = id(p.getInventory().getItem(e.getHand()));
        if (id != null && id.startsWith("talisman_"))
            p.sendActionBar(mm(seaName(id) + " <yellow>спас тебя от смерти и рассыпался"));
    }

    /** Старые сферы/талисманы (Арес, Афина, ...) и предметы старого вида меняются на новые при входе. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinUpgrade(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        int n = upgrade(p.getInventory(), p.getLocation()) + upgrade(p.getEnderChest(), p.getLocation());
        if (n > 0) Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) pl.msg(p, "<aqua>Сферы и талисманы обновлены под новый баланс: <white>" + n + " шт."); }, 60);
    }

    /** Сундуки/шалкеры: сферы старого баланса обновляются при открытии. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenUpgrade(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof org.bukkit.block.Container || e.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest) upgrade(e.getInventory(), e.getInventory().getLocation() != null ? e.getInventory().getLocation() : e.getPlayer().getLocation());
    }

    private int upgrade(org.bukkit.inventory.Inventory inv, org.bukkit.Location dropAt) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String id = id(it);
            if (id == null || !isOffhandItem(id)) continue;
            String to = LEGACY.getOrDefault(id, id);
            Integer ver = it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(pl, "sea_ver"), PersistentDataType.INTEGER);
            boolean old = !to.equals(id) || ver == null || ver != SEA_VER || (to.startsWith("sphere_") ? it.getType() != Material.PLAYER_HEAD : it.getType() != Material.TOTEM_OF_UNDYING);
            if (!old) continue;
            ItemStack fresh = create(to);
            if (fresh == null) continue;
            int amount = Math.max(1, it.getAmount());
            inv.setItem(i, fresh);
            // новые сферы не стакаются - остальные копии из старого стака выдаём отдельно, а не удаляем
            for (int k = 1; k < amount; k++) {
                ItemStack extra = create(to);
                for (ItemStack left : inv.addItem(extra).values())
                    if (dropAt != null && dropAt.getWorld() != null) dropAt.getWorld().dropItemNaturally(dropAt, left);
            }
            n += amount;
        }
        return n;
    }

    /** Сферы и талисманы нельзя поставить/использовать как обычный предмет. */
    @EventHandler(priority = EventPriority.LOW)
    public void onUseOffhandItem(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getItem() != null && isOffhandItem(id(e.getItem())) && e.getAction().isRightClick()) e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if ("apollo_crossbow".equals(id(e.getBow())) && e.getProjectile() instanceof AbstractArrow a) a.getPersistentDataContainer().set(APOLLO, PersistentDataType.BYTE, (byte) 1);
        if ("artemis_bow".equals(id(e.getBow())) && e.getProjectile() instanceof Arrow a) {
            a.getPersistentDataContainer().set(ARROW, PersistentDataType.BYTE, (byte) 1);
            a.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k != null && "kratos_blade".equals(id(k.getInventory().getItemInMainHand()))) {
            var attr = k.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double max = attr == null ? 20 : attr.getValue();
            k.setHealth(Math.min(max, k.getHealth() + 4));
        }
    }

    // ---------- бур 3x3 + автоплавка ----------
    private static Material smelt(Material m) {
        return switch (m) {
            case RAW_IRON -> Material.IRON_INGOT; case RAW_GOLD -> Material.GOLD_INGOT; case RAW_COPPER -> Material.COPPER_INGOT;
            default -> m;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        if (!"hephaestus_drill".equals(id(e.getPlayer().getInventory().getItemInMainHand()))) return;
        for (Item i : e.getItems()) {
            ItemStack s = i.getItemStack();
            Material to = smelt(s.getType());
            if (to != s.getType()) i.setItemStack(new ItemStack(to, s.getAmount()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        // спавнер с шёлковым касанием
        if (b.getType() == Material.SPAWNER && b.getState() instanceof CreatureSpawner cs && cs.getSpawnedType() != null) {
            ItemStack tool = p.getInventory().getItemInMainHand();
            Enchantment silk = en("silk_touch");
            if (silk != null && tool.containsEnchantment(silk) && tool.getType().name().endsWith("_PICKAXE")) {
                e.setExpToDrop(0);
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), spawner(cs.getSpawnedType()));
            }
        }
        if (drilling) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!"hephaestus_drill".equals(id(tool))) return;
        float pitch = p.getLocation().getPitch();
        BlockFace face = p.getFacing();
        List<Block> extra = new ArrayList<>();
        for (int a = -1; a <= 1; a++) for (int c = -1; c <= 1; c++) {
            if (a == 0 && c == 0) continue;
            Block x;
            if (Math.abs(pitch) > 50) x = b.getRelative(a, 0, c);
            else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) x = b.getRelative(a, c, 0);
            else x = b.getRelative(0, c, a);
            extra.add(x);
        }
        drilling = true;
        try {
            for (Block x : extra) {
                Material m = x.getType();
                if (m.isAir() || x.isLiquid() || m.getHardness() < 0 || m.getHardness() > 50 || m == Material.SPAWNER) continue;
                if (!pl.regions().canBuild(p, x.getLocation())) continue;
                BlockBreakEvent ev = new BlockBreakEvent(x, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) continue;
                Collection<ItemStack> drops = x.getDrops(tool, p);
                x.setType(Material.AIR);
                for (ItemStack d : drops) {
                    d.setType(smelt(d.getType()));
                    x.getWorld().dropItemNaturally(x.getLocation().add(0.5, 0.5, 0.5), d);
                }
            }
        } finally { drilling = false; }
    }

    // ---------- установка спавнера ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (it.getType() != Material.SPAWNER || !it.hasItemMeta()) return;
        String t = it.getItemMeta().getPersistentDataContainer().get(SPAWNER, PersistentDataType.STRING);
        if (t == null) return;
        try {
            EntityType type = EntityType.valueOf(t);
            Block b = e.getBlockPlaced();
            Bukkit.getScheduler().runTask(pl, () -> {
                if (b.getState() instanceof CreatureSpawner cs) { cs.setSpawnedType(type); cs.update(true, false); }
            });
        } catch (Exception ignored) {}
    }
}
