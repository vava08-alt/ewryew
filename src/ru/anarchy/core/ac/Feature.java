package ru.anarchy.core.ac;

/**
 * Признаки (улики), из которых ИИ складывает вероятность чита.
 * weight - стартовый вес в модели, k - сколько "очков" улики дают насыщение (1 - e^-s/k),
 * halfLife - период полураспада улики в секундах (0 - не затухает), strong - может одна вызвать на проверку.
 */
public enum Feature {
    AIM_SNAP("Снап-аим (резкий доворот на цель)", 7.0, 2.5, 600, false),
    AIM_SPIKE("Silent-aim (взгляд дёргается к цели и обратно)", 10.5, 2.5, 900, true),
    AIM_LOCK("Прицел приклеен к одной точке цели", 6.0, 2.0, 900, false),
    AIM_ROBOTIC("Роботизированные повороты головы", 4.5, 2.0, 900, false),
    AIM_SENS("Повороты не от мыши (нет шага сенсы)", 3.5, 2.0, 900, false),
    AURA_MULTI("Удары по нескольким целям за один тик", 10.5, 2.5, 1200, true),
    AURA_FOV("Удары вне прицела / за спину", 10.5, 2.5, 1200, true),
    AURA_WALL("Удары сквозь стены", 7.0, 2.0, 900, false),
    AURA_NOSWING("Удары без взмаха рукой", 8.0, 2.0, 900, false),
    AURA_TIMING("Идеальный тайминг ударов (бот)", 5.0, 2.0, 900, false),
    AURA_GUI("Удары с открытым сундуком/инвентарём", 7.0, 2.0, 900, false),
    CLICKER("Автокликер", 6.5, 2.0, 900, false),
    CRYSTAL("Кристалл-аура", 10.0, 3.0, 900, true),
    ANCHOR("Якорь-аура", 7.0, 2.0, 900, false),
    TOTEM("Автототем", 11.0, 2.5, 1800, true),
    INVMOVE("Инвентарь на ходу (InvMove / автоброня)", 6.0, 2.0, 900, false),
    STEALER("Чест-стилер", 4.0, 2.0, 900, false),
    XRAY("X-Ray (подозрительная добыча руды)", 7.5, 1.0, 1800, false),
    CLIENT("Чит-клиент / запрещённый мод", 14.0, 1.0, 0, true),
    GRIM_COMBAT("Grim: Reach / Hitbox", 10.0, 4.0, 600, true),
    GRIM_MOVE("Grim: движение (fly, speed, nofall...)", 10.0, 6.0, 300, true),
    GRIM_TIMER("Grim: Timer", 7.0, 3.0, 300, false),
    GRIM_PACKET("Grim: невалидные пакеты", 7.0, 3.0, 600, false),
    GRIM_PLACE("Grim: Scaffold / постройка", 7.0, 3.0, 600, false),
    GRIM_BREAK("Grim: Nuker / FastBreak", 6.0, 3.0, 600, false),
    GRIM_AIM("Grim: Aim", 3.5, 2.0, 600, false),
    AURA_ACCURACY("Удары без единого промаха", 6.0, 2.0, 900, false),
    AURA_SPRINT("Спринт ботом (пакетный спринт / идеальный W-tap)", 7.0, 2.0, 900, false),
    AIM_TRACK("Прицел повторяет движение цели (аимбот)", 6.0, 2.0, 900, false);

    public final String title;
    public final double weight, k;
    public final int halfLife;
    public final boolean strong;

    Feature(String title, double weight, double k, int halfLife, boolean strong) {
        this.title = title; this.weight = weight; this.k = k; this.halfLife = halfLife; this.strong = strong;
    }

    public static final Feature[] ALL = values();

    public static Feature byName(String s) {
        for (Feature f : ALL) if (f.name().equalsIgnoreCase(s)) return f;
        return null;
    }
}
