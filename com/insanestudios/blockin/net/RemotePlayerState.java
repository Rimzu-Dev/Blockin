package com.insanestudios.blockin.net;

/**
 * Latest snapshot of another player, as broadcast by the server. Values use
 * feet position (x, y = bb.y0, z) plus view angles. No interpolation/smoothing
 * yet — this is the raw wire value until the client receives the next frame.
 */
public final class RemotePlayerState {

    public final int id;
    public final float x;
    public final float y;
    public final float z;
    public final float yRot;
    public final float xRot;

    public RemotePlayerState(int id, float x, float y, float z, float yRot, float xRot) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
    }
}