package ru.anarchy.core;

import java.util.List;

public record Rank(String id, String display, String prefix, String nameColor, String chatColor, int weight,
                   int homes, int regions, int rtpCooldown, int warmup, boolean chatCooldown, double killMultiplier,
                   long priceAri, String icon, boolean hidden, List<String> perks, List<String> description) {
}
