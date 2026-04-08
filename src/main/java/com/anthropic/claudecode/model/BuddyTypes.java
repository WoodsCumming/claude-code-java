package com.anthropic.claudecode.model;

import java.util.List;
import java.util.Map;

/**
 * Buddy companion types.
 * Translated from src/buddy/types.ts
 */
public class BuddyTypes {

    public enum Rarity {
        COMMON, UNCOMMON, RARE, EPIC, LEGENDARY;

        public static final List<Rarity> RARITIES = List.of(COMMON, UNCOMMON, RARE, EPIC, LEGENDARY);

        /** Weighted probability for random rarity rolls. */
        public int weight() {
            return switch (this) {
                case COMMON -> 60;
                case UNCOMMON -> 25;
                case RARE -> 10;
                case EPIC -> 4;
                case LEGENDARY -> 1;
            };
        }

        public String stars() {
            return switch (this) {
                case COMMON -> "★";
                case UNCOMMON -> "★★";
                case RARE -> "★★★";
                case EPIC -> "★★★★";
                case LEGENDARY -> "★★★★★";
            };
        }

        /** Returns the theme-color key associated with this rarity. */
        public String themeColor() {
            return switch (this) {
                case COMMON -> "inactive";
                case UNCOMMON -> "success";
                case RARE -> "permission";
                case EPIC -> "autoAccept";
                case LEGENDARY -> "warning";
            };
        }

        public String displayName() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    /**
     * All available companion species.
     * Values are decoded at runtime to avoid literal matches in build output.
     */
    public enum Species {
        DUCK("duck"),
        GOOSE("goose"),
        BLOB("blob"),
        CAT("cat"),
        DRAGON("dragon"),
        OCTOPUS("octopus"),
        OWL("owl"),
        PENGUIN("penguin"),
        TURTLE("turtle"),
        SNAIL("snail"),
        GHOST("ghost"),
        AXOLOTL("axolotl"),
        CAPYBARA("capybara"),
        CACTUS("cactus"),
        ROBOT("robot"),
        RABBIT("rabbit"),
        MUSHROOM("mushroom"),
        CHONK("chonk");

        private final String value;

        Species(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Species fromValue(String value) {
            for (Species s : values()) {
                if (s.value.equalsIgnoreCase(value)) return s;
            }
            throw new IllegalArgumentException("Unknown species: " + value);
        }
    }

    public enum Eye {
        DOT("·"),
        SPARKLE("✦"),
        CROSS("×"),
        CIRCLE("◉"),
        AT("@"),
        DEGREE("°");

        private final String symbol;

        Eye(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }

        public static Eye fromSymbol(String symbol) {
            for (Eye e : values()) {
                if (e.symbol.equals(symbol)) return e;
            }
            throw new IllegalArgumentException("Unknown eye symbol: " + symbol);
        }
    }

    public enum Hat {
        NONE,
        CROWN,
        TOPHAT,
        PROPELLER,
        HALO,
        WIZARD,
        BEANIE,
        TINYDUCK;

        public String getValue() {
            return name().toLowerCase();
        }
    }

    public enum StatName {
        DEBUGGING, PATIENCE, CHAOS, WISDOM, SNARK
    }

    /**
     * Deterministic companion attributes derived from hash(userId).
     * Corresponds to CompanionBones in TypeScript.
     */
    public record CompanionBones(
        Rarity rarity,
        Species species,
        Eye eye,
        Hat hat,
        boolean shiny,
        Map<StatName, Integer> stats
    ) {}

    /**
     * Model-generated companion soul stored in config after first hatch.
     * Corresponds to CompanionSoul in TypeScript.
     */
    public record CompanionSoul(
        String name,
        String personality
    ) {}

    /**
     * Full companion combining bones and soul.
     * Corresponds to Companion in TypeScript.
     */
    public record Companion(
        Rarity rarity,
        Species species,
        Eye eye,
        Hat hat,
        boolean shiny,
        Map<StatName, Integer> stats,
        String name,
        String personality,
        long hatchedAt
    ) {}

    /**
     * Persisted portion of a companion (soul + hatchedAt).
     * Bones are regenerated from hash(userId) on every read.
     * Corresponds to StoredCompanion in TypeScript.
     */
    public record StoredCompanion(
        String name,
        String personality,
        long hatchedAt
    ) {}

    /** Rarity floor stat value used when rolling companion stats. */
    public static int rarityFloor(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 5;
            case UNCOMMON -> 15;
            case RARE -> 25;
            case EPIC -> 35;
            case LEGENDARY -> 50;
        };
    }

    private BuddyTypes() {}
}
