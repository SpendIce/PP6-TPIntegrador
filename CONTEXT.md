# Acortamiento de enlaces

Vocabulario del servicio que transforma direcciones de destino en enlaces públicos temporales y reutilizables.

## Lenguaje

**URL de destino**:
Dirección original a la que conduce una asignación vigente del servicio.
_Evitar_: URL acortada, alias.

**Alias**:
Código público que forma parte del enlace acortado y puede asociarse a distintos destinos en momentos diferentes.
_Evitar_: Identidad de la asignación.

**Enlace acortado**:
Dirección pública formada por la dirección base del servicio y un alias. Puede cambiar de destino cuando su alias se reasigna.
_Evitar_: URL de destino.

**Asignación**:
Asociación temporal de un alias con una URL de destino. Cada solicitud de acortamiento produce una asignación independiente, incluso cuando se repite el destino.
_Evitar_: Alias, enlace permanente.

**Vencimiento**:
Fin de la vigencia de una asignación, establecido exactamente a los 60 minutos de su creación. El tiempo transcurrido con el servicio apagado también cuenta.
_Evitar_: Eliminación, reasignación.

**Reasignación**:
Uso de un alias cuya asignación anterior venció para una nueva asignación. Los enlaces y códigos QR anteriores que contienen ese alias pueden conducir al nuevo destino.
_Evitar_: Renovación de la asignación anterior.

**Código QR del enlace**:
Representación escaneable de la URL acortada. Representa esa dirección pública y no una asignación histórica particular.
_Evitar_: QR de la URL de destino.

**Historial de asignaciones**:
Conjunto de asignaciones conservadas, incluidas aquellas que vencieron o cuyo alias se reasignó. Describe las asociaciones anteriores entre alias y destinos.
_Evitar_: Asignación vigente, estadísticas de visitas.

## Usuarios y namespaces

**Usuario**:
Persona identificada por su cuenta de Google que inició sesión en el servicio. Crear enlaces acortados no requiere ser usuario.
_Evitar_: Cuenta, perfil.

**Namespace**:
Nombre público y único que un usuario elige para agrupar sus enlaces personales; equivale a «espacio de nombres». Cada usuario tiene a lo sumo uno y puede cambiarlo.
_Evitar_: Espacio de nombres, carpeta, usuario.

**Alias personalizado**:
Código que el usuario elige para un enlace personal. No puede repetirse entre los enlaces personales vigentes del mismo usuario.
_Evitar_: Alias (sin calificar), slug.

**Enlace personal**:
Dirección pública formada por la dirección base del servicio, el namespace de su usuario y un alias personalizado. Pertenece al usuario, no al nombre del namespace.
_Evitar_: Enlace acortado, enlace con login.

**Cambio de namespace**:
Reemplazo del namespace de un usuario por otro nombre libre. Los enlaces personales conservan su alias personalizado y pasan a responder bajo el nuevo nombre.
_Evitar_: Renombrar usuario, migración de enlaces.

**Namespace anterior**:
Nombre que un usuario dejó por un cambio de namespace y que, durante el período de gracia, sigue conduciendo a sus enlaces personales y solo ese usuario puede recuperar.
_Evitar_: Namespace viejo, alias del namespace.

**Período de gracia**:
Lapso que empieza con un cambio de namespace y durante el cual el namespace anterior queda reservado para su usuario. Al terminar, el nombre queda libre para cualquier usuario.
_Evitar_: Vencimiento.

**Sesión principal**:
Sesión que un usuario inicia directamente con Google en un dispositivo que considera confiable. Es la única que puede aprobar una vinculación por QR.
_Evitar_: Sesión de Google, dispositivo confiable.

**Sesión vinculada**:
Sesión abierta mediante una vinculación por QR, pensada para dispositivos no confiables. Dura menos que una sesión principal y no puede aprobar otras vinculaciones.
_Evitar_: Sesión QR, dispositivo vinculado.

**Vinculación por QR**:
Inicio de una sesión vinculada en un dispositivo que muestra un código QR, aprobado desde una sesión principal del mismo usuario.
_Evitar_: Login por QR, código QR del enlace.
