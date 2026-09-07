package com.insanestudios.blockin.World;

/**
 * Receives notifications when the world changes so renderers can rebuild
 * stale geometry.
 */
public interface LevelListener {

    /** Called when every tile in the world may have changed. */
    void allChanged();

    /** Called when the light column at (x, z) changed between y0 and y1. */
    void lightColumnChanged(int x, int z, int y0, int y1);

    /** Called when one tile changed. */
    void tileChanged(int x, int y, int z);
}