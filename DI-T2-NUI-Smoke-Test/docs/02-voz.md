# App Voz (`VozApp`)

La voz como interfaz tiene tres piezas, y esta app las reúne: **entrada** (reconocimiento con Vosk), **salida** (síntesis de voz) y
**lenguaje** (una IA que responde). Cada una tiene sus botones y todas comparten ventana, modelo y micrófono.

**Fuentes:** `VozApp`, `CommandEngine`, `CommandView`, `Tts`, `GroqClient` · **Librerías:** Vosk 0.3.45, `javax.sound.sampled`,
`java.net.http`, Jackson 2.17.2, PowerShell (SAPI).

## Flujos

```
 Primera vez:  URL --HttpClient--> models/vosk-model-small-es-0.42.zip --descomprime (anti zip-slip)--> carpeta del modelo

 Microfono --TargetDataLine.read--> PCM 16 kHz / 16 bit / mono / little-endian   (bloques de 4096 bytes = 128 ms)
                    |
                    v     listen(recognizer, segundos, handler)        <- bucle común a Dictado, Comandos y Asistente
         Recognizer.acceptWaveForm(buf, n)  --true = fin de frase--> getResult() JSON {"text" : "..."}  --regex--> texto
                    |
        +-----------+------------------------+
   DICTADO                              COMANDOS
   Recognizer(model, 16000)             Recognizer(model, 16000, GRAMATICA)
   acumula el texto de todas las frases CommandEngine.handle(texto, ahora) --> CommandView.render --> imagen

 ASISTENTE (ciclo completo):   micro -> Vosk (dictado libre 5 s) -> GroqClient.ask -> respuesta -> Tts.speak -> altavoz
 HABLAR:                       texto de la ventana -> Tts.speak (voz y velocidad elegidas)
 PREGUNTAR:                    texto de la ventana -> GroqClient.ask -> respuesta en pantalla
```

## Ventana

| Control | Qué hace |
|---|---|
| `Dictado (5 s)` | Escucha 5 s y muestra el texto reconocido (de **todas** las frases dichas) |
| `Comandos (20 s)` | Escucha 20 s con gramática cerrada y pinta el estado de la máquina de comandos |
| `Hablar` / `Parar` | Sintetiza el texto del área de texto por el altavoz con la voz y velocidad elegidas; `Parar` corta la voz |
| `Preguntar (texto)` | Envía el texto a Groq y muestra la respuesta |
| `Asistente (voz)` | Dicta 5 s, pregunta a Groq y **habla** la respuesta |
| Campo de clave | Clave de Groq; si está vacío se usa la variable de entorno `GROQ_API_KEY` |
| `Comprobar` | Ejecuta el `--check` en la propia ventana |

## Pasos de `--check`

| Paso | Qué valida | Ejemplo real |
|---|---|---|
| `Comandos` | Autotest de la máquina de estados (sin micrófono) | `1 ejecutado, 3 ignorados, 2 activaciones` |
| `Modelo` | Descarga si falta, carga el modelo y crea los dos reconocedores (libre y con gramática) | `vosk-model-small-es-0.42 cargado (...)` |
| `Micro` | Abre la línea de audio y lee un bloque | `captura 16000 Hz, 16 bit, mono` |
| `Voces` | Hay al menos una voz es-ES instalada | `1 voces: Microsoft Helena Desktop` |
| `Sintesis` | Habla una frase a un WAV temporal, comprueba que no está vacío y lo borra (silencioso) | `WAV de 74 KB con la voz Microsoft Helena Desktop` |
| `IA` | Sin clave: el servicio responde 401 (alcanzable). Con clave: clave válida y modelo disponible | `servicio alcanzable (HTTP 401 sin clave, como se espera)` |

## Máquina de comandos (`CommandEngine`)

Mitiga el "toque de Midas": sin la palabra de activación los comandos se ignoran, y tras activarla hay una ventana de 6 s.

```
                      "oye asistente"
     +---------+ ---------------------------> +-------------+
     | REPOSO  |                              | ESCUCHANDO  |  ventana de 6 s
     +---------+ <--- comando ejecutado ----- +-------------+
       |   ^     <--- tiempo agotado (tick) ---     |
       |   +-- cada comando exige nueva activación
       +-- comando sin activar: IGNORADO

   Frase                          Intención   Entidad
   "modo oscuro" / "modo claro"   TEMA        oscuro | claro
   "pon fondo rojo|verde|azul"    FONDO       rojo | verde | azul
   "guardar documento"            GUARDAR     (ninguna)
```

Traza del autotest (con el código real):

