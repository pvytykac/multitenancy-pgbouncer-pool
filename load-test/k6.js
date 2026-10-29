import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CUSTOMER = __ENV.CUSTOMER || 'acme';
const TENANT = __ENV.TENANT || 't1';
const SERVICE_PATH = __ENV.SERVICE_PATH || '/api/v1/whoami';

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}${SERVICE_PATH}`, {
    headers: {
      'X-Customer-Id': CUSTOMER,
      'X-Tenant-Id': TENANT,
    },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'db present': (r) => {
      try {
        return JSON.parse(r.body).db !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
  sleep(0.1);
}
