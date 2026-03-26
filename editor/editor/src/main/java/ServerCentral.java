import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerCentral {
    private static final int PORT = 5000;
    private static List<String> document = Collections.synchronizedList(new ArrayList<>());
    private static List<Integer> versions = Collections.synchronizedList(new ArrayList<>());
    private static ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("ServerCentral started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                executor.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String request;
                while ((request = in.readLine()) != null) {
                    processRequest(request.trim());
                }
            } catch (IOException e) {
                System.err.println("Client disconnected");
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void processRequest(String request) {
            if (request == null || request.isEmpty()) return;

            String[] parts = request.split(" ", 2);
            String command = parts[0];

            try {
                switch (command) {
                    case "GETD":
                        sendDocument(out);
                        break;

                    case "GETL":
                        if (parts.length > 1) {
                            int lineNum = Integer.parseInt(parts[1].trim()) - 1;
                            if (lineNum >= 0 && lineNum < document.size()) {
                                int ver = versions.get(lineNum);
                                out.println("LINE " + (lineNum + 1) + " " + ver + " " + document.get(lineNum));
                            } else {
                                out.println("ERRL Invalid line number");
                            }
                        }
                        break;

                    case "ADDL":
                        if (parts.length > 1) {
                            handleAddLine(parts[1]);
                        }
                        break;

                    case "MDFL":
                        if (parts.length > 1) {
                            handleModifyLine(parts[1]);
                        }
                        break;

                    case "RMVL":
                        if (parts.length > 1) {
                            handleRemoveLine(parts[1]);
                        }
                        break;

                    default:
                        out.println("ERRL Unknown command");
                }
            } catch (Exception e) {
                out.println("ERRL " + e.getMessage());
            }
        }

        private void handleAddLine(String args) {
            // Format: ADDL <pos> <ver> <text>  OR  ADDL <pos> <text>
            String[] tokens = args.split(" ", 3);
            try {
                int pos = Integer.parseInt(tokens[0].trim()) - 1;
                int ver = 1;
                String content = "";
        
                if (tokens.length == 2) {
                    // ADDL <pos> <text>
                    content = tokens[1];
                } else if (tokens.length >= 3) {
                    // ADDL <pos> <ver> <text>
                    // Nếu tokens[1] là số thì parse, còn không thì coi là text luôn
                    try {
                        ver = Integer.parseInt(tokens[1].trim());
                        content = tokens[2];
                    } catch (NumberFormatException e) {
                        // Trường hợp này là ADDL <pos> <text có dấu cách>
                        ver = 1;
                        content = args.substring(args.indexOf(' ') + 1);
                    }
                }
        
                if (pos >= 0 && pos <= document.size()) {
                    synchronized (document) {
                        document.add(pos, content);
                        versions.add(pos, ver);
                    }
                    out.println("DONE");
                } else {
                    out.println("ERRL Invalid position");
                }
            } catch (NumberFormatException ex) {
                out.println("ERRL Malformed ADDL");
            }
        }

        private void handleModifyLine(String args) {
            // Format: MDFL <pos> <oldVer> <text>  OR  MDFL <pos> <text>
            String[] tokens = args.split(" ", 3);
            try {
                int lineNum = Integer.parseInt(tokens[0].trim()) - 1;
                int oldVer = 1;
                String content = "";

                if (tokens.length == 2) {
                    // MDFL <pos> <text>
                    content = tokens[1];
                    oldVer = 1;
                } else if (tokens.length >= 3) {
                    // MDFL <pos> <oldVer> <text>
                    oldVer = Integer.parseInt(tokens[1].trim());
                    content = tokens[2];
                }

                if (lineNum >= 0 && lineNum < document.size()) {
                    int curVer = versions.get(lineNum);
                    // Check version match
                    if (oldVer != curVer) {
                        // Version mismatch - send back current state (conflict)
                        out.println("LINE " + (lineNum + 1) + " " + curVer + " " + document.get(lineNum));
                    } else {
                        // Version match - apply update
                        int newVer = curVer + 1;
                        synchronized (document) {
                            document.set(lineNum, content);
                            versions.set(lineNum, newVer);
                        }
                        out.println("DONE");
                    }
                } else {
                    out.println("ERRL Invalid line number");
                }
            } catch (NumberFormatException ex) {
                out.println("ERRL Malformed MDFL");
            }
        }

        private void handleRemoveLine(String args) {
            // Format: RMVL <pos>
            try {
                int lineNum = Integer.parseInt(args.trim()) - 1;
                if (lineNum >= 0 && lineNum < document.size()) {
                    synchronized (document) {
                        document.remove(lineNum);
                        versions.remove(lineNum);
                    }
                    out.println("DONE");
                } else {
                    out.println("ERRL Invalid line number");
                }
            } catch (NumberFormatException ex) {
                out.println("ERRL Malformed RMVL");
            }
        }

        private void sendDocument(PrintWriter pw) {
            synchronized (document) {
                for (int i = 0; i < document.size(); i++) {
                    int ver = versions.get(i);
                    pw.println("LINE " + (i + 1) + " " + ver + " " + document.get(i));
                }
            }
            pw.println("DONE");
        }


    }
}
