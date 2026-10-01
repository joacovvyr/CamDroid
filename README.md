# CamDroid

Cámara Android experimental con modo de retrato local. El modelo de segmentación de personas está incluido en el APK, y la aplicación no necesita Internet.

## Foto IA de alta resolución

Desde la versión 0.5, **GUARDAR FOTO IA** usa CameraX ImageCapture para solicitar un JPEG del sensor con calidad máxima. Un modelo local calcula una máscara nueva sobre esa foto y el procesamiento conserva los píxeles originales de la persona, mientras desenfoca el fondo. La foto deja de ser una captura de pantalla del preview. CameraX y el fabricante determinan la resolución y el procesamiento que finalmente entrega la cámara. Para evitar agotar la memoria, las fotos superiores a 20 megapíxeles se decodifican con reducción por potencias de dos.

La vista en tiempo real es otro camino: CameraX ImageAnalysis, máscara local en modo STREAM_MODE y desenfoque OpenGL. Inicia a 1280×720; la inferencia se reduce internamente a 640 píxeles en el lado mayor. La foto procesa su propia máscara con SINGLE_IMAGE_MODE. La foto aplica la intensidad de desenfoque elegida; el ajuste «Mejora IA» modifica solo la vista. La cámara delantera se refleja en el archivo para coincidir con la composición mostrada.

En la versión 0.6, **Ver máscara IA real** muestra solo la persona detectada. El punto de enfoque ya no se añade a esa máscara ni protege un círculo de fondo al desenfocar. Los bordes de confianza intermedia usan el color de la imagen como guía, tanto en la vista como al guardar la foto. La vista mantiene siempre juntos el frame y su máscara para evitar bordes dobles al mover la mano; puede actualizarse menos veces por segundo. Esto mejora transiciones sobre fondos contrastados, pero no puede reconstruir un dedo o un mechón que el modelo no detectó.

Desde la versión 0.7, **FOTO ORIGINAL + IA HASTA 8K** captura un JPEG y guarda dos archivos: `_ORIGINAL.jpg` conserva exactamente los bytes entregados por CameraX, y `_IA.jpg` aplica ajuste local de sombras y superresolución ESRGAN ×4 por bloques. La salida tiene hasta 7680 píxeles en el lado mayor y 33,2 megapíxeles (7680×4320); la resolución real depende de la proporción y del original. No captura detalle óptico nuevo: la superresolución estima textura y puede inventar detalles finos, especialmente sobre letras, pelo y edificios. Tampoco iguala una edición generativa grande ni recupera zonas totalmente quemadas o negras. El procesamiento puede tardar varios minutos y consumir mucha memoria, particularmente cerca del límite 8K. La foto original se guarda antes de iniciar la IA para preservarla si el procesamiento falla.

El modelo [`ESRGAN.tflite`](app/src/main/assets/ESRGAN.tflite) se incluye en el APK y se ejecuta con TensorFlow Lite, sin enviar la foto a Internet. Procede del [ejemplo oficial de superresolución para Android de TensorFlow](https://github.com/tensorflow/examples/tree/master/lite/examples/super_resolution/android); su [ficha de modelo](https://www.kaggle.com/models/kaggle/esrgan-tf2) indica licencia MIT. SHA-256 del archivo: `1A380D3744103E11EF343534AAFF54815CAE40769DCD00C023652A7E5BC47F4B`.

En Android 10 o posterior, las fotos IA aparecen en `Pictures/CamDroid`. En Android 8 y 9 se guardan en el directorio privado externo de la app.

## Controles

El modo retrato tiene intensidad de desenfoque, selector de perfil virtual, máscara de diagnóstico, cambio de cámara y controles pro de cámara. Los perfiles virtuales y los controles pro no convierten el sensor del teléfono en una cámara profesional. La app tampoco produce RAW/DNG, HDR multiframe ni una foto IA de video. La nitidez y los bordes dependen del enfoque, la luz, el sensor y la calidad de la segmentación. El modelo puede fallar con pelo, dedos y objetos transparentes.

## Compilar y probar

Se requieren Android SDK 35, JDK 17 y Gradle 8.9. Ejecutar `gradle assembleDebug lintDebug`. GitHub Actions genera un APK de prueba, verifica el modelo incluido y comprueba que el manifiesto no incluya permiso de Internet.

Para evaluar calidad, comparar la foto guardada con la cámara nativa del mismo teléfono, con el mismo lente y luz. Probar especialmente manos y cabello, la foto frontal, desenfoque 0% y 100%, y resoluciones de vista distintas. Revisar la resolución real del JPEG guardado y la memoria al tomar varias fotos seguidas.
