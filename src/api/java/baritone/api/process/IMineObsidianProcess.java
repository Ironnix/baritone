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

public interface IMineObsidianProcess extends IBaritoneProcess {

    /**
     * Begin mining obsidian within the specified bounding box.
     * Mines all obsidian blocks, cleans up placed scaffold blocks,
     * then collects drops.
     *
     * @param corner1 One corner of the bounding box
     * @param corner2 The opposite corner of the bounding box
     */
    void mineObsidian(BlockPos corner1, BlockPos corner2);

    /**
     * Cancels the current obsidian mining task.
     */
    default void cancel() {
        onLostControl();
    }
}
