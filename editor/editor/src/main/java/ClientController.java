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

    private static final boolean USE_PUSH = false;
    private String serverHost = "localhost";
    private int serverPort = 5000;

    private Socket socket;
    private PrintWriter out;
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
            catch (NumberFormatException ex) {
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

        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            deleteLineMenuItem.setDisable(newV == null);
            if (newV != null) textField.setText(newV);
        });
    }

    private void serverListener() {
        try {
            String line;
            while ((line = in.readLine()) != null) {

                if (line.startsWith("LINE ")) {
                    if (isGettingDocument) {
                        String[] parts = line.split(" ", 4);
                        if (parts.length >= 4) {
                            int    idx  = Integer.parseInt(parts[1]) - 1;
                            int    ver  = Integer.parseInt(parts[2]);
                            String text = parts[3];
                            while (documentBuffer.size() <= idx) documentBuffer.add("");
                            while (versionsBuffer.size() <= idx) versionsBuffer.add(1);
                            documentBuffer.set(idx, text);
                            versionsBuffer.set(idx, ver);
                        }
                    }

                } else if (line.equals("DONE")) {
                    if (isGettingDocument) {
                        // Snapshot buffer rồi clear ngay trên thread này
                        final List<String>  docSnap = new ArrayList<>(documentBuffer);
                        final List<Integer> verSnap = new ArrayList<>(versionsBuffer);
                        documentBuffer.clear();
                        versionsBuffer.clear();
                        isGettingDocument = false;  

                        Platform.runLater(() -> {
                            localDocument.clear();
                            localDocument.addAll(docSnap);
                            localVersions.clear();
                            localVersions.addAll(verSnap);
                            listView.getItems().setAll(localDocument);
                        });
                    }

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
        int    selectedIndex = listView.getSelectionModel().getSelectedIndex();
        String text          = textField.getText();
        if (text == null || text.isEmpty()) text = "(New Line)";

        int pos    = (selectedIndex == -1) ? listView.getItems().size() + 1 : selectedIndex + 2;
        int insIdx = pos - 1;   // 0-based

        localDocument.add(insIdx, text);
        localVersions.add(insIdx, 1);
        listView.getItems().add(insIdx, text);

        sendCommand(String.format("ADDL %d 1 %s", pos, text));
    }

    @FXML
    private void handleDeleteLine() {
        int selectedIndex = listView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) return;

        if (selectedIndex < localDocument.size()) localDocument.remove(selectedIndex);
        if (selectedIndex < localVersions.size()) localVersions.remove(selectedIndex);
        listView.getItems().remove(selectedIndex);

        sendCommand(String.format("RMVL %d", selectedIndex + 1));
    }

    @FXML
    private void handleTextFieldUpdate() {
        int selectedIndex = listView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) return;

        String text   = textField.getText();
        if (text == null) text = "";
        int oldVer    = (selectedIndex < localVersions.size()) ? localVersions.get(selectedIndex) : 1;

        // ── Optimistic update ──
        if (selectedIndex < localDocument.size()) localDocument.set(selectedIndex, text);
        listView.getItems().set(selectedIndex, text);

        // ── Gửi lên server ──
        sendCommand(String.format("MDFL %d %d %s", selectedIndex + 1, oldVer, text));
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
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Error closing socket: " + e.getMessage());
        }
    }
}