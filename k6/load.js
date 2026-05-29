/* k6 load script — single-node capacity reference (NOT a production SLA).
 *
 * Plan §7 Day 5 + ADR-0006.
 *
 * Run via Docker so no host install is needed:
 *
 *   docker run --rm --network host \
 *     -v "$PWD/k6:/scripts" \
 *     -e API_BASE=http://localhost:8090 \
 *     -e API_TOKEN="$(curl -s -X POST http://localhost:8090/auth/token \
 *                       -H 'Content-Type: application/json' \
 *                       -d '{\"subject\":\"loadgen\"}' | jq -r .accessToken)" \
 *     grafana/k6 run /scripts/load.js
 *
 * Targets: p99 < 200ms for /api/v1/transactions on a single API instance.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const submitLatency = new Trend('submit_latency_ms', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: 200,              // 200 req/s — adjust based on hardware
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],            // < 1 % errors
    'http_req_duration{name:submit}': ['p(99)<200'],
    'submit_latency_ms': ['p(95)<150', 'p(99)<200'],
  },
};

const BASE = __ENV.API_BASE || 'http://localhost:8090';
const TOKEN = __ENV.API_TOKEN;
if (!TOKEN) throw new Error('API_TOKEN env var required');

function randomTx() {
  const id = `${Math.random().toString(16).slice(2, 10)}-0000-0000-0000-${Date.now()}`;
  return {
    txId: id.padEnd(36, '0'),
    accountId: `LOAD-${Math.floor(Math.random() * 1000)}`,
    amount: (Math.random() * 20000).toFixed(2),
    currency: 'ZAR',
    mcc: '5411',
    channel: 'WEB',
    country: 'ZA',
    ipCountry: Math.random() < 0.1 ? 'NG' : 'ZA',
    deviceId: `dev-${Math.floor(Math.random() * 100)}`,
    accountAgeDays: Math.floor(Math.random() * 1000),
    timestamp: new Date().toISOString(),
  };
}

export default function () {
  const res = http.post(
    `${BASE}/api/v1/transactions`,
    JSON.stringify(randomTx()),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${TOKEN}`,
        'Idempotency-Key': `k6-${__VU}-${__ITER}`,
      },
      tags: { name: 'submit' },
    }
  );
  submitLatency.add(res.timings.duration);
  check(res, {
    '202 Accepted': r => r.status === 202,
    'has decisionId': r => r.json('decisionId') !== '',
  });
}
