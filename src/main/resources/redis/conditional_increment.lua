-- Incremento condicional atômico (DESIGN-CONCEITUAL-V2.md §4).
--
-- A ÚNICA operação de escrita do sistema: admite sse `contador + hits <= limiar`
-- e só então incrementa — nunca escreve acima do limiar (over-admission zero por
-- construção) e nega SEM escrever (não infla o contador). O limiar chega PRONTO
-- do nó (capacidade para ALTA; linha de liberação para BAIXA, §6.1), derivado em
-- aritmética inteira exata num único lugar (ReleaseLine.kt, §6.3) — este script
-- não tem matemática de domínio, então nó e central não podem divergir. Aplica o
-- TTL apenas quando a chave ainda não o tem (não reinicia a expiração de uma
-- janela em curso).
--
-- KEYS[1] = chave da janela (prefixo:dimensão:valor:início)
-- ARGV[1] = threshold (limiar já derivado pelo nó; <= capacidade)
-- ARGV[2] = hits      (N a consumir; o nó só chama com hits > 0)
-- ARGV[3] = ttl_ms
--
-- Retorna {admitted, counter}:
--   admitted = 1/0
--   counter  = o contador APÓS a operação (inalterado quando negado) — alimenta o
--              snapshot local (§5.1) em admissões E em negações.

local key       = KEYS[1]
local threshold = tonumber(ARGV[1])
local hits      = tonumber(ARGV[2])
local ttl_ms    = tonumber(ARGV[3])

local counter = tonumber(redis.call('GET', key) or '0')

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
