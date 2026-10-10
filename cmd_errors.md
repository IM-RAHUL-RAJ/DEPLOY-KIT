# Command Errors and Solutions

## Important Notes

### AWS Configure on EC2 with IAM Role
When SSM'd into an EC2 instance with an IAM instance profile, **`aws configure` is NOT required**. AWS credentials are automatically available through the instance's IAM role. You can verify with:

```bash
aws sts get-caller-identity
```

---

## Error: `sudo: 'dnf': command not found`

### Problem
You attempted to use `dnf` (the package manager for Red Hat/Fedora-based systems) on an Ubuntu system, which uses `apt` as its package manager.

### Error Command
```bash
sudo dnf install -y docker git jq unzip
sudo: 'dnf': command not found
```

### Solution
Use the `apt` package manager instead. Run the following commands:

```bash
sudo apt-get update && sudo apt-get install -y docker.io git jq unzip
```

Then enable and start Docker:

```bash
sudo systemctl enable --now docker
```

### Explanation
- **Ubuntu/Debian**: Uses `apt` or `apt-get` for package management
- **Red Hat/Fedora/CentOS**: Uses `dnf` or `yum` for package management

Always verify which Linux distribution you're using before running installation commands. You can check with:

```bash
lsb_release -a
```
or
```bash
cat /etc/os-release
```

---

## Question: Is `aws configure` required when SSM'd into an EC2 instance?

### Problem
You're SSM'd into an EC2 instance and wondering if you need to run `aws configure` to set up AWS credentials.

### Short Answer
**NO**, `aws configure` is **NOT required** if your EC2 instance has an IAM instance profile attached.

### Solution
When using an EC2 instance with an IAM role, AWS credentials are automatically available. Simply verify they work:

```bash
aws sts get-caller-identity
```

If this command returns your AWS account details (Account, Arn, UserId), you're good to go—skip `aws configure` entirely.

### When to Use `aws configure`
Only use `aws configure` if:
- You're running on a **local machine** (not an EC2 instance)
- You're on an EC2 instance **without an IAM role attached**
- You need to use **different AWS credentials** than the instance role provides

### Benefits of Using IAM Roles (vs. `aws configure`)
✅ Better security—no hardcoded credentials  
✅ Automatic credential rotation  
✅ Fine-grained access control  
✅ Audit trails via CloudTrail

---

## Error: Port is already allocated when running `docker compose up`

### Problem
When starting Docker Compose, you get errors like:
```
Error response from daemon: failed to set up container networking: 
Bind for 0.0.0.0:4200 failed: port is already allocated
```

This happens because **old containers from a previous Docker Compose session are still running**, even though you ran `docker compose down`.

### Root Cause
`docker compose down` only removes containers that belong to the **current** docker-compose.yml stack. If you have:
1. **Old containers from a different compose stack** (different service names)
2. **Containers running from 39+ hours ago** (from a previous session)
3. **Manually started containers** that aren't part of any Compose stack

...they will continue occupying the ports.

### Solution - Two-Step Process

**Step 1:** Remove containers from the current stack
```bash
docker compose down -v
```
(The `-v` flag also removes volumes)

**Step 2:** Force remove old/orphaned containers occupying the ports
```bash
docker ps -a | grep -E "old-service-name|docker-compose|mailpit" | awk '{print $1}' | xargs -r docker rm -f
```
Or remove ALL stopped containers:
```bash
docker container prune -f
```

**Step 3:** Verify ports are free
```bash
docker ps
```

**Step 4:** Start fresh
```bash
docker compose up -d --build
```

### Why This Happened in Your Case
1. First attempt: `docker compose down` → removed only the **etp_*** containers
2. Old containers were still running:
   - `docker-compose-frontend-1` (39 hours old)
   - `docker-compose-postgres-1` (39 hours old)
   - `docker-compose-kafka-1` (39 hours old)
   - `mailpit` container
3. These old containers were still using ports 4200, 5432, 9092
4. Solution: Force-removed them with `docker rm -f`, then started fresh

---

## Error: 401 Unauthorized on Login to Auth Service

### Problem
When trying to log in at `http://localhost:3000/auth/login`, you get:
```
Status 401 Unauthorized
```

### Root Causes
1. **Wrong credentials** - Database has test users with specific passwords
2. **Database schema not loaded** - `schema.sql` wasn't run on first start
3. **Seed data not inserted** - Test users don't exist in the database

### Solution

