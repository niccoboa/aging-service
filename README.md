# aging-service

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/aging-service-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)





## Fase 2: Bucket System per Replica, Osservabilità (Prometheus/Grafana) e Bilanciamento

Questa sezione estende la guida precedente documentando l'implementazione del meccanismo di rilevamento dell'aging basato su bucket a livello di singola replica, l'integrazione dello stack di monitoraggio leggero (senza Istio per vincoli di RAM) e la risoluzione dei problemi di bilanciamento e memoria su Minikube (Kubuntu 26.04).

---

### 1. Scelta Architetturale (Zero Overhead)
Per rispettare i vincoli hardware dell'ambiente (host ~6 GB RAM, cluster Minikube limitato a `2048 MB` e `2 CPU`) evitando il sovraccarico di un Service Mesh completo (Istio/Envoy), il calcolo dei tempi di risposta e la logica a stati dei bucket sono stati implementati direttamente a livello applicativo in Quarkus:
* **Misurazione interna per replica:** Ogni pod cronometra l'esecuzione della richiesta con `System.nanoTime()` ed espone le metriche taggate con il proprio `HOSTNAME` (`pod`).
* **Prometheus & Grafana:** Utilizzati esclusivamente per lo scraping periodico (`/q/metrics` ogni 2s) e la visualizzazione delle serie storiche.

---

### 2. Configurazione Dipendenze (`pom.xml`)
Aggiunta delle estensioni Micrometer nel file `pom.xml` per esporre le metriche nel formato compatibile con Prometheus:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

---

### 3. Logica di Aging Over-Usage e Bucket System (`AgingResource.java`)
Aggiornamento del microservizio per combinare l'invecchiamento cumulativo (*over-usage*) con la macchina a stati dei bucket ($B_1 \dots B_M$, profondità $D$, soglie $\mu + m\sigma$):
* **Parametri configurati:** $M = 3$, $D = 3$ (capacità totale $Z = M \cdot D = 9$), $\mu = 400\text{ ms}$, $\sigma = 150\text{ ms}$.
* **Metriche esposte su `/q/metrics`:**
  * `aging_last_response_time_ms{pod="..."}`: Tempo di risposta esatto dell'ultima richiesta servita dal pod.
  * `aging_bucket_level{pod="..."}`: Livello complessivo dei token accumulati (scala lineare da `0` a `9`).
  * `aging_current_bucket_index{pod="..."}`: Indice del bucket attivo (`1..3`).
  * `aging_rejuvenation_triggered{pod="..."}`: Flag di overflow finale (`0` = sano, `1` = richiede rejuvenation).

#### Build e Deploy dell'immagine aggiornata (`1.0.2`)
```bash
./mvnw package -DskipTests
minikube image build -t aging-service:1.0.2 -f src/main/docker/Dockerfile.jvm .
sed -i 's/aging-service:1.0.1/aging-service:1.0.2/g' replicaset.yaml
kubectl apply -f replicaset.yaml
kubectl delete pods -l app=aging-service
```

---

### 4. Tuning Memoria e Bilanciamento Reale del Carico
Durante i primi test sotto carico concorrente (30 VU con k6) sono emersi due colli di bottiglia risolti come segue:

1. **Aumento limiti RAM dei Pod (prevenzione `OOMKilled`):**
   Il limite iniziale di `128Mi` causava il riavvio dei container JVM sotto stress. In `replicaset.yaml` le risorse sono state aggiornate a:
   ```yaml
   resources:
     requests:
       cpu: "100m"
       memory: "128Mi"
     limits:
       cpu: "500m"
       memory: "256Mi"
   ```
   ```bash
   kubectl apply -f replicaset.yaml
   kubectl delete pods -l app=aging-service
   ```

2. **Passaggio da `port-forward` a `NodePort` (bilanciamento su tutte e 3 le repliche):**
   Poiché `kubectl port-forward` instrada tutto il traffico su un singolo pod attraverso un unico tunnel TCP, il Service `aging-service-lb` è stato convertito in `NodePort` per distribuire le richieste di k6 su tutte e 3 le repliche:
   ```bash
   kubectl patch svc aging-service-lb -p '{"spec": {"type": "NodePort"}}'
   kubectl get svc aging-service-lb   # Recupero porta esposta (es. 30201)
   kubectl get nodes -o wide          # Recupero INTERNAL-IP di Minikube (es. 192.168.49.2)
   ```
   Aggiornamento dell'endpoint nello script `load-test.js`:
   ```bash
   sed -i 's|http://localhost:8080|http://192.168.49.2:30201|g' load-test.js
   ```

---

### 5. Deploy e Configurazione Prometheus & Grafana
Per permettere a Prometheus di raccogliere le metriche da **tutti e 3 i pod contemporaneamente** anziché da una replica casuale dietro il LoadBalancer, è stato introdotto un Service headless (`clusterIP: None`) con DNS discovery (`dns_sd_configs`) e limiti di memoria conservativi (`256Mi` max per container).

```bash
kubectl apply -f prometheus.yaml
kubectl apply -f grafana.yaml
```

#### Esposizione Dashboard e Test di Carico
```bash
# Terminale 1: Port-forward delle interfacce web di monitoraggio
kubectl port-forward svc/prometheus-service 9090:9090 &
kubectl port-forward svc/grafana-service 3000:3000 &

# Terminale 2: Esecuzione test di carico MMPP bilanciato sulle 3 repliche
cd ~/aging-service
k6 run load-test.js --out csv=results_bucket.csv
```

* **Prometheus Targets:** `http://localhost:9090/targets` (verifica `3/3 up` sotto `aging-service-pods`).
* **Grafana Dashboard:** `http://localhost:3000` (Data Source `http://prometheus-service:9090`, pannelli per `aging_bucket_level`, `aging_last_response_time_ms` e `aging_rejuvenation_triggered` con legenda `{{pod}}`).