```
 t(ms)   entrada              estado                        resultado
 0       "pon fondo rojo"     REPOSO                        IGNORADO              (ignored=1)
 100     "oye asistente"      REPOSO -> ESCUCHANDO          "Te escucho..."       (wakeWords=1, límite 6100)
 200     "pon fondo rojo"     ESCUCHANDO -> REPOSO          EJECUTADO [FONDO:rojo](executed=1)
 300     "modo oscuro"        REPOSO                        IGNORADO              (ignored=2)
 400     "oye asistente"      REPOSO -> ESCUCHANDO          "Te escucho..."       (wakeWords=2, límite 6400)
 6401    "guardar documento"  ESCUCHANDO -> REPOSO (tick)   IGNORADO              (ignored=3)
```

**Intención** = qué hacer (`TEMA`, `FONDO`, `GUARDAR`); **entidad** = el parámetro (`oscuro`, `rojo`...). El motor es **puro**: no usa
audio ni JavaFX y el instante entra como parámetro, por eso se prueba sin micrófono.

**Gramática cerrada de Vosk:** `new Recognizer(model, 16000f, GRAMMAR)` limita el vocabulario a una lista JSON de frases; `[unk]`
recoge todo lo demás. Menos errores a cambio de no entender nada fuera de la lista.

## Síntesis de voz (`Tts`) y lenguaje (`GroqClient`)

```
 Java                             powershell.exe  -EncodedCommand <script en Base64 UTF-16LE>
 voices()          ------------>  SpeechSynthesizer.GetInstalledVoices()  filtradas por es-ES
 speak(texto,...)  ------------>  SelectVoice + Rate + Speak                    -> altavoz (no bloquea, se puede parar)
 speakToFile(...)  ------------>  SetOutputToWaveFile + Speak                   -> fichero WAV (bloquea)
 El texto viaja en Base64 (UTF-8) dentro del script: evita problemas de comillas y tildes.

 GroqClient:   ask(clave, pregunta)  POST /openai/v1/chat/completions   -> choices[0].message.content
               reachable()           GET  /openai/v1/models  sin clave   -> espera HTTP 401
               checkKey(clave)       GET  /openai/v1/models  con clave   -> espera HTTP 200 y el modelo en la lista
```

* `$ErrorActionPreference='Stop'` hace que un error de PowerShell termine el proceso con código distinto de 0 y se detecte.
* `System.Speech` solo ve las voces SAPI5 clásicas (p. ej. "Microsoft Helena Desktop"); es probable que no vea las voces "naturales"
  de Windows 11 (no verificado).
* Modelo de IA: `openai/gpt-oss-20b` en Groq (verificado en su documentación en el momento de escribir esto; puede cambiar).
* **La clave nunca** se escribe en el código, en ficheros ni en logs: llega del campo de contraseña o de `GROQ_API_KEY`.

## APIs de audio y Vosk

| Clase | Papel |
|---|---|
| `AudioFormat(16000f, 16, 1, true, false)` | Frecuencia, bits, canales, con signo, little-endian (lo que espera el modelo; `mfcc.conf` declara 16 kHz) |
| `AudioSystem.getTargetDataLine` / `TargetDataLine` | Entrada de audio por defecto: `open`, `start`, `read`, `stop`, `close` |
| `LibVosk.setLogLevel(WARNINGS)` | Reduce el ruido de Kaldi |
| `Model(String)` | Carga el modelo en memoria nativa; se crea una vez y se cierra en `stop()` |
| `Recognizer` | Decodificador con estado; `acceptWaveForm` alimenta audio, `getResult`/`getFinalResult` devuelven JSON |

El modelo pequeño de español tiene una tasa de error de palabras notable (su README publica entre ~11 % y ~43 % según el corpus):
vale para aula, no para producción.

## Limitaciones y observaciones

* **Palabra de activación y comando en la misma frase** ("oye asistente pon fondo rojo", sin pausa): el motor solo registra la
  activación y **descarta el comando**. Hay que hacer una pausa.
* **Voz ya no es portable:** `Voces` y `Sintesis` fallan fuera de Windows (el error lo dice claramente).
* `Asistente` escucha un tiempo fijo de 5 s; no hay pulsar-para-hablar.
* **Cuidado con el eco:** si una app habla mientras escucha, el micrófono capta su propia voz y puede disparar la activación. El
  `Asistente` es secuencial (escucha y luego habla), así que no lo sufre.
* **Privacidad:** el texto dictado que se envía a la IA sale a un servicio externo, a diferencia de Vosk, que es offline.
* Extraer el JSON de Vosk con una expresión regular basta mientras el texto no contenga comillas.
* No hay selector de archivo para guardar el WAV en la ventana; `Tts.speakToFile` lo soporta y lo usa el `--check`.
* El texto de requisitos de Voz en el launcher ("Microfono, Internet (1a vez)") no menciona el altavoz ni que es solo Windows.