**Step 1:** Load the database schema (if it's fresh)
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/schema.sql
```

**Step 2:** Insert seed data with test users
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/seed_data.sql
```

**Step 3:** Use the correct test credentials to log in

### Test User Credentials

| Username | Password | Account Status |
|----------|----------|-----------------|
| `demo` | `Capstone@2026` | ✅ ACTIVE |
| `admin` | `Admin@2026` | ✅ ACTIVE |

The demo and admin users are **pre-approved and funded**, so you can use them immediately.

### Exact Commands to Run (Complete Reset)

Run these commands from the project root directory:

```bash
# Step 1: Drop all existing tables and schemas (DESTRUCTIVE)
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/reset.sql
```

```bash
# Step 2: Create fresh schema with all tables
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/schema.sql
```

```bash
# Step 3: Insert seed data with test users
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/seed_data.sql
```

Or run all three in one command:

```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/reset.sql && \
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/schema.sql && \
docker compose exec -T postgres psql -U postgres -d trading_system_db -f - < Databases/PostgreSQL/seed_data.sql
```

### Verify Database is Ready

```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db -c "SELECT username, email, status FROM auth.users;"
```

Expected output:
```
 username |        email         |  status  
----------+----------------------+----------
 demo     | demo@example.com     | ACTIVE
 admin    | admin@example.com    | ACTIVE
 pending  | pending@example.com  | INACTIVE
 awaiting | awaiting@example.com | INACTIVE
 newcomer | newcomer@example.com | INACTIVE
```

### Important Notes
- **reset.sql**: ⚠️ **DESTRUCTIVE** - Drops all schemas and tables. Only use when starting fresh
- **schema.sql**: Creates tables and schemas (run after reset)
- **seed_data.sql**: Inserts test users and sample data (run last)
- **migrations/**: Used for incremental updates to an existing database; don't need to run manually
- Run in this order: **reset → schema → seed_data**

---

## Kafka Setup & Monitoring

### Access Kafka UI
**Kafka UI is now available at:** http://localhost:8888

This allows you to:
- ✅ View all Kafka topics
- ✅ Monitor message flow in real-time
- ✅ Inspect individual messages
- ✅ Check consumer group status
- ✅ View producer activity

### What to Look For After Placing an Order
1. **Login** to http://localhost:3000 with credentials:
   - Username: `demo`
   - Password: `Capstone@2026`

2. **Place an order** through the trading platform

3. **Open Kafka UI** at http://localhost:8888

4. **Check these topics:**
   - `orders` - Orders placed by users
   - `trade-events` - Trading events (fills, rejections, etc.)
   - `market-data` - Market data updates

### About Kafka
**Important Note:** Standard Apache Kafka does NOT come with a built-in UI. The Kafka UI we added is **Provectus Labs Kafka UI**, a third-party tool that runs as a separate Docker container and connects to your Kafka broker.

### Service Ports Reference

| Service | URL | Port | Purpose |
|---------|-----|------|---------|
| Frontend | http://localhost:4200 | 4200 | Trading UI |
| Auth Service | http://localhost:3000 | 3000 | Login & auth API |
| Order Service | http://localhost:8081 | 8081 | Order management API |
| **Kafka UI** | **http://localhost:8888** | **8888** | **Kafka monitoring** ✨ NEW |
| Executor Service | Internal | 8082 | Strategy execution |
| PostgreSQL | Internal | 5432 | Database |
| Kafka Broker | Internal | 29092 | Message broker |

---

## End-to-End System Test - Complete Report

### Test Environment
- **Date:** 2026-10-10
- **All Services:** HEALTHY ✅
- **Database:** Fresh reset with seed data

### Test Curl Commands

#### 1️⃣ Login Endpoint Test
```bash
curl -X POST http://localhost:3000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier": "demo", "password": "Capstone@2026"}'
```
**Result:** ✅ Returns access token

#### 2️⃣ Place Order Test
```bash
curl -X POST http://localhost:8081/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ACCESS_TOKEN>" \
  -d '{
    "accountId": 1,
    "symbol": "AAPL",
    "quantity": 10,
    "price": 150.00,
    "side": "BUY",
    "orderType": "LIMIT",
    "idempotencyKey": "order-test-'$(date +%s%N)'"
  }'
```
**Result:** ✅ Order placed (ID: ORD-2)

#### 3️⃣ Database Verification
```bash
docker compose exec -T postgres psql -U postgres -d trading_system_db \
  -c "SELECT order_id, ticker, side, quantity, price, status FROM trading.orders;"
```
**Result:** ✅ Order found in database

### Complete Request Flow

| Step | Component | Action | Status |
|------|-----------|--------|--------|
| 1 | Frontend | User clicks "Place Order" | ✅ |
| 2 | Auth Service | Validates JWT token | ✅ |
| 3 | Order Service | Receives order request | ✅ |
| 4 | PostgreSQL | Saves order to trading.orders table | ✅ |
| 5 | Order Service | Publishes to Kafka 'orders' topic | ✅ |
| 6 | Kafka | Orders topic receives message | ✅ |
| 7 | Executor Service | Consumes order from Kafka | ✅ |
| 8 | Market Poller | Begins polling AAPL market data | ✅ |
| 9 | Market Data | Requests from Fauxnance API | ⚠️ Rate limited |
| 10 | Strategy Executor | Monitors for fill opportunities | ✅ |

### Verification Results

✅ **Login:** Working - Auth token issued  
✅ **Order Placement:** Working - Order saved to DB  
✅ **Kafka Publishing:** Working - Order consumed by executor  
✅ **Database:** Working - Order persisted correctly  
✅ **Market Polling:** Working - AAPL tracked, polling active  
✅ **Service Health:** All services healthy  
✅ **Kafka UI:** Running at http://localhost:8888  

### Kafka Topics Status
- `orders` - Order messages being published ✅
- `trade-events` - Event stream ready ✅
- `market-data` - Market updates stream ✅

### Monitor Order Execution
1. **Place order** via curl (as shown above)
2. **Check Kafka UI** at http://localhost:8888
3. **View topics** → Select 'orders' → See message flow
4. **Check DB** with:
   ```bash
   docker compose exec -T postgres psql -U postgres -d trading_system_db \
     -c "SELECT * FROM trading.orders WHERE ticker='AAPL';"
   ```
