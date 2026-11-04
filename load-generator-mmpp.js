import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const replicaResponseTime = new Trend('replica_response_time', true);
const replicaRequestCount = new Counter('replica_requests_total');

// Tassi proporzionati alle risorse del cluster locale (3 pod da 250m CPU)
const STATES = {
  LOW:  { id: 0, name: 'LOW',  lambdaRps: 2,  exitRate: 1 / 15 }, // media 15s a 2 req/s
  MID:  { id: 1, name: 'MID',  lambdaRps: 5,  exitRate: 1 / 20 }, // media 20s a 5 req/s
  HIGH: { id: 2, name: 'HIGH', lambdaRps: 10, exitRate: 1 / 10 }, // media 10s a 10 req/s
};

const TRANSITION_PROBABILITIES = {
  LOW:  { MID: 1.0, HIGH: 0.0 },
  MID:  { LOW: 0.4, HIGH: 0.6 },
  HIGH: { LOW: 0.2, MID: 0.8 },
};

export const options = {
  scenarios: {
    mmpp_traffic: {
      executor: 'ramping-arrival-rate',
      startRate: STATES.MID.lambdaRps,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: buildMarkovStages(STATES.MID, 90), // 90 secondi di test per iniziare
    },
  },
};

function buildMarkovStages(initialState, targetTotalDurationSec) {
  const stages = [];
  let currentState = initialState;
  let elapsed = 0;

  while (elapsed < targetTotalDurationSec) {
    const u = Math.random();
    const duration = Math.max(1, Math.round(-Math.log(u) / currentState.exitRate));
    const effectiveDuration = Math.min(duration, targetTotalDurationSec - elapsed);

    stages.push({
      target: currentState.lambdaRps,
      duration: `${effectiveDuration}s`,
    });

    elapsed += effectiveDuration;

    const transitions = TRANSITION_PROBABILITIES[currentState.name];
    const rand = Math.random();
    let cumulative = 0;
    let nextStateName = currentState.name;

    for (const [candidate, prob] of Object.entries(transitions)) {
      cumulative += prob;
      if (rand <= cumulative) {
        nextStateName = candidate;
        break;
      }
    }
    currentState = STATES[nextStateName];
  }
  return stages;
}

const TARGET_URL = __ENV.TARGET_URL || 'http://localhost:8080/compute';

export default function () {
  // Timeout a 10 secondi: se la replica degrada troppo, fallisce subito senza intasare k6
  const params = { timeout: '10s' };
  const res = http.get(TARGET_URL, params);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (ok && res.body) {
    const match = res.body.match(/\[(.*?)\]/);
    const podName = match ? match[1] : 'unknown-pod';

    replicaResponseTime.add(res.timings.duration, { pod: podName });
    replicaRequestCount.add(1, { pod: podName });
  }
}