# PrisonMacro para iPhone · 0.3.18

La versión 0.3.18 mantiene el logo de Prison en la cabecera y como icono, varios proyectos locales, comprobación previa de errores y edición completa de pasos anidados. Cada acción de imagen muestra su miniatura y permite elegir otra captura guardada, volver a recortarla y conservar su asociación al exportar. La compilación para dispositivo se comprobó en `ios/build/ConnectedDeviceDerivedData/Build/Products/Debug-iphoneos/PrisonMacro.app` y se instaló y abrió en el iPhone 13 conectado con la firma de tu Team Apple.

## Qué hace esta primera versión

- Importa una captura desde Fotos/Archivos y permite arrastrar el recorte.
- Conserva la posición X/Y original al volver a recortar, necesaria para el modo «Punto exacto» en Android.
- Guarda la miniatura y el PNG dentro del esquema JSON versión 4.
- Deja añadir esperas, abrir aplicación, cerrar desde Recientes y condiciones «SI SE VE» asociadas a una captura concreta.
- Mantiene el nombre, la descripción y la miniatura de cada paso para que puedas revisar la macro sin entrar en cada captura.
- Dentro de cada condición puedes cambiar la captura y añadir acciones en «ENTONCES» o «SI NO»; SI NO vacío continúa con el siguiente paso.
- Permite editar nombre y descripción de cada paso.
- Exporta/importa proyectos y sincroniza proyectos con las capturas mediante la misma cuenta de correo de Firebase.
- Muestra las sesiones activas que la cuenta tiene registradas.
- Guarda varios proyectos en el iPhone y permite crear, abrir, duplicar y borrar cada copia local.
- Avisa antes de exportar/ejecutar en Android si falta una captura, una aplicación válida, una espera o contenido dentro de una repetición.

El iPhone es un editor y sincronizador. No puede controlar los toques de BlueStacks ni leer su pantalla remotamente; PrisonMacro Android es quien debe tener la captura de pantalla y la accesibilidad autorizadas para ejecutar la macro.

## Abrirlo en Xcode

1. Abre `ios/PrisonMacro/PrisonMacro.xcodeproj`.
2. En **Signing & Capabilities** selecciona tu Team. El proyecto ya usa firma automática; conserva `es.macrostudio.ios` o cámbialo por un identificador que pertenezca a tu cuenta.
3. Si Xcode avisa de que falta la plataforma iOS 26.5, abre **Xcode > Settings > Components** e instala el componente iOS correspondiente.
4. En el iPhone, activa **Ajustes → Privacidad y seguridad → Modo desarrollador** y confirma el reinicio. iOS exige esta confirmación en el propio teléfono.
5. Conecta y desbloquea el iPhone, acepta **Confiar en este ordenador** si aparece, selecciona el iPhone 13 en Xcode y pulsa **Run**. El proyecto tiene como mínimo iOS 16.
6. Como alternativa reproducible, desde la raíz del proyecto ejecuta `bash scripts/install-ios.sh`; compila, firma mediante Xcode y usa `devicectl` para instalarla en el iPhone disponible.
7. En el iPhone, importa una captura del juego, arrastra el recuadro, corrige las medidas de pantalla si hace falta y pulsa Guardar.
8. Añade la condición desde «Añadir pasos», cambia su captura si hace falta y añade acciones en ENTONCES o SI NO.
9. Usa Exportar JSON para pasarlo manualmente o inicia sesión por enlace de correo y pulsa «Subir proyecto». En Android, abre **Nube**, actualiza y descarga el proyecto.

El proyecto Xcode permanece en `ios/PrisonMacro/PrisonMacro.xcodeproj`. La compilación firmada que se instaló en el dispositivo está en `ios/build/ConnectedDeviceDerivedData/Build/Products/Debug-iphoneos/PrisonMacro.app`; para otra instalación física hay que volver a compilar con el Team Apple que corresponda.

## Verificación realizada

El target 0.3.18 compila para `iphoneos` ARM64 con `CODE_SIGNING_ALLOWED=NO`; la comprobación actual terminó en `BUILD SUCCEEDED` después de mantener `WAIT_IMAGE` en paridad con Android y eliminar un aviso del editor Swift. En una ronda anterior se instaló y abrió el bundle firmado en el iPhone 13. No se afirma que iOS ejecute toques sobre BlueStacks: el iPhone edita, recorta, guarda, exporta y sincroniza; Android conserva la captura/accesibilidad y la ejecución.
