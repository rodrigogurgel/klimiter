-- Operação atômica de lease (DESIGN-CONCEITUAL.md §4.1).
--
-- Concede parte da capacidade restante da janela e registra o consumo no contador
-- global, que SÓ CRESCE dentro da janela (invariante §9). Aplica o TTL apenas
-- quando a chave ainda não o tem (não reinicia a expiração de uma janela em curso).
--
-- KEYS[1] = chave da janela (prefixo:dimensão:valor:início)
-- ARGV[1] = capacity        (capacidade da política)
-- ARGV[2] = requested       (tamanho solicitado do bloco)
-- ARGV[3] = ttl_ms          (TTL em milissegundos)
--
-- Retorna {granted, free_global}:
--   granted     = quanto foi concedido (0..requested)
--   free_global = capacity - já_arrendado após o incremento (0 = janela cheia)

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local requested = tonumber(ARGV[2])
local ttl_ms    = tonumber(ARGV[3])

local leased  = tonumber(redis.call('GET', key) or '0')
local granted = 0

if leased < capacity then
  local remaining = capacity - leased
  if requested < remaining then
    granted = requested
  else
    granted = remaining
  end
  if granted > 0 then
    leased = redis.call('INCRBY', key, granted)
  end
end

-- PTTL == -1: a chave existe e não tem expiração. Só então aplicamos o TTL.
if redis.call('PTTL', key) == -1 then
  redis.call('PEXPIRE', key, ttl_ms)
end

local free_global = capacity - leased
if free_global < 0 then
  free_global = 0
end

return {granted, free_global}
