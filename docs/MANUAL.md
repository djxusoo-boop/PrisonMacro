# Manual · PrisonMacro 0.3.15

## Instalar en BlueStacks

Usa el botón APK de la barra lateral y selecciona **outputs/PrisonMacro-0.3.15.apk**. No selecciones el antiguo `app/build/intermediates/apk/debug/app-debug.apk`.

No es necesario iniciar sesión en Google para instalar este APK. Si aparece otro error, conserva el mensaje exacto. El error anterior era `INSTALL_FAILED_TEST_ONLY`, corregido en el proceso de empaquetado. ADB de BlueStacks estaba desactivado; no se han cambiado sus ajustes ni cuentas.

## Usar horizontal o vertical

En **Proyectos → Orientación de PrisonMacro** elige:

- **Horizontal fijado**: valor predeterminado y recomendado mientras usas BlueStacks con un juego horizontal. Evita que el editor vaya girando al volver del juego.
- **Vertical fijado**: pensado para editar listas largas. Activa automáticamente la vista compacta: sin cabecera alta, con tarjetas plegadas y controles inferiores con iconos para aprovechar la altura.
- **Seguir sistema**: deja que BlueStacks o Android decidan la orientación.

Antes de capturar, grabar o ejecutar sobre una pantalla horizontal, vuelve a **Horizontal fijado**. Las plantillas y gestos están ligados a su orientación y resolución.

## Primer proyecto

1. En Proyectos, cambia el nombre. Los cambios válidos se guardan automáticamente.
2. En Grabador, abre el Laboratorio. Empieza allí antes de usar otras aplicaciones.
3. En Proyectos, activa el servicio PrisonMacro en Accesibilidad. Android puede exigir permitir ajustes restringidos para una app instalada manualmente; esa decisión corresponde al usuario.
4. En Imagen, pulsa Autorizar captura. Comparte la pantalla completa. Android puede volver a pedir este consentimiento al reiniciar la sesión.
5. Abre el panel flotante, navega al Laboratorio o app de prueba y pulsa Captura. El panel se oculta antes de tomar la imagen.
6. Arrastra un rectángulo sobre el elemento a reconocer y guarda. Incluye texto, bordes o detalles, no un fondo liso.
7. Selecciona Punto exacto para exigir la posición original, o Cualquier punto para buscar en la pantalla completa. Esta versión exige la misma resolución, orientación y escala.

Cada nuevo recorte se añade a **Biblioteca de capturas**. Allí puedes renombrarlo, elegirlo como imagen de inicio rápido o borrarlo si ninguna condición lo está usando. La imagen de inicio rápido es independiente de las capturas elegidas dentro de cada condición.

## Una imagen, varios clics

1. Vuelve al panel flotante sobre tu app de prueba.
2. Pulsa Grabar. Haz un clic o un arrastre y suelta.
3. Espera a que se reproduzca el gesto antes del siguiente. Se graba una trayectoria de un solo contacto, no multitáctil.
4. Pulsa FIN REC. Los pasos y las esperas se añaden al proyecto, sin reemplazar los anteriores.
5. En Editor, ajusta tiempos y pasos. Las coordenadas de pulsaciones manuales son porcentajes de la pantalla.
6. **Ejecutar** empieza por la primera instrucción, por ejemplo Abrir aplicación. **Simular todo** recorre los pasos sin enviar gestos ni abrir aplicaciones.

Para el caso directo «cuando aparezca esta imagen, pulsa», añade **Detectar imagen y hacer clic**. En su edición verás la biblioteca y podrás tocar la miniatura que quieras; no usa automáticamente la imagen principal. La tarjeta del editor conserva la miniatura y el nombre para que sepas qué hará sin abrirla. Después elige:

- **Centro**: pulsa el centro de la coincidencia encontrada.
- **Desplazamiento**: pulsa a una distancia relativa de la captura, en X/Y entre -100 y 100 %.
- **Punto fijo**: pulsa en coordenadas porcentuales de toda la pantalla, útil si la imagen sirve de aviso pero el botón está en otra zona.
- **Un clic**, **Doble clic** o **Pulsación larga**.

