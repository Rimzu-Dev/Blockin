package com.insanestudios.blockin.net;

import com.insanestudios.blockin.PlayerInput;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Wire codec for {@link PlayerInput}. Each input is tagged with the tick it
 * was generated on (a client-side sequence number) so a future
 * prediction/reconciliation pass can match server commands to client ticks —
 * the field just rides the wire now; nothing consumes it yet.
 */
public final class PlayerInputCodec {

    private PlayerInputCodec() {
    }

    /** Writes the tick tag and all input fields. */
    public static void write(DataOutputStream out, long tickCount, PlayerInput in) throws IOException {
        out.writeLong(tickCount);
        out.writeBoolean(in.forward);
        out.writeBoolean(in.back);
        out.writeBoolean(in.left);
        out.writeBoolean(in.right);
        out.writeBoolean(in.jump);
        out.writeFloat(in.yawDelta);
        out.writeFloat(in.pitchDelta);
    }

    /** Reads the tick tag into the returned holder and the input into {@code target}. */
    public static long read(DataInputStream in, PlayerInput target) throws IOException {
        long tick = in.readLong();
        target.forward = in.readBoolean();
        target.back = in.readBoolean();
        target.left = in.readBoolean();
        target.right = in.readBoolean();
        target.jump = in.readBoolean();
        target.yawDelta = in.readFloat();
        target.pitchDelta = in.readFloat();
        return tick;
    }
}