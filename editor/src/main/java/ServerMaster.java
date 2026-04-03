import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerMaster {

    private String  masterHost;
    private int     masterPort;
    private boolean isMaster;
    private int     myPort;

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();

    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();

    private int seqno = 0;

    private volatile MasterConnection masterConn = null;

    private final ConcurrentHashMap<Integer, ClientHandler> pendingFwds =
            new ConcurrentHashMap<>();
    private final AtomicInteger reqIdGen = new AtomicInteger(1);

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java ServerMaster <port> [peers.cfg]");
            System.exit(1);
        }
        int    port    = Integer.parseInt(args[0]);
        String cfgPath = (args.length >= 2) ? args[1] : "peers.cfg";
        new ServerMaster().start(port, cfgPath);
    }

    public void start(int port, String cfgPath) throws IOException {
        myPort = port;
        parsePeersCfg(cfgPath);
        isMaster = (myPort == masterPort);

        if (!isMaster) {

            new Thread(this::connectToMaster, "MasterConnect").start();
        }

        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                ClientHandler h = new ClientHandler(s);
                clients.add(h);
                new Thread(h, "Client-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void parsePeersCfg(String cfgPath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(cfgPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] tokens = line.split("[\\s=]+", 3);
                if (tokens.length < 3) continue;
                if (tokens[0].equalsIgnoreCase("master")) {
                    masterHost = tokens[1];
                    masterPort = Integer.parseInt(tokens[2]);
                }

            }
        }
        if (masterHost == null)
            throw new IOException("Aucune ligne 'master' dans " + cfgPath);
    }

    private void broadcastLocal(String msg) {
        for (ClientHandler c : clients) {
            if (!c.isSlave) c.send(msg);
        }
    }

    private void broadcastOrder(int seq, int reqId, String pushMsg) {
        String order = "ORDER " + seq + " " + reqId + " " + pushMsg;
        for (ClientHandler c : clients) {
            if (c.isSlave) c.send(order);
        }
    }

    private String applyOp(String[] p) {
        if (p.length < 1) return null;
        return switch (p[0]) {
            case "ADDL" -> {
                if (p.length < 4) yield null;
                int pos = Integer.parseInt(p[1]);
                int ver = Integer.parseInt(p[2]);
                int idx = Math.max(0, Math.min(pos - 1, document.size()));
                document.add(idx, p[3]);
                versions.add(idx, ver);
                yield String.format("ADDL %d %d %s", idx + 1, ver, p[3]);
            }
            case "RMVL" -> {
                if (p.length < 2) yield null;
                int pos = Integer.parseInt(p[1]);
                int idx = pos - 1;
                if (idx < 0 || idx >= document.size()) yield null;
                document.remove(idx);
                versions.remove(idx);
                yield "RMVL " + pos;
            }
            case "MDFL" -> {
                if (p.length < 4) yield null;
                int pos    = Integer.parseInt(p[1]);
                int ver    = Integer.parseInt(p[2]);
                int idx    = pos - 1;
                if (idx < 0 || idx >= document.size()) yield null;
                int newVer = ver + 1;
                document.set(idx, p[3]);
                versions.set(idx, newVer);
                yield String.format("LINE %d %d %s", pos, newVer, p[3]);
            }
            case "LINE" -> {

                if (p.length < 4) yield null;
                int pos = Integer.parseInt(p[1]);
                int ver = Integer.parseInt(p[2]);
                int idx = pos - 1;
                if (idx < 0 || idx >= document.size()) yield null;
                document.set(idx, p[3]);
                versions.set(idx, ver);
                yield String.format("LINE %d %d %s", pos, ver, p[3]);
            }
            default -> null;
        };
    }

    private void connectToMaster() {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                Socket s = new Socket(masterHost, masterPort);
                masterConn = new MasterConnection(s);
                masterConn.send("REGSLAVE");
                new Thread(masterConn, "MasterConn").start();
                return;
            } catch (IOException e) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.err.println("[Esclave] Impossible de joindre le maître");
    }

    class ClientHandler implements Runnable {

        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;
        boolean isSlave = false;

        ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handleCommand(line.trim());
            } catch (IOException e) {
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

            switch (parts[0]) {

                case "REGSLAVE" -> {
                    if (!isMaster) { send("ERRL Not master"); return; }
                    synchronized (ServerMaster.this) {
                        isSlave = true;
                        for (int i = 0; i < document.size(); i++)
                            send(String.format("LINE %d %d %s",
                                    i + 1, versions.get(i), document.get(i)));
                        send("DONE");
                    }
                }

                case "GETD" -> {
                    synchronized (ServerMaster.this) {
                        for (int i = 0; i < document.size(); i++)
                            send(String.format("LINE %d %d %s",
                                    i + 1, versions.get(i), document.get(i)));
                        send("DONE");
                    }
                }

                case "GETL" -> {
                    if (parts.length < 2) { send("ERRL GETL missing index"); return; }
                    int pos = Integer.parseInt(parts[1]);
                    synchronized (ServerMaster.this) {
                        int idx = pos - 1;
                        if (idx < 0 || idx >= document.size()) {
                            send("ERRL " + pos + " out of range");
                            return;
                        }
                        send(String.format("LINE %d %d %s",
                                pos, versions.get(idx), document.get(idx)));
                    }
                }

                case "ADDL", "RMVL", "MDFL" -> {
                    if (isMaster) {

                        synchronized (ServerMaster.this) {
                            seqno++;
                            String push = applyOp(parts);
                            if (push == null) {
                                seqno--;
                                send("ERRL Operation failed");
                                return;
                            }
                            broadcastLocal(push);
                            broadcastOrder(seqno, 0, push);
                        }
                        send("DONE");
                    } else {

                        if (masterConn == null) {
                            send("ERRL No master connection");
                            return;
                        }
                        int reqId = reqIdGen.getAndIncrement();
                        pendingFwds.put(reqId, this);
                        masterConn.send("FWDL " + reqId + " " + cmd);

                    }
                }

                case "FWDL" -> {
                    if (!isMaster) { send("ERRL FWDL only on master"); return; }
                    if (parts.length < 3) { send("ERRL FWDL malformed"); return; }
                    int    reqId   = Integer.parseInt(parts[1]);
                    String innerCmd = (parts.length == 4)
                            ? parts[2] + " " + parts[3]
                            : parts[2];
                    String[] inner = innerCmd.split(" ", 4);
                    synchronized (ServerMaster.this) {
                        seqno++;
                        String push = applyOp(inner);
                        if (push == null) { seqno--; return; }
                        broadcastLocal(push);
                        broadcastOrder(seqno, reqId, push);
                    }

                }

                default -> send("ERRL Unknown command: " + parts[0]);
            }
        }
    }

    class MasterConnection implements Runnable {

        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;

        private boolean             initPhase = true;
        private final List<String>  initDoc   = new ArrayList<>();
        private final List<Integer> initVer   = new ArrayList<>();

        MasterConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handle(line.trim());
            } catch (IOException e) {
                System.err.println("[Esclave] Connexion maître perdue : " + e.getMessage());
            }
        }

        synchronized void send(String msg) {
            out.println(msg);
            out.flush();
        }

        private void handle(String line) {

            if (initPhase) {
                if (line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        while (initDoc.size() <= idx) { initDoc.add(""); initVer.add(1); }
                        initDoc.set(idx, p[3]);
                        initVer.set(idx, Integer.parseInt(p[2]));
                    }
                } else if (line.equals("DONE")) {
                    synchronized (ServerMaster.this) {
                        document.clear();  document.addAll(initDoc);
                        versions.clear();  versions.addAll(initVer);
                    }
                    initDoc.clear(); initVer.clear();
                    initPhase = false;
                }
                return;
            }

            if (!line.startsWith("ORDER ")) return;

            int i1 = line.indexOf(' ');
            int i2 = line.indexOf(' ', i1 + 1);
            int i3 = line.indexOf(' ', i2 + 1);
            if (i1 < 0 || i2 < 0 || i3 < 0) return;

            int    reqId = Integer.parseInt(line.substring(i2 + 1, i3));
            String inner = line.substring(i3 + 1);
            String[] p   = inner.split(" ", 4);

            synchronized (ServerMaster.this) {
                String push = applyOp(p);
                if (push != null) broadcastLocal(push);
            }

            if (reqId != 0) {
                ClientHandler pending = pendingFwds.remove(reqId);
                if (pending != null) pending.send("DONE");
            }
        }
    }
}
