# Pruebas · 26 de octubre de 2026

## Ronda actual · 0.3.18

**APK Android.** `bash scripts/build-apk.sh` terminó correctamente con pruebas JVM, lint, ensamblado, firma APK v2 y comprobación de ausencia de `testOnly`. El entregable actual es `outputs/PrisonMacro-0.3.18.apk`, versión `0.3.18`/código `21`; SHA-256: `3277b817c63fb47b43001207b83d9c3e3a30c1de693020da206a1085dfd00002`.

**Selección de capturas.** En el AVD aislado `Medium_Phone_API_37.0` (`emulator-5560`) se instaló la APK y el paquete de pruebas final. Pasaron por separado `imageLibraryCanAssignExistingCaptureToPendingCondition` y `editorAssignsSavedCaptureToOneCondition`, `OK (1 test)` cada una. Esto verifica el flujo que faltaba: abrir la edición de una acción, ver las capturas guardadas, elegir una distinta y conservarla en esa acción sin cambiar las demás.

**Regresión completa.** `bash scripts/test-device.sh emulator-5560` terminó en `OK (34 tests)` en el AVD aislado `Medium_Phone_API_37.0` (`sdk_gphone64_arm64`). Pasaron los cierres desde Recientes, Pausa/Seguir, selección de captura por acción, grabación de una pulsación y grabación de dos pulsaciones con espera, persistencia y reproducción. No se ejecutaron macros sobre Zooba, anuncios, compras, mensajes ni cuentas.

**iPhone.** `xcodebuild` terminó correctamente para `iphoneos` ARM64 con `CODE_SIGNING_ALLOWED=NO`; se eliminó también un aviso de Swift del editor. La revisión incluye el editor equivalente de proyectos, capturas, miniaturas por acción, recorte, color, esperas, ramas, descripciones, exportación y sincronización. No se instaló una nueva copia física durante esta ronda. iOS no puede controlar los toques de BlueStacks; la ejecución sigue siendo responsabilidad de Android.

**Correcciones funcionales de esta ronda.** «Abrir aplicación / juego» permite escribir el paquete manualmente y ofrece `Usar Zooba`; «Esperar hasta ver una imagen» permite elegir la captura concreta de llegada en cada paso; «Cerrar aplicación» intenta retirar la tarjeta activa desde Recientes y tiene un watchdog que vuelve a Inicio si el lanzador no responde. El botón flotante cambia a `▶ Seguir` cuando la macro está realmente pausada; si no hay una ejecución activa, `Pausa` permanece desactivado.

El Editor separa ahora `Reproducir ahora` de `▶ Al ver imagen`, que espera la captura principal antes de iniciar la secuencia. El laboratorio también recoloca sus objetivos de grabación para que ambos estén dentro del área visible; los tests aislados de una y dos pulsaciones, y después la batería completa, pasan.

## Verificación funcional Android + iPhone · 0.3.17

**Android.** `bash scripts/build-apk.sh` terminó correctamente con pruebas JVM, lint, ensamblado, firma APK v2, ZIP íntegro y ausencia de `testOnly`. Después, `bash scripts/test-device.sh emulator-5560` instaló la app y su paquete de pruebas en el AVD aislado `Medium_Phone_API_37.0`; terminó en `OK (33 tests)` en 57,093 s, sin fallos. El entregable es `outputs/PrisonMacro-0.3.17.apk`, versión `0.3.17`/código `20`; SHA-256: `dbe334bf2f588366c12b38c50c5f0dba9d8e95cb5c3e72e75207b80ed1d0e206`. Se añadió una comprobación previa independiente del guardado: una macro incompleta puede conservarse como borrador, pero no puede pulsarse «Iniciar» hasta corregir la captura, aplicación, espera o grupo vacío indicado. Las condiciones muestran siempre un resumen legible de sus dos caminos, por ejemplo `ENTONCES: abrir aplicación → esperar 10 s → cerrar aplicación` y `SI NO: continuar con el siguiente paso`.

La 0.3.17 todavía no se declara instalada en BlueStacks Air: su puente ADB cerró la conexión durante `install` y el intento mediante `abb_exec`. La versión instalada que se pudo consultar seguía siendo la 0.3.16. No se confunde esta limitación del instalador con una prueba de la APK. La última prueba funcional real en BlueStacks, hecha con la 0.3.16 y sin jugar ninguna partida, detectó la captura `Captura 1` al 100 %, abrió Zooba, esperó 10 segundos y retiró su tarjeta desde Recientes; el registro terminó la secuencia correctamente. Para comprobar la nueva interfaz 0.3.17 falta completar la instalación manual desde el botón APK de BlueStacks.

