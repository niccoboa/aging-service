package it.unifi.qesm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Path("/compute")
@ApplicationScoped
public class AgingResource {

    private final AtomicLong requestCounter = new AtomicLong(0);
    private final String podName = System.getenv().getOrDefault("HOSTNAME", "unknown-pod");

    @Inject
    MeterRegistry registry;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String compute() {
        long start = System.nanoTime();
        long usage = requestCounter.incrementAndGet();
        long iterations = usage * 2_500L;
        double dummyResult = 0;

        for (long i = 0; i < iterations; i++) {
            dummyResult += Math.sin(i);
        }

        long durationNs = System.nanoTime() - start;

        // Registra la metrica: NON serve passare il tag 'pod' qui,
        // ci pensa Prometheus tramite le label dei pod di Kubernetes!
        Timer.builder("aging_service_compute_duration_seconds")
                .description("Tempo di esecuzione CPU per replica")
                .publishPercentiles(0.5, 0.95)
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);

        return String.format("[%s] Request #%d processed. Checksum: %.4f\n", podName, usage, dummyResult);
    }
}