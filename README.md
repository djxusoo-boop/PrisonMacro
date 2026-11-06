# PrisonMacro · 0.3.18

Aplicación Android local de automatización visual en español. Esta es una **versión inicial funcional**, no una implementación completa del documento maestro ni un reemplazo terminado de Macrorify.

## APK

El archivo para instalar es `outputs/PrisonMacro-0.3.18.apk`. Es una compilación de depuración firmada, **sin `testOnly`**. No distribuyas el APK de `build/intermediates`: Android Studio puede volver a marcarlo como solo para pruebas al pulsar Run.

El rechazo inicial de BlueStacks quedó confirmado en su registro como `INSTALL_FAILED_TEST_ONLY`. No era un ZIP corrupto. La APK publicada está firmada y sin `testOnly`; el puente ADB de BlueStacks Air se desconectó durante esta última instalación, así que allí debe instalarse desde el botón APK.

## Implementado

- Editor secuencial y jerárquico: pulsación/trayectoria, espera, repetir grupo, clic en imagen detectada, condición de imagen y condición de color con ramas ENTONCES/SI NO.
- Vista compacta responsiva para horizontal: elimina la cabecera alta, integra Ayuda en las pestañas, reúne los controles del proyecto en una fila y recoge las operaciones de cada tarjeta bajo «Opciones». Se activa automáticamente en pantallas anchas y puede desactivarse.
- Interfaz del editor en español inspirada en un flujo sencillo: «Mis macros» con Editar/Reproducir, tarjetas con Editar/Probar y un botón «Añadir acción» que agrupa Gestos e imágenes, Condiciones, Secuencia y Dispositivo y aplicación.
- Menú «⋮ Más» en cada acción, al estilo de un editor de macros: editar, cambiar imagen, editar clic/coordenadas, probar, añadir dentro/encima/después, duplicar en la misma rama y borrar.
- En cada condición se ven directamente los dos caminos: «＋ Añadir acción en ENTONCES» y «＋ Añadir acción en SI NO», con la explicación de que SI NO vacío continúa.
- Cada tarjeta resume esos caminos con lenguaje legible, y una comprobación previa bloquea «Iniciar» si falta una captura, coordenada, aplicación, tiempo válido o contenido de una repetición.
- Tres modos de orientación para el editor: horizontal fijado (predeterminado), vertical fijado y seguir sistema. La vista vertical usa los controles compactos para conservar más altura útil.
- «Condición después» permite elegir imagen o color. Si se cancela la edición de un bloque recién creado, el borrador se retira y el proyecto queda intacto.
- Edición, activar/desactivar, mover con flechas, copiar al final, quitar, deshacer/rehacer y simulación sin toques.
- Motor real con callbacks de gestos, espera previa, timeout de búsqueda, intervalo tras completar la secuencia y máximo de repeticiones.
- Pausa entre instrucciones y parada de nuevos pasos; un gesto que Android ya aceptó puede terminar.
- El Editor separa «Reproducir ahora» de «Al ver imagen», para que el disparador del logo no se confunda con el clic sobre una captura.
- Acciones fáciles de Android: Inicio, Atrás, Recientes, notificaciones, ajustes rápidos, abrir una app instalada y cerrar la tarjeta activa desde Recientes. Este último gesto no equivale a «Forzar detención».
- «Abrir aplicación / juego» permite buscar una app instalada o escribir su paquete Android manualmente; para Zooba se puede usar `com.wildlife.games.battle.royale.free.zooba` aunque el selector de BlueStacks no la enumere.
- Flujo de arranque recomendado: «Abrir aplicación / juego» → «Esperar hasta ver una imagen» con la captura del lobby elegida en ese paso → «Cerrar aplicación». «Clic en imagen detectada» solo hace clic; no abre la app.
- Captura mediante MediaProjection, servicio visible con notificación y recorte rectangular sobre una captura.
- Detección local de imágenes y colores, en coordenada exacta o en cualquier punto. La condición de color admite tolerancia RGB y exige varios píxeles vecinos para reducir activaciones por ruido.
- Grabador asistido de un contacto: intercepta el gesto, libera temporalmente el toque para reproducirlo en la app destino, guarda trayectoria y espera y vuelve a armarse después de que el `onClick` termine. No es escucha pasiva global.
- Biblioteca de capturas con nombre, renombrado, borrado seguro y elección por condición. El selector mantiene visible la imagen elegida y permite buscar por nombre entre este proyecto y los demás. Cada condición puede importar una imagen de Android, recortarla o pedir una captura nueva que queda vinculada automáticamente.
- Las imágenes antiguas guardadas como «imagen principal» se recuperan automáticamente en la biblioteca al abrir el proyecto; así una acción puede cambiarse a cualquier captura y volver a recortarse. La importación usa el selector completo de Archivos de Android para que también funcione cuando el Photo Picker aparece vacío en BlueStacks.
- Acción directa «Clic en imagen detectada»: cada paso puede elegir su propia captura, mostrar la miniatura en el editor y pulsar en el centro, con desplazamiento relativo o en un punto fijo de pantalla; admite un clic, doble clic y pulsación larga.
- Proyectos JSON, escritura atómica, copia anterior recuperable, exportación/importación con todas las capturas incluidas, registro local de 200 eventos.
- Laboratorio para probar pulsaciones sin tocar juegos, mensajes ni compras.

