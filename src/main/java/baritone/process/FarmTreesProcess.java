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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    private enum Phase {
        CHOPPING,
        CLEANUP,
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
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (phase) {
            case CHOPPING:
                return handleChopping(calcFailed, isSafeToCancel);
            case CLEANUP:
                return handleCleanup(calcFailed, isSafeToCancel);
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
            // No more logs — move to cleanup phase to collect placed blocks
            phase = Phase.CLEANUP;
            logDirect("All logs chopped, cleaning up placed blocks...");
            return handleCleanup(false, isSafeToCancel);
        }

        // Sort by Y descending (mine from top to bottom to avoid floating logs)
        logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());

        // Try to break a log within reach, but ONLY if the log is at or below
        // the player's head level. If a log is above us, let the pathfinder
        // handle getting there (pillaring/scaffolding) without interruption.
        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        int playerHeadY = playerPos.getY() + 1;
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : logs) {
            // Skip logs above head — don't interrupt the pathfinder's pillar movement
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
            logDirect("FarmTrees: pathfinding failed, retrying...");
        }

        // Target ALL log positions as goals. The pathfinder will find the
        // cheapest path to any of them. For 2x2 trees this means it will
        // enter the trunk through already-broken spaces and ascend by
        // breaking logs as stairs — much cheaper than pillaring from outside.
        // Use REVALIDATE_GOAL_AND_PATH so the path updates each tick as
        // logs are broken and new spaces open up.
        List<Goal> goals = logs.stream()
                .map(pos -> (Goal) new GoalTwoBlocks(pos.getX(), pos.getY(), pos.getZ()))
                .collect(Collectors.toList());

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleCleanup(boolean calcFailed, boolean isSafeToCancel) {
        // Scan for throwaway blocks inside the bounding box that the bot placed
        // while scaffolding up. Break them so they drop as items.
        List<BlockPos> throwawayBlocks = scanForThrowawayBlocks();

        if (throwawayBlocks.isEmpty()) {
            // Cleanup done — move to replanting or finish
            if (Baritone.settings().farmTreesReplantSaplings.value) {
                phase = Phase.REPLANTING;
                logDirect("Cleanup done, replanting saplings...");
                return handleReplanting(false, isSafeToCancel);
            } else {
                logDirect("FarmTrees done.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Break from top to bottom so we don't strand ourselves
        throwawayBlocks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : throwawayBlocks) {
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

        List<Goal> goals = throwawayBlocks.stream()
                .map(pos -> (Goal) new GoalTwoBlocks(pos.getX(), pos.getY(), pos.getZ()))
                .collect(Collectors.toList());

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleReplanting(boolean calcFailed, boolean isSafeToCancel) {
        // Find dirt/grass blocks within the bounding box that have air above them
        // and could have a sapling placed on them
        List<BlockPos> plantable = scanForPlantableSpots();

        if (plantable.isEmpty() || !hasSaplingInInventory()) {
            logDirect("FarmTrees done.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        // Try to place saplings within reach
        for (BlockPos pos : plantable) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            // We target the top face of the ground block (pos is the ground block)
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
            // Can't reach plantable spots, finish up
            logDirect("FarmTrees done (couldn't reach all planting spots).");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Path to plantable spots
        List<Goal> goals = plantable.stream()
                .map(pos -> new GoalBlock(pos.above()))
                .collect(Collectors.toList());

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.SET_GOAL_AND_PATH
        );
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
                    // Check if this is a dirt-like block that saplings can be planted on
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

    private boolean hasSaplingInInventory() {
        return ctx.player().getInventory().getNonEquipmentItems().stream().anyMatch(this::isSapling);
    }

    private List<BlockPos> scanForThrowawayBlocks() {
        // Find blocks inside the bounding box that match the acceptableThrowawayItems
        // setting — these are blocks the bot placed as scaffolding
        Set<Block> throwawayBlocks = new HashSet<>();
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (item instanceof BlockItem) {
                throwawayBlocks.add(((BlockItem) item).getBlock());
            }
        }
        if (throwawayBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockPos> found = new ArrayList<>();
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
                    Block block = ctx.world().getBlockState(pos).getBlock();
                    if (throwawayBlocks.contains(block)) {
                        found.add(pos);
                    }
                }
            }
        }
        return found;
    }

    @Override
    public void onLostControl() {
        active = false;
        corner1 = null;
        corner2 = null;
        phase = null;
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
