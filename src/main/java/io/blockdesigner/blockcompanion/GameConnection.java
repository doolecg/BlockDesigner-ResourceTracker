package io.blockdesigner.blockcompanion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * One connection to a game's live link: TCP to 127.0.0.1, one JSON object per line, the hello with the instance's token
 * first. Messages from the game go to {@link Listener} on the thread {@code ui} runs things on.
 */
final class GameConnection implements AutoCloseable {
    static final String APP = "BlockCompanion Plugin";
    private static final ObjectMapper JSON = new ObjectMapper();

    enum State { CONNECTING, CONNECTED, CLOSED }

    interface Listener {
        void message(GameConnection c, JsonNode message);

        void stateChanged(GameConnection c);
    }

    final GameInstance instance;
    private final Listener listener;
    private final Consumer<Runnable> ui;
    private final String version;
    private volatile State state = State.CONNECTING;
    private volatile String error;
    private Socket socket;
    private OutputStream out;

    GameConnection(GameInstance instance, String version, Listener listener, Consumer<Runnable> ui) {
        this.instance = instance;
        this.version = version;
        this.listener = listener;
        this.ui = ui;
    }

    State state() {
        return state;
    }

    /** Why the last attempt failed, or null. */
    String error() {
        return error;
    }

    /** Connects on a background thread and keeps reading until the game goes away. */
    void start() {
        Thread t = new Thread(this::run, "BlockCompanion Plugin game link");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try (Socket s = open()) {
            synchronized (this) {
                socket = s;
                out = s.getOutputStream();
            }
            ObjectNode hello = JSON.createObjectNode().put("type", "hello").put("token", instance.token()).put("app", APP).put("version", version);
            send(hello);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode m = JSON.readTree(line);
                String type = m.path("type").asText("");
                if (type.equals("welcome")) setState(State.CONNECTED);
                else if (type.equals("error") && state == State.CONNECTING) error = m.path("message").asText("refused");
                ui.accept(() -> listener.message(this, m));
            }
        } catch (IOException e) {
            if (state != State.CLOSED) error = e.getMessage();
        } finally {
            setState(State.CLOSED);
        }
    }

    /**
     * Connects to 127.0.0.1, then ::1: BlockCompanion 0.2.0 and older listen on their JVM's loopback address, which is
     * ::1 when that game's JVM prefers IPv6.
     */
    private Socket open() throws IOException {
        IOException first = null;
        for (byte[] ip : new byte[][]{{127, 0, 0, 1}, InetAddress.getByName("::1").getAddress()}) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getByAddress(ip), instance.port()), 2000);
                return s;
            } catch (IOException e) {
                s.close();
                if (first == null) first = e;
            }
        }
        throw first;
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        ui.accept(() -> listener.stateChanged(this));
    }

    /** Sends a message; false when the connection is gone. */
    synchronized boolean send(JsonNode message) {
        if (out == null) return false;
        try {
            out.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    ObjectNode newMessage(String type) {
        return JSON.createObjectNode().put("type", type);
    }

    @Override
    public synchronized void close() {
        state = State.CLOSED;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // gone anyway
        }
    }
}