## Qué se ha probado y qué falta

Consulta [PRUEBAS.md](docs/PRUEBAS.md). La ronda actual pasa 34/34 pruebas instrumentadas en el emulador seguro, incluida Pausa/Seguir, selección de captura, cierre desde Recientes y el ciclo grabar → guardar → reproducir. Esto **no equivale** a haber ejecutado una macro sobre Zooba ni a verificar todo el flujo de MediaProjection y recorte en BlueStacks o en un teléfono físico.

Pendiente: OCR, variables, funciones con parámetros, condiciones booleanas compuestas, multitáctil, edición de cada punto de una trayectoria, escalado entre resoluciones, búsqueda en región arbitraria, arrastrar para reordenar y depuración con breakpoints. No hay botones ficticios para estas funciones.

## Compilar

Requiere Android SDK 35, build-tools 36.0.0 y JDK 21. En este Mac hay un JDK 21 aislado dentro de `.tools/`; no se ha sustituido el Java del sistema. La combinación de lint de AGP 8.7.3 y el JDK 25 de Android Studio falla, por lo que el script utiliza JDK 21.

```sh
bash scripts/build-apk.sh
```

El script ejecuta pruebas unitarias, lint, compilación, verificación criptográfica y comprobación de `testOnly`, y solo publica si todo pasa.

```sh
JAVA_HOME="$PWD/.tools/jdk-21.0.12.1+1/Contents/Home" ./gradlew :app:assembleDebugAndroidTest
```

La ejecución local de macros no necesita red. Capturas, proyectos y registros permanecen en el dispositivo salvo que actives la pestaña **Nube**. La nube usa una cuenta independiente por correo, un enlace de acceso de un solo uso y Realtime Database con presencia privada de dispositivos y proyectos. En el proyecto `prison-ed653` ya están habilitados Email Link y las reglas privadas de `database.rules.json`; si se crea otro proyecto Firebase, hay que repetir esa configuración.

## Compañero para iPhone

Hay un proyecto SwiftUI en `ios/PrisonMacro/PrisonMacro.xcodeproj`. El iPhone permite importar una captura del carrete o de Archivos, recortarla, nombrarla, añadir pasos y condiciones con la miniatura visible, exportar el JSON y sincronizarlo por la misma cuenta de correo. El PNG queda incluido en el JSON, por lo que Android puede descargarlo y usarlo como plantilla. La compilación actual para dispositivo queda en `ios/build/ConnectedDeviceDerivedData/Build/Products/Debug-iphoneos/PrisonMacro.app`.

El iPhone no puede enviar toques a BlueStacks por las restricciones de iOS: edita y sincroniza; la ejecución y los permisos de captura/accesibilidad siguen estando en Android. El compañero iPhone 0.3.18 incluye el logo, varios proyectos locales, condiciones/repeticiones anidadas, selector visible de la captura propia de cada paso, avisos previos y recortes que conservan X/Y para el modo exacto. Se compiló, instaló y abrió en el iPhone 13 conectado con la firma de tu Team Apple. Artefacto comprobado: `ios/build/ConnectedDeviceDerivedData/Build/Products/Debug-iphoneos/PrisonMacro.app`.

## Documentación

- [Manual](docs/MANUAL.md)
- [Pruebas y errores corregidos](docs/PRUEBAS.md)
- [Arquitectura y fuentes](docs/ARQUITECTURA.md)
- [Continuación del desarrollo](docs/CONTINUAR.md)
