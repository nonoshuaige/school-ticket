import { execFileSync } from "node:child_process";

const args = parseArgs(process.argv.slice(2));

const MODE = args.mode || process.env.MODE || "load";
const BASE_URL = args.baseUrl || process.env.BASE_URL || "http://localhost:8080/api/v1";
const PASSWORD = args.password || process.env.PASSWORD || "123456";
const TICKET_ID = numberArg("ticketId", "TICKET_ID", 21);
const QUANTITY = numberArg("quantity", "QUANTITY", 1);
const USER_COUNT = numberArg("userCount", "USER_COUNT", 100);
const REQUESTS = numberArg("requests", "REQUESTS", 500);
const CONCURRENCY = numberArg("concurrency", "CONCURRENCY", 50);
const LOGIN_CONCURRENCY = numberArg("loginConcurrency", "LOGIN_CONCURRENCY", 20);
const ROUNDS = numberArg("rounds", "ROUNDS", 10);
const RECOVER_WAIT_MS = numberArg("recoverWaitMs", "RECOVER_WAIT_MS", 25000);
const TICKET_IDS = listArg("ticketIds", "TICKET_IDS", "21,22,23").map(Number);

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) continue;
    const [rawKey, inlineValue] = arg.slice(2).split("=", 2);
    const key = rawKey.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    parsed[key] = inlineValue ?? argv[++i] ?? "true";
  }
  return parsed;
}

function numberArg(argName, envName, defaultValue) {
  return Number(args[argName] ?? process.env[envName] ?? defaultValue);
}