La acción espera hasta el tiempo máximo del proyecto. Si no encuentra la captura, detiene la macro y deja el motivo en Registro; no ejecuta un clic a ciegas.

Para crear «Abrir aplicación → Si se ve la imagen → Entonces pulsar», añade primero Abrir aplicación, pulsa **+ Condición** y toca el nombre de esa condición. El editor muestra las capturas del proyecto actual con miniatura y nombre. Más abajo aparecen las de otros proyectos: pulsa **Usar** para copiar una al proyecto actual y dejarla seleccionada; el original permanece en su proyecto.

### Menú de cada acción

En cada tarjeta del Editor, **⋮ Más** concentra las operaciones habituales: editar la acción, cambiar su captura, probarla sin ejecutar el resto, añadir una acción dentro de una condición o grupo, insertar una acción encima o una condición después, duplicar en la misma rama y borrar. En una acción anidada, estas operaciones conservan su sitio dentro de ENTONCES o SI NO.

La captura asignada aparece siempre en **Imagen elegida ahora**. Usa **Buscar captura por nombre** para reducir la lista; el mismo texto filtra también las capturas disponibles en otros proyectos. Seleccionar otra tarjeta solo cambia esta condición y no altera las demás.

«+ Condición» pregunta si quieres **SI SE VE UNA IMAGEN** o **SI SE VE UN COLOR** y la coloca justo después del paso elegido. Si pulsas Cancelar en la edición de una condición recién creada, no queda ningún bloque vacío en la secuencia.

Dentro de esa misma condición tienes tres formas de obtener la imagen:

1. Tocar una captura de la biblioteca del proyecto.
2. Pulsar **Traer imagen desde Archivos / Galería / Mac**, elegir una imagen del selector completo del sistema y recortarla. En BlueStacks hay que entrar por **Pick From Mac**. Ese selector externo no muestra la biblioteca interna de PrisonMacro. Al guardar queda asignada directamente a esa condición.
3. Pulsar **＋ Hacer una captura nueva y recortarla**. PrisonMacro abre Imagen y muestra a qué condición se asignará la próxima captura. Autoriza la pantalla si hace falta, abre el panel, pulsa Captura, recorta y guarda. Puedes cancelar esa asignación antes de capturar.

Una condición nueva queda en **Imagen elegida: NINGUNA** hasta que toques expresamente una miniatura. La app nunca asigna por su cuenta la primera captura.

La vista compacta se activa automáticamente en horizontal ancho. Quita la cabecera alta, coloca Ayuda junto a las pestañas y muestra varias instrucciones sin reducir el tamaño principal de las letras. Pulsa **Opciones** en cualquier tarjeta para moverla, duplicarla, añadir una condición, simularla o quitarla. Pulsa **Compacta** para volver a la vista amplia.

La tarjeta de una condición enseña siempre la miniatura o muestra de color, el nombre elegido y el número de pasos de **Entonces** y **Si no**. Pulsa **＋ Añadir acción en ENTONCES** directamente en la tarjeta para elegir el clic, arrastre, espera o función que se ejecutará al encontrarla. Pulsa **Opciones** y después despliega sus detalles para ver las ramas; pulsa directamente el nombre de la instrucción para editarla. **Recortar esta imagen** permite ajustar de nuevo la seleccionada conservando su asociación. Añade la pulsación o función dentro de ENTONCES. SI NO es opcional; vacío significa continuar con el siguiente paso. La opción **Ejecutar al ver la imagen de inicio rápido** sigue disponible por separado en Imagen.

## Condición de color

