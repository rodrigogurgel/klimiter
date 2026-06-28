-- Pacing fundido: valida a linha E arrenda o missing num passo atômico
-- (DESIGN-CONCEITUAL.md §6.4), fechando o TOCTOU do probe-separado.
--
-- Admite sse contador + missing <= linha. Só escreve (INCRBY missing) quando
-- admite E missing > 0; caso contrário é somente-leitura. Quando missing = 0
-- degenera numa validação de linha.
--
-- KEYS[1] = chave da janela
-- ARGV[1] = capacity
-- ARGV[2] = missing       (faltante)
-- ARGV[3] = elapsed_ms
-- ARGV[4] = duration_ms
-- ARGV[5] = ttl_ms
--
-- Retorna {admitted, free_global}:
--   admitted    = 1/0
--   free_global = capacity - já_arrendado após o passo (0 = janela cheia)

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local missing  = tonumber(ARGV[2])
local elapsed  = tonumber(ARGV[3])
local duration = tonumber(ARGV[4])
local ttl_ms   = tonumber(ARGV[5])

local leased = tonumber(redis.call('GET', key) or '0')

-- Linha de liberação (§6.2): piso(capacity * elapsed / duration) + 1, limitada à
-- capacidade. Piso flutuante aqui; o nó usa piso inteiro >= este (§6.3, §9).
local line
if duration <= 0 or elapsed >= duration then
  line = capacity
else
  line = math.floor(capacity * elapsed / duration) + 1
  if line > capacity then line = capacity end
end

local admitted = 0
if leased + missing <= line then
  admitted = 1
  if missing > 0 then
    leased = redis.call('INCRBY', key, missing)
    if redis.call('PTTL', key) == -1 then
      redis.call('PEXPIRE', key, ttl_ms)
    end
  end
end

local free_global = capacity - leased
if free_global < 0 then
  free_global = 0
end

return {admitted, free_global}