**iPhone.** `xcodebuild` terminó con código 0 para `iphoneos` ARM64 sin firma y para el simulador iPhone 17 con iOS 27.0. La compilación final se reinstaló y abrió en el simulador; la captura revisada es `outputs/prisonmacro-iphone-0.3.17-final-wait.png`. Se verificó visualmente la portada vertical con Editor, Capturas, Proyectos, Nube y Ayuda, el aviso concreto de que un proyecto vacío necesita una acción y el acceso visible a Importar, Exportar y Añadir paso. También se comprobó que Application Support contiene `projects.json` con el proyecto activo. El artefacto de dispositivo declara versión `0.3.17`/compilación `17` y es Mach-O ARM64.

En iPhone se añadieron varios proyectos locales persistentes, alta, duplicado, apertura y borrado seguro; edición completa de condiciones y repeticiones anidadas; resumen `ENTONCES / SI NO`; comprobación previa equivalente a Android; y conservación de las coordenadas X/Y originales al recortar o volver a recortar. Esta última corrección es necesaria para que `Punto exacto` siga siendo válido al exportar desde iPhone a Android. La compilación y el arranque en simulador están verificados; no se afirma una nueva instalación física en el iPhone ni una sincronización Firebase de extremo a extremo en esta ronda.

## Verificación funcional Android · 0.3.15

`bash scripts/build-apk.sh` terminó correctamente. Pasaron las pruebas unitarias, lint, ensamblado, firma APK v2, ZIP íntegro y comprobación de ausencia de `testOnly`. La APK publicada es `outputs/PrisonMacro-0.3.15.apk`, versión `0.3.15`/código `18`; SHA-256: `e482ad81b251e28d7bfebd1bef0c7ad7836f37cf00fcb2ff358e80d8d90ee309`.

La regresión instrumentada se ejecutó en el AVD aislado `Medium_Phone_API_37.0` (`sdk_gphone64_arm64`, `emulator-5560`) con `bash scripts/test-device.sh emulator-5560`: el runner terminó en `OK (33 tests)`. Se comprobó el primer arranque limpio, la orientación horizontal fijada, que un proyecto vacío no permite Reproducir/Simular y que Añadir acción sigue disponible.

La revisión visual y de flujo se hizo sin abrir Zooba ni ejecutar macros sobre juegos, compras, mensajes o cuentas. Se recorrieron Editor, Imagen, Grabador, Entrenador, Registro, Proyectos y Nube. En laboratorio se comprobó: selector de acciones en español; elección de captura por acción con miniatura y nombre; importación mediante el selector completo de Archivos hasta `Downloads`; recorte; asignación visible «Clic en: Captura 1»; modos Cualquier punto/Punto exacto; espera y repetición; condición de color; grabador asistido; laboratorio seguro; registro; exportación/importación y controles de orientación.

Se corrigió un caso real de migración: proyectos de versiones intermedias podían conservar una plantilla en `template` pero una biblioteca `templates` vacía. Al abrirlos, el selector parecía no tener capturas y daba la impresión de que siempre se usaba la misma. Desde 0.3.15 esa plantilla se recupera con el nombre «Imagen principal» y se puede sustituir o recortar desde cada acción. También se cambió `GetContent` por `OpenDocument`, evitando depender de un Photo Picker vacío en Android/BlueStacks, se corrigió Duplicar para que una acción anidada permanezca dentro de su rama, «+ Condición» queda visible en las tarjetas compactas y los proyectos vacíos ya no muestran un botón Reproducir operativo.

No se afirma una prueba física en BlueStacks Air ni en el iPhone durante esta ronda. La evidencia corresponde al AVD de laboratorio; el APK queda preparado para instalarse manualmente en BlueStacks sin `testOnly`. Mejora futura segura: zoom/pan del recortador para fuentes verticales y filtro de proyectos largos.

## Verificación de marca y empaquetado · 0.3.14

**Android 0.3.14:** `bash scripts/build-apk.sh` terminó correctamente con pruebas unitarias, lint, ensamblado, verificación de firma APK v2, ZIP íntegro y ausencia de `testOnly`. El paquete es `outputs/PrisonMacro-0.3.14.apk`, versión `0.3.14`/código `17`, y contiene el recurso `prison_logo.png`. No se instaló en BlueStacks en esta ronda y no se ejecutó ninguna macro sobre Zooba.

**iPhone 0.3.14:** `xcodebuild` terminó correctamente para `generic/platform=iOS` con firma desactivada. El catálogo `Assets.xcassets` compila dentro de `Assets.car`, `Info.plist` declara `CFBundleIconName = AppIcon` y el artefacto es `ios/build/LogoDerivedData/Build/Products/Debug-iphoneos/PrisonMacro.app`. No se afirma instalación física en el iPhone desde esta sesión.

## Última verificación aprobada · 0.3.12

**PrisonMacro 0.3.12: compilación, lint, firma APK v2, ausencia de `testOnly` y 33 pruebas instrumentadas Android superadas.** Se muestran directamente los dos caminos de cada condición: «＋ Añadir acción en ENTONCES» y «＋ Añadir acción en SI NO», con la ayuda «SI NO vacío = continuar con el siguiente paso». La ronda de laboratorio terminó con `OK (33 tests)` en 53,183 s en el AVD aislado `emulator-5560`. El APK publicado es `outputs/PrisonMacro-0.3.12.apk`; SHA-256: `eb140a47b0683ea48c76bacedf9eec36966d9e62e486afe2d128bae02f18775f`.

