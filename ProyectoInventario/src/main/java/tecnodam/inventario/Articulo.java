package tecnodam.inventario;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class Articulo {
    private String codigo;
    private String nombre;
    private String categoria;
    private int stock;

    // Variable estática para recordar qué fichero se leyó finalmente
    private static String ficheroLeido = "";

    public Articulo(String codigo, String nombre, String categoria, int stock) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.categoria = categoria;
        this.stock = stock;
    }

    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getCategoria() { return categoria; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public static String getFicheroLeido() {
        return ficheroLeido;
    }

    @Override
    public String toString() {
        return "[" + codigo + "] " + nombre + " (" + categoria + ") - Stock: " + stock;
    }

    /** Lee los artículos desde el fichero CSV inicial y guarda el nombre del archivo cargado. */
    public static ObservableList<Articulo> listar(String nombreFichero) {
        ObservableList<Articulo> lista = FXCollections.observableArrayList();
        BufferedReader br = null;
        ficheroLeido = "";

        try {
            InputStream is = Articulo.class.getResourceAsStream("/" + nombreFichero);
            if (is == null) {
                is = Articulo.class.getResourceAsStream(nombreFichero);
            }

            if (is != null) {
                br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                ficheroLeido = nombreFichero;
            } else {
                Path path = Path.of(nombreFichero);
                if (!Files.exists(path)) {
                    path = Path.of("data/catalogo-inicial.csv");
                    nombreFichero = "data/catalogo-inicial.csv";
                }
                if (Files.exists(path)) {
                    br = new BufferedReader(new FileReader(path.toFile(), StandardCharsets.UTF_8));
                    ficheroLeido = path.toString();
                }
            }

            if (br == null) {
                System.err.println("No se pudo encontrar el fichero CSV: " + nombreFichero);
                return lista;
            }

            String linea;
            boolean esPrimeraLinea = true;

            while ((linea = br.readLine()) != null) {
                if (esPrimeraLinea) {
                    esPrimeraLinea = false;
                    continue;
                }
                String[] partes = linea.split(";");
                if (partes.length == 4) {
                    lista.add(new Articulo(
                        partes[0].trim(),
                        partes[1].trim(),
                        partes[2].trim(),
                        Integer.parseInt(partes[3].trim())
                    ));
                }
            }
            br.close();
        } catch (IOException | NumberFormatException e) {
            System.err.println("Error al leer el CSV: " + e.getMessage());
        }
        return lista;
    }

    /** Sobrescribe el fichero catalogo.csv con el formato original y el stock actualizado al salir. */
    public static void guardar(ObservableList<Articulo> lista, String rutaArchivo) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(rutaArchivo, StandardCharsets.UTF_8))) {
            bw.write("codigo;nombre;categoria;stock");
            bw.newLine();
            for (Articulo art : lista) {
                bw.write(art.getCodigo() + ";" + art.getNombre() + ";" + art.getCategoria() + ";" + art.getStock());
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error al guardar el archivo CSV: " + e.getMessage());
        }
    }
}