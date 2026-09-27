package net.kroet.underwatertrees.listeners;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.Block;

/**
 * Tracked sapling positions as a round-robin queue, indexed by chunk. Not
 * thread-safe: every caller runs on the server main thread.
 */
final class TrackedSaplings {

    record Position(UUID world, int x, int y, int z) {
        static Position of(Block block) {
            return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        ChunkKey chunk() {
            return new ChunkKey(world, x >> 4, z >> 4);
        }
    }

    record ChunkKey(UUID world, int x, int z) {
    }

    private final LinkedHashSet<Position> queue = new LinkedHashSet<>();
    private final Map<ChunkKey, Set<Position>> byChunk = new HashMap<>();

    boolean add(Position position) {
        if (!queue.add(position)) {
            return false;
        }
        byChunk.computeIfAbsent(position.chunk(), key -> new LinkedHashSet<>()).add(position);
        return true;
    }

    boolean remove(Position position) {
        if (!queue.remove(position)) {
            return false;
        }
        ChunkKey key = position.chunk();
        Set<Position> inChunk = byChunk.get(key);
        if (inChunk != null && inChunk.remove(position) && inChunk.isEmpty()) {
            byChunk.remove(key);
        }
        return true;
    }

    void removeChunk(UUID world, int chunkX, int chunkZ) {
        Set<Position> inChunk = byChunk.remove(new ChunkKey(world, chunkX, chunkZ));
        if (inChunk != null) {
            queue.removeAll(inChunk);
        }
    }

    /**
     * Moves the oldest position to the back of the queue and returns it, so
     * repeated calls visit every position in turn. Null if empty.
     */
    Position next() {
        if (queue.isEmpty()) {
            return null;
        }
        Position head = queue.removeFirst();
        queue.addLast(head);
        return head;
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }

    void clear() {
        queue.clear();
        byChunk.clear();
    }
}
