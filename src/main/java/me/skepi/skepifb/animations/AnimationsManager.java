package me.skepi.skepifb.animations;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class AnimationsManager {
    private final SkepiFBPlugin plugin;
    private final ShopManager shopManager;

    public AnimationsManager(SkepiFBPlugin plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
    }

    public Optional<String> getEquippedAnimation(UUID playerUuid) {
        return shopManager.getEquippedShopItem(playerUuid, "reset_animation");
    }

    public String normalizeAnimationName(String animationName) {
        if (animationName == null) {
            return "NONE";
        }
        String normalized = animationName.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("animations:")) {
            normalized = normalized.substring("animations:".length());
        } else if (normalized.startsWith("animation:")) {
            normalized = normalized.substring("animation:".length());
        } else if (normalized.startsWith("anim:")) {
            normalized = normalized.substring("anim:".length());
        }

        if (normalized.contains(":")) {
            normalized = normalized.substring(normalized.lastIndexOf(":") + 1);
        }

        if ("creative_mode2".equals(normalized) || normalized.startsWith("magic")) {
            return "MAGIC_SWIRL";
        }
        if (normalized.startsWith("creative")) {
            return "CREATIVE_MODE";
        }
        if (normalized.startsWith("fire")) {
            return "FIRE";
        }
        if (normalized.startsWith("ice")) {
            return "ICE_SHATTER";
        }
        if (normalized.startsWith("fall")) {
            return "FALLING";
        }
        if (normalized.startsWith("blue")) {
            return "BLUE_ENERGY";
        }
        if (normalized.startsWith("magic")) {
            return "MAGIC_SWIRL";
        }
        if (normalized.startsWith("none")) {
            return "NONE";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String describeLocation(Location location) {
        if (location == null) {
            return "null";
        }
        return "x=" + String.format(Locale.ROOT, "%.3f", location.getX())
                + " y=" + String.format(Locale.ROOT, "%.3f", location.getY())
                + " z=" + String.format(Locale.ROOT, "%.3f", location.getZ());
    }

    public boolean playAnimationStep(Player player, Location location, Entity visualEntity, Material previousMaterial, String animationName, int phase, Object creativeNpc) {
        if (player == null || location == null) {
            return true;
        }
        String anim = normalizeAnimationName(animationName);
        World world = location.getWorld();
        if (world == null) {
            return true;
        }

        try {
            // Use exact block coordinates for visual entity placement, but center
            // particle effects on the visible block center.
            Location visualCenter = location.clone();
            Location particleCenter = location.clone().add(0.5, 0.5, 0.5);
            if (phase == 1 && Boolean.getBoolean("skepifb.debug")) {
                // Very low-volume, opt-in diagnostic (enable with -Dskepifb.debug=true on server
                // start) confirming this exact animation code path is executing for a given block -
                // useful for telling "the new animation code isn't running at all (stale jar)" apart
                // from "it's running but a specific effect is wrong". Off by default so normal
                // resets on large builds don't spam console with one line per block.
                plugin.getLogger().info("[SkepiFB] animation " + anim + " step 1 for " + player.getName() + " at " + describeLocation(location));
            }
            switch (anim) {
                case "FALLING": {
                    // The FALLING style no longer uses this method at all - it is driven entirely by
                    // TimerManager's runSequentialFallingTick, using a real Bukkit FallingBlock
                    // entity with real gravity/physics instead of manual per-tick teleporting. This
                    // case is unreachable in normal operation; kept only as a safe fallback.
                    return true;
                }
                case "ICE_SHATTER": {
                    if (visualEntity instanceof BlockDisplay display && previousMaterial != null && previousMaterial != Material.AIR) {
                        if (phase <= 10) {
                            display.setBlock(Material.PACKED_ICE.createBlockData());
                            world.spawnParticle(Particle.ITEM_SNOWBALL, particleCenter, 10, 0.28, 0.28, 0.28, 0.01);
                            world.spawnParticle(Particle.SNOWFLAKE, particleCenter, 5, 0.3, 0.3, 0.3, 0.02);
                            world.spawnParticle(Particle.CLOUD, particleCenter, 4, 0.16, 0.16, 0.16, 0.01);
                            if (phase == 1) {
                                world.playSound(particleCenter, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.1f);
                            }
                            return false;
                        }
                        display.setBlock(Material.AIR.createBlockData());
                        world.spawnParticle(Particle.SNOWFLAKE, particleCenter, 16, 0.35, 0.4, 0.35, 0.05);
                        world.spawnParticle(Particle.CLOUD, particleCenter, 9, 0.3, 0.35, 0.3, 0.03);
                        world.spawnParticle(Particle.ITEM_SNOWBALL, particleCenter, 11, 0.3, 0.3, 0.3, 0.03);
                        world.playSound(particleCenter, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.9f);
                        return true;
                    }
                    return true;
                }
                case "FIRE": {
                    world.spawnParticle(Particle.FLAME, particleCenter, 8, 0.22, 0.22, 0.22, 0.02);
                    world.spawnParticle(Particle.SMOKE, particleCenter, 6, 0.2, 0.2, 0.2, 0.02);
                    world.spawnParticle(Particle.LAVA, particleCenter, 1, 0.15, 0.15, 0.15);
                    if (phase == 1) {
                        world.playSound(visualCenter, Sound.ENTITY_BLAZE_SHOOT, 0.9f, 1.0f);
                    }
                    if (phase >= 9) {
                        world.spawnParticle(Particle.FLAME, particleCenter, 14, 0.35, 0.35, 0.35, 0.04);
                        world.spawnParticle(Particle.LARGE_SMOKE, particleCenter, 6, 0.3, 0.3, 0.3, 0.02);
                        world.playSound(visualCenter, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.6f);
                        return true;
                    }
                    return false;
                }
                case "BLUE_ENERGY": {
                    // NOTE: the DUST particles here previously passed large offsetY/offsetZ values
                    // (0.0, 0.8, 1.0) alongside a particle count of 1 - for any count >= 1 those
                    // offsets are a per-particle RANDOM SCATTER radius, not a fixed direction, so
                    // most of the colored dust was being flung far away from the swirl instead of
                    // sitting in the ring, leaving only the small END_ROD particles visibly in
                    // place. Offsets are now 0 so every dust particle renders exactly where it's
                    // meant to.
                    // Ring point count halved (16 -> 8) and the burst counts below cut roughly in
                    // half too - with several blocks animating in the same burst, the previous
                    // counts added up to an overwhelming amount of particles on screen at once.
                    for (int i = 0; i < 8; i++) {
                        double angle = (i / 8.0) * Math.PI * 2.0 + (phase * 0.5);
                        Vector offset = new Vector(Math.cos(angle) * 0.42, 0.16 + (i % 3) * 0.05, Math.sin(angle) * 0.42);
                        world.spawnParticle(Particle.END_ROD, particleCenter.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0);
                        world.spawnParticle(Particle.DUST, particleCenter.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0,
                                new Particle.DustOptions(Color.AQUA, 1.2f));
                    }
                    world.spawnParticle(Particle.ELECTRIC_SPARK, particleCenter, 7, 0.22, 0.22, 0.22, 0.02);
                    world.spawnParticle(Particle.DUST, particleCenter, 5, 0.15, 0.15, 0.15, 0.0,
                            new Particle.DustOptions(Color.fromRGB(80, 160, 255), 1.4f));
                    if (phase == 1) {
                        world.playSound(visualCenter, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
                    }
                    if (phase >= 8) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, particleCenter, 12, 0.3, 0.3, 0.3, 0.05);
                        world.playSound(visualCenter, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.4f);
                        return true;
                    }
                    return false;
                }
                case "CREATIVE_MODE": {
                    if (creativeNpc != null) {
                        try {
                            moveCreativeNpc(creativeNpc, location.clone().add(0.5, 0.0, 0.5));
                            Object entity = creativeNpc.getClass().getMethod("getEntity").invoke(creativeNpc);
                            if (entity instanceof org.bukkit.entity.LivingEntity livingEntity) {
                                livingEntity.swingMainHand();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    // Real block-break particles (the same effect you see mining a block in
                    // survival) instead of FALLING_DUST/CLOUD, which look like falling sand dust
                    // rather than a block shattering. Particle.BLOCK requires the specific
                    // BlockData being broken to pick the right texture/color.
                    if (previousMaterial != null && previousMaterial != Material.AIR) {
                        world.spawnParticle(Particle.BLOCK, particleCenter, 18, 0.3, 0.3, 0.3, 0.0, previousMaterial.createBlockData());
                    } else {
                        world.spawnParticle(Particle.CLOUD, particleCenter, 5, 0.15, 0.15, 0.15);
                    }
                    if (phase == 1) {
                        world.playSound(visualCenter, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.75f, 1.2f);
                        if (previousMaterial != null && previousMaterial != Material.AIR) {
                            try {
                                world.playSound(visualCenter, previousMaterial.createBlockData().getSoundGroup().getBreakSound(), 0.8f, 1.0f);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (visualEntity != null && !visualEntity.isDead() && phase >= 3) {
                        visualEntity.remove();
                    }
                    return phase >= 3;
                }
                case "MAGIC_SWIRL": {
                    if (phase < 10) {
                        for (int i = 0; i < 7; i++) {
                            double angle = (i / 7.0) * Math.PI * 2.0 + (phase * 0.4);
                            Vector offset = new Vector(Math.cos(angle) * 0.4, 0.14 + (phase * 0.045), Math.sin(angle) * 0.4);
                            world.spawnParticle(Particle.END_ROD, particleCenter.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0);
                            world.spawnParticle(Particle.CLOUD, particleCenter.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0);
                            world.spawnParticle(Particle.DUST, particleCenter.clone().add(offset), 1, 0.0, 0.0, 0.0, 0.0,
                                    new Particle.DustOptions(Color.PURPLE, 1.1f));
                            world.spawnParticle(Particle.DUST, particleCenter.clone().add(offset).add(0.0, 0.12, 0.0), 1, 0.0, 0.0, 0.0, 0.0,
                                    new Particle.DustOptions(Color.AQUA, 1.1f));
                        }
                        world.spawnParticle(Particle.ENCHANT, particleCenter, 7, 0.22, 0.22, 0.22, 0.02);
                        world.spawnParticle(Particle.WITCH, particleCenter, 2, 0.15, 0.15, 0.15, 0.0);
                        world.playSound(visualCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.75f, 1.2f);
                        return false;
                    }
                    world.spawnParticle(Particle.CLOUD, particleCenter, 10, 0.3, 0.3, 0.3, 0.02);
                    world.spawnParticle(Particle.DUST, particleCenter.clone().add(0.0, 0.18, 0.0), 7, 0.14, 0.14, 0.14, 0.0,
                            new Particle.DustOptions(Color.PURPLE, 1.3f));
                    world.spawnParticle(Particle.DUST, particleCenter.clone().add(0.0, 0.18, 0.0), 7, 0.14, 0.14, 0.14, 0.0,
                            new Particle.DustOptions(Color.AQUA, 1.3f));
                    world.spawnParticle(Particle.ENCHANT, particleCenter, 10, 0.3, 0.3, 0.3, 0.03);
                    world.playSound(visualCenter, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.9f, 1.1f);
                    return true;
                }
                case "NONE":
                default:
                    return true;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AnimationsManager: failed to play animation step (" + anim + "): " + t);
            return true;
        }
    }

    public Object spawnCreativeNpc(Player player, Location location) {
        if (player == null || location == null) {
            return null;
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
                return null;
            }
            Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = apiClass.getMethod("getNPCRegistry").invoke(null);
            Object npc = registry.getClass().getMethod("createNPC", org.bukkit.entity.EntityType.class, String.class)
                    .invoke(registry, org.bukkit.entity.EntityType.PLAYER, player.getName());
            npc.getClass().getMethod("setName", String.class).invoke(npc, player.getName());
            try {
                npc.getClass().getMethod("setSkinName", String.class).invoke(npc, player.getName());
            } catch (Throwable ignored) {
            }
            Location centeredSpawn = location.clone().add(0.5, 0.0, 0.5);
            npc.getClass().getMethod("spawn", Location.class).invoke(npc, centeredSpawn);
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity instanceof Entity bukkitEntity) {
                bukkitEntity.setInvulnerable(true);
                bukkitEntity.setSilent(true);
                bukkitEntity.setGravity(false);
                if (bukkitEntity instanceof org.bukkit.entity.LivingEntity livingEntity) {
                    livingEntity.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE));
                }
            }
            return npc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void moveCreativeNpc(Object npc, Location location) {
        if (npc == null || location == null) {
            return;
        }
        try {
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity instanceof Entity bukkitEntity) {
                bukkitEntity.teleport(location);
            }
        } catch (Throwable ignored) {
        }
    }

    public void destroyCreativeNpc(Object npc) {
        if (npc == null) {
            return;
        }
        try {
            npc.getClass().getMethod("destroy").invoke(npc);
        } catch (Throwable ignored) {
        }
    }
}
