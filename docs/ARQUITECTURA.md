# Arquitectura y decisiones

Un módulo Gradle `app`, con paquetes separados para editor, core, engine, vision, capture, gestures y projects. No se han creado módulos vacíos que aparenten capacidades inexistentes.

- `core/Models.kt`: instrucciones inmutables, árbol acotado, validación y operaciones de edición.
- `core/Studio.kt`: estado observable de UI, historial y guardado.
- `projects/ProjectStore.kt`: JSON versión 2, AtomicFile y copia previa válida.
- `engine/Engine.kt`: continuaciones cancelables con generación de sesión, límites, pausa y cola secuencial; detector fuera del hilo principal.
- `vision/Matcher.kt`: algoritmo RGB puro probado en JVM. `BitmapDetector.kt`: reducción y refinamiento a tamaño original. No usa OpenCV en esta fase para evitar una dependencia nativa y mantener un algoritmo auditable; no ofrece invariancia de escala/rotación ni puntuaciones probabilísticas.
- `capture/CaptureService.kt`: MediaProjection con foreground service y callback de cierre; conserva el último frame y muestrea aproximadamente 5 Hz sin descartar el último cambio de una pantalla estática.
- `gestures/MacroAccessibilityService.kt`: panel y grabador asistido mediante superposición de accesibilidad. Se oculta la superficie que intercepta el toque antes de reproducirlo; el siguiente gesto se admite al llegar el callback.
- `editor/CropActivity.kt`: mapea el rectángulo visible a píxeles de la captura, respetando el escalado y las bandas de la vista.

## Seguridad y límites

Sin root, servicios de red, ejecución de scripts, evasión antitrampas ni lectura del árbol de ventanas de otras apps. La captura se autoriza por la interfaz oficial de Android. El grabador es de un contacto y no lee teclado. Las pruebas de gestos habilitan y restauran accesibilidad únicamente en el emulador de laboratorio `sdk_gphone`, nunca en un teléfono o BlueStacks personal.

Pendiente de optimización: algunos guardados pequeños y la importación/exportación todavía hacen E/S en el hilo principal; conviene migrarlos a un repositorio asíncrono antes de admitir proyectos grandes. El detector puede dar falsos positivos en elementos muy parecidos; hay umbral, prueba manual, rechazo de uniformidad y límites de repetición. Ninguna puntuación garantiza identidad semántica.

## Investigación

Consultadas el 21 de octubre de 2026:

- [Abstract Mode oficial](https://www.kok-emm.com/docs/abstract): acciones, condiciones y agrupación, como referencia conceptual, sin copiar código o recursos.
- [Detección de Macrorify](https://www.kok-emm.com/docs/image-detection/basic): plantilla, región, puntuación y timeout. La documentación consultada no certifica por sí sola cada detalle de la versión 1.8.
- [MediaProjection](https://developer.android.com/media/grow/media-projection): autorización, servicio en primer plano, callback y ciclo de vida.
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService): `dispatchGesture`, callbacks y cancelación de gestos concurrentes.
- [APK para distribución](https://developer.android.google.cn/build/build-for-release?hl=en): Run puede introducir `testOnly`; hay que compilar un APK instalable.
- [Adoptium](https://adoptium.net/installation/ci-scripts/): JDK 21 aislado y suma SHA-256 contrastada con su API oficial.

La guía de ADB de BlueStacks 5 localizada corresponde a Windows; no se presenta como una ruta de ajustes confirmada para BlueStacks Air. En este Mac se verificó el ajuste real `bst.enable_adb_access="0"` y el fallo de instalación en el registro de Air.
