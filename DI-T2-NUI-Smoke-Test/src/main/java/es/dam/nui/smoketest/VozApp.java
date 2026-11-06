package es.dam.nui.smoketest;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * App "Voz": reconocimiento de voz offline con Vosk (motor Kaldi, el audio no sale del equipo).
 * Une las antiguas pruebas 4 (dictado) y 11 (comandos): comparten el modelo, la descarga y la
 * captura de micro, y cambia solo el reconocedor y lo que se hace con el texto.
 *
 * <pre>{@code
 *   ensureModel() --(1a vez: descarga ~40 MB y descomprime en ./models)--> Model (una sola carga)
 *
 *   Microfono --TargetDataLine.read--> PCM 16 kHz / 16 bit / mono / little-endian  (bloques de 128 ms)
 *                      |
 *                      v   listen(recognizer, segundos, handler)        <- bucle comun
 *            Recognizer.acceptWaveForm(buf, n)  --true = fin de frase--> getResult() JSON {"text" : "..."}
 *                      |                                                       |
 *        +-------------+--------------+                                        | regex
 *        |                            |                                        v
 *   DICTADO                      COMANDOS                                   texto
 *   Recognizer(model, 16000)     Recognizer(model, 16000, GRAMATICA)         |
 *   acumula las frases           CommandEngine.handle(texto, ahora) <--------+
 *   texto libre                  CommandView.render(...) -> ImageView
 *
 *   --check (sin persona):  Comandos  autotest de la maquina de estados (CommandEngine)
 *                           Modelo    descarga si falta + carga + crea ambos reconocedores
 *                           Micro     abre la linea y lee un bloque de audio
 * }</pre>
 *
 * <p>Gramatica cerrada: {@code new Recognizer(model, rate, GRAMMAR)} limita el vocabulario a una
 * lista JSON de frases; {@code [unk]} recoge todo lo demas. Reduce mucho los errores a costa de
 * no entender nada fuera de la lista.
 */
public class VozApp extends NuiApp {

    private static final String MODEL_NAME = "vosk-model-small-es-0.42";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    private static final Path MODELS_DIR = Path.of("models");
    private static final int HTTP_OK = 200;

    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int BUFFER_BYTES = 4096;      // 2048 muestras = 128 ms de audio
    private static final long MS_PER_SECOND = 1000;
    private static final int DICTATION_SECONDS = 5;
    private static final int COMMANDS_SECONDS = 20;

