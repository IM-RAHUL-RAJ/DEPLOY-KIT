# End-to-End Testing Guide

## Quick Start: Run All Tests

```bash
# Option 1: Using individual curl commands
bash /tmp/e2e_test_commands.sh

# Option 2: Manual step-by-step testing
```

## Test 1: Authentication

### Login Request
```bash
curl -X POST http://localhost:3000/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "demo",
    "password": "Capstone@2026"
  }'
```

### Expected Response
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 900,
  "user": {
    "userId": 1,
    "username": "demo",
    "email": "demo@example.com",
    "status": "ACTIVE",
    "accountId": 1
  }
}
```

### Save Token for Next Tests
```bash
# Extract and save token
TOKEN=$(curl -s -X POST http://localhost:3000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier": "demo", "password": "Capstone@2026"}' | jq -r '.accessToken')

echo $TOKEN  # Use this for subsequent API calls
```

---

## Test 2: Place Order

### Place Order Request
```bash
curl -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "accountId": 1,
    "symbol": "AAPL",
    "quantity": 10,
    "price": 150.00,
    "side": "BUY",
    "orderType": "LIMIT",
    "idempotencyKey": "order-'$(date +%s%N)'"
  }'
```

### Expected Response
```json
{
  "orderId": "ORD-2",
  "status": "NEW",
  "message": "Order placed successfully",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 10,
  "price": 150.00,
  "orderType": "LIMIT"
}
```

### Key Points
- ⚠️ `idempotencyKey` is REQUIRED (prevents duplicate orders)
- ✅ Must be between 8-100 characters
- ✅ Can be UUID, timestamp, or any unique string
- ✅ Same key used twice = returns same result (idempotent)

---

## Test 3: Database Verification

### Check Order in Database
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT order_id, ticker, side, quantity, price, status FROM trading.orders ORDER BY order_id DESC LIMIT 5;"
```

### Expected Output
```
 order_id | ticker | side |  quantity   | price  | status
----------+--------+------+-------------+--------+--------
        2 | AAPL   | BUY  | 10.00000000 | 150.00 | NEW
        1 | AAPL   | BUY  | 50.00000000 | 150.00 | FILLED
```

### Check Account Balance
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT account_id, balance, available FROM trading.account WHERE account_id = 1;"
```

### Check Holdings
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT account_id, ticker, quantity, average_price FROM trading.holding;"
```

---

## Test 4: Kafka Message Flow

### Method 1: Check Executor Logs
```bash
# Verify order was consumed by executor
docker compose logs executor-service | grep "Limit order"

# Example: "Limit order 2 queued for the market poller"
```

### Method 2: View in Kafka UI
1. Open http://localhost:8888
2. Click on "etp-kafka" cluster
3. Navigate to "Topics"
4. Select "orders" topic
5. View recent messages

### Expected in Logs
```
Limit order 2 queued for the market poller
```

---

## Test 5: Market Data Polling

### Check Market Poller Status
```bash
docker compose logs executor-service | grep "MarketDataPoller" | tail -5
```

### Expected Output
```
Poll: 2 tracked, 0 quoted in 1 call(s), 0 working order(s) resolved
On demand: 1 new symbol(s) requested, 0 quoted
```

### What This Means
- **2 tracked**: 2 symbols being monitored
- **0 quoted**: No quotes received yet (API rate limit or quota)
- **0 working orders**: No orders matched yet

### Check Market Interest in Database
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT symbol, reason, requested_at, expires_at FROM trading.market_interest;"
```

---

## Test 6: Kafka UI Monitoring

### Access Kafka UI
- **URL**: http://localhost:8888
- **Broker**: kafka:29092
- **Status**: All topics monitored in real-time

### Available Topics
1. **orders** - Order placement events
2. **trade-events** - Trading events (fills, rejections)
3. **market-data** - Market data updates

### Monitor Live Messages
1. Click on topic name
2. Messages appear in real-time
3. Click message to expand and see payload

---

## Test 7: Service Health Check

### Check All Services
```bash
docker compose ps --format "table {{.Service}}\t{{.Status}}"
```

### Health Endpoints
```bash
# Auth Service
curl http://localhost:3000/health

# Order Service
curl http://localhost:8081/actuator/health

# Executor Service (internal)
curl http://localhost:8082/health
```

### Expected Response
```json
{
  "status": "up",  // or "degraded" for non-critical issues
  "dependencies": [
    {"name": "postgres", "status": "ok"},
    {"name": "kafka", "status": "ok"},
    {"name": "fauxnance", "status": "degraded"}  // API rate limit is OK
  ]
}
```

---

## Test Scenarios

### Scenario 1: Happy Path (Complete Order Flow)
```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:3000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier": "demo", "password": "Capstone@2026"}' | jq -r '.accessToken')