En Editor → Añadir instrucción, elige **SI SE VE UN COLOR**. Puedes escribir un color hexadecimal como `#FF5500` o usar uno de los botones rápidos Rojo, Verde, Azul o Amarillo. Configura la tolerancia RGB entre 0 y 255: un valor pequeño exige mayor parecido y uno grande admite más variaciones. El modo **Cualquier punto** busca por toda la pantalla; **Punto exacto** utiliza coordenadas X/Y porcentuales. Para reducir falsos positivos no basta un píxel aislado: varios píxeles vecinos deben tener un color parecido. Después añade acciones en ENTONCES y, opcionalmente, en SI NO.

El panel ocupa la franja superior. No puede grabarse un toque sobre sus botones. La reproducción asistida ocurre al soltar el gesto, por lo que no equivale a una grabación pasiva instantánea.

## Tiempos y repetición

- Espera tras detectar: retardo antes de empezar los pasos.
- Tiempo entre ejecuciones: pausa **después de completar** la secuencia antes de volver a buscar; no es una cadencia de inicio a inicio. Cero significa una ejecución.
- Tiempo máximo buscando: evita esperar para siempre.
- Máximo de repeticiones: límite adicional. Si la imagen sigue presente y el intervalo es mayor que cero, puede volver a activarse.
- Límite de seguridad global: 30 minutos y 10.000 instrucciones por sesión.

En los campos numéricos pulsa Aplicar. Los valores inválidos se muestran en rojo y no se guardan.

## Condiciones, pruebas y parada

IF evalúa la imagen una vez y ejecuta ENTONCES o SI NO. Si falta la captura o la plantilla es ilegible, el motor se detiene: no confunde un error técnico con una imagen ausente.

Simular no envía gestos, pero sí respeta esperas y evalúa condiciones de imagen cuando hay captura. Pausa actúa entre instrucciones; el gesto actual puede acabar. PARAR cancela las acciones futuras y las búsquedas; una pulsación prolongada ya entregada a Android puede continuar hasta su duración configurada. No uses duraciones largas si necesitas detener inmediatamente.

La notificación permite cerrar la captura. Un cambio de orientación finaliza la sesión de captura para evitar clics en coordenadas incorrectas: reautoriza y vuelve a capturar/grabar.

## Guardar y recuperar

Cada proyecto conserva su estado actual y una copia anterior válida. Si el JSON actual se corrompe, el lector intenta recuperar la copia. Deshacer/rehacer pertenece a la sesión abierta. Exportar JSON incluye todas las capturas y sus asociaciones y sirve como copia portátil. Importar crea otro proyecto, no sobrescribe el existente. Máximo de importación: 30 MB.

En **Nube**, cada correo funciona como una cuenta independiente. Solicita un enlace de acceso, ábrelo en el mismo dispositivo y usa **Subir proyecto actual** para guardar el proyecto con sus capturas. En otro dispositivo, entra con el mismo correo, pulsa **Actualizar lista** y usa **Descargar**. El proyecto descargado se importa como una copia nueva para no sobrescribir el que ya tienes abierto. La presencia muestra las sesiones vistas durante los últimos tres minutos. Aplica `database.rules.json` en Realtime Database antes de usar esta función.
# Acciones fáciles de Android

En Editor → Añadir instrucción están disponibles: salir al inicio, volver atrás, aplicaciones recientes, desplegar notificaciones, desplegar ajustes rápidos y abrir una aplicación. En «Abrir aplicación / juego», pulsa «Elegir app instalada» y guarda. Añade una espera o detección para dar tiempo a cargar.

«Salir al inicio» no fuerza el cierre del juego. «Recientes» solo abre el selector. **Cerrar app activa desde Recientes** abre ese selector, espera entre 500 y 3000 ms, desliza hacia arriba la tarjeta que estaba activa y vuelve a Inicio. Es la alternativa sin root más sencilla para retirar la app de Recientes; no equivale a «Forzar detención» y Android puede conservar el proceso en memoria. Pruébala primero en el laboratorio y aumenta la espera si el selector de tu dispositivo tarda en aparecer. La simulación no abre apps ni paneles. Android puede rechazar acciones según dispositivo o permisos: el motor se detiene si recibe rechazo.
