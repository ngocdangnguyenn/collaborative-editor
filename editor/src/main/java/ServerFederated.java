import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerFederated {

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Set<PeerHandler>   peers   = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws IOException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        new ServerFederated().start(port);
    }

    public void start(int port) throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                ClientHandler h = new ClientHandler(s);
                clients.add(h);
                new Thread(h, "Client-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void broadcastToClients(String msg, ClientHandler except) {
        for (ClientHandler c : clients) {
            if (c == except) continue;
            if (c.isPeer) c.send("SYNC " + msg);
            else          c.send(msg);
        }
    }

    private void syncToPeers(String syncMsg, PeerHandler except) {
        for (PeerHandler p : peers) { if (p != except) p.send(syncMsg); }
    }

    private void connectToPeer(String host, int port, ClientHandler requester) {
        new Thread(() -> {
            try {
                Socket s = new Socket(host, port);
                PeerHandler ph = new PeerHandler(s);
                peers.add(ph);

                ph.send("PEERSYNC");
                ph.send("GETD");

                new Thread(ph, "Peer-" + host + ":" + port).start();

                if (requester != null) requester.send("DONE");
            } catch (IOException e) {
                System.err.println("[Federation] Cannot connect to peer: " + e.getMessage());
                if (requester != null) requester.send("ERRL Cannot connect to peer: " + e.getMessage());
            }
        }, "PeerConnect-" + host + ":" + port).start();
    }

    class ClientHandler implements Runnable {

        private final Socket      socket;
        private final PrintWriter out;
        private final BufferedReader in;
        boolean isPeer = false;

        ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    handleCommand(line.trim());
                }
            } catch (IOException e) {
                System.err.println("Client disconnected: " + e.getMessage());
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        synchronized void send(String msg) {
            out.println(msg);
            out.flush();
        }

        private void handleCommand(String cmd) {
            String[] parts = cmd.split(" ", 4);
            if (parts.length == 0) return;

            synchronized (ServerFederated.this) {
                switch (parts[0]) {
                    case "PEERSYNC" -> { isPeer = true; send("PEERSYNC_OK"); }
                    case "GETD"    -> handleGetD();
                    case "GETL"    -> handleGetL(parts);
                    case "ADDL"    -> handleAddL(parts, false);
                    case "RMVL"    -> handleRmvL(parts, false);
                    case "MDFL"    -> handleMdfL(parts, false);
                    case "CONNECT" -> handleConnect(parts);
                    case "SYNC"    -> {

                        int sp = cmd.indexOf(' ');
                        if (sp >= 0) handleIncomingSync(cmd.substring(sp + 1).split(" ", 4));
                    }
                    default        -> send("ERRL Unknown command: " + parts[0]);
                }
            }
        }

        private void handleGetD() {
            for (int i = 0; i < document.size(); i++) {
                send(String.format("LINE %d %d %s", i + 1, versions.get(i), document.get(i)));
            }
            send("DONE");
        }

        private void handleGetL(String[] parts) {
            if (parts.length < 2) { send("ERRL GETL missing index"); return; }
            int pos = Integer.parseInt(parts[1]);
            int idx = pos - 1;
            if (idx < 0 || idx >= document.size()) { send("ERRL " + pos + " out of range"); return; }
            send(String.format("LINE %d %d %s", pos, versions.get(idx), document.get(idx)));
        }

        private void handleAddL(String[] parts, boolean fromSync) {
            if (parts.length < 4) { if (!fromSync) send("ERRL ADDL malformed"); return; }
            int    pos  = Integer.parseInt(parts[1]);
            int    ver  = Integer.parseInt(parts[2]);
            String text = parts[3];
            int    idx  = Math.max(0, Math.min(pos - 1, document.size()));
            document.add(idx, text);
            versions.add(idx, ver);

            String pushMsg = String.format("ADDL %d %d %s", idx + 1, ver, text);
            broadcastToClients(pushMsg, fromSync ? null : this);
            if (!fromSync) {
                syncToPeers("SYNC " + pushMsg, null);
                send("DONE");
            }
        }

        private void handleRmvL(String[] parts, boolean fromSync) {
            if (parts.length < 2) { if (!fromSync) send("ERRL RMVL missing index"); return; }
            int pos = Integer.parseInt(parts[1]);
            int idx = pos - 1;
            if (idx < 0 || idx >= document.size()) {
                if (!fromSync) send("ERRL " + pos + " out of range");
                return;
            }
            document.remove(idx);
            versions.remove(idx);

            String pushMsg = "RMVL " + pos;
            broadcastToClients(pushMsg, fromSync ? null : this);
            if (!fromSync) {
                syncToPeers("SYNC " + pushMsg, null);
                send("DONE");
            }
        }

        private void handleMdfL(String[] parts, boolean fromSync) {
            if (parts.length < 4) { if (!fromSync) send("ERRL MDFL malformed"); return; }
            int    pos    = Integer.parseInt(parts[1]);
            int    ver    = Integer.parseInt(parts[2]);
            String text   = parts[3];
            int    idx    = pos - 1;
            if (idx < 0 || idx >= document.size()) {
                if (!fromSync) send("ERRL " + pos + " out of range");
                return;
            }
            document.set(idx, text);
            int newVer = ver + 1;
            versions.set(idx, newVer);

            String pushMsg = String.format("LINE %d %d %s", pos, newVer, text);
            broadcastToClients(pushMsg, fromSync ? null : this);
            if (!fromSync) {
                syncToPeers("SYNC " + pushMsg, null);
                send("DONE");
            }
        }

        private void handleConnect(String[] parts) {
            if (parts.length < 3) { send("ERRL CONNECT host port"); return; }
            String host = parts[1];
            int    port;
            try { port = Integer.parseInt(parts[2]); }
            catch (NumberFormatException e) { send("ERRL CONNECT invalid port"); return; }
            connectToPeer(host, port, this);
        }

        private void handleIncomingSync(String[] p) {

            if (p.length < 1) return;
            switch (p[0]) {
                case "ADDL" -> {
                    if (p.length < 4) return;
                    int pos = Integer.parseInt(p[1]), ver = Integer.parseInt(p[2]);
                    String text = p[3];
                    int idx = Math.max(0, Math.min(pos - 1, document.size()));
                    document.add(idx, text); versions.add(idx, ver);

                    broadcastToClients(String.format("ADDL %d %d %s", idx + 1, ver, text), this);
                }
                case "RMVL" -> {
                    if (p.length < 2) return;
                    int pos = Integer.parseInt(p[1]), idx = pos - 1;
                    if (idx >= 0 && idx < document.size()) {
                        document.remove(idx); versions.remove(idx);
                        broadcastToClients("RMVL " + pos, this);
                    }
                }
                case "LINE" -> {

                    if (p.length < 4) return;
                    int pos = Integer.parseInt(p[1]), ver = Integer.parseInt(p[2]);
                    String text = p[3];
                    int idx = pos - 1;
                    if (idx >= 0 && idx < document.size()) {
                        document.set(idx, text); versions.set(idx, ver);
                        broadcastToClients(String.format("LINE %d %d %s", pos, ver, text), this);
                    }
                }
                default -> System.err.println("[SYNC] commande inconnue : " + p[0]);
            }
        }

        void applySyncAddL(String[] parts) { handleAddL(parts, true); }
        void applySyncRmvL(String[] parts) { handleRmvL(parts, true); }
        void applySyncMdfL(String[] parts) { handleMdfL(parts, true); }
    }

    class PeerHandler implements Runnable {

        private final Socket       socket;
        private final PrintWriter  out;
        private final BufferedReader in;

        private boolean gettingInitial = true;
        private final List<String>  initBuf = new ArrayList<>();
        private final List<Integer> verBuf  = new ArrayList<>();

        PeerHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    handlePeerMessage(line.trim());
                }
            } catch (IOException e) {
                System.err.println("Peer disconnected: " + e.getMessage());
            } finally {
                peers.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        synchronized void send(String msg) {
            out.println(msg);
            out.flush();
        }

        private void handlePeerMessage(String line) {

            if (gettingInitial) {
                if (line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        int ver = Integer.parseInt(p[2]);
                        String text = p[3];
                        while (initBuf.size() <= idx) initBuf.add("");
                        while (verBuf.size()  <= idx) verBuf.add(1);
                        initBuf.set(idx, text);
                        verBuf.set(idx, ver);
                    }
                } else if (line.equals("DONE")) {
                    synchronized (ServerFederated.this) {

                        if (initBuf.size() > document.size()) {
                            document.clear(); document.addAll(initBuf);
                            versions.clear(); versions.addAll(verBuf);
                        }
                    }
                    initBuf.clear(); verBuf.clear();
                    gettingInitial = false;
                }
                return;
            }

            if (!line.startsWith("SYNC ")) return;
            String inner = line.substring(5).trim();
            String[] parts = inner.split(" ", 4);
            if (parts.length == 0) return;

            synchronized (ServerFederated.this) {
                switch (parts[0]) {
                    case "ADDL" -> {
                        if (parts.length >= 4) {
                            int    pos  = Integer.parseInt(parts[1]);
                            int    ver  = Integer.parseInt(parts[2]);
                            String text = parts[3];
                            int    idx  = Math.max(0, Math.min(pos - 1, document.size()));
                            document.add(idx, text);
                            versions.add(idx, ver);

                            broadcastToClients(String.format("ADDL %d %d %s", idx + 1, ver, text), null);
                        }
                    }
                    case "RMVL" -> {
                        if (parts.length >= 2) {
                            int pos = Integer.parseInt(parts[1]);
                            int idx = pos - 1;
                            if (idx >= 0 && idx < document.size()) {
                                document.remove(idx);
                                versions.remove(idx);
                                broadcastToClients("RMVL " + pos, null);
                            }
                        }
                    }
                    case "LINE" -> {
                        if (parts.length >= 4) {
                            int    pos  = Integer.parseInt(parts[1]);
                            int    ver  = Integer.parseInt(parts[2]);
                            String text = parts[3];
                            int    idx  = pos - 1;
                            if (idx >= 0 && idx < document.size()) {
                                document.set(idx, text);
                                versions.set(idx, ver);
                                broadcastToClients(String.format("LINE %d %d %s", pos, ver, text), null);
                            }
                        }
                    }
                    default -> System.err.println("[Federation] Unknown SYNC command: " + parts[0]);
                }
            }
        }
    }
}
