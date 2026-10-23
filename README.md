# cypator-order-tester

A command-line order and regression tester for the **Ripple/Cypator WebSocket Taker API** ([docs.liquidity.ripple.com](https://docs.liquidity.ripple.com/)).

It uses the two sessions the API defines. Steps 1–2 run in this order:
1. **Market session:** connect and log on with the market `client` id.
2. **Trade session:** connect and log on with the trade `client` id.
3. **Subscribe** every instrument on the market session (`snapshot`, `SPOT`) and wait for each instrument's first book.
4. **Trade.** For each order, check the instrument's book. Work out the price (fixed, or from the live book), send the order on the trade session, and wait for its final state.
5. **Unsubscribe** and close both sessions. Report PASS/FAIL per order on the console and in a CSV file.

The JSON config holds the instruments, sizes, prices, order types, pre-exec scenarios and order count. Runtime options pick a subset.

### Order types

| Scenario | Wire values (`orderType` / `tif` / `preExec`) | Notes |
|----------|-----------------------------------------------|-------|
| `MARKET_IOC` | `MARKET` / `IOC` / `false` | |
| `MARKET_FOK` | `MARKET` / `FOK` / `false` | |
| `LIMIT_IOC` | `LIMIT` / `IOC` / `false` | |
| `LIMIT_FOK` | `LIMIT` / `FOK` / `false` | |
| `LIMIT_GTC` | `LIMIT` / `GOOD_TILL_CANCEL` / `false` | only where the acceptor enables GTC; canceled again (`cancel_order`) after the ACK |
| `LIMIT_GTD` | `LIMIT` / `GTD` / `false` | not in the WS docs; the order template must carry `${expireTimeMs}` |
| `<TYPE>+PREEXEC` | as above, with `preExec: true` | every type listed in `preExecOrderTypes` is **also** sent as a pre-execution order |

> **These are real orders.** The app refuses to run in two cases unless you pass `--allow-prod`:
> - `environment` is not in `run.nonProdEnvironments` (`uat`, `rippleuat`, `gcp-uat`, `dev`, `local`).
> - The **market or trade URL** host matches `run.prodUrlPatterns`: `ripple.cypator.com` (Ripple prod) or anything with `prod` in it.

## Quick start: Fireblocks on gcp-uat (PowerShell)

> `config\uat-fireblocks.local.json` and `config\uat-fireblocks.env.local.*` are git-ignored. They exist only on the machine they were created on; elsewhere, create them as in the step-by-step guide below.

Everything is already in that folder, so you can run it there yourself. Here are the steps in PowerShell.

1. Go to the repo:

```
cd D:\Cypator-New\Deploy\cypator-order-tester
```

2. Build it. You only need to do this again after code changes. It runs the 78 tests and creates `target\cypator-order-tester.jar`:

```
mvn package
```

3. Load the Fireblocks credentials into the current window. The leading dot matters, and you need to repeat this in every new PowerShell window:

```
. .\config\uat-fireblocks.env.local.ps1
```

4. Check the connection and prices without sending any orders:

```
java -jar target\cypator-order-tester.jar --config config\uat-fireblocks.local.json --check
```

5. Send the orders. Start with a single one:

```
java -jar target\cypator-order-tester.jar --config config\uat-fireblocks.local.json --types LIMIT_FOK --instruments BTC/USD --sides BUY --pre-exec none --max-orders 1
```

Then the full set (20 orders, including pre-exec):

```
java -jar target\cypator-order-tester.jar --config config\uat-fireblocks.local.json
```

## Step by step: running against an environment

Each step below gives the command for **Command Prompt** (`cmd`, prompt `D:\...>`), **PowerShell** (prompt `PS D:\...>`) and **Git Bash**. They differ only in how credentials are loaded (step 5) and in the slash direction.

### 1. What you need first

- **Tools:** JDK 17 and Maven 3.6 or later on the PATH. Check with `java -version` and `mvn -v`.
- **Per session:** the **market** and **trade** session details. For each one you need the URL, client id, API key and secret, plus the server identifier. The server identifier is usually shared, and the API key and secret may be too.
- **Network access:** your machine's **public IP must be on the endpoint's allowlist**. For GKE endpoints that is the `loadBalancerSourceRanges` of the LCA service; change it in Helm values, not with `kubectl patch`, or ArgoCD reverts it.
- **A dedicated test taker if possible.** Each client gets one market and one trade connection, so connecting drops anyone already logged on with the same client ids.

### 2. Open a terminal in the repo and build

```bash
cd D:\Cypator-New\Deploy\cypator-order-tester
```

```bash
mvn package
```

This runs the tests and produces `target/cypator-order-tester.jar`. Rebuild only after code changes.

### 3. Create your environment config (once per environment)

Copy the sample to a git-ignored local file:

```bash
copy config\rippleuat.sample.json config\myenv.local.json
```

(PowerShell: `Copy-Item`; Git Bash: `cp config/rippleuat.sample.json config/myenv.local.json`.)

Then edit `config/myenv.local.json`:
- **`environment`:** for example `uat`, `rippleuat` or `gcp-uat`. Anything else counts as production and needs `--allow-prod`.
- **Sessions:** set `ws.market.url` (`wss://<host>:12007/market`) and `ws.trade.url` (`wss://<host>:12007/trade`), plus `ws.market.client`, `ws.trade.client` and `ws.server`. Each can also come from `${env:...}`.
- **Separate keys per session:** if each session has its own API key and secret, add `"apiKey": "${env:MY_MD_API_KEY}"` and `"secret": "${env:MY_MD_SECRET}"` inside `ws.market`, and the same with trade variables inside `ws.trade`. The shared `ws.apiKey`/`ws.secret` are then not used.
- **`instruments`:** the symbols and sizes to test. `"priceMode": "book"` prices from the live book, so you don't have to guess prices. An instrument the taker can't subscribe to is reported and never traded.
- **Other knobs:** `orderTypes`, `preExecOrderTypes`, `run.maxOrders` and `expect` (see the [config reference](#config-reference)).
- **Slow books:** on a freshly restarted LCA, the books can take a while to fill. Set `ws.market.waitForBookMs` to `30000` there.

### 4. Create your credentials file (once per environment)

Copy the template that matches your terminal. The copy is git-ignored because of `*.local.*`, so secrets never get committed. Then replace the `<...>` placeholders.

| Terminal | Copy | Edit |
|----------|------|------|
| Command Prompt | `copy config\credentials.sample.cmd config\myenv.env.local.cmd` | `config\myenv.env.local.cmd` |
| PowerShell | `Copy-Item config\credentials.sample.ps1 config\myenv.env.local.ps1` | `config\myenv.env.local.ps1` |
| Git Bash | `cp config/credentials.sample.sh config/myenv.env.local.sh` | `config/myenv.env.local.sh` |

The variable names must match the `${env:...}` names used in your config.

### 5. Load the credentials into the terminal window

You need to do this again in every new window; the variables only live in that window.

| Terminal | Command |
|----------|---------|
| Command Prompt | `call config\myenv.env.local.cmd` |
| PowerShell | `. .\config\myenv.env.local.ps1`  (the leading dot matters) |
| Git Bash | `source config/myenv.env.local.sh` |

`. .\...` fails in Command Prompt with *"'.' is not recognized"*. Use `call` there, or type `powershell` first.

### 6. Dry run: check what would be sent (no connection)

```bash
java -jar target\cypator-order-tester.jar --config config\myenv.local.json --dry-run
```

The dry run prints the sessions, the subscriptions and the exact JSON of every order.
- **Missing credentials:** a `WARNING: a real run would stop here` line means a credential is missing (step 5).
- **Order cap:** `capped at maxOrders ... never sent: [...]` lists what the cap leaves out. Raise it with `--max-orders N` (`0` means no cap).

### 7. Check: connect and log on, send no orders

```bash
java -jar target\cypator-order-tester.jar --config config\myenv.local.json --check
```

The check logs on to the market session, then the trade session, subscribes every instrument and prints the books. **It sends no orders.** Success looks like this:

```
[market] logged on
[trade] logged on
  BTC/USD: bid 83319.73 x 0.0982 | ask 83369.06 x 0.0074  (6/6 levels, ...)
CHECK OK: both sessions logged on, every instrument has a book
```

If it fails, see [Troubleshooting](#troubleshooting-a-first-connection) below.

### 8. Send one real order

```bash
java -jar target\cypator-order-tester.jar --config config\myenv.local.json --types LIMIT_FOK --instruments BTC/USD --sides BUY --pre-exec none --max-orders 1
```

Use a symbol from your config. The order line should end in `-> FILLED PASS`.

### 9. Run the full set

```bash
java -jar target\cypator-order-tester.jar --config config\myenv.local.json
```

To narrow a run:
- `--types`, `--instruments`, `--sides` and `--max-orders` pick a subset.
- `--pre-exec none|only` leaves out pre-exec orders, or sends only them.

### 10. Read the results

- **Console:** one line per order, then the results table and the totals (`N orders, N pass, N fail`).
- **CSV:** `reports\run-<runId>-<environment>.csv` has one row per order, including the book at send time and every raw message.
- **Exit code:** `0` all passed, `1` some failed, `2` config or prod-guard error.
- **Server side:** every ClOrdID starts `OT-WS-<runId>-`. Grep it, or the order id, in the LCA log (`/LCA/logs/app-Prod-prod.ProdPlatform.LCANode-<date>.log` in the `lcaws*` pod) to see the order's lifecycle.

### Troubleshooting a first connection

These are the errors we hit when connecting to gcp-uat for the first time.

| Symptom | Likely cause | What to do |
|---------|--------------|------------|
| `connect failed: ... HTTP connect timed out` | Your IP is not on the endpoint's allowlist (the packets are dropped) | Check with `Test-NetConnection <host> -Port 12007`. Add your public IP (`/32`) to the LCA service's `loadBalancerSourceRanges`. |
| `Unexpected HTTP response status code 503` | The LCA pod is (re)starting; envoy is up but the app is not | Wait until the pod is Ready and its load-balancer endpoint is attached, then retry. |
| `logon failed: code 50103: Invalid credentials` | Wrong key/secret/client id, **or** the LCA started with an empty session table (it raced data-agent at startup) | Look for `Error retrieving session info` in the LCA log. If it is there, restart the LCA once data-agent is healthy (see the devops-skills note `ws-acceptor-empty-session-table`). |
| `no market data snapshot received` | No liquidity for that taker and instrument, or the books are still warming up after a restart | Rerun `--check` with `waitForBookMs` 30000. If it is still empty, that instrument has no liquidity in this environment, so leave it out. |
| Pre-exec order `TIMEOUT` after an ACK | The pre-exec maker could not take the order (in gcp-uat, maker 142 `lpgwsmakersim` fails for ETH/USD with `instrument is null`). The failure never reaches the taker. | Check the maker pod's log for `COULD_NOT_SEND_TO_LP`. Leave that instrument out of pre-exec with `"preExecOrderTypes": []`. |
| `... uses an unset environment variable` | Credentials were not loaded in this window | Repeat step 5. |


## Build

You need JDK 17 and Maven 3.6 or later.

```bash
mvn package
```

This runs the tests and produces `target/cypator-order-tester.jar`.

## Run

```bash
java -jar target/cypator-order-tester.jar --config config/rippleuat.local.json --dry-run
java -jar target/cypator-order-tester.jar --config config/rippleuat.local.json
java -jar target/cypator-order-tester.jar --config config/rippleuat.local.json --types LIMIT_FOK --instruments XRP/USD --sides BUY --max-orders 1
java -jar target/cypator-order-tester.jar --config config/rippleuat.local.json --pre-exec only
```

| Option | Meaning |
|--------|---------|
| `--config <file>` | JSON config (required) |
| `--market-url <wss://…>` / `--trade-url <wss://…>` | Override `ws.market.url` / `ws.trade.url` |
| `--no-market-data` | Skip the market session. Only allowed when every price is fixed (`priceMode: config`). |
| `--max-orders N` | Cap for this run. `0` means no cap. |
| `--types LIMIT_FOK,MARKET_IOC` | Send only these order types |
| `--pre-exec include\|none\|only` | Include pre-exec scenarios (the default), leave them out, or send only them |
| `--instruments XRP/USD,USDC/USD` / `--sides BUY` | Send only these instruments / this side |
| `--repeat N` / `--timeout MS` | Send every combination N times / per-order wait for a final state |
| `--check` | Connect and log on both sessions, subscribe, print the books, and send **no orders**. Exit 0 when every instrument has a usable book. Use it as the first contact with a new environment. |
| `--dry-run` | Print the subscriptions and the exact order JSON, without connecting. Also warns about anything a real run would reject (e.g. unset credentials). |
| `--allow-prod` | Required for production, see above |

The exit code is `0` when every order passed and `1` when any order failed. It is `2`, before any socket is opened, in these cases:
- a usage or config error, including unset `${env:}` credentials, bad URLs, an unknown `expect` key, or a missing template field;
- the selection contains no orders;
- the prod guard blocks the run.

## Pricing

`run.priceMode` sets the default, and each instrument can override it with `"priceMode"`.

| Mode | How limit prices are chosen |
|------|-----------------------------|
| `config` | `instruments[].limitPrice.{BUY,SELL}` |
| `book` | From the **live book at send time**: |
| | • IOC/FOK: cross the touch by `run.aggressiveBps` (default 20), BUY above the ask and SELL below the bid, so they can fill |
| | • GTC/GTD: rest `run.passiveBps` (default 100) away from the touch, BUY below the bid and SELL above the ask, so they **rest** and the cancel path is exercised |
| any mode, **pre-exec** | `instruments[].preExecPrice` if set, otherwise the live touch ± `run.preExecBps` (default 0). Pre-exec orders **never** use `limitPrice`, which is usually off-market on purpose. A pre-exec price further than `run.preExecMaxDeviationBps` (default 500 = the 5% price band) from the live touch is **refused before sending**. |

Price rounding:
- Prices are rounded to the book's own decimals, or to `instrument.priceDecimals` if set.
- Rounding always goes towards the safe side: an aggressive price never rounds back inside the spread, and a passive one never rounds towards the touch.

MARKET orders carry a price by default (`run.sendPriceOnMarket: true`), because the API field table marks `price` as required. The price is the live touch, or `limitPrice` without a book, or nothing if neither exists. Set it to `false` to send MARKET orders without a price.

**Market-data gating** (`ws.market.requireBook`, default true):
- **When an order is not sent:** if its instrument has no usable book, it is **not sent** and fails as `ERROR "not sent: …"`. A book is unusable when the subscribe was refused (e.g. `50202 Unknown symbol`), no snapshot arrived within `waitForBookMs`, it is older than `maxBookAgeMs` (when set), or the market session is down.
- **Book-priced orders** always need a usable book.
- **What gets logged:** the books are printed after subscribing and at the end of the run, and every order line shows the book it was priced from.

## Pre-execution orders

Pre-exec orders use `"preExec": true` on the normal order message, as the API docs describe.
- **Choosing them:** list order types in `preExecOrderTypes` (default in the sample: `["LIMIT_FOK"]`). Each one is sent twice: once as a normal order and once as `<TYPE>+PREEXEC`. An instrument can override the list, and `[]` turns pre-exec off for it.
- **Price:** **they execute at the price you send** (seen in rippleuat, July 2026). A price far from market still fills, but it trips the trade-persistor `PriceBandCheckService` ERROR (5% band), and the monitor turns WARN. So the tester never prices them from `limitPrice`:
  - **source:** `preExecPrice` if set, otherwise the live touch (MARKET pre-exec orders are priced too);
  - **guard:** a fixed `preExecPrice` more than `preExecMaxDeviationBps` from the touch is not sent.
- **Types:** GTC/GTD cannot be pre-exec types; that is a config error.
- **In the database** they show up as `taker_orders.bit_masks = 8192` with `source = API`.
- **Pass/fail:** use `expect` keys `PREEXEC` (all pre-exec scenarios) or `LIMIT_FOK+PREEXEC` (one scenario).

## Output

- **Console:**
  - every message sent (`>>`) and received (`<<`), prefixed `[market]` or `[trade]`:
    - the logon is shown masked (`apiKey key***, sign ***`);
    - heartbeats and snapshots (`ws.quietOps`) are not echoed;
  - a **WARNING** line when `maxOrders` cuts the list short, naming the instruments, sides and scenarios that will not be sent;
  - the books, after subscribing and at the end;
  - one line per order: `-> FILLED PASS filled=10 @ 2.41 (8 ms)`;
  - a results table.
- **CSV:** `reports/run-<yyMMddHHmmssSSS>-<environment>.csv` has one row per order:

  | Group | Columns |
  |-------|---------|
  | Order | `seq`, `clOrdId`, `instrument`, `scenario`, `orderType`, `preExec`, `side`, `quantity`, `qtyCurrency` |
  | Price | `price`, `priceSource` (`config` / `book-aggressive` / `book-passive` / `book-touch` / `none`) |
  | Book at send time | `bookBid`, `bookAsk`, `bookAgeMs` |
  | Outcome | `status`, `pass`, `filledQty`, `avgPrice`, `orderId`, `ackMs`, `totalMs`, `text`, `events` |

  - `events` holds every raw message for the order; late messages are marked `LATE`.
  - Latency cells are empty when no reply arrived.

ClOrdIDs have the form `OT-WS-<yyMMddHHmmssSSS>-<seq>`. Use one to find the order in the LCA logs or in `taker_orders.client_order_id`.

### Final states

| State | Meaning |
|-------|---------|
| `FILLED` | Fully filled |
| `PARTIAL_FILL` | Partly filled, remainder canceled (IOC) |
| `CANCELED` | Killed with no fill, or our own cancel of a GTC order |
| `REJECTED` | Refused (non-zero `code`, `op=invalid`, state REJECTED, or a refused cancel) |
| `EXPIRED` | Expired |
| `RESTING` | GTC left on the book on purpose |
| `TIMEOUT` | No final state within `orderTimeoutMs` |
| `ERROR` | Not sent (no book, cannot price), socket closed, template error |

**Pass/fail.** `expect` maps a scenario to the states that count as PASS. Lookup order: `<TYPE>+PREEXEC`, then `PREEXEC`, then `<TYPE>`, then `default`. Keys are case-insensitive, and an unknown key is a config error. Without a match, everything except `REJECTED`, `TIMEOUT` and `ERROR` passes.

**GTC safety.** After the ACK the tester sends `cancel_order` for the order and waits for the `CANCELED` trade.
- **If something goes wrong** (the cancel is refused, the socket drops, or the outcome becomes unknown), the order fails with `WARNING: … may still be ON THE BOOK - check and cancel it manually`.
- **If the trade socket drops between the ACK and the cancel**, the tester reconnects and still sends the cancel.

## Config reference

```jsonc
{
  "environment": "rippleuat",
  "run": { "maxOrders": 10, "repeat": 1, "delayBetweenOrdersMs": 500, "orderTimeoutMs": 10000,
           "sides": ["BUY","SELL"], "reportDir": "reports", "clOrdIdPrefix": "OT", "stopOnFailure": false,
           "priceMode": "config", "aggressiveBps": 20, "passiveBps": 100, "preExecBps": 0,
           "preExecMaxDeviationBps": 500, "sendPriceOnMarket": true, "gtdExpireMinutes": 60 },
  "instruments": [ { "symbol": "USDC/USD", "instrumentId": 76, "sizes": [100, 1000],
                     "priceMode": "book",                 // or "limitPrice": {"BUY": 1.01, "SELL": 0.99}
                     "preExecPrice": {"BUY": 1.0002, "SELL": 0.9998},   // optional fixed pre-exec prices
                     "priceDecimals": 4,                  // optional
                     "quantityIn": ["BASE"],              // "TERM" only if orderTemplate sends ${currency}
                     "orderTypes": [...], "preExecOrderTypes": [...],   // optional overrides
                     "vars": { "account": "ACC1" } } ],  // optional per-instrument template vars
  "orderTypes": ["MARKET_IOC","MARKET_FOK","LIMIT_IOC","LIMIT_FOK"],
  "preExecOrderTypes": ["LIMIT_FOK"],
  "orderVars": { "account": null, "baseChain": null, "termChain": null },   // null = field dropped
  "expect": { "LIMIT_FOK": ["FILLED"], "PREEXEC": ["FILLED"] },
  "ws": {
    "server": "...", "apiKey": "...", "secret": "...",          // shared by both sessions
    "signPath": "/verify", "timestampUnit": "ms", "logonTimeoutMs": 10000,
    "pingIntervalMs": 20000,   // must be 15000-25000 for real hosts (API: every 30 s, max 1 / 15 s)
    "reconnect": true, "headers": {},
    "logonTemplate": {...}, "pingTemplate": {"op": "ping"},
    "market": { "url": "wss://…/market", "client": "...", "subscribeTemplate": {...}, "unsubscribeTemplate": {...},
                "waitForBookMs": 10000, "requireBook": true, "maxBookAgeMs": 0 },
    "trade":  { "url": "wss://…/trade",  "client": "...", "orderTemplate": {...}, "cancelTemplate": {...},
                "cancelRestingOrders": true, "tifMap": {"GTC": "GOOD_TILL_CANCEL"} }
  }
}
```

### Templates (from the API docs)

All messages are JSON templates with `${var}` placeholders. A value that is exactly `"${x}"` keeps its JSON type, so a number stays a number and a boolean stays a boolean. Keys starting with `_` are comments.

| Message | Template (sample) | Variables |
|---------|-------------------|-----------|
| logon (both sessions) | `{"op":"logon","arg":{"apiKey","sign","server","client"},"ts":"${timestamp}"}` | `${apiKey}` `${sign}` `${server}` `${client}` `${timestamp}` (epoch ms, number) |
| ping | `{"op":"ping"}`; the server answers `{"op":"ping"}`, which is **not** answered back | |
| subscribe | `{"op":"subscribe","arg":{instrument, side BOTH, subscriptionId, quantity 0, subscriptionRequestType snapshot, typeBook SPOT, aggBook 0, marketDepth 0},"ts"}` | `${instrument}` `${instrumentId}` `${subscriptionId}` `${nowMs}` |
| unsubscribe | `{"op":"unsubscribe","arg":{subscriptionId, instrument, typeBook}}` | same |
| order | `{"op":"order","arg":{instrument, side, clOrderId, orderType, tif, account, quantity, price, preExec, baseChain, termChain},"ts"}` | `${clOrdId}` `${instrument}` `${side}` `${quantity}` `${price}` `${orderType}` `${tif}` (via `tifMap`) `${preExec}` `${currency}` `${expireTimeMs}` `${nowMs}` + `orderVars` / `vars` |
| cancel | `{"op":"cancel_order","arg":{"clOrderId":"${origClOrdId}","instrument"},"ts"}` | order vars + `${origClOrdId}` `${cancelClOrdId}` `${orderId}` |

`sign = Base64(HMAC-SHA256(timestamp + "/verify", secret))`, where the timestamp is the logon `ts`.

### How replies are handled

**Keep-alive.** Each session pings on a deadline counted from its logon or last ping.
- **During an order:** no ping is sent while an order waits for its answer, and an order goes out at least 1 s after a ping.
- **Catching up:** a ping skipped during an order goes out right after it.
- **Hard deadline:** if an order flight reaches 25 s since the last ping, the ping goes out anyway, so the 30 s drop never happens.

**Reconnects** follow the same order as the start:
1. Market logon, then trade logon.
2. Re-subscribe after a new market socket. A re-subscribed book is **not used** until the new subscription answers.
3. After a trade-only reconnect, wait up to `waitForBookMs` for fresh snapshots, because market data pauses while the trade session is down.

**Market session**
- `subscribe` with `code 0` is the ack, and a non-zero code (e.g. `50202 Unknown symbol`) marks that book as failed.
- Each `snapshot` replaces the book.
  - The best bid and ask are worked out from the levels.
  - Price decimals are the largest scale seen on any level, read exactly from the wire (`2.4100` = 4 decimals).
- An error that names no subscription or instrument is shown, but is not applied to any book.

**Trade session**
- **ACK:** the order echo (`op=order`, `code 0`). With a non-zero code it is `REJECTED`.
- **Fills:** `op=trade` with `state` FILLED / PARTIALLY_FILLED / CANCELED / REJECTED. Both spellings of the fill fields are accepted (`cumQty`/`cumQuantity`, `lastQty`/`lastQuantity`), and `avgPrice` is used when present.
- **Cancels:** `op=cancel_order` with a non-zero code means the cancel was refused, which fails the order with the on-book warning.
- **Errors without a clOrderId** (`op=invalid`) are credited to the one order in flight. That is safe because:
  - no ping is sent while an order is in flight, and an order is not sent within 1 s of a ping;
  - after a TIMEOUT the trade socket is replaced, so a late reply cannot reach the next order;
  - messages from a replaced socket are only recorded, marked `LATE (old socket)`.
- **Unknown values:** add new `state` values to `ws.trade.response.stateMap` and new time-in-force values to `tifMap`. Both are **merged** into the defaults.

## Testing without an environment

```bash
java -cp target/cypator-order-tester.jar com.cypator.ordertester.mock.MockWsServer      # port 19877, apiKey key123, secret sekret
java -jar target/cypator-order-tester.jar --config config/local-mock.json
```

The mock serves `/market` and `/trade` and follows the documented message formats.

**Logon and heartbeat**
- Both sessions log on with HMAC + server + client.
- A ping is answered with `{"op":"ping"}`.

**Market data**
- Snapshots every 200 ms.
- Books: XRP/USD 2.40/2.41, USDC/USD 0.9998/1.0002, RLUSD/USD 0.9997/1.0003, and EMPTY/SYM with no levels.
- Any other symbol gets `50202`.

**Orders**
- Fills follow the book: a LIMIT fills only if it crosses, otherwise IOC/FOK are CANCELED and `GOOD_TILL_CANCEL` rests until `cancel_order`.
- USDC/USD above 500 is thin: IOC partially fills, FOK is killed.
- `preExec` fills at the order price.

**Special symbols**

| Symbol | Behaviour |
|--------|-----------|
| `BAD/SYM` | 50400 |
| `SILENT/SYM` | no answer |
| `DROP/SYM` | the socket is closed |
| `SLOW/SYM` | a late error with no clOrderId |
| `ERRCL/SYM` | `op=reject` with its clOrderId |
| `DELAY/SYM` | FILLED after 600 ms (a long order flight) |

`local-mock.json` ends with 4 deliberate FAILs and exit code 1:
- **USDC/USD 1000 LIMIT_FOK:** killed, ×2 sides.
- **BAD/SYM:** never sent because it has no market data, ×2 sides.

`mvn package` runs the same mock in its tests: 77 tests, including the session order (market → trade → subscribe) at start and on reconnect, pricing, pre-exec guards, keep-alive, reconnect and timeout safety.

## Layout

```
config/                     sample + mock configs (*.local.json is git-ignored)
src/main/java/com/cypator/ordertester/
  Main.java                 CLI, prod guard, session flow, PASS/FAIL
  ScenarioGenerator.java    config -> ordered list of orders (incl. pre-exec scenarios)
  config/                   config model + loader (${env:..}, "_" comments, strict keys)
  ws/WsSession.java         one session: connect, logon/HMAC, ping, reconnect, socket replacement
  ws/TradeClient.java       trade session: orders, ACK/fills/cancels, reply matching
  ws/PendingOrder.java      per-order state: ACK, fills, average price, latency, timeout, on-book warning
  md/MarketDataClient.java  market session: subscribe, snapshots, book usability
  md/PriceResolver.java     config / book pricing (aggressive, passive, touch)
  report/Reporter.java      console table + CSV
  mock/MockWsServer.java    local stand-in for the API
src/test/java/...           unit tests + end-to-end tests against the mock
```