# 2. Place order
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"accountId": 1, "symbol": "MSFT", "quantity": 5, "price": 300.00, "side": "BUY", "orderType": "LIMIT", "idempotencyKey": "test-'$(date +%s%N)'"}' | jq .

# 3. Verify in DB
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT * FROM trading.orders WHERE ticker='MSFT' ORDER BY order_id DESC LIMIT 1;"

# 4. Watch Kafka UI
echo "Open http://localhost:8888 and check 'orders' topic"
```

### Scenario 2: Check Idempotency
```bash
# Place order with same idempotency key twice
KEY="idempotent-test-$(date +%s)"

# First call
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"accountId": 1, "symbol": "TSLA", "quantity": 2, "price": 250.00, "side": "BUY", "orderType": "LIMIT", "idempotencyKey": "'$KEY'"}' | jq '.orderId'

# Second call with same key
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"accountId": 1, "symbol": "TSLA", "quantity": 2, "price": 250.00, "side": "BUY", "orderType": "LIMIT", "idempotencyKey": "'$KEY'"}' | jq '.orderId'

# Both should return SAME order ID
```

### Scenario 3: Multiple Orders in Rapid Succession
```bash
for i in {1..5}; do
  curl -s -X POST http://localhost:8081/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"accountId": 1, "symbol": "GOOG", "quantity": 1, "price": 100.00, "side": "BUY", "orderType": "LIMIT", "idempotencyKey": "batch-'$i'-'$(date +%s%N)'"}' | jq '.orderId'
  
  sleep 1
done

# Verify all 5 orders in DB
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT COUNT(*) as order_count FROM trading.orders WHERE ticker='GOOG';"
```

---

## Troubleshooting

### Login Fails
```bash
# Check auth service logs
docker compose logs auth-service | tail -20

# Verify database has users
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT username, status FROM auth.users LIMIT 5;"
```

### Order Placement Fails
```bash
# Check order service logs
docker compose logs order-service | tail -20

# Verify account exists
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT account_id, user_id, balance, status FROM trading.account WHERE account_id = 1;"
```

### Kafka Messages Not Showing
```bash
# Check executor logs
docker compose logs executor-service | tail -30

# Verify Kafka is running
docker compose ps | grep kafka

# Check Kafka topic exists (via Kafka UI)
# http://localhost:8888 → Topics
```

### Market Data Not Updating
```bash
# Check Fauxnance API quota
docker compose logs executor-service | grep -i "fauxnance\|api\|quota"

# Check market interest table
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT symbol, expires_at FROM trading.market_interest WHERE expires_at > now();"
```

---

## Performance Metrics

### Expected Response Times
- **Login**: ~200ms
- **Place Order**: ~500ms  
- **Order Status**: ~100ms
- **Database Queries**: ~50ms

### Kafka Latency
- **Order → Executor**: <1 second
- **Message Persistence**: Immediate

### Market Polling
- **Interval**: 60 seconds (configurable)
- **API Quota**: 2000 requests/day (Fauxnance dev)
- **Symbols Tracked**: Up to 100 concurrent

---

## Advanced Testing

### Load Test Orders
```bash
for i in {1..100}; do
  curl -s -X POST http://localhost:8081/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"accountId": 1, "symbol": "AAPL", "quantity": 1, "price": 150.00, "side": "BUY", "orderType": "LIMIT", "idempotencyKey": "load-'$i'-'$(date +%s%N)'"}' &
done
wait
```

### Monitor Kafka Queue Depth
```bash
# View in Kafka UI - Check "Messages" column for each topic
# http://localhost:8888 → Topics
```

---

## Next Steps

1. ✅ Test login - Verify authentication works
2. ✅ Test order placement - Verify API accepts requests
3. ✅ Test database - Verify data persistence
4. ✅ Test Kafka - Verify message flow
5. ✅ Monitor Kafka UI - Real-time message tracking
6. 🚀 Deploy to AWS - Use EC2 instance IP in PUBLIC_HOST

---

## More Info

- **Docker Compose**: cd /home/ubuntu/Documents/DEPLOY-KIT && docker compose ...
- **Database**: localhost:5432 (postgres/password)
- **Logs**: docker compose logs [service-name]
- **Configuration**: .env file (PostgreSQL, Kafka, JWT keys, etc.)

