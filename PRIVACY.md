# Política de Privacidad

**Aplicación:** CodeStudio
**Fecha de entrada en vigor:** 20 de septiembre de 2026
**Versión de la política:** 2.0

Esta política describe de forma clara y completa cómo la aplicación CodeStudio (en adelante, "la aplicación" o "la app") maneja la información, con qué fines la utiliza y qué control tienes sobre ella. CodeStudio es un entorno de desarrollo integrado (IDE) que se ejecuta completamente en tu dispositivo Android. No necesitas crear una cuenta y no te pedimos tus datos personales para utilizarla.

Recomendamos leer este documento completo. Está redactado para ser transparente, no para ocultar nada detrás de tecnicismos.

---

## 1. El principio más importante: tus proyectos son tuyos

Nada de lo que escribes con CodeStudio sale de tu dispositivo salvo en los casos excepcionales y expresamente consentidos por ti que se detallan en esta política. Concretamente:

- La aplicación **no lee, sube ni comparte** tu código fuente, los nombres de tus archivos, los nombres de tus proyectos, ni el contenido de ningún documento.
- Tu proyecto, tus archivos, tus ajustes y tus claves de firma de Android (los keystores usados para publicar tus apps) **se guardan y permanecen localmente** en tu dispositivo.
- No creamos cuentas de usuario, no pedimos tu nombre, correo electrónico, teléfono ni ubicación, y no tenemos ninguna manera de identificarte personalmente.

## 2. Información que la aplicación recopila

### 2.1 Telemetría opcional de rendimiento y estabilidad (desactivada por defecto)

La única información que la aplicación podría enviar sobre tu uso es una **telemetría anónima de rendimiento y estabilidad**. Esta telemetría:

- Está **desactivada de forma predeterminada**. La primera vez que abres la app se te pregunta si deseas activarla, y nada se recopila ni se envía hasta que tú tocas "Permitir".
- Se puede activar o desactivar en cualquier momento desde los ajustes de la app. Si la desactivas, también se descarta cualquier dato que estuviera pendiente de envío.
- Solo recopila **datos agregados y de rendimiento**, nunca el contenido de tu código ni evento a evento lo que escribes.

Si decides activarla, la aplicación envía lo siguiente:

- **Información de la aplicación y del dispositivo:** versión y número de compilación de la app, nivel de API de Android, modelo y fabricante del dispositivo, arquitectura de la CPU (ABI) e idioma de la interfaz.
- **Mediciones de rendimiento:** cuánto tarda la app en arrancar, en indexar el proyecto, en compilar, en completar código y en hacer análisis de código. Estas mediciones se registran como duraciones y como resúmenes agregados (por ejemplo, un recuento y un promedio), no como un registro de teclas individuales.
- **Resultado de compilaciones:** si una compilación o ejecución tuvo éxito o falló, y cuánto tardó.
- **Informes de errores y de bloqueos (crash):** un informe depurado cuando la app falla o encuentra un error interno. El informe contiene únicamente el tipo de excepción y los marcos de pila (stack frames) propios de la aplicación. Se eliminan los mensajes de las excepciones, las rutas de archivo y cualquier código antes de generar el informe.
- **Identificadores aleatorios:** un identificador de instalación (un UUID aleatorio generado en tu dispositivo, que **no** está vinculado a tu identidad ni a ninguna cuenta) y un identificador de sesión que sirve para agrupar los eventos de un mismo arranque de la app.

### 2.2 Lo que la aplicación nunca recopila

- Tu código fuente, el contenido de tus archivos, los nombres de archivo ni los nombres de tus proyectos.
- Rutas del sistema de archivos.
- Qué funciones utilizas (no existe seguimiento del uso de funciones).
- Identificadores publicitarios, número de serie del dispositivo, IMEI, información de cuentas, contactos ni ubicación precisa.
- Cualquier dato que permita identificarte personalmente.

Si no activas la telemetría, **ninguna** de las informaciones anteriores sale de tu dispositivo.

## 3. Permisos que solicita la aplicación