La versión se instaló y se volvió a abrir visualmente en BlueStacks Air. El editor conservó el proyecto de 5 pasos, la captura asignada a la primera condición y mostró los dos caminos en la misma tarjeta. No se ejecutó ninguna macro ni se abrió Zooba.

**Compañero iPhone 0.3.12:** `xcodebuild` completó correctamente para `iphonesimulator` universal arm64/x86_64 y para `iphoneos` arm64 con firma desactivada. Se actualizó la versión del proyecto desde 0.1.0 a 0.3.12, se añadió el cambio de captura por condición y la edición de ramas ENTONCES/SI NO, y se regeneraron `outputs/PrisonMacro-iPhone-0.3.12-unsigned.zip` y `outputs/PrisonMacro-iPhone-0.3.12-project.zip`. No se afirma instalación física: requiere seleccionar el Team Apple del usuario en Xcode.

El iPhone 13 real aparece emparejado como `iphone xuso` (modelo `iPhone14,5`). La compilación dirigida a ese dispositivo fue intentada con aprovisionamiento automático, pero Xcode la detuvo antes de compilar porque Developer Mode está desactivado en el teléfono. El script `scripts/install-ios.sh` queda preparado para repetir compilación e instalación después de activarlo.

## Última verificación aprobada · 0.3.11

**PrisonMacro 0.3.11: compilación, lint, firma APK v2, ausencia de `testOnly` y 33 pruebas instrumentadas Android superadas.** Se añadió el menú «⋮ Más» por acción, con edición, cambio de imagen, prueba, inserción dentro/encima/después, duplicado en la misma rama y borrado. La prueba visual en BlueStacks Air confirmó el menú y el selector agrupado en español. También se creó una acción temporal «Detectar imagen y hacer clic», se comprobó que muestra las 3 capturas guardadas, permite elegir «Captura 2», conserva la miniatura y ofrece «Recortar esta imagen»; se canceló sin dejar pasos extra y el proyecto volvió a 5 pasos. No se ejecutó ninguna macro ni se abrió Zooba. La ronda de laboratorio terminó con `OK (33 tests)` en 54,666 s en el AVD aislado `emulator-5560`; el APK publicado es `outputs/PrisonMacro-0.3.11.apk`; SHA-256: `7100c908e148e221aab6c93394715437b8de1577d025437d64b848362e865a37`.

## Última verificación aprobada

**PrisonMacro 0.3.10: compilación, lint, firma APK v2, ausencia de `testOnly` y 32 pruebas instrumentadas Android superadas.** Se hizo visible en cada condición el botón «＋ Añadir acción en ENTONCES»; al pulsarlo, el selector abre «Clic / arrastre en punto» con coordenadas X/Y y «Detectar imagen y hacer clic» para la acción directa. La prueba visual en BlueStacks Air confirmó la instalación sobre la 0.3.9, la apertura del editor y la nueva ruta; se canceló el paso de prueba para no alterar la macro existente. La ronda terminó con `OK (32 tests)` en 52,857 s en el AVD aislado `emulator-5560`. APK: `outputs/PrisonMacro-0.3.10.apk`; SHA-256: `166f2679b0cf11ea68fdd17d052067fc41a19c9dde5852389df8aeb714ffaf0a`.

**PrisonMacro 0.3.9: interfaz reorganizada en español y 32 pruebas instrumentadas Android superadas.** El editor ahora muestra una cabecera sencilla con «Añadir acción» y «Reproducir», cada tarjeta tiene «Editar» y «Probar», y el selector de pasos se presenta en botones grandes agrupados por Gestos e imágenes, Condiciones, Secuencia y Dispositivo y aplicación. La pestaña Proyectos incluye «Mis macros» con Editar/Reproducir por proyecto. Se corrigió una regresión del selector que ocultaba «SI SE VE UNA IMAGEN» y «SI SE VE UN COLOR». La ronda terminó con `OK (32 tests)` en 52,349 s en el AVD aislado `emulator-5560`. La revisión visual se guardó en `outputs/editor-0.3.9-ui.png` y `outputs/action-picker-0.3.9-final.png`. APK: `outputs/PrisonMacro-0.3.9.apk`; SHA-256: `3d64a849bec40646703b6f3dc57f86bea9fbefe7d250c75c524d25164e6becd7`.

