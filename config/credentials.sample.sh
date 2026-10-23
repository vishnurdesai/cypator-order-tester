# Credentials template for Git Bash / Linux / macOS - placeholders only, safe to commit.
# 1. cp config/credentials.sample.sh config/<env>.env.local.sh   (*.local.* is git-ignored)
# 2. fill in the values below
# 3. load before a run:   source config/<env>.env.local.sh
# Names match config/rippleuat.sample.json. If the market and trade sessions have their own keys,
# set per-session variables instead and point ws.market.apiKey / ws.trade.apiKey (and .secret) at them.
export OT_WS_SERVER='<server identifier, e.g. TCYPRWSAPIUAT11>'
export OT_WS_API_KEY='<api key>'
export OT_WS_SECRET='<secret key>'
export OT_WS_MARKET_CLIENT='<market session client id>'
export OT_WS_TRADE_CLIENT='<trade session client id>'
echo "Credentials loaded into this shell."
