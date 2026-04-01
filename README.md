# 🚀 ByteCache (MiniRedis)

A high-performance, in-memory key-value database engine built entirely from scratch in **Core Java 17** with *zero external logic dependencies*. ByteCache implements the core architecture that makes real networking databases like Redis fast and safe: thread-safe concurrent reads, active TTL expiration, and $O(1)$ constant-time caching mechanics.

## 🌟 Key Features

* **$O(1)$ LRU Eviction**: Unites a native `HashMap` with a custom-engineered `DoublyLinkedList`. As cache capacity is met, the least recently accessed node is severed natively from the tail in constant time without any list scanning.
* **$O(\log n)$ LFU Eviction Mode**: An optional eviction mode that intelligently purges the least *frequently* accessed elements by leveraging a `PriorityQueue` (Min-Heap).
* **Multi-Threaded Safety (`ReentrantReadWriteLock`)**: Read-heavy cache workloads inherently benefit from unblocked parallel reading while completely serializing modifying `SET/DEL` transactions to prevent race conditions.
* **TTL Key Expiry**: Implements the identical dual-expiry strategy used by Redis: a native `ScheduledExecutorService` actively purges items concurrently at deadline, alongside lazy evaluations during lookup.
* **Publish / Subscribe**: Employs a non-blocking `ConcurrentHashMap` mapping channels to subscriber sockets dynamically, allowing decoupled async message broadcasting via `ExecutorService`.
* **Socket / Custom CLI Parsing**: Implements a native TCP `ServerSocket` instance mapping Redis commands (`SET`, `GET`, `DEL`, `EXPIRE`, `SUBSCRIBE`, `PUBLISH`) across a cached connection pool cleanly.

---

## ⚡ Benchmarks & Throughput
Evaluated on a concurrent Quad-Threaded load containing 1,000,000 operations under a simulated standard 90% Read / 10% Write database workload limit (using `JMH` / custom LoadBenchmark).


| Eviction Mode | Mechanics | Duration (1M Ops) | Operations / Sec |
| :--- | :--- | :--- | :--- |
| **LRU Cache** | `DoublyLinkedList` | 457 ms | **~2,188,183 ops/sec** |
| **LFU Cache** | `Min-Heap` | 935 ms | **~1,069,518 ops/sec** |

---

## 🌐 Try the Live Cloud Demo (AWS EC2)
The database engine is packaged in an `eclipse-temurin:17-jre` Docker image and deployed publicly on an Amazon AWS instance! You can interact strictly via TCP immediately without graphical clients.

**Option A: Query natively via Telnet or Netcat (nc) 🖥️**
Raw, unencrypted caching just like the real protocol:
```bash
# Windows / Linux:
telnet 98.93.91.108 6379 

# macOS (which removed telnet):
nc 98.93.91.108 6379

# Type commands directly, e.g.:
SET cloud_test 100
GET cloud_test
```

**Option B: Use the integrated Java CLI Client ☕**
1. Clone the repo: `git clone https://github.com/bhavyanjain3004/ByteCache.git`
2. Test connection against the EC2 instance natively:
```bash
java -cp target/classes com.bytecache.client.MiniRedisClient --host 98.93.91.108 --port 6379
```

---

## 🏗️ Local Installation

### 1. Build using Maven
```bash
git clone https://github.com/bhavyanjain3004/ByteCache.git
cd ByteCache
mvn clean package -DskipTests
```

### 2. Start the Local Database Engine
By default, the server spins up using LRU policy over port `:6379`.
```bash
java -cp target/classes com.bytecache.server.MiniRedisServer
# Optional Flags: --port 6379 --policy LFU --capacity 1000
```

### 3. Alternatively, deploy locally with Docker 🐳
```bash
docker build -t bytecache .
docker run -d -p 6379:6379 bytecache
```
