# Manual · PrisonMacro 0.4.0

## Instalar

En BlueStacks usa el botón APK de la barra lateral y elige `outputs/PrisonMacro-0.4.0.apk`. Se instala encima de la versión anterior y conserva tus macros.

## Cómo está organizada la app

```
Mis macros (inicio)
├── ▶ en cada macro ............ muestra el panel flotante y te deja en tu juego
├── ⋮ en cada macro ............ editar · cambiar nombre · duplicar · exportar · borrar
├── ＋ Nueva macro
└── ⚙ Ajustes
    ├── 💾 Copias de seguridad .. crear copia de TODO · restaurar / importar
    ├── 🔐 Permisos ............. accesibilidad · captura de pantalla
    ├── ☁ Nube · 📜 Registro · 🧪 Laboratorio
    └── 📱 Orientación

Editor de una macro
├── 🚩 Cómo empieza ............ al pulsar ▶ o cuando se vea una imagen
├── Lista de pasos (toca uno para editarlo; ⋮ para subir, bajar, duplicar, borrar…)
├── 🖼 Imágenes ................ biblioteca de recortes de esta macro
├── ⚙ Ajustes de la macro ...... vueltas, pausas, sensibilidad de la búsqueda
└── Abajo: ＋ Añadir paso · ● Grabar · ▶ Reproducir
```

## Primera vez: permisos

En la pantalla de inicio aparece «Antes de empezar» con dos botones:

1. **Accesibilidad → Activar.** Busca PrisonMacro en la lista y actívalo. Si Android dice «ajuste restringido»: Ajustes › Apps › PrisonMacro › ⋮ › Permitir ajustes restringidos.
2. **Captura de pantalla → Autorizar.** Elige compartir la pantalla completa. Android la vuelve a pedir si se reinicia la app; si no la has autorizado, la app la pide sola cuando hace falta.

Ahora la captura **ya no se corta al girar la pantalla** (por ejemplo, al abrir un juego horizontal): se adapta sola.

## Crear una macro

1. **＋ Nueva macro**, escribe el nombre y pulsa Crear. Se abre el editor.
2. Elige una forma:
   - **● Grabar**: la app se esconde y aparece el panel flotante. Ve a tu juego, pulsa **●** y haz tus toques. Cada toque se reproduce al soltarlo y se guarda con la espera que hubo antes. Pulsa **■ Terminar grabación** y vuelves al editor con los pasos.
   - **＋ Añadir paso**: elige qué hacer, agrupado en *Tocar la pantalla*, *Esperar*, *Decidir y repetir* y *Android y apps*.
3. Pulsa **▶ Reproducir**. La app se esconde, vas a tu juego y pulsas **▶** en el panel cuando quieras empezar.

### Panel flotante

`⠿ ▶ ■ ● 📷 ⌂ ✕` — arrastra desde **⠿** para moverlo. **▶** reproduce (y pasa a **Ⅱ** para pausar), **■** para, **●** graba, **📷** guarda una captura en la biblioteca, **⌂** vuelve a PrisonMacro, **✕** oculta el panel.

## Imágenes: «cuando veas esto, toca aquí»

Añade el paso **🎯 Buscar imagen y tocarla** (o **⏳ Esperar a ver una imagen**, o **🖼 Si se ve una imagen…**). En su editor, la sección de imagen tiene:

- **📷 Capturar** — lo más rápido. La app se esconde y aparece un panel con **📷 Capturar**. Ve a la pantalla del juego, pulsa Capturar, arrastra el dedo sobre el botón que quieres reconocer, ponle nombre y **✓ Guardar**. Vuelves automáticamente al mismo paso con la imagen ya puesta.
- **🖼 Mis imágenes** — reutiliza una imagen de esta macro o de otra (se copia).
- **📁 Galería / archivos** — trae una imagen del dispositivo (en BlueStacks, «Pick from Mac»).
- **✂ Recortar** — recorta más una imagen ya guardada.
- **👁 ¿Se ve ahora?** — la app se esconde y en 2,5 s te dice si encuentra la imagen en pantalla.

En **Buscar imagen y tocarla** eliges dónde tocar (en el centro, desplazado o en otro punto de la pantalla, que puedes marcar tocando la pantalla) y el tipo de toque (uno, doble o largo).

En **Si se ve una imagen…** y **Si se ve un color…** la lista muestra las dos ramas, **✔ Si se ve** y **✖ Si NO se ve**, cada una con su **＋ Añadir paso aquí**. Igual con **🔁 Repetir pasos**.

## Cómo empieza y cuántas veces se repite

Toca la tarjeta **🚩** del editor (o ⚙):

- **Al pulsar ▶** o **Cuando se vea una imagen** (con 📷 Capturar o 🖼 Mis imágenes para elegirla).
- **Vueltas**: cuántas veces se repite la macro entera, y la pausa entre vueltas.
- **Búsqueda de imágenes**: en toda la pantalla o solo en su sitio original, el parecido exigido (90–95 % recomendado) y el tiempo máximo buscando.

Por seguridad, cualquier ejecución se detiene a los 30 minutos o tras 10.000 pasos.

## Copias de seguridad

- **Una macro**: en Mis macros, ⋮ › **📤 Exportar / compartir**. Genera un `.json` con sus imágenes.
- **Todas**: ⚙ Ajustes › **💾 Crear copia de seguridad**. Un solo archivo con todas las macros e imágenes.
- **Recuperar**: ⚙ Ajustes › **♻ Restaurar copia o importar una macro**. La app reconoce el tipo de archivo:
  - una copia completa reemplaza las macros con el mismo origen y no toca las demás (pide confirmación);
  - una macro suelta se añade como nueva.

## Límites

Misma resolución y orientación en que se capturó la imagen; un solo dedo al grabar; sin OCR, variables ni multitáctil. «Cerrar la app actual» la quita de Recientes pero no equivale a «Forzar detención».
