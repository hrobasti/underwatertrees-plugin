package com.hro_basti.underwatertrees.listeners;

import com.hro_basti.underwatertrees.Plugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class UnderwaterSaplingsListener implements Listener {

    private final Plugin plugin;
    private final Set<Material> saplings = EnumSet.noneOf(Material.class);
    private final Set<Material> validSoils = new HashSet<>();
    private boolean requireWaterAbove = true;

    public UnderwaterSaplingsListener(Plugin plugin) {
        this.plugin = plugin;
        applyConfig(plugin.getConfig());
    }

    public void applyConfig(FileConfiguration cfg) {
        saplings.clear();
        validSoils.clear();

        requireWaterAbove = cfg.getBoolean("require-water-above", false);
        boolean logStats = cfg.getBoolean("log-stats", true);
        boolean logDetail = cfg.getBoolean("log-detail", false);

        // New format: section with key: boolean entries
        var saplingSection = cfg.getConfigurationSection("saplings");
        if (saplingSection != null) {
            for (String key : saplingSection.getKeys(false)) {
                boolean enabled = saplingSection.getBoolean(key, false);
                if (!enabled) continue;
                try {
                    Material m = Material.matchMaterial(key.toUpperCase());
                    if (m == null) {
                        plugin.getLogger().warning("Unknown sapling/material key in config: " + key);
                        continue;
                    }
                    String name = m.name();
                    // Allow any material ending with _SAPLING or the named propagule (no direct enum reference)
                    if (name.endsWith("_SAPLING") || name.equals("MANGROVE_PROPAGULE")) {
                        saplings.add(m);
                    } else {
                        // Accept other materials for future extensions; still add if enabled
                        saplings.add(m);
                    }
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Unknown sapling/material key in config: " + key);
                }
            }
        } else {
            plugin.getLogger().warning("Config section 'saplings' missing; no saplings will be allowed.");
        }

        var soilSection = cfg.getConfigurationSection("soils");
        if (soilSection != null) {
            for (String key : soilSection.getKeys(false)) {
                boolean enabled = soilSection.getBoolean(key, false);
                if (!enabled) continue;
                try {
                    Material m = Material.matchMaterial(key.toUpperCase());
                    if (m == null) {
                        plugin.getLogger().warning("Unknown soil material key in config: " + key);
                        continue;
                    }
                    validSoils.add(m);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Unknown soil material key in config: " + key);
                }
            }
        } else {
            plugin.getLogger().warning("Config section 'soils' missing; no soils will be valid.");
        }

        // Fallback: if both sets empty, populate with default vanilla lists
        if (saplings.isEmpty() && validSoils.isEmpty()) {
            plugin.getLogger().warning("No saplings and soils loaded; applying fallback defaults.");
            // Default soils (resolved by name to avoid hard references)
            String[] defaultSoils = new String[]{
                "DIRT",
                "GRASS_BLOCK",
                "PODZOL",
                "COARSE_DIRT",
                "ROOTED_DIRT",
                "MOSS_BLOCK",
                "MUD"
            };
            for (String n : defaultSoils) {
            Material m = Material.matchMaterial(n);
            if (m != null) validSoils.add(m);
            }
            // Default saplings (resolved by name to avoid hard references)
            String[] defaultSaplings = new String[]{
                "OAK_SAPLING",
                "SPRUCE_SAPLING",
                "BIRCH_SAPLING",
                "JUNGLE_SAPLING",
                "ACACIA_SAPLING",
                "DARK_OAK_SAPLING",
                "CHERRY_SAPLING",
                "MANGROVE_PROPAGULE"
            };
            for (String n : defaultSaplings) {
            Material m = Material.matchMaterial(n);
            if (m != null) saplings.add(m);
            }
        }

        if (logStats) {
            plugin.getLogger().info("Loaded soils: " + validSoils.size() + ", saplings: " + saplings.size() + ", require-water-above=" + requireWaterAbove + ", log-detail=" + logDetail);
        }
        if (logDetail) {
            if (!validSoils.isEmpty()) {
                plugin.getLogger().info("Soils:");
                for (Material m : validSoils) {
                    plugin.getLogger().info(" - " + m.name());
                }
            }
            if (!saplings.isEmpty()) {
                plugin.getLogger().info("Saplings:");
                for (Material m : saplings) {
                    plugin.getLogger().info(" - " + m.name());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceUnderwater(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        Material type = item.getType();
        if (!saplings.contains(type)) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        if (event.getBlockFace() != BlockFace.UP) return;

        Block placeBlock = clicked.getRelative(BlockFace.UP);
        if (requireWaterAbove && placeBlock.getType() != Material.WATER) return;

        if (!validSoils.contains(clicked.getType())) return;

        event.setCancelled(true);

        BlockData data = org.bukkit.Bukkit.createBlockData(type);
        if (data instanceof Sapling s) {
            s.setStage(0);
            placeBlock.setBlockData(s, false);
        } else {
            placeBlock.setType(type, false);
        }

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            int amount = item.getAmount();
            if (amount > 1) {
                item.setAmount(amount - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }

        placeBlock.getWorld().playSound(placeBlock.getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f);
    }

    // Expose counts for metrics charts
    public int getSaplingCount() {
        return saplings.size();
    }

    public int getSoilCount() {
        return validSoils.size();
    }

    public java.util.Set<Material> getSaplings() {
        return java.util.Collections.unmodifiableSet(saplings);
    }

    public java.util.Set<Material> getValidSoils() {
        return java.util.Collections.unmodifiableSet(validSoils);
    }
}
