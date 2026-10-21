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

## Setup

1. Copy the sample config: `cp config/rippleuat.sample.json config/rippleuat.local.json`. `*.local.json` is git-ignored.
2. Export the credentials. The config reads them with `${env:NAME}`:
   ```bash
   export OT_WS_SERVER=...          # server identifier, e.g. TRIPPLEWSAPIUAT1 (the wsapiN number does not map to it)
   export OT_WS_API_KEY=...         # login
   export OT_WS_SECRET=...          # password / passphrase - only used to compute the HMAC signature
   export OT_WS_MARKET_CLIENT=...   # client id of the MARKET session (e.g. RIPPLEMC2-style)
   export OT_WS_TRADE_CLIENT=...    # client id of the TRADE session  (e.g. RIPPLETC2-style)
   ```
   Each session can override `apiKey`, `secret` or `server` if its credentials differ.
3. Point `ws.market.url` and `ws.trade.url` at the `wsapiN` host your taker uses: `…:12007/market` and `…:12007/trade`. Your public IP must be whitelisted on that NLB.
4. Run `--dry-run`, then `--check` (sessions + books, no orders), then one order (`--max-orders 1`), then the full set.

Use a **dedicated test taker**. The API allows only one market and one trade connection per client, so a new connection drops the previous one.

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
