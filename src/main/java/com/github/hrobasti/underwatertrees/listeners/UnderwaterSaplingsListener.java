package com.github.hrobasti.underwatertrees.listeners;

import com.github.hrobasti.underwatertrees.UnderwaterTreesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class UnderwaterSaplingsListener implements Listener {

    private final UnderwaterTreesPlugin plugin;
    private final Set<Material> saplings = EnumSet.noneOf(Material.class);
    private final Set<Material> validSoils = new HashSet<>();
    private final Set<BlockCoordinate> trackedSaplings = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int VANILLA_GROWTH_LIGHT_LEVEL = 10;
    private static final long DEFAULT_GROWTH_TASK_PERIOD_TICKS = 100L;
    private static final int MAX_GROWTH_ATTEMPTS_PER_CYCLE = 64;
    private static final double DEFAULT_GROWTH_ATTEMPT_CHANCE = 0.15D;

    private boolean requireWaterAbove = true;
    private boolean protectSaplings = true;
    private boolean growthLightOverrideEnabled = true;
    private int growthMinimumLightLevel = 1;
    private long growthTaskPeriodTicks = DEFAULT_GROWTH_TASK_PERIOD_TICKS;
    private double growthAttemptChance = DEFAULT_GROWTH_ATTEMPT_CHANCE;
    private BukkitTask growthTask;

    public UnderwaterSaplingsListener(UnderwaterTreesPlugin plugin) {
        this.plugin = plugin;
        applyConfig(plugin.getConfig());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(org.bukkit.event.block.BlockFromToEvent event) {
        if (!protectSaplings) return;
        Block to = event.getToBlock();
        if (saplings.contains(to.getType())) {
            // Prevent fluids from flowing into the sapling block to avoid breaking/updates
            event.setCancelled(true);
        }
    }

    public void applyConfig(FileConfiguration cfg) {
        saplings.clear();
        validSoils.clear();

        requireWaterAbove = cfg.getBoolean("require-water-above", false);
        protectSaplings = cfg.getBoolean("protect-underwater-saplings", true);
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

        var growthSection = cfg.getConfigurationSection("growth-light-override");
        if (growthSection == null) {
            growthLightOverrideEnabled = true;
            growthMinimumLightLevel = 1;
        } else {
            growthLightOverrideEnabled = growthSection.getBoolean("enabled", true);
            int configuredLight = growthSection.getInt("minimum-level", 1);
            if (configuredLight < 0 || configuredLight > 15) {
                plugin.getLogger().warning("growth-light-override.minimum-level outside 0-15; falling back to vanilla requirement (10).");
                growthMinimumLightLevel = VANILLA_GROWTH_LIGHT_LEVEL;
            } else {
                growthMinimumLightLevel = configuredLight;
            }

            long periodSeconds = Math.max(1L, growthSection.getLong("attempt-period-seconds", DEFAULT_GROWTH_TASK_PERIOD_TICKS / 20L));
            growthTaskPeriodTicks = periodSeconds * 20L;

            double configuredChance = growthSection.getDouble("attempt-chance", DEFAULT_GROWTH_ATTEMPT_CHANCE);
            if (configuredChance < 0.0D || configuredChance > 1.0D) {
                plugin.getLogger().warning("growth-light-override.attempt-chance outside 0-1; falling back to default (" + DEFAULT_GROWTH_ATTEMPT_CHANCE + ").");
                growthAttemptChance = DEFAULT_GROWTH_ATTEMPT_CHANCE;
            } else {
                growthAttemptChance = configuredChance;
            }
        }

        if (growthLightOverrideEnabled) {
            rebuildTrackedSaplings();
            restartGrowthTask();
        } else {
            trackedSaplings.clear();
            cancelGrowthTask();
        }

        if (logStats) {
            plugin.getLogger().info("Loaded soils: " + validSoils.size() + ", saplings: " + saplings.size() + ", require-water-above=" + requireWaterAbove + ", protect-underwater-saplings=" + protectSaplings + ", growth-override=" + growthLightOverrideEnabled + " (min=" + growthMinimumLightLevel + ", periodTicks=" + growthTaskPeriodTicks + ", chance=" + growthAttemptChance + "), log-detail=" + logDetail);
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
    public void onPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (!protectSaplings) return;
        Block b = event.getBlock();
        if (!saplings.contains(b.getType())) return;

        // Allow natural break if survival conditions are no longer met
        Block soil = b.getRelative(BlockFace.DOWN);
        if (!validSoils.contains(soil.getType())) return;
        Block above = b.getRelative(BlockFace.UP);
        if (requireWaterAbove && above.getType() != Material.WATER) return;

        // Survival conditions still valid -> cancel any physics that would otherwise break the sapling
        event.setCancelled(true);
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

        addTrackedIfCandidate(placeBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSaplingPlaced(BlockPlaceEvent event) {
        if (!saplings.contains(event.getBlockPlaced().getType())) return;
        addTrackedIfCandidate(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSaplingBreak(BlockBreakEvent event) {
        if (!saplings.contains(event.getBlock().getType())) return;
        removeTracked(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaplingGrow(BlockGrowEvent event) {
        if (!saplings.contains(event.getBlock().getType())) return;
        removeTracked(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSaplingFade(BlockFadeEvent event) {
        if (!growthLightOverrideEnabled) return;
        Block block = event.getBlock();
        if (!saplings.contains(block.getType())) return;
        if (!isUnderwaterCandidate(block)) {
            trackedSaplings.remove(BlockCoordinate.of(block));
            return;
        }
        int light = block.getLightLevel();
        if (light >= growthMinimumLightLevel) {
            event.setCancelled(true);
            addTrackedIfCandidate(block);
        } else {
            removeTracked(block);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!growthLightOverrideEnabled) return;
        scanChunkForSaplings(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (trackedSaplings.isEmpty()) return;
        final String worldName = event.getWorld().getName();
        final int chunkX = event.getChunk().getX();
        final int chunkZ = event.getChunk().getZ();
        trackedSaplings.removeIf(pos -> pos.world().equals(worldName) && (pos.x() >> 4) == chunkX && (pos.z() >> 4) == chunkZ);
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

    private void addTrackedIfCandidate(Block block) {
        if (!growthLightOverrideEnabled) return;
        if (!shouldTrack(block)) return;
        trackedSaplings.add(BlockCoordinate.of(block));
    }

    private void removeTracked(Block block) {
        trackedSaplings.remove(BlockCoordinate.of(block));
    }

    private boolean shouldTrack(Block block) {
        if (!saplings.contains(block.getType())) {
            return false;
        }
        if (!hasWaterAbove(block)) {
            return false;
        }
        Block soil = block.getRelative(BlockFace.DOWN);
        return validSoils.contains(soil.getType());
    }

    private boolean isUnderwaterCandidate(Block block) {
        return shouldTrack(block);
    }

    private boolean hasWaterAbove(Block block) {
        Material above = block.getRelative(BlockFace.UP).getType();
        return above == Material.WATER || above == Material.BUBBLE_COLUMN;
    }

    private void rebuildTrackedSaplings() {
        trackedSaplings.clear();
        if (!growthLightOverrideEnabled || saplings.isEmpty()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForSaplings(chunk);
            }
        }
    }

    private void scanChunkForSaplings(Chunk chunk) {
        if (saplings.isEmpty()) {
            return;
        }
        int minY = chunk.getWorld().getMinHeight();
        int maxY = chunk.getWorld().getMaxHeight();
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (shouldTrack(block)) {
                        trackedSaplings.add(BlockCoordinate.of(block));
                    }
                }
            }
        }
    }

    private void restartGrowthTask() {
        cancelGrowthTask();
        if (!growthLightOverrideEnabled) {
            return;
        }
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTrackedSaplings, growthTaskPeriodTicks, growthTaskPeriodTicks);
    }

    private void cancelGrowthTask() {
        if (growthTask != null) {
            growthTask.cancel();
            growthTask = null;
        }
    }

    private void tickTrackedSaplings() {
        if (!growthLightOverrideEnabled) {
            return;
        }
        if (trackedSaplings.isEmpty()) {
            return;
        }
        Iterator<BlockCoordinate> iterator = trackedSaplings.iterator();
        int processed = 0;
        while (iterator.hasNext() && processed < MAX_GROWTH_ATTEMPTS_PER_CYCLE) {
            BlockCoordinate coordinate = iterator.next();
            Block block = getLoadedBlock(coordinate);
            if (block == null) {
                processed++;
                continue;
            }
            if (!saplings.contains(block.getType())) {
                iterator.remove();
                processed++;
                continue;
            }
            if (!isUnderwaterCandidate(block)) {
                iterator.remove();
                processed++;
                continue;
            }
            if (block.getLightLevel() < growthMinimumLightLevel) {
                processed++;
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() > growthAttemptChance) {
                processed++;
                continue;
            }
            if (attemptSaplingGrowth(block)) {
                iterator.remove();
            }
            processed++;
        }
    }

    private Block getLoadedBlock(BlockCoordinate coordinate) {
        World world = Bukkit.getWorld(coordinate.world());
        if (world == null) {
            return null;
        }
        int chunkX = coordinate.x() >> 4;
        int chunkZ = coordinate.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        return world.getBlockAt(coordinate.x(), coordinate.y(), coordinate.z());
    }

    private boolean attemptSaplingGrowth(Block block) {
        Material before = block.getType();
        boolean applied = block.applyBoneMeal(BlockFace.UP);
        if (!applied) {
            return false;
        }
        return !saplings.contains(before) || !saplings.contains(block.getType());
    }

    private record BlockCoordinate(String world, int x, int y, int z) {
        static BlockCoordinate of(Block block) {
            return new BlockCoordinate(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }
}

