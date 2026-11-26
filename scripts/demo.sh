#!/usr/bin/env bash
# End-to-end walkthrough of Lockstep against running services.
#
#   ./scripts/demo.sh
#
# Needs: both services running (see README "Run it locally"), curl, python3.
# Reads ADMIN_USERNAME / ADMIN_PASSWORD from .env if present.
# Optional: LEDGER_URL, FRAUD_URL, DEMO_PAUSE (seconds between steps, default 1).
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi

LEDGER="${LEDGER_URL:-http://localhost:8080}"
FRAUD="${FRAUD_URL:-http://localhost:8081}"
PAUSE="${DEMO_PAUSE:-1}"
: "${ADMIN_USERNAME:?set ADMIN_USERNAME (and ADMIN_PASSWORD) in .env}"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD in .env}"

bold=$'\e[1m'; dim=$'\e[2m'; green=$'\e[32m'; yellow=$'\e[33m'; reset=$'\e[0m'
step() { sleep "$PAUSE"; printf '\n%s== %s ==%s\n' "$bold" "$1" "$reset"; }
note() { printf '%s%s%s\n' "$dim" "$1" "$reset"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
pretty() { python3 -m json.tool; }

post() { # post URL TOKEN BODY [extra curl args...]
  local url=$1 token=$2 body=$3; shift 3
  curl -s -X POST "$url" -H 'Content-Type: application/json' \
       ${token:+-H "Authorization: Bearer $token"} "$@" -d "$body"
}
get() { curl -s "$1" -H "Authorization: Bearer $2"; }
login() { post "$LEDGER/auth/token" "" "{\"username\":\"$1\",\"password\":\"$2\"}" | json "d['access_token']"; }
balance() { get "$LEDGER/accounts/$1" "$ADMIN" | json "'%.2f CAD' % (d['balanceCents'] / 100)"; }

suffix=$(date +%s)
ALICE_NAME="alice-$suffix"; BOB_NAME="bob-$suffix"; PASSWORD="demo-password-$suffix"

step "1. Sign up two customers and log in (JWT)"
post "$LEDGER/auth/register" "" "{\"username\":\"$ALICE_NAME\",\"password\":\"$PASSWORD\"}" | pretty
post "$LEDGER/auth/register" "" "{\"username\":\"$BOB_NAME\",\"password\":\"$PASSWORD\"}" > /dev/null
ALICE=$(login "$ALICE_NAME" "$PASSWORD"); BOB=$(login "$BOB_NAME" "$PASSWORD"); ADMIN=$(login "$ADMIN_USERNAME" "$ADMIN_PASSWORD")
note "Got tokens for $ALICE_NAME, $BOB_NAME and the admin."

step "2. Each opens an account (the owner comes from the token, not the request)"
ALICE_ACC=$(post "$LEDGER/accounts" "$ALICE" "" | json "d['id']")
BOB_ACC=$(post "$LEDGER/accounts" "$BOB" "" | json "d['id']")
SYSTEM_ACC=$(get "$LEDGER/accounts" "$ADMIN" | json "[a['id'] for a in d if a['type'] == 'SYSTEM'][0]")
echo "alice: account $ALICE_ACC   bob: account $BOB_ACC   bank funding (SYSTEM): account $SYSTEM_ACC"

step "3. Admin deposits 1,000.00 into Alice's account: a transfer FROM the SYSTEM account"
post "$LEDGER/transfers" "$ADMIN" "{\"fromAccountId\":$SYSTEM_ACC,\"toAccountId\":$ALICE_ACC,\"amountCents\":100000}" \
     -H "Idempotency-Key: deposit-$suffix" > /dev/null
echo "alice balance: $(balance "$ALICE_ACC")"

step "4. Alice pays Bob 25.00, then RETRIES with the same Idempotency-Key"
KEY="pay-bob-$suffix"
post "$LEDGER/transfers" "$ALICE" "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":2500}" \
     -H "Idempotency-Key: $KEY" | pretty
echo "${yellow}retry:${reset}"
curl -s -i -X POST "$LEDGER/transfers" -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE" \
     -H "Idempotency-Key: $KEY" -d "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":2500}" \
     | grep -iE '^HTTP|^idempotent-replayed'
echo "bob balance: $(balance "$BOB_ACC")   ${green}(paid once, not twice)${reset}"

step "5. Every entry: one debit and one credit per transfer, so balances always add up"
get "$LEDGER/accounts/$ALICE_ACC/entries" "$ALICE" \
  | json "'\n'.join('  transfer %-4s %+10.2f' % (e['transferId'], e['amountCents'] / 100) for e in d)"

step "6. Rules enforced: overdraft, and Bob trying to spend Alice's money"
post "$LEDGER/transfers" "$ALICE" "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":99999999}" \
     -H "Idempotency-Key: overdraft-$suffix" | pretty
post "$LEDGER/transfers" "$BOB" "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":100}" \
     -H "Idempotency-Key: steal-$suffix" | json "'%s %s: %s' % (d['status'], d['title'], d['detail'])"
note "Errors are RFC 9457 problem+json with a correlationId; someone else's account is a 404."

step "7. Suspicious activity: four more small payments, then a big one"
for i in 1 2 3 4; do
  post "$LEDGER/transfers" "$ALICE" "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":2500}" \
       -H "Idempotency-Key: small-$i-$suffix" > /dev/null
done
post "$LEDGER/transfers" "$ALICE" "{\"fromAccountId\":$ALICE_ACC,\"toAccountId\":$BOB_ACC,\"amountCents\":60000}" \
     -H "Idempotency-Key: big-$suffix" | json "'201 Created: transfer %s of %.2f (the ledger never waits for fraud checks)' % (d['id'], d['amountCents'] / 100)"

step "8. Fraud service (separate app, fed by Kafka) flags it asynchronously"
for _ in $(seq 1 30); do
  FLAGS=$(get "$FRAUD/flags?accountId=$ALICE_ACC" "$ADMIN")
  [[ $(echo "$FLAGS" | json "len(d)") -ge 2 ]] && break
  sleep 1
done
echo "$FLAGS" | json "'\n'.join('  %-12s %s' % (f['rule'], f['reason']) for f in d)"

step "9. Admin runs reconciliation: recompute every balance from the entries"
post "$LEDGER/admin/reconciliation-runs" "$ADMIN" "" \
  | json "'status=%s  accounts=%s  transfers=%s  mismatches=%s' % (d['run']['status'], d['run']['accountsChecked'], d['run']['transfersChecked'], d['run']['mismatchCount'])"

step "10. The audit log: who did what, and when (append-only, enforced by the database)"
get "$LEDGER/admin/audit-events?limit=6" "$ADMIN" \
  | json "'\n'.join('  %-26s %-20s %s' % (e['occurredAt'][:19], e['action'], e['actor']) for e in d)"

printf '\n%sDone.%s Swagger UI: %s/swagger-ui.html and %s/swagger-ui.html\n' "$green" "$reset" "$LEDGER" "$FRAUD"
