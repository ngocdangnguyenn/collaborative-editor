import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoClient {

    private static final int THINK_MS  = 150;
    private static final int SETTLE_MS = 500;

    private final String host;
    private final int    port;
    private final int    clientId;
    private final int    numOps;
    private final String outputFile;
    private final int    totalClients;
    private final Path   barrierDir;

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;

    private final List<String>  localDoc  = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> localVers = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger pendingAcks = new AtomicInteger(0);

    private volatile boolean    initialReady   = false;
    private volatile boolean    capturingFinal = false;
    private final List<String>  finalBuffer    = Collections.synchronizedList(new ArrayList<>());
    private final List<String>  finalDoc       = new ArrayList<>();
    private volatile boolean    finalDone      = false;

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println(
                "Usage: java AutoClient <host> <port> <clientId> <numOps> <outputFile> <totalClients> <barrierDir>");
            System.exit(1);
        }
        new AutoClient(
            args[0], Integer.parseInt(args[1]),
            Integer.parseInt(args[2]), Integer.parseInt(args[3]),
            args[4], Integer.parseInt(args[5]),
            Paths.get(args[6])
        ).run();
    }

    AutoClient(String host, int port, int clientId, int numOps,
               String outputFile, int totalClients, Path barrierDir) {
        this.host = host; this.port = port;
        this.clientId = clientId; this.numOps = numOps;
        this.outputFile = outputFile;
        this.totalClients = totalClients;
        this.barrierDir   = barrierDir;
    }

    void run() throws Exception {
        socket = new Socket(host, port);
        out    = new PrintWriter(socket.getOutputStream(), true);
        in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        Thread listener = new Thread(this::listen, "Listener-" + clientId);
        listener.setDaemon(true);
        listener.start();

        send("GETD");
        waitFor(() -> initialReady, 5000, "GETD initial timeout");
        log("Connected. Document has " + localDoc.size() + " lines.");

        Random rng = new Random(clientId * 31L + 17);
        for (int op = 0; op < numOps; op++) {
            doRandomOp(rng, op);
            Thread.sleep(THINK_MS);
        }
        log("All " + numOps + " ops sent. Waiting for my ACKs...");

        waitFor(() -> pendingAcks.get() == 0, 20000, "ACK timeout");
        log("All my ACKs received.");

        Files.createDirectories(barrierDir);
        Path readyFile = barrierDir.resolve("ready_" + clientId + ".txt");
        Files.writeString(readyFile, "ready");
        log("Barrier: waiting for all " + totalClients + " clients...");

        waitFor(() -> countReadyFiles() >= totalClients, 30000, "Barrier timeout");
        log("All clients ready. Settling...");

        Thread.sleep(SETTLE_MS);

        finalBuffer.clear();
        capturingFinal = true;
        send("GETD");
        waitFor(() -> finalDone, 15000, "GETD final timeout");

        saveResult();
        socket.close();
        log("Done. Saved " + finalDoc.size() + " lines to " + outputFile);
    }

    private int countReadyFiles() {
        try {
            return (int) Files.list(barrierDir)
                .filter(p -> p.getFileName().toString().startsWith("ready_"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void doRandomOp(Random rng, int opNum) {
        int size = localDoc.size();
        int roll = rng.nextInt(10);

        if (size == 0 || roll < 5) {

            int    pos  = (size == 0) ? 1 : rng.nextInt(size + 1) + 1;
            String text = "C" + clientId + "-op" + opNum + "-" + rng.nextInt(1000);
            int    idx  = Math.max(0, Math.min(pos - 1, localDoc.size()));
            localDoc.add(idx, text);
            localVers.add(idx, 1);
            pendingAcks.incrementAndGet();
            send(String.format("ADDL %d 1 %s", pos, text));

        } else if (roll < 8 && size > 0) {

            int    pos    = rng.nextInt(size) + 1;
            String text   = "C" + clientId + "-mod" + opNum;
            int    oldVer = (pos - 1 < localVers.size()) ? localVers.get(pos - 1) : 1;
            if (pos - 1 < localDoc.size()) localDoc.set(pos - 1, text);
            pendingAcks.incrementAndGet();
            send(String.format("MDFL %d %d %s", pos, oldVer, text));

        } else if (size > 0) {

            int pos = rng.nextInt(size) + 1;
            if (pos - 1 < localDoc.size()) localDoc.remove(pos - 1);
            if (pos - 1 < localVers.size()) localVers.remove(pos - 1);
            pendingAcks.incrementAndGet();
            send(String.format("RMVL %d", pos));
        }
    }

    private void listen() {
        try {
            boolean gettingInitial = true;
            List<String>  initDocBuf = new ArrayList<>();
            List<Integer> initVerBuf = new ArrayList<>();

            String line;
            while ((line = in.readLine()) != null) {

                if (line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length < 4) continue;
                    int    idx  = Integer.parseInt(p[1]) - 1;
                    int    ver  = Integer.parseInt(p[2]);
                    String text = p[3];

                    if (gettingInitial) {
                        while (initDocBuf.size() <= idx) initDocBuf.add("");
                        while (initVerBuf.size() <= idx) initVerBuf.add(1);
                        initDocBuf.set(idx, text);
                        initVerBuf.set(idx, ver);

                    } else if (capturingFinal) {
                        while (finalBuffer.size() <= idx) finalBuffer.add("");
                        finalBuffer.set(idx, text);

                    } else {

                        if (idx < localDoc.size()) {
                            localDoc.set(idx, text);
                            localVers.set(idx, ver);
                        }
                    }

                } else if (line.equals("DONE")) {
                    if (gettingInitial) {
                        localDoc.clear();  localDoc.addAll(initDocBuf);
                        localVers.clear(); localVers.addAll(initVerBuf);
                        gettingInitial = false;
                        initialReady   = true;

                    } else if (capturingFinal) {

                        finalDoc.clear();
                        finalDoc.addAll(finalBuffer);
                        capturingFinal = false;
                        finalDone      = true;

                    } else {

                        pendingAcks.decrementAndGet();
                    }

                } else if (line.startsWith("ADDL ") && !gettingInitial && !capturingFinal) {
                    String[] p = line.split(" ", 4);
                    if (p.length < 4) continue;
                    int    idx  = Math.max(0, Math.min(Integer.parseInt(p[1]) - 1, localDoc.size()));
                    String text = p[3];
                    int    ver  = Integer.parseInt(p[2]);
                    localDoc.add(idx, text);
                    localVers.add(idx, ver);

                } else if (line.startsWith("RMVL ") && !gettingInitial && !capturingFinal) {
                    String[] p = line.split(" ", 3);
                    if (p.length < 2) continue;
                    int idx = Integer.parseInt(p[1]) - 1;
                    if (idx >= 0 && idx < localDoc.size()) localDoc.remove(idx);
                    if (idx >= 0 && idx < localVers.size()) localVers.remove(idx);

                } else if (line.startsWith("ERRL ")) {
                    System.err.println("[Client " + clientId + "] Server error: " + line.substring(5));

                    if (!gettingInitial && !capturingFinal && pendingAcks.get() > 0) {
                        pendingAcks.decrementAndGet();
                    }
                }
            }
        } catch (IOException e) {

        }
    }

    private synchronized void send(String cmd) {
        out.println(cmd);
        out.flush();
    }

    private void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String msg)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline)
                throw new RuntimeException("[Client " + clientId + "] " + msg);
            Thread.sleep(30);
        }
    }

    private void saveResult() throws IOException {
        Path outPath = Paths.get(outputFile);
        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
        try (PrintWriter w = new PrintWriter(new FileWriter(outputFile))) {
            for (String l : finalDoc) w.println(l);
        }
    }

    private void log(String msg) {
        System.out.printf("[Client %d] %s%n", clientId, msg);
    }
}