**PrisonMacro 0.3.8: compilación, lint, firma APK v2, ausencia de `testOnly` y 32 pruebas instrumentadas Android superadas.** Se añadió «Clic en imagen detectada»: cada acción puede elegir una captura de la biblioteca, cambiarla, conservarla al guardar y configurar centro, desplazamiento o punto fijo. La interfaz vertical de esa configuración evita ocultar opciones en una fila horizontal. La APK se instaló en el AVD aislado `emulator-5560`; la ronda terminó con `OK (32 tests)` en 53,295 s. El test de interfaz seleccionó «Cruz de anuncio» para una acción directa y verificó que la tarjeta guardada muestra la captura nueva. La prueba no usa BlueStacks, Zooba, compras, mensajes ni cuentas reales. APK: `outputs/PrisonMacro-0.3.8.apk`; SHA-256: `78552cc3df808ba7aeb732d22420518028095921bff404bc4d71ce89cf441e09`.

El caso de accesibilidad que falló en una primera ronda no era un fallo de la APK: el test desplazaba un diálogo horizontal y perdía chips que estaban fuera del árbol visible. Se corrigió el diseño pasando Centro/Desplazamiento/Punto fijo y Un clic/Doble clic/Pulsación larga a opciones verticales; la segunda ronda completa pasó. El clic sobre una coincidencia real se deja para el laboratorio con consentimiento de captura y accesibilidad; no se ejecuta sobre Zooba.

La 0.3.6 integra Firebase Auth Email Link y Realtime Database. El código valida el correo, mantiene cada cuenta aislada, muestra sesiones activas durante tres minutos y permite subir/descargar proyectos con capturas. La validación de una cuenta real requiere abrir el enlace recibido por correo en el mismo dispositivo; no se simuló un acceso real ni se enviaron macros a un juego. Las reglas privadas de `database.rules.json` ya se publicaron en `prison-ed653`.

El compañero iPhone (`ios/PrisonMacro/`) compila en Debug para `iphonesimulator` arm64/x86_64 y en Release para `iphoneos` arm64 con firma desactivada. Incluye importación de captura, recorte, miniatura por condición, exportación de PNG dentro del JSON y sincronización. No se pudo abrir una interfaz de simulador porque CoreSimulator no tiene un runtime disponible en este Mac; tampoco se afirma instalación física sin firma Apple.

Se reemplazó el bloqueo fijo de la actividad principal por un selector persistente: Horizontal fijado, Vertical fijado o Seguir sistema. El valor inicial continúa siendo Horizontal fijado para no reintroducir los giros de BlueStacks. La selección Vertical fijado fue pulsada y revisada en la interfaz real del emulador; la captura `outputs/prisonmacro-0.3.5-vertical.png` confirma diseño compacto, estado completo y botones inferiores sin textos truncados. Las pantallas de recorte y laboratorio mantienen horizontal, ya que capturas y coordenadas deben coincidir con la pantalla que se automatiza.

**PrisonMacro 0.3.4: 26 pruebas JVM y 29 pruebas instrumentadas Android superadas.** La ronda final duró 68,971 s en `sdk_gphone64_arm64`. También pasaron compilación, Android lint, ZIP, firma APK v2 y ausencia de `testOnly`. APK: `outputs/PrisonMacro-0.3.4.apk`; SHA-256: `b03219d1c113b8b5682315e3c700e14e6bf851222e82a6cb29375880ad456b2c`.

La nueva instrucción «Cerrar app activa desde Recientes» abre Recientes, espera el tiempo configurado, descarta hacia arriba la tarjeta activa y vuelve a Inicio. La prueba física se ejecuta solo contra `LabActivity` en el emulador y confirma que Inicio queda reanudado; no abre ni manipula Zooba. Se corrigió un fallo real por el que el gesto del sistema heredaba indebidamente la validación de resolución de la plantilla del proyecto. No se afirma «Forzar detención»: Android puede conservar el proceso.

La vista horizontal compacta elimina la cabecera alta, integra Ayuda en la fila de pestañas, compacta el encabezado del proyecto y recoge las operaciones secundarias bajo «Opciones». La captura real revisada es `outputs/prisonmacro-0.3.4-fullscreen-compact.png`: se ven tres instrucciones en la misma altura donde antes se veía aproximadamente una. Las letras principales mantienen la tipografía legible. La regresión recorre «Opciones» y «+ Condición» y conserva intacto el proyecto al cancelar.

Durante la validación se corrigieron dos falsos fallos de prueba: la comprobación de Recientes buscaba `LabActivity` en todo el historial en vez de leer la actividad reanudada, y el grabador podía guardar antes de que el emulador entregara `onClick` a la app objetivo. La prueba espera ahora ambos hechos de forma acotada; la ronda final completa pasó.

**PrisonMacro 0.3.3: 25 pruebas JVM y 28 pruebas instrumentadas Android superadas.** El selector de cada condición fija arriba la captura elegida, busca por nombre y filtra también las capturas de otros proyectos. La regresión escribe «Cruz», comprueba que queda un resultado, elige esa captura, guarda y verifica su identificador en la condición. La ronda final duró 61,721 s en `sdk_gphone64_arm64`; compilación, lint y firma APK v2 también pasaron. Inspección visual: `outputs/selector-search-0.3.3.png`. APK: `outputs/PrisonMacro-0.3.3.apk`; SHA-256: `2e6059f407984f16646957ff04cac47f6d46b1392c253f503912df13ba55be1e`.

