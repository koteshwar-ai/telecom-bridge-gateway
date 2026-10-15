"""
Constant 100 TPS against POST /api/v1/charge, stopping after 500,000 requests.

  locust -f loadtest/locustfile.py --headless -u 100 -r 100 \
         --host http://localhost:8080 --run-time 90m --csv loadtest/results

100 users x constant_throughput(1) = 100 requests/second as long as each response takes < 1 s.
500,000 requests / 100 TPS = 5,000 s (~83 min); the run stops by itself once the counter is reached.
"""
import itertools
import random

from locust import HttpUser, constant_throughput, task

TOTAL_REQUESTS = 500_000
_counter = itertools.count(1)


class ChargeUser(HttpUser):
    wait_time = constant_throughput(1)  # 1 request / second / user

    @task
    def charge(self):
        n = next(_counter)
        if n > TOTAL_REQUESTS:
            self.environment.runner.quit()
            return
        payload = {"msisdn": f"9198{random.randint(10_000_000, 99_999_999)}", "units": 1024}
        with self.client.post("/api/v1/charge", json=payload, name="POST /api/v1/charge",
                              catch_response=True) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")
            elif resp.json().get("status") != "APPROVED":
                resp.failure(f"not approved: {resp.text}")
            else:
                resp.success()
