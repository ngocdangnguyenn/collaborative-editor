import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ServerBully {

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();
    private int seqno = 0;

    private int myId;
    private int myClientPort;

    private final Map<Integer, String[]> nodesMap = new TreeMap<>();

    enum Role { LEADER, FOLLOWER }
    private volatile Role role     = Role.FOLLOWER;
    private volatile int  leaderId = -1;

    private final Set<ClientHandler>                        clients     = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, BullyPeer>     bullyPeers  = new ConcurrentHashMap<>();
    private volatile LeaderConnection                       leaderConn  = null;
    private final ConcurrentHashMap<Integer, ClientHandler> pendingFwds = new ConcurrentHashMap<>();
    private final AtomicInteger reqIdGen = new AtomicInteger(1);

    private static final int HEARTBEAT_INTERVAL_MS = 1000;
    private static final int HEARTBEAT_TIMEOUT_MS  = 3000;
    private static final int ELECTION_TIMEOUT_MS   = 2000;

    private volatile long    lastHeartbeat      = System.currentTimeMillis();
    private volatile boolean electionInProgress = false;

    public static void main(String[] args) throws Exception {
        int    port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        String cfg  = (args.length > 1) ? args[1] : "bully.cfg";
        new ServerBully().start(port, cfg);
    }

    void start(int clientPort, String cfgPath) throws Exception {
        myClientPort = clientPort;
        loadConfig(cfgPath);
        System.out.printf("[Bully %d] port=%d%n", myId, myClientPort);
        new Thread(this::listenBully,    "Bully-Listen").start();
        new Thread(this::connectToPeers, "Bully-Connect").start();
        new Thread(this::watchdog,       "Bully-Watchdog").start();
        try (ServerSocket ss = new ServerSocket(myClientPort)) {
            while (true) {
                Socket s = ss.accept();
                ClientHandler ch = new ClientHandler(s);
                clients.add(ch);
                new Thread(ch, "Client-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void loadConfig(String cfgPath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(cfgPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("[\\s=]+");
                if (t[0].equalsIgnoreCase("myid") && t.length >= 2)
                    myId = Integer.parseInt(t[1]);
                else if (t[0].equalsIgnoreCase("node") && t.length >= 4)
                    nodesMap.put(Integer.parseInt(t[1]), new String[]{t[2], t[3]});
            }
        }
    }

    private int bullyPort(int clientPort) { return clientPort + 1000; }

    private void listenBully() {
        try (ServerSocket ss = new ServerSocket(bullyPort(myClientPort))) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> handleBullyIncoming(s), "BullyIn").start();
            }
        } catch (IOException e) {
            System.err.println("[Bully " + myId + "] listen error: " + e.getMessage());
        }
    }

    private void connectToPeers() {
        for (Map.Entry<Integer, String[]> entry : nodesMap.entrySet()) {
            int peerId = entry.getKey();
            if (peerId == myId) continue;
            String host     = entry.getValue()[0];
            int    peerPort = Integer.parseInt(entry.getValue()[1]);
            new Thread(() -> {
                while (true) {
                    if (bullyPeers.containsKey(peerId)) { sleepMs(2000); continue; }
                    try {
                        Socket s = new Socket(host, bullyPort(peerPort));
                        BullyPeer bp = new BullyPeer(peerId, s);
                        bullyPeers.put(peerId, bp);
                        new Thread(bp, "BullyPeer-" + peerId).start();
                    } catch (IOException e) {
                        sleepMs(1000);
                    }
                }
            }, "BullyConnect-" + peerId).start();
        }
        sleepMs(3000);
        startElection();
    }

    private void handleBullyIncoming(Socket s) {
        try (s;
             BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter    pw = new PrintWriter(s.getOutputStream(), true)) {
            String line;
            while ((line = br.readLine()) != null) handleBullyMsg(line.trim(), pw);
        } catch (IOException ignored) {}
    }

    private synchronized void handleBullyMsg(String msg, PrintWriter replyTo) {
        if (msg.isEmpty()) return;
        String[] p = msg.split("\\s+");
        switch (p[0]) {
            case "ELECTION" -> {
                int candidateId = Integer.parseInt(p[1]);
                if (myId > candidateId) {
                    replyTo.println("OK " + myId);
                    if (!electionInProgress) startElection();
                }
            }
            case "OK"          -> electionInProgress = false;
            case "COORDINATOR" -> {
                int newLeader = Integer.parseInt(p[1]);
                electionInProgress = false;
                if (newLeader == leaderId && role == Role.LEADER) return;
                leaderId = newLeader;
                if (myId == newLeader) {
                    becomeLeader();
                } else {
                    role = Role.FOLLOWER;
                    lastHeartbeat = System.currentTimeMillis();
                    connectToLeader();
                }
            }
            case "HEARTBEAT" -> {
                int fromLeader = Integer.parseInt(p[1]);
                if (fromLeader == leaderId) lastHeartbeat = System.currentTimeMillis();
            }
        }
    }

    private synchronized void startElection() {
        if (electionInProgress) return;
        electionInProgress = true;
        System.out.printf("[Bully %d] election%n", myId);
        boolean sentToHigher = false;
        for (Map.Entry<Integer, BullyPeer> e : bullyPeers.entrySet()) {
            if (e.getKey() > myId) {
                e.getValue().send("ELECTION " + myId);
                sentToHigher = true;
            }
        }
        if (!sentToHigher) { becomeLeader(); return; }
        new Thread(() -> {
            sleepMs(ELECTION_TIMEOUT_MS);
            synchronized (ServerBully.this) {
                if (electionInProgress) becomeLeader();
            }
        }, "ElectionTimer").start();
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leaderId = myId;
        electionInProgress = false;
        System.out.printf("[Bully %d] leader%n", myId);
        if (leaderConn != null) { leaderConn.close(); leaderConn = null; }
        for (BullyPeer bp : bullyPeers.values()) bp.send("COORDINATOR " + myId);
        new Thread(this::sendHeartbeats, "Bully-Heartbeat").start();
    }

    private void sendHeartbeats() {
        while (role == Role.LEADER) {
            for (BullyPeer bp : bullyPeers.values()) bp.send("HEARTBEAT " + myId);
            sleepMs(HEARTBEAT_INTERVAL_MS);
        }
    }

    private void watchdog() {
        sleepMs(4000);
        while (true) {
            sleepMs(500);
            if (role == Role.LEADER) continue;
            if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS && !electionInProgress)
                startElection();
        }
    }

    private void connectToLeader() {
        if (leaderId < 0) return;
        String[] node = nodesMap.get(leaderId);
        if (node == null) return;
        String leaderHost = node[0];
        int    leaderPort = Integer.parseInt(node[1]);
        new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                try {
                    Socket s = new Socket(leaderHost, leaderPort);
                    LeaderConnection lc = new LeaderConnection(s);
                    leaderConn = lc;
                    lc.send("REGSLAVE");
                    new Thread(lc, "LeaderConn").start();
                    return;
                } catch (IOException e) { sleepMs(500); }
            }
            System.err.printf("[Bully %d] cannot reach leader %d%n", myId, leaderId);
        }, "ConnectLeader").start();
    }

    private synchronized void broadcastLocal(String msg) {
        for (ClientHandler c : clients) if (!c.isSlave) c.send(msg);
    }

    private synchronized void broadcastOrder(int seq, int reqId, String pushMsg) {
        String order = "ORDER " + seq + " " + reqId + " " + pushMsg;
        for (ClientHandler c : clients) if (c.isSlave) c.send(order);
    }

    private synchronized String applyOp(String[] p) {
        if (p.length < 1) return null;
        return switch (p[0]) {
            case "ADDL" -> {
                if (p.length < 4) yield null;
                int pos = Integer.parseInt(p[1]), ver = Integer.parseInt(p[2]);
                int idx = Math.max(0, Math.min(pos - 1, document.size()));
                document.add(idx, p[3]); versions.add(idx, ver);
                yield String.format("ADDL %d %d %s", idx + 1, ver, p[3]);
            }
            case "RMVL" -> {
                if (p.length < 2) yield null;
                int idx = Integer.parseInt(p[1]) - 1;
                if (idx < 0 || idx >= document.size()) yield null;
                document.remove(idx); versions.remove(idx);
                yield "RMVL " + (idx + 1);
            }
            case "MDFL" -> {
                if (p.length < 4) yield null;
                int pos = Integer.parseInt(p[1]), ver = Integer.parseInt(p[2]);
                int idx = pos - 1;
                if (idx < 0 || idx >= document.size()) yield null;
                document.set(idx, p[3]); versions.set(idx, ver + 1);
                yield String.format("LINE %d %d %s", pos, ver + 1, p[3]);
            }
            default -> null;
        };
    }

    class BullyPeer implements Runnable {
        final int peerId;
        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;

        BullyPeer(int peerId, Socket s) throws IOException {
            this.peerId = peerId;
            this.socket = s;
            this.out = new PrintWriter(s.getOutputStream(), true);
            this.in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
        }

        synchronized void send(String msg) { out.println(msg); out.flush(); }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handleBullyMsg(line.trim(), out);
            } catch (IOException e) {
                bullyPeers.remove(peerId);
                if (peerId == leaderId) startElection();
            }
        }
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

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handle(line.trim());
            } catch (IOException ignored) {
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        synchronized void send(String msg) { out.println(msg); out.flush(); }

        private void handle(String cmd) {
            String[] parts = cmd.split(" ", 4);
            if (parts.length == 0) return;
            switch (parts[0]) {
                case "REGSLAVE" -> {
                    if (role != Role.LEADER) { send("ERRL not leader"); return; }
                    synchronized (ServerBully.this) {
                        isSlave = true;
                        for (int i = 0; i < document.size(); i++)
                            send(String.format("LINE %d %d %s", i + 1, versions.get(i), document.get(i)));
                        send("DONE");
                    }
                }
                case "GETD" -> {
                    synchronized (ServerBully.this) {
                        for (int i = 0; i < document.size(); i++)
                            send(String.format("LINE %d %d %s", i + 1, versions.get(i), document.get(i)));
                        send("DONE");
                    }
                }
                case "GETL" -> {
                    if (parts.length < 2) { send("ERRL missing index"); return; }
                    int pos = Integer.parseInt(parts[1]);
                    synchronized (ServerBully.this) {
                        int idx = pos - 1;
                        if (idx < 0 || idx >= document.size()) { send("ERRL out of range"); return; }
                        send(String.format("LINE %d %d %s", pos, versions.get(idx), document.get(idx)));
                    }
                }
                case "ADDL", "RMVL", "MDFL" -> {
                    if (role == Role.LEADER) {
                        synchronized (ServerBully.this) {
                            seqno++;
                            String push = applyOp(parts);
                            if (push == null) { seqno--; send("ERRL operation failed"); return; }
                            broadcastLocal(push);
                            broadcastOrder(seqno, 0, push);
                        }
                        send("DONE");
                    } else {
                        if (leaderConn == null) { send("ERRL no leader"); return; }
                        int reqId = reqIdGen.getAndIncrement();
                        pendingFwds.put(reqId, this);
                        leaderConn.send("FWDL " + reqId + " " + cmd);
                    }
                }
                case "FWDL" -> {
                    if (role != Role.LEADER) { send("ERRL FWDL only on leader"); return; }
                    if (parts.length < 3) { send("ERRL malformed"); return; }
                    int    reqId    = Integer.parseInt(parts[1]);
                    String innerCmd = parts.length == 4 ? parts[2] + " " + parts[3] : parts[2];
                    String[] inner  = innerCmd.split(" ", 4);
                    synchronized (ServerBully.this) {
                        seqno++;
                        String push = applyOp(inner);
                        if (push == null) { seqno--; return; }
                        broadcastLocal(push);
                        broadcastOrder(seqno, reqId, push);
                    }
                }
                default -> send("ERRL unknown: " + parts[0]);
            }
        }
    }

    class LeaderConnection implements Runnable {
        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;
        private boolean             initPhase = true;
        private final List<String>  initDoc   = new ArrayList<>();
        private final List<Integer> initVer   = new ArrayList<>();

        LeaderConnection(Socket s) throws IOException {
            this.socket = s;
            this.out    = new PrintWriter(s.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(s.getInputStream()));
        }

        synchronized void send(String msg) { out.println(msg); out.flush(); }
        void close() { try { socket.close(); } catch (IOException ignored) {} }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handle(line.trim());
            } catch (IOException e) {
                leaderConn = null;
                startElection();
            }
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
                    synchronized (ServerBully.this) {
                        document.clear(); document.addAll(initDoc);
                        versions.clear(); versions.addAll(initVer);
                    }
                    initDoc.clear(); initVer.clear();
                    initPhase = false;
                }
                return;
            }
            if (!line.startsWith("ORDER ")) return;
            String[] tok = line.split(" ", 4);
            if (tok.length < 4) return;
            int    reqId = Integer.parseInt(tok[2]);
            String[] p   = tok[3].split(" ", 4);
            synchronized (ServerBully.this) {
                String push = applyOp(p);
                if (push != null) broadcastLocal(push);
            }
            if (reqId != 0) {
                ClientHandler pending = pendingFwds.remove(reqId);
                if (pending != null) pending.send("DONE");
            }
        }
    }

    private static void sleepMs(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}