# Credentials template for PowerShell - placeholders only, safe to commit.
# 1. Copy-Item config\credentials.sample.ps1 config\<env>.env.local.ps1   (*.local.* is git-ignored)
# 2. fill in the values below
# 3. load before a run (note the leading dot):   . .\config\<env>.env.local.ps1
# Names match config/rippleuat.sample.json. If the market and trade sessions have their own keys,
# set per-session variables instead and point ws.market.apiKey / ws.trade.apiKey (and .secret) at them.
$env:OT_WS_SERVER        = '<server identifier, e.g. TCYPRWSAPIUAT11>'
$env:OT_WS_API_KEY       = '<api key>'
$env:OT_WS_SECRET        = '<secret key>'
$env:OT_WS_MARKET_CLIENT = '<market session client id>'
$env:OT_WS_TRADE_CLIENT  = '<trade session client id>'
Write-Host 'Credentials loaded into this window.'
