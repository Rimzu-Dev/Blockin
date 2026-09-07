package com.insanestudios.blockin.net;

import com.insanestudios.blockin.PlayerInput;
import com.insanestudios.blockin.PlayerSim;
import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.Blocks.BlockLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal LAN multiplayer server. UUID-less, trust-the-client, no validation.
 *
 * <p>Keeps one {@link PlayerSim} per connected client (the sims still run the
 * 20 Hz tick loop for idle/fallback physics), but <b>positions broadcast over
 * MSG_STATE are the client-reported values</b> ({@code MSG_SELF_STATE}, sent
 * each tick by every client from its own 60 Hz local sim) — the server relays
 * them to every <em>other</em> client, falling back to the per-client sim only
 * until a client's first report arrives. Broadcasting the re-simulated 20 Hz
 * copy diverged wildly from the owner's real position (different random spawn
 * + only every-3rd input consumed); relaying the client's own position made
 * REMOTE match the owner's LOCAL exactly (RemotePosProbe: deltas &lt; 0.06
 * block). Block place/break messages are applied to the shared {@link Level}
 * and rebroadcast.
 *
 * <p>One reader thread per client pulls input/position/block messages; a broken
 * socket only drops that player, never the server.
 */
public final class GameServer {

    /** Default seed used when the demo server is started without arguments. */
    public static final long SHARED_SEED = 90210L;

    /** Default listen port. */
    public static final int DEFAULT_PORT = 25566;

    /** Protocol message ids (client and server agree on these). */
    public static final byte MSG_WELCOME = 0;
    public static final byte MSG_STATE = 1;
    public static final byte MSG_BLOCK = 2;
    public static final byte MSG_INPUT = 3;
    public static final byte MSG_SELF_STATE = 4;

    private static final int TICKS_PER_SECOND = 20;

    private static final class Client {
        final int id;
        final Socket socket;
        final PlayerSim sim;
        final DataOutputStream out;

        /** Latest input received from this client (null = no input yet). */
        volatile PlayerInput latest = null;

        /** Latest position this client reported about itself (MSG_SELF_STATE),
         *  or null until the first report. Broadcast in preference to the server's
         *  own sim when present — the client's local 60 Hz sim is the position the
         *  owner actually sees, so relaying it is exact (trust-the-client LAN). */
        volatile RemotePlayerState reported = null;

        Client(int id, Socket socket, Level level) throws IOException {
            this.id = id;
            this.socket = socket;
            this.sim = new PlayerSim(level);
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
    }

    private final Level level;
    private final ServerSocket server;
    private final Map<Integer, Client> clients = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final PlayerInput idleInput = new PlayerInput();
    private volatile boolean running = true;
    private final AtomicLong stateFramesSent = new AtomicLong();

    public GameServer(int port, long seed, int w, int h, int d) throws IOException {
        this.level = new Level(w, h, d, seed);
        this.server = new ServerSocket(port);
    }

    /** The port actually bound (useful with port 0). */
    public int port() {
        return server.getLocalPort();
    }

    public long seed() {
        return level.seed;
    }

    public Level level() {
        return level;
    }

    /** Registers blocks (liquids must be known for swim physics parity). */
    public void loadBlocks() {
        BlockLoader.loadAll();
    }

