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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.selection.ISelection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MineObsidianCommand extends Command {

    public MineObsidianCommand(IBaritone baritone) {
        super(baritone, "mineobsidian");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        BlockPos corner1;
        BlockPos corner2;

        if (args.hasAny()) {
            args.requireExactly(6);
            int x1 = args.getAs(Integer.class);
            int y1 = args.getAs(Integer.class);
            int z1 = args.getAs(Integer.class);
            int x2 = args.getAs(Integer.class);
            int y2 = args.getAs(Integer.class);
            int z2 = args.getAs(Integer.class);
            corner1 = new BlockPos(x1, y1, z1);
            corner2 = new BlockPos(x2, y2, z2);
        } else {
            ISelection sel = baritone.getSelectionManager().getLastSelection();
            if (sel == null) {
                throw new CommandInvalidStateException("No selection set. Use #sel 1 and #sel 2 first, or provide coordinates.");
            }
            corner1 = sel.min();
            corner2 = sel.max();
        }

        int count = countObsidian(corner1, corner2);
        logDirect(String.format("Found %d obsidian blocks to mine in area (%d, %d, %d) to (%d, %d, %d)",
                count,
                corner1.getX(), corner1.getY(), corner1.getZ(),
                corner2.getX(), corner2.getY(), corner2.getZ()));
        baritone.getMineObsidianProcess().mineObsidian(corner1, corner2);
    }

    private int countObsidian(BlockPos corner1, BlockPos corner2) {
        int count = 0;
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = ctx.world().getBlockState(new BlockPos(x, y, z));
                    if (state.getBlock() == Blocks.OBSIDIAN) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mine obsidian in a bounding box";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mineobsidian command mines all obsidian blocks within a bounding box from top to bottom,",
                "cleans up any scaffold blocks placed during pathfinding, then collects drops.",
                "",
                "Usage:",
                "> mineobsidian - Uses the current selection (set with #sel 1 and #sel 2).",
                "> mineobsidian <x1> <y1> <z1> <x2> <y2> <z2> - Mine obsidian in the specified bounding box."
        );
    }
}
