package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.BuddyTypes;
import com.anthropic.claudecode.model.BuddyTypes.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Buddy companion generation service.
 * Translated from src/buddy/companion.ts
 *
 * Generates deterministic companion attributes from a user ID seed, with a
 * simple in-process cache to avoid redundant recomputation on hot paths.
 */
@Slf4j
@Service
public class BuddyCompanionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuddyCompanionService.class);


    private static final String SALT = "friend-2026-401";

    /** Result of a companion roll: deterministic bones + a seed for further generation. */
    public record Roll(CompanionBones bones, long inspirationSeed) {}

    // Simple single-entry roll cache keyed on userId+SALT
    private record RollCacheEntry(String key, Roll value) {}
    private volatile RollCacheEntry rollCache;

    // -------------------------------------------------------------------------
    // Mulberry32 — tiny seeded PRNG, good enough for picking companions
    // -------------------------------------------------------------------------

    /**
     * Creates a Mulberry32 PRNG supplier from the given seed.
     * Produces values in [0.0, 1.0).
     */
    private static Supplier<Double> mulberry32(long seed) {
        long[] state = {seed & 0xFFFFFFFFL};
        return () -> {
            state[0] = (state[0] + 0x6d2b79f5L) & 0xFFFFFFFFL;
            long t = imul(state[0] ^ (state[0] >>> 15), 1 | state[0]);
            t = (t + imul(t ^ (t >>> 7), 61 | t)) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        };
    }

    /** 32-bit integer multiply mimicking JavaScript's Math.imul. */
    private static long imul(long a, long b) {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    // -------------------------------------------------------------------------
    // FNV-1a 32-bit hash (matches TypeScript hashString fallback)
    // -------------------------------------------------------------------------

    private static long hashString(String s) {
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h = imul(h, 16777619L);
        }
        return h & 0xFFFFFFFFL;
    }

    // -------------------------------------------------------------------------
    // Roll helpers
    // -------------------------------------------------------------------------

    private static <T> T pick(Supplier<Double> rng, T[] arr) {
        return arr[(int) (rng.get() * arr.length)];
    }

    private static Rarity rollRarity(Supplier<Double> rng) {
        int total = 0;
        for (Rarity r : Rarity.RARITIES) {
            total += r.weight();
        }
        double roll = rng.get() * total;
        for (Rarity rarity : Rarity.RARITIES) {
            roll -= rarity.weight();
            if (roll < 0) return rarity;
        }
        return Rarity.COMMON;
    }

    private static Map<StatName, Integer> rollStats(Supplier<Double> rng, Rarity rarity) {
        int floor = BuddyTypes.rarityFloor(rarity);
        StatName[] statNames = StatName.values();

        StatName peak = pick(rng, statNames);
        StatName dump;
        do {
            dump = pick(rng, statNames);
        } while (dump == peak);

        Map<StatName, Integer> stats = new EnumMap<>(StatName.class);
        for (StatName name : statNames) {
            if (name == peak) {
                stats.put(name, Math.min(100, floor + 50 + (int) (rng.get() * 30)));
            } else if (name == dump) {
                stats.put(name, Math.max(1, floor - 10 + (int) (rng.get() * 15)));
            } else {
                stats.put(name, floor + (int) (rng.get() * 40));
            }
        }
        return stats;
    }

    private static Roll rollFrom(Supplier<Double> rng) {
        Rarity rarity = rollRarity(rng);
        Hat hat = rarity == Rarity.COMMON ? Hat.NONE : pick(rng, Hat.values());
        CompanionBones bones = new CompanionBones(
            rarity,
            pick(rng, Species.values()),
            pick(rng, Eye.values()),
            hat,
            rng.get() < 0.01,
            rollStats(rng, rarity)
        );
        long inspirationSeed = (long) (rng.get() * 1_000_000_000L);
        return new Roll(bones, inspirationSeed);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Rolls deterministic companion attributes for the given userId.
     * Result is cached for the hot-path use cases (sprite tick, keystroke, observer).
     * Translated from roll() in companion.ts
     */
    public Roll roll(String userId) {
        String key = userId + SALT;
        RollCacheEntry cached = rollCache;
        if (cached != null && cached.key().equals(key)) {
            return cached.value();
        }
        Roll value = rollFrom(mulberry32(hashString(key)));
        rollCache = new RollCacheEntry(key, value);
        return value;
    }

    /**
     * Rolls companion attributes from an arbitrary seed string (no cache).
     * Translated from rollWithSeed() in companion.ts
     */
    public Roll rollWithSeed(String seed) {
        return rollFrom(mulberry32(hashString(seed)));
    }

    /**
     * Resolves the effective user ID for companion generation.
     * Falls back to "anon" when no authenticated account is available.
     * Translated from companionUserId() in companion.ts
     */
    public String companionUserId(String accountUuid, String userId) {
        if (accountUuid != null && !accountUuid.isBlank()) return accountUuid;
        if (userId != null && !userId.isBlank()) return userId;
        return "anon";
    }

    /**
     * Reconstructs a full Companion by merging regenerated bones with a stored soul.
     * Bones are always regenerated so edits to the SPECIES list or the stored
     * companion config cannot break or fake rarities.
     * Translated from getCompanion() in companion.ts
     *
     * @param stored  the persisted soul (name, personality, hatchedAt); null if no companion
     * @param userId  the resolved companion user ID (from companionUserId())
     * @return a fully merged Companion, or null if no stored companion exists
     */
    public Companion getCompanion(StoredCompanion stored, String userId) {
        if (stored == null) return null;
        CompanionBones bones = roll(userId).bones();
        return new Companion(
            bones.rarity(),
            bones.species(),
            bones.eye(),
            bones.hat(),
            bones.shiny(),
            bones.stats(),
            stored.name(),
            stored.personality(),
            stored.hatchedAt()
        );
    }
}
