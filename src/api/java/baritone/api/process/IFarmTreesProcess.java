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

package baritone.api.process;

import net.minecraft.core.BlockPos;

public interface IFarmTreesProcess extends IBaritoneProcess {

    /**
     * Begin farming trees within the specified bounding box.
     * Mines all log blocks top-to-bottom, optionally replants saplings,
     * waits for regrowth, then repeats indefinitely.
     *
     * @param corner1 One corner of the bounding box
     * @param corner2 The opposite corner of the bounding box
     */
    void farmTrees(BlockPos corner1, BlockPos corner2);

    /**
     * Cancels the current tree farming task.
     */
    default void cancel() {
        onLostControl();
    }
}