La aplicación solicita dos permisos, ambos con un propósito concreto y limitado:

- **Conexión a Internet (INTERNET):** se utiliza para descargar las dependencias de los proyectos y los componentes del paquete de desarrollo de Android (Android SDK) cuando tú lo pides, y para enviar la telemetría opcional si la has activado.
- **Instalar aplicaciones desconocidas (REQUEST_INSTALL_PACKAGES):** se utiliza para que puedas instalar el APK que has compilado con CodeStudio. Esta instalación la inicias tú y pasa por el diálogo estándar de confirmación de instalación de Android.

## 4. Conexiones de red

Además de la telemetría opcional, CodeStudio se conecta a internet únicamente para llevar a cabo las acciones que tú inicias:

- Descargar dependencias de repositorios de paquetes (por ejemplo, repositorios Maven) cuando tu proyecto las requiere.
- Descargar componentes del Android SDK, fuentes y documentación desde los servidores de Google.

Estas peticiones contienen los nombres y las versiones de los paquetes que se están descargando. **No contienen tu código.** Los archivos descargados se guardan en la caché de tu dispositivo.

## 5. Uso de la información

Si activas la telemetría, su único propósito es **comprender y mejorar el rendimiento y la estabilidad** de la aplicación, por ejemplo para detectar operaciones lentas o diagnosticar fallos. Los datos de telemetría **no** se utilizan para fines publicitarios, para elaborar perfiles de usuario ni para su venta.

## 6. Compartir información con terceros

- CodeStudio **no vende** tus datos y **no los comparte con anunciantes**.
- La telemetría opcional se almacena mediante Supabase, un servicio de base de datos alojado que actúa como **encargado del tratamiento** de datos de la aplicación. La telemetría se transmite mediante una conexión cifrada. Ningún otro tercero recibe telemetría.
- Cuando descargas dependencias o componentes del SDK, esas peticiones se dirigen directamente a los repositorios de paquetes correspondientes o a los servidores del SDK de Google, que las gestionan según sus propias condiciones y políticas.
- Si utilizas un "gateway" personalizado (un endpoint compatible con OpenAI que tú configuras), la aplicación envía tus peticiones de modelo y tu base URL privada únicamente a ese endpoint que tú mismo designaste; no las reenvía a ningún otro servidor.

## 7. Conservación y supresión de los datos

- Los datos locales de tus proyectos, tus ajustes y las cachés permanecen **en tu dispositivo** hasta que tú los elimines o desinstales la aplicación. Al desinstalar, se eliminan los datos locales de la app.
- La telemetría, si la has activado, se conserva en el backend de telemetría para el análisis de rendimiento y estabilidad. Como la telemetría lleva únicamente un identificador de instalación aleatorio y no contiene información personal, los registros individuales no se pueden vincular a una persona concreta.
- Para detener toda futura recopilación, desactiva la telemetría en la app. Si además deseas que se elimine la telemetría ya enviada bajo tu identificador de instalación, contacta con nosotros en la dirección indicada al final de este documento e incluye el identificador de instalación que se muestra en los ajustes de analíticas de la app.

## 8. Seguridad

La telemetría se envía mediante HTTPS. Los datos de tus proyectos y las credenciales que utilizas dentro de CodeStudio (por ejemplo, las claves de firma) se almacenan en tu dispositivo y **no se transmiten** desde la aplicación.

## 9. Menores

CodeStudio es una herramienta para desarrolladores y no está dirigida a menores de 13 años. No recopila a sabiendas información personal de menores.

## 10. Cambios en esta política

Esta política puede actualizarse según la aplicación evolucione. Cualquier cambio material se reflejará aquí con una nueva fecha de entrada en vigor. Te animamos a revisar este documento periódicamente.

## 11. Contacto

Si tienes preguntas sobre esta política o sobre tus datos, puedes escribirnos a:

**contact.AssassinGhost@gmail.com**

El código fuente de la aplicación está disponible en: https://github.com/AssassinGhostYT/CodeStudio
