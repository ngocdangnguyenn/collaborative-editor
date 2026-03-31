import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public class GUIClient extends javafx.application.Application {
    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("clientView.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root);
            primaryStage.setTitle("collaborative editor");
            primaryStage.setScene(scene);
            primaryStage.setOnCloseRequest(e -> {
                ClientController controller = loader.getController();
                if (controller != null) controller.close();
            });
            primaryStage.show();
        } catch (IOException e) {
            System.err.println("Failed to load UI: " + e.getMessage());
            javafx.application.Platform.exit();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
