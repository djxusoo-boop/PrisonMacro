# Continuación del desarrollo

Nombre público actual: **PrisonMacro**. APK actual: `outputs/PrisonMacro-0.3.18.apk`. Identificador `es.macrostudio` conservado para actualizar sin perder proyectos. La 0.3.18 mantiene la comprobación previa, los resúmenes `ENTONCES / SI NO`, la biblioteca de imágenes y la selección de una captura distinta dentro de cada acción. Android pasa unit tests, lint, firma APK v2 y comprobación de `testOnly`. El compañero iPhone compila para `iphoneos` con los cambios actuales; su instalación física se documenta solo cuando se realiza con firma y dispositivo disponibles. BlueStacks Air sigue necesitando instalación visible desde su botón APK porque su puente ADB no está expuesto en este entorno; no se presenta esa limitación como un fallo de la APK. No ejecutar partidas, compras, mensajes ni cuentas durante las pruebas.

## Ronda actual · 0.3.18

- APK publicada: `outputs/PrisonMacro-0.3.18.apk`; compilación, lint, firma v2 y ausencia de `testOnly` correctas. SHA-256 actual: `3277b817c63fb47b43001207b83d9c3e3a30c1de693020da206a1085dfd00002`.
- En el emulador aislado `Medium_Phone_API_37.0` pasaron las dos pruebas dirigidas: elegir una captura existente para una condición y cambiarla desde el editor (`OK (1 test)` cada una).
- La regresión completa del laboratorio terminó en `OK (34 tests)` en `emulator-5560`. Pasan los dos cierres desde Recientes, Pausa/Seguir, selección de capturas, una pulsación grabada y dos pulsaciones con espera, guardado y reproducción.
- El Editor distingue ahora `Reproducir ahora` de `Al ver imagen`; el segundo espera la captura principal antes de ejecutar la secuencia. El laboratorio mantiene sus dos botones de grabación dentro del área visible para no probar por accidente la barra de navegación.
- `xcodebuild` para `iphoneos` ARM64 terminó correctamente sin firma (`CODE_SIGNING_ALLOWED=NO`) después de la paridad de `WAIT_IMAGE`; no se instaló una nueva copia física durante esta ronda. La app iPhone es editor/sincronizador; iOS no puede enviar toques a BlueStacks.

La cadena MediaProjection real → recorte → guardar → detectar → ejecutar A/espera/B ya se verificó manualmente en laboratorio, incluida imagen desplazada y negativa oculta (ver último apartado inicial de PRUEBAS.md). La regresión actual añade abrir/cerrar la app de laboratorio desde Recientes y estabiliza el grabador. No se ha ejecutado la macro sobre Zooba ni sobre anuncios reales. Prioridad siguiente: modo exacto en vivo y secuencia de varios gestos grabados disparada por imagen. No confundir la prueba guiada A/B con grabación de la secuencia completa.

Estado:

- El APK original fallaba por `testOnly=true`. Confirmado en Player.log con INSTALL_FAILED_TEST_ONLY.
- `scripts/build-apk.sh` impide publicar APK testOnly y verifica firma, ZIP, tests y lint.
- Emulador de laboratorio usado en esta ronda: `Medium_Phone_API_37.0`, serial `emulator-5560`, modelo `sdk_gphone64_arm64`; no es BlueStacks ni un teléfono personal. Comprobar siempre modelo y serial antes de probar.
- El emulador de laboratorio queda separado de BlueStacks. Si hace falta repetir pruebas, arrancar `/Users/xuso/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_37.0 -port 5560 -no-snapshot-save -no-audio -no-window`, comprobar `sys.boot_completed` y ejecutar las pruebas dirigidas o `scripts/test-device.sh emulator-5560`. No cerrar BlueStacks.
- JDK aislado `.tools/jdk-21.0.12.1+1/Contents/Home`. JDK25 de Studio no sirve para lint antiguo. No modificar Java global.
- La prueba GestureDeviceTest activa/restaura accesibilidad solo en `sdk_gphone`. En cualquier otro dispositivo debe omitirse y no tocar permisos.
- Capturas y registros de verificación en `outputs`, informes Gradle bajo `app/build/reports`.
- El script de pruebas espera hasta 60 segundos a `sys.boot_completed=1` antes de comprobar el modelo: evita fallar inmediatamente con `device offline` tras arrancar el laboratorio. Cambio revisado con `bash -n`; pendiente probar específicamente el arranque lento. No modifica la APK ni acredita pruebas adicionales de la app.
- Automatización de seguimiento `revisar-macrostudio-es`, cada hora en este chat. Revisar el estado más reciente; no repetir trabajo ni notificar si no hay avances.

Siguiente trabajo seguro:

- Próxima mejora propuesta: añadir búsqueda por nombre/tipo para proyectos largos y una prueba de ida y vuelta iPhone → nube → Android con una cuenta de laboratorio, sin ocultar errores ni cambiar el orden real de ejecución.

0. La selección y sustitución de captura por acción pasan de forma aislada en 0.3.18. La batería completa de 34 pruebas también pasa en el AVD actual. El cierre desde Recientes incluye un watchdog: intenta retirar la tarjeta y, si Android no la expone a tiempo, vuelve a Inicio y deja registro; no se promete «Forzar detención».

1. Revisar el último informe de pruebas y ejecutar regresiones tras cambios.
2. Comprobar ciclo de captura con el consentimiento normal de Android. No usar mecanismos para esconderlo o saltarlo en el dispositivo del usuario.
3. Probar recorte en horizontal y cambios de resolución; verificar que no se ejecutan gestos con un perfil incompatible.
4. Mover persistencia e importación/exportación fuera del hilo principal con resultados de guardado explícitos.
5. Mejorar edición de trayectorias, ejecución individual real y región arbitraria solo con pruebas propias; no añadir botones simulados.
6. Después, añadir funciones/variables/OCR como incrementos separados, no prometerlos antes de implementarlos.

No automatizar partidas, compras, mensajes o cuentas. No borrar datos ni ajustes existentes. Si el único paso pendiente requiere una decisión de permisos del usuario, documentarlo y dejar el APK disponible sin afirmar verificación total.