**PrisonMacro 0.3.2: 25 pruebas JVM y 28 pruebas instrumentadas Android superadas.** La ronda Android final duró 79,697 s en `sdk_gphone64_arm64`. Compilación, lint, ZIP, firma APK v2 y ausencia de `testOnly` también se verificaron. El editor compacto se revisó en una captura real del emulador: los controles Añadir/Deshacer/Rehacer/Simular aparecen antes de la lista; la condición cerrada conserva nombre, miniatura, objetivo y resumen Entonces/Si no; Detalles abre las ramas. Las regresiones de selección de imagen y condición de color se adaptaron para recorrer este flujo compacto y pasaron. Evidencia: `outputs/editor-0.3.2.png`.

La 0.3.2 se instaló después en BlueStacks Air sobre la 0.3.1. Tras volver a abrirla se verificaron visualmente la versión, la barra superior compacta y el proyecto conservado con sus 8 instrucciones. Se abrió y cerró «Detalles» en la primera condición: cambió correctamente a «Ocultar» y regresó al resumen sin modificar el contador ni la captura asociada. No se ejecutó la macro ni se abrió Zooba.

APK final de esta ronda: `outputs/PrisonMacro-0.3.2.apk`. SHA-256: `d312538bab3ea6988a28732dfa70203cc7580b916df4d1cb6843e8de0527d6aa`.

**25 pruebas JVM y 28 pruebas instrumentadas Android superadas**, sin fallos ni omisiones. La ronda instrumentada final duró 76,267 s en `sdk_gphone64_arm64`. También pasaron compilación, Android lint y verificación de firma APK v2; el entregable no contiene `testOnly`.

La regresión de interfaz abre el editor con dos capturas guardadas, comprueba que el botón para añadir otra está visible arriba, entra en una condición, selecciona «Cruz de anuncio», pulsa Guardar y comprueba que esa condición conserva el identificador elegido. Las opciones aparecen en una lista vertical con miniaturas y la tarjeta muestra la elegida. Otra regresión verifica que una condición nueva permanece en «Imagen elegida: NINGUNA» aunque el proyecto ya tenga capturas, evitando asignaciones implícitas. Una nueva regresión pulsa «Hacer una captura nueva y recortarla», comprueba que Imagen se abre desde arriba, muestra la condición solicitante y conserva su identificador como destino pendiente. La asignación recursiva se prueba también en JVM para condiciones anidadas sin alterar las hermanas. Otra regresión comprueba que volver a recortar una captura mantiene las coordenadas absolutas de la pantalla original y conserva su identificador. El proyecto JSON versión 4 conserva biblioteca, asociaciones por acción y condiciones de color; sigue importando los esquemas 2 y 3. La validación rechaza referencias inexistentes, recortes fuera de pantalla y parámetros de color inválidos.

Se corrigió además una carrera del grabador: en dispositivos cargados Android podía notificar el fin de `dispatchGesture` antes de que la aplicación objetivo procesara el toque. La capa interceptora permanece oculta 350 ms antes de rearmarse. Una pulsación inmóvil ya no se guarda como una trayectoria de dos puntos idénticos: se normaliza a un punto, evitando que una pantalla desplazable la interprete mal. La prueba A → espera → B → guardar → recargar → reproducir vuelve a pasar dentro de la ronda completa.

El laboratorio ahora es desplazable, deja accesibles los controles inferiores y muestra la marca PRISONMACRO. Las condiciones SI SE VE sin captura se conservan como borrador editable, pero el motor bloquea su ejecución con un mensaje claro; nunca heredan silenciosamente la imagen principal. La regresión nueva lo comprueba incluso cuando existe una imagen principal.

La interfaz ya no usa «principal» para las condiciones. Cada tarjeta SI SE VE muestra «Captura de esta condición» y un botón Elegir/Cambiar imagen. «Imagen de inicio rápido» queda como disparador global opcional y separado. La biblioteca muestra miniatura y estado de inicio rápido, y cada captura ofrece directamente Recortar, Usar para inicio rápido, Renombrar, Borrar y controles de orden. Dos pruebas JVM confirman que una captura creada para una condición se asigna solo a ella y no reemplaza el inicio rápido existente; la primera captura sí puede inicializarlo cuando aún no existe. La regresión Android pulsa el botón visible Cambiar imagen, elige otra captura y guarda su identificador.

La versión 0.3.0 añade condiciones **SI SE VE UN COLOR** con búsqueda global o coordenada exacta, tolerancia RGB, vista de muestra y ramas ENTONCES/SI NO. Las pruebas JVM cubren coincidencia exacta, búsqueda global, tolerancia, rechazo de ruido aislado, cancelación y conversión hexadecimal. La regresión Android abre el diálogo, comprueba sus controles y confirma que la condición sobrevive al ciclo guardar/exportar/cargar. Durante una ronda apareció un cierre transitorio del proceso de instrumentación sin traza retenida: la prueba de color pasó después aisladamente y la batería completa terminó correctamente en dos ejecuciones posteriores, incluida la compilación final.

