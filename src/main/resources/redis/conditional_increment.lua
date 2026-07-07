-- Incremento condicional atômico (DESIGN-CONCEITUAL-V2.md §4).
--
-- A ÚNICA operação de escrita do sistema: admite sse `contador + hits <= limiar`
-- e só então incrementa — nunca escreve acima do limiar (over-admission zero por
-- construção) e nega SEM escrever (não infla o contador). O limiar é a capacidade
-- (ALTA) ou a linha de liberação (BAIXA, §6.1). Aplica o TTL apenas quando a chave
-- ainda não o tem (não reinicia a expiração de uma janela em curso).
--
-- KEYS[1] = chave da janela (prefixo:dimensão:valor:início)
-- ARGV[1] = capacity     (capacidade da política)
-- ARGV[2] = hits         (N a consumir; o nó só chama com hits > 0)
-- ARGV[3] = paced        (1 = BAIXA: limiar é a linha; 0 = ALTA: limiar é a capacidade)
-- ARGV[4] = elapsed_ms   (decorrido na janela; usado só quando paced = 1)
-- ARGV[5] = duration_ms  (duração da janela;  usado só quando paced = 1)
-- ARGV[6] = ttl_ms
--
-- Retorna {admitted, counter}:
--   admitted = 1/0
--   counter  = o contador APÓS a operação (inalterado quando negado) — alimenta o
--              snapshot local (§5.1) em admissões E em negações.

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local hits     = tonumber(ARGV[2])
local paced    = tonumber(ARGV[3])
local elapsed  = tonumber(ARGV[4])
local duration = tonumber(ARGV[5])
local ttl_ms   = tonumber(ARGV[6])

local counter = tonumber(redis.call('GET', key) or '0')

-- Limiar (§4): capacidade para ALTA; linha de liberação (§6.1) para BAIXA.
-- Piso flutuante aqui; o nó usa piso inteiro >= este (§6.3, §9).
local threshold = capacity
if paced == 1 and duration > 0 and elapsed < duration then
  threshold = math.floor(capacity * elapsed / duration) + 1
  if threshold > capacity then threshold = capacity end
end

local admitted = 0
if hits > 0 and counter + hits <= threshold then
  admitted = 1
  counter = redis.call('INCRBY', key, hits)
  -- counter == hits ⟺ esta INCRBY criou a chave (o contador só cresce de 0, §9):
  -- TTL só aqui, dispensando o PTTL de toda chamada.
  if counter == hits then
    redis.call('PEXPIRE', key, ttl_ms)
  end
end

return {admitted, counter}
