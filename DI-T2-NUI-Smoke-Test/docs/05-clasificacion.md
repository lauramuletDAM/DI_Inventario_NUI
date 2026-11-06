# App Clasificación (`ClasificacionApp`)

**Qué valida:** que **Deep Java Library (DJL)** puede cargar el motor nativo de PyTorch, descargar un modelo preentrenado (ResNet18,
ImageNet) y clasificar una imagen. Es la comprobación más pesada de la primera ejecución, por eso va en su propia app y con el plazo
más largo del launcher (900 s).

**Fuente:** `ClasificacionApp.java` · **Librerías:** `ai.djl:api`, `pytorch-engine`, `pytorch-model-zoo` (0.34.0), `slf4j-simple`.

## Flujo

```
 Criteria.builder()    "un clasificador de imágenes, motor PyTorch, resnet, 18 capas"
    |  loadModel()  -> 1.ª vez: descarga libtorch nativo y los pesos (caché: ~/.djl.ai)
    v
 ZooModel<Image, Classifications>      (se carga UNA vez y se reutiliza)
    |  newPredictor()
    v
 Predictor --predict(Image)--> Classifications --topK(3)--> "clase (xx,x%), clase (xx,x%), ..."
              ^
 URL --ImageFactory.fromUrl--> Image         (foto de un perro del repositorio pytorch/hub)
```

## Paso de `--check`

| Paso | Qué valida | Ejemplo real |
|---|---|---|
| `ResNet18` | Carga el modelo, clasifica la foto de prueba y devuelve las 3 mejores clases | `n02111889 Samoyed, Samoyede (90,9%), n02114548 white wolf, Arctic wolf, ... (2,4%), n02111500 Great Pyrenees (2,2%)` |

Solo se exige que el modelo **devuelva** clases; no se valida que sean las correctas. Con la foto del perro se espera ver razas caninas, pero
eso es una expectativa, no un criterio de aceptación.

## Piezas de DJL

| Clase | Papel |
|---|---|
| `Criteria<I,O>` | Descripción del modelo buscado: aplicación, motor, artefacto, filtros. Genérica en tipos de entrada y salida. |
| `Application.CV.IMAGE_CLASSIFICATION` | Tarea: clasificar imágenes. |
| `ZooModel<I,O>` | Modelo cargado del *model zoo*, con su traductor de preproceso y postproceso. `AutoCloseable`. |
| `Predictor<I,O>` | Ejecuta inferencias. **No es seguro entre hilos**: se crea uno por llamada. `AutoCloseable`. |
| `ImageFactory` / `Image` | Carga imágenes desde URL, fichero o `BufferedImage`. |
| `Classifications` | Lista de pares clase-probabilidad; `topK(n)` devuelve las `n` mejores. |

```
 api (interfaces) ----------------> Criteria, Predictor, ...
 pytorch-engine (runtime) --------> el motor; la primera vez baja libtorch para tu plataforma
 pytorch-model-zoo ---------------> catálogo con "resnet" (variantes por capas / dataset)
 slf4j-simple (runtime) ----------> salida de log por consola
```

## Ventana

Un botón **Clasificar la foto de prueba**, una línea de estado (avisa de que la primera carga puede tardar varios minutos) y un área de texto.

## Problemas frecuentes

| Síntoma | Solución |
|---|---|
| `ModelNotFoundException` | El filtro `layers=18` no existe en esa versión de DJL: ajustar la constante `LAYERS` |
| Error al cargar `torch.dll` / `c10.dll` | Instalar *Microsoft Visual C++ Redistributable x64* |
| Muy lento la 1.ª vez | Normal: descarga de libtorch y de pesos; después usa la caché |

## Observaciones

* El modelo se carga una vez y se reutiliza si se pulsa el botón varias veces; el predictor se crea por llamada.
* La imagen de prueba se descarga en cada ejecución (hace falta Internet siempre).
* Las probabilidades se muestran con el formato del idioma del sistema (coma decimal en español).
