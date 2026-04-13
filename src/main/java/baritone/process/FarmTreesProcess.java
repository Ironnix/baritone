/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IFarmTreesProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

public final class FarmTreesProcess extends BaritoneProcessHelper implements IFarmTreesProcess {

    private boolean active;
    private BlockPos corner1;
    private BlockPos corner2;
    private Phase phase;
    private Set<BlockPos> originalAirBlocks; // snapshot of air at start, to detect placed blocks
    private Set<BlockPos> cleanupTargets; // snapshot of placed blocks when cleanup starts
    private BlockPos focusedLog; // anchor used to keep working on one tree at a time
    private BlockPos currentlyMining; // block we're actively mining (persist across ticks)
    private int miningTicks; // how many ticks we've been trying to mine currentlyMining

    private enum Phase {
        CHOPPING,
        CLEANUP,
        COLLECTING,
        REPLANTING
    }

    public FarmTreesProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farmTrees(BlockPos corner1, BlockPos corner2) {
        this.corner1 = corner1;
        this.corner2 = corner2;
        this.active = true;
        this.phase = Phase.CHOPPING;
        this.originalAirBlocks = snapshotAirBlocks();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (phase) {
            case CHOPPING:
                return handleChopping(calcFailed, isSafeToCancel);
            case CLEANUP:
                return handleCleanup(calcFailed, isSafeToCancel);
            case COLLECTING:
                return handleCollecting(calcFailed, isSafeToCancel);
            case REPLANTING:
                return handleReplanting(calcFailed, isSafeToCancel);
            default:
                onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private PathingCommand handleChopping(boolean calcFailed, boolean isSafeToCancel) {
        List<BlockPos> logs = scanForLogs();

        if (logs.isEmpty()) {
            phase = Phase.CLEANUP;
            cleanupTargets = snapshotCleanupTargets();
            logDirect("All logs cleared, cleaning up placed blocks...");
            return handleCleanup(false, isSafeToCancel);
        }

        List<BlockPos> focusedLogs = selectFocusedLogs(logs);

        // Sort logs by Y DESCENDING — mine top first, work downward within the current tree
        focusedLogs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        int topY = focusedLogs.get(0).getY();

        // Top layer for pathfinding goal (within 2 Y of highest)
        List<BlockPos> topLayer = focusedLogs.stream()
                .filter(pos -> pos.getY() >= topY - 2)
                .collect(Collectors.toList());

        BetterBlockPos playerPos = ctx.playerFeet();

        // If we're already mining a block, keep going until it breaks or we give up
        if (currentlyMining != null) {
            BlockState miningState = ctx.world().getBlockState(currentlyMining);
            boolean isLeaf = miningState.is(BlockTags.LEAVES);
            if (!miningState.is(BlockTags.LOGS) && !isLeaf) {
                // Block was broken or is no longer a log/leaf
                currentlyMining = null;
                miningTicks = 0;
            } else if (miningTicks > (isLeaf ? 15 : 60)) {
                // Stuck for too long, give up and let pathfinder try something else
                currentlyMining = null;
                miningTicks = 0;
            } else {
                miningTicks++;
                baritone.getInputOverrideHandler().clearAllKeys();
                Optional<Rotation> rot = RotationUtils.reachable(ctx, currentlyMining);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (isLeaf) {
                        selectEmptyHandForLeaves();
                    } else {
                        MovementHelper.switchToBestToolFor(ctx, miningState);
                    }
                    if (ctx.isLookingAt(currentlyMining)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                } else if (!isLeaf) {
                    BlockPos blockingLeaf = findBlockingLeaf(currentlyMining);
                    if (blockingLeaf != null) {
                        Optional<Rotation> leafRot = RotationUtils.reachable(ctx, blockingLeaf);
                        if (leafRot.isPresent()) {
                            currentlyMining = blockingLeaf;
                            miningTicks = 0;
                            baritone.getLookBehavior().updateTarget(leafRot.get(), true);
                            selectEmptyHandForLeaves();
                            if (ctx.isLookingAt(blockingLeaf)) {
                                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                            }
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }
                    // Not reachable right now — only jump for elevated logs that need extra reach
                    if (currentlyMining.getY() >= playerPos.getY() + 2) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                    Rotation lookAt = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            new Vec3(currentlyMining.getX() + 0.5, currentlyMining.getY() + 0.5, currentlyMining.getZ() + 0.5),
                            ctx.playerRotations()
                    );
                    baritone.getLookBehavior().updateTarget(lookAt, true);
                    MovementHelper.switchToBestToolFor(ctx, miningState);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                } else {
                    // Leaf not reachable, give up immediately
                    currentlyMining = null;
                    miningTicks = 0;
                    return new PathingCommand(null, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Only start mining new blocks when on the ground — don't interrupt scaffolding placement
        if (!ctx.player().onGround()) {
            List<Goal> goals = createChoppingGoals(topLayer, calcFailed);
            return new PathingCommand(
                    new GoalComposite(goals.toArray(new Goal[0])),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH
            );
        }

        // Try to break reachable logs from the top layer, or mine blocking leaves
        baritone.getInputOverrideHandler().clearAllKeys();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : topLayer) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                currentlyMining = pos;
                miningTicks = 0;
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // Log is in range but not directly reachable — check if a leaf is blocking
            if (isSafeToCancel) {
                BlockPos blockingLeaf = findBlockingLeaf(pos);
                if (blockingLeaf != null) {
                    Optional<Rotation> leafRot = RotationUtils.reachable(ctx, blockingLeaf);
                    if (leafRot.isPresent()) {
                        currentlyMining = blockingLeaf;
                        miningTicks = 0;
                        baritone.getLookBehavior().updateTarget(leafRot.get(), true);
                        selectEmptyHandForLeaves();
                        if (ctx.isLookingAt(blockingLeaf)) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }

        if (calcFailed) {
            logDirect("FarmTrees: pathfinding failed, retrying...");
        }

        // Path toward the top layer of logs. Only loosen the goal if strict routing failed.
        List<Goal> goals = createChoppingGoals(topLayer, calcFailed);

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleCleanup(boolean calcFailed, boolean isSafeToCancel) {
        if (cleanupTargets == null) {
            cleanupTargets = snapshotCleanupTargets();
        }

        // Only remove the scaffold snapshot captured when cleanup started.
        List<BlockPos> placedBlocks = new ArrayList<>();
        for (BlockPos pos : cleanupTargets) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.SAPLINGS)) {
                placedBlocks.add(pos);
            }
        }

        if (placedBlocks.isEmpty()) {
            phase = Phase.COLLECTING;
            logDirect("Cleanup done, collecting drops...");
            return handleCollecting(false, isSafeToCancel);
        }

        // Sort by Y descending — remove top blocks first to avoid stranding
        placedBlocks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());

        // Try to break a placed block within reach
        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        int playerHeadY = playerPos.getY() + 1;
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : placedBlocks) {
            if (pos.getY() > playerHeadY) {
                continue;
            }
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed) {
            logDirect("FarmTrees: cleanup pathfinding failed, retrying...");
        }

        List<Goal> goals = placedBlocks.stream()
                .map(pos -> (Goal) new GoalTwoBlocks(pos.getX(), pos.getY(), pos.getZ()))
                .collect(Collectors.toList());

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleCollecting(boolean calcFailed, boolean isSafeToCancel) {
        List<Goal> goals = new ArrayList<>();

        for (Entity entity : ctx.entities()) {
            if (!(entity instanceof ItemEntity)) {
                continue;
            }
            ItemEntity itemEntity = (ItemEntity) entity;
            if (!isCollectibleDrop(itemEntity.getItem())) {
                continue;
            }
            if (!isInsideFarm(itemEntity.blockPosition())) {
                continue;
            }
            goals.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
        }

        if (goals.isEmpty()) {
            if (Baritone.settings().farmTreesReplantSaplings.value) {
                phase = Phase.REPLANTING;
                logDirect("Collection done, replanting saplings...");
                return handleReplanting(false, isSafeToCancel);
            }
            logDirect("FarmTrees done.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (calcFailed) {
            logDirect("FarmTrees: collection pathfinding failed, retrying...");
        }

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleReplanting(boolean calcFailed, boolean isSafeToCancel) {
        List<BlockPos> plantable = scanForPlantableSpots();

        if (plantable.isEmpty() || !hasSaplingInInventory()) {
            logDirect("FarmTrees done.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : plantable) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), blockReachDistance, false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isSapling)) {
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                if (result instanceof BlockHitResult && ((BlockHitResult) result).getDirection() == Direction.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        if (calcFailed) {
            logDirect("FarmTrees done.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goals = plantable.stream()
                .map(pos -> (Goal) new GoalBlock(pos.above()))
                .collect(Collectors.toList());

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.SET_GOAL_AND_PATH
        );
    }

    private BlockPos findBlockingLeaf(BlockPos targetLog) {
        Vec3 start = ctx.playerHead();
        Vec3 target = new Vec3(targetLog.getX() + 0.5, targetLog.getY() + 0.5, targetLog.getZ() + 0.5);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(start, target, ctx.playerRotations());
        double blockReachDistance = ctx.playerController().getBlockReachDistance();
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rotation, blockReachDistance);
        if (result instanceof BlockHitResult) {
            BlockPos hitPos = ((BlockHitResult) result).getBlockPos();
            if (!hitPos.equals(targetLog) && ctx.world().getBlockState(hitPos).is(BlockTags.LEAVES)) {
                return hitPos;
            }
        }
        return null;
    }

    private List<BlockPos> selectFocusedLogs(List<BlockPos> logs) {
        if (logs.isEmpty()) {
            focusedLog = null;
            return Collections.emptyList();
        }

        Set<BlockPos> logSet = new HashSet<>(logs);
        BlockPos seed = null;
        if (focusedLog != null) {
            seed = logs.stream()
                    .min(Comparator.comparingDouble(pos -> pos.distSqr(focusedLog)))
                    .orElse(null);
        }
        if (seed == null) {
            BetterBlockPos playerPos = ctx.playerFeet();
            seed = logs.stream()
                    .min(Comparator.comparingDouble(playerPos::distSqr))
                    .orElseThrow(IllegalStateException::new);
        }

        List<BlockPos> focused = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (!logSet.remove(current)) {
                continue;
            }
            focused.add(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (logSet.contains(neighbor)) {
                            queue.addLast(neighbor);
                        }
                    }
                }
            }
        }

        focusedLog = seed;
        return focused;
    }

    private List<Goal> createChoppingGoals(List<BlockPos> topLayer, boolean calcFailed) {
        return topLayer.stream()
                .map(pos -> calcFailed ? (Goal) new GoalNear(pos, 5) : new GoalTwoBlocks(pos.getX(), pos.getY(), pos.getZ()))
                .collect(Collectors.toList());
    }

    private void selectEmptyHandForLeaves() {
        List<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        int selectedSlot = ctx.player().getInventory().getSelectedSlot();
        if (selectedSlot >= 0 && selectedSlot < 9 && inventory.get(selectedSlot).isEmpty()) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.get(slot).isEmpty()) {
                ctx.player().getInventory().setSelectedSlot(slot);
                return;
            }
        }
    }

    private List<BlockPos> scanForLogs() {
        List<BlockPos> logs = new ArrayList<>();
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    if (state.is(BlockTags.LOGS)) {
                        logs.add(pos);
                    }
                }
            }
        }
        return logs;
    }

    private Set<BlockPos> snapshotCleanupTargets() {
        Set<BlockPos> targets = new HashSet<>();
        if (originalAirBlocks == null) {
            return targets;
        }
        for (BlockPos pos : originalAirBlocks) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.SAPLINGS)) {
                targets.add(pos);
            }
        }
        return targets;
    }

    private List<BlockPos> scanForPlantableSpots() {
        List<BlockPos> spots = new ArrayList<>();
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = ctx.world().getBlockState(pos);
                    Block block = state.getBlock();
                    if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL
                            || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT || block == Blocks.MUD
                            || block == Blocks.MUDDY_MANGROVE_ROOTS) {
                        BlockPos above = pos.above();
                        if (above.getY() <= maxY) {
                            BlockState aboveState = ctx.world().getBlockState(above);
                            if (aboveState.getBlock() instanceof AirBlock) {
                                spots.add(pos);
                            }
                        }
                    }
                }
            }
        }
        return spots;
    }

    private boolean isSapling(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem) {
            Block block = ((BlockItem) stack.getItem()).getBlock();
            return block instanceof SaplingBlock;
        }
        return false;
    }

    private boolean isCollectibleDrop(ItemStack stack) {
        if (isSapling(stack)) {
            return true;
        }
        if (stack.getItem() instanceof BlockItem) {
            Block block = ((BlockItem) stack.getItem()).getBlock();
            return block.defaultBlockState().is(BlockTags.LOGS);
        }
        return false;
    }

    private boolean isInsideFarm(BlockPos pos) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private boolean hasSaplingInInventory() {
        return ctx.player().getInventory().getNonEquipmentItems().stream().anyMatch(this::isSapling);
    }

    private Set<BlockPos> snapshotAirBlocks() {
        Set<BlockPos> airBlocks = new HashSet<>();
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ctx.world().getBlockState(pos).isAir()) {
                        airBlocks.add(pos);
                    }
                }
            }
        }
        return airBlocks;
    }

    @Override
    public void onLostControl() {
        active = false;
        corner1 = null;
        corner2 = null;
        phase = null;
        originalAirBlocks = null;
        cleanupTargets = null;
        focusedLog = null;
        currentlyMining = null;
        miningTicks = 0;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public String displayName0() {
        return "FarmTrees";
    }
}
