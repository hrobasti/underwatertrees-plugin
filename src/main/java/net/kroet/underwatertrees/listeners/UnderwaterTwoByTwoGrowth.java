package net.kroet.underwatertrees.listeners;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * Dark and pale oak trunks only grow into air or leaves, so underwater these
 * 2x2 trees came out as a stump with a floating crown. Their growth is redone
 * with the trunk cells drained, and the new tree goes to protection plugins as
 * a StructureGrowEvent before any block of it is set.
 */
final class UnderwaterTwoByTwoGrowth {

    record Offset(int dx, int dy, int dz) {
    }

    enum Refill {
        WATER, WATERLOG, KEEP
    }

    // DarkOakTrunkPlacer(6, 2, 1): at most 9 layers. Each layer only checks its
    // north-west cell, which leans up to one block sideways from layer 3 and up
    // to two from layer 4.
    private static final int MAX_TRUNK_HEIGHT = 9;
    private static final List<Offset> TRUNK_GATE_OFFSETS = computeTrunkGateOffsets();

    private final Plugin plugin;
    private final Predicate<Block> isManagedSapling;
    private final Consumer<Block> onGrown;
    private final InternalEventGuard growEventGuard;
    private final Map<Block, Request> pending = new HashMap<>();
    private final Random random = new Random();
    private final Set<Material> paleMoss = EnumSet.of(Material.PALE_MOSS_BLOCK, Material.PALE_MOSS_CARPET,
            Material.PALE_HANGING_MOSS);
    // The replacement event while it is fired, so it isn't handled a second time.
    private StructureGrowEvent ownEvent;

    // The dropped growth, handed on to the replacement event; charged lists one
    // player per bone meal taken, to give back if protection refuses the tree.
    private static final class Request {
        private Location location;
        private final TreeType species;
        private boolean bonemeal;
        private Player player;
        private final List<Player> charged = new ArrayList<>();

        private Request(StructureGrowEvent event) {
            species = event.getSpecies();
            takeOver(event);
        }

        private void takeOver(StructureGrowEvent event) {
            location = event.getLocation();
            bonemeal = event.isFromBonemeal();
            player = event.getPlayer();
        }
    }

    UnderwaterTwoByTwoGrowth(Plugin plugin, Predicate<Block> isManagedSapling, Consumer<Block> onGrown) {
        this.plugin = plugin;
        this.isManagedSapling = isManagedSapling;
        this.onGrown = onGrown;
        this.growEventGuard = new InternalEventGuard(plugin.getLogger(), "StructureGrowEvent",
                "Underwater dark and pale oak growth");
    }

    // Every growth path (random tick, bonemeal, dispenser, the growth task) ends
    // here; the trunkless result is dropped and the tree grown again next tick.
    void onStructureGrow(StructureGrowEvent event) {
        if (event == ownEvent) {
            return;
        }
        Material sapling = saplingOf(event.getSpecies());
        if (sapling == null) {
            return;
        }
        Block grown = event.getLocation().getBlock();
        Offset corner = findOrigin((dx, dz) -> grown.getRelative(dx, 0, dz).getType() == sapling);
        if (corner == null) {
            return;
        }
        Block origin = grown.getRelative(corner.dx(), 0, corner.dz());
        if (!isManagedSapling.test(origin) || !isDrainable(origin.getRelative(BlockFace.UP))) {
            return;
        }
        event.setCancelled(true);
        Player charged = chargeBoneMeal(event);
        Request request = pending.get(origin);
        if (request == null) {
            Request scheduled = new Request(event);
            pending.put(origin, scheduled);
            Bukkit.getScheduler().runTask(plugin, () -> {
                pending.remove(origin);
                regrow(origin, sapling, scheduled);
            });
            request = scheduled;
        } else if (request.player == null && event.getPlayer() != null) {
            // Protection plugins check bone meal with the player's rights.
            request.takeOver(event);
        }
        if (charged != null) {
            request.charged.add(charged);
        }
    }

    private void regrow(Block origin, Material sapling, Request request) {
        if (!origin.getWorld().isChunkLoaded(origin.getX() >> 4, origin.getZ() >> 4)) {
            return;
        }
        List<Block> base = List.of(origin, origin.getRelative(1, 0, 0), origin.getRelative(0, 0, 1),
                origin.getRelative(1, 0, 1));
        if (base.stream().anyMatch(block -> block.getType() != sapling)
                || !isDrainable(origin.getRelative(BlockFace.UP))) {
            return;
        }
        Map<Block, BlockData> before = new LinkedHashMap<>();
        base.forEach(block -> before.put(block, block.getBlockData()));
        List<Block> drained = new ArrayList<>();
        for (Offset offset : TRUNK_GATE_OFFSETS) {
            Block block = origin.getRelative(offset.dx(), offset.dy(), offset.dz());
            if (isDrainable(block)) {
                before.put(block, block.getBlockData());
                drained.add(block);
            }
        }
        before.keySet().forEach(block -> block.setType(Material.AIR, false));
        boolean placed = false;
        try {
            // Only collected, not set; saplings grow pale oaks without the pale moss
            // of the world-gen variant.
            List<BlockState> tree = new ArrayList<>();
            boolean grew = origin.getWorld().generateTree(origin.getLocation(), random, request.species, state -> {
                if (!paleMoss.contains(state.getType())) {
                    tree.add(state);
                }
                return false;
            });
            if (!grew) {
                return;
            }
            List<BlockState> allowed = askProtectionPlugins(request, tree);
            if (allowed == null) {
                giveBackBoneMeal(request.charged);
                return;
            }
            allowed.forEach(state -> state.update(true, false));
            placed = true;
        } finally {
            if (!placed) {
                before.forEach((block, data) -> block.setBlockData(data, false));
            }
        }
        for (Block block : drained) {
            refill(block, before.get(block));
        }
        // A sapling cell a protection plugin kept free of the trunk gets water.
        BlockData water = Material.WATER.createBlockData();
        base.forEach(block -> refill(block, water));
        base.forEach(onGrown);
    }

