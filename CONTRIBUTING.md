# Colaborar en PrisonMacro

Este repositorio contiene la aplicación Android y el compañero SwiftUI para iPhone. El flujo está pensado para editar y sincronizar proyectos entre dispositivos; la ejecución de toques y la accesibilidad pertenecen a Android.

## Empezar

```sh
git clone https://github.com/djxusoo-boop/PrisonMacro.git
cd PrisonMacro
```

Para Android se necesita Android Studio con SDK 35, build-tools 36.0.0 y JDK 21. El script de compilación busca el JDK aislado en `.tools/` si está disponible:

```sh
bash scripts/build-apk.sh
```

El script ejecuta las pruebas unitarias, lint, compilación, firma y comprobación de que la APK no sea `testOnly`. La batería instrumentada solo se ejecuta contra el emulador de laboratorio seguro:

```sh
bash scripts/test-device.sh emulator-5560
```

No ejecutes las pruebas sobre BlueStacks, juegos, compras, mensajes ni cuentas reales.

Para iPhone abre `ios/PrisonMacro/PrisonMacro.xcodeproj` con Xcode. La firma de desarrollo y el Team de Apple son propios de cada colaborador; no se incluyen certificados ni perfiles.

## Configuración de Firebase

`app/google-services.json` y la configuración cliente de `ios/PrisonMacro/CloudStore.swift` contienen identificadores y claves cliente de Firebase necesarios para que compile la app. No son credenciales de administrador. Las reglas de `database.rules.json` limitan los datos a `auth.uid`.

Nunca subas claves privadas, cuentas de servicio, tokens de sesión, certificados, perfiles de Apple, almacenes de firma, `local.properties` ni archivos `.env`. El `.gitignore` ya excluye esos materiales y el estado privado de Xcode.

## Cambios y pruebas

1. Mantén las funciones de captura, recorte, selección de imagen, color, esperas y ramas en el editor.
2. Añade o actualiza pruebas antes de cambiar el motor.
3. Comprueba `git diff --check` y ejecuta el laboratorio antes de compartir una APK.
4. No afirmes que una macro funciona en BlueStacks o Zooba basándote solo en el emulador: son entornos distintos.

Consulta `README.md` y `docs/PRUEBAS.md` para conocer el estado y las limitaciones verificadas.
