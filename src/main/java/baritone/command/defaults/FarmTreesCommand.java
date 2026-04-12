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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FarmTreesCommand extends Command {

    public FarmTreesCommand(IBaritone baritone) {
        super(baritone, "farmtrees");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        BlockPos corner1;
        BlockPos corner2;

        if (args.hasAny()) {
            // Explicit coordinates: #farmtrees x1 y1 z1 x2 y2 z2
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
            // Use the current selection from #sel 1 / #sel 2
            ISelection sel = baritone.getSelectionManager().getLastSelection();
            if (sel == null) {
                throw new CommandInvalidStateException("No selection set. Use #sel 1 and #sel 2 first, or provide coordinates.");
            }
            corner1 = sel.min();
            corner2 = sel.max();
        }

        logDirect(String.format("Starting tree farm from (%d, %d, %d) to (%d, %d, %d)",
                corner1.getX(), corner1.getY(), corner1.getZ(),
                corner2.getX(), corner2.getY(), corner2.getZ()));
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
                "> farmtrees - Uses the current selection (set with #sel 1 and #sel 2).",
                "> farmtrees <x1> <y1> <z1> <x2> <y2> <z2> - Farm trees in the specified bounding box.",
                "",
                "Settings:",
                "> set farmTreesWaitTicks <ticks> - Ticks to wait between cycles (default: 600 = 30s).",
                "> set farmTreesReplantSaplings <true/false> - Whether to replant saplings (default: true)."
        );
    }
}
