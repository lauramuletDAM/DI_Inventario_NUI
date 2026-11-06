# Documentación técnica del NUI smoke test

Un documento de arquitectura y uno por app. Cada uno incluye flujo en ASCII, pasos de `--check` con un resultado real de ejemplo, APIs y
librerías, límites y observaciones. Los datos de los modelos ONNX y las trazas de las máquinas de estado se obtuvieron inspeccionando los
ficheros y ejecutando la lógica real; los ejemplos de resultado proceden de ejecuciones reales de `--check`.

| Doc | App | Clases principales | Tecnología clave |
|---|---|---|---|
| [00](00-arquitectura.md) | Arquitectura general | `Main`, `AppId`, `NuiApp`, `LiveApp`, `AppRunner`, `Report` | JavaFX, procesos, hilos |
| [01](01-entorno.md) | Entorno | `EntornoApp` | `java.net.http`, JavaCV, SCENE3D |
| [02](02-voz.md) | Voz | `VozApp`, `CommandEngine`, `Tts`, `GroqClient` | Vosk, SAPI (PowerShell), API de Groq |
| [03](03-marcadores-ra.md) | Marcadores y RA | `QrMode`, `ArucoMode`, `FaceMode`, `CubeMode` | ZXing, OpenCV (ArUco, Haar), JavaFX 3D |
| [04](04-manos-cuerpo.md) | Manos y cuerpo | `HandModel`, `DwellEngine`, `SwipeEngine`, `PoseMode`, `PickingMode` | ONNX Runtime, MediaPipe, `Robot` |
| [05](05-clasificacion.md) | Clasificación | `ClasificacionApp` | DJL + PyTorch |

Los fuentes (`src/main/java/es/dam/nui/smoketest/*.java`) llevan Javadoc con los mismos diagramas.
