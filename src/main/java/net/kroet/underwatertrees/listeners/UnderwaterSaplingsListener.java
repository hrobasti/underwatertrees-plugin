package net.kroet.underwatertrees.listeners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.LongStream;
import net.kroet.turtlelib.helper.MessageService;
import net.kroet.underwatertrees.UnderwaterTreesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Sapling;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public final class UnderwaterSaplingsListener implements Listener {

    private final UnderwaterTreesPlugin plugin;
    private final Set<Material> saplings = EnumSet.noneOf(Material.class);
    private final Set<Material> validSoils = EnumSet.noneOf(Material.class);
    private final TrackedSaplings trackedSaplings = new TrackedSaplings();
    private final Set<Block> pendingFloodChecks = new LinkedHashSet<>();
    // Chunks waiting for their one-time scan, and chunks being scanned now.
    private final Set<TrackedSaplings.ChunkKey> pendingScans = new LinkedHashSet<>();
    private final Set<TrackedSaplings.ChunkKey> scansInFlight = new HashSet<>();
    private BukkitTask migrationTask;
    private ExecutorService scanPool;
    // Chunk PersistentDataContainer: trackedSaplingsKey holds every sapling on
    // valid soil (underwater or not) as packed chunk-local positions;
    // saplingsMigratedKey marks a chunk as scanned, so it is scanned only once.
    private final NamespacedKey trackedSaplingsKey;
    private final NamespacedKey saplingsMigratedKey;
    private static final int VANILLA_GROWTH_LIGHT_LEVEL = 9;
    private static final long DEFAULT_GROWTH_TASK_PERIOD_TICKS = 1200L;
    private static final int MAX_GROWTH_ATTEMPTS_PER_CYCLE = 64;
    private static final double DEFAULT_GROWTH_ATTEMPT_CHANCE = 0.25D;
    private static final int MIGRATION_SNAPSHOTS_PER_TICK = 4;
    private static final int MAX_SCANS_IN_FLIGHT = 16;
    private static final int SCAN_THREADS = 2;
    private static final NamespacedKey WATER = NamespacedKey.minecraft("water");
    private static final NamespacedKey FLOWING_WATER = NamespacedKey.minecraft("flowing_water");
    // The entries config.yml enables by default, resolved by name at runtime.
    // Package-private for DefaultMaterialsTest.
    static final List<String> DEFAULT_SOILS = List.of("DIRT", "GRASS_BLOCK", "PODZOL", "COARSE_DIRT",
            "ROOTED_DIRT", "MOSS_BLOCK", "MUD");
    static final List<String> DEFAULT_SAPLINGS = List.of("OAK_SAPLING", "SPRUCE_SAPLING", "BIRCH_SAPLING",
            "JUNGLE_SAPLING", "ACACIA_SAPLING", "DARK_OAK_SAPLING", "PALE_OAK_SAPLING", "CHERRY_SAPLING",
            "POPLAR_SAPLING");

    private boolean requireWaterAbove = false;
    private boolean protectSaplings = true;
    private boolean growthLightOverrideEnabled = true;
    private int growthMinimumLightLevel = 0;
    private long growthTaskPeriodTicks = DEFAULT_GROWTH_TASK_PERIOD_TICKS;
    private long scheduledGrowthPeriodTicks = -1L;
    private double growthAttemptChance = DEFAULT_GROWTH_ATTEMPT_CHANCE;
    private BukkitTask growthTask;
    private final InternalEventGuard placeEventGuard;
    private final UnderwaterTwoByTwoGrowth twoByTwoGrowth;

    public UnderwaterSaplingsListener(UnderwaterTreesPlugin plugin) {
        this.plugin = plugin;
        this.placeEventGuard = new InternalEventGuard(plugin.getLogger(), "BlockPlaceEvent", "Underwater planting");
        this.twoByTwoGrowth = new UnderwaterTwoByTwoGrowth(plugin, this::isKnownSapling, this::removeTracked);
        this.trackedSaplingsKey = new NamespacedKey(plugin, "tracked_saplings");
        this.saplingsMigratedKey = new NamespacedKey(plugin, "tracked_saplings_migrated");
        applyConfig(plugin.getConfig());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (!protectSaplings)
            return;
        // Only water is held back, and only at saplings that sit underwater on
        // an allowed soil; lava and saplings on land behave as in vanilla.
        Block to = event.getToBlock();
        if (!saplings.contains(to.getType()) || !validSoils.contains(to.getRelative(BlockFace.DOWN).getType()))
            return;
        Material above = to.getRelative(BlockFace.UP).getType();
        boolean underwater = above == Material.WATER || (!requireWaterAbove && above == Material.BUBBLE_COLUMN);
        if (underwater && holdsWater(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    public void applyConfig(FileConfiguration cfg) {
        saplings.clear();
        validSoils.clear();

        requireWaterAbove = cfg.getBoolean("require_water_above", false);
        protectSaplings = cfg.getBoolean("protect_underwater_saplings", true);
        boolean logStats = cfg.getBoolean("log_stats", true);
        boolean logDetail = cfg.getBoolean("log_detail", false);

        var saplingSection = cfg.getConfigurationSection("saplings");
        if (saplingSection != null) {
            for (String key : saplingSection.getKeys(false)) {
                boolean enabled = saplingSection.getBoolean(key, false);
                if (!enabled)
                    continue;
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    plugin.getLogger().warning("Unknown sapling/material key in config: " + key);
                    continue;
                }
                saplings.add(m);
            }
        }

        var soilSection = cfg.getConfigurationSection("soils");
        if (soilSection != null) {
            for (String key : soilSection.getKeys(false)) {
                boolean enabled = soilSection.getBoolean(key, false);
                if (!enabled)
                    continue;
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    plugin.getLogger().warning("Unknown soil material key in config: " + key);
                    continue;
                }
                validSoils.add(m);
            }
        }

        // Fallback: an empty section (missing or all-disabled) gets vanilla defaults on
        // its own, so a config that only omits one section doesn't disable the plugin
        if (validSoils.isEmpty()) {
            plugin.getLogger().warning("Config section 'soils' is missing or enables no soil; falling back to defaults.");
            for (String n : DEFAULT_SOILS) {
                Material m = Material.matchMaterial(n);
                if (m != null)
                    validSoils.add(m);
            }
        }
        if (saplings.isEmpty()) {
            plugin.getLogger().warning("Config section 'saplings' is missing or enables no sapling; falling back to defaults.");
            for (String n : DEFAULT_SAPLINGS) {
                Material m = Material.matchMaterial(n);
                if (m != null)
                    saplings.add(m);
            }
        }

        var growthSection = cfg.getConfigurationSection("growth_light_override");
        if (growthSection == null) {
            growthLightOverrideEnabled = true;
            growthMinimumLightLevel = 0;
        } else {
            growthLightOverrideEnabled = growthSection.getBoolean("enabled", true);
            int configuredLight = growthSection.getInt("minimum_level", 0);
            if (configuredLight < 0 || configuredLight > 15) {
                plugin.getLogger().warning("growth_light_override.minimum_level outside 0-15; falling back to vanilla requirement ("
                        + VANILLA_GROWTH_LIGHT_LEVEL + ").");
                growthMinimumLightLevel = VANILLA_GROWTH_LIGHT_LEVEL;
            } else {
                growthMinimumLightLevel = configuredLight;
            }

            long periodSeconds = Math.max(1L, growthSection.getLong("attempt_period_seconds", DEFAULT_GROWTH_TASK_PERIOD_TICKS / 20L));
            growthTaskPeriodTicks = periodSeconds * 20L;

            double configuredChance = growthSection.getDouble("attempt_chance", DEFAULT_GROWTH_ATTEMPT_CHANCE);
            if (configuredChance < 0.0D || configuredChance > 1.0D) {
                plugin.getLogger().warning("growth_light_override.attempt_chance outside 0-1; falling back to default (" + DEFAULT_GROWTH_ATTEMPT_CHANCE + ").");
                growthAttemptChance = DEFAULT_GROWTH_ATTEMPT_CHANCE;
            } else {
                growthAttemptChance = configuredChance;
            }
        }

        if (growthLightOverrideEnabled) {
            rebuildTrackedSaplings();
            ensureGrowthTask();
        } else {
            trackedSaplings.clear();
            pendingScans.clear();
            cancelMigrationTask();
            cancelGrowthTask();
        }

        if (logStats) {
            logStatsBlock(logDetail);
        }
        if (logDetail) {
            var messages = plugin.getMessages();
            if (!validSoils.isEmpty()) {
                messages.log(plugin.getLogger(), Level.INFO, "log.detail_soils_header");
                for (Material m : validSoils) {
                    plugin.getLogger().info(" - " + m.name());
                }
            }
            if (!saplings.isEmpty()) {
                messages.log(plugin.getLogger(), Level.INFO, "log.detail_saplings_header");
                for (Material m : saplings) {
                    plugin.getLogger().info(" - " + m.name());
                }
            }
        }
    }

    private void logStatsBlock(boolean logDetail) {
        var messages = plugin.getMessages();
        messages.log(plugin.getLogger(), Level.INFO, "log.stats_header");
        logStatLine(messages, "soils", Integer.toString(validSoils.size()));
        logStatLine(messages, "saplings", Integer.toString(saplings.size()));
        logStatLine(messages, "require_water_above", stateLabel(messages, requireWaterAbove));
        logStatLine(messages, "protect_underwater_saplings", stateLabel(messages, protectSaplings));
        logStatLine(messages, "growth_light_override", growthOverrideSummary(messages));
        logStatLine(messages, "log_detail", stateLabel(messages, logDetail));
    }

    private void logStatLine(MessageService messages, String key, String value) {
        messages.log(plugin.getLogger(), Level.INFO, "log.stats_line", Map.of("key", key, "value", value));
    }

    private String stateLabel(MessageService messages, boolean flag) {
        return flag ? messages.plain("log.modules_state_active") : messages.plain("log.modules_state_inactive");
    }

    private String growthOverrideSummary(MessageService messages) {
        return stateLabel(messages, growthLightOverrideEnabled)
                + " (min=" + growthMinimumLightLevel + ", periodTicks=" + growthTaskPeriodTicks + ", chance=" + growthAttemptChance + ")";
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!protectSaplings)
            return;
        Block b = event.getBlock();
        if (!saplings.contains(b.getType()))
            return;

        // Allow natural break if survival conditions are no longer met
        Block soil = b.getRelative(BlockFace.DOWN);
        if (!validSoils.contains(soil.getType()))
            return;
        Block above = b.getRelative(BlockFace.UP);
        if (requireWaterAbove && above.getType() != Material.WATER)
            return;

        // Survival conditions still valid -> cancel any physics that would otherwise
        // break the sapling
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceUnderwater(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND)
            return;
        // Like vanilla, the off hand only places when the main hand holds no
        // block; this also keeps one click from planting from both hands.
        if (hand == EquipmentSlot.OFF_HAND) {
            ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
            if (!mainHand.isEmpty() && mainHand.getType().isBlock())
                return;
        }

        ItemStack item = event.getItem();
        if (item == null)
            return;

        Material type = item.getType();
        if (!saplings.contains(type))
            return;

        Block clicked = event.getClickedBlock();
        if (clicked == null)
            return;

        if (event.getBlockFace() != BlockFace.UP)
            return;

        Block placeBlock = clicked.getRelative(BlockFace.UP);
        Material aboveType = placeBlock.getType();
        // This handler only covers the one case vanilla can't: placing a sapling
        // into water. If the target isn't water, leave the interaction alone so
        // vanilla's own placement and protection checks run undisturbed.
        boolean targetIsWater = aboveType == Material.WATER || aboveType == Material.BUBBLE_COLUMN;
        if (!targetIsWater)
            return;
        if (requireWaterAbove && aboveType != Material.WATER)
            return;

        if (!validSoils.contains(clicked.getType()))
            return;

        Player player = event.getPlayer();
        // Vanilla blocks adventure mode later, in the item's own use logic,
        // which this handler replaces; so adventure mode stays with vanilla.
        if (player.getGameMode() == GameMode.ADVENTURE)
            return;
        if (!player.hasPermission("underwatertrees.place"))
            return;

        event.setCancelled(true);

        BlockState previousState = placeBlock.getState();
        BlockData data = Bukkit.createBlockData(type);
        if (data instanceof Sapling s) {
            s.setStage(0);
            placeBlock.setBlockData(s, false);
        } else {
            placeBlock.setType(type, false);
        }

        // Let protection plugins (WorldGuard, GriefPrevention, ...) see this like
        // any other player-triggered block placement, so they can veto it. Any
        // failure, even an unexpected exception, removes the sapling again.
        boolean allowed = false;
        try {
            allowed = placeEventGuard.allows(() -> {
                BlockPlaceEvent placeEvent = new BlockPlaceEvent(placeBlock, previousState, clicked, item, player,
                        canBuildOutsideSpawnProtection(player, placeBlock), hand);
                plugin.getServer().getPluginManager().callEvent(placeEvent);
                return !placeEvent.isCancelled() && placeEvent.canBuild();
            });
        } finally {
            if (!allowed) {
                previousState.update(true, false);
            }
        }
        if (!allowed) {
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            int amount = item.getAmount();
            player.getInventory().setItem(hand, amount > 1 ? item.asQuantity(amount - 1) : null);
        }

        placeBlock.getWorld().playSound(placeBlock.getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f);

        rememberSapling(placeBlock);
    }

    // Mirrors the server's spawn protection: it only applies to non-ops while
    // ops exist, in the world that holds the server's respawn point.
    private static boolean canBuildOutsideSpawnProtection(Player player, Block block) {
        if (Bukkit.getOperators().isEmpty() || player.isOp()) {
            return true;
        }
        World spawnWorld = Bukkit.getServer().getRespawnWorld();
        var spawn = spawnWorld.getSpawnLocation();
        return !isSpawnProtected(block.getWorld().getKey(), block.getX(), block.getZ(), spawnWorld.getKey(),
                spawn.getBlockX(), spawn.getBlockZ(), Bukkit.getSpawnRadius());
    }

    // Radius > 0, same world as the spawn, and within the radius around it.
    static boolean isSpawnProtected(NamespacedKey world, int x, int z, NamespacedKey spawnWorld, int spawnX,
            int spawnZ, int radius) {
        return radius > 0 && world.equals(spawnWorld) && isWithinSpawnProtection(x, z, spawnX, spawnZ, radius);
    }

    static boolean isWithinSpawnProtection(int x, int z, int spawnX, int spawnZ, int radius) {
        return Math.max(Math.abs(x - spawnX), Math.abs(z - spawnZ)) <= radius;
    }

    // Tracking only follows placements and breaks that stand, so a sapling that
    // a protection plugin keeps or refuses is neither lost nor a phantom entry.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaplingPlaced(BlockPlaceEvent event) {
        if (!saplings.contains(event.getBlockPlaced().getType()))
            return;
        rememberSapling(event.getBlockPlaced());
    }

    // Runs after protection plugins have had their say on the growth.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTreeGrow(StructureGrowEvent event) {
        twoByTwoGrowth.onStructureGrow(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaplingBreak(BlockBreakEvent event) {
        if (!saplings.contains(event.getBlock().getType()))
            return;
        removeTracked(event.getBlock());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!growthLightOverrideEnabled)
            return;
        if (event.isNewChunk()) {
            // Freshly generated terrain holds no planted saplings yet.
            markMigrated(event.getChunk(), new long[0]);
            return;
        }
        loadOrMigrateChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        pendingScans.remove(new TrackedSaplings.ChunkKey(world, chunkX, chunkZ));
        if (!trackedSaplings.isEmpty()) {
            trackedSaplings.removeChunk(world, chunkX, chunkZ);
        }
    }

    // Expose counts for metrics charts
    public int getSaplingCount() {
        return saplings.size();
    }

    public int getSoilCount() {
        return validSoils.size();
    }

    // Every sapling on valid soil is remembered in its chunk, even on dry land
    // or with the override off; only underwater ones get growth attempts.
    private void rememberSapling(Block block) {
        if (!isKnownSapling(block))
            return;
        persistTrackedAdd(block);
        if (growthLightOverrideEnabled && hasWaterAbove(block)) {
            trackedSaplings.add(TrackedSaplings.Position.of(block));
        }
    }

    private void removeTracked(Block block) {
        trackedSaplings.remove(TrackedSaplings.Position.of(block));
        persistTrackedRemove(block);
    }

    private boolean isKnownSapling(Block block) {
        return saplings.contains(block.getType()) && validSoils.contains(block.getRelative(BlockFace.DOWN).getType());
    }

    private boolean isUnderwaterCandidate(Block block) {
        return isKnownSapling(block) && hasWaterAbove(block);
    }

    // Water can reach a sapling long after it was planted. Checks run one tick
    // later, once the water is actually there, and at most once per sapling.
    private void queueFloodCheck(Block sapling) {
        if (pendingFloodChecks.add(sapling) && pendingFloodChecks.size() == 1) {
            Bukkit.getScheduler().runTask(plugin, this::runFloodChecks);
        }
    }

    private void runFloodChecks() {
        List<Block> blocks = new ArrayList<>(pendingFloodChecks);
        pendingFloodChecks.clear();
        for (Block block : blocks) {
            if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                rememberSapling(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        Block below = event.getToBlock().getRelative(BlockFace.DOWN);
        if (saplings.contains(below.getType()) && holdsWater(event.getBlock())) {
            queueFloodCheck(below);
        }
    }

    // Water also flows out of blocks that only hold it (seagrass, kelp, coral, a
    // waterlogged slab, ...), so the fluid counts, not the block type.
    private static boolean holdsWater(Block source) {
        return isWater(source.getWorld().getFluidData(source.getLocation()).getFluidType().getKey());
    }

    // Still or flowing water; lava and empty never count.
    static boolean isWater(NamespacedKey fluid) {
        return WATER.equals(fluid) || FLOWING_WATER.equals(fluid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block below = event.getBlock().getRelative(BlockFace.DOWN);
        if (saplings.contains(below.getType())) {
            queueFloodCheck(below);
        }
    }

    private boolean hasWaterAbove(Block block) {
        Material above = block.getRelative(BlockFace.UP).getType();
        return above == Material.WATER || above == Material.BUBBLE_COLUMN;
    }

    private void rebuildTrackedSaplings() {
        trackedSaplings.clear();
        pendingScans.clear();
        if (!growthLightOverrideEnabled || saplings.isEmpty()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                loadOrMigrateChunk(chunk);
            }
        }
    }

    /**
     * Reads a chunk's cached sapling positions, or queues its one-time scan if the
     * chunk hasn't been scanned yet. Safe to call on every chunk load and every
     * reload, since scanning only ever happens once per chunk.
     */
    private void loadOrMigrateChunk(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (pdc.has(saplingsMigratedKey, PersistentDataType.BYTE)) {
            loadTrackedSaplingsFromPdc(chunk, pdc);
        } else {
            queueSaplingMigration(chunk);
        }
    }

    private void loadTrackedSaplingsFromPdc(Chunk chunk, PersistentDataContainer pdc) {
        long[] packedPositions = pdc.get(trackedSaplingsKey, PersistentDataType.LONG_ARRAY);
        if (packedPositions == null || packedPositions.length == 0)
            return;
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        List<Long> stillValid = new ArrayList<>(packedPositions.length);
        for (long packed : packedPositions) {
            Block block = world.getBlockAt(baseX + decodeLocalX(packed), decodeY(packed), baseZ + decodeLocalZ(packed));
            // Re-validated against the live block (only this chunk's entries), so
            // changes nobody saw an event for, like flooding by commands, count too.
            if (isKnownSapling(block)) {
                stillValid.add(packed);
                if (hasWaterAbove(block)) {
                    trackedSaplings.add(TrackedSaplings.Position.of(block));
                }
            }
        }
        if (stillValid.size() != packedPositions.length) {
            pdc.set(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, toLongArray(stillValid));
        }
    }

    /**
     * Queues a never-scanned chunk for its one-time discovery scan. The queue is
     * drained a few chunks per tick, so enabling the override or reloading with
     * many loaded chunks can't stall the server.
     */
    private void queueSaplingMigration(Chunk chunk) {
        if (saplings.isEmpty()) {
            // Nothing to ever find; still mark migrated so this chunk isn't rescanned
            // on every future load.
            markMigrated(chunk, new long[0]);
            return;
        }
        TrackedSaplings.ChunkKey key = new TrackedSaplings.ChunkKey(chunk.getWorld().getUID(), chunk.getX(),
                chunk.getZ());
        if (scansInFlight.contains(key) || !pendingScans.add(key)) {
            return;
        }
        if (migrationTask == null) {
            migrationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainMigrationQueue, 1L, 1L);
        }
    }

    // ChunkSnapshot must be taken on the main thread; copying one costs a
    // fraction of a millisecond, so only a few are taken per tick.
    private void drainMigrationQueue() {
        int started = 0;
        Iterator<TrackedSaplings.ChunkKey> queue = pendingScans.iterator();
        while (queue.hasNext() && started < MIGRATION_SNAPSHOTS_PER_TICK && scansInFlight.size() < MAX_SCANS_IN_FLIGHT) {
            TrackedSaplings.ChunkKey key = queue.next();
            queue.remove();
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                continue; // queued again on its next load
            }
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            if (chunk.getPersistentDataContainer().has(saplingsMigratedKey, PersistentDataType.BYTE)) {
                continue;
            }
            startScan(world, chunk, key);
            started++;
        }
        if (pendingScans.isEmpty()) {
            cancelMigrationTask();
        }
    }

    private void startScan(World world, Chunk chunk, TrackedSaplings.ChunkKey key) {
        ChunkSnapshot snapshot = chunk.getChunkSnapshot();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        Set<Material> saplingsSnapshot = EnumSet.copyOf(saplings);
        Set<Material> soilsSnapshot = EnumSet.copyOf(validSoils);
        scansInFlight.add(key);
        scanPool().execute(() -> {
            long[] found = scanSnapshotForSaplings(snapshot, minY, maxY, saplingsSnapshot, soilsSnapshot);
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                scansInFlight.remove(key);
                if (!world.isChunkLoaded(key.x(), key.z())) {
                    // Unloaded again before the scan finished; no marker was written, so
                    // it simply gets rescanned the next time it loads.
                    return;
                }
                markMigrated(world.getChunkAt(key.x(), key.z()), found);
            });
        });
    }

    private ExecutorService scanPool() {
        if (scanPool == null) {
            scanPool = Executors.newFixedThreadPool(SCAN_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "UnderwaterTrees-ChunkScan");
                thread.setDaemon(true);
                return thread;
            });
        }
        return scanPool;
    }

    private void cancelMigrationTask() {
        if (migrationTask != null) {
            migrationTask.cancel();
            migrationTask = null;
        }
    }

    /** Stops timers and scan threads; called when the plugin is disabled. */
    public void shutdown() {
        cancelGrowthTask();
        cancelMigrationTask();
        pendingScans.clear();
        if (scanPool != null) {
            scanPool.shutdownNow();
            scanPool = null;
        }
    }

    private static long[] scanSnapshotForSaplings(ChunkSnapshot snapshot, int minY, int maxY,
            Set<Material> saplingsSnapshot, Set<Material> soilsSnapshot) {
        List<Long> found = new ArrayList<>();
        // Starts one row up so the soil lookup at y - 1 stays inside ChunkSnapshot's
        // valid y range (unlike a live Block, it throws on an out-of-range y).
        for (int y = minY + 1; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (!saplingsSnapshot.contains(snapshot.getBlockType(x, y, z)))
                        continue;
                    if (!soilsSnapshot.contains(snapshot.getBlockType(x, y - 1, z)))
                        continue;
                    found.add(encodeLocalCoordinate(x, y, z));
                }
            }
        }
        return toLongArray(found);
    }

    private void markMigrated(Chunk chunk, long[] found) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        // Keeps saplings planted while the scan was running.
        long[] known = LongStream.concat(
                LongStream.of(pdc.getOrDefault(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, new long[0])),
                LongStream.of(found)).distinct().toArray();
        if (known.length > 0) {
            pdc.set(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, known);
        }
        pdc.set(saplingsMigratedKey, PersistentDataType.BYTE, (byte) 1);
        if (!growthLightOverrideEnabled) {
            return;
        }
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (long packed : known) {
            Block block = world.getBlockAt(baseX + decodeLocalX(packed), decodeY(packed), baseZ + decodeLocalZ(packed));
            if (isUnderwaterCandidate(block)) {
                trackedSaplings.add(TrackedSaplings.Position.of(block));
            }
        }
    }

    private void persistTrackedAdd(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        long[] existing = pdc.getOrDefault(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, new long[0]);
        long packed = encodeLocalCoordinate(block.getX() & 0xF, block.getY(), block.getZ() & 0xF);
        for (long value : existing) {
            if (value == packed)
                return;
        }
        long[] updated = Arrays.copyOf(existing, existing.length + 1);
        updated[existing.length] = packed;
        pdc.set(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, updated);
    }

    private void persistTrackedRemove(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        long[] existing = pdc.get(trackedSaplingsKey, PersistentDataType.LONG_ARRAY);
        if (existing == null || existing.length == 0)
            return;
        long packed = encodeLocalCoordinate(block.getX() & 0xF, block.getY(), block.getZ() & 0xF);
        long[] updated = new long[existing.length];
        int count = 0;
        boolean found = false;
        for (long value : existing) {
            if (value == packed) {
                found = true;
                continue;
            }
            updated[count++] = value;
        }
        if (!found)
            return;
        pdc.set(trackedSaplingsKey, PersistentDataType.LONG_ARRAY, Arrays.copyOf(updated, count));
    }

    private static long[] toLongArray(List<Long> values) {
        long[] array = new long[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    // Packs a chunk-local sapling position (x/z: 0-15, y: full signed world height)
    // into a single long for PersistentDataContainer storage. Package-private (not
    // private) so PackedSaplingCoordinateTest can round-trip values directly.
    static long encodeLocalCoordinate(int localX, int y, int localZ) {
        return ((long) (localX & 0xF) << 40) | ((long) (localZ & 0xF) << 36) | (y & 0xFFFFFFFFL);
    }

    static int decodeLocalX(long packed) {
        return (int) ((packed >>> 40) & 0xF);
    }

    static int decodeLocalZ(long packed) {
        return (int) ((packed >>> 36) & 0xF);
    }

    static int decodeY(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    // Keeps a running timer on reload unless its period changed; restarting
    // it every time resets the countdown, so frequent reloads would starve it.
    private void ensureGrowthTask() {
        if (growthTask != null && !growthTask.isCancelled() && scheduledGrowthPeriodTicks == growthTaskPeriodTicks) {
            return;
        }
        cancelGrowthTask();
        if (!growthLightOverrideEnabled) {
            return;
        }
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTrackedSaplings, growthTaskPeriodTicks, growthTaskPeriodTicks);
        scheduledGrowthPeriodTicks = growthTaskPeriodTicks;
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
        // Each cycle continues where the last one stopped, so saplings that can
        // never grow (too dark, lone dark oak) can't block all the others.
        int budget = Math.min(MAX_GROWTH_ATTEMPTS_PER_CYCLE, trackedSaplings.size());
        for (int i = 0; i < budget && !trackedSaplings.isEmpty(); i++) {
            TrackedSaplings.Position position = trackedSaplings.next();
            Block block = getLoadedBlock(position);
            if (block == null) {
                continue;
            }
            if (!isKnownSapling(block)) {
                removeTracked(block);
                continue;
            }
            if (!hasWaterAbove(block)) {
                // Stays remembered in the chunk; new water re-queues it.
                trackedSaplings.remove(position);
                continue;
            }
            if (block.getLightLevel() < growthMinimumLightLevel) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() > growthAttemptChance) {
                continue;
            }
            if (attemptSaplingGrowth(block)) {
                trackedSaplings.remove(position);
            }
        }
    }

    private Block getLoadedBlock(TrackedSaplings.Position position) {
        World world = Bukkit.getWorld(position.world());
        if (world == null) {
            return null;
        }
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return null;
        }
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    private boolean attemptSaplingGrowth(Block block) {
        Material before = block.getType();
        boolean applied = block.applyBoneMeal(BlockFace.UP);
        if (!applied) {
            return false;
        }
        return !saplings.contains(before) || !saplings.contains(block.getType());
    }
}
