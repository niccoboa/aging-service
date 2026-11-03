import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
  scenarios: {
    mmpp_workload: {
      executor: 'constant-vus',
      vus: 10,
      duration: '2m',
    },
  },
};

// Configurazioni per i 3 stati di carico (LOW, MID, HIGH)
const STATES = {
  LOW:  { name: 'LOW',  usage: 5,  sleepSec: 0.5 },
  MID:  { name: 'MID',  usage: 10, sleepSec: 0.2 },
  HIGH: { name: 'HIGH', usage: 20, sleepSec: 0.05 },
};

// Funzione di transizione di stato della catena di Markov (Profilo MostlyMid)
function getNextState(current) {
  const r = Math.random();
  if (current === 'MID') {
    if (r < 0.15) return 'LOW';
    if (r > 0.85) return 'HIGH';
    return 'MID';
  } else if (current === 'LOW') {
    return r < 0.6 ? 'MID' : 'LOW';
  } else if (current === 'HIGH') {
    return r < 0.6 ? 'MID' : 'HIGH';
  }
  return 'MID';
}

let currentState = 'MID';

export default function () {
  currentState = getNextState(currentState);
  const config = STATES[currentState];

  const res = http.get(`http://localhost:8080/compute?usage=${config.usage}`);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(config.sleepSec);
}