    // Fires the replacement growth for protection plugins and block loggers; null
    // if it is refused or no block is left to set.
    private List<BlockState> askProtectionPlugins(Request request, List<BlockState> tree) {
        List<BlockState> allowed = new ArrayList<>();
        boolean grows = growEventGuard.allows(() -> {
            StructureGrowEvent grow = new StructureGrowEvent(request.location, request.species, request.bonemeal,
                    request.player, tree);
            ownEvent = grow;
            try {
                Bukkit.getPluginManager().callEvent(grow);
            } finally {
                ownEvent = null;
            }
            allowed.addAll(grow.getBlocks());
            return growsAfter(grow.isCancelled(), allowed.size());
        });
        return grows ? allowed : null;
    }

    private static void refill(Block block, BlockData water) {
        BlockData now = block.getBlockData();
        switch (refillFor(now.getMaterial().isAir(), now instanceof Waterlogged)) {
            case WATER -> block.setBlockData(water, false);
            case WATERLOG -> {
                ((Waterlogged) now).setWaterlogged(true);
                block.setBlockData(now, false);
            }
            case KEEP -> {
            }
        }
    }

    // A cancelled bonemeal use gives the item back, but vanilla uses it up
    // whether the tree grows or not. Returns the player charged, if any.
    private static Player chargeBoneMeal(StructureGrowEvent event) {
        Player player = event.getPlayer();
        if (!event.isFromBonemeal() || player == null || player.getGameMode() == GameMode.CREATIVE) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand.getType() != Material.BONE_MEAL) {
            hand = inventory.getItemInOffHand();
        }
        if (hand.getType() != Material.BONE_MEAL) {
            return null;
        }
        hand.setAmount(hand.getAmount() - 1);
        return player;
    }

    // As with vanilla growth a protection plugin cancels, the bone meal stays with
    // the player.
    private static void giveBackBoneMeal(List<Player> players) {
        for (Player player : players) {
            if (!player.isOnline()) {
                continue;
            }
            player.getInventory().addItem(new ItemStack(Material.BONE_MEAL)).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    private static boolean isDrainable(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    private static Material saplingOf(TreeType species) {
        return switch (species) {
            case DARK_OAK -> Material.DARK_OAK_SAPLING;
            case PALE_OAK -> Material.PALE_OAK_SAPLING;
            default -> null;
        };
    }

    static List<Offset> trunkGateOffsets() {
        return TRUNK_GATE_OFFSETS;
    }

    // Layer 0 holds the saplings themselves; the lean starts at layer
    // height - 3 at the earliest, and height is at least 6.
    private static List<Offset> computeTrunkGateOffsets() {
        List<Offset> offsets = new ArrayList<>();
        for (int dy = 1; dy < MAX_TRUNK_HEIGHT; dy++) {
            offsets.add(new Offset(0, dy, 0));
            for (int step = 1; step <= 2; step++) {
                if (dy < 2 + step) {
                    continue;
                }
                offsets.add(new Offset(step, dy, 0));
                offsets.add(new Offset(-step, dy, 0));
                offsets.add(new Offset(0, dy, step));
                offsets.add(new Offset(0, dy, -step));
            }
        }
        return List.copyOf(offsets);
    }

    // Same order as vanilla's TreeGrower: the first 2x2 of equal saplings that
    // contains the grown one, as the offset of its north-west corner.
    static Offset findOrigin(BiPredicate<Integer, Integer> isSapling) {
        for (int x = 0; x >= -1; x--) {
            for (int z = 0; z >= -1; z--) {
                if (isSapling.test(x, z) && isSapling.test(x + 1, z) && isSapling.test(x, z + 1)
                        && isSapling.test(x + 1, z + 1)) {
                    return new Offset(x, 0, z);
                }
            }
        }
        return null;
    }

    // The tree is set only if protection plugins let it grow with at least one
    // block; an emptied list would take the saplings and leave nothing.
    static boolean growsAfter(boolean cancelled, int blocksLeft) {
        return !cancelled && blocksLeft > 0;
    }

    // A drained cell the tree left empty gets its water back; one it filled
    // with leaves or another waterloggable block is waterlogged.
    static Refill refillFor(boolean nowAir, boolean nowWaterloggable) {
        if (nowAir) {
            return Refill.WATER;
        }
        return nowWaterloggable ? Refill.WATERLOG : Refill.KEEP;
    }
}
