import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 }, // Ramp-up a 10 utenti simultanei in 30s
    { duration: '1m',  target: 30 }, // Sale a 30 utenti simultanei per 1 minuto (fase di picco)
    { duration: '30s', target: 0  }, // Ramp-down a 0 utenti
  ],
};

export default function () {
  // Genera un valore casuale di 'usage' tra 5 e 20 per variare il carico di lavoro
  const usage = Math.floor(Math.random() * 15) + 5;
  const res = http.get(`http://localhost:8080/compute?usage=${usage}`);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(0.2); // Pausa di 200ms tra le richieste
}