La prueba visual de la 0.3.0 en BlueStacks descubrió que «+ Condición después» añadía inmediatamente una condición de imagen y Cancelar dejaba ese borrador guardado. La 0.3.1 muestra primero un selector limitado a imagen o color y elimina automáticamente el bloque recién creado si se cancela su edición. La regresión 28 reproduce exactamente ese flujo y confirma que la secuencia conserva su único paso original.

APK final: `outputs/PrisonMacro-0.3.1.apk`. SHA-256: `9070b386d1bf0f9bfd4c7009a897acf29d1a986ee17a6b7843db12cd49d1c1e1`.

La 0.3.1 se instaló y abrió manualmente desde la pantalla de inicio de BlueStacks Air, conservando el proyecto existente. Se retiró únicamente la condición vacía creada por la prueba anterior y se confirmó que el contador volvió de 9 a 8 instrucciones. En la aplicación instalada se abrió «+ Condición después», se verificó que ofrece exclusivamente «SI SE VE UNA IMAGEN» y «SI SE VE UN COLOR», se abrió la configuración de color y se pulsó Cancelar. El contador volvió de 9 a 8, demostrando visualmente que el borrador temporal ya no queda guardado. También se verificaron la versión 0.3.1, la miniatura visible y el botón «Cambiar imagen de esta condición». Las 28 pruebas automatizadas corresponden al emulador aislado y no a un juego; en BlueStacks no se ejecutó la macro ni se abrió Zooba.

## Actualización: grabador y acciones Android

Última batería: **17 JVM + 20 instrumentadas superadas**, 35,181 s; después se repitió aisladamente la grabación de dos botones: OK (1 test), 9,786 s. No contar la repetición como una prueba distinta. Se verificó grabación A/B, esperas, recarga desde disco y reproducción de ambos botones. No equivale aún a disparar esa grabación por una imagen.

Se protegió el cierre de instancias antiguas del servicio para no borrar una conexión nueva; el test espera la desconexión antes de reconectar. El grabador mantiene 150 ms oculta la capa interceptora después del callback de Android antes de rearmarla, para permitir procesar la pulsación. Una ejecución intermedia había vuelto a fallar en B; los resultados indicados son posteriores al último ajuste. No garantiza ausencia de fallos en otros dispositivos.

Nuevas instrucciones: Inicio (salir, no forzar cierre), Atrás, Recientes, Notificaciones, Ajustes rápidos y Abrir app con selector de aplicaciones instaladas. Persistencia y rechazo de paquete inválido probados; pendiente comprobar visualmente cada acción global en dispositivos reales. No incluye forzar detención, root ni pulsaciones automáticas sobre compras. Abrir app acepta la solicitud de lanzamiento; añadir espera/detección para su carga.

APK firmado e instalado sin testOnly. SHA-256 actual: `f396daed2b7c519eb1902bd4dcfff5368d1aeed863361860b1c6458830e6dba7`. Los apartados siguientes son históricos.

## Incidencia resuelta: regresión de grabación múltiple

Se añadió `recordsTwoButtonsWithWaitAndReloadsBeforeReplay`: graba A/B, comprueba las esperas, recarga el proyecto del disco y reproduce. Tras ampliar el margen de rearme a 350 ms, esta prueba supera la ronda completa indicada arriba.

Durante la ejecución aparecieron `failed to attach`, un cierre del proceso y un diálogo visible `System UI isn't responding` del emulador (evidencia `outputs/gesture-failure.png`). Se reinició únicamente el laboratorio sin borrar datos y se cerró el diálogo mediante su botón. La nueva ejecución completa terminó con 19 pruebas, 2 fallos: servicio no conectado en el test de un clic y primer gesto no guardado en el test múltiple. La prueba de gesto directo sí pasó en esta última ronda. `outputs/device-tests.txt` contiene el resultado fallido, no el anterior aprobado.

La interferencia del sistema está observada, pero no se ha demostrado que explique todos los fallos restantes. Pendiente aislar la reconexión entre tests y la recepción del primer gesto. La última ronda completa aprobada sigue siendo 17 JVM + 18 instrumentadas; el APK de producción no se modificó durante esta investigación. Se conserva la evidencia manual previa de detección A/espera/B.

## Última verificación: cadena visual real completada en laboratorio

Se añadió «Probar imagen → A → esperar → B» al laboratorio: usa una copia del proyecto, demora inicial de 500 ms, gesto A, espera de 500 ms y gesto B. Solo una ejecución, búsqueda máxima de 10 segundos; se detiene al salir y restaura el proyecto al destruir la actividad. No equivale a una prueba de secuencia grabada completa: los gestos de este ensayo se construyen a partir de los botones del laboratorio.

Con MediaProjection y accesibilidad autorizadas mediante los diálogos normales:

