import { execFileSync } from "node:child_process";

const args = parseArgs(process.argv.slice(2));

const MYSQL_BIN = args.mysqlBin || process.env.MYSQL_BIN || "D:\\JavaDevelop\\mysql-8.0.28-winx64\\bin\\mysql.exe";
const MYSQL_USER = args.mysqlUser || process.env.MYSQL_USER || "root";
const MYSQL_PASSWORD = args.mysqlPassword || process.env.MYSQL_PASSWORD || "123456";
const MYSQL_DB = args.mysqlDb || process.env.MYSQL_DB || "school_ticket";
const TICKET_ID = Number(args.ticketId || process.env.TICKET_ID || 21);
const EVENT_ID = Number(args.eventId || process.env.EVENT_ID || 8);

const checks = [];

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

function addCheck(name, pass, details = "") {
  checks.push({ name, pass, details });
}

function mysql(sql) {
  return execFileSync(MYSQL_BIN, [
    "-u", MYSQL_USER,
    `-p${MYSQL_PASSWORD}`,
    "-D", MYSQL_DB,
    "--batch",
    "--raw",
    "-N",
    "-e", sql,
  ], { encoding: "utf8" }).trim();
}

function docker(args) {
  return execFileSync("docker", args, { encoding: "utf8" }).trim();
}

function parseSingleRow(output) {
  const line = output.split(/\r?\n/).filter(Boolean)[0] || "";
  return line.split("\t");
}

function parseQueueRows(output) {
  const lines = output.split(/\r?\n/).filter(Boolean);
  const headerIndex = lines.findIndex((line) => line.startsWith("name\t"));
  if (headerIndex < 0) return [];
  const headers = lines[headerIndex].split("\t");
  return lines.slice(headerIndex + 1).map((line) => {
    const cols = line.split("\t");
    return Object.fromEntries(headers.map((header, index) => [header, cols[index]]));
  });
}

function checkMysqlStock() {
  const sql = `
SELECT
  tc.total_quantity,
  tc.remaining_quantity,
  COALESCE(SUM(CASE WHEN o.status IN (0,1,4) THEN o.quantity ELSE 0 END), 0) AS occupied_qty,
  tc.remaining_quantity + COALESCE(SUM(CASE WHEN o.status IN (0,1,4) THEN o.quantity ELSE 0 END), 0) AS calculated_total
FROM ticket_category tc
LEFT JOIN \`order\` o ON o.ticket_id = tc.ticket_id
WHERE tc.ticket_id = ${TICKET_ID}
GROUP BY tc.ticket_id, tc.total_quantity, tc.remaining_quantity;
`;
  const [total, remaining, occupied, calculated] = parseSingleRow(mysql(sql)).map(Number);
  addCheck(
    "mysql stock invariant",
    Number.isFinite(total) && remaining >= 0 && occupied >= 0 && calculated === total,
    `ticket=${TICKET_ID}, total=${total}, remaining=${remaining}, occupied=${occupied}, calculated=${calculated}`,
  );
  return { total, remaining, occupied };
}

function checkRedisStock(mysqlRemaining) {
  const stock = docker(["exec", "redis", "redis-cli", "GET", `ticket:stock:${TICKET_ID}`]);
  const stockNumber = Number(stock);
  addCheck(
    "redis stock matches mysql",
    stock !== "" && stockNumber === mysqlRemaining,
    `ticket=${TICKET_ID}, redis=${stock || "<nil>"}, mysqlRemaining=${mysqlRemaining}`,
  );
}

function checkUserPurchaseLimit() {
  const sql = `
SELECT COALESCE(MAX(qty), 0)
FROM (
  SELECT user_id, SUM(quantity) AS qty
  FROM \`order\`
  WHERE ticket_id IN (SELECT ticket_id FROM ticket_category WHERE event_id = ${EVENT_ID})
    AND status IN (0,1,4)
  GROUP BY user_id
) t;
`;
  const [maxQty] = parseSingleRow(mysql(sql)).map(Number);
  addCheck(
    "mysql event purchase limit",
    Number.isFinite(maxQty) && maxQty <= 5,
    `event=${EVENT_ID}, maxEffectiveQtyPerUser=${maxQty}`,
  );
}

function checkStreamPel() {
  let output = "";
  try {
    output = docker(["exec", "redis", "redis-cli", "XPENDING", "stream:orders", "order-consumers"]);
  } catch (error) {
    addCheck("redis stream pel", false, (error.stdout || error.stderr || error.message).toString().trim());
    return;
  }
  const [pending] = parseSingleRow(output).map(Number);
  addCheck("redis stream pel", pending === 0, `pending=${Number.isFinite(pending) ? pending : "unknown"}`);
}

function checkRabbitQueues() {
  const output = docker(["exec", "rabbitmq", "rabbitmqctl", "list_queues", "name", "messages", "messages_ready", "messages_unacknowledged", "consumers"]);
  const queues = parseQueueRows(output);
  const byName = Object.fromEntries(queues.map((queue) => [queue.name, queue]));
  const createQueue = byName["order.create.queue"];
  const deadQueue = byName["order.dead.queue"];
  addCheck(
    "rabbit order.create empty",
    createQueue && Number(createQueue.messages) === 0 && Number(createQueue.messages_unacknowledged) === 0,
    createQueue ? `messages=${createQueue.messages}, unacked=${createQueue.messages_unacknowledged}, consumers=${createQueue.consumers}` : "queue missing",
  );
  addCheck(
    "rabbit order.dead empty",
    deadQueue && Number(deadQueue.messages) === 0,
    deadQueue ? `messages=${deadQueue.messages}` : "queue missing",
  );
}

function checkLocalMessageTables() {
  const eventRows = mysql("SELECT status, COUNT(*) FROM order_event_log GROUP BY status;");
  const refundRows = mysql("SELECT status, COUNT(*) FROM refund GROUP BY status;");
  const badEventRows = eventRows.split(/\r?\n/).filter((line) => /^0\t|^9\t/.test(line));
  const badRefundRows = refundRows.split(/\r?\n/).filter((line) => /^0\t|^9\t/.test(line));
  addCheck("order_event_log no pending", badEventRows.length === 0, eventRows || "<empty>");
  addCheck("refund no pending", badRefundRows.length === 0, refundRows || "<empty>");
}

function main() {
  let stock = null;
  try {
    stock = checkMysqlStock();
    checkRedisStock(stock.remaining);
    checkUserPurchaseLimit();
    checkStreamPel();
    checkRabbitQueues();
    checkLocalMessageTables();
  } catch (error) {
    addCheck("verify crashed", false, error.message);
  }

  console.log(`\n=== Seckill verify ticket=${TICKET_ID}, event=${EVENT_ID} ===`);
  for (const check of checks) {
    console.log(`${check.pass ? "PASS" : "FAIL"} ${check.name}${check.details ? ` - ${check.details}` : ""}`);
  }

  if (checks.some((check) => !check.pass)) {
    process.exitCode = 1;
  }
}

main();