function listArg(argName, envName, defaultValue) {
  return String(args[argName] ?? process.env[envName] ?? defaultValue)
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function phoneOf(userId) {
  if (userId === 1) return "13800138000";
  return `138${String(userId).padStart(8, "0")}`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function requestJson(method, path, body, token) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const started = performance.now();
  let httpStatus = 0;
  let json = null;
  let text = "";
  try {
    const options = { method, headers };
    if (body !== undefined) options.body = JSON.stringify(body);
    const res = await fetch(`${BASE_URL}${path}`, options);
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

function postJson(path, body, token) {
  return requestJson("POST", path, body, token);
}

function getJson(path, token) {
  return requestJson("GET", path, undefined, token);
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

async function loginUsers(count = USER_COUNT) {
  const users = Array.from({ length: count }, (_, i) => i + 1);
  return runPool(users, LOGIN_CONCURRENCY, async (userId) => {
    const phone = phoneOf(userId);
    const res = await postJson("/auth/login", { phone, password: PASSWORD });
    if (res.json?.code !== 200 || !res.json?.data) {
      throw new Error(`login failed: userId=${userId}, phone=${phone}, response=${JSON.stringify(res.json)}`);
    }
    return { userId, phone, token: res.json.data };
  });
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

function summarize(label, results, totalMs) {
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

  console.log(`\n=== ${label} ===`);
  console.log(`mode: ${MODE}`);
  console.log(`baseUrl: ${BASE_URL}`);
  console.log(`ticketId: ${TICKET_ID}, quantity: ${QUANTITY}`);
  console.log(`users: ${USER_COUNT}, requests: ${results.length}, concurrency: ${CONCURRENCY}`);
  console.log(`durationMs: ${Math.round(totalMs)}`);
  console.log(`throughputRps: ${totalMs > 0 ? Math.round((results.length / totalMs) * 100000) / 100 : 0}`);
  console.log(`successOrders: ${successOrders.length}`);
  console.log(`uniqueSuccessOrders: ${new Set(successOrders).size}`);
  console.log(`latencyMs p50=${percentile(latency, 0.5)} p95=${percentile(latency, 0.95)} p99=${percentile(latency, 0.99)} max=${latency.length ? Math.max(...latency) : 0}`);

  console.log("\nHTTP status:");
  for (const [key, value] of [...byHttp.entries()].sort()) {
    console.log(`  ${key}: ${value}`);
  }

  console.log("\nBusiness result:");
  for (const [key, value] of [...byResult.entries()].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${key}: ${value}`);
  }
  return { successOrders };
}

async function createOrders(users, requests = REQUESTS, concurrency = CONCURRENCY, ticketId = TICKET_ID, quantity = QUANTITY) {
  const requestIndexes = Array.from({ length: requests }, (_, i) => i);
  const started = performance.now();
  const results = await runPool(requestIndexes, concurrency, async (_, index) => {
    const user = users[index % users.length];
    return postJson("/order/create", { ticketId, quantity }, user.token);
  });
  const summary = summarize(`Seckill ${ticketId}`, results, performance.now() - started);
  return { results, ...summary };
}

async function modeLoad() {
  console.log("Logging in users...");
  const users = await loginUsers();
  console.log(`Logged in ${users.length} users.`);
  await createOrders(users);
}

async function modeLimit() {
  const [user] = await loginUsers(1);
  const steps = [
    { ticketId: TICKET_IDS[0], quantity: 2 },
    { ticketId: TICKET_IDS[1] ?? TICKET_IDS[0], quantity: 3 },
    { ticketId: TICKET_IDS[2] ?? TICKET_IDS[0], quantity: 1 },
  ];

  for (const [index, step] of steps.entries()) {
    if (index > 0) await sleep(1100);
    const started = performance.now();
    const result = await postJson("/order/create", step, user.token);
    summarize(`Limit step ${index + 1}: ticket=${step.ticketId}, qty=${step.quantity}`, [result], performance.now() - started);
  }
}

async function modeSoldout() {
  const users = await loginUsers();
  let totalSuccess = 0;
  for (let round = 1; round <= ROUNDS; round++) {
    console.log(`\n--- soldout round ${round}/${ROUNDS} ---`);
    const { successOrders } = await createOrders(users);
    totalSuccess += successOrders.length;
    if (successOrders.length === 0) break;
    await sleep(1000);
  }
  console.log(`\nTotal successful orders before post-soldout probe: ${totalSuccess}`);
  await createOrders(users, REQUESTS, CONCURRENCY);
}

async function modeMqFailover() {
  console.log("Stopping RabbitMQ container...");
  execFileSync("docker", ["stop", "rabbitmq"], { stdio: "inherit" });
  await sleep(4000);

  const users = await loginUsers(Math.min(USER_COUNT, REQUESTS));
  await createOrders(users, REQUESTS, CONCURRENCY);

  console.log("\nPEL while RabbitMQ is down:");
  safeExec("docker", ["exec", "redis", "redis-cli", "XPENDING", "stream:orders", "order-consumers"]);

  console.log("\nStarting RabbitMQ container...");
  execFileSync("docker", ["start", "rabbitmq"], { stdio: "inherit" });
  await sleep(RECOVER_WAIT_MS);

  console.log("\nPEL after RabbitMQ recovery:");
  safeExec("docker", ["exec", "redis", "redis-cli", "XPENDING", "stream:orders", "order-consumers"]);
}

async function waitForOrderVisible(orderNo, token) {
  for (let i = 0; i < 30; i++) {
    const res = await getJson(`/order/${orderNo}`, token);
    const status = res.json?.data?.status;
    if (res.json?.code === 200 && status === 0) return true;
    await sleep(500);
  }
  return false;
}

async function modeCancelRollback() {
  const [user] = await loginUsers(1);
  const created = await postJson("/order/create", { ticketId: TICKET_ID, quantity: QUANTITY }, user.token);
  summarize("Create order before cancel", [created], created.ms);

  const orderNo = created.json?.data?.orderNo;
  if (!orderNo) {
    throw new Error(`create order failed: ${JSON.stringify(created.json)}`);
  }

  const visible = await waitForOrderVisible(orderNo, user.token);
  if (!visible) {
    throw new Error(`order did not become payable before timeout: ${orderNo}`);
  }

  const cancelled = await postJson("/order/cancel", { orderNo }, user.token);
  summarize(`Cancel order ${orderNo}`, [cancelled], cancelled.ms);
  await sleep(12000);
  console.log("Waited 12s for OrderEventLogTask rollback. Run verify.mjs for final reconciliation.");
}

function safeExec(file, fileArgs) {
  try {
    const output = execFileSync(file, fileArgs, { encoding: "utf8" });
    console.log(output.trim());
  } catch (error) {
    console.log((error.stdout || error.stderr || error.message).toString().trim());
  }
}

async function main() {
  switch (MODE) {
    case "load":
      await modeLoad();
      break;
    case "limit":
      await modeLimit();
      break;
    case "soldout":
      await modeSoldout();
      break;
    case "mq-failover":
      await modeMqFailover();
      break;
    case "cancel-rollback":
      await modeCancelRollback();
      break;
    default:
      throw new Error(`unknown mode: ${MODE}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
