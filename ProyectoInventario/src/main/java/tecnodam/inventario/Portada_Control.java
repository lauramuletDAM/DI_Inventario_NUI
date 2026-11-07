package tecnodam.inventario;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class Portada_Control {

	@FXML
	private Label titulo, catalogo, gestion_camara, gestion_voz, entrada_manual, producto, cantidad, error;

	@FXML
	private Button bcamara, bvoz, bconfirmar, bsalir;

	@FXML
	private ListView<Articulo> lvarticulos;

	@FXML
	private ComboBox<String> cbproducto;

	@FXML
	private Spinner<Integer> spcantidad;

	private ObservableList<Articulo> listaArticulos;

	@FXML
	public void initialize() {
		// 1. Cargar los artículos desde el CSV inicial
		listaArticulos = Articulo.listar("data/catalogo-inicial.csv");

		// 2. Mostrar el nombre del fichero leído en la etiqueta 'catalogo'
		if (catalogo != null) {
			catalogo.setText("Catálogo: " + Articulo.getFicheroLeido());
		}

		// 3. Cargar la lista en el ListView central de la interfaz
		lvarticulos.setItems(listaArticulos);

		// 4. Inicializar el ComboBox EXCLUSIVAMENTE con los nombres de los artículos
		// existentes
		cbproducto.getItems().clear();
		for (Articulo art : listaArticulos) {
			cbproducto.getItems().add(art.getNombre());
		}

		// 5. Configurar el Spinner de cantidad (Mínimo 1, Máximo 100, Valor inicial 1)
		SpinnerValueFactory<Integer> valueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 1);
		spcantidad.setValueFactory(valueFactory);

		// Limpiar etiqueta de error inicial
		if (error != null) {
			error.setText("");
		}

		// 6. Vincular acciones de los botones
		// Acción del botón de cámara (bcamara)
		bcamara.setOnAction(event -> CamaraInventarioVentana.abrir(listaArticulos, articuloDetectado -> {
		    // Se ejecuta al hacer la pinza con éxito
		    articuloDetectado.setStock(articuloDetectado.getStock() + 1); // O la cantidad seleccionada
		    lvarticulos.refresh();
		    
		    if (error != null) {
		        error.setStyle("-fx-text-fill: #22c55e;");
		        error.setText("<html><center>Stock añadido correctamente para: \n" + articuloDetectado.getNombre() + " (\nTotal: " + articuloDetectado.getStock() + ")");
		    }
		}));
		bconfirmar.setOnAction(event -> confirmarEntrada());
		bsalir.setOnAction(event -> salirYGuardar());
	}

	/**
	 * Lógica para añadir (solo sumar) unidades al stock del producto seleccionado
	 */
	@FXML
	private void confirmarEntrada() {
		String nombreSeleccionado = cbproducto.getValue();
		Integer cantidadAñadir = spcantidad.getValue();

		// Validaciones
		if (nombreSeleccionado == null || nombreSeleccionado.isBlank()) {
			mostrarMensajeError("Debe seleccionar un producto del catálogo.");
			return;
		}

		if (cantidadAñadir == null || cantidadAñadir <= 0) {
			mostrarMensajeError("La cantidad a añadir debe ser mayor que 0.");
			return;
		}

		// Buscar el artículo seleccionado en la lista
		Articulo articuloEncontrado = null;
		for (Articulo art : listaArticulos) {
			if (art.getNombre().equals(nombreSeleccionado)) {
				articuloEncontrado = art;
				break;
			}
		}

		if (articuloEncontrado != null) {
			// Operación: Solo añadir unidades al stock actual
			int nuevoStock = articuloEncontrado.getStock() + cantidadAñadir;
			articuloEncontrado.setStock(nuevoStock);

			// Refrescar visualmente el ListView central
			lvarticulos.refresh();

			// Mensaje de éxito
			if (error != null) {
				error.setStyle("-fx-text-fill: #22c55e;"); // Color verde éxito
				error.setText("Stock actualizado: +" + cantidadAñadir + " unidades en \n" + articuloEncontrado.getNombre()
						+ "\n (Total: " + nuevoStock + ")");
			}

			// Limpiar selección del combo y resetear spinner
			cbproducto.setValue(null);
			spcantidad.getValueFactory().setValue(1);
		} else {
			mostrarMensajeError("El producto seleccionado no existe en el inventario.");
		}
	}

	/**
	 * Al pulsar el botón salir, se sobrescribe catalogo.csv con el formato original
	 * y las nuevas unidades
	 */
	@FXML
	private void salirYGuardar() {
		Articulo.guardar(listaArticulos, "data/catalogo.csv");

		Stage stage = (Stage) bsalir.getScene().getWindow();
		stage.close();
		Platform.exit();
	}

	private void mostrarMensajeError(String mensaje) {
		if (error != null) {
			error.setStyle("-fx-text-fill: #ef4444;"); // Color rojo error
			error.setText(mensaje);
		}
	}
}