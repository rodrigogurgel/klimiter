package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Prioridade do tráfego (§2.1). [HIGH] é limitada apenas pela capacidade da janela (§4); [LOW]
 * passa pelo pacing — só é admitida abaixo da linha de liberação (§6).
 */
enum class Priority { HIGH, LOW }