    /** Accepts clients until {@link #stop()}; runs the tick loop on this thread. */
    public void start() {
        System.out.println("[Server] listening on port " + port()
                + " seed=" + level.seed + " depth=" + level.depth);
        Thread acceptor = new Thread(this::acceptLoop, "blockin-net-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        tickLoop();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients.values()) {
            try {
                c.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                register(s);
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Server] accept failed: " + e);
                }
            }
        }
    }

    private void register(Socket s) {
        try {
            Client c = new Client(nextId.getAndIncrement(), s, level);
            clients.put(c.id, c);

            synchronized (c.out) {
                c.out.writeByte(MSG_WELCOME);
                c.out.writeLong(level.seed);
                c.out.writeInt(level.depth);
                c.out.writeInt(c.id); // tell the client its own server-assigned id
                c.out.flush();
            }

            Thread reader = new Thread(() -> readLoop(c), "blockin-net-client-" + c.id);
            reader.setDaemon(true);
            reader.start();

            System.out.println("[Server] + player " + c.id
                    + " (" + s.getInetAddress().getHostAddress() + ") total=" + clients.size());
        } catch (IOException e) {
            System.err.println("[Server] register failed: " + e);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void readLoop(Client c) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(c.socket.getInputStream()))) {
            while (running) {
                byte msg = in.readByte();
                switch (msg) {
                    case MSG_INPUT -> {
                        PlayerInput input = new PlayerInput();
                        PlayerInputCodec.read(in, input);
                        c.latest = input;
                    }
                    case MSG_SELF_STATE -> {
                        float x = in.readFloat();
                        float y = in.readFloat();
                        float z = in.readFloat();
                        float yRot = in.readFloat();
                        float xRot = in.readFloat();
                        c.reported = new RemotePlayerState(c.id, x, y, z, yRot, xRot);
                    }
                    case MSG_BLOCK -> {
                        int x = in.readInt();
                        int y = in.readInt();
                        int z = in.readInt();
                        int type = in.readInt();
                        level.setTile(x, y, z, type);
                        broadcastBlock(x, y, z, type, c.id);
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            drop(c);
        }
    }

    /** The authoritative tick: simulate every player, then broadcast states. */
    private void tickLoop() {
        long periodNanos = 1_000_000_000L / TICKS_PER_SECOND;
        while (running) {
            long start = System.nanoTime();

            for (Client c : clients.values()) {
                try {
                    PlayerInput in = c.latest;
                    c.sim.simulate(in != null ? in : idleInput);
                } catch (Throwable t) {
                    System.err.println("[Server] sim failed for player " + c.id + ": " + t);
                    t.printStackTrace();
                }
            }

            for (Client r : clients.values()) {
                try {
                    sendStates(r);
                } catch (java.io.IOException e) {
                    drop(r);
                } catch (Throwable t) {
                    System.err.println("[Server] state send failed for player " + r.id + ": " + t);
                    t.printStackTrace();
                }
            }

            long elapsed = System.nanoTime() - start;
            long sleepMs = (periodNanos - elapsed) / 1_000_000L;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void sendStates(Client r) throws IOException {
        synchronized (r.out) {
            for (Client p : clients.values()) {
                if (p.id == r.id) continue; // you already know yourself
                RemotePlayerState s = p.reported;
                float x, y, z, yRot, xRot;
                if (s != null) {
                    x = s.x; y = s.y; z = s.z; yRot = s.yRot; xRot = s.xRot;
                } else {
                    x = p.sim.x; y = p.sim.bb.y0; z = p.sim.z; yRot = p.sim.yRot; xRot = p.sim.xRot;
                }
                r.out.writeByte(MSG_STATE);
                r.out.writeInt(p.id);
                r.out.writeFloat(x);
                r.out.writeFloat(y);
                r.out.writeFloat(z);
                r.out.writeFloat(yRot);
                r.out.writeFloat(xRot);
            }
            r.out.flush();
            stateFramesSent.incrementAndGet();
        }
    }

    public int clientCount() {
        return clients.size();
    }

    public long stateFramesSent() {
        return stateFramesSent.get();
    }

    private void broadcastBlock(int x, int y, int z, int type, int sourceId) {
        for (Client r : clients.values()) {
            if (r.id == sourceId) continue; // source already applied it locally
            try {
                synchronized (r.out) {
                    r.out.writeByte(MSG_BLOCK);
                    r.out.writeInt(x);
                    r.out.writeInt(y);
                    r.out.writeInt(z);
                    r.out.writeInt(type);
                    r.out.flush();
                }
            } catch (IOException e) {
                drop(r);
            }
        }
    }

    private void drop(Client c) {
        if (clients.remove(c.id) == null) return;
        try {
            c.socket.close();
        } catch (IOException ignored) {
        }
        System.out.println("[Server] - player " + c.id + " total=" + clients.size());
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("bad port, using " + DEFAULT_PORT);
            }
        }
        try {
            GameServer server = new GameServer(port, SHARED_SEED, 128, 64, 128);
            server.loadBlocks();
            server.start();
        } catch (IOException e) {
            System.err.println("[Server] fatal: " + e);
            System.exit(1);
        }
    }
}