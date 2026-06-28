#!/usr/bin/env bash
# Análise SonarQube local num comando: sobe o SonarQube (profile 'sonar'), garante a senha do
# admin (semeada na 1ª vez via API — a Community Edition não tem env de boot p/ isso), gera um
# token e roda a análise enviando ao Sonar local. Idempotente: pode rodar quantas vezes quiser.
#
#   make sonar-local
#   SONAR_PASSWORD='outra-senha-12+' make sonar-local
#
# Reset (senha em estado desconhecido / upgrade de major do Sonar): `make sonar-reset` apaga os
# volumes e o próximo `make sonar-local` re-semeia do zero.
set -euo pipefail
cd "$(dirname "$0")/.."

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
SONAR_PASSWORD="${SONAR_PASSWORD:-Klimiter-Local-2026}"       # política do SonarQube (ver pré-check abaixo)
TOKEN_NAME="${SONAR_TOKEN_NAME:-klimiter-local}"
GRADLE_TASKS="${SONAR_GRADLE_TASKS:-test detekt jacocoTestReport sonar}"
PROJECT_KEY="${SONAR_PROJECT_KEY:-rodrigogurgel_klimiter}"

# Política de senha do SonarQube 25+: >= 12 chars, com maiúscula, minúscula, dígito e caractere especial.
pw="$SONAR_PASSWORD"
if [ "${#pw}" -lt 12 ] \
  || ! printf '%s' "$pw" | grep -q '[A-Z]' || ! printf '%s' "$pw" | grep -q '[a-z]' \
  || ! printf '%s' "$pw" | grep -q '[0-9]' || ! printf '%s' "$pw" | grep -q '[^A-Za-z0-9]'; then
  echo "!! SONAR_PASSWORD inválida. Exige: >= 12 caracteres, com maiúscula, minúscula, dígito E caractere especial."
  exit 1
fi

# admin/<senha> autentica?  (validate retorna 200 + {"valid":true|false} mesmo com credencial errada)
valid() { curl -fsS -u "admin:$1" "$SONAR_URL/api/authentication/validate" 2>/dev/null | grep -q '"valid":true'; }

echo ">> subindo SonarQube (profile sonar)..."
docker compose --profile sonar up -d sonarqube >/dev/null

echo ">> aguardando status UP em $SONAR_URL (1ª subida leva ~1-2 min)..."
up=false
for _ in $(seq 1 60); do
  if curl -fsS "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; then up=true; break; fi
  sleep 5
done
if [ "$up" != true ]; then
  echo "!! SonarQube não ficou UP a tempo. Últimas linhas:"; docker compose --profile sonar logs --tail=20 sonarqube; exit 1
fi
echo "   UP."

# Semeia a senha do admin (idempotente): 1ª vez troca admin/admin -> SONAR_PASSWORD; depois já está setada.
if valid admin; then
  echo ">> primeira vez: definindo a senha do admin..."
  resp=$(curl -sS -u "admin:admin" -X POST "$SONAR_URL/api/users/change_password" \
    --data-urlencode "login=admin" --data-urlencode "previousPassword=admin" \
    --data-urlencode "password=$SONAR_PASSWORD" -w $'\n%{http_code}')
  code=$(printf '%s' "$resp" | tail -n1)
  if [ "$code" != 204 ] && [ "$code" != 200 ]; then
    echo "!! SonarQube recusou a senha (HTTP $code): $(printf '%s' "$resp" | sed '$d')"; exit 1
  fi
  echo "   senha definida."
elif valid "$SONAR_PASSWORD"; then
  echo ">> senha do admin já configurada."
else
  echo "!! O admin não autentica com 'admin' nem com a SONAR_PASSWORD atual (alguém trocou pela UI)."
  echo "   Use a senha certa em SONAR_PASSWORD, ou rode 'make sonar-reset' para zerar e re-semear."
  exit 1
fi

# Token (revoga e regera p/ ser idempotente) — é o que o gradle usa, independente da senha.
curl -fsS -u "admin:$SONAR_PASSWORD" -X POST "$SONAR_URL/api/user_tokens/revoke" \
  --data-urlencode "name=$TOKEN_NAME" >/dev/null 2>&1 || true
TOKEN=$(curl -fsS -u "admin:$SONAR_PASSWORD" -X POST "$SONAR_URL/api/user_tokens/generate" \
  --data-urlencode "name=$TOKEN_NAME" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "!! falha ao gerar o token."; exit 1; }
echo ">> token '$TOKEN_NAME' gerado."

echo ">> análise: ./gradlew $GRADLE_TASKS"
SONAR_HOST_URL="$SONAR_URL" ./gradlew $GRADLE_TASKS -Dsonar.token="$TOKEN"

echo ""
echo ">> pronto."
echo "   Dashboard : $SONAR_URL/dashboard?id=$PROJECT_KEY"
echo "   Login UI  : admin / $SONAR_PASSWORD"
