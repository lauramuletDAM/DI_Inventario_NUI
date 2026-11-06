package tecnodam.inventario;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;


public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        Parent root = FXMLLoader.load(getClass().getResource("/vistas/portada.fxml"));
        stage.setTitle("Inventarios con Rosal IA");
        stage.setScene(new Scene(root));
        stage.show();
    }
}
