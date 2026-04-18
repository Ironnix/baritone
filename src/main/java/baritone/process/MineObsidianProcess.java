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
import baritone.api.process.IMineObsidianProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

public final class MineObsidianProcess extends BaritoneProcessHelper implements IMineObsidianProcess {

    private boolean active;
    private BlockPos corner1;
    private BlockPos corner2;
    private Phase phase;
    private Set<BlockPos> originalAirBlocks;
    private BlockPos currentlyMining;
    private int miningTicks;

    private enum Phase {
        MINING,
        CLEANUP,
        COLLECTING
    }

    public MineObsidianProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void mineObsidian(BlockPos corner1, BlockPos corner2) {
        this.corner1 = corner1;
        this.corner2 = corner2;
        this.active = true;
        this.phase = Phase.MINING;
        this.originalAirBlocks = snapshotAirBlocks();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (phase) {
            case MINING:
                return handleMining(calcFailed, isSafeToCancel);
            case CLEANUP:
                return handleCleanup(calcFailed, isSafeToCancel);
            case COLLECTING:
                return handleCollecting(calcFailed, isSafeToCancel);
            default:
                onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private PathingCommand handleMining(boolean calcFailed, boolean isSafeToCancel) {
        List<BlockPos> obsidianBlocks = scanForObsidian();

        if (obsidianBlocks.isEmpty()) {
            phase = Phase.CLEANUP;
            logDirect("All obsidian cleared, cleaning up placed blocks...");
            return handleCleanup(false, isSafeToCancel);
        }

        // Sort by Y descending — mine top first
        obsidianBlocks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        int topY = obsidianBlocks.get(0).getY();

        // Top layer for pathfinding goal (within 2 Y of highest)
        List<BlockPos> topLayer = obsidianBlocks.stream()
                .filter(pos -> pos.getY() >= topY - 2)
                .collect(Collectors.toList());

        BetterBlockPos playerPos = ctx.playerFeet();

        // If we're already mining a block, keep going until it breaks or we give up
        if (currentlyMining != null) {
            BlockState miningState = ctx.world().getBlockState(currentlyMining);
            if (!isObsidian(miningState)) {
                // Block was broken or is no longer obsidian
                currentlyMining = null;
                miningTicks = 0;
            } else if (miningTicks > 200) {
                // Obsidian takes a long time to mine; give up after ~10 seconds
                currentlyMining = null;
                miningTicks = 0;
            } else {
                miningTicks++;
                baritone.getInputOverrideHandler().clearAllKeys();
                Optional<Rotation> rot = RotationUtils.reachable(ctx, currentlyMining);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, miningState);
                    if (ctx.isLookingAt(currentlyMining)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                } else {
                    // Not reachable right now — only jump for elevated blocks that need extra reach
                    if (currentlyMining.getY() >= playerPos.getY() + 2) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                    Rotation lookAt = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            new net.minecraft.world.phys.Vec3(currentlyMining.getX() + 0.5, currentlyMining.getY() + 0.5, currentlyMining.getZ() + 0.5),
                            ctx.playerRotations()
                    );
                    baritone.getLookBehavior().updateTarget(lookAt, true);
                    MovementHelper.switchToBestToolFor(ctx, miningState);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Only start mining new blocks when on the ground
        if (!ctx.player().onGround()) {
            List<Goal> goals = createMiningGoals(topLayer, calcFailed);
            return new PathingCommand(
                    new GoalComposite(goals.toArray(new Goal[0])),
                    PathingCommandType.REVALIDATE_GOAL_AND_PATH
            );
        }

        // Try to break reachable obsidian from the top layer
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
        }

        if (calcFailed) {
            logDirect("MineObsidian: pathfinding failed, retrying...");
        }

        List<Goal> goals = createMiningGoals(topLayer, calcFailed);

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private PathingCommand handleCleanup(boolean calcFailed, boolean isSafeToCancel) {
        List<BlockPos> placedBlocks = new ArrayList<>();
        for (BlockPos pos : originalAirBlocks) {
            BlockState state = ctx.world().getBlockState(pos);
            if (!state.isAir() && !isObsidian(state)) {
                placedBlocks.add(pos);
            }
        }

        if (placedBlocks.isEmpty()) {
            phase = Phase.COLLECTING;
            logDirect("Cleanup done, collecting drops...");
            return handleCollecting(false, isSafeToCancel);
        }

        int topY = placedBlocks.stream().mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
        List<BlockPos> topLayer = placedBlocks.stream()
                .filter(pos -> pos.getY() == topY)
                .sorted(Comparator.comparingDouble(ctx.playerFeet()::distSqr))
                .collect(Collectors.toList());

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        int playerHeadY = playerPos.getY() + 1;
        double blockReachDistance = ctx.playerController().getBlockReachDistance();

        for (BlockPos pos : topLayer) {
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
            logDirect("MineObsidian: cleanup pathfinding failed, retrying...");
        }

        List<Goal> goals = topLayer.stream()
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
            if (!isObsidianDrop(itemEntity.getItem())) {
                continue;
            }
            if (!isInsideArea(itemEntity.blockPosition())) {
                continue;
            }
            goals.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
        }

        if (goals.isEmpty()) {
            logDirect("MineObsidian done.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (calcFailed) {
            logDirect("MineObsidian: collection pathfinding failed, retrying...");
        }

        return new PathingCommand(
                new GoalComposite(goals.toArray(new Goal[0])),
                PathingCommandType.REVALIDATE_GOAL_AND_PATH
        );
    }

    private List<Goal> createMiningGoals(List<BlockPos> topLayer, boolean calcFailed) {
        return topLayer.stream()
                .map(pos -> (Goal) new GoalNear(pos, calcFailed ? 5 : 1))
                .collect(Collectors.toList());
    }

    private boolean isObsidian(BlockState state) {
        return state.getBlock() == Blocks.OBSIDIAN;
    }

    private boolean isObsidianDrop(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem) {
            Block block = ((BlockItem) stack.getItem()).getBlock();
            return block == Blocks.OBSIDIAN;
        }
        return false;
    }

    private List<BlockPos> scanForObsidian() {
        List<BlockPos> blocks = new ArrayList<>();
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
                    if (isObsidian(state)) {
                        blocks.add(pos);
                    }
                }
            }
        }
        return blocks;
    }

    private boolean isInsideArea(BlockPos pos) {
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
        currentlyMining = null;
        miningTicks = 0;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public String displayName0() {
        return "MineObsidian";
    }
}
