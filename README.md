# Tasf.B2B - Ant Colony Optimization (ACO)

Implementacion en Java del planificador ACO para Tasf.B2B usando los datos reales del curso:

- `aeropuertos.txt`
- `planes_vuelo.txt`
- `_envios_preliminar_.zip`

El proyecto ya no depende del `NetworkGenerator` para correr los escenarios principales. Ahora carga:

- 30 aeropuertos fijos con continente, GMT y capacidad de almacen.
- 2866 planes de vuelo diarios con horarios locales y capacidad real.
- envios desde el ZIP, filtrados por rango de fechas.

## Modelo usado

El solver trabaja sobre una linea de tiempo en UTC para evitar errores por zona horaria:

- Cada vuelo del archivo es un horario diario repetitivo.
- En la busqueda, cada horario se instancia como una salida concreta segun el momento actual del envio.
- La feromona se deposita sobre el horario base del vuelo.
- La capacidad se controla por salida concreta, no solo por ruta base.

Ademas del plazo de entrega, la evaluacion penaliza:

- envios no entregados
- retrasos
- sobrecarga de vuelos
- overflow de almacen en aeropuertos

## Entradas

### Aeropuertos

Se leen desde `aeropuertos.txt` en UTF-16.

### Vuelos

Formato esperado por linea:

```text
ORIG-DEST-HH:MM-HH:MM-CAP
SKBO-SEQM-03:34-04:21-0300
```

### Envios

Se leen desde `_envios_preliminar_.zip`, donde cada archivo representa un aeropuerto origen.

Formato esperado por linea:

```text
id_envio-aaaammdd-hh-mm-dest-###-idCliente
000000001-20260102-00-55-SPIM-002-0019169
```

## Ejecucion

### Compilar

```bash
mvn clean compile
```

### Escenarios

```bash
java -cp target/classes pe.edu.pucp.tasf.Main E1 [fecha] [cancelaciones]
#java -cp target/classes pe.edu.pucp.tasf.Main E2 [dias] [fechaInicio] [cancelaciones]
#java -cp target/classes pe.edu.pucp.tasf.Main E3 [fechaInicio] [maxDias] [cancelaciones]
```

Los tres escenarios usan el mismo solver ACO. Lo que cambia por parametro es el horizonte de planificacion y el criterio operativo:

- `E1`: operaciones dia a dia en tiempo real. Planifica un dia.
- `E2`: simulacion de periodo. Planifica 3, 5, 7 o mas dias segun el parametro `dias`.
- `E3`: simulacion hasta colapso. Avanza dia por dia hasta llegar a semaforo rojo o hasta `maxDias`.

El parametro opcional `cancelaciones` indica cuantas cancelaciones de vuelos se simulan despues de planificar. Si no se envia, vale `0`. En `E3`, esa cantidad se aplica por cada dia simulado.

La fecha se pasa en formato `yyyymmdd`.

Ejemplos:

```bash
java -cp target/classes pe.edu.pucp.tasf.Main E1 20260102 2
#java -cp target/classes pe.edu.pucp.tasf.Main E2 5 20260102 2
#java -cp target/classes pe.edu.pucp.tasf.Main E3 20260102 30 2
```

## Resolucion de rutas de datos

Por defecto el programa busca los archivos en este orden:

1. `data/` dentro del proyecto
2. la raiz del proyecto
3. el directorio padre del proyecto

La opcion recomendada es:

```text
aco-tasf/
  data/
    aeropuertos.txt
    planes_vuelo.txt
    _envios_preliminar_.zip
```

Si se quiere usar otras rutas, sobreescribir con propiedades Java:

```bash
java ^
  -Dtasf.airports=data\aeropuertos.txt ^
  -Dtasf.flights=data\planes_vuelo.txt ^
  -Dtasf.shipments=data\_envios_preliminar_.zip ^
  -cp target/classes pe.edu.pucp.tasf.Main E2 5 20260102 2
```

Para experimentacion numerica, la semilla se puede variar sin cambiar el modelo:

```bash
java ^
  -Dtasf.seed=42 ^
  -cp target/classes pe.edu.pucp.tasf.Main E2 5 20280215 0
```

### Parametros ACO fijados para E2

El escenario de periodo (`E2`) usa una configuracion fija del ACO hibrido para que las replicas sean comparables:

- variante: MMAS con refuerzo elitista y reparacion de almacenes
- hormigas: 20
- iteraciones maximas: 10000
- limite de tiempo: 900000 ms
- alpha: 1.0
- beta: 4.5
- rho: 0.18
- Q: 150.0
- hormigas elitistas: 5
- maxHops: 10
- tau inicial: 1.0
- tau min / max: 0.1 / 10.0
- reparacion de almacenes: activa por defecto
- parada temprana: si `fitness = 0`

## Notas de escala

El ZIP contiene millones de registros. Para horizontes grandes, el programa puede activar agregacion de pedidos equivalentes cuando el numero de solicitudes de planificacion supera el umbral configurado.

La agregacion:

- conserva origen, destino y minuto de liberacion
- suma cantidades compatibles
- divide en lotes de tamano controlado para que el ACO siga siendo operable

## Salida del reporte

Cada corrida reporta:

- solicitudes planificadas
- maletas totales
- maletas entregadas
- maletas no entregadas
- maletas tardias
- delay total
- overflow de vuelos
- overflow de almacen
- fitness
- semaforo

Tambien lista las primeras rutas de la solucion para inspeccion rapida.
