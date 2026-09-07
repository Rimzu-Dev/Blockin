package com.insanestudios.blockin.net;

import com.insanestudios.blockin.PlayerInput;
import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.World.WaterSystem;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LAN client. Runs the local player as usual (responsive, no waiting on the
 * network), streams each tick's input {@link GameServer#MSG_INPUT} to the
 * server, and additionally reports its own live position each tick via
 * {@link GameServer#MSG_SELF_STATE} so the server relays <b>the real
 * position</b> (from this client's 60 Hz local sim) to other clients instead of
 * a divergent 20 Hz server-side re-simulation.
 *
 * <p>A background thread receives: {@link GameServer#MSG_STATE} broadcasts from
 * other players (stored raw, latest-wins in {@link #remotePlayers()}) and
 * {@link GameServer#MSG_BLOCK} edits, which are queued and applied to the
 * local {@link Level} by the caller on the main thread via
 * {@link #drainPendingBlocks()} — the same path a local edit takes.
 */
public final class GameClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final long serverSeed;
    private final int serverDepth;
    private final int myId;

    private final Map<Integer, RemotePlayerState> remotes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> remoteFirstSeen = new ConcurrentHashMap<>();
    private final Map<Integer, Long> remoteLastSeen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BlockEdit> pendingBlocks = new ConcurrentLinkedQueue<>();

    private volatile boolean connected = true;
    private volatile Level level;
    private Thread receiver;

    private long clientTick = 0;

    private static final class BlockEdit {
        final int x;
        final int y;
        final int z;
        final int type;

        BlockEdit(int x, int y, int z, int type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
        }
    }

    private GameClient(String host, int port, Socket socket, DataInputStream in,
                       DataOutputStream out, long serverSeed, int serverDepth, int myId) {
        this.host = host;
        this.port = port;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.serverSeed = serverSeed;
        this.serverDepth = serverDepth;
        this.myId = myId;
    }

    /** Connects and reads the server's WELCOME (seed + depth + own id). */
    public static GameClient connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            byte msg = in.readByte();
            if (msg != GameServer.MSG_WELCOME) {
                throw new IOException("bad welcome message " + msg);
            }
            long seed = in.readLong();
            int depth = in.readInt();
            int myId = in.readInt();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            return new GameClient(host, port, s, in, out, seed, depth, myId);
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public long seed() {
        return serverSeed;
    }

    public int depth() {
        return serverDepth;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Attaches the local level and starts receiving broadcasts. */
    public void bind(Level level) {
        this.level = level;
        if (receiver != null) return;
        receiver = new Thread(this::receiveLoop, "blockin-net-recv");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receiveLoop() {
        try {
            while (connected) {
                byte msg = in.readByte();
                switch (msg) {
                    case GameServer.MSG_STATE -> {
                        int id = in.readInt();
                        float x = in.readFloat();
                        float y = in.readFloat();
                        float z = in.readFloat();
                        float yRot = in.readFloat();
                        float xRot = in.readFloat();
                        long now = System.currentTimeMillis();
                        remotes.put(id, new RemotePlayerState(id, x, y, z, yRot, xRot));
                        remoteFirstSeen.putIfAbsent(id, now);
                        remoteLastSeen.put(id, now);
                    }
                    case GameServer.MSG_BLOCK -> {
                        int x = in.readInt();
                        int y = in.readInt();
                        int z = in.readInt();
                        int type = in.readInt();
                        pendingBlocks.add(new BlockEdit(x, y, z, type));
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            if (connected) {
                System.err.println("[Net] disconnected: " + e.getMessage());
            }
        } finally {
            connected = false;
        }
    }

    /** Streams this tick's input to the server (called once per local tick). */
    public void sendInput(PlayerInput input) {
        if (!connected) return;
        try {
            synchronized (out) {
                out.writeByte(GameServer.MSG_INPUT);
                PlayerInputCodec.write(out, clientTick++, input);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("[Net] input send failed: " + e);
            connected = false;
        }
    }

    /** Reports this client's own live position (feet y) to the server so it can
     *  relay the REAL position to other clients instead of re-simulating one.
     *  Called every tick alongside {@link #sendInput}. */
    public void sendSelfState(float x, float y, float z, float yRot, float xRot) {
        if (!connected) return;
        try {
            synchronized (out) {
                out.writeByte(GameServer.MSG_SELF_STATE);
                out.writeFloat(x);
                out.writeFloat(y);
                out.writeFloat(z);
                out.writeFloat(yRot);
                out.writeFloat(xRot);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("[Net] self-state send failed: " + e);
            connected = false;
        }
    }

    /** Tells the server a block was placed/broken (it applies + rebroadcasts). */
    public void sendBlock(int x, int y, int z, int type) {
        if (!connected) return;
        try {
            synchronized (out) {
                out.writeByte(GameServer.MSG_BLOCK);
                out.writeInt(x);
                out.writeInt(y);
                out.writeInt(z);
                out.writeInt(type);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("[Net] block send failed: " + e);
            connected = false;
        }
    }

    /** Applies queued remote block edits on the caller's (main) thread. */
    public void drainPendingBlocks() {
        Level lv = level;
        if (lv == null) return;
        BlockEdit e;
        while ((e = pendingBlocks.poll()) != null) {
            lv.setTile(e.x, e.y, e.z, e.type);
            WaterSystem.onBlockChanged(lv, e.x, e.y, e.z);
        }
    }

    /** Latest broadcast positions for other players (id -> state). */
    public Collection<RemotePlayerState> remotePlayers() {
        return remotes.values();
    }

    /** This client's own server-assigned player id. */
    public int myId() {
        return myId;
    }

    /** Short stable identity hash for a remote player id (first-seen basis),
     *  so the same logical player can be cross-checked on every instance. */
    public String remoteHash(int id) {
        Long first = remoteFirstSeen.get(id);
        if (first == null) return "------";
        long h = first;
        h ^= h >>> 16;
        h *= 0x45d9f3bL;
        h ^= h >>> 16;
        return String.format("%06x", (int) (h & 0xFFFFFFL));
    }

    /** Milliseconds since this remote player's position last arrived. */
    public long remoteAgeMs(int id) {
        Long last = remoteLastSeen.get(id);
        if (last == null) return Long.MAX_VALUE;
        return System.currentTimeMillis() - last;
    }

    public void close() {
        connected = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        try {
            if (receiver != null) {
                receiver.join(1000);
            }
        } catch (InterruptedException ignored) {
        }
    }
}