- Plantilla real guardada anteriormente: coincidencia 100 % en (206,351), 255 ms; A pasó de 25 a 26 y B de 0 a 1.
- Imagen movida: coincidencia 100 % en (251,381), 128 ms; A=27 y B=2.
- Imagen oculta: mejor similitud 72 %, inferior al 93 % exigido; timeout sin acciones, contadores permanecen A=27/B=2.
- Evidencias: `outputs/live-flow-before.png`, `outputs/live-flow-after.png`, `outputs/live-flow-log.txt`.
- Regresión anterior a la prueba manual: 17 JVM y 18 instrumentadas correctas (19,819 s); APK firmado, sin testOnly e instalado sin `-t`.
- SHA-256 actual: `b8d21f8b9372b47ec2f2f21e5ca75c3e30486049b8eee590c596f816f5eff0d4`.

Al terminar se detuvo la captura y se desactivó el permiso temporal de accesibilidad del laboratorio. El editor quedó abierto. En aquella ronda BlueStacks aún no estaba validado; la comprobación posterior de la 0.3.2 está documentada al principio de este archivo. Los apartados siguientes conservan la cronología y los pendientes que existían entonces.

## Entorno

Mac Apple Silicon; Android Studio Rabbit 1; SDK de compilación 35; Android Emulator API 37, `sdk_gphone64_arm64`, puerto 5560. Las pruebas automatizadas se ejecutan exclusivamente sobre la app y su laboratorio. BlueStacks Air 5.21.790.7505 tiene ADB desactivado; la 0.3.2 sí se instaló y revisó visualmente mediante su interfaz, pero la ejecución completa de macros en BlueStacks no se ha probado.

## Resultados

- 17 pruebas JVM: cero fallos, cero omitidas. Localización en bordes y centro, modo exacto, negativo, cancelación, tamaño inválido, árbol, mover/duplicar/eliminar, números no válidos, rutas de imagen, IDs duplicados y plantilla uniforme.
- 14 pruebas instrumentadas: editor/laboratorio, JSON completo, esquema inválido, coordenadas inválidas, persistencia atómica y recuperación desde copia ante corrupción, simulación repetida, parada, pulsación real por AccessibilityService, grabación asistida y reproducción real, detector exacto, detección desplazada/refinamiento, imagen ausente, resolución incompatible y cancelación de detección diferida al parar o cambiar de proyecto. Última ejecución: OK (14 tests), 16,849 segundos.
- APK instalado en el emulador con `adb install -r`, **sin `-t`**. La APK de instrumentación sí usa `-t` por ser un paquete de pruebas separado.
- Firma APK v2 verificada y estructura ZIP íntegra. `testOnly` ausente en el APK entregable.
- Android lint: cero errores; 23 advertencias (versiones más recientes disponibles, internacionalización de textos españoles, referencias de ciclo de vida, accesibilidad de la superficie gestual y gravedad física). No se han silenciado errores con un baseline.
- Captura del editor producida por instrumentación y revisada visualmente: `outputs/editor-test.png`.

La prueba de gestos activa temporalmente accesibilidad **solo en el emulador sdk_gphone** y restaura los valores anteriores. Se comprobó que vuelven a `null` / `0`. No se han cambiado los permisos de BlueStacks ni de un teléfono.

## Fallos encontrados y corregidos

1. APK inicial marcado `testOnly=true`: BlueStacks registró `INSTALL_FAILED_TEST_ONLY`. El script publica solo APK normal, firmado y verificado.
2. JDK 25 incompatible con el analizador de AGP 8.7.3: JDK 21 aislado, sin modificar instalaciones existentes.
3. `InputStream.readNBytes` requería API 33: lectura acotada compatible desde API 26.
4. Una pantalla estática podía dejar de ser detectable por caducidad del frame: se conserva hasta que termine la proyección.
5. Descartar frames demasiado pronto podía perder el último cambio: adquisición diferida del último frame disponible, en vez de descartar sin reemplazar.
6. Detección exacta sufría potencial desalineación al reducir imágenes por separado: comparación exacta sobre píxeles originales; búsqueda global con refinamiento.
7. Error de captura podía confundirse con imagen ausente y disparar ELSE: ahora se detiene el motor.
8. Importaciones podían contener IDs duplicados o rutas de imagen no seguras: validación explícita y archivos importados renombrados internamente.
9. Guardado fallido alteraba el historial de deshacer: el estado se actualiza solo si el guardado tiene éxito.
10. Detecciones simultáneas podían acumular copias grandes en la cola: una detección activa como máximo.
11. Fondos uniformes daban coincidencias poco útiles: se rechazan por falta de variación.
12. La prueba de persistencia usaba el contexto del paquete de test con otro UID: directorio aislado en caché del proceso correcto.
13. El runner detenía el servicio al arrancar: setup de test controlado tras iniciar instrumentación, sin supresión de accesibilidad.
14. La prueba de repeticiones contaba eventos de ejecuciones anteriores: identificador único por prueba, manteniendo el registro persistente.
15. La prueba manual de detección usaba un temporizador ajeno al motor: podía dispararse después de PARAR o sobre otro proyecto. Ahora pertenece al motor, reemplaza solicitudes previas y comprueba el proyecto original antes de actuar. Regresión instrumentada superada.
16. Cambiar a un proyecto cuyo guardado falla borraba el historial de deshacer del proyecto actual. Ahora se conserva si la actualización falla; comprobado por revisión de código, sin simulación de disco lleno.

