import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerCentralPush {

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    public static void main(String[] args) throws IOException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        new ServerCentralPush().start(port);
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

    class ClientHandler implements Runnable {

        private final Socket       socket;
        private final PrintWriter  out;
        private final BufferedReader in;

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
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        synchronized void send(String msg) { out.println(msg); out.flush(); }

        private void broadcast(String msg) {
            for (ClientHandler c : clients) { if (c != this) c.send(msg); }
        }

        private void handleCommand(String cmd) {
            String[] parts = cmd.split(" ", 4);
            if (parts.length == 0) return;
            String kw = parts[0];
            synchronized (ServerCentralPush.this) {
                switch (kw) {
                    case "GETD" -> handleGetD();
                    case "GETL" -> handleGetL(parts);
                    case "ADDL" -> handleAddL(parts);
                    case "RMVL" -> handleRmvL(parts);
                    case "MDFL" -> handleMdfL(parts);
                    default     -> send("ERRL Unknown command: " + kw);
                }
            }
        }

        private void handleGetD() {
            for (int i = 0; i < document.size(); i++)
                send(String.format("LINE %d %d %s", i + 1, versions.get(i), document.get(i)));
            send("DONE");
        }

        private void handleGetL(String[] parts) {
            if (parts.length < 2) { send("ERRL GETL missing index"); return; }
            int pos = Integer.parseInt(parts[1]);
            int idx = pos - 1;
            if (idx < 0 || idx >= document.size()) { send("ERRL " + pos + " out of range"); return; }
            send(String.format("LINE %d %d %s", pos, versions.get(idx), document.get(idx)));
        }

        private void handleAddL(String[] parts) {
            if (parts.length < 4) { send("ERRL ADDL malformed"); return; }
            int    pos  = Integer.parseInt(parts[1]);
            int    ver  = Integer.parseInt(parts[2]);
            String text = parts[3];

            int idx = Math.max(0, Math.min(pos - 1, document.size()));
            document.add(idx, text);
            versions.add(idx, ver);

            broadcast(String.format("ADDL %d %d %s", idx + 1, ver, text));
            send("DONE");
        }

        private void handleRmvL(String[] parts) {
            if (parts.length < 2) { send("ERRL RMVL missing index"); return; }
            int pos = Integer.parseInt(parts[1]);
            int idx = pos - 1;
            if (idx < 0 || idx >= document.size()) { send("ERRL " + pos + " out of range"); return; }

            document.remove(idx);
            versions.remove(idx);
            broadcast("RMVL " + pos);
            send("DONE");
        }

        private void handleMdfL(String[] parts) {
            if (parts.length < 4) { send("ERRL MDFL malformed"); return; }
            int    pos  = Integer.parseInt(parts[1]);
            int    ver  = Integer.parseInt(parts[2]);
            String text = parts[3];
            int    idx  = pos - 1;
            if (idx < 0 || idx >= document.size()) { send("ERRL " + pos + " out of range"); return; }

            document.set(idx, text);
            int newVer = ver + 1;
            versions.set(idx, newVer);
            broadcast(String.format("LINE %d %d %s", pos, newVer, text));
            send("DONE");
        }
    }
}
