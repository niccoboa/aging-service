import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

// --- MODELLO TEORICO CTMC (3 STATI) ---
const STATES = {
  LOW:  { name: 'LOW',  lambdaRps: 2,  exitRate: 1 / 15 }, // tempo medio: 15s
  MID:  { name: 'MID',  lambdaRps: 5,  exitRate: 1 / 20 }, // tempo medio: 20s
  HIGH: { name: 'HIGH', lambdaRps: 10, exitRate: 1 / 10 }, // tempo medio: 10s
};

const TRANSITIONS = {
  LOW:  { MID: 1.0, HIGH: 0.0 },
  MID:  { LOW: 0.4, HIGH: 0.6 },
  HIGH: { LOW: 0.2, MID: 0.8 },
};

// Generatore di sequenze di stage calcolate runtime tramite timer esponenziali
function generateAutonomousMarkovPlan(initialState, totalTestTimeSec) {
  const stages = [];
  let currentState = initialState;
  let elapsed = 0;

  while (elapsed < totalTestTimeSec) {
    // Sorteggio esponenziale del tempo di permanenza (Step II)
    const u = Math.random();
    const sojournTime = Math.max(1, Math.round(-Math.log(u) / currentState.exitRate));
    const effectiveTime = Math.min(sojournTime, totalTestTimeSec - elapsed);

    stages.push({
      target: currentState.lambdaRps,
      duration: `${effectiveTime}s`,
    });

    elapsed += effectiveTime;

    // Selezione stocastica del prossimo stato
    const transitions = TRANSITIONS[currentState.name];
    const rand = Math.random();
    let cumulative = 0;
    let nextState = currentState.name;

    for (const [candidate, prob] of Object.entries(transitions)) {
      cumulative += prob;
      if (rand <= cumulative) {
        nextState = candidate;
        break;
      }
    }

    currentState = STATES[nextState];
  }
  return stages;
}

export const options = {
  scenarios: {
    // Attuatore Poissoniano a ritmo controllato dai timer generati
    mmpp_generator: {
      executor: 'ramping-arrival-rate',
      startRate: STATES.MID.lambdaRps,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: generateAutonomousMarkovPlan(STATES.MID, 120), // Esegue per 2 minuti
    },
  },
};

const TARGET_URL = __ENV.TARGET_URL || 'http://localhost:8080/compute';

export default function () {
  const params = {
    timeout: '10s',
    headers: {
      'Connection': 'close', // Evita il keep-alive sullo stesso pod
    },
  };
  const res = http.get(TARGET_URL, params);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}