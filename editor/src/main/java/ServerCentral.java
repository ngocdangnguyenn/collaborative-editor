import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerCentral {

    private final List<String>  document = new ArrayList<>();
    private final List<Integer> versions = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5000;
        new ServerCentral().start(port);
    }

    void start(int port) throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                executor.execute(new ClientHandler(s));
            }
        }
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

        @Override
        public void run() {
            try {
                String req;
                while ((req = in.readLine()) != null) processRequest(req.trim());
            } catch (IOException e) {
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private void processRequest(String req) {
            if (req.isEmpty()) return;
            String[] parts = req.split(" ", 2);
            switch (parts[0]) {
                case "GETD" -> sendDocument();
                case "GETL" -> {
                    if (parts.length < 2) { out.println("ERRL GETL missing index"); return; }
                    synchronized (ServerCentral.this) {
                        int idx = Integer.parseInt(parts[1].trim()) - 1;
                        if (idx < 0 || idx >= document.size()) { out.println("ERRL Invalid line number"); return; }
                        out.println("LINE " + (idx+1) + " " + versions.get(idx) + " " + document.get(idx));
                    }
                }
                case "ADDL" -> { if (parts.length > 1) handleAddLine(parts[1]); }
                case "MDFL" -> { if (parts.length > 1) handleModifyLine(parts[1]); }
                case "RMVL" -> { if (parts.length > 1) handleRemoveLine(parts[1]); }
                default     -> out.println("ERRL Unknown command: " + parts[0]);
            }
        }

        private void handleAddLine(String args) {
            String[] t = args.split(" ", 3);
            try {
                int pos = Integer.parseInt(t[0].trim()) - 1;
                int ver = 1;
                String content;
                if (t.length >= 3) {
                    try {
                        ver     = Integer.parseInt(t[1].trim());
                        content = t[2];
                    } catch (NumberFormatException e) {
                        content = args.substring(args.indexOf(' ') + 1);
                    }
                } else if (t.length == 2) {
                    content = t[1];
                } else {
                    out.println("ERRL Malformed ADDL"); return;
                }
                synchronized (ServerCentral.this) {
                    if (pos < 0 || pos > document.size()) { out.println("ERRL Invalid position"); return; }
                    document.add(pos, content);
                    versions.add(pos, ver);
                }
                out.println("DONE");
            } catch (NumberFormatException e) {
                out.println("ERRL Malformed ADDL");
            }
        }

        private void handleModifyLine(String args) {
            String[] t = args.split(" ", 3);
            try {
                int idx = Integer.parseInt(t[0].trim()) - 1;
                int oldVer;
                String content;
                if (t.length >= 3) {
                    oldVer  = Integer.parseInt(t[1].trim());
                    content = t[2];
                } else if (t.length == 2) {
                    oldVer  = 1;
                    content = t[1];
                } else {
                    out.println("ERRL Malformed MDFL"); return;
                }
                synchronized (ServerCentral.this) {
                    if (idx < 0 || idx >= document.size()) { out.println("ERRL Invalid line number"); return; }
                    int curVer = versions.get(idx);
                    if (oldVer != curVer) {
                        out.println("LINE " + (idx+1) + " " + curVer + " " + document.get(idx));
                    } else {
                        document.set(idx, content);
                        versions.set(idx, curVer + 1);
                        out.println("DONE");
                    }
                }
            } catch (NumberFormatException e) {
                out.println("ERRL Malformed MDFL");
            }
        }

        private void handleRemoveLine(String args) {
            try {
                int idx = Integer.parseInt(args.trim()) - 1;
                synchronized (ServerCentral.this) {
                    if (idx < 0 || idx >= document.size()) { out.println("ERRL Invalid line number"); return; }
                    document.remove(idx);
                    versions.remove(idx);
                }
                out.println("DONE");
            } catch (NumberFormatException e) {
                out.println("ERRL Malformed RMVL");
            }
        }

        private void sendDocument() {
            synchronized (ServerCentral.this) {
                for (int i = 0; i < document.size(); i++)
                    out.println("LINE " + (i+1) + " " + versions.get(i) + " " + document.get(i));
            }
            out.println("DONE");
        }
    }
}
