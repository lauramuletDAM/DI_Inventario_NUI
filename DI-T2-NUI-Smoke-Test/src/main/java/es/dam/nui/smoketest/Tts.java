package es.dam.nui.smoketest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sintesis de voz (TTS) con Windows SAPI a traves de PowerShell: la SALIDA de voz de la interfaz.
 * Sin dependencias de Java: solo {@code powershell.exe} y {@code System.Speech} (.NET), asi que
 * funciona UNICAMENTE en Windows.
 *
 * <pre>{@code
 *   Java                                   powershell.exe  -EncodedCommand <script en Base64 UTF-16LE>
 *   ----                                   ---------------------------------------------------------
 *   voices()           -------------->     SpeechSynthesizer.GetInstalledVoices()  filtradas por es-ES
 *   speak(texto, ...)  -------------->     SelectVoice + Rate + Speak(texto)        -> altavoz (no bloquea)
 *   speakToFile(...)   -------------->     SetOutputToWaveFile + Speak              -> fichero WAV (bloquea)
 *
 *   El texto viaja en Base64 (UTF-8) dentro del script y se decodifica en PowerShell: evita problemas
 *   de comillas y tildes. El script completo va en Base64 UTF-16LE (-EncodedCommand) por lo mismo.
 * }</pre>
 *
 * <p>{@code $ErrorActionPreference='Stop'} hace que cualquier error termine el proceso con codigo
 * distinto de 0, de modo que se pueda detectar. {@code System.Speech} solo ve las voces SAPI5 clasicas
 * instaladas (p. ej. "Microsoft Helena Desktop"); es probable que no vea las voces "naturales" de
 * Windows 11 (no verificado).
 */
final class Tts {

    static final String LANGUAGE = "es-ES";
    private static final String VOICE_PREFIX = "VOZ:";
    private static final String PREAMBLE = "$ProgressPreference='SilentlyContinue';$ErrorActionPreference='Stop';";
    private static final long PROCESS_TIMEOUT_SECONDS = 60;

    private Tts() {
    }

    /** Voces habilitadas para es-ES, por nombre. */
    static List<String> voices() throws Exception {
        String script = "Add-Type -AssemblyName System.Speech;"
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$s.GetInstalledVoices()|Where-Object{$_.Enabled -and $_.VoiceInfo.Culture.Name -eq '" + LANGUAGE + "'}"
                + "|ForEach-Object{'" + VOICE_PREFIX + "'+$_.VoiceInfo.Name};$s.Dispose()";
        List<String> voices = new ArrayList<>();
        for (String line : runAndRead(script).lines().toList()) {
            if (line.startsWith(VOICE_PREFIX)) {
                voices.add(line.substring(VOICE_PREFIX.length()).trim());
            }
        }
        return voices;
    }

    /** Habla por el altavoz. No bloquea: devuelve el proceso para poder pararlo con {@code destroy()}. */
    static Process speak(String text, String voice, int rate) throws IOException {
        return start(speakScript(text, voice, rate, null));
    }

    /** Sintetiza a un fichero WAV y espera a que termine; el fichero debe quedar con contenido. */
    static void speakToFile(String text, String voice, int rate, Path file) throws Exception {
        runAndRead(speakScript(text, voice, rate, file));
        if (!Files.exists(file) || Files.size(file) == 0) {
            throw new IllegalStateException("la sintesis no genero el fichero " + file);
        }
    }

    private static String speakScript(String text, String voice, int rate, Path file) {
        String textBase64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder("Add-Type -AssemblyName System.Speech;");
        s.append("$t=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('").append(textBase64).append("'));")
                .append("$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;")
                .append("$s.SelectVoice('").append(quote(voice)).append("');")
                .append("$s.Rate=").append(rate).append(';');
        if (file != null) {
            s.append("$s.SetOutputToWaveFile('").append(quote(file.toAbsolutePath().toString())).append("');");
        }
        return s.append("$s.Speak($t);$s.Dispose()").toString();
    }

    /** Escapa las comillas simples para una cadena entre comillas simples de PowerShell. */
    private static String quote(String value) {
        return value.replace("'", "''");
    }

    private static String encode(String script) {
        return Base64.getEncoder().encodeToString((PREAMBLE + script).getBytes(StandardCharsets.UTF_16LE));
    }

    private static Process start(String script) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            throw new IllegalStateException("La sintesis de voz usa Windows SAPI: solo esta disponible en Windows");
        }
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encode(script))
                .redirectErrorStream(true).start();
    }

    /** Ejecuta el script, espera (con plazo) y devuelve su salida; lanza excepcion si el codigo de salida no es 0. */
    private static String runAndRead(String script) throws Exception {
        Process process = start(script);
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("PowerShell no respondio en " + PROCESS_TIMEOUT_SECONDS + " s");
        }
        // La salida es pequena (cabe en el buffer de la tuberia), asi que se puede leer despues de esperar.
        String output = new String(process.getInputStream().readAllBytes(), Charset.forName(System.getProperty("native.encoding", "UTF-8")));
        if (process.exitValue() != 0) {
            throw new IllegalStateException("PowerShell termino con codigo " + process.exitValue() + ": " + output.trim());
        }
        return output;
    }
}