17. Un gesto de recorte cancelado conservaba una selección parcial y permitía guardarla; ahora se descarta. También se ignoran inicios fuera de la imagen y contactos múltiples. Prueba nueva sobre el View real: imagen horizontal con bandas, arrastre inverso y conversión a píxeles originales, inicio fuera de imagen y cancelación. No utiliza MediaProjection ni reemplaza capturas guardadas.

Última ronda adicional: 17 pruebas JVM y **15 instrumentadas superadas**, 15,509 segundos en dispositivo. La cifra de 14 anterior corresponde a la ronda previa. El flujo completo de captura en vivo sigue pendiente.

18. Guardar una captura sobrescribía directamente `capture.png`, pudiendo perder la anterior si la codificación fallaba a medias. Ahora se publica mediante AtomicFile. Prueba instrumentada con PNG válido, excepción de escritura inyectada tras tres bytes, conservación byte a byte del anterior y posterior sustitución por otra imagen válida. No simula apagado físico ni disco lleno.

Última ronda de persistencia: **17 pruebas JVM y 16 instrumentadas superadas**, 18,214 segundos en dispositivo. APK recompilado, firmado y verificado sin testOnly, e instalado sin `-t`. SHA-256 actual: `8c8a96122d61589a9a742718036d48f5678fe57c347f8d2822543324e6400973`.

## Pendiente de verificar

### Prueba manual de captura real (22/10, sesión interactiva)

En el laboratorio API 37 se aceptaron los diálogos normales de compartir pantalla y accesibilidad. `dumpsys media_projection` confirmó proyección activa de MacroStudio, no privilegiada y sin consentimiento pendiente. Desde el panel flotante se capturó la pantalla del laboratorio, se abrió CropActivity, se arrastró un rectángulo y se guardó una plantilla de 680×111 en (206,351). El registro confirmó el guardado. Evidencia visual: `outputs/live-crop.png`. No se ejecutaron macros en apps externas. Después se detuvo la captura y se restauró accesibilidad desactivada.

Fallos observados: el título del recortador quedaba bajo la barra de estado en API 37 y el texto de estado conservaba «Accesibilidad desconectada» después de reconectar. Se añadieron insets al recortador y actualización de estado al conectar. Compilación, JVM, lint y firma del nuevo APK correctos; falta nueva inspección visual del recortador corregido. SHA-256: `9d058cf5b0b4887a068e2e12ed6552d88f8408e66adfb12ac81d5c53f097e37f`.

Esto verifica captura → recorte → guardado reales, **no** todavía la cadena completa con detección y ejecución disparada por esa captura. El inspector `uiautomator dump` interrumpe temporalmente servicios de accesibilidad; para esta prueba se pasó a capturas `screencap` y entradas normales.

Regresión posterior a las correcciones: OK (18 tests), 20,162 segundos. APK instalado sin `-t`; app abierta en el emulador visible al terminar para demostración al usuario. No cerrar este emulador automáticamente asumiendo que sigue en modo nocturno.

Ronda posterior de seguridad del motor: **18 instrumentadas superadas** en 21,789 segundos, además de las 17 JVM. Dos regresiones nuevas comprueban que una pausa impide avanzar y PARAR descarta la continuación pendiente, y que IF_IMAGE sin captura detiene la ejecución sin entrar en ELSE. Son pruebas en simulación dentro del laboratorio; no acreditan MediaProjection en vivo. No se cambió código de producción en esta ronda; el APK mantiene el SHA-256 anterior y se reinstaló sin `-t`.

- MediaProjection con consentimiento normal, recorte y detección **en vivo** de principio a fin. Las pruebas de bitmaps no sustituyen esta comprobación.
- Ejecución completa de macros en BlueStacks Air; instalación y funcionamiento en Android físico, LDPlayer y Android 8 real.
- Grabación de arrastres largos, fondos animados, pantalla protegida y rotación durante distintas fases.
- Flujo de exportación/importación mediante el selector de documentos del sistema.
- Pruebas de estrés y consumo prolongado. La aplicación no se declara terminada ni libre de errores.

## Repetir las pruebas

Con el emulador de laboratorio ya arrancado:

```sh
bash scripts/test-device.sh emulator-5560
```

El script comprueba el modelo antes de las pruebas y verifica el texto de JUnit: `am instrument` puede devolver código 0 incluso con fallos. Informes: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html` y `outputs/device-tests.txt`.
