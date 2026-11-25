# Criba

Revisa las fotos y vídeos de tu móvil Android desde el PC, uno a uno, y decide con el teclado qué se queda. Pensado para cuando el móvil está lleno y la galería no ayuda a limpiar.

No borra nada del móvil. Lo que descartas se mueve a una carpeta del propio móvil y lo borras tú cuando lo hayas revisado.

## Requisitos

- Windows 10 u 11
- Python 3.10 o superior ([python.org](https://www.python.org/downloads/), marca "Add python.exe to PATH")
- Android 11 o superior con la depuración USB activada
- Un cable USB de datos

No hace falta instalar nada más: si falta `adb`, el lanzador lo instala con winget.

## Uso

1. En el móvil, activa las opciones de desarrollador (Ajustes → Acerca del teléfono → pulsa 7 veces en "Número de compilación", o en "Versión de MIUI" en Xiaomi) y dentro activa **Depuración USB**.
2. Conecta el móvil y abre `Abrir Criba.bat`.
3. Acepta en el móvil el aviso "¿Permitir depuración USB?".
4. Se abre `http://127.0.0.1:8765` en el navegador. Elige qué revisar y empieza.

| Tecla | Acción |
|---|---|
| `N` | Borrar |
| `S` | Conservar |
| `P` | Guardar en el PC y quitar del móvil |
| `A` | Saltar el resto del álbum |
| `Z` | Deshacer |
| `Espacio` | Pausar el vídeo |
| `←` `→` | Retroceder / avanzar 5 s |
| `M` | Sonido |
| `Esc` | Ir al repaso |

Al terminar cada álbum ves un resumen. En el repaso confirmas y entonces se mueven los archivos.

## Dónde acaba cada cosa

| Decisión | En el móvil | En el PC |
|---|---|---|
| Borrar | `Criba_para_borrar/<álbum>/` | nada |
| Guardar en el PC | `Criba_ya_en_PC/<álbum>/` | `Vídeos\Del movil\<álbum>\` |

Los archivos de "Guardar en el PC" solo se mueven en el móvil después de copiarlos y comprobar que la copia es idéntica (tamaño y MD5). Desde el repaso puedes devolver cualquier archivo a su sitio con un clic.

Para borrar de verdad, abre esas carpetas desde el Explorador (hay botones en la página) o desde la galería del móvil y bórralas.

## Modos

- **Vídeos más pesados**: lo que más espacio libera con menos decisiones.
- **Todo, de mayor a menor** y **Todo por fecha**.
- **Un álbum**: eliges el álbum en la tabla.
- **WhatsApp: enviados y privados**: WhatsApp oculta estas carpetas a la galería y suelen ocupar varios GB.

## Privacidad

Todo funciona en tu PC. El servidor solo escucha en `127.0.0.1` y no se conecta a internet. Las decisiones y el inventario se guardan en la carpeta `criba/` y no se suben a ningún sitio.

## Problemas frecuentes

- **El vídeo no se reproduce**: algunos vídeos HEVC (H.265) no se ven en todos los navegadores. Puedes decidir igualmente por nombre y tamaño.
- **El móvil no aparece en el Explorador**: en el aviso USB del móvil elige "Transferencia de archivos". El botón "Abrir Criba_para_borrar" intenta activarlo solo.
- **`unauthorized` o no detecta el móvil**: desbloquea el móvil, acepta el aviso de depuración y vuelve a abrir el lanzador.
- **Xiaomi**: algunos ajustes de MIUI/HyperOS piden además "Depuración USB (ajustes de seguridad)". Para usar Criba no hace falta.

## Licencia

[MIT](LICENSE)
