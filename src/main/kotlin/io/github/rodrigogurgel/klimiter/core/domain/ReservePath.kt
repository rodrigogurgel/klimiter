package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Nível do caminho de reserva de ALTA prioridade efetivamente tomado (§5), para telemetria:
 * [L1] serviu do crédito local (sem round-trip); [L2] negação rápida por bucket esgotado;
 * [L3] negação rápida por ser impossível (`N > disponível estimado`); [L4] renovação no contador
 * (houve round-trip). A distribuição entre eles é o sinal-chave para tunar o prefetch (§5).
 */
enum class ReservePath { L1, L2, L3, L4 }
