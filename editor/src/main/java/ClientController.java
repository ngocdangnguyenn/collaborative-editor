import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.application.Platform;
import java.io.*;
import java.net.Socket;
import java.util.*;

public class ClientController {

    @FXML private ListView<String> listView;
    @FXML private TextField textField;
    @FXML private MenuItem deleteLineMenuItem;

    private static final boolean USE_PUSH = true;

    private String serverHost = "localhost";
    private int    serverPort = 5000;

    private Socket        socket;
    private PrintWriter   out;
    private BufferedReader in;

    private final List<String>  localDocument = new ArrayList<>();
    private final List<Integer> localVersions  = new ArrayList<>();

    private volatile boolean    isGettingDocument = false;
    private final List<String>  documentBuffer    = new ArrayList<>();
    private final List<Integer> versionsBuffer    = new ArrayList<>();

    @FXML
    public void initialize() {
        String envHost = System.getenv("SERVER_HOST");
        String envPort = System.getenv("SERVER_PORT");
        if (envHost != null && !envHost.isEmpty()) serverHost = envHost;
        if (envPort != null && !envPort.isEmpty()) {
            try { serverPort = Integer.parseInt(envPort); }
            catch (NumberFormatException e) {
                System.err.println("Invalid SERVER_PORT, using default: " + serverPort);
            }
        }

        try {
            socket = new Socket(serverHost, serverPort);
            out    = new PrintWriter(socket.getOutputStream(), true);
            in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        } catch (IOException e) {
            System.err.println("Cannot connect to server: " + e.getMessage());
            return;
        }

        isGettingDocument = true;
        new Thread(this::serverListener, "ServerListener").start();
        sendCommand("GETD");

        listView.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            deleteLineMenuItem.setDisable(n == null);
            if (n != null) textField.setText(n);
        });
    }

    private void serverListener() {
        try {
            String line;
            while ((line = in.readLine()) != null) {

                if (line.startsWith("LINE ")) {
                    String[] p = line.split(" ", 4);
                    if (p.length < 4) continue;
                    int    idx  = Integer.parseInt(p[1]) - 1;
                    int    ver  = Integer.parseInt(p[2]);
                    String text = p[3];

                    if (isGettingDocument) {
                        while (documentBuffer.size() <= idx) documentBuffer.add("");
                        while (versionsBuffer.size() <= idx) versionsBuffer.add(1);
                        documentBuffer.set(idx, text);
                        versionsBuffer.set(idx, ver);
                    } else if (USE_PUSH) {

                        final int fi = idx; final int fv = ver; final String ft = text;
                        Platform.runLater(() -> {
                            if (fi < listView.getItems().size()) {
                                listView.getItems().set(fi, ft);
                                localDocument.set(fi, ft);
                                localVersions.set(fi, fv);
                            }
                        });
                    }

                } else if (line.equals("DONE")) {
                    if (isGettingDocument) {
                        final List<String>  doc = new ArrayList<>(documentBuffer);
                        final List<Integer> ver = new ArrayList<>(versionsBuffer);
                        documentBuffer.clear();
                        versionsBuffer.clear();
                        isGettingDocument = false;
                        Platform.runLater(() -> {
                            localDocument.clear(); localDocument.addAll(doc);
                            localVersions.clear(); localVersions.addAll(ver);
                            listView.getItems().setAll(localDocument);
                        });
                    }

                } else if (line.startsWith("ADDL ") && USE_PUSH && !isGettingDocument) {
                    String[] p = line.split(" ", 4);
                    if (p.length < 4) continue;
                    int    idx  = Integer.parseInt(p[1]) - 1;
                    int    ver  = Integer.parseInt(p[2]);
                    String text = p[3];
                    final int fi = idx; final int fv = ver; final String ft = text;
                    Platform.runLater(() -> {
                        int insIdx = Math.max(0, Math.min(fi, listView.getItems().size()));
                        listView.getItems().add(insIdx, ft);
                        localDocument.add(insIdx, ft);
                        localVersions.add(insIdx, fv);
                    });

                } else if (line.startsWith("RMVL ") && USE_PUSH && !isGettingDocument) {
                    String[] p = line.split(" ", 3);
                    if (p.length < 2) continue;
                    int idx = Integer.parseInt(p[1]) - 1;
                    Platform.runLater(() -> {
                        if (idx >= 0 && idx < listView.getItems().size()) {
                            listView.getItems().remove(idx);
                            if (idx < localDocument.size()) localDocument.remove(idx);
                            if (idx < localVersions.size()) localVersions.remove(idx);
                        }
                    });

                } else if (line.startsWith("ERRL ")) {
                    System.err.println("Server error: " + line.substring(5));
                }
            }
        } catch (IOException e) {
            System.err.println("ServerListener stopped: " + e.getMessage());
        }
    }

    @FXML
    private void handleAddLine() {
        int    sel  = listView.getSelectionModel().getSelectedIndex();
        String text = textField.getText();
        if (text == null || text.isEmpty()) text = "(New Line)";

        int pos    = (sel == -1) ? listView.getItems().size() + 1 : sel + 2;
        int insIdx = pos - 1;

        localDocument.add(insIdx, text);
        localVersions.add(insIdx, 1);
        listView.getItems().add(insIdx, text);

        sendCommand(String.format("ADDL %d 1 %s", pos, text));
    }

    @FXML
    private void handleDeleteLine() {
        int sel = listView.getSelectionModel().getSelectedIndex();
        if (sel < 0) return;

        if (sel < localDocument.size()) localDocument.remove(sel);
        if (sel < localVersions.size()) localVersions.remove(sel);
        listView.getItems().remove(sel);

        sendCommand("RMVL " + (sel + 1));
    }

    @FXML
    private void handleTextFieldUpdate() {
        int sel = listView.getSelectionModel().getSelectedIndex();
        if (sel < 0) return;

        String text   = textField.getText();
        if (text == null) text = "";
        int    oldVer = (sel < localVersions.size()) ? localVersions.get(sel) : 1;

        if (sel < localDocument.size()) localDocument.set(sel, text);
        listView.getItems().set(sel, text);

        sendCommand(String.format("MDFL %d %d %s", sel + 1, oldVer, text));
    }

    @FXML
    private void handleRefresh() {
        if (isGettingDocument) return;
        documentBuffer.clear();
        versionsBuffer.clear();
        isGettingDocument = true;
        sendCommand("GETD");
    }

    private synchronized void sendCommand(String cmd) {
        if (out == null) return;
        out.println(cmd);
        out.flush();
    }

    public void close() {
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException e) { System.err.println("Error closing socket: " + e.getMessage()); }
    }
}
