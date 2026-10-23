@echo off
rem Credentials template for Command Prompt - placeholders only, safe to commit.
rem 1. copy config\credentials.sample.cmd config\<env>.env.local.cmd   (*.local.* is git-ignored)
rem 2. fill in the values below
rem 3. load before a run:   call config\<env>.env.local.cmd
rem Names match config/rippleuat.sample.json. If the market and trade sessions have their own keys,
rem set per-session variables instead and point ws.market.apiKey / ws.trade.apiKey (and .secret) at them.
set "OT_WS_SERVER=<server identifier, e.g. TCYPRWSAPIUAT11>"
set "OT_WS_API_KEY=<api key>"
set "OT_WS_SECRET=<secret key>"
set "OT_WS_MARKET_CLIENT=<market session client id>"
set "OT_WS_TRADE_CLIENT=<trade session client id>"
echo Credentials loaded into this window.