    private static final String GRAMMAR = "[\"oye asistente\", \"modo oscuro\", \"modo claro\", \"guardar documento\","
            + " \"pon fondo rojo\", \"pon fondo verde\", \"pon fondo azul\", \"[unk]\"]";
    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"");

    private static final int WINDOW_WIDTH = 620;
    private static final int WINDOW_HEIGHT = 820;
    private static final int OUTPUT_ROWS = 6;
    private static final int BYTES_PER_KB = 1024;
    private static final int TEXT_ROWS = 3;
    private static final int RATE_MIN = -5;                        // velocidad de la voz (SAPI admite -10..10)
    private static final int RATE_MAX = 5;
    private static final int RATE_DEFAULT = 0;
    private static final int RATE_TICK = 5;
    private static final String DEFAULT_TEXT = "Hola, esto es una prueba de sintesis de voz.";
    private static final String CHECK_TEXT = "Prueba de voz";
    private static final int GAP = 10;

    /** Recibe cada bloque de audio ya procesado: si cerro una frase y el instante actual. */
    private interface ChunkHandler {
        void onChunk(boolean phraseEnd, long now);
    }

    private Model model;                 // se carga una vez, cuando hace falta
    private Label statusLabel;           // null en modo --check (no hay ventana)
    private ImageView commandView;       // idem
    private Process speaking;            // voz en curso (para poder pararla)
    private volatile String typedKey = "";   // clave escrita en la ventana; se copia desde el hilo de JavaFX
    private ComboBox<String> voices;
    private Slider rate;
    private TextArea input;
    private PasswordField keyField;

    @Override
    protected Map<String, Callable<String>> steps() {
        Map<String, Callable<String>> steps = new LinkedHashMap<>();
        steps.put("Comandos", CommandEngine::selfTest);
        steps.put("Modelo", this::cargarModelo);
        steps.put("Micro", this::probarMicro);
        steps.put("Voces", this::probarVoces);
        steps.put("Sintesis", this::probarSintesis);
        steps.put("IA", this::probarIa);
        return steps;
    }

    // ------------------------------------------------------------------ comprobaciones

    private String cargarModelo() throws Exception {
        Model loaded = model();
        try (var _ = new Recognizer(loaded, SAMPLE_RATE);
             var _ = new Recognizer(loaded, SAMPLE_RATE, GRAMMAR)) {
            return MODEL_NAME + " cargado (dictado libre y gramatica de comandos)";
        }
    }

    private String probarMicro() throws Exception {
        TargetDataLine line = openLine();
        try {
            if (line.read(new byte[BUFFER_BYTES], 0, BUFFER_BYTES) <= 0) {
                throw new IllegalStateException("el microfono no entrego audio");
            }
            return "captura " + (int) SAMPLE_RATE + " Hz, " + SAMPLE_BITS + " bit, mono";
        } finally {
            line.stop();
            line.close();
        }
    }

    // ------------------------------------------------------------------ demos interactivas

    /** Dictado libre: muestra el texto reconocido durante {@value #DICTATION_SECONDS} s. */
    private String dictar() throws Exception {
        String result = escuchar();
        return result.isBlank() ? "microfono OK, pero no se reconocio texto (habla mas alto/cerca)"
                                : "reconocido: \"" + result + "\"";
    }

    /** Escucha {@value #DICTATION_SECONDS} s y devuelve el texto de TODAS las frases dichas (vacio si no hubo habla). */
    private String escuchar() throws Exception {
        StringBuilder text = new StringBuilder();
        try (Recognizer recognizer = new Recognizer(model(), SAMPLE_RATE)) {
            status("HABLA AHORA durante " + DICTATION_SECONDS + " segundos...");
            listen(recognizer, DICTATION_SECONDS, (phraseEnd, now) -> {
                if (phraseEnd) {
                    text.append(' ').append(extractText(recognizer.getResult()));
                }
            });
            text.append(' ').append(extractText(recognizer.getFinalResult()));
        }
        return text.toString().trim();
    }

    /** Comandos con wake word durante {@value #COMMANDS_SECONDS} s; pinta el estado del motor en directo. */
    private String comandos() throws Exception {
        CommandEngine engine = new CommandEngine();
        try (Recognizer recognizer = new Recognizer(model(), SAMPLE_RATE, GRAMMAR)) {
            status("Di 'oye asistente' y luego un comando. Prueba tambien uno SIN la palabra de activacion: debe ignorarse.");
            listen(recognizer, COMMANDS_SECONDS, (phraseEnd, now) -> {
                engine.tick(now);
                if (phraseEnd) {
                    engine.handle(extractText(recognizer.getResult()), now);
                }
                showCommands(CommandView.render(engine, now));
            });
            engine.handle(extractText(recognizer.getFinalResult()), System.currentTimeMillis());
        }
        return engine.wakeWords + " activaciones, " + engine.executed + " comandos ejecutados, "
                + engine.ignored + " ignorados por estar en reposo"
                + (engine.wakeWords == 0 ? " (no se detecto 'oye asistente'; habla mas cerca/alto)" : "");
    }

    // ------------------------------------------------------------------ salida de voz y lenguaje (TTS + IA)

    private String probarVoces() throws Exception {
        List<String> found = Tts.voices();
        if (found.isEmpty()) {
            throw new IllegalStateException("no hay voces " + Tts.LANGUAGE + " instaladas (Configuracion > Hora e idioma > Voz)");
        }
        return found.size() + " voces: " + String.join(", ", found);
    }

    /** Sintetiza una frase a un WAV temporal (silencioso, sin persona delante) y comprueba que no esta vacio. */
    private String probarSintesis() throws Exception {
        String voice = Tts.voices().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no hay voces " + Tts.LANGUAGE));
        Path dir = Files.createTempDirectory("tts-check");
        Path wav = dir.resolve("voz.wav");
        try {
            Tts.speakToFile(CHECK_TEXT, voice, RATE_DEFAULT, wav);
            return "WAV de " + Files.size(wav) / BYTES_PER_KB + " KB con la voz " + voice;
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(dir);
        }
    }

    /** Sin clave: el servicio es alcanzable (401). Con clave (ventana o variable de entorno): clave valida y modelo disponible. */
    private String probarIa() throws Exception {
        String key = apiKey();
        return key.isEmpty() ? GroqClient.reachable() : GroqClient.checkKey(key);
    }

    private String apiKey() {
        String env = System.getenv(GroqClient.API_KEY_ENV);
        return typedKey.isEmpty() ? (env == null ? "" : env.trim()) : typedKey;
    }

    private void requireKey(String key) {
        if (key.isEmpty()) {
            throw new IllegalStateException("Falta la clave de Groq: escribela en el campo o define la variable " + GroqClient.API_KEY_ENV);
        }
    }

    private String hablar(String text, String voice, int speed) throws Exception {
        if (voice == null) {
            throw new IllegalStateException("Selecciona una voz");
        }
        parar();
        speaking = Tts.speak(text, voice, speed);
        return "Hablando con " + voice + "...";
    }

    private String preguntar(String key, String question) throws Exception {
        requireKey(key);
        return GroqClient.ask(key, question);
    }

    /** Ciclo completo: micro -> Vosk (dictado libre) -> Groq -> voz. La voz no bloquea: se puede parar. */
    private String asistente(String key, String voice, int speed) throws Exception {
        requireKey(key);
        if (voice == null) {
            throw new IllegalStateException("Selecciona una voz");
        }
        String heard = escuchar();
        if (heard.isBlank()) {
            return "no se reconocio texto (habla mas alto/cerca)";
        }
        status("Preguntando a la IA...");
        String answer = GroqClient.ask(key, heard);
        parar();
        speaking = Tts.speak(answer, voice, speed);
        return "Tu: " + heard + "\nIA: " + answer;
    }

    private void parar() {
        if (speaking != null && speaking.isAlive()) {
            speaking.destroy();
        }
    }

    // ------------------------------------------------------------------ audio y modelo

    private static TargetDataLine openLine() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);
        TargetDataLine line = AudioSystem.getTargetDataLine(format);
        line.open(format);
        line.start();
        return line;
    }

    /** Bucle comun: lee el micro durante {@code seconds}, alimenta al reconocedor y avisa al handler tras cada bloque. */
    private static void listen(Recognizer recognizer, int seconds, ChunkHandler handler) throws Exception {
        TargetDataLine line = openLine();
        try {
            byte[] buffer = new byte[BUFFER_BYTES];
            long end = System.currentTimeMillis() + seconds * MS_PER_SECOND;
            while (System.currentTimeMillis() < end) {
                int n = line.read(buffer, 0, buffer.length);
                boolean phraseEnd = n > 0 && recognizer.acceptWaveForm(buffer, n);
                handler.onChunk(phraseEnd, System.currentTimeMillis());
            }
        } finally {
            line.stop();
            line.close();
        }
    }

    private static String extractText(String json) {
        Matcher m = TEXT_FIELD.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private synchronized Model model() throws Exception {
        if (model == null) {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(ensureModel().toString());
        }
        return model;
    }

    /**
     * Devuelve la carpeta del modelo, descargandolo y descomprimiendolo si falta. Cada entrada del
     * ZIP se normaliza y debe seguir dentro de ./models (proteccion frente a "zip slip").
     */
    private Path ensureModel() throws Exception {
        Path dir = MODELS_DIR.resolve(MODEL_NAME);
        if (Files.isDirectory(dir)) {
            return dir;
        }
        status("Descargando modelo de voz (~40 MB, solo la primera vez)...");
        Files.createDirectories(MODELS_DIR);
        Path zip = MODELS_DIR.resolve(MODEL_NAME + ".zip");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(MODEL_URL)).build(),
                HttpResponse.BodyHandlers.ofFile(zip));
        if (response.statusCode() != HTTP_OK) {
            throw new IllegalStateException("Descarga fallida: HTTP " + response.statusCode());
        }
        status("Descomprimiendo modelo...");
        Path root = MODELS_DIR.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IllegalStateException("Entrada ZIP no valida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.delete(zip);
        return dir;
    }

    @Override
    public void stop() throws Exception {
        parar();
        if (model != null) {
            model.close();
        }
    }

    // ------------------------------------------------------------------ interfaz

    /** Cambia el texto de estado desde cualquier hilo (no hace nada en modo --check). */
    private void status(String text) {
        Platform.runLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(text);
            }
        });
    }

    private void showCommands(BufferedImage image) {
        Platform.runLater(() -> {
            if (commandView != null) {
                commandView.setImage(SwingFXUtils.toFXImage(image, null));
            }
        });
    }

    @Override
    protected void buildUi(Stage stage) {
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        TextArea out = new TextArea();
        out.setEditable(false);
        out.setWrapText(true);
        out.setPrefRowCount(OUTPUT_ROWS);
        commandView = new ImageView();
        commandView.setFitWidth(CommandView.WIDTH);
        commandView.setPreserveRatio(true);

        input = new TextArea(DEFAULT_TEXT);
        input.setWrapText(true);
        input.setPrefRowCount(TEXT_ROWS);
        voices = new ComboBox<>();
        rate = new Slider(RATE_MIN, RATE_MAX, RATE_DEFAULT);
        rate.setShowTickLabels(true);
        rate.setMajorTickUnit(RATE_TICK);
        keyField = new PasswordField();
        keyField.setPromptText("Clave de Groq (o variable " + GroqClient.API_KEY_ENV + ")");

        Button dictate = new Button("Dictado (" + DICTATION_SECONDS + " s)");
        Button commands = new Button("Comandos (" + COMMANDS_SECONDS + " s)");
        Button speak = new Button("Hablar");
        Button stopSpeaking = new Button("Parar");
        Button ask = new Button("Preguntar (texto)");
        Button assistant = new Button("Asistente (voz)");
        Button verify = new Button("Comprobar");
        List<Button> buttons = List.of(dictate, commands, speak, ask, assistant, verify);   // Parar no se desactiva
        dictate.setOnAction(e -> runInBackground(buttons, out, this::dictar));
        commands.setOnAction(e -> runInBackground(buttons, out, this::comandos));
        stopSpeaking.setOnAction(e -> parar());
        // Los valores de la ventana se leen AQUI (hilo de JavaFX) y se pasan ya copiados a la tarea.
        speak.setOnAction(e -> {
            String text = input.getText();
            String voice = voices.getValue();
            int speed = (int) rate.getValue();
            runInBackground(buttons, out, () -> hablar(text, voice, speed));
        });
        ask.setOnAction(e -> {
            String question = input.getText().trim();
            typedKey = keyField.getText().trim();
            String key = apiKey();
            runInBackground(buttons, out, () -> preguntar(key, question));
        });
        assistant.setOnAction(e -> {
            typedKey = keyField.getText().trim();
            String key = apiKey();
            String voice = voices.getValue();
            int speed = (int) rate.getValue();
            runInBackground(buttons, out, () -> asistente(key, voice, speed));
        });
        verify.setOnAction(e -> {
            typedKey = keyField.getText().trim();
            runInBackground(buttons, out, () -> check().text().replace(SEPARATOR, "\n"));
        });

        VBox root = new VBox(GAP,
                new HBox(GAP, dictate, commands, verify),
                new Label("Texto (para Hablar y Preguntar):"), input,
                new HBox(GAP, voices, new Label("Velocidad:"), rate, speak, stopSpeaking),
                keyField, new HBox(GAP, ask, assistant),
                statusLabel, out, commandView);
        root.setPadding(new Insets(GAP));
        stage.setTitle("Voz");
        stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
        stage.show();
        cargarVoces();
    }

    /** Rellena la lista de voces en segundo plano; si no hay (o no es Windows) lo dice en la linea de estado. */
    private void cargarVoces() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return Tts.voices();
            } catch (Throwable e) {
                Platform.runLater(() -> statusLabel.setText("Sin sintesis de voz: " + e.getMessage()));
                return List.<String>of();
            }
        }).thenAccept(found -> Platform.runLater(() -> {
            if (!found.isEmpty()) {
                voices.getItems().setAll(found);
                voices.getSelectionModel().selectFirst();
            }
        }));
    }

    /** Ejecuta la tarea fuera del hilo de JavaFX con los botones desactivados y muestra su resultado. */
    private void runInBackground(List<Button> buttons, TextArea out, Callable<String> task) {
        buttons.forEach(b -> b.setDisable(true));
        out.setText("Trabajando...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Throwable e) {   // Throwable: tambien errores nativos (UnsatisfiedLinkError)
                return "FALLO - " + e;
            }
        }).thenAccept(text -> Platform.runLater(() -> {
            out.setText(text);
            statusLabel.setText("");
            buttons.forEach(b -> b.setDisable(false));
        }));
    }
}
