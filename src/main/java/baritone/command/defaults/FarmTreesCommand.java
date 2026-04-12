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
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FarmTreesCommand extends Command {

    public FarmTreesCommand(IBaritone baritone) {
        super(baritone, "farmtrees");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireExactly(6);
        int x1 = args.getAs(Integer.class);
        int y1 = args.getAs(Integer.class);
        int z1 = args.getAs(Integer.class);
        int x2 = args.getAs(Integer.class);
        int y2 = args.getAs(Integer.class);
        int z2 = args.getAs(Integer.class);
        BlockPos corner1 = new BlockPos(x1, y1, z1);
        BlockPos corner2 = new BlockPos(x2, y2, z2);
        logDirect(String.format("Starting tree farm from (%d, %d, %d) to (%d, %d, %d)", x1, y1, z1, x2, y2, z2));
        baritone.getFarmTreesProcess().farmTrees(corner1, corner2);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Farm trees in a bounding box";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The farmtrees command mines all log blocks within a bounding box from top to bottom,",
                "optionally replants saplings, then waits for tree regrowth and repeats indefinitely.",
                "",
                "Usage:",
                "> farmtrees <x1> <y1> <z1> <x2> <y2> <z2> - Farm trees in the specified bounding box.",
                "",
                "Settings:",
                "> set farmTreesWaitTicks <ticks> - Ticks to wait between cycles (default: 600 = 30s).",
                "> set farmTreesReplantSaplings <true/false> - Whether to replant saplings (default: true)."
        );
    }
}
