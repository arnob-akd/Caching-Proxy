# 🗄️ Caching Proxy

A command-line caching proxy server built in **pure Java** — it sits in front of any HTTP server, forwards your requests to it, and caches the responses so repeated calls don't have to hit the origin server again.

**Project URL:** https://roadmap.sh/projects/caching-server

## 📖 About

### Project Overview

This is a hands-on coding challenge focused on building a simple, fully working CLI caching proxy. The core idea: the proxy sits between a client and an origin server. On the first request for a given path, it forwards the request to the origin, caches the response, and returns it. On every request after that, it returns the cached response instantly — no call to the origin — until the cache is explicitly cleared.

**Core requirements of the challenge:**

- Start the proxy with `caching-proxy --port <number> --origin <url>`
- Forward every incoming request to the given origin server
- Cache the response and add an `X-Cache` header (`HIT` or `MISS`) to every response
- Serve repeated requests straight from the cache instead of hitting the origin again
- Clear the cache on demand with `caching-proxy --clear-cache`

This README documents how this implementation approaches each of those requirements, and what actually happens when you run it.

## ✨ Features

| Flag | Description |
|---|---|
| `--port <number>` | Port the proxy server listens on |
| `--origin <url>` | The server all requests are forwarded to |
| `--clear-cache` | Empties the cache of the currently running server |

- 🎯 `X-Cache: HIT` / `X-Cache: MISS` header on every proxied response
- ⚡ In-memory cache (`ConcurrentHashMap`) — no database, no disk I/O for cached responses
- 🔍 Port auto-detection — the server remembers its own port so `--clear-cache` just works
- 🚫 GET-only proxying — other HTTP methods get a clean `405 Method Not Allowed`
- 🧹 Header filtering — hop-by-hop headers are stripped so the server sets them correctly itself
- ✅ Input validation — missing/invalid port, missing origin, or unknown flags all fail with a helpful message instead of crashing

## 🔁 How It Works

1. You start the proxy, telling it which port to listen on and which server (`origin`) to forward requests to.
2. The server saves its own port to a small file (`~/.caching-proxy-port`) so it can be found later.
3. A client sends a request to the proxy (e.g. `GET /products`).
4. Only `GET` requests are allowed through — anything else gets a `405 Method Not Allowed`.
5. The proxy checks its in-memory cache for that exact path.
6. **Cache hit** → the cached response is returned instantly, with `X-Cache: HIT`.
7. **Cache miss** → the request is forwarded to the origin server, the response is stored in the cache, and returned with `X-Cache: MISS`.
8. Any request made again is now served straight from the cache — no call to the origin.
9. Running `--clear-cache` at any time empties the cache without restarting the server.

## 💻 Sample Run

```
$ java -jar CachingProxy.jar --port 3000 --origin https://dummyjson.com
Caching proxy server started on port 3000
Forwarding to https://dummyjson.com
```

```
$ curl -i http://localhost:3000/products
HTTP/1.1 200 OK
X-Cache: MISS
...

$ curl -i http://localhost:3000/products
HTTP/1.1 200 OK
X-Cache: HIT
...

$ java -jar CachingProxy.jar --clear-cache
Cache cleared

$ curl -i http://localhost:3000/products
HTTP/1.1 200 OK
X-Cache: MISS
...
```

## 🛠 Tech stack

- **Java 11+**
- `com.sun.net.httpserver.HttpServer` — for running the proxy server itself
- `java.net.http.HttpClient` — for forwarding requests to the origin server
- `ConcurrentHashMap` — as the in-memory cache store

No third-party libraries or build tools are required — everything runs on the standard JDK.

## 🚀 Getting Started

### Prerequisites

- JDK 11 or later installed and available on your `PATH`

### Build

```bash
javac CachingProxy.java
jar cfm CachingProxy.jar manifest.txt CachingProxy.class
```

### Run

```bash
java -jar CachingProxy.jar --port <number> --origin <url>
```

**Example:**

```bash
java -jar CachingProxy.jar --port 3000 --origin https://dummyjson.com
```

### (Optional) Run it as a real command

A `caching-proxy.bat` wrapper is included so the tool can be invoked like a normal CLI command instead of `java -jar ...`:

```bat
caching-proxy --port 3000 --origin https://dummyjson.com
```

### Clear the cache

```bash
java -jar CachingProxy.jar --clear-cache
```

The server remembers its own port automatically, so `--clear-cache` doesn't need `--port` repeated. To target a specific port explicitly:

```bash
java -jar CachingProxy.jar --clear-cache --port 3000
```

> Note: only run one proxy instance at a time — since the port is remembered in a single file, running two instances simultaneously means `--clear-cache` (without `--port`) will only target whichever one started most recently.

## 📁 Project Structure

```
.
├── CachingProxy.java     # Main source file — argument parsing, server, proxy & cache logic
├── manifest.txt          # Points the jar to the Main-Class
├── caching-proxy.bat     # Windows wrapper so the tool can be run as a plain command
└── README.md
```

## 🧠 What I Learned

Building this project helped me understand:

- How a reverse/caching proxy works under the hood
- Forwarding HTTP requests and relaying responses (including headers and status codes) with Java's `HttpClient`
- Handling HTTP redirects (origin servers redirecting `http` → `https`)
- Using an in-memory `ConcurrentHashMap` as a simple, thread-safe cache
- Communicating between two separate CLI invocations (`--clear-cache` talking to a running server instance over HTTP)
- Persisting small pieces of state (the running port) to disk so a second CLI invocation can discover it
- Filtering out hop-by-hop headers instead of blindly relaying every header from the origin response
- Validating CLI arguments properly and failing with useful messages instead of crashing
- Packaging a plain Java program into a runnable `.jar` and wrapping it as a native-feeling CLI command

---

Project reference: https://roadmap.sh/projects/caching-server