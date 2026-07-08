const BASE_URL = process.env.BASE_URL || "http://localhost:8080/api/v1";
const PASSWORD = process.env.PASSWORD || "123456";
const TICKET_ID = Number(process.env.TICKET_ID || 1);
const QUANTITY = Number(process.env.QUANTITY || 1);
const USER_COUNT = Number(process.env.USER_COUNT || 100);
const REQUESTS = Number(process.env.REQUESTS || 500);
const CONCURRENCY = Number(process.env.CONCURRENCY || 50);
const LOGIN_CONCURRENCY = Number(process.env.LOGIN_CONCURRENCY || 20);

function phoneOf(userId) {
  if (userId === 1) return "13800138000";
  return `138${String(userId).padStart(8, "0")}`;
}

async function postJson(path, body, token) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const started = performance.now();
  let httpStatus = 0;
  let json = null;
  let text = "";
  try {
    const res = await fetch(`${BASE_URL}${path}`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
    httpStatus = res.status;
    text = await res.text();
    try {
      json = JSON.parse(text);
    } catch {
      json = { code: "NON_JSON", msg: text.slice(0, 120), data: null };
    }
  } catch (error) {
    json = { code: "NETWORK_ERROR", msg: error.message, data: null };
  }
  return {
    httpStatus,
    json,
    ms: Math.round((performance.now() - started) * 100) / 100,
  };
}

async function runPool(items, concurrency, worker) {
  let cursor = 0;
  const results = [];
  async function loop() {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await worker(items[index], index);
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(concurrency, items.length) }, loop),
  );
  return results;
}

async function loginUsers() {
  const users = Array.from({ length: USER_COUNT }, (_, i) => i + 1);
  const results = await runPool(users, LOGIN_CONCURRENCY, async (userId) => {
    const phone = phoneOf(userId);
    const res = await postJson("/auth/login", { phone, password: PASSWORD });
    if (res.json?.code !== 200 || !res.json?.data) {
      throw new Error(`login failed: userId=${userId}, phone=${phone}, response=${JSON.stringify(res.json)}`);
    }
    return { userId, phone, token: res.json.data };
  });
  return results;
}

function percentile(values, p) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1);
  return sorted[index];
}

function addCount(map, key) {
  map.set(key, (map.get(key) || 0) + 1);
}

function summarize(results, totalMs) {
  const byResult = new Map();
  const byHttp = new Map();
  const latency = [];
  const successOrders = [];

  for (const item of results) {
    addCount(byHttp, String(item.httpStatus));
    const code = item.json?.code ?? "UNKNOWN";
    const msg = item.json?.msg ?? "";
    addCount(byResult, `${code} ${msg}`);
    latency.push(item.ms);
    if (code === 200 && item.json?.data?.orderNo) {
      successOrders.push(item.json.data.orderNo);
    }
  }

  console.log("\n=== Seckill load test summary ===");
  console.log(`baseUrl: ${BASE_URL}`);
  console.log(`ticketId: ${TICKET_ID}, quantity: ${QUANTITY}`);
  console.log(`users: ${USER_COUNT}, requests: ${REQUESTS}, concurrency: ${CONCURRENCY}`);
  console.log(`durationMs: ${Math.round(totalMs)}`);
  console.log(`throughputRps: ${Math.round((REQUESTS / totalMs) * 100000) / 100}`);
  console.log(`successOrders: ${successOrders.length}`);
  console.log(`uniqueSuccessOrders: ${new Set(successOrders).size}`);
  console.log(`latencyMs p50=${percentile(latency, 0.5)} p95=${percentile(latency, 0.95)} p99=${percentile(latency, 0.99)} max=${Math.max(...latency)}`);

  console.log("\nHTTP status:");
  for (const [key, value] of [...byHttp.entries()].sort()) {
    console.log(`  ${key}: ${value}`);
  }

  console.log("\nBusiness result:");
  for (const [key, value] of [...byResult.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${key}: ${value}`);
  }
}

async function main() {
  console.log("Logging in users...");
  const users = await loginUsers();
  console.log(`Logged in ${users.length} users.`);

  const requestIndexes = Array.from({ length: REQUESTS }, (_, i) => i);
  const started = performance.now();
  const results = await runPool(requestIndexes, CONCURRENCY, async (_, index) => {
    const user = users[index % users.length];
    return postJson("/order/create", { ticketId: TICKET_ID, quantity: QUANTITY }, user.token);
  });
  summarize(results, performance.now() - started);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
