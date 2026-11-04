package it.unifi.qesm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicLong;

@Path("/compute")
@ApplicationScoped
public class AgingResource {
    
    private final AtomicLong requestCounter = new AtomicLong(0);
    
    // Legge il nome del Pod assegnato da Kubernetes
    private final String podName = System.getenv().getOrDefault("HOSTNAME", "unknown-pod");

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String compute() {
        long usage = requestCounter.incrementAndGet();
        //long iterations = usage * 500_000L;
        long iterations = usage * 2_500L;
        double dummyResult = 0;
        
        for (long i = 0; i < iterations; i++) {
            dummyResult += Math.sin(i);
        }
        
        return String.format("[%s] Request #%d processed. Checksum: %.4f\n", podName, usage, dummyResult);
    }
}