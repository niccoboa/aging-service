package it.unifi.qesm;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Path("/compute")
@ApplicationScoped
public class AgingResource {

    private final String podName = System.getenv().getOrDefault("HOSTNAME", "unknown-pod");

    @Inject
    MeterRegistry registry;

    // 1. Stato di Aging (Over-Usage): accumula il lavoro svolto dalla replica nel tempo
    private final AtomicLong totalUsageAccumulated = new AtomicLong(0);

    // 2. Parametri del Bucket System (calibrati sui tempi "sani" osservati ~400ms)
    private final int M = 3;              // Numero di bucket (B_1 ... B_M)
    private final int D = 3;              // Profondità di ogni singolo bucket (Depth)
    private final double MU = 400.0;      // Media tempo di risposta sistema sano (ms)
    private final double SIGMA = 150.0;   // Deviazione standard sistema sano (ms)

    // Stato corrente dei bucket per questa specifica replica
    private final AtomicInteger currentBucketIndex = new AtomicInteger(1); // Parte da B_1 (1..M)
    private final AtomicInteger tokensInCurrentBucket = new AtomicInteger(0); // Token nel bucket attivo (0..D)
    private final AtomicInteger totalBucketLevel = new AtomicInteger(0); // Livello globale (0 .. M*D)
    private final AtomicInteger rejuvenationTriggered = new AtomicInteger(0); // 1 se ha raggiunto M*D
    private final AtomicLong lastResponseTimeMs = new AtomicLong(0);

    @PostConstruct
    void initMetrics() {
        // Esponiamo le metriche a Prometheus taggate con l'ID della singola replica (pod)
        Gauge.builder("aging_last_response_time_ms", lastResponseTimeMs, AtomicLong::get)
                .tag("pod", podName)
                .description("Ultimo tempo di risposta esatto registrato dalla singola replica (ms)")
                .register(registry);

        Gauge.builder("aging_bucket_level", totalBucketLevel, AtomicInteger::get)
                .tag("pod", podName)
                .description("Livello totale dei token accumulati nei bucket (0 .. M*D)")
                .register(registry);

        Gauge.builder("aging_current_bucket_index", currentBucketIndex, AtomicInteger::get)
                .tag("pod", podName)
                .description("Indice del bucket attualmente attivo (1 .. M)")
                .register(registry);

        Gauge.builder("aging_rejuvenation_triggered", rejuvenationTriggered, AtomicInteger::get)
                .tag("pod", podName)
                .description("Flag che indica se la replica richiede rejuvenation (1 = si, 0 = no)")
                .register(registry);
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String compute(@QueryParam("usage") Integer usage) {
        if (usage == null || usage <= 0) {
            usage = 1;
        }

        // Inizio misurazione tempo di risposta per la singola richiesta
        long startTime = System.nanoTime();

        // Accumuliamo l'aging (over-usage) in modo proporzionale al carico ricevuto
        long currentAgingFactor = totalUsageAccumulated.addAndGet(usage);

        // Il carico computazionale dipende dal workload istantaneo (usage) + il degrado accumulato
        long iterations = (usage * 300_000L) + (currentAgingFactor * 15_000L);
        double dummyResult = 0;

        for (long i = 0; i < iterations; i++) {
            dummyResult += Math.sin(i);
        }

        // Calcolo tempo di risposta esatto in millisecondi
        long durationMs = (System.nanoTime() - startTime) / 1_000_000L;
        lastResponseTimeMs.set(durationMs);

        // Aggiornamento sincronizzato del Bucket System
        updateBucket(durationMs);

        return String.format(
                "[%s] Req usage=%d (Accumulated=%d) | RespTime=%dms | Bucket=B%d (Tokens=%d/%d, Total=%d/%d) | Rejuv=%d | Checksum=%f\n",
                podName, usage, currentAgingFactor, durationMs,
                currentBucketIndex.get(), tokensInCurrentBucket.get(), D,
                totalBucketLevel.get(), (M * D), rejuvenationTriggered.get(), dummyResult
        );
    }

    private synchronized void updateBucket(long responseTimeMs) {
        int m = currentBucketIndex.get();
        int tokens = tokensInCurrentBucket.get();

        // Soglia del bucket corrente B_m: mu + m * sigma
        double threshold = MU + (m * SIGMA);

        if (responseTimeMs > threshold) {
            // Violazione della soglia: aggiungiamo un token
            tokens++;
            if (tokens >= D) {
                // Overflow del bucket corrente
                if (m < M) {
                    currentBucketIndex.set(m + 1);
                    tokensInCurrentBucket.set(0);
                } else {
                    // Overflow dell'ultimo bucket B_M -> Trigger Rejuvenation!
                    tokensInCurrentBucket.set(D);
                    rejuvenationTriggered.set(1);
                }
            } else {
                tokensInCurrentBucket.set(tokens);
            }
        } else {
            // Richiesta completata "in tempo": togliamo un token
            if (tokens > 0) {
                tokensInCurrentBucket.set(tokens - 1);
            } else if (m > 1) {
                // Il bucket corrente si svuota -> torniamo al bucket precedente B_{m-1}
                currentBucketIndex.set(m - 1);
                tokensInCurrentBucket.set(D - 1);
            }
        }

        // Calcolo livello complessivo (da 0 a M*D) per una visualizzazione lineare su Prometheus/Grafana
        int total = ((currentBucketIndex.get() - 1) * D) + tokensInCurrentBucket.get();
        totalBucketLevel.set(total);
    }
}