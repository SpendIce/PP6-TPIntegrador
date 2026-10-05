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
