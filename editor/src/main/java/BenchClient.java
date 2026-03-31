import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Locale;

public class BenchClient {

    private final String mode;
    private final String host;
    private final int    port;
    private final int    clientId;
    private final int    numOps;
    private final int    thinkMs;
    private final String outFile;
    private final int    numClients;
    private final Path   barrierDir;

    private PrintWriter    out;
    private BufferedReader in;

    private final List<Double>       latencies = new ArrayList<>();
    private final BlockingQueue<String> ackQ   = new LinkedBlockingQueue<>();

    private final List<String>  doc  = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> vers = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean  capturingFinal = false;
    private final List<String> finalBuf = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch finalLatch = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println(
                "Usage: java BenchClient <direct|dispatch> <host> <port>"
                + " <clientId> <numOps> <thinkMs> <outFile> [numClients barrierDir]");
            System.exit(1);
        }
        String mode    = args[0];
        String host    = args[1];
        int    port    = Integer.parseInt(args[2]);
        int    id      = Integer.parseInt(args[3]);
        int    ops     = Integer.parseInt(args[4]);
        int    think   = Integer.parseInt(args[5]);
        String out     = args[6];
        int    nClts   = (args.length > 7) ? Integer.parseInt(args[7]) : 0;
        Path   barrier = (args.length > 8) ? Paths.get(args[8]) : null;

        new BenchClient(mode, host, port, id, ops, think, out, nClts, barrier).run();
    }

    BenchClient(String mode, String host, int port, int clientId, int numOps,
                int thinkMs, String outFile, int numClients, Path barrierDir) {
        this.mode = mode; this.host = host; this.port = port;
        this.clientId = clientId; this.numOps = numOps;
        this.thinkMs = thinkMs; this.outFile = outFile;
        this.numClients = numClients; this.barrierDir = barrierDir;
    }

    void run() throws Exception {

        String actualHost = host;
        int    actualPort = port;

        if ("dispatch".equals(mode)) {
            try (Socket ds = new Socket(host, port);
                 PrintWriter  dw = new PrintWriter(ds.getOutputStream(), true);
                 BufferedReader dr = new BufferedReader(
                         new InputStreamReader(ds.getInputStream()))) {
                dw.println("GETSERVER");
                String resp = dr.readLine();
                if (resp != null && resp.startsWith("SERVER ")) {
                    String[] p = resp.split(" ", 3);
                    actualHost = p[1];
                    actualPort = Integer.parseInt(p[2]);
                }
            }
            System.out.println("[BenchClient " + clientId + "] dispatch → "
                    + actualHost + ":" + actualPort);
        }

        Socket socket = new Socket(actualHost, actualPort);
        out = new PrintWriter(socket.getOutputStream(), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        out.println("GETD");
        String s;
        List<String>  initDoc = new ArrayList<>();
        List<Integer> initVer = new ArrayList<>();
        while (!(s = in.readLine()).equals("DONE")) {
            if (s.startsWith("LINE ")) {
                String[] p = s.split(" ", 4);
                if (p.length >= 4) {
                    int idx = Integer.parseInt(p[1]) - 1;
                    while (initDoc.size() <= idx) { initDoc.add(""); initVer.add(1); }
                    initDoc.set(idx, p[3]);
                    initVer.set(idx, Integer.parseInt(p[2]));
                }
            }
        }
        doc.addAll(initDoc);
        vers.addAll(initVer);

        Thread listener = new Thread(this::listen, "Listener-" + clientId);
        listener.setDaemon(true);
        listener.start();

        if (numClients > 0 && barrierDir != null) {
            Files.writeString(barrierDir.resolve("ready_" + clientId + ".txt"), "ok");
            long deadline = System.currentTimeMillis() + 30_000;
            while (countFiles("ready_") < numClients
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(40);
            }
            Thread.sleep(100);
        }

        Random rng       = new Random(clientId * 31L + 17);
        long   wallStart = System.currentTimeMillis();

        for (int i = 0; i < numOps; i++) {
            String cmd = buildOp(rng, i);
            if (cmd == null) continue;

            long t0 = System.nanoTime();
            out.println(cmd);
            String ack = ackQ.poll(15, TimeUnit.SECONDS);
            double latMs = (System.nanoTime() - t0) / 1_000_000.0;

            if (ack != null && !ack.startsWith("ERRL")) latencies.add(latMs);
            if (thinkMs > 0) Thread.sleep(thinkMs);
        }

        long wallEnd = System.currentTimeMillis();

        if (numClients > 0 && barrierDir != null) {
            Files.writeString(barrierDir.resolve("done_" + clientId + ".txt"), "ok");
            long deadline = System.currentTimeMillis() + 30_000;
            while (countFiles("done_") < numClients
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(40);
            }
            Thread.sleep(600);
        }

        capturingFinal = true;
        out.println("GETD");
        finalLatch.await(15, TimeUnit.SECONDS);

        double wallMs  = wallEnd - wallStart;
        double avg     = latencies.isEmpty() ? 0
                : latencies.stream().mapToDouble(x -> x).average().getAsDouble();
        double min     = latencies.stream().mapToDouble(x -> x).min().orElse(0);
        double max     = latencies.stream().mapToDouble(x -> x).max().orElse(0);
        double opsPerS = (wallMs > 0) ? latencies.size() / (wallMs / 1000.0) : 0;

        Files.createDirectories(Paths.get(outFile).getParent() == null
                ? Paths.get(".") : Paths.get(outFile).toAbsolutePath().getParent());

        try (PrintWriter pw = new PrintWriter(new FileWriter(outFile))) {
            pw.printf(Locale.US, "STATS %d %d %.2f %.2f %.2f %.2f %.2f%n",
                    clientId, latencies.size(), wallMs, avg, min, max, opsPerS);
            for (String l : new ArrayList<>(finalBuf)) pw.println(l);
        }

        socket.close();
        System.out.printf(Locale.US, "[BenchClient %d] ops=%d wallMs=%.0f avgLat=%.1fms thr=%.1f ops/s%n",
                clientId, latencies.size(), wallMs, avg, opsPerS);
    }

    private String buildOp(Random rng, int opIdx) {
        int size = doc.size();
        int roll = rng.nextInt(10);

        if (size == 0 || roll < 5) {

            int    pos  = (size == 0) ? 1 : rng.nextInt(size + 1) + 1;
            String text = "C" + clientId + "-op" + opIdx + "-" + rng.nextInt(1000);
            int    idx  = Math.max(0, Math.min(pos - 1, doc.size()));
            doc.add(idx, text);
            vers.add(idx, 1);
            return String.format("ADDL %d 1 %s", pos, text);
        } else if (roll < 8) {

            int    pos  = rng.nextInt(size) + 1;
            String text = "C" + clientId + "-mod" + opIdx;
            int    ver  = (pos - 1 < vers.size()) ? vers.get(pos - 1) : 1;
            if (pos - 1 < doc.size()) doc.set(pos - 1, text);
            return String.format("MDFL %d %d %s", pos, ver, text);
        } else {

            int pos = rng.nextInt(size) + 1;
            if (pos - 1 < doc.size())  doc.remove(pos - 1);
            if (pos - 1 < vers.size()) vers.remove(pos - 1);
            return "RMVL " + pos;
        }
    }

    private void listen() {
        try {
            String line;
            while ((line = in.readLine()) != null) {

                if (line.equals("DONE")) {
                    if (capturingFinal) {
                        finalLatch.countDown();
                    } else {
                        ackQ.put("DONE");
                    }

                } else if (line.startsWith("ERRL")) {
                    ackQ.put(line);

                } else if (capturingFinal && line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        while (finalBuf.size() <= idx) finalBuf.add("");
                        finalBuf.set(idx, p[3]);
                    }

                } else if (line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        synchronized (doc) {
                            if (idx >= 0 && idx < doc.size()) {
                                doc.set(idx, p[3]);
                                if (idx < vers.size()) vers.set(idx, Integer.parseInt(p[2]));
                            }
                        }
                    }

                } else if (line.startsWith("ADDL ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length >= 4) {
                        int idx = Math.max(0, Math.min(
                                Integer.parseInt(p[1]) - 1, doc.size()));
                        synchronized (doc) {
                            doc.add(idx, p[3]);
                            vers.add(idx, Integer.parseInt(p[2]));
                        }
                    }

                } else if (line.startsWith("RMVL ")) {
                    String[] p = line.split(" ", 2);
                    if (p.length >= 2) {
                        int idx = Integer.parseInt(p[1]) - 1;
                        synchronized (doc) {
                            if (idx >= 0 && idx < doc.size()) {
                                doc.remove(idx);
                                if (idx < vers.size()) vers.remove(idx);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Listener-" + clientId + "] " + e.getMessage());
        }
    }

    private int countFiles(String prefix) {
        try {
            return (int) Files.list(barrierDir)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .count();
        } catch (IOException e) { return 0; }
    }
}
