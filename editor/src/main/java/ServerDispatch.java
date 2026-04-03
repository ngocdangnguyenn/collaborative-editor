import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerDispatch {

    private final List<String[]> servers    = new ArrayList<>();
    private final AtomicInteger  roundRobin = new AtomicInteger(0);

    public static void main(String[] args) throws IOException {
        int    port = (args.length > 0) ? Integer.parseInt(args[0]) : 4999;
        String cfg  = (args.length > 1) ? args[1] : "dispatch.cfg";
        new ServerDispatch().start(port, cfg);
    }

    void start(int port, String cfgPath) throws IOException {
        loadConfig(cfgPath);
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> handle(s),
                        "Dispatch-" + s.getRemoteSocketAddress()).start();
            }
        }
    }

    private void loadConfig(String cfgPath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(cfgPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] t = line.split("[\\s=]+", 3);
                if (t.length >= 3 && t[0].equalsIgnoreCase("server")) {
                    servers.add(new String[]{t[1], t[2]});
                }
            }
        }
        if (servers.isEmpty())
            throw new IOException("Aucun serveur déclaré dans " + cfgPath);
    }

    private void handle(Socket s) {
        try (s;
             BufferedReader in  = new BufferedReader(
                     new InputStreamReader(s.getInputStream()));
             PrintWriter    out = new PrintWriter(s.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.equals("GETSERVER")) {
                    String[] srv = servers.get(
                            Math.abs(roundRobin.getAndIncrement()) % servers.size());
                    out.println("SERVER " + srv[0] + " " + srv[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("[Dispatch] " + e.getMessage());
        }
    }
}
