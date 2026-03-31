import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ServerRaft {

    record LogEntry(int term, String cmd) {}

    private final AtomicInteger  currentTerm  = new AtomicInteger(0);
    private volatile int         votedFor     = -1;
    private final List<LogEntry> log          = new ArrayList<>();
    private volatile int         commitIndex  = -1;
    private volatile int         lastApplied  = -1;

    private final Map<Integer, Integer> nextIndex  = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> matchIndex = new ConcurrentHashMap<>();

    enum Role { FOLLOWER, CANDIDATE, LEADER }
    private volatile Role role     = Role.FOLLOWER;
    private volatile int  leaderId = -1;

    private int               myId;
    private int               myClientPort;
    private final List<int[]> peers = new ArrayList<>();

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();

    private final Set<ClientHandler>                        clients        = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, RaftPeer>      raftPeers      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ClientHandler> pendingClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer>       voteCount      = new ConcurrentHashMap<>();
    private final BlockingQueue<String>                     applyQueue     = new LinkedBlockingQueue<>();

    private volatile long electionDeadline = 0;
    private static final int ELECTION_TIMEOUT_MIN = 1500;
    private static final int ELECTION_TIMEOUT_MAX = 3000;
    private static final int HEARTBEAT_INTERVAL   = 300;

    private final Random rng = new Random();

    public static void main(String[] args) throws Exception {
        int    clientPort = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        String cfgPath    = (args.length > 1) ? args[1] : "raft.cfg";
        new ServerRaft().start(clientPort, cfgPath);
    }

    void start(int clientPort, String cfgPath) throws Exception {
        myClientPort = clientPort;
        loadConfig(cfgPath);
        System.out.printf("[Raft %d] port=%d  pairs=%s%n",
                myId, myClientPort, peers.stream().map(p -> p[0] + ":" + p[1]).toList());
        resetElectionTimer();

        new Thread(this::applyLoop, "Raft-Apply").start();
        new Thread(() -> { try { listenRaft(); } catch (IOException e) {
        }}, "Raft-Listen").start();
        new Thread(this::electionLoop, "Raft-Election").start();
        new Thread(this::connectToPeers, "Raft-Connect").start();

        try (ServerSocket ss = new ServerSocket(myClientPort)) {
            while (true) {
                Socket s = ss.accept();
                ClientHandler ch = new ClientHandler(s);
                clients.add(ch);
                new Thread(ch, "RaftClient-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void loadConfig(String cfgPath) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(cfgPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) lines.add(line);
            }
        }

        for (String line : lines) {
            String[] t = line.split("[\\s=]+");
            if (t[0].equalsIgnoreCase("myid")) myId = Integer.parseInt(t[1]);
        }

        for (String line : lines) {
            String[] t = line.split("[\\s=]+");
            if (t[0].equalsIgnoreCase("node") && t.length >= 4) {
                int nid = Integer.parseInt(t[1]);
                int np  = Integer.parseInt(t[3]);
                if (nid != myId) peers.add(new int[]{nid, np});
            }
        }
    }

    private int myRaftPort() { return myClientPort + 1000; }

    private void listenRaft() throws IOException {
        try (ServerSocket ss = new ServerSocket(myRaftPort())) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> handleRaftIncoming(s),
                        "RaftIncoming-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void connectToPeers() {
        for (int[] peer : peers) {
            final int peerId = peer[0], peerRaftPort = peer[1] + 1000;
            new Thread(() -> {
                while (true) {
                    try {
                        Socket s = new Socket("localhost", peerRaftPort);
                        RaftPeer rp = new RaftPeer(peerId, s);
                        raftPeers.put(peerId, rp);
                        new Thread(rp, "RaftPeer-" + peerId).start();
                        return;
                    } catch (IOException e) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                    }
                }
            }, "Connect-" + peerId).start();
        }
    }

    private void electionLoop() {
        while (true) {
            try {
                Thread.sleep(20);
                if (role == Role.LEADER) {
                    sendHeartbeats();
                    Thread.sleep(HEARTBEAT_INTERVAL);
                } else if (System.currentTimeMillis() > electionDeadline) {
                    startElection();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void startElection() {
        role = Role.CANDIDATE;
        currentTerm.incrementAndGet();
        votedFor = myId;
        resetElectionTimer();
        int term = currentTerm.get();
        int lastLogIndex = log.size() - 1;
        int lastLogTerm  = (lastLogIndex >= 0) ? log.get(lastLogIndex).term() : 0;

        System.out.printf("[Raft %d] Election term=%d%n", myId, term);

        for (RaftPeer rp : raftPeers.values())
            rp.sendRaw(String.format("RequestVote %d %d %d %d", term, myId, lastLogIndex, lastLogTerm));
    }

    private void sendHeartbeats() {
        for (RaftPeer rp : raftPeers.values()) {
            sendAppendEntries(rp, true);
        }
    }

    private void sendAppendEntries(RaftPeer rp, boolean heartbeatOnly) {
        synchronized (this) {
            int ni        = nextIndex.getOrDefault(rp.peerId, log.size());
            int prevIndex = ni - 1;
            int prevTerm  = (prevIndex >= 0 && prevIndex < log.size()) ? log.get(prevIndex).term() : 0;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("AppendEntries %d %d %d %d %d",
                    currentTerm.get(), myId, prevIndex, prevTerm, commitIndex));

            if (!heartbeatOnly) {

                for (int i = ni; i < log.size(); i++) {
                    LogEntry e = log.get(i);
                    sb.append(i == ni ? " " : "|").append(e.term()).append(":").append(e.cmd());
                }
            }
            rp.sendRaw(sb.toString());
        }
    }

    private void handleRaftIncoming(Socket s) {
        try (s;
             BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter pw    = new PrintWriter(s.getOutputStream(), true)) {
            String line;
            while ((line = br.readLine()) != null) {
                String resp = handleRaftMsg(line.trim());
                if (resp != null) pw.println(resp);
            }
        } catch (IOException e) {

        }
    }

    synchronized String handleRaftMsg(String msg) {
        if (msg.isEmpty()) return null;
        String[] p = msg.split("\\s+");
        return switch (p[0]) {

            case "RequestVote" -> {
                int term     = Integer.parseInt(p[1]);
                int candId   = Integer.parseInt(p[2]);
                int lastIdx  = Integer.parseInt(p[3]);
                int lastTerm = Integer.parseInt(p[4]);

                if (term > currentTerm.get()) {
                    currentTerm.set(term);
                    role = Role.FOLLOWER;
                    votedFor = -1;
                }

                boolean upToDate = (lastTerm > myLastLogTerm())
                        || (lastTerm == myLastLogTerm() && lastIdx >= log.size() - 1);
                boolean granted = (term >= currentTerm.get())
                        && (votedFor == -1 || votedFor == candId)
                        && upToDate;

                if (granted) {
                    votedFor = candId;
                    resetElectionTimer();
                }
                yield String.format("VoteResult %d %b %d", term, granted, myId);
            }

            case "VoteResult" -> {
                int term   = Integer.parseInt(p[1]);
                boolean ok = Boolean.parseBoolean(p[2]);
                if (term > currentTerm.get()) { currentTerm.set(term); role = Role.FOLLOWER; votedFor = -1; yield null; }
                if (role == Role.CANDIDATE && term == currentTerm.get() && ok) {
                    int v = voteCount.merge(term, 1, Integer::sum);
                    if (v + 1 >= (1 + peers.size()) / 2 + 1) becomeLeader();
                }
                yield null;
            }

            case "AppendEntries" -> {
                int term     = Integer.parseInt(p[1]);
                int leadId   = Integer.parseInt(p[2]);
                int prevIdx  = Integer.parseInt(p[3]);
                int prevTerm = Integer.parseInt(p[4]);
                int leaderCI = Integer.parseInt(p[5]);

                if (term < currentTerm.get()) {
                    yield String.format("AppendAck %d false %d", currentTerm.get(), myId);
                }
                currentTerm.set(term); role = Role.FOLLOWER; leaderId = leadId;
                resetElectionTimer();

                if (prevIdx >= 0 && (prevIdx >= log.size() || log.get(prevIdx).term() != prevTerm)) {
                    yield String.format("AppendAck %d false %d", term, myId);
                }

                if (p.length > 6) {
                    String joined = String.join(" ", Arrays.copyOfRange(p, 6, p.length));
                    String[] entries = joined.split("\\|");
                    for (int i = 0; i < entries.length; i++) {
                        String entry = entries[i].trim();
                        int colon = entry.indexOf(':');
                        if (colon < 0) continue;
                        int eTerm = Integer.parseInt(entry.substring(0, colon));
                        String eCmd = entry.substring(colon + 1);
                        int idx = prevIdx + 1 + i;
                        if (idx < log.size()) {
                            if (log.get(idx).term() != eTerm) {
                                while (log.size() > idx) log.remove(log.size() - 1);
                                log.add(new LogEntry(eTerm, eCmd));
                            }
                        } else {
                            log.add(new LogEntry(eTerm, eCmd));
                        }
                    }
                }

                if (leaderCI > commitIndex) {
                    int newCI = Math.min(leaderCI, log.size() - 1);
                    for (int i = commitIndex + 1; i <= newCI; i++) applyQueue.add(log.get(i).cmd());
                    commitIndex = newCI;
                }
                yield String.format("AppendAck %d true %d %d", term, myId, log.size() - 1);
            }

            case "AppendAck" -> {
                int term   = Integer.parseInt(p[1]);
                boolean ok = Boolean.parseBoolean(p[2]);
                int fromId = Integer.parseInt(p[3]);
                int matchI = (p.length > 4) ? Integer.parseInt(p[4]) : -1;
                if (term > currentTerm.get()) { currentTerm.set(term); role = Role.FOLLOWER; votedFor = -1; yield null; }
                if (role != Role.LEADER) yield null;
                if (ok) {
                    if (matchI >= 0) { matchIndex.put(fromId, matchI); nextIndex.put(fromId, matchI + 1); tryAdvanceCommit(); }
                } else {
                    int ni = Math.max(0, nextIndex.getOrDefault(fromId, log.size()) - 1);
                    nextIndex.put(fromId, ni);
                    RaftPeer rp = raftPeers.get(fromId);
                    if (rp != null) sendAppendEntries(rp, false);
                }
                yield null;
            }

            default -> null;
        };
    }

    private void becomeLeader() {
        role = Role.LEADER; leaderId = myId;
        System.out.printf("[Raft %d] LEADER (term %d)%n", myId, currentTerm.get());
        for (int[] peer : peers) { nextIndex.put(peer[0], log.size()); matchIndex.put(peer[0], -1); }
        sendHeartbeats();
        this.notifyAll();
    }

    private void tryAdvanceCommit() {
        for (int n = log.size() - 1; n > commitIndex; n--) {
            if (log.get(n).term() != currentTerm.get()) continue;
            int count = 1;
            for (int mi : matchIndex.values()) if (mi >= n) count++;
            if (count >= (1 + peers.size()) / 2 + 1) {
                for (int i = commitIndex + 1; i <= n; i++) applyQueue.add(log.get(i).cmd());
                commitIndex = n;
                break;
            }
        }
    }

    private int  myLastLogTerm()  { return log.isEmpty() ? 0 : log.get(log.size() - 1).term(); }

    private void resetElectionTimer() {
        electionDeadline = System.currentTimeMillis()
                + ELECTION_TIMEOUT_MIN + rng.nextInt(ELECTION_TIMEOUT_MAX - ELECTION_TIMEOUT_MIN);
    }

    private void applyLoop() {
        while (true) {
            try {
                String cmd = applyQueue.take();
                String pushMsg = null;
                int appliedIdx;
                synchronized (this) {
                    lastApplied++;
                    appliedIdx = lastApplied;
                    pushMsg = applyToDoc(cmd.split(" ", 4));
                }
                if (pushMsg != null) { final String m = pushMsg; for (ClientHandler c : clients) c.send(m); }
                ClientHandler ch = pendingClients.remove(appliedIdx);
                if (ch != null) ch.send("DONE");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String applyToDoc(String[] p) {
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
                int nv = ver + 1;
                document.set(idx, p[3]); versions.set(idx, nv);
                yield String.format("LINE %d %d %s", pos, nv, p[3]);
            }
            default -> null;
        };
    }

    class ClientHandler implements Runnable {
        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;

        ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) handle(line.trim());
            } catch (IOException e) {
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        synchronized void send(String msg) { out.println(msg); out.flush(); }

        private void handle(String cmd) {
            String[] parts = cmd.split(" ", 4);
            switch (parts[0]) {
                case "GETD" -> {
                    synchronized (ServerRaft.this) {
                        for (int i = 0; i < document.size(); i++)
                            send(String.format("LINE %d %d %s", i+1, versions.get(i), document.get(i)));
                        send("DONE");
                    }
                }
                case "GETL" -> {
                    if (parts.length < 2) { send("ERRL GETL index manquant"); return; }
                    int pos = Integer.parseInt(parts[1]);
                    synchronized (ServerRaft.this) {
                        int idx = pos - 1;
                        if (idx < 0 || idx >= document.size()) { send("ERRL hors limites"); return; }
                        send(String.format("LINE %d %d %s", pos, versions.get(idx), document.get(idx)));
                    }
                }
                case "ADDL", "RMVL", "MDFL" -> propose(cmd, this);
                default -> send("ERRL commande inconnue: " + parts[0]);
            }
        }
    }

    private void propose(String cmd, ClientHandler requester) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 3000;
            while (role != Role.LEADER && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (role != Role.LEADER) { requester.send("ERRL NOTLEADER " + leaderId); return; }
            int logIdx;
            synchronized (ServerRaft.this) {
                logIdx = log.size();
                log.add(new LogEntry(currentTerm.get(), cmd));
                pendingClients.put(logIdx, requester);
            }
            for (RaftPeer rp : raftPeers.values()) sendAppendEntries(rp, false);
        }, "Propose").start();
    }

    class RaftPeer implements Runnable {
        final int       peerId;
        private final Socket         socket;
        private final PrintWriter    out;
        private final BufferedReader in;

        RaftPeer(int peerId, Socket socket) throws IOException {
            this.peerId = peerId;
            this.socket = socket;
            this.out    = new PrintWriter(socket.getOutputStream(), true);
            this.in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        synchronized void sendRaw(String msg) { out.println(msg); out.flush(); }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    handleRaftMsg(line.trim());
                }
            } catch (IOException e) {
                raftPeers.remove(peerId);
                int[] peerConf = peers.stream().filter(pp -> pp[0] == peerId).findFirst().orElse(null);
                if (peerConf != null) {
                    final int pp = peerConf[1];
                    new Thread(() -> {
                        while (true) {
                            try {
                                Thread.sleep(500);
                                Socket s = new Socket("localhost", pp + 1000);
                                RaftPeer rp = new RaftPeer(peerId, s);
                                raftPeers.put(peerId, rp);
                                new Thread(rp, "RaftPeer-" + peerId).start();
                                return;
                            } catch (Exception ex) {  }
                        }
                    }, "Reconnect-" + peerId).start();
                }
            }
        }
    }
